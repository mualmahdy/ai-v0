package com.example.domain.core.decision

import com.example.domain.core.network.NetworkPolicy
import java.util.UUID

/**
 * Real CBR-MDP (Case-Based Reasoning + Markov Decision Process) Decision Intelligence Engine.
 * Evaluates candidate actions over state vectors, combines historical case retrieval with expected MDP utility,
 * and maintains belief updates upon receiving environment observations.
 */
class CbrMdpEngine(
    private val caseBase: CaseBase = CaseBase()
) {
    // Discount factor gamma for MDP
    private val gamma: Float = 0.9f
    private val cbrWeight: Float = 0.55f
    private val mdpWeight: Float = 0.45f

    // Estimated transition success probabilities per action type
    private val actionSuccessEstimates = mutableMapOf<DecisionActionType, Float>().apply {
        DecisionActionType.entries.forEach { this[it] = 0.85f }
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

        val scoredCandidates = candidateActions.map { action ->
            scoreAction(action, state, similarCases)
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

        val actionSuccessProb = actionSuccessEstimates[action.type] ?: 0.8f
        val expectedFutureValue = gamma * actionSuccessProb * (1.0f - state.uncertaintyScore)
        val mdpValue = ((immediateReward - costPenalty - latencyPenalty) + expectedFutureValue).coerceIn(-1.0f, 1.5f)

        // 3. Combined Final Score & Confidence
        val normalizedMdp = ((mdpValue + 1.0f) / 2.5f).coerceIn(0.0f, 1.0f)
        val finalScore = (cbrWeight * cbrScore + mdpWeight * normalizedMdp).coerceIn(0.0f, 1.0f)
        val confidence = ((1.0f - state.uncertaintyScore) * 0.6f + (if (matchingCases.isNotEmpty()) 0.4f else 0.2f)).coerceIn(0.1f, 0.99f)

        val reason = buildString {
            append("قرار مبني على ")
            if (matchingCases.isNotEmpty()) {
                append("تطابق ${matchingCases.size} حالة سابقة (CBR: ${"%.2f".format(cbrScore)}) مع ")
            }
            append("قيمة المنفعة المتوقعة (MDP: ${"%.2f".format(normalizedMdp)}) ")
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
     */
    fun processObservationAndUpdateBelief(
        state: DecisionState,
        observation: EnvironmentObservation
    ): DecisionState {
        // Update transition estimate using exponential moving average
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

        return state.copy(
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
    }

    fun getCaseBase(): CaseBase = caseBase
}
