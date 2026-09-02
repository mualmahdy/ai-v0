package com.example.application.orchestration

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionService
import com.example.application.observation.ObservationService
import com.example.domain.core.capability.CapabilityEvidenceRegistry
import com.example.domain.core.resource.DecisionRecord
import com.example.domain.core.resource.ExecutionOutcome
import com.example.domain.core.resource.ResourceExecutionInput

/**
 * P0.8 — Closed Loop Runner (APPROVED-BASELINE v2.1, Section I — LOCKED).
 *
 * Implements the LOCKED closed loop for contract decisions:
 *
 *   DECIDE -> EXECUTE (exactly selectedResourceId) -> OBSERVE
 *          -> VERIFY (correctness of output vs. acceptance criteria)
 *          -> STATE UPDATE (health + execution + evidence + verification outcome)
 *          -> RE-DECIDE (uses updated state)
 *
 * Contract compliance:
 * - OBS-1 (test 15): the next decision is evaluated ONLY after
 *   [ObservationService.observeContractExecution] — which performs ALL state
 *   writes sequentially — has returned. Ordering is structural (suspend call chain).
 * - RULE FB-2: the failure flow is ALWAYS Decision(A) -> Execution(A) ->
 *   Observation(A failed) -> State Update -> Decision(B) -> Execution(B). There is
 *   NO path where Execution(A) silently executes Resource B.
 * - RULE FB-1: FallbackPolicy is a planning hint only. On failure this runner
 *   re-DECIDES (new DecisionRecord, decisionVersion incremented — test 10); it
 *   never re-executes the failed decision against a substituted resource.
 * - Verification (test 15) is separate from transport health (RULE RH-1): a
 *   transport-successful output can still be verification-rejected.
 * - AgentOrchestrator remains the owner of the LEGACY CBR-MDP task loop (Section M
 *   — Preserve: "AgentOrchestrator's loop ownership (no decomposition in P0)");
 *   this runner is the P0 contract loop for DecisionRecord-based steps and does
 *   not decompose or modify the legacy orchestrator.
 */
class ClosedLoopTaskRunner(
    private val decisionService: DecisionService,
    private val executionService: ExecutionService,
    private val observationService: ObservationService
) {

    /** Verification result for a single loop step (correctness — separate from health). */
    data class StepVerification(
        val verified: Boolean,
        val confidence: Double,
        val missingEvidenceKeys: List<String>,
        val summary: String
    )

    data class LoopAttempt(
        val decision: DecisionRecord,
        val executedResourceId: String,
        val outcome: ExecutionOutcome,
        val transportError: String?,
        val verification: StepVerification?
    )

    data class LoopResult(
        val taskId: String,
        val stepId: String,
        val succeeded: Boolean,
        val attempts: List<LoopAttempt>,
        val finalOutput: Any?,
        val finalDecisionVersion: Int,
        val noCapableResource: Boolean,
        val summary: String
    )

    /**
     * Runs the closed loop for one step requiring the given capabilities.
     *
     * @param requiredCapabilityIds CapabilityType.code values required by the step.
     * @param input the runtime execution input (prompt/query/embedding texts).
     * @param maxAttempts maximum decide->execute cycles (re-decisions on failure).
     */
    suspend fun runStep(
        taskId: String,
        stepId: String,
        requiredCapabilityIds: Set<String>,
        input: ResourceExecutionInput,
        maxAttempts: Int = 3
    ): LoopResult {
        val attempts = mutableListOf<LoopAttempt>()
        var lastOutput: Any? = null

        repeat(maxAttempts) { attemptIndex ->
            // 1. DECIDE — consumes state INCLUDING the previous attempt's observation,
            //    because observation (step 3) completed all state writes before returning
            //    and this call happens strictly after it (OBS-1 ordering guarantee).
            val decision = decisionService.evaluateWithRecord(
                taskId = taskId,
                stepId = stepId,
                requiredCapabilityIds = requiredCapabilityIds
            ) ?: return LoopResult(
                taskId = taskId,
                stepId = stepId,
                succeeded = false,
                attempts = attempts,
                finalOutput = null,
                finalDecisionVersion = attempts.lastOrNull()?.decision?.decisionVersion ?: 0,
                noCapableResource = true,
                summary = "لا يوجد مورد قابل للاستخدام يلبّي القدرات المطلوبة: $requiredCapabilityIds"
            )

            // 2. EXECUTE — exactly decision.selectedResourceId.
            val result = executionService.execute(decision, input)
            lastOutput = result.output

            // 3 + 4 + 5. OBSERVE -> VERIFY -> STATE UPDATE — ALL state writes complete
            // before this returns (suspend chain = OBS-1 structural guarantee).
            val observation = observationService.observeContractExecution(decision, result)

            // Verification (correctness) — separate track from transport health.
            val verification = verifyContractStep(result, requiredCapabilityIds)
            observationService.recordVerificationOutcome(
                taskId = taskId,
                stepId = stepId,
                verified = verification.verified,
                confidence = verification.confidence,
                summary = verification.summary
            )

            attempts.add(
                LoopAttempt(
                    decision = decision,
                    executedResourceId = result.executedResourceId.value,
                    outcome = result.outcome,
                    transportError = result.transportError,
                    verification = verification
                )
            )

            // 6. Loop control (VerificationService output feeds loop control, Section I).
            if (result.outcome == ExecutionOutcome.SUCCESS && verification.verified) {
                return LoopResult(
                    taskId = taskId,
                    stepId = stepId,
                    succeeded = true,
                    attempts = attempts,
                    finalOutput = result.output,
                    finalDecisionVersion = decision.decisionVersion,
                    noCapableResource = false,
                    summary = "اكتملت الخطوة بنجاح عبر ${result.executedResourceId.value} (محاولة ${attemptIndex + 1})."
                )
            }
            // RULE FB-2: failure -> observation -> state update (done above) -> RE-DECIDE.
            // The next iteration re-decides with the updated health/evidence state; the
            // failed resource's score degrades through its health track, so the next
            // decision may select a different resource — via a NEW DecisionRecord only.
        }

        return LoopResult(
            taskId = taskId,
            stepId = stepId,
            succeeded = false,
            attempts = attempts,
            finalOutput = lastOutput,
            finalDecisionVersion = attempts.lastOrNull()?.decision?.decisionVersion ?: 0,
            noCapableResource = false,
            summary = "استُنفدت المحاولات ($maxAttempts) دون تحقق كامل من الخطوة $stepId."
        )
    }

    /**
     * Step-correctness verification (VerificationService role): the transport result
     * must carry the Evidence Contract keys of the required capabilities (Rule 13/14
     * semantics preserved). This is SEPARATE from transport health (RULE RH-1):
     * HTTP-200-style transport success with semantically unusable output is verified=false
     * while the resource remains transport-healthy.
     */
    private fun verifyContractStep(
        result: com.example.domain.core.resource.ExecutionResult,
        requiredCapabilityIds: Set<String>
    ): StepVerification {
        if (result.outcome != ExecutionOutcome.SUCCESS) {
            return StepVerification(
                verified = false,
                confidence = 0.0,
                missingEvidenceKeys = emptyList(),
                summary = "لم يتم التحقق: فشل تنفيذ على مستوى النقل (${result.transportError})."
            )
        }

        val outputMap = result.output as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val missing = mutableListOf<String>()
        var satisfied = 0
        var total = 0

        for (capabilityId in requiredCapabilityIds) {
            val capabilityType = com.example.domain.core.capability.CapabilityType.fromCode(capabilityId)
                ?: continue
            val contract = CapabilityEvidenceRegistry.getContract(capabilityType)
            total++
            val hasEvidence = contract.requiredEvidenceKeys.any { key ->
                val value = outputMap[key]
                when (value) {
                    null -> false
                    is String -> value.isNotBlank()
                    is Collection<*> -> value.isNotEmpty()
                    is Map<*, *> -> value.isNotEmpty()
                    is Boolean -> value
                    is List<*> -> value.isNotEmpty()
                    is FloatArray -> value.isNotEmpty()
                    else -> true
                }
            }
            if (hasEvidence) {
                satisfied++
            } else {
                missing.addAll(contract.requiredEvidenceKeys)
            }
        }

        val verified = total == 0 || (satisfied == total && missing.isEmpty())
        val confidence = if (total == 0) 1.0 else satisfied.toDouble() / total
        return StepVerification(
            verified = verified,
            confidence = confidence,
            missingEvidenceKeys = missing,
            summary = if (verified) {
                "تم التحقق: أدلة القدرات المطلوبة مستوفاة ($satisfied/$total)."
            } else {
                "فشل التحقق: أدلة مفقودة $missing (الصحة على مستوى النقل منفصلة عن صحة المخرجات — RULE RH-1)."
            }
        )
    }
}
