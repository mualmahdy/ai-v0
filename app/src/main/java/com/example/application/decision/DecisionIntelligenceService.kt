package com.example.application.decision

import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.evolution.runtime.PolicyEvaluationReport
import com.example.domain.core.evolution.runtime.PolicyKind
import com.example.infrastructure.persistence.dao.DecisionCaseDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * DecisionIntelligenceService — Phase 5 Decision Intelligence (P1)
 * ============================================================================
 *
 * GAP-CLOSURE P0-08: this service previously "evaluated" the policy with a
 * HARD-CODED `simulatePolicyLookup() = "LLM_GENERATION"` and a placeholder
 * lookahead that summed a constant — its reports could say nothing true
 * about the REAL decision runtime (CbrMdpEngine + DecisionService). Both
 * paths now call the REAL engine:
 *
 *  - [evaluatePolicy] runs the engine's actual `evaluateAndSelectAction`
 *    against the same candidate set the runtime builds, so the report
 *    measures the policy that actually decides executions.
 *  - [lookahead] reads REAL Q-table cells (`getQEntry`) and rolls the
 *    discounted value of the sticky-state successor (max over actions),
 *    honestly returning a neutral prior when nothing has been learned.
 *  - [calibrateUncertainty] is unchanged (it was already empirical).
 *
 * The service remains an AUXILIARY measurement layer; the authoritative
 * decision path is still DecisionService.evaluate() — but now the numbers
 * here are derived from that same engine, not fabricated.
 */
class DecisionIntelligenceService(
    private val decisionCaseDao: DecisionCaseDao,
    private val cbrMdpEngine: CbrMdpEngine
) {

    /**
     * Evaluate the current Q-policy against a held-out task suite.
     *
     * @param taskSuite list of (state, expectedBestAction) pairs to
     *        evaluate against. The "expected" action is whatever the
     *        ground-truth label says; we compare it to the action the
     *        CURRENT engine (real CBR retrieval + Q-table) would pick.
     */
    suspend fun evaluatePolicy(
        versionId: String,
        taskSuite: List<Pair<DecisionState, String>>
    ): PolicyEvaluationReport = withContext(Dispatchers.Default) {
        if (taskSuite.isEmpty()) {
            return@withContext PolicyEvaluationReport(
                versionId = versionId,
                taskSuiteSize = 0,
                successCount = 0,
                degradedCount = 0,
                failureCount = 0,
                averageReward = 0f,
                p95LatencyMs = 0L,
                totalTokensConsumed = 0L,
                regressionDetected = false,
                regressionScore = 0f,
                notes = "عينة فارغة"
            )
        }

        var success = 0
        var degraded = 0
        var failure = 0
        var totalReward = 0f
        val latencies = mutableListOf<Long>()
        var engineCells = 0

        for ((state, expectedAction) in taskSuite) {
            val start = System.currentTimeMillis()
            // P0-08: the REAL policy lookup — the same engine call the
            // runtime's decision layer performs (CBR retrieval + Q-table).
            val predictedAction = realPolicyLookup(state)
            engineCells = cbrMdpEngine.qTableSize()
            val latency = System.currentTimeMillis() - start
            latencies.add(latency)
            val reward = if (predictedAction == expectedAction) {
                success++
                1.0f
            } else if (predictedAction.startsWith(expectedAction.substringBefore("_"))) {
                degraded++
                0.5f
            } else {
                failure++
                -0.2f
            }
            totalReward += reward
        }

        val sortedLatencies = latencies.sorted()
        val p95 = if (sortedLatencies.isNotEmpty()) sortedLatencies[(sortedLatencies.size * 0.95).toInt().coerceAtMost(sortedLatencies.size - 1)] else 0L

        PolicyEvaluationReport(
            versionId = versionId,
            taskSuiteSize = taskSuite.size,
            successCount = success,
            degradedCount = degraded,
            failureCount = failure,
            averageReward = totalReward / taskSuite.size,
            p95LatencyMs = p95,
            totalTokensConsumed = 0L,
            regressionDetected = false, // set by the caller via detectRegression
            regressionScore = 0f,
            notes = "تقييم عبر المحرك الحقيقي (CbrMdpEngine.evaluateAndSelectAction) — " +
                "خلايا Q متعلمة: $engineCells"
        )
    }

    /**
     * P0-08 (REAL): performs the engine's own action selection for the
     * given state using the runtime's standard candidate set. Returns the
     * chosen action TYPE name — exactly what the decision runtime would
     * choose, not a simulation.
     */
    private fun realPolicyLookup(state: DecisionState): String {
        val candidates = standardCandidateActions()
        val decision = cbrMdpEngine.evaluateAndSelectAction(state, candidates)
        return decision.chosenAction.type.name
    }

    /** The standard candidate set mirroring DecisionService's planner space. */
    internal fun standardCandidateActions(): List<DecisionAction> =
        listOf(
            DecisionAction(DecisionActionType.EXECUTE_STEP, targetId = "current"),
            DecisionAction(DecisionActionType.SELECT_MODEL, targetId = "auto"),
            DecisionAction(DecisionActionType.SEARCH, targetId = "web"),
            DecisionAction(DecisionActionType.RETRIEVE_KNOWLEDGE, targetId = "rag"),
            DecisionAction(DecisionActionType.RETRIEVE_MEMORY, targetId = "memory"),
            DecisionAction(DecisionActionType.DELEGATE, targetId = "sub_agent"),
            DecisionAction(DecisionActionType.CREATE_PLAN, targetId = "dag_workflow_planner"),
            DecisionAction(DecisionActionType.REPLAN, targetId = "self"),
            DecisionAction(DecisionActionType.COMPLETE, targetId = "terminal_complete"),
            DecisionAction(DecisionActionType.STOP, targetId = "terminal_stop"),
            DecisionAction(DecisionActionType.ASK_USER, targetId = "guidance")
        )

    /**
     * Multi-step lookahead (P0-08 — REAL Q-table values).
     *
     * Estimates the long-horizon value of taking `actionType` in `state`:
     *
     *   Q(s, a) + Σ γ^i · max_a' Q(s', a')   (sticky successor s' = s)
     *
     * Q values come from the engine's LIVE Q-table (`getQEntry`). When no
     * cell has been learned for the state region, the method returns a
     * HONEST neutral prior (0f) — it no longer fabricates a constant and
     * label it "lookahead".
     *
     * @return the estimated long-horizon value.
     */
    fun lookahead(
        state: DecisionState,
        actionType: String,
        horizon: Int = 3,
        gamma: Float = 0.9f
    ): Float {
        val regionKey = cbrMdpEngine.stateRegionKey(state)
        val action = runCatching { DecisionActionType.valueOf(actionType) }.getOrNull()
            ?: return 0f

        val qNow = cbrMdpEngine.getQEntry(regionKey, action)?.qValue ?: 0f

        // Successor state assumed sticky (Markov with self-transition) — the
        // honest simplification; we roll the BEST next-action value forward.
        var value = qNow
        var discount = gamma
        for (i in 0 until (horizon - 1).coerceAtLeast(0)) {
            val successorBest = DecisionActionType.entries.maxOfOrNull { next ->
                cbrMdpEngine.getQEntry(regionKey, next)?.qValue ?: 0f
            } ?: 0f
            value += discount * successorBest
            discount *= gamma
        }
        return value
    }

    /**
     * Calibrate uncertainty based on historical prediction error.
     *
     * The audit found `uncertaintyScore` was hand-tuned (`*0.7` on
     * success, `*1.3` on failure) without calibration against actual
     * prediction error. This method computes the empirical prediction
     * error for the state region and adjusts the uncertainty toward
     * the observed error rate.
     */
    suspend fun calibrateUncertainty(
        stateRegionKey: String,
        rawUncertainty: Float
    ): Float = withContext(Dispatchers.IO) {
        // Load recent cases for this region.
        val recentCases = decisionCaseDao.getRecentCases(50)
        val regionCases = recentCases.filter {
            // The case's feature vector encodes the region; we approximate
            // by checking if the first feature matches.
            it.featuresJson.contains(stateRegionKey, ignoreCase = true)
        }
        if (regionCases.size < 10) {
            // Not enough data to calibrate; return raw uncertainty.
            return@withContext rawUncertainty
        }
        // Empirical error rate: fraction of cases with negative reward.
        val errorRate = regionCases.count { it.outcomeReward < 0f }.toFloat() / regionCases.size.toFloat()
        // Blend the raw uncertainty with the empirical error rate.
        val calibrated = (rawUncertainty * 0.5f) + (errorRate * 0.5f)
        calibrated.coerceIn(0f, 1f)
    }
}
