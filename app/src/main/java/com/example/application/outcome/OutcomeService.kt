package com.example.application.outcome

import com.example.application.execution.ExecutionResult
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.task.TaskDefinition

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

data class TaskVerificationReport(
    val isSatisfied: Boolean,
    val missingCriteria: List<String> = emptyList(),
    val confidence: Float = 1.0f,
    val summary: String = ""
)

/**
 * Outcome Evaluation Service determining individual action outcomes and global task completion / termination criteria.
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
            else -> ActionOutcomeType.SUCCESS
        }
    }

    /**
     * Performs strict verification of task criteria against gathered evidence and output.
     */
    fun verifyTaskCompletion(
        task: TaskDefinition,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String,
        lastAction: DecisionAction
    ): TaskVerificationReport {
        val missing = mutableListOf<String>()

        // 1. Verify required output keys
        for (requiredKey in task.successCriteria.requiredOutputKeys) {
            if (!accumulatedEvidence.containsKey(requiredKey)) {
                missing.add("Missing required output key: $requiredKey")
            }
        }

        // 2. Verify minimum output length
        if (finalOutputText.length < task.successCriteria.minOutputLengthChars) {
            missing.add("Output length (${finalOutputText.length}) less than minimum required (${task.successCriteria.minOutputLengthChars})")
        }

        // 3. Domain semantic checks
        if (lastAction.type == DecisionActionType.SELECT_MODEL || lastAction.type == DecisionActionType.SELECT_AGENT) {
            missing.add("Intermediate routing action does not complete task")
        }

        val prompt = task.input.rawPrompt
        val isMultiStepResearch = prompt.contains("بحث", ignoreCase = true) ||
                prompt.contains("search", ignoreCase = true) ||
                prompt.contains("أحدث", ignoreCase = true) ||
                prompt.contains("latest", ignoreCase = true)

        if (isMultiStepResearch) {
            val hasEvidence = accumulatedEvidence.containsKey("searchResults") || accumulatedEvidence.containsKey("memorySnippets")
            if (!hasEvidence) {
                missing.add("Multi-step research requires search or memory evidence before completion")
            }
            val hasSynthesized = finalOutputText.isNotBlank() && (lastAction.type == DecisionActionType.EXECUTE_STEP || lastAction.type == DecisionActionType.COMPLETE)
            if (!hasSynthesized) {
                missing.add("Research task requires synthesized explanation output")
            }
        }

        val isToolTask = prompt.contains("ملف", ignoreCase = true) ||
                prompt.contains("أداة", ignoreCase = true) ||
                prompt.contains("file", ignoreCase = true) ||
                prompt.contains("احسب", ignoreCase = true) ||
                prompt.contains("calculate", ignoreCase = true) ||
                prompt.contains("tool", ignoreCase = true)
        if (isToolTask) {
            val hasToolOutput = accumulatedEvidence.containsKey("toolOutput") || accumulatedEvidence.containsKey("calculatorOutput")
            if (!hasToolOutput) {
                missing.add("Tool task requires tool execution output")
            }
        }

        // 4. Ensure error text isn't passed off as successful completion
        if (finalOutputText.startsWith("Error:") || finalOutputText.startsWith("فشل:") || finalOutputText.startsWith("BLOCKED:")) {
            missing.add("Output indicates an unrecovered execution error")
        }

        val isSatisfied = missing.isEmpty()
        val confidence = if (isSatisfied) 1.0f else (1.0f - (missing.size * 0.3f)).coerceAtLeast(0.0f)
        val summary = if (isSatisfied) {
            "تم التحقق بنجاح من كافة معايير إنجاز المهمة."
        } else {
            "فشل التحقق: ${missing.joinToString("; ")}"
        }

        return TaskVerificationReport(
            isSatisfied = isSatisfied,
            missingCriteria = missing,
            confidence = confidence,
            summary = summary
        )
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
