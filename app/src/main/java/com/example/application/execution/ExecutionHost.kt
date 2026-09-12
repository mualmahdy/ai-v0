package com.example.application.execution

import com.example.domain.core.events.ExecutionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide execution host — now a MULTI-EXECUTION REGISTRY (gap-closure
 * P0-01 / P1-20).
 *
 * Previously this host held ONE `currentJob` and `launch()` CANCELLED the
 * previous execution — a second task silently killed the first, `isRunning`
 * was a global lie, and the foreground service could not tell which
 * execution it was protecting.
 *
 * Contract now:
 *  - [launch] registers an execution under a caller-supplied key (taskId).
 *    It NEVER cancels other executions — concurrent executions are the
 *    supported case. Re-launching the SAME key replaces only that key's
 *    previous job (honest "user re-ran this task" semantics).
 *  - [cancel] cancels exactly ONE execution. The legacy [cancelCurrent]
 *    remains only as a deprecated cancel-ALL shim for old call sites.
 *  - [activeExecutions] is the live per-execution registry (StateFlow) the
 *    foreground service and the UI subscribe to.
 *  - (the deprecated `isRunning`/`currentJob`/`publish`/`cancelCurrent`
 *    shims were deleted by GAP-08 — zero callers existed)
 *    running") so existing collectors keep compiling.
 *
 * State survives configuration changes and navigation; it does NOT survive
 * process death — that is covered by the durable checkpoint + canonical
 * execution context + startup resume path in `AgentOrchestrator`.
 */
object ExecutionHost {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // P1-10 (audit 2026 §20): the legacy `_events` SharedFlow
    // (extraBufferCapacity=256, DROP_OLDEST, tryEmit) had ZERO consumers —
    // it was dead wiring whose only possible behaviour under a burst was
    // SILENT EVENT LOSS. It is removed; the ONE real execution event bus is
    // the orchestrator's backpressured publisher, to which telemetry and
    // radar subscribe. No second, lossy bus remains.

    /** One live execution registered under its key (convention: taskId). */
    data class ExecutionHandle(
        val key: String,
        val job: Job,
        val startedAtEpochMs: Long,
        /**
         * P1-14 (audit 2026 §7/§25 — no execution drain before workspace
         * deletion): the workspace this execution is attributed to. The
         * delete path CANCELS and DRAINS these jobs BEFORE removing the
         * workspace — an execution can never outlive the workspace whose
         * sandbox/authz scope it executes in (no orphaned jobs writing into
         * a deleted workspace root).
         */
        val workspaceId: String? = null
    )

    private val handles = ConcurrentHashMap<String, ExecutionHandle>()

    /**
     * P1-12 (audit 2026 §25 — same-key relaunch is not atomic): the
     * cancel → launch → replace-handle sequence is now guarded by ONE
     * monitor, so two concurrent `launch("same-key")` calls can never
     * interleave into "A cancels, B cancels, A registers, B registers"
     * (which previously left TWO live jobs under one key — the first job
     * leaked, uncancellable through the host).
     */
    private val launchMonitor = Any()

    private val _activeExecutions = MutableStateFlow<Map<String, ExecutionHandle>>(emptyMap())
    val activeExecutions: StateFlow<Map<String, ExecutionHandle>> = _activeExecutions.asStateFlow()

    private val _isAnyRunning = MutableStateFlow(false)

    /** TRUE while at least one execution is live. */
    val isAnyRunning: StateFlow<Boolean> = _isAnyRunning.asStateFlow()

    init {
        // Keep the derived any-running flag in sync with the registry.
        scope.launch {
            activeExecutions.collect { live ->
                _isAnyRunning.value = live.isNotEmpty()
            }
        }
    }

    /**
     * Launches ONE execution under [key] (convention: the taskId). Does NOT
     * cancel any OTHER execution. Re-launching the same key replaces that
     * key's previous job only — ATOMICALLY (see [launchMonitor]).
     */
    fun launch(key: String, block: suspend () -> Unit): Job =
        launch(key, workspaceId = null, block = block)

    /**
     * P1-14: [launch] with WORKSPACE ATTRIBUTION — the handle records the
     * workspace whose scope the execution runs in, so workspace deletion can
     * drain exactly the executions it would orphan.
     */
    fun launch(key: String, workspaceId: String?, block: suspend () -> Unit): Job = synchronized(launchMonitor) {
        handles.remove(key)?.job?.cancel()
        val job = scope.launch {
            block()
        }
        val handle = ExecutionHandle(
            key = key,
            job = job,
            startedAtEpochMs = System.currentTimeMillis(),
            workspaceId = workspaceId
        )
        handles[key] = handle
        publishState()
        job.invokeOnCompletion { unregisterIfCurrent(key, job) }
        job
    }

    /** Cancels exactly ONE execution (no-op if not running). */
    fun cancel(key: String) = synchronized(launchMonitor) {
        handles.remove(key)?.job?.cancel()
        publishState()
    }

    /** TRUE when [key] is currently executing. */
    fun isExecuting(key: String): Boolean = handles.containsKey(key)

    fun handleFor(key: String): ExecutionHandle? = handles[key]

    /**
     * P1-14: executions currently attributed to [workspaceId]
     * (unattributed executions are NOT affected).
     */
    fun executionsFor(workspaceId: String): List<ExecutionHandle> =
        handles.values.filter { it.workspaceId == workspaceId }

    /**
     * P1-14 (audit 2026 — "deleting a workspace while executions are still
     * running"): CANCELS every execution attributed to [workspaceId] and
     * AWAITS their completion (bounded by [timeoutMs]). Returns TRUE when
     * fully drained. The delete path REFUSES the deletion when this returns
     * false (fail-closed: a stuck execution must never leave the workspace
     * half-deleted) — honest refusal beats silent orphaning.
     */
    suspend fun drainWorkspace(workspaceId: String, timeoutMs: Long = 5_000L): Boolean {
        val jobs = synchronized(launchMonitor) {
            handles.values.filter { it.workspaceId == workspaceId }
                .onEach { it.job.cancel() }
                .map { it.job }
        }
        if (jobs.isEmpty()) return true
        val drained = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            jobs.forEach { it.join() }
        }
        return drained != null
    }

    // --- internals ---

    private fun unregisterIfCurrent(key: String, job: Job) {
        val current = handles[key]
        if (current?.job === job) {
            handles.remove(key)
            publishState()
        }
    }

    private fun publishState() {
        _activeExecutions.value = handles.toMap()
    }
}
