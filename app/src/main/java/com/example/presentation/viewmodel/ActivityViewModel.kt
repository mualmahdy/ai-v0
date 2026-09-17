package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.ports.observability.TelemetryPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ActivityViewModel — ADR-6 slice 7 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The UNIFIED ACTIVITY FEED left the MainViewModel per ADR-6 option-ج,
 * with its whole dependency set (telemetryPort + the workspace scoping +
 * the studio signal bus's ACTIVITY stake).
 *
 * BEHAVIOR (moved verbatim): the per-execution trace binding —
 * `ExecutionEvent.Started` carries the REAL execution id; the feed
 * switches to that execution's trace stream when set and falls back to
 * the workspace-scoped recent window otherwise (never to an empty-id
 * query that matches nothing — the UNIFIED ACTIVITY TRACE WIRING FIX).
 * Both flows are GAP-04 workspace-scoped: mid-execution the trace stays
 * bound to the live execution (the execution pins its own workspace at
 * launch); otherwise it follows the ACTIVE workspace — traces and audit
 * rows from other workspaces are invisible.
 *
 * SIGNAL-BUS SEAM (the GovernanceViewModel pattern): this feature
 * collects its OWN stake from the studio signal bus — the live execution
 * id (Started). No ViewModel holds a reference to another; the wiring is
 * the same per-Activity bus MainActivity created in slice 2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    /**
     * The telemetry read port (Room-backed in production). Nullable for
     * source compatibility — the honest fallback is an EMPTY feed, never
     * a fabricated one.
     */
    private val telemetryPort: TelemetryPort?,
    workspaceRuntimeService: WorkspaceRuntimeService,
    studioSignals: StudioSignalSource? = null
) : ViewModel() {

    /**
     * The execution id of the CURRENTLY RUNNING (or most recently
     * finished) Studio execution — the activity feed's per-execution
     * trace binding.
     */
    private val activeExecutionId = MutableStateFlow<String?>(null)

    /** The live execution trace (per-execution or the workspace window). */
    val activeExecutionTrace: StateFlow<List<ExecutionTraceNode>> =
        telemetryPort?.let { port ->
            // GAP-04 (Design Closure 2026): the feed reflects ONLY the active
            // (or executing) workspace. Mid-execution the trace stays bound
            // to the live execution; otherwise it follows the ACTIVE
            // workspace — traces from other workspaces are invisible.
            combine(flowOf(port), activeExecutionId, workspaceRuntimeService.activeWorkspace) { p, id, ws -> Triple(p, id, ws?.id) }
                .flatMapLatest { (p, id, wsId) ->
                    if (!id.isNullOrBlank()) {
                        // A live execution → that execution's trace (the
                        // execution pins its own workspace at launch).
                        p.traceForExecution(id)
                    } else {
                        // No live execution → the workspace-scoped recent window.
                        p.recentTraceNodes(wsId, 50)
                    }
                }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        } ?: MutableStateFlow(emptyList<ExecutionTraceNode>()).asStateFlow()

    /** The workspace-scoped recent audit events (the security timeline). */
    val recentAuditEvents: StateFlow<List<AuditEvent>> =
        telemetryPort?.let { port ->
            // GAP-04: audit events are workspace-scoped too — the global
            // read previously leaked other workspaces' audit rows into the
            // Unified Activity Feed.
            workspaceRuntimeService.activeWorkspace
                .flatMapLatest { ws -> port.auditEvents(ws?.id, 100) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        } ?: MutableStateFlow(emptyList<AuditEvent>()).asStateFlow()

    init {
        // (Moved verbatim from MainViewModel.observeStudioSignals — after
        // the slice-6 extraction this Started stake was the collector's
        // whole remaining body; it moves with the feed it powers.)
        studioSignals?.let { signals ->
            viewModelScope.launch {
                signals.collect { signal ->
                    when (signal) {
                        is StudioSignal.ExecutionEvent -> {
                            when (val event = signal.event) {
                                is ExecutionEvent.Started -> activeExecutionId.value = event.executionId
                                else -> Unit
                            }
                        }
                        is StudioSignal.NetworkPolicyChanged -> Unit
                    }
                }
            }
        }
    }
}
