package com.example.application.orchestration

import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * GAP-11 (Design Closure 2026) — the task board read authority.
 *
 * The tasks surface (TasksScreen) previously had NO live task list at all:
 * `UiState.activeTasks` was write-only and TaskDao.getAllTasksFlow /
 * getTasksForWorkspaceFlow had zero production callers. This service owns
 * the workspace-scoped board read; resumption stays with
 * [AgentOrchestrator.resumeTask] (the durable-execution authority).
 *
 * `workspaceId == null` = no active workspace → an HONEST empty board
 * (never a cross-workspace leak — same policy as the GAP-04 feed).
 */
class TaskBoardService(
    private val taskDao: TaskDao
) {
    /** Live, newest-first task rows for a workspace (null = empty board). */
    fun observeBoard(workspaceId: String?): Flow<List<TaskEntity>> =
        if (workspaceId == null) flowOf(emptyList())
        else taskDao.getTasksForWorkspaceFlow(workspaceId)
}
