package com.example.application.observation

import com.example.application.execution.ExecutionResult
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.resource.ExecutionOutcome
import com.example.domain.ports.resource.EvidenceRecord
import com.example.domain.ports.resource.EvidenceStorePort
import com.example.domain.ports.resource.ExecutionStateRecord
import com.example.domain.ports.resource.ExecutionStateStorePort
import com.example.domain.ports.resource.ResourceHealthService
import com.example.domain.ports.resource.VerificationOutcomeRecord
import com.example.domain.ports.resource.VerificationOutcomeStorePort
import org.json.JSONObject
import java.util.UUID

/**
 * Normalizes execution results and environment feedback into canonical EnvironmentObservations
 * for CBR-MDP belief updating and case base retention.
 *
 * P0 RESOURCE CONTRACT (APPROVED-BASELINE v2.1, Section I — LOCKED):
 * [observeContractExecution] consumes a contract ExecutionResult and produces the
 * classified observations:
 *
 * | Observation Type     | Produced From                   | Updates                          |
 * |----------------------|---------------------------------|----------------------------------|
 * | ResourceHealthEvent  | transport outcome of ExecutionResult | ResourceHealthService       |
 * | ExecutionStateEvent  | lifecycle of execution itself   | execution state store (Room)     |
 * | EvidenceEvent        | raw output                      | evidence store (Room)            |
 *
 * Transport-outcome classification (RULE RH-1 — health measures TRANSPORT only):
 * - SUCCESS                                    -> transport success.
 * - FAILURE "execution_error: ..."             -> transport failure (a real resource
 *    was reached and the transport call failed).
 * - Resolution-stage failures (resource_unresolvable, runtime_unsupported,
 *   resource_not_usable, adapter_binding_failed, governance_blocked) -> NO health
 *   event: the resource was never reached at the transport layer, so its transport
 *   health must not be poisoned.
 *
 * OBS-1 ordering guarantee: this method is suspend and performs ALL state writes
 * (health + execution + evidence) sequentially BEFORE returning. The closed-loop
 * runner awaits it, so the next decision input is always built AFTER the previous
 * step's state update completed.
 */
class ObservationService(
    // P0 RESOURCE CONTRACT collaborators (composition-root wired; nullable for
    // backward compatibility with the legacy CBR-MDP observation path):
    private val resourceHealthService: ResourceHealthService? = null,
    private val executionStateStore: ExecutionStateStorePort? = null,
    private val evidenceStore: EvidenceStorePort? = null,
    private val verificationOutcomeStore: VerificationOutcomeStorePort? = null
) {

    /** Transport failure prefixes that indicate the resource WAS reached (transport-level failure). */
    private val transportFailurePrefixes = listOf("execution_error:")

    /** Resolution-stage failure codes: resource never reached — no transport outcome. */
    private val resolutionStageCodes = setOf(
        "resource_unresolvable",
        "runtime_unsupported",
        "resource_not_usable",
        "adapter_binding_failed",
        "governance_blocked"
    )

    /**
     * Constructs a normalized EnvironmentObservation from an executed action and its ExecutionResult.
     */
    fun createObservation(
        action: DecisionAction,
        result: ExecutionResult,
        stepIndex: Int = 0
    ): EnvironmentObservation {
        val reward = calculateFeedbackReward(action, result)
        val summary = if (result.outputText.isNotBlank()) {
            result.outputText.take(150)
        } else if (result.errorDescription != null) {
            "فشل: ${result.errorDescription}"
        } else {
            "اكتمل تنفيذ الإجراء ${action.type.displayName}"
        }

        return EnvironmentObservation(
            action = action,
            isSuccess = result.isSuccess,
            actualLatencyMs = result.latencyMs,
            tokensConsumed = result.tokensConsumed,
            errorDescription = result.errorDescription,
            outputSummary = summary,
            outputData = result.outputData,
            stepIndex = stepIndex,
            feedbackReward = reward,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun calculateFeedbackReward(
        action: DecisionAction,
        result: ExecutionResult
    ): Float {
        if (!result.isSuccess) {
            return if (result.errorDescription?.contains("حظر", ignoreCase = true) == true ||
                result.errorDescription?.contains("رفض", ignoreCase = true) == true) {
                -0.9f
            } else {
                -0.6f
            }
        }

        var reward = 1.0f

        // Penalize degradation
        if (result.isDegraded) {
            reward -= 0.35f
        }

        // Small reward penalty for high latency
        if (result.latencyMs > 4000L) {
            reward -= 0.1f
        }

        // Small reward penalty for huge token consumption
        if (result.tokensConsumed > 4000) {
            reward -= 0.1f
        }

        return reward.coerceIn(-1.0f, 1.0f)
    }

    // =========================================================================
    // P0 RESOURCE CONTRACT — Observation -> State (Section I, LOCKED)
    // =========================================================================

    /**
     * Classified observation of a contract execution. Performs ALL state updates
     * (health + execution state + evidence) before returning (OBS-1).
     */
    suspend fun observeContractExecution(
        decision: com.example.domain.core.resource.DecisionRecord,
        result: com.example.domain.core.resource.ExecutionResult
    ): ContractObservation {
        var healthUpdated = false
        var healthWasTransportSuccess = false

        // 1. ResourceHealthEvent — transport outcome only (RULE RH-1).
        val transportFailureCode = result.transportError?.substringBefore(':')?.trim()
        val isTransportFailure = result.outcome == ExecutionOutcome.FAILURE &&
            transportFailureCode != null &&
            transportFailureCode in transportFailurePrefixes.map { it.removeSuffix(":") }
        val isResolutionFailure = result.outcome == ExecutionOutcome.FAILURE &&
            transportFailureCode in resolutionStageCodes

        if (result.outcome == ExecutionOutcome.SUCCESS && resourceHealthService != null) {
            resourceHealthService.recordTransportSuccess(result.executedResourceId, result.latencyMs)
            healthUpdated = true
            healthWasTransportSuccess = true
        } else if (isTransportFailure && resourceHealthService != null) {
            val reason = result.transportError?.substringAfter(':', "").orEmpty().ifBlank { "transport failure" }
            val isTimeout = reason.contains("timeout", ignoreCase = true) || reason.contains("timed out", ignoreCase = true)
            resourceHealthService.recordTransportFailure(result.executedResourceId, reason, isTimeout)
            healthUpdated = true
        }
        // Resolution-stage failures intentionally produce NO health event.

        // 2. ExecutionStateEvent -> execution state store (Room).
        executionStateStore?.save(
            ExecutionStateRecord(
                executionId = "exec_${UUID.randomUUID()}",
                decisionId = decision.decisionId,
                taskId = decision.taskId,
                stepId = decision.stepId,
                resourceId = result.executedResourceId.value,
                outcome = result.outcome.name,
                transportError = result.transportError,
                latencyMs = result.latencyMs,
                timestamp = result.timestamp
            )
        )

        // 3. EvidenceEvent -> evidence store (Room). Raw output keys become evidence keys.
        val outputMap = result.output as? Map<*, *>
        val evidenceKeys = outputMap?.keys?.mapNotNull { it?.toString() } ?: emptyList()
        if (evidenceStore != null && outputMap != null) {
            val payload = JSONObject()
            for ((key, value) in outputMap) {
                payload.put(key.toString(), value?.toString() ?: "")
            }
            evidenceStore.save(
                EvidenceRecord(
                    evidenceId = "evd_${UUID.randomUUID()}",
                    taskId = decision.taskId,
                    stepId = decision.stepId,
                    decisionId = decision.decisionId,
                    resourceId = result.executedResourceId.value,
                    evidenceKeys = evidenceKeys,
                    summary = (outputMap["synthesizedText"] ?: outputMap["searchResults"] ?: outputMap["embeddingVector"] ?: "")
                        .toString().take(200),
                    payloadJson = payload.toString(),
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        return ContractObservation(
            decisionId = decision.decisionId,
            taskId = decision.taskId,
            stepId = decision.stepId,
            resourceId = result.executedResourceId.value,
            outcome = result.outcome.name,
            transportError = result.transportError,
            isResolutionStageFailure = isResolutionFailure,
            healthUpdated = healthUpdated,
            healthWasTransportSuccess = healthWasTransportSuccess,
            evidenceKeys = evidenceKeys
        )
    }

    /**
     * VerificationOutcome persistence (Section I: verified/rejected per stepId).
     * Called by the loop runner AFTER verification evaluates the output — loop
     * control consumes it (verified -> proceed; rejected -> re-decide).
     */
    suspend fun recordVerificationOutcome(
        taskId: String,
        stepId: String,
        verified: Boolean,
        confidence: Double,
        summary: String
    ) {
        verificationOutcomeStore?.save(
            VerificationOutcomeRecord(
                stepId = stepId,
                taskId = taskId,
                verified = verified,
                confidence = confidence,
                summary = summary,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}

/** Classified observation result of a contract execution (Section I). */
data class ContractObservation(
    val decisionId: String,
    val taskId: String,
    val stepId: String,
    val resourceId: String,
    val outcome: String,
    val transportError: String?,
    /** True when the failure happened before any transport contact (no health impact). */
    val isResolutionStageFailure: Boolean,
    /** True when a ResourceHealthEvent updated the health track. */
    val healthUpdated: Boolean,
    /** True when the health event was a transport SUCCESS. */
    val healthWasTransportSuccess: Boolean,
    val evidenceKeys: List<String>
)
