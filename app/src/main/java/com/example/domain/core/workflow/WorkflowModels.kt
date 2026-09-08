package com.example.domain.core.workflow

import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.AcceptanceCriterion
import com.example.domain.core.task.TaskCapabilityRequirements
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
 * EXPLICIT ARTIFACT/DATAFLOW CONTRACT (defect family 7): complements the
 * dependency DAG with a data contract — a step DECLARES the named artifacts
 * it produces and the named artifacts it consumes. The engine validates
 * that every consumed artifact has a producer among the step's transitive
 * dependencies (a dangling consumer is a PLAN DEFECT, surfaced at
 * validation — never a silent empty input), stores the produced values at
 * runtime, and feeds them into consuming steps' prompts. Artifacts are
 * PERSISTED with the step state so resume restores the explicit dataflow.
 */
data class StepArtifactContract(
    /** Named outputs this step produces (name -> value at runtime). */
    val produces: List<ArtifactSpec> = emptyList(),
    /** Named artifacts (produced by upstream steps) this step consumes. */
    val consumes: List<String> = emptyList()
) {
    data class ArtifactSpec(
        val name: String,
        val description: String = ""
    )
}

/**
 * Directed node in a workflow plan with dynamic step-level capability requirements (Rule 8).
 */
data class StepNode(
    val id: String,
    val taskId: TaskId,
    val agentRole: AgentRole,
    val description: String,
    val requirements: TaskCapabilityRequirements = TaskCapabilityRequirements(),
    val expectedOutputs: List<String> = emptyList(),
    val evidenceRequirements: List<String> = emptyList(),
    val acceptanceCriteria: List<AcceptanceCriterion> = emptyList(),
    val dependencies: Set<String> = emptySet(),
    /**
     * REPORT GAP (workflow canonical-agent binding): when set, the step MUST
     * execute through this DURABLE agent from the canonical agent registry —
     * its system prompt, capabilities, budget, workspace scope, version and
     * lifecycle are all inherited from the real agent. When null, the engine
     * resolves a durable role-matching agent; synthetic fallback is last
     * resort ONLY when no registry is wired (pure JVM tests).
     */
    val assignedAgentId: String? = null,
    /** Optional exact model resource pin for this step (user choice per step). */
    val assignedModelId: String? = null,
    /** Explicit artifact/dataflow contract (defect family 7). */
    val artifactContract: StepArtifactContract = StepArtifactContract(),
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
