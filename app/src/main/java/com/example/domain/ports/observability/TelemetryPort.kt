package com.example.domain.ports.observability

import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.DimensionSummary
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricSnapshot
import com.example.domain.core.observability.MetricType
import kotlinx.coroutines.flow.Flow

/**
 * Telemetry Port — write path for metrics, audit events, health probes,
 * and execution-trace nodes.
 *
 * The port is intentionally write-only from the application's point of view:
 * callers record telemetry but never read it back synchronously (reads are
 * for the dashboard, which uses the snapshot flow).
 */
interface TelemetryPort {

    /** Atomic increment of a counter (or any sample with type=COUNTER). */
    suspend fun record(sample: MetricSample)

    /** Batched record — used by the orchestrator when flushing an execution. */
    suspend fun recordBatch(samples: List<MetricSample>)

    /** Persist a security audit event. Returns the assigned row id. */
    suspend fun recordAudit(event: AuditEvent): Long

    /** Persist a single execution trace node (one per action). */
    suspend fun recordTraceNode(node: ExecutionTraceNode)

    /** Snapshot of all aggregate counters/histograms — hot in-memory only. */
    fun snapshots(): Flow<List<MetricSnapshot>>

    /** Pre-aggregated per-dimension summary (provider/tool/agent) for dashboard. */
    fun dimensionSummaries(): Flow<List<DimensionSummary>>

    /** Live stream of audit events for the activity feed. */
    fun auditEvents(limit: Int = 100): Flow<List<AuditEvent>>

    /**
     * GAP-04 (Design Closure 2026): workspace-scoped live audit stream.
     * The Unified Activity Feed must reflect ONLY the active workspace —
     * the previous global read leaked other workspaces' audit rows.
     *
     * Default implementation falls back to the unscoped stream so that
     * test fakes keep their behavior; the production repository overrides
     * this with a SQL-level `WHERE workspaceId = :id` filter.
     *
     * `workspaceId == null` means "no active workspace" — the honest feed
     * is EMPTY (nothing is attributable), never a cross-workspace leak.
     */
    fun auditEvents(workspaceId: String?, limit: Int = 100): Flow<List<AuditEvent>> =
        auditEvents(limit)

    /** Live stream of execution-trace nodes for an execution. */
    fun traceForExecution(executionId: String): Flow<List<ExecutionTraceNode>>

    /**
     * Live stream of the MOST RECENT execution-trace nodes across executions
     * (report fix: Unified Activity wiring). The activity feed previously
     * subscribed to `traceForExecution("")` — an empty id that can never
     * match a real execution, so the feed showed nothing. When no execution
     * is active the feed now falls back to the recent-trace window.
     */
    fun recentTraceNodes(limit: Int = 50): Flow<List<ExecutionTraceNode>>

    /**
     * GAP-04 (Design Closure 2026): workspace-scoped recent-trace window.
     * Same policy as [auditEvents] above: default = unscoped fallback for
     * test fakes; production overrides with a SQL `WHERE workspaceId = :id`
     * filter; `workspaceId == null` = honest empty (no active workspace).
     */
    fun recentTraceNodes(workspaceId: String?, limit: Int = 50): Flow<List<ExecutionTraceNode>> =
        recentTraceNodes(limit)

    /** Aggregate snapshot filtered by type. */
    suspend fun snapshotByType(type: MetricType): List<MetricSnapshot>
}
