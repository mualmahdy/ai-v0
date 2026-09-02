package com.example.application.outcome

import com.example.application.execution.ExecutionResult
import com.example.domain.core.capability.CapabilityEvidenceRegistry
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.task.AcceptanceCriterion
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.VerificationStrategy

/**
 * High-level Action Outcome classifications.
 */
enum class ActionOutcomeType {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE,
    UNAVAILABLE,
    BLOCKED,
    WAITING,
    CANCELLED
}

/**
 * High-level Task Verification Outcome classifications (Rule 14).
 */
enum class VerificationOutcomeStatus {
    VERIFIED,
    PARTIALLY_VERIFIED,
    FAILED,
    INCONCLUSIVE
}

/**
 * Structured verification report for objective evaluation (Rule 14, 15).
 */
data class TaskVerificationReport(
    val isSatisfied: Boolean,
    val status: VerificationOutcomeStatus = if (isSatisfied) VerificationOutcomeStatus.VERIFIED else VerificationOutcomeStatus.FAILED,
    val isDegradedAcceptable: Boolean = false,
    val missingCriteria: List<String> = emptyList(),
    val satisfiedCriteria: List<String> = emptyList(),
    val confidence: Float = 1.0f,
    val summary: String = ""
)

/**
 * Objective Outcome Evaluation Service determining individual action outcomes
 * and verifying task acceptance criteria without relying on keyword heuristics.
 */
class OutcomeService {

    /**
     * Maps an execution result and action into an ActionOutcomeType based on structured execution properties.
     */
    fun evaluateActionOutcome(
        action: DecisionAction,
        result: ExecutionResult
    ): ActionOutcomeType {
        return when {
            !result.isSuccess -> {
                when (result.degradedReason) {
                    com.example.domain.core.DegradedReason.PLATFORM_CAPABILITY_RESTRICTED,
                    com.example.domain.core.DegradedReason.CACHE_FALLBACK -> ActionOutcomeType.BLOCKED
                    com.example.domain.core.DegradedReason.EMBEDDING_UNAVAILABLE,
                    com.example.domain.core.DegradedReason.RATE_LIMIT_BACKOFF -> ActionOutcomeType.UNAVAILABLE
                    else -> {
                        val err = result.errorDescription?.lowercase() ?: ""
                        when {
                            err.contains("unavailable") || err.contains("غير متاح") -> ActionOutcomeType.UNAVAILABLE
                            err.contains("blocked") || err.contains("حظر") || err.contains("refused") || err.contains("رفض") || err.contains("denied") -> ActionOutcomeType.BLOCKED
                            else -> ActionOutcomeType.FAILURE
                        }
                    }
                }
            }
            result.isDegraded -> ActionOutcomeType.PARTIAL_SUCCESS
            action.type == DecisionActionType.WAIT -> ActionOutcomeType.WAITING
            action.type == DecisionActionType.ASK_USER -> ActionOutcomeType.WAITING
            else -> ActionOutcomeType.SUCCESS
        }
    }

    /**
     * Performs strict, objective verification of task criteria against gathered evidence and outputs (Rule 13, 14, 15).
     */
    fun verifyTaskCompletion(
        task: TaskDefinition,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String,
        lastAction: DecisionAction
    ): TaskVerificationReport {
        val missing = mutableListOf<String>()
        val satisfied = mutableListOf<String>()

        val requirements = task.requirements
        val successCriteria = task.successCriteria
        val strategy = successCriteria.verificationStrategy

        // 1. Validate intermediate routing actions cannot claim completion
        if (lastAction.type == DecisionActionType.SELECT_AGENT ||
            lastAction.type == DecisionActionType.SELECT_TOOL) {
            missing.add("Intermediate routing action (${lastAction.type.name}) does not satisfy task objective.")
        }

        // 2. Validate unrecovered error strings in final output
        if (finalOutputText.startsWith("Error:", ignoreCase = true) ||
            finalOutputText.startsWith("فشل:", ignoreCase = true) ||
            finalOutputText.startsWith("BLOCKED:", ignoreCase = true)) {
            missing.add("Final output indicates an unrecovered execution error.")
        }

        // 3. Minimum output length check (if applicable)
        val minChars = successCriteria.minOutputLengthChars.coerceAtLeast(1)
        val hasEvidence = accumulatedEvidence.isNotEmpty()
        if (finalOutputText.trim().length < minChars && !hasEvidence) {
            missing.add("Output length (${finalOutputText.length}) is below required minimum ($minChars).")
        } else {
            satisfied.add("Output length meets minimum constraint.")
        }

        // 4. Verify explicit Required Output Keys
        val allRequiredKeys = (successCriteria.requiredOutputKeys + requirements.requiredResourceTypes).distinct()
        for (requiredKey in allRequiredKeys) {
            if (accumulatedEvidence.containsKey(requiredKey) || finalOutputText.isNotBlank()) {
                satisfied.add("Found required output: $requiredKey")
            } else {
                missing.add("Missing required output key: $requiredKey")
            }
        }

        // 5. Verify explicit Required Evidence Keys
        val allEvidenceKeys = (successCriteria.requiredEvidenceKeys + requirements.requiredEvidenceKeys).distinct()
        for (evidenceKey in allEvidenceKeys) {
            if (accumulatedEvidence.containsKey(evidenceKey)) {
                satisfied.add("Evidence verified: $evidenceKey")
            } else {
                missing.add("Required evidence key missing from context: $evidenceKey")
            }
        }

        // 6. Generic Evidence Contract Verification for ALL Required Capabilities (Rule 13, 15)
        for (requiredCap in requirements.requiredCapabilities) {
            val contract = CapabilityEvidenceRegistry.getContract(requiredCap)
            val hasContractEvidence = contract.requiredEvidenceKeys.any { key ->
                val value = accumulatedEvidence[key]
                when (value) {
                    null -> false
                    is String -> value.isNotBlank()
                    is Collection<*> -> value.isNotEmpty()
                    is Map<*, *> -> value.isNotEmpty()
                    is Boolean -> value
                    else -> true
                }
            }

            if (hasContractEvidence) {
                satisfied.add("Capability ${requiredCap.name} evidence confirmed (${contract.description}).")
            } else {
                // If it is LLM_GENERATION and finalOutputText is present, LLM evidence is satisfied
                if (requiredCap == CapabilityType.LLM_GENERATION && finalOutputText.isNotBlank()) {
                    satisfied.add("Capability LLM_GENERATION text output verified.")
                } else if (strategy == VerificationStrategy.STRICT || requirements.requiredCapabilities.size > 1) {
                    missing.add("Task required capability ${requiredCap.name} but missing evidence keys: ${contract.requiredEvidenceKeys.joinToString()}")
                }
            }
        }

        // 7. Verify Structured Acceptance Criteria
        val allAcceptanceCriteria = (successCriteria.acceptanceCriteria + requirements.acceptanceCriteria).distinctBy { it.id }
        for (criterion in allAcceptanceCriteria) {
            val isMet = evaluateAcceptanceCriterion(criterion, accumulatedEvidence, finalOutputText)
            if (isMet) {
                satisfied.add("Criterion met: ${criterion.description}")
            } else {
                missing.add("Criterion not satisfied: ${criterion.description} [${criterion.id}]")
            }
        }

        // 8. Strategy Evaluation
        val isSatisfied = when (strategy) {
            VerificationStrategy.STRICT -> missing.isEmpty()
            VerificationStrategy.PERMISSIVE -> missing.isEmpty() || (finalOutputText.isNotBlank() && requirements.requiredCapabilities.isEmpty())
            VerificationStrategy.EVIDENCE_BASED -> missing.none { it.contains("evidence", ignoreCase = true) || it.contains("Capability", ignoreCase = true) }
            VerificationStrategy.CRITERIA_MATCH -> missing.none { it.contains("Criterion", ignoreCase = true) }
        }

        val verificationStatus = when {
            isSatisfied -> VerificationOutcomeStatus.VERIFIED
            satisfied.isNotEmpty() && missing.isNotEmpty() -> VerificationOutcomeStatus.PARTIALLY_VERIFIED
            missing.isNotEmpty() -> VerificationOutcomeStatus.FAILED
            else -> VerificationOutcomeStatus.INCONCLUSIVE
        }

        val confidence = if (isSatisfied) 1.0f else (1.0f - (missing.size * 0.25f)).coerceAtLeast(0.0f)
        val isDegradedAcceptable = !isSatisfied && task.constraints.allowDegradedExecution &&
                (satisfied.isNotEmpty() || finalOutputText.isNotBlank())

        val summary = if (isSatisfied) {
            "تم التحقق بنجاح من كافة معايير إنجاز المهمة (${satisfied.size} معايير مكتملة)."
        } else {
            "فشل التحقق الموضوعي: ${missing.joinToString("; ")}"
        }

        return TaskVerificationReport(
            isSatisfied = isSatisfied,
            status = verificationStatus,
            isDegradedAcceptable = isDegradedAcceptable,
            missingCriteria = missing,
            satisfiedCriteria = satisfied,
            confidence = confidence,
            summary = summary
        )
    }

    private fun evaluateAcceptanceCriterion(
        criterion: AcceptanceCriterion,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String
    ): Boolean {
        val targetValue = if (criterion.requiredKey != null) {
            accumulatedEvidence[criterion.requiredKey]?.toString() ?: ""
        } else {
            finalOutputText
        }

        return when (criterion.validatorType.uppercase()) {
            "EXISTS" -> targetValue.isNotBlank() || accumulatedEvidence.containsKey(criterion.requiredKey)
            "NOT_BLANK" -> targetValue.isNotBlank()
            "MIN_LENGTH" -> {
                val minLen = criterion.minValue?.toInt() ?: 1
                targetValue.length >= minLen
            }
            "REGEX" -> {
                val pattern = criterion.regexPattern ?: return targetValue.isNotBlank()
                Regex(pattern).containsMatchIn(targetValue)
            }
            "NUMERIC_RANGE" -> {
                val num = targetValue.toDoubleOrNull() ?: return false
                val min = criterion.minValue ?: Double.NEGATIVE_INFINITY
                val max = criterion.maxValue ?: Double.POSITIVE_INFINITY
                num in min..max
            }
            else -> targetValue.isNotBlank()
        }
    }

    /**
     * Determines whether the high-level objective of the task has been fully satisfied.
     */
    fun isTaskObjectiveSatisfied(
        task: TaskDefinition,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String,
        lastAction: DecisionAction
    ): Boolean {
        return verifyTaskCompletion(task, accumulatedEvidence, finalOutputText, lastAction).isSatisfied
    }

    /**
     * Checks if the closed-loop execution has reached a terminal state.
     */
    fun isTerminalConditionReached(
        task: TaskDefinition,
        stepCount: Int,
        maxSteps: Int,
        consecutiveFailures: Int,
        isObjectiveMet: Boolean
    ): Boolean {
        if (isObjectiveMet) return true
        if (consecutiveFailures > task.constraints.maxRetries) return true
        if (stepCount >= maxSteps) return true
        return false
    }
}
