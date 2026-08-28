package com.example.domain.core.workflow

import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskId

/**
 * Unique identifier for a workflow instance.
 */
@JvmInline
value class WorkflowId(val value: String)

/**
 * Execution topology for the workflow.
 */
enum class ExecutionMode {
    SEQUENTIAL,
    DIRECTED_ACYCLIC_GRAPH,
    FAN_OUT_PARALLEL
}

/**
 * Step execution status within a workflow.
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    DEGRADED,
    FAILED,
    SKIPPED
}

/**
 * Directed node in a workflow plan.
 */
data class StepNode(
    val id: String,
    val taskId: TaskId,
    val agentRole: AgentRole,
    val description: String,
    val dependencies: Set<String> = emptySet(),
    val status: StepStatus = StepStatus.PENDING,
    val outputSummary: String? = null,
    val durationMs: Long = 0L
)

/**
 * Immutable declaration of a workflow plan.
 */
data class WorkflowPlan(
    val id: WorkflowId,
    val goal: String,
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val steps: List<StepNode>
)

/**
 * Failures that may occur during workflow planning or DAG resolution.
 */
sealed interface WorkflowFailure {
    data class CyclicDependencyDetected(val cycleNodes: List<String>) : WorkflowFailure
    data class StepExecutionFailed(val stepId: String, val reason: String) : WorkflowFailure
    data class TimeoutExceeded(val workflowId: String, val elapsedMs: Long) : WorkflowFailure
    data class AbortedBySecurityGuard(val stepId: String, val reason: String) : WorkflowFailure
    data class CancelledByUser(val workflowId: String) : WorkflowFailure
}

/**
 * Comprehensive execution report for a workflow run.
 */
data class WorkflowExecutionReport(
    val workflowId: WorkflowId,
    val goal: String,
    val overallOutcome: Outcome<String, WorkflowFailure>,
    val stepStatuses: Map<String, StepStatus>,
    val totalDurationMs: Long,
    val totalTokensConsumed: Int
)
