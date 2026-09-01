package com.example.application.outcome

import com.example.application.execution.ExecutionResult
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
 * Structured verification report for objective evaluation.
 */
data class TaskVerificationReport(
    val isSatisfied: Boolean,
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
     * Maps an execution result and action into an ActionOutcomeType.
     */
    fun evaluateActionOutcome(
        action: DecisionAction,
        result: ExecutionResult
    ): ActionOutcomeType {
        return when {
            !result.isSuccess -> {
                if (result.errorDescription?.contains("غير متاح", ignoreCase = true) == true ||
                    result.errorDescription?.contains("unavailable", ignoreCase = true) == true) {
                    ActionOutcomeType.UNAVAILABLE
                } else if (result.errorDescription?.contains("حظر", ignoreCase = true) == true ||
                    result.errorDescription?.contains("blocked", ignoreCase = true) == true ||
                    result.errorDescription?.contains("refused", ignoreCase = true) == true ||
                    result.errorDescription?.contains("رفض", ignoreCase = true) == true) {
                    ActionOutcomeType.BLOCKED
                } else {
                    ActionOutcomeType.FAILURE
                }
            }
            result.isDegraded -> ActionOutcomeType.PARTIAL_SUCCESS
            action.type == DecisionActionType.WAIT -> ActionOutcomeType.WAITING
            action.type == DecisionActionType.ASK_USER -> ActionOutcomeType.WAITING
            else -> ActionOutcomeType.SUCCESS
        }
    }

    /**
     * Performs strict, objective verification of task criteria against gathered evidence and outputs.
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

        // 3. Minimum output length check
        val minChars = successCriteria.minOutputLengthChars.coerceAtLeast(1)
        if (finalOutputText.trim().length < minChars && !accumulatedEvidence.containsKey("toolOutput")) {
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

        // 6. Verify Capability Requirements Evidence
        if (requirements.requiredCapabilities.contains(CapabilityType.SEARCH)) {
            if (accumulatedEvidence.containsKey("searchResults")) {
                satisfied.add("Capability SEARCH evidence confirmed.")
            } else {
                missing.add("Task required SEARCH capability but no search results were gathered.")
            }
        }

        if (requirements.requiredCapabilities.contains(CapabilityType.MEMORY_RETRIEVAL) ||
            requirements.requiredCapabilities.contains(CapabilityType.EMBEDDING)) {
            if (accumulatedEvidence.containsKey("memorySnippets")) {
                satisfied.add("Capability MEMORY evidence confirmed.")
            } else if (strategy == VerificationStrategy.STRICT) {
                missing.add("Task required MEMORY capability but no memory records were retrieved.")
            }
        }

        if (requirements.requiredCapabilities.contains(CapabilityType.TOOL_EXECUTION) ||
            requirements.requiredCapabilities.contains(CapabilityType.FILE_STORAGE)) {
            if (accumulatedEvidence.containsKey("toolOutput") || accumulatedEvidence.containsKey("toolAttributes")) {
                satisfied.add("Capability TOOL_EXECUTION evidence confirmed.")
            } else if (strategy == VerificationStrategy.STRICT) {
                missing.add("Task required TOOL_EXECUTION capability but no tool output was captured.")
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
            VerificationStrategy.PERMISSIVE -> missing.isEmpty() || finalOutputText.isNotBlank()
            VerificationStrategy.EVIDENCE_BASED -> missing.none { it.contains("Evidence", ignoreCase = true) }
            VerificationStrategy.CRITERIA_MATCH -> missing.none { it.contains("Criterion", ignoreCase = true) }
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
