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
                if (result.errorDescription?.contains("غير متاح", ignoreCase = true) == true) {
                    ActionOutcomeType.UNAVAILABLE
                } else if (result.errorDescription?.contains("حظر", ignoreCase = true) == true ||
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
     * Determines whether the high-level objective of the task has been fully satisfied.
     */
    fun isTaskObjectiveSatisfied(
        task: TaskDefinition,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String,
        lastAction: DecisionAction
    ): Boolean {
        // 1. Explicit Complete action chosen by decision intelligence
        if (lastAction.type == DecisionActionType.COMPLETE) {
            return true
        }

        val prompt = task.input.rawPrompt
        val isMultiStepResearch = prompt.contains("بحث", ignoreCase = true) ||
                prompt.contains("search", ignoreCase = true) ||
                prompt.contains("أحدث", ignoreCase = true) ||
                prompt.contains("latest", ignoreCase = true)

        // 2. Multi-step research tasks require evidence + synthesis
        if (isMultiStepResearch) {
            val hasEvidence = accumulatedEvidence.containsKey("searchResults") || accumulatedEvidence.containsKey("memorySnippets")
            val hasSynthesizedText = finalOutputText.isNotBlank() && (lastAction.type == DecisionActionType.SELECT_MODEL || lastAction.type == DecisionActionType.EXECUTE_STEP)
            return hasEvidence && hasSynthesizedText
        }

        // 3. Tool tasks require tool output + optional synthesis
        val isToolTask = prompt.contains("ملف", ignoreCase = true) || prompt.contains("أداة", ignoreCase = true) || prompt.contains("file", ignoreCase = true)
        if (isToolTask) {
            val hasToolOutput = accumulatedEvidence.containsKey("toolOutput")
            val hasText = finalOutputText.isNotBlank()
            return hasToolOutput && hasText
        }

        // 4. General LLM generation task satisfies objective once output is produced
        if (finalOutputText.isNotBlank() && (lastAction.type == DecisionActionType.SELECT_MODEL || lastAction.type == DecisionActionType.EXECUTE_STEP)) {
            val meetsMinLength = finalOutputText.length >= task.successCriteria.minOutputLengthChars
            return meetsMinLength
        }

        return false
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
