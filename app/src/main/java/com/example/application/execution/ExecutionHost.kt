package com.example.application.execution

import com.example.domain.core.events.ExecutionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *  - `isRunning` is kept as a deprecated derived alias ("is ANY execution
 *    running") so existing collectors keep compiling.
 *
 * State survives configuration changes and navigation; it does NOT survive
 * process death — that is covered by the durable checkpoint + canonical
 * execution context + startup resume path in `AgentOrchestrator`.
 */
object ExecutionHost {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<ExecutionEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Live stream of ALL execution events (each event carries its executionId). */
    val events: SharedFlow<ExecutionEvent> = _events.asSharedFlow()

    /** One live execution registered under its key (convention: taskId). */
    data class ExecutionHandle(
        val key: String,
        val job: Job,
        val startedAtEpochMs: Long
    )

    private val handles = ConcurrentHashMap<String, ExecutionHandle>()

    private val _activeExecutions = MutableStateFlow<Map<String, ExecutionHandle>>(emptyMap())
    val activeExecutions: StateFlow<Map<String, ExecutionHandle>> = _activeExecutions.asStateFlow()

    private val _isAnyRunning = MutableStateFlow(false)

    /** TRUE while at least one execution is live. */
    val isAnyRunning: StateFlow<Boolean> = _isAnyRunning.asStateFlow()

    @Deprecated(
        message = "Global single-job semantics removed (P0-01). Use isAnyRunning.",
        replaceWith = ReplaceWith("isAnyRunning")
    )
    val isRunning: StateFlow<Boolean> get() = isAnyRunning

    @Deprecated(
        message = "Single currentJob removed (P0-01). Use activeExecutions / handleFor(key).",
        replaceWith = ReplaceWith("handleFor(key)")
    )
    val currentJob: Job? get() = null

    init {
        // Keep the derived any-running flag in sync with the registry.
        scope.launch {
            activeExecutions.collect { live ->
                _isAnyRunning.value = live.isNotEmpty()
            }
        }
    }

    fun publish(event: ExecutionEvent) {
        _events.tryEmit(event)
    }

    /**
     * Launches ONE execution under [key] (convention: the taskId). Does NOT
     * cancel any OTHER execution. Re-launching the same key replaces that
     * key's previous job only.
     */
    fun launch(key: String, block: suspend () -> Unit): Job {
        cancel(key)
        val job = scope.launch {
            block()
        }
        val handle = ExecutionHandle(key = key, job = job, startedAtEpochMs = System.currentTimeMillis())
        handles[key] = handle
        publishState()
        job.invokeOnCompletion { unregisterIfCurrent(key, job) }
        return job
    }

    /** Cancels exactly ONE execution (no-op if not running). */
    fun cancel(key: String) {
        handles.remove(key)?.job?.cancel()
        publishState()
    }

    /**
     * Legacy cancel-ALL (deprecated): the old UI "stop" button had global
     * semantics. Prefer [cancel] with the specific execution key.
     */
    @Deprecated(
        message = "Cancels ALL executions. Use cancel(key) for one execution (P0-01).",
        replaceWith = ReplaceWith("cancel(key)")
    )
    fun cancelCurrent() {
        for (k in handles.keys.toList()) cancel(k)
    }

    /** TRUE when [key] is currently executing. */
    fun isExecuting(key: String): Boolean = handles.containsKey(key)

    fun handleFor(key: String): ExecutionHandle? = handles[key]

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
