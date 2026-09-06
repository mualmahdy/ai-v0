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

/**
 * Process-wide execution host (audit 2026 fix).
 *
 * Previously agent executions ran inside `viewModelScope` — when the user
 * left the screen (or the system tore down the ViewModel) the execution was
 * cancelled mid-flight while the task row stayed RUNNING. Long agent tasks
 * now run in an APPLICATION scope that survives ViewModel destruction, and a
 * foreground service ([com.example.application.execution.AgentExecutionForegroundService])
 * raises the process priority while work is live so Android does not kill it.
 *
 * State survives configuration changes and navigation; it does NOT survive
 * process death — that is covered by the durable checkpoint + startup resume
 * path in `AgentOrchestrator`.
 */
object ExecutionHost {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<ExecutionEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Live stream of the current execution's events (for UI collection). */
    val events: SharedFlow<ExecutionEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    var currentJob: Job? = null
        private set

    fun publish(event: ExecutionEvent) {
        _events.tryEmit(event)
    }

    fun markStarted() {
        _isRunning.value = true
    }

    fun markFinished() {
        _isRunning.value = false
        currentJob = null
    }

    fun launch(block: suspend () -> Unit): Job {
        currentJob?.cancel()
        val job = scope.launch {
            try {
                markStarted()
                block()
            } finally {
                markFinished()
            }
        }
        currentJob = job
        return job
    }

    fun cancelCurrent() {
        currentJob?.cancel()
        currentJob = null
        _isRunning.value = false
    }
}
