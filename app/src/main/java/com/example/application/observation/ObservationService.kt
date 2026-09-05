package com.example.application.observation

import com.example.application.execution.ExecutionResult
import com.example.application.outcome.ActionOutcomeType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.EnvironmentObservation

/**
 * Normalizes execution results and environment feedback into canonical EnvironmentObservations
 * for CBR-MDP belief updating and case base retention.
 *
 * ============================================================================
 * FIX D-2 (audit c03919d): real reward shaping
 * ============================================================================
 * Previously the feedback reward was a binary success/failure heuristic with
 * no notion of OUTPUT QUALITY — a task could "succeed" while producing a
 * useless answer and the engine would reinforce it identically.
 *
 * The reward now integrates three honest signals:
 *   1. Execution signals (success, degradation, latency, tokens) — as before.
 *   2. The ActionOutcomeType classification from OutcomeService
 *      (D-3: previously dead code, now wired into the live loop).
 *   3. The TASK VERIFICATION quality (verifyTaskCompletion confidence) at
 *      terminal steps — the model's actual acceptance-criteria score becomes
 *      the terminal reward for the CBR-MDP Q-update.
 */
class ObservationService {

    /**
     * Constructs a normalized EnvironmentObservation from an executed action and its ExecutionResult.
     *
     * @param actionOutcome explicit classification from OutcomeService.evaluateActionOutcome
     *   (D-3 wiring; null keeps the legacy internal classification).
     * @param taskVerificationQuality terminal quality signal in [0,1] from
     *   OutcomeService.verifyTaskCompletion (TaskVerificationReport.confidence
     *   with sign: positive when verified, negative when failed). Null for
     *   non-terminal steps.
     */
    fun createObservation(
        action: DecisionAction,
        result: ExecutionResult,
        stepIndex: Int = 0,
        actionOutcome: ActionOutcomeType? = null,
        taskVerificationQuality: Float? = null
    ): EnvironmentObservation {
        val reward = calculateFeedbackReward(action, result, actionOutcome, taskVerificationQuality)
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
            outputData = result.outputData +
                // D-3: expose the classification so downstream consumers
                // (persistence, observability) see the honest outcome type.
                ("actionOutcomeType" to (actionOutcome?.name ?: "UNKNOWN")),
            stepIndex = stepIndex,
            feedbackReward = reward,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun calculateFeedbackReward(
        action: DecisionAction,
        result: ExecutionResult,
        actionOutcome: ActionOutcomeType? = null,
        taskVerificationQuality: Float? = null
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

        // ------------------------------------------------------------------
        // FIX D-3: honest outcome-type shaping. The previously-dead
        // OutcomeService.evaluateActionOutcome classification now feeds the
        // reward: PARTIAL_SUCCESS and WAITING are NOT full successes.
        // ------------------------------------------------------------------
        if (actionOutcome != null) {
            when (actionOutcome) {
                ActionOutcomeType.PARTIAL_SUCCESS -> reward -= 0.15f
                ActionOutcomeType.WAITING -> reward -= 0.2f
                ActionOutcomeType.UNAVAILABLE -> reward -= 0.25f
                ActionOutcomeType.BLOCKED -> reward -= 0.3f
                ActionOutcomeType.SUCCESS, ActionOutcomeType.CANCELLED -> Unit
                // FAILURE is handled by the !isSuccess early return above.
                ActionOutcomeType.FAILURE -> Unit
            }
        }

        // ------------------------------------------------------------------
        // FIX D-2: terminal task-verification quality (verifyTaskCompletion
        // confidence) blended into the reward — verified completions are
        // reinforced proportionally to how well the acceptance criteria were
        // actually met; failed verification pulls the reward down.
        // ------------------------------------------------------------------
        if (taskVerificationQuality != null) {
            reward = (reward * 0.6f) + (taskVerificationQuality.coerceIn(-1f, 1f) * 0.4f)
        }

        return reward.coerceIn(-1.0f, 1.0f)
    }
}
