package com.example.presentation.viewmodel

import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * ============================================================================
 * StudioSignal — the STUDIO feature's outbound signal bus (ADR-6 slice 2,
 * Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * GAP-19/21: when the conversation runtime left the MainViewModel, the
 * execution event stream stopped being a single collector's side effect.
 * Other features still have an honest stake in studio execution events:
 *
 *  - the ACTIVITY FEED follows the live execution id (Started) for the
 *    per-execution trace stream;
 *  - the DECISION preview mirrors DecisionMade / ObservationRecorded /
 *    Completed / Error (case-base + uncertainty are decision surfaces);
 *  - the SESSION network policy is an input to the decision simulation and
 *    the governance radar snapshot.
 *
 * The seam is an explicit, dumb [MutableSharedFlow] created once per
 * Activity (MainActivity) and injected into BOTH ViewModels:
 * [StudioViewModel] EMITS, MainViewModel COLLECTS. No ViewModel holds a
 * reference to the other; the wiring is deterministic and testable in
 * isolation (a test injects its own bus and asserts exactly what the
 * feature publishes).
 *
 * Only the CROSS-FEATURE subset of events is published — ContentChunk /
 * UsageBudgetUpdate / Degraded stay private to the Studio feature state.
 */
sealed interface StudioSignal {

    /**
     * A studio execution event other features project into their state.
     * (The parameter type is fully qualified on purpose: a bare
     * `ExecutionEvent` inside StudioSignal would resolve to THIS nested
     * class, making the constructor recursive.)
     */
    data class ExecutionEvent(val event: com.example.domain.core.events.ExecutionEvent) : StudioSignal

    /**
     * The session network policy changed (an execution-time input the
     * decision preview and the governance snapshot re-derive from).
     */
    data class NetworkPolicyChanged(val policy: NetworkPolicy) : StudioSignal
}

/** The per-Activity studio signal bus type (created in MainActivity). */
typealias StudioSignalBus = MutableSharedFlow<StudioSignal>

/** Read-only view handed to consumers (MainViewModel). */
typealias StudioSignalSource = SharedFlow<StudioSignal>
