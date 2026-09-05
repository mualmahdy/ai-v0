package com.example.domain.core.task.intelligence

import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskPriority

/**
 * ============================================================================
 * Task Intelligence Domain Models — Phase 5 (P1)
 * ============================================================================
 *
 * Closes the Task Intelligence gap (audit: 40–45% → ~55%) by adding:
 *
 *   1. Multi-level task decomposition (parent → subtask graph).
 *   2. Inter-task dependency DAG.
 *   3. Dynamic priorities + deadlines.
 *   4. Resource-aware scheduling hints.
 *
 * The audit found `TaskDefinition` had no `parentTaskId`/`subtaskIds`,
 * no hierarchical planner, no `ScheduledTaskExecutor`, and priorities
 * were ignored (FIFO only).
 */

data class TaskDependency(
    val parentTaskId: TaskId,
    val childTaskId: TaskId,
    val dependencyType: TaskDependencyType,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

enum class TaskDependencyType(val displayLabelAr: String) {
    BLOCKS("الأب يحجب الابن حتى يكتمل"),
    DEPENDS_ON("الابن يعتمد على الأب"),
    DECOMPOSED_FROM("الابن مُفكَّك من الأب"),
    PARALLEL_WITH("متوازٍ مع")
}

data class TaskGraphNode(
    val taskId: TaskId,
    val parentTaskId: TaskId?,
    val priority: TaskPriority,
    val depth: Int,
    val estimatedTokens: Int,
    val deadlineEpochMs: Long?,
    val isReady: Boolean
)

data class TaskDecomposition(
    val parentTaskId: TaskId,
    val subtasks: List<TaskDefinition>,
    val dependencies: List<TaskDependency>,
    val rationale: String
)

data class TaskSchedule(
    val orderedTaskIds: List<TaskId>,
    val parallelBatches: List<List<TaskId>>,
    val estimatedTotalDurationMs: Long,
    val estimatedTotalTokens: Int
)

data class TaskDeadlineEnforcementResult(
    val taskId: TaskId,
    val isOverdue: Boolean,
    val overdueMs: Long,
    val recommendedAction: DeadlineAction
)

enum class DeadlineAction { PROCEED, WARN, ESCALATE, CANCEL }
