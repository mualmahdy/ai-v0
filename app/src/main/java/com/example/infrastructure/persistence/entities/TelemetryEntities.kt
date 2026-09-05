package com.example.infrastructure.persistence.entities

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
        Index("metric_type"),
        Index("dimensions_key"),
        Index("recorded_at_epoch_ms"),
        Index("execution_id")
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
        Index("resource_type"),
        Index("resource_id"),
        Index("workspace_id"),
        Index("occurred_at_epoch_ms")
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
 * Periodic health probe samples (one row per probe). Allows the dashboard
 * to render a timeline instead of just the latest snapshot.
 */
@Entity(
    tableName = "health_probes",
    indices = [
        Index("resource_id"),
        Index("resource_type"),
        Index("probed_at_epoch_ms")
    ]
)
data class HealthProbeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val resourceId: String,
    val resourceType: String,
    val isHealthy: Boolean,
    val latencyMs: Long,
    val errorMessage: String?,
    val probedAtEpochMs: Long
)

/**
 * Execution trace node — one row per (executionId, stepIndex, action).
 * Powers the Unified Activity Feed screen so the user can see the full
 * decision→action→observation chain at a glance.
 */
@Entity(
    tableName = "execution_trace_nodes",
    indices = [
        Index("execution_id"),
        Index("step_index"),
        Index("started_at_epoch_ms")
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
    val observationSummary: String?
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
        Index("workspace_id"),
        Index("lifecycle_state"),
        Index("started_at_epoch_ms")
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
        Index("workflow_id"),
        Index("step_id"),
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
    val attemptCount: Int = 0,
    val lastErrorMessage: String?
)

/**
 * Tool-specific audit record. Captures every tool invocation: who called
 * it, with what arguments hash, what was the outcome, how long it took.
 * Closes the Tool Ecosystem gap "no tool audit trail".
 */
@Entity(
    tableName = "tool_audit_log",
    indices = [
        Index("tool_name"),
        Index("execution_id"),
        Index("caller_agent_id"),
        Index("outcome"),
        Index("occurred_at_epoch_ms")
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
 */
@Entity(
    tableName = "permission_grants",
    indices = [
        Index("principal_type"),
        Index("principal_id"),
        Index("resource_type"),
        Index("resource_id")
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
    val expiresAtEpochMs: Long?
)

/**
 * Persistent policy version for the CBR-MDP / decision engine. Closes the
 * Evolution/Self-Improvement gap "no PolicyVersion entity, no history of
 * agent/decision policy changes".
 *
 * Each promotion creates a new row; the active policy is the latest
 * `isPromoted = true` row. Rollback = mark newer rows `isPromoted = false`.
 */
@Entity(
    tableName = "policy_versions",
    indices = [
        Index("policy_kind"),
        Index("is_promoted"),
        Index("created_at_epoch_ms")
    ]
)
data class PolicyVersionEntity(
    @PrimaryKey
    val versionId: String,
    val policyKind: String, // CBR_MDP_Q_TABLE, ROUTING, AGENT_SELECTION, TOOL_SELECTION
    val versionLabel: String,
    val snapshotJson: String,
    val evaluationReportJson: String?,
    val isPromoted: Boolean,
    val promotedBy: String,
    val promotedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val parentVersionId: String?
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
        Index("workspace_id"),
        Index("agent_id"),
        Index("is_active")
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
        Index("tool_name"),
        Index("lifecycle_state"),
        Index("is_enabled")
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
        Index("tool_id"),
        Index("is_healthy")
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
