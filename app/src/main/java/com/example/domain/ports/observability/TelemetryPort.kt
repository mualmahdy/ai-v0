package com.example.domain.ports.observability

import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.DimensionSummary
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.observability.HealthProbe
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

    /** Persist a health probe sample. */
    suspend fun recordHealthProbe(probe: HealthProbe)

    /** Persist a single execution trace node (one per action). */
    suspend fun recordTraceNode(node: ExecutionTraceNode)

    /** Snapshot of all aggregate counters/histograms — hot in-memory only. */
    fun snapshots(): Flow<List<MetricSnapshot>>

    /** Pre-aggregated per-dimension summary (provider/tool/agent) for dashboard. */
    fun dimensionSummaries(): Flow<List<DimensionSummary>>

    /** Live stream of audit events for the activity feed. */
    fun auditEvents(limit: Int = 100): Flow<List<AuditEvent>>

    /** Live stream of execution-trace nodes for an execution. */
    fun traceForExecution(executionId: String): Flow<List<ExecutionTraceNode>>

    /** Aggregate snapshot filtered by type. */
    suspend fun snapshotByType(type: MetricType): List<MetricSnapshot>
}
