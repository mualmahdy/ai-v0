package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Phase 5 — Telemetry / Observability / Audit / Workflow / Policy entities
 * ============================================================================
 *
 * All tables here are introduced by `MIGRATION_7_TO_8` (see `AppDatabase.kt`).
 * The migration is purely additive — no existing table is altered, so all
 * prior user data (workspaces, RAG, providers, decision cases, Q-table)
 * survives the upgrade cleanly.
 */

/**
 * Append-only metric event log. Every `MetricSample` becomes one row.
 *
 * For high-frequency counters we ALSO keep an in-process aggregator in
 * `TelemetryService`, but the durable row is the source of truth for
 * cross-session analytics and crash recovery.
 */
@Entity(
    tableName = "metric_events",
    indices = [
        Index("metricType"),
        Index("dimensionsKey"),
        Index("recordedAtEpochMs"),
        Index("executionId")
    ]
)
data class MetricEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val metricType: String,
    val dimensionsKey: String,
    val executionId: String?,
    val sessionId: String?,
    val workspaceId: String?,
    val providerId: String?,
    val toolName: String?,
    val agentId: String?,
    val resourceType: String?,
    val actionType: String?,
    val value: Long,
    val attributesJson: String,
    val recordedAtEpochMs: Long
)

/**
 * Security audit trail. Each row is one security-relevant decision:
 * ALLOW/DENY/REQUIRE_CONSENT/DEGRADE, with the actor, resource, reason,
 * and the workspace it happened in. Closes the Security Governance gap
 * "no AuditLog entity".
 */
@Entity(
    tableName = "audit_trail",
    indices = [
        Index("severity"),
        Index("actor"),
        Index("resourceType"),
        Index("resourceId"),
        Index("workspaceId"),
        Index("occurredAtEpochMs")
    ]
)
data class AuditTrailEntity(
    @PrimaryKey
    val id: String,
    val severity: String,
    val actor: String,
    val action: String,
    val resourceType: String,
    val resourceId: String,
    val decision: String,
    val reason: String,
    val workspaceId: String?,
    val attributesJson: String,
    val occurredAtEpochMs: Long
)

/**
 * Execution trace node — one row per (executionId, stepIndex, action).
 * Powers the Unified Activity Feed screen so the user can see the full
 * decision→action→observation chain at a glance.
 */
@Entity(
    tableName = "execution_trace_nodes",
    indices = [
        Index("executionId"),
        Index("stepIndex"),
        Index("startedAtEpochMs"),
        Index("workspaceId")
    ]
)
data class ExecutionTraceNodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val executionId: String,
    val stepIndex: Int,
    val actionType: String,
    val targetResourceId: String?,
    val agentId: String?,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val durationMs: Long?,
    val outcome: String,
    val summary: String,
    val observationSummary: String?,
    /**
     * P1-10 (audit 2026 §20 — trace workspace query has no workspace
     * predicate): owning workspace id from the pinned execution→workspace
     * binding. Null = honestly UNATTRIBUTED (legacy rows before v15).
     */
    val workspaceId: String? = null
)

/**
 * Persistent workflow execution state. Closes the Workflow Intelligence gap
 * "WorkflowPlan and WorkflowExecutionReport are NOT persisted to Room".
 *
 * One row per workflow execution. Steps are stored in
 * `workflow_step_states` so partial completion can be resumed.
 */
@Entity(
    tableName = "workflow_executions",
    indices = [
        Index("workspaceId"),
        Index("lifecycleState"),
        Index("startedAtEpochMs")
    ]
)
data class WorkflowExecutionEntity(
    @PrimaryKey
    val workflowId: String,
    val workspaceId: String,
    val planJson: String,
    val lifecycleState: String, // RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, COMPENSATING
    val currentStepIndex: Int,
    val totalSteps: Int,
    val startedAtEpochMs: Long,
    val lastCheckpointAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val failureReason: String?,
    val cancellationReason: String?
)

/**
 * Per-step workflow state. Allows the engine to skip already-completed
 * steps when resuming after process death (closes APP-P0-07 sibling gap
 * for workflows).
 */
@Entity(
    tableName = "workflow_step_states",
    indices = [
        Index("workflowId"),
        Index("stepId"),
        Index("status")
    ]
)
data class WorkflowStepStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val workflowId: String,
    val stepId: String,
    val stepIndex: Int,
    val status: String, // PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, COMPENSATED
    val outputSummary: String?,
    val durationMs: Long?,
    val startedAtEpochMs: Long?,
    val completedAtEpochMs: Long?,
    // GAP-01 (Design Closure 2026): mirrors MIGRATION_7_TO_8's
    // workflow_step_states DDL (attemptCount INTEGER NOT NULL DEFAULT 0).
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    val lastErrorMessage: String?,
    /**
     * Durable ARTIFACT/DATAFLOW payloads (defect family 7): step outputs
     * consumed by later steps survive process death, so resume restores
     * the explicit dataflow — not just a 200-char summary.
     */
    val artifactsJson: String? = null
)

/**
 * Tool-specific audit record. Captures every tool invocation: who called
 * it, with what arguments hash, what was the outcome, how long it took.
 * Closes the Tool Ecosystem gap "no tool audit trail".
 */
@Entity(
    tableName = "tool_audit_log",
    indices = [
        Index("toolName"),
        Index("executionId"),
        Index("callerAgentId"),
        Index("outcome"),
        Index("occurredAtEpochMs")
    ]
)
data class ToolAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val toolName: String,
    val toolVersion: String,
    val executionId: String,
    val callerAgentId: String?,
    val workspaceId: String?,
    val argumentsHash: String,
    val outcome: String, // SUCCESS, DEGRADED, FAILURE
    val failureCode: String?,
    val durationMs: Long,
    val tokenCostEstimate: Int,
    val occurredAtEpochMs: Long
)

/**
 * Per-agent per-tool permission grant. Closes the Security Governance gap
 * "no fine-grained authorization, no per-agent or per-tool permissions".
 *
 * REPAIR (defect family 2 — workspace context is part of authorization):
 * a grant may be scoped to ONE workspace. `workspaceId = null` means a
 * GLOBAL grant (explicitly unscoped — the only grants that authorize
 * executions with no workspace attribution).
 */
@Entity(
    tableName = "permission_grants",
    indices = [
        Index("principalType"),
        Index("principalId"),
        Index("resourceType"),
        Index("resourceId"),
        Index("workspaceId")
    ]
)
data class PermissionGrantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val principalType: String, // AGENT, WORKSPACE, USER, EXTENSION
    val principalId: String,
    val resourceType: String, // TOOL, RESOURCE, CAPABILITY
    val resourceId: String,
    val permission: String, // EXECUTE, READ, WRITE, ADMIN
    val isAllowed: Boolean,
    val grantedBy: String,
    val grantedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    /** Workspace scope (null = explicitly GLOBAL). */
    val workspaceId: String? = null
)

/**
 * Per-agent per-workspace memory namespace. Closes the Memory System gap
 * "no per-agent memory namespace, all agents share the global memory repo".
 *
 * The actual memory rows still live in `memory_records`; this table is the
 * ACL that scopes retrieval. We add a `workspace_id` and `agent_id` column
 * via migration to `memory_records` itself (see `MIGRATION_7_TO_8`).
 */
@Entity(
    tableName = "agent_memory_namespaces",
    indices = [
        Index("workspaceId"),
        Index("agentId"),
        Index("isActive")
    ]
)
data class AgentMemoryNamespaceEntity(
    @PrimaryKey
    val namespaceId: String,
    val workspaceId: String,
    val agentId: String,
    val memoryScope: String, // PRIVATE, SHARED_WITH_WORKSPACE, GLOBAL
    val createdAtEpochMs: Long,
    val isActive: Boolean
)

/**
 * Tool lifecycle state persistence. Closes the Tool Ecosystem gap "no
 * discover→register→validate→authorize→expose→execute→observe→audit→revoke
 * lifecycle".
 */
@Entity(
    tableName = "tool_lifecycle_states",
    indices = [
        Index("toolName"),
        Index("lifecycleState"),
        Index("isEnabled")
    ]
)
data class ToolLifecycleStateEntity(
    @PrimaryKey
    val toolId: String,
    val toolName: String,
    val version: String,
    val lifecycleState: String, // DISCOVERED, REGISTERED, VALIDATED, AUTHORIZED, EXPOSED, OBSERVED, REVOKED
    val isEnabled: Boolean,
    val timeoutMs: Long,
    val maxRetries: Int,
    val retryBackoffMs: Long,
    val registeredAtEpochMs: Long,
    val lastValidatedAtEpochMs: Long?,
    val lastExecutedAtEpochMs: Long?,
    val revokedAtEpochMs: Long?,
    val revokeReason: String?
)

/**
 * Cached tool health snapshot. Updated by the `ToolHealthMonitor` after
 * every call. Used by `DecisionService` for capability-aware routing.
 */
@Entity(
    tableName = "tool_health_snapshots",
    indices = [
        Index("toolId"),
        // Matches MIGRATION_7_TO_8: "CREATE INDEX index_tool_health_snapshots_isHealthy
        // ON tool_health_snapshots(circuitState)" — the healthy flag is derived from
        // circuitState + failure rates, so the named index covers that column.
        Index(value = ["circuitState"], name = "index_tool_health_snapshots_isHealthy")
    ]
)
data class ToolHealthSnapshotEntity(
    @PrimaryKey
    val toolId: String,
    val totalCalls: Long,
    val successCount: Long,
    val failureCount: Long,
    val degradedCount: Long,
    val averageLatencyMs: Double,
    val p95LatencyMs: Long,
    val lastFailureCode: String?,
    val lastErrorMessage: String?,
    val circuitState: String, // CLOSED, OPEN, HALF_OPEN
    val openedAtEpochMs: Long?,
    val lastUpdatedEpochMs: Long
)
