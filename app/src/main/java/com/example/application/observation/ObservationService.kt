package com.example.application.observation

import com.example.application.execution.ExecutionResult
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.EnvironmentObservation

/**
 * Normalizes execution results and environment feedback into canonical EnvironmentObservations
 * for CBR-MDP belief updating and case base retention.
 */
class ObservationService {

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
}
