package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.TaskBoardService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.task.TaskLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * GAP-11 (Design Closure 2026) — the TASKS feature ViewModel.
 *
 * ADR-6 freeze compliance: the resumable-tasks surface lives in a NEW
 * feature ViewModel (MainViewModel is frozen for new features). It exposes:
 *  - a live, workspace-scoped task board (non-terminal + recently-finished
 *    rows, bounded to [BOARD_LIMIT] entries);
 *  - `resume(taskId)` → [AgentOrchestrator.resumeTask], which restores the
 *    durable checkpoint and REPLAYS completed intents (idempotency ledger)
 *    instead of re-executing them.
 *
 * Timed-out tasks appear as degraded rows in state TIMED_OUT with a Resume
 * action — previously they were persisted as "WAITING" (indistinguishable
 * from user-input pauses) and resumeTask had no production caller at all.
 */
class TasksViewModel(
    private val taskBoardService: TaskBoardService,
    private val agentOrchestrator: AgentOrchestrator,
    workspaceRuntimeService: WorkspaceRuntimeService
) : ViewModel() {

    /** Board is bounded — the full history stays in persistence, not memory. */
    private val boardLimit: Int = 30

    /** One honest UI-facing task row (subset of TaskEntity the surface needs). */
    data class TaskRow(
        val id: String,
        val title: String,
        val state: String,
        val isDegraded: Boolean,
        val degradedReason: String?,
        val totalTokensConsumed: Int,
        val durationMs: Long,
        val updatedAtEpochMs: Long,
        val isResumable: Boolean
    )

    val board: StateFlow<List<TaskRow>> = workspaceRuntimeService.activeWorkspace
        .flatMapLatest { workspace -> taskBoardService.observeBoard(workspace?.id) }
        .map { rows ->
            rows.take(boardLimit).map { entity ->
                val parsedState = runCatching {
                    TaskLifecycleState.valueOf(entity.lifecycleState)
                }.getOrNull()
                TaskRow(
                    id = entity.id,
                    title = entity.goal.ifBlank { entity.rawPrompt }.take(80),
                    state = entity.lifecycleState,
                    isDegraded = entity.isDegraded,
                    degradedReason = entity.degradedReason,
                    totalTokensConsumed = entity.totalTokensConsumed,
                    durationMs = entity.durationMs,
                    updatedAtEpochMs = entity.updatedAtEpochMs,
                    isResumable = parsedState != null &&
                            parsedState in TaskLifecycleState.RESUMABLE
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One in-flight resume at a time (button disabled while resuming). */
    private val _resumingTaskId = MutableStateFlow<String?>(null)
    val resumingTaskId: StateFlow<String?> = _resumingTaskId.asStateFlow()

    /**
     * Resumes a task through the durable-execution authority. Execution
     * events flow through the orchestrator's telemetry bus; the board flow
     * reflects state changes live (Room emits on every task write).
     */
    fun resume(taskId: String) {
        if (_resumingTaskId.value != null) return // one at a time — honest
        _resumingTaskId.value = taskId
        viewModelScope.launch {
            try {
                runCatching {
                    agentOrchestrator.resumeTask(taskId).collect { /* events flow through the telemetry bus */ }
                }
            } finally {
                _resumingTaskId.value = null
            }
        }
    }
}
