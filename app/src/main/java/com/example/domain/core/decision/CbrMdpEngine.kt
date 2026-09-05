package com.example.domain.core.decision

import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Real CBR-MDP (Case-Based Reasoning + Markov Decision Process) Decision Intelligence Engine.
 * Evaluates candidate actions over state vectors, combines historical case retrieval with expected MDP utility,
 * and maintains belief updates upon receiving environment observations.
 *
 * ============================================================================
 * FIX D-1 / D-4 (audit c03919d) — tabular MDP with persistence
 * ============================================================================
 *
 * Previous defects:
 *   - D-1: "MDP" was nominal — ONE global success estimate per action TYPE
 *     (no V/Q per (s,a), no state aggregation, no transition rates).
 *   - D-4: all learned estimates lived in a plain in-memory map and were
 *     lost on every process death.
 *
 * The engine now maintains a REAL tabular MDP:
 *
 *   1. STATE AGGREGATION — DecisionState is hashed into a coarse region key
 *      (task type × evidence flags × failure bucket × step bucket × network
 *      availability). Coarse regions keep the table small enough to learn
 *      on-device without a function approximator.
 *
 *   2. PER-(REGION, ACTION) CELLS — each cell stores a Q value, a visit
 *      count, and a success count (transition success rate).
 *
 *   3. TD LEARNING — on every environment observation:
 *         Q(r,a) ← Q(r,a) + α · (reward + γ · maxQ(r') − Q(r,a))
 *      where r is the region BEFORE the action and r' the region AFTER.
 *
 *   4. PERSISTENCE — dirty cells are written to the [MdpLearningStore]
 *      (Room in production, in-memory in tests) asynchronously; the whole
 *      table is loaded once at bootstrap, so experience accumulates ACROSS
 *      sessions (D-4).
 *
 *   5. EXPLORATION — a UCB-style bonus favors under-visited actions within
 *      a region (deterministic, bounded — no random flakiness in tests).
 *
 *   6. EVOI GATE (P1) — ASK_USER is only scored as valuable when the
 *      expected value of information justifies interrupting the user
 *      (high uncertainty or repeated failures); otherwise it is heavily
 *      penalized so the engine prefers autonomous progress.
 */
class CbrMdpEngine(
    private val caseBase: CaseBase = CaseBase(),
    /** FIX D-4: persistent tabular-MDP store (Room-backed in production). */
    private val mdpStore: MdpLearningStore? = null,
    /** Scope used to persist dirty Q-table cells asynchronously. */
    private val persistenceScope: CoroutineScope? = null
) {
    // Discount factor gamma for MDP
    private val gamma: Float = 0.9f
    private val cbrWeight: Float = 0.55f
    private val mdpWeight: Float = 0.45f

    // FIX D-1: TD learning rate for the Q-table update.
    private val learningRateAlpha: Float = 0.3f

    // FIX D-1 (exploration): UCB exploration coefficient — small enough that
    // it only breaks ties in favor of under-explored actions.
    private val explorationCoefficient: Float = 0.12f

    // FIX D-1 (shrinkage): visits/(visits + VISIT_CONFIDENCE) — weight given
    // to the LEARNED Q value vs. the heuristic prior for a (region, action).
    private val VISIT_CONFIDENCE = 5

    // P1 (EVOI gate): ASK_USER is only valuable when uncertainty is at least
    // this high, or after this many consecutive failures.
    private val evoiUncertaintyThreshold: Float = 0.55f
    private val evoiFailureThreshold: Int = 2

    // Estimated transition success probabilities per action type (global
    // fallback prior for unvisited (region, action) cells).
    // FIX DOM-P2-25: uninformative 0.5 prior (no optimism bias); per-region
    // truth now comes from the Q-table below.
    private val actionSuccessEstimates = mutableMapOf<DecisionActionType, Float>().apply {
        DecisionActionType.entries.forEach { this[it] = 0.5f }
    }

    // ========================================================================
    // FIX D-1/D-4: the tabular MDP Q-table.
    // key = "regionKey|ACTION_NAME", value = learned cell.
    // ========================================================================
    private val qTable = ConcurrentHashMap<String, MdpQEntry>()
    private val dirtyCells = ConcurrentHashMap.newKeySet<String>()

    /**
     * Aggregates a [DecisionState] into a coarse region key — the "s" of the
     * tabular MDP. Dimensions (deliberately few, so the table stays learnable):
     *   taskType | memory-evidence | search-evidence | tool-evidence |
     *   failure-bucket (0/1/2+) | step-bucket (0/1/2+) | network
     */
    fun stateRegionKey(state: DecisionState): String {
        val taskType = when {
            state.requiresCoding -> "COD"
            state.requiresWebSearch -> "SEA"
            else -> "GEN"
        }
        val failureBucket = when {
            state.consecutiveFailures <= 0 -> "F0"
            state.consecutiveFailures == 1 -> "F1"
            else -> "F2"
        }
        val stepBucket = when {
            state.currentStep <= 0 -> "P0"
            state.currentStep == 1 -> "P1"
            else -> "P2"
        }
        val network = if (state.networkPolicy == NetworkPolicy.OFFLINE || !state.isNetworkAvailable) "OFF" else "ON"
        return "$taskType|M${if (state.hasMemoryEvidence) 1 else 0}|S${if (state.hasSearchEvidence) 1 else 0}|" +
            "T${if (state.hasToolExecutionEvidence) 1 else 0}|$failureBucket|$stepBucket|$network"
    }

    private fun cellKey(regionKey: String, actionType: DecisionActionType): String =
        "$regionKey|${actionType.name}"

    /** Direct read access for observability/tests. */
    fun getQEntry(regionKey: String, actionType: DecisionActionType): MdpQEntry? =
        qTable[cellKey(regionKey, actionType)]

    /** Number of learned cells (observability). */
    fun qTableSize(): Int = qTable.size

    /**
     * FIX D-4: loads the persisted Q-table into memory. Called once at
     * bootstrap (AppContainer) BEFORE any decision is evaluated.
     */
    suspend fun loadPersistedQTable() {
        val store = mdpStore ?: return
        runCatching {
            for (entry in store.loadAll()) {
                qTable[cellKey(entry.regionKey, entry.actionType)] = entry
            }
        }
    }

    /** Flushes dirty cells to the store (asynchronous when a scope is wired). */
    private fun persistDirtyCells() {
        val store = mdpStore ?: return
        val scope = persistenceScope ?: return
        if (dirtyCells.isEmpty()) return
        val entries = dirtyCells.mapNotNull { key ->
            qTable[key]?.copy(lastUpdatedEpochMs = System.currentTimeMillis())
        }
        if (entries.isEmpty()) return
        dirtyCells.clear()
        scope.launch {
            runCatching { store.persist(entries) }
        }
    }

    /**
     * Executes the decision step: State -> CBR-MDP -> Action + Observability Trace.
     */
    fun evaluateAndSelectAction(
        state: DecisionState,
        candidateActions: List<DecisionAction>
    ): DecisionResult {
        val queryFeatures = state.toFeatureVector()
        val similarCases = caseBase.findSimilarCases(queryFeatures, k = 5, minSimilarity = 0.4f)
        val regionKey = stateRegionKey(state)

        val scoredCandidates = candidateActions.map { action ->
            scoreAction(action, state, regionKey, similarCases)
        }.sortedByDescending { it.finalScore }

        val bestCandidate = scoredCandidates.firstOrNull() ?: ScoredActionCandidate(
            action = DecisionAction(DecisionActionType.STOP),
            cbrScore = 0.0f,
            mdpValue = 0.0f,
            finalScore = 0.0f,
            confidence = 0.5f,
            reason = "Default fallback stop action"
        )

        return DecisionResult(
            chosenAction = bestCandidate.action,
            confidence = bestCandidate.confidence,
            rationale = bestCandidate.reason,
            stateSnapshot = state,
            evaluatedAlternatives = scoredCandidates,
            matchedHistoricalCasesCount = similarCases.size
        )
    }

    private fun scoreAction(
        action: DecisionAction,
        state: DecisionState,
        regionKey: String,
        similarCases: List<Pair<DecisionCase, Float>>
    ): ScoredActionCandidate {
        // 1. CBR Score: Aggregate rewards of similar historical cases that took matching action
        var cbrScore = 0.5f // Neutral prior
        val matchingCases = similarCases.filter { it.first.chosenAction.type == action.type }
        if (matchingCases.isNotEmpty()) {
            val totalWeight = matchingCases.sumOf { it.second.toDouble() }.toFloat()
            val weightedReward = matchingCases.sumOf { (it.first.outcomeReward * it.second).toDouble() }.toFloat()
            cbrScore = (weightedReward / totalWeight.coerceAtLeast(0.01f)).coerceIn(0.0f, 1.0f)
        }

        // 2. MDP Expected Utility: Q(s, a) = Immediate Reward - Cost/Latency Penalty + Transition Value
        var immediateReward = 0.7f
        var costPenalty = (action.estimatedCost * 5.0).toFloat().coerceIn(0.0f, 0.4f)
        var latencyPenalty = (action.estimatedLatencyMs.toFloat() / 5000.0f).coerceIn(0.0f, 0.3f)

        // Offline / Network constraints
        if (state.networkPolicy == NetworkPolicy.OFFLINE || !state.isNetworkAvailable) {
            val isRemoteAction = action.type == DecisionActionType.SEARCH ||
                    (action.type == DecisionActionType.SELECT_PROVIDER && action.targetId?.contains("cloud", ignoreCase = true) == true) ||
                    (action.type == DecisionActionType.SELECT_MODEL && action.payload["isLocal"] == "false") ||
                    (action.type == DecisionActionType.EXECUTE_MCP && action.payload["isLocal"] == "false") ||
                    (action.type == DecisionActionType.USE_INTEGRATION)
            if (isRemoteAction) {
                immediateReward = -1.0f
                costPenalty = 1.0f
            }
        }

        // Action-specific heuristics and closed-loop state transitions
        when (action.type) {
            DecisionActionType.SELECT_MODEL, DecisionActionType.EXECUTE_STEP -> {
                if (state.requiresVision) immediateReward += 0.2f
                if (state.requiresToolCalling) immediateReward += 0.2f
                // When search or memory evidence has already been gathered in previous step, prioritize model synthesis
                if ((state.hasSearchEvidence || state.hasMemoryEvidence || state.hasToolExecutionEvidence) && state.currentStep > 0) {
                    immediateReward += 0.45f
                }
            }
            DecisionActionType.SELECT_AGENT -> {
                if (state.requiresCoding && action.targetId == "code_craftsman") immediateReward += 0.3f
                if (state.taskComplexity > 0.7f && action.targetId == "architect_orchestrator") immediateReward += 0.25f
            }
            DecisionActionType.SEARCH -> {
                if (state.requiresWebSearch && !state.hasSearchEvidence) {
                    immediateReward += 0.4f
                } else if (state.hasSearchEvidence) {
                    // Already searched, lower immediate reward unless replanning
                    immediateReward -= 0.3f
                } else {
                    immediateReward -= 0.2f
                }
            }
            DecisionActionType.RETRIEVE_MEMORY, DecisionActionType.RETRIEVE_KNOWLEDGE -> {
                if (!state.hasMemoryEvidence) {
                    immediateReward += 0.35f
                } else {
                    immediateReward -= 0.2f
                }
            }
            DecisionActionType.EXECUTE_TOOL, DecisionActionType.SELECT_TOOL -> {
                if (state.requiresToolCalling) immediateReward += 0.35f
            }
            DecisionActionType.EXECUTE_MCP -> {
                immediateReward += 0.3f
            }
            DecisionActionType.EXECUTE_SKILL -> {
                immediateReward += 0.35f
            }
            DecisionActionType.USE_INTEGRATION -> {
                immediateReward += 0.3f
            }
            DecisionActionType.RETRY -> {
                if (state.consecutiveFailures in 1..2) immediateReward += 0.25f else immediateReward -= 0.5f
            }
            DecisionActionType.REPLAN -> {
                if (state.consecutiveFailures >= 2) immediateReward += 0.5f else immediateReward -= 0.3f
            }
            DecisionActionType.CREATE_PLAN -> {
                if (state.taskComplexity > 0.6f && state.currentStep == 0) immediateReward += 0.3f
            }
            DecisionActionType.COMPLETE, DecisionActionType.STOP -> {
                if ((state.hasSearchEvidence || state.hasMemoryEvidence || state.hasToolExecutionEvidence) && state.currentStep >= 2) {
                    immediateReward += 0.6f
                }
            }
            DecisionActionType.ASK_USER -> {
                if (state.consecutiveFailures > 2) immediateReward += 0.4f
            }
            else -> Unit
        }

        // ------------------------------------------------------------------
        // P1 — EVOI gate before ASK_USER: interrupting the user is only worth
        // its expected value of information when uncertainty is high or the
        // loop has repeatedly failed. Otherwise heavily penalize so the engine
        // prefers autonomous progress over stalling.
        // ------------------------------------------------------------------
        var evoiGated = false
        if (action.type == DecisionActionType.ASK_USER) {
            val uncertaintyJustifies = state.uncertaintyScore >= evoiUncertaintyThreshold
            val failuresJustify = state.consecutiveFailures > evoiFailureThreshold
            if (!uncertaintyJustifies && !failuresJustify) {
                immediateReward -= 1.2f
                evoiGated = true
            }
        }

        // ------------------------------------------------------------------
        // FIX D-1: learned per-(region, action) Q value + REAL transition rate.
        // learnedWeight grows with visits (shrinkage toward the heuristic when
        // the cell is cold), so an empty table reproduces legacy behavior and
        // a warm table overrides heuristics with measured experience.
        // ------------------------------------------------------------------
        val cell = qTable[cellKey(regionKey, action.type)]
        val learnedQ = cell?.qValue ?: 0f
        val learnedWeight = if (cell != null) {
            cell.visitCount.toFloat() / (cell.visitCount + VISIT_CONFIDENCE).toFloat()
        } else 0f
        // Per-region transition success rate (D-1: real, measured) with the
        // global EMA as the cold-start prior.
        val transitionSuccessProb = if (cell != null && cell.visitCount > 0) {
            cell.successCount.toFloat() / cell.visitCount
        } else {
            actionSuccessEstimates[action.type] ?: 0.5f
        }

        val actionSuccessProb = transitionSuccessProb
        val expectedFutureValue = gamma * actionSuccessProb * (1.0f - state.uncertaintyScore)
        val heuristicMdpValue = (immediateReward - costPenalty - latencyPenalty) + expectedFutureValue
        val mdpValue = ((1f - learnedWeight) * heuristicMdpValue + learnedWeight * learnedQ)
            .coerceIn(-1.0f, 1.5f)

        // 3. Combined Final Score & Confidence
        val normalizedMdp = ((mdpValue + 1.0f) / 2.5f).coerceIn(0.0f, 1.0f)
        var finalScore = (cbrWeight * cbrScore + mdpWeight * normalizedMdp).coerceIn(0.0f, 1.0f)

        // ------------------------------------------------------------------
        // FIX D-1 (exploration): UCB-style bonus — favors under-visited actions
        // so the engine keeps learning instead of locking onto the heuristic
        // optimum. Deterministic and bounded (no test flakiness).
        // ------------------------------------------------------------------
        val regionVisitTotal = qTable.keys.count { it.startsWith("$regionKey|") }
        if (regionVisitTotal > 0) {
            val pairVisits = cell?.visitCount ?: 0
            val explorationBonus = explorationCoefficient *
                sqrt(ln((regionVisitTotal + 1).toDouble()) / (pairVisits + 1.0)).toFloat()
            finalScore = (finalScore + explorationBonus).coerceIn(0.0f, 1.0f)
        }

        val confidence = ((1.0f - state.uncertaintyScore) * 0.6f + (if (matchingCases.isNotEmpty()) 0.4f else 0.2f)).coerceIn(0.1f, 0.99f)

        val reason = buildString {
            append("قرار مبني على ")
            if (matchingCases.isNotEmpty()) {
                append("تطابق ${matchingCases.size} حالة سابقة (CBR: ${"%.2f".format(cbrScore)}) مع ")
            }
            append("قيمة المنفعة المتوقعة (MDP: ${"%.2f".format(normalizedMdp)}) ")
            if (cell != null && cell.visitCount > 0) {
                append("[منطقة $regionKey — ${cell.visitCount} زيارة، نسبة نجاح ${"%.0f".format(transitionSuccessProb * 100)}%] ")
            }
            if (evoiGated) append("[بوابة EVOI: قيمة معلومات الاستطلاع لا تبرر مقاطعة المستخدم] ")
            if (costPenalty > 0.1f) append("مع خصم استهلاك الموارد.")
        }

        return ScoredActionCandidate(
            action = action,
            cbrScore = cbrScore,
            mdpValue = mdpValue,
            finalScore = finalScore,
            confidence = confidence,
            reason = reason
        )
    }

    /**
     * Environment Feedback Loop: Observation -> Belief Update -> Case Base Update.
     *
     * FIX D-1: performs the tabular TD update
     *     Q(r,a) ← Q(r,a) + α·(reward + γ·maxQ(r') − Q(r,a))
     * on the (region, action) cell, increments visit/success counts (the real
     * transition rate), and marks the cell dirty for persistence (FIX D-4).
     */
    fun processObservationAndUpdateBelief(
        state: DecisionState,
        observation: EnvironmentObservation
    ): DecisionState {
        val regionKey = stateRegionKey(state)

        // Update transition estimate using exponential moving average
        // (kept as the cold-start global prior).
        val currentEst = actionSuccessEstimates[observation.action.type] ?: 0.8f
        val newSuccessSignal = if (observation.isSuccess) 1.0f else 0.0f
        actionSuccessEstimates[observation.action.type] = (0.8f * currentEst) + (0.2f * newSuccessSignal)

        // Store solved case in CaseBase for continuous learning
        caseBase.addCase(
            DecisionCase(
                id = UUID.randomUUID().toString(),
                problemFeatures = state.toFeatureVector(),
                chosenAction = observation.action,
                outcomeReward = observation.feedbackReward,
                taskType = if (state.requiresCoding) "CODING" else if (state.requiresWebSearch) "SEARCH" else "GENERAL"
            )
        )

        // Return updated state
        val updatedFailures = if (observation.isSuccess) 0 else state.consecutiveFailures + 1
        val updatedUncertainty = if (observation.isSuccess) {
            (state.uncertaintyScore * 0.7f).coerceAtLeast(0.05f)
        } else {
            (state.uncertaintyScore * 1.3f).coerceAtMost(0.95f)
        }

        // Update evidence flags based on action type and outcome
        val hasSearch = state.hasSearchEvidence || (observation.action.type == DecisionActionType.SEARCH && observation.isSuccess)
        val hasMemory = state.hasMemoryEvidence || ((observation.action.type == DecisionActionType.RETRIEVE_MEMORY || observation.action.type == DecisionActionType.RETRIEVE_KNOWLEDGE) && observation.isSuccess)
        val hasTool = state.hasToolExecutionEvidence || ((observation.action.type == DecisionActionType.EXECUTE_TOOL || observation.action.type == DecisionActionType.SELECT_TOOL || observation.action.type == DecisionActionType.EXECUTE_MCP || observation.action.type == DecisionActionType.EXECUTE_SKILL) && observation.isSuccess)

        val updatedState = state.copy(
            consecutiveFailures = updatedFailures,
            uncertaintyScore = updatedUncertainty,
            remainingTokenBudget = (state.remainingTokenBudget - observation.tokensConsumed).coerceAtLeast(0),
            hasSearchEvidence = hasSearch,
            hasMemoryEvidence = hasMemory,
            hasToolExecutionEvidence = hasTool,
            lastActionType = observation.action.type,
            lastActionSuccess = observation.isSuccess,
            currentStep = state.currentStep + 1
        )

        // ------------------------------------------------------------------
        // FIX D-1: tabular TD update on Q(r, a).
        // r  = region BEFORE the action; r' = region AFTER (from updatedState).
        // ------------------------------------------------------------------
        val nextRegionKey = stateRegionKey(updatedState)
        val maxNextQ = qTable.keys
            .filter { it.startsWith("$nextRegionKey|") }
            .mapNotNull { qTable[it]?.qValue }
            .maxOrNull() ?: 0f

        val reward = observation.feedbackReward
        val key = cellKey(regionKey, observation.action.type)
        val existing = qTable[key]
        val newCell = if (existing == null) {
            MdpQEntry(
                regionKey = regionKey,
                actionType = observation.action.type,
                qValue = reward + gamma * maxNextQ,
                visitCount = 1,
                successCount = if (observation.isSuccess) 1 else 0
            )
        } else {
            val tdTarget = reward + gamma * maxNextQ
            val updatedQ = existing.qValue + learningRateAlpha * (tdTarget - existing.qValue)
            existing.copy(
                qValue = updatedQ.coerceIn(-1.5f, 1.5f),
                visitCount = existing.visitCount + 1,
                successCount = existing.successCount + if (observation.isSuccess) 1 else 0
            )
        }
        qTable[key] = newCell
        dirtyCells.add(key)

        // FIX D-4: flush the learned cells to the persistent store.
        persistDirtyCells()

        return updatedState
    }

    fun getCaseBase(): CaseBase = caseBase
}
