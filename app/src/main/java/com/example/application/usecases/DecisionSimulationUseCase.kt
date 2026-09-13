package com.example.application.usecases

import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskId
import java.util.UUID

/**
 * ============================================================================
 * DecisionSimulationUseCase — GAP-19 (Design Closure 2026, ADR-6 step 2)
 * ============================================================================
 *
 * Extracted VERBATIM from MainViewModel.simulateDecisionInternal — the
 * decision-simulation business rules (state construction + the candidate
 * action set + engine evaluation) lived in the presentation layer, which
 * made them untestable outside the ViewModel and forced the VM to hold
 * engine-level knowledge. The ViewModel now only projects the result into
 * UiState.
 *
 * The candidate set is the product's decision-simulation fixture (the
 * "what would the kernel choose" preview on the Decision screen): agent,
 * model, provider (OFFLINE-aware), search, knowledge retrieval, planning
 * and the terminal STOP.
 */
class DecisionSimulationUseCase(
    private val cbrMdpEngine: CbrMdpEngine
) {

    /** Projection-ready result — the ViewModel copies it into UiState. */
    data class SimulationOutcome(
        val decision: DecisionResult,
        val caseBase: List<com.example.domain.core.decision.DecisionCase>
    )

    suspend operator fun invoke(
        taskComplexity: Float,
        uncertaintyScore: Float,
        networkPolicy: NetworkPolicy
    ): SimulationOutcome {
        val state = DecisionState(
            taskId = TaskId(UUID.randomUUID().toString()),
            taskComplexity = taskComplexity,
            requiresVision = false,
            requiresToolCalling = true,
            requiresWebSearch = taskComplexity > 0.7f,
            requiresCoding = true,
            networkPolicy = networkPolicy,
            uncertaintyScore = uncertaintyScore
        )

        val candidateActions = listOf(
            DecisionAction(DecisionActionType.SELECT_AGENT, targetId = "code_craftsman"),
            DecisionAction(DecisionActionType.SELECT_MODEL, targetId = "gemini-2.5-flash", estimatedCost = 0.001),
            DecisionAction(
                DecisionActionType.SELECT_PROVIDER,
                targetId = if (networkPolicy == NetworkPolicy.OFFLINE) "local_ollama" else "gemini_google"
            ),
            DecisionAction(DecisionActionType.SEARCH, targetId = "multi_source_search", estimatedCost = 0.005),
            DecisionAction(DecisionActionType.RETRIEVE_KNOWLEDGE, targetId = "rag_knowledge_base"),
            DecisionAction(DecisionActionType.CREATE_PLAN, targetId = "dag_workflow_engine"),
            DecisionAction(DecisionActionType.STOP)
        )

        val result = cbrMdpEngine.evaluateAndSelectAction(state, candidateActions)
        return SimulationOutcome(
            decision = result,
            caseBase = cbrMdpEngine.getCaseBase().getAllCases()
        )
    }
}
