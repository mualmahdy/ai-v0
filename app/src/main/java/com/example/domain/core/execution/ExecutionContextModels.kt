package com.example.domain.core.execution

import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskId

/**
 * ============================================================================
 * Canonical Execution Context — gap-closure (root cause 1 / 2 / 3)
 * ============================================================================
 *
 * THE single durable unit that binds one execution to every identity it
 * operates under (previously these links were scattered across Workspace /
 * Session / ExecutionLog / Memory / Telemetry rows and re-derived
 * independently by each service, which caused workspace switching,
 * attribution drift, unstable execution identity and agent migration on
 * resume).
 *
 * Contract:
 *  - Created ONCE when an execution starts; IMMUTABLE afterwards.
 *  - [executionId] is STABLE across resume attempts (a resumed execution is
 *    the SAME execution, attempt + 1 — not a new execution).
 *  - [workspaceId] is PINNED at launch: telemetry, memory, resources and
 *    accounting for this execution resolve against THIS id even if the user
 *    switches the active workspace mid-run.
 *  - [agentId] + [agentRole] are PINNED: a resume that cannot find the
 *    original agent fails honestly instead of silently migrating to another
 *    agent with different prompts/capabilities/permissions.
 *  - [projectId] is the workspace-scoped sandbox project (nullable = not
 *    yet bound; NEVER an implicit 1L fallback).
 */
data class CanonicalExecutionContext(
    val executionId: String,
    val taskId: TaskId,
    val workspaceId: String,
    val projectId: Long? = null,
    val agentId: AgentId,
    val agentRole: AgentRole,
    val modelId: String? = null,
    val parentTaskId: String? = null,
    val delegationDepth: Int = 0,
    /** 1 for a fresh execution; incremented on every durable resume. */
    val attempt: Int = 1,
    val startedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(executionId.isNotBlank()) { "executionId must not be blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(attempt >= 1) { "attempt must be >= 1" }
    }

    fun nextAttempt(): CanonicalExecutionContext = copy(attempt = attempt + 1)
}

/**
 * Honest binding outcome when an execution cannot be bound to a canonical
 * context (fail-closed — never a silent "default" workspace).
 */
sealed interface ContextBinding {
    data class Bound(val context: CanonicalExecutionContext) : ContextBinding

    /** [reason] is machine-readable (WORKSPACE_NOT_READY / AGENT_UNAVAILABLE / ...). */
    data class Unbound(val reason: String, val diagnostic: String) : ContextBinding
}
