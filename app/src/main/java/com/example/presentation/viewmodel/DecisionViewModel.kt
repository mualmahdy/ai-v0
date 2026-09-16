package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.usecases.DecisionSimulationUseCase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * DecisionViewModel — ADR-6 slice 6 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The CBR-MDP decision cockpit left the MainViewModel per ADR-6 option-ج,
 * with its whole dependency set (cbrMdpEngine + decisionSimulationUseCase).
 *
 * STATE: the 5 decision UiState fields (latestDecision / caseBaseList /
 * isSimulatingDecision / decisionTaskComplexity / decisionUncertainty) moved
 * to this feature-owned [DecisionUiState].
 *
 * BEHAVIOR (moved verbatim): the slider-driven parameter updates with live
 * re-simulation, and the preview evaluation itself — through the REAL
 * DecisionSimulationUseCase (GAP-19: the simulation business rules live in
 * the use-case; the VM only projects the result), with the HONEST fallback
 * when the use-case is absent (legacy constructions): the preview is
 * unavailable rather than fabricated. The spinner flag is honestly WRITTEN
 * around the engine call (GAP-23 — previously a no-writer field).
 *
 * STUDIO SIGNALS (the decision feature's own stake — the ADR-6 slice-2 seam
 * that used to be collected by MainViewModel): the DECISION display mirrors
 * are projections of studio execution events; this ViewModel COLLECTS its
 * own share from the signal bus (the GovernanceViewModel pattern — each
 * feature collects its own stake, no shared mutable state):
 *
 *  - DecisionMade → the latest decision mirror;
 *  - ObservationRecorded → the uncertainty mirror + the case-base refresh
 *    (from the engine's own case base);
 *  - Completed / Error → the case-base refresh;
 *  - NetworkPolicyChanged → the SESSION network-policy display mirror (an
 *    input of the simulation) + an immediate re-simulation — the old
 *    MainViewModel setNetworkPolicy behaviour, preserved exactly.
 */
class DecisionViewModel(
    private val cbrMdpEngine: CbrMdpEngine,
    private val decisionSimulationUseCase: DecisionSimulationUseCase? = null,
    /**
     * ADR-6 SLICE 6: the STUDIO signal bus — this ViewModel COLLECTS the
     * decision feature's share of the studio's cross-feature projections.
     * Created once per Activity in MainActivity; nullable keeps the
     * constructor source-compatible with legacy constructions.
     */
    private val studioSignals: StudioSignalSource? = null
) : ViewModel() {

    data class DecisionUiState(
        /** The last engine verdict (execution event mirror OR simulation). */
        val latestDecision: DecisionResult? = null,
        /** The acquired case base (the engine's own truth). */
        val caseBaseList: List<DecisionCase> = emptyList(),
        /**
         * GAP-23 (Design Closure 2026): honestly WRITTEN around the engine
         * call — previously a no-writer field, so the preview spinner could
         * never appear.
         */
        val isSimulatingDecision: Boolean = false,
        // The state-vector sliders (the "what would the kernel choose" preview).
        val decisionTaskComplexity: Float = 0.6f,
        val decisionUncertainty: Float = 0.2f,
        /**
         * DISPLAY MIRROR ONLY (inherited from MainViewModel, ADR-6 slice 2):
         * the SESSION network policy is owned by StudioViewModel; this mirror
         * is synced from the studio signal bus so the simulation reads ONE
         * shared value. The persisted WORKSPACE policy (egress authority)
         * lives on the workspace row — a different, unrelated field.
         */
        val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(DecisionUiState())
    val state: StateFlow<DecisionUiState> = _state.asStateFlow()

    init {
        observeStudioSignals()
        // (Moved from MainViewModel.loadInitialData — same trigger point:
        // the feature VM's construction.) The preview is populated on first
        // open through the REAL use-case, so the cockpit never renders an
        // unexplained empty verdict.
        simulateDecision()
    }

    /**
     * The decision feature's share of the studio signal bus (moved verbatim
     * from MainViewModel.observeStudioSignals — the decision branch; the
     * Started branch stays with the activity feed's owner).
     */
    private fun observeStudioSignals() {
        val signals = studioSignals ?: return
        viewModelScope.launch {
            signals.collect { signal ->
                when (signal) {
                    is StudioSignal.ExecutionEvent -> {
                        when (val event = signal.event) {
                            is ExecutionEvent.DecisionMade -> _state.update {
                                it.copy(latestDecision = event.decision)
                            }
                            is ExecutionEvent.ObservationRecorded -> _state.update {
                                it.copy(
                                    decisionUncertainty = event.updatedUncertainty,
                                    caseBaseList = cbrMdpEngine.getCaseBase().getAllCases()
                                )
                            }
                            is ExecutionEvent.Completed, is ExecutionEvent.Error -> _state.update {
                                it.copy(caseBaseList = cbrMdpEngine.getCaseBase().getAllCases())
                            }
                            else -> Unit
                        }
                    }
                    is StudioSignal.NetworkPolicyChanged -> {
                        _state.update { it.copy(networkPolicy = signal.policy) }
                        simulateDecision()
                    }
                }
            }
        }
    }

    // --- State-vector sliders (live re-simulation preserved verbatim) ---

    fun updateDecisionComplexity(value: Float) {
        _state.update { it.copy(decisionTaskComplexity = value) }
        simulateDecision()
    }

    fun updateDecisionUncertainty(value: Float) {
        _state.update { it.copy(decisionUncertainty = value) }
        simulateDecision()
    }

    fun simulateDecision() {
        // Readiness-gated engine evaluation (defect family 5): the decision
        // may suspend on case-base load readiness, so it runs scoped.
        // GAP-23 (Design Closure 2026): the spinner flag is now WRITTEN
        // honestly around the engine call — it was previously a no-writer
        // field, so DecisionScreen's progress indicator could never appear.
        viewModelScope.launch {
            _state.update { it.copy(isSimulatingDecision = true) }
            try {
                simulateDecisionInternal()
            } finally {
                _state.update { it.copy(isSimulatingDecision = false) }
            }
        }
    }

    private suspend fun simulateDecisionInternal() {
        // GAP-19 (ADR-6 step 2): the simulation business rules (state
        // construction + candidate set + engine evaluation) live in
        // DecisionSimulationUseCase; the VM only projects the result.
        val useCase = decisionSimulationUseCase
        if (useCase == null) {
            // Honest fallback for legacy constructions without the use-case:
            // the preview is unavailable rather than fabricated.
            return
        }
        val current = _state.value
        // DISPLAY-HONESTY REPAIR (ADR-6 slice 6 — the slice-5 S-2 precedent):
        // the use-case invocation (engine evaluation over the persisted
        // learning state) can fail; an uncaught failure inside the
        // viewModelScope launch previously took the whole coroutine down
        // with NO user-visible surface. The honest degradation is an
        // explicit errorMessage — the feature's own channel, rendered by the
        // global snackbar.
        val outcome = runCatching {
            useCase(
                taskComplexity = current.decisionTaskComplexity,
                uncertaintyScore = current.decisionUncertainty,
                networkPolicy = current.networkPolicy
            )
        }.getOrElse { failure ->
            _state.update {
                it.copy(errorMessage = "تعذر محاكاة القرار: ${failure.localizedMessage}")
            }
            return
        }
        _state.update {
            it.copy(
                latestDecision = outcome.decision,
                caseBaseList = outcome.caseBase
            )
        }
    }

    /** Clears the feature's honest error channel (after the global snackbar). */
    fun clearErrorMessage() {
        _state.update { it.copy(errorMessage = null) }
    }
}
