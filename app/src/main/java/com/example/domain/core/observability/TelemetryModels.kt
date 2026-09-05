package com.example.domain.core.observability

/**
 * ============================================================================
 * Telemetry & Observability Domain Models — Phase 5 (P1 Remediation)
 * ============================================================================
 *
 * Closes the audit gap "Observability (25–35%)" by introducing a real
 * telemetry pipeline. `ExecutionLogEntity` already existed in Room but was
 * never called by production code; this module turns that table into a
 * first-class observability surface plus adds aggregate metrics
 * (counters / histograms / cost tracking) on top.
 *
 * Design rules (matching the rest of the codebase):
 *  - All public types are immutable data classes or sealed interfaces.
 *  - No emoji, no special unicode escapes — Arabic labels are stored as
 *    literal UTF-8 in the .kt source.
 *  - Honest defaults: zero/UNKNOWN, never optimistic.
 */

/**
 * Kind of telemetry sample emitted by the runtime.
 *
 * KEEP IN SYNC with the `metric_type` column in `metric_events` (see
 * `MIGRATION_7_TO_8` in `AppDatabase.kt`).
 */
enum class MetricType(val code: String, val displayLabelAr: String) {
    COUNTER("counter", "عدّاد"),
    LATENCY_HISTOGRAM("latency_histogram", "رسم زمن الاستجابة"),
    TOKEN_USAGE("token_usage", "استهلاك التوكنز"),
    COST_USD("cost_usd", "التكلفة بالدولار"),
    FAILURE_RATE("failure_rate", "معدل الفشل"),
    HEALTH_PROBE("health_probe", "فحص الصحة");

    companion object {
        fun fromCode(code: String): MetricType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Dimension bucketing for a metric sample. Allows slicing by
 * executionId / providerId / toolName / agentId / workspaceId.
 */
data class MetricDimensions(
    val executionId: String? = null,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val providerId: String? = null,
    val toolName: String? = null,
    val agentId: String? = null,
    val resourceType: String? = null,
    val actionType: String? = null,
    val extras: Map<String, String> = emptyMap()
) {
    /**
     * Stable dimension key used as a hash map key in the in-process aggregator.
     */
    fun toKey(): String = buildString {
        append("e=").append(executionId ?: "-"); append('|')
        append("s=").append(sessionId ?: "-"); append('|')
        append("w=").append(workspaceId ?: "-"); append('|')
        append("p=").append(providerId ?: "-"); append('|')
        append("t=").append(toolName ?: "-"); append('|')
        append("a=").append(agentId ?: "-"); append('|')
        append("rt=").append(resourceType ?: "-"); append('|')
        append("at=").append(actionType ?: "-")
        // extras intentionally excluded from the key — they are descriptive only
    }
}

/**
 * Single immutable metric sample. Long values are used for both counters
 * and latency so the same persistence path can store both.
 */
data class MetricSample(
    val type: MetricType,
    val dimensions: MetricDimensions,
    val value: Long,
    val recordedAtEpochMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Aggregate snapshot of a single (type, dimensions) bucket.
 * Used by the dashboard / failover / circuit-breaker decisions.
 */
data class MetricSnapshot(
    val type: MetricType,
    val dimensions: MetricDimensions,
    val count: Long,
    val sum: Long,
    val min: Long,
    val max: Long,
    val lastValue: Long,
    val lastUpdatedEpochMs: Long
)

/**
 * Pre-aggregated summary for a single dimension (e.g. per-provider).
 * Returned by `TelemetryService.snapshot()` for dashboard rendering.
 */
data class DimensionSummary(
    val dimensionKey: String,
    val providerId: String?,
    val toolName: String?,
    val agentId: String?,
    val callCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val degradedCount: Long,
    val averageLatencyMs: Double,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalCostUsd: Double,
    val lastUpdatedEpochMs: Long
)

/**
 * Health probe result emitted periodically by background monitoring
 * (e.g. `ProviderHealthMonitor`). Persisted so the dashboard can show
 * a timeline, not just the latest snapshot.
 */
data class HealthProbe(
    val resourceId: String,
    val resourceType: String,
    val isHealthy: Boolean,
    val latencyMs: Long,
    val errorMessage: String? = null,
    val probedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Severity for an audit-log entry.
 */
enum class AuditSeverity { INFO, WARN, ERROR, CRITICAL }

/**
 * Structured audit event persisted to `audit_trail` (new table, see
 * `MIGRATION_7_TO_8`). Closes the Security Governance gap "no AuditLog
 * entity, security decisions are not persisted".
 */
data class AuditEvent(
    val id: String,
    val severity: AuditSeverity,
    val actor: String,
    val action: String,
    val resourceType: String,
    val resourceId: String,
    val decision: String,
    val reason: String,
    val workspaceId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val occurredAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Pre-computed execution trace node — one row per (executionId, stepIndex, action).
 * Used by the Unified Activity Feed UI screen so the user can see the full
 * decision→action→observation chain at a glance.
 */
data class ExecutionTraceNode(
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
