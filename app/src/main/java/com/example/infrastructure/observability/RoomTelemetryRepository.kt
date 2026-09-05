package com.example.infrastructure.observability

import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.DimensionSummary
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.observability.HealthProbe
import com.example.domain.core.observability.MetricDimensions
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricSnapshot
import com.example.domain.core.observability.MetricType
import com.example.domain.ports.observability.TelemetryPort
import com.example.infrastructure.persistence.dao.AuditTrailDao
import com.example.infrastructure.persistence.dao.ExecutionLogDao
import com.example.infrastructure.persistence.dao.ExecutionTraceDao
import com.example.infrastructure.persistence.dao.HealthProbeDao
import com.example.infrastructure.persistence.dao.MetricEventDao
import com.example.infrastructure.persistence.dao.MetricBucketRow
import com.example.infrastructure.persistence.entities.AuditTrailEntity
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.ExecutionTraceNodeEntity
import com.example.infrastructure.persistence.entities.HealthProbeEntity
import com.example.infrastructure.persistence.entities.MetricEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * RoomTelemetryRepository — Phase 5 Observability
 * ============================================================================
 *
 * Implements `TelemetryPort` against the Room `metric_events`, `audit_trail`,
 * `health_probes`, and `execution_trace_nodes` tables.
 *
 * Also maintains an in-process aggregate cache (`snapshotsFlow`) so that
 * dashboard reads do not require a SQL GROUP BY on every emission — the
 * cache is updated atomically when a sample is recorded.
 *
 * Wiring (see `AppContainer`):
 *   - Constructed once with the four DAOs.
 *   - `TelemetryService` wraps this repository and exposes domain-friendly
 *     APIs (`recordToolCall`, `recordProviderCall`, `recordDecision`).
 *
 * Threading:
 *   - All writes are dispatched to `Dispatchers.IO`.
 *   - The aggregate cache is guarded by a `Mutex` so concurrent writers do
 *     not lose updates (the cache is small — at most a few hundred buckets).
 */
class RoomTelemetryRepository(
    private val metricEventDao: MetricEventDao,
    private val auditTrailDao: AuditTrailDao,
    private val healthProbeDao: HealthProbeDao,
    private val executionTraceDao: ExecutionTraceDao,
    private val executionLogDao: ExecutionLogDao,
    private val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TelemetryPort {

    /**
     * In-process aggregate cache. Keyed by `(type, dimensions.toKey())`.
     * Updated atomically on every `record()` so the dashboard flow emits
     * a fresh snapshot without re-running SQL.
     */
    private val _snapshotsFlow = MutableStateFlow<Map<String, MetricSnapshot>>(emptyMap())
    private val snapshotsFlow: StateFlow<Map<String, MetricSnapshot>> = _snapshotsFlow.asStateFlow()
    private val cacheLock = Mutex()

    /**
     * Recent latency samples per dimension key, kept bounded for percentile
     * computation. We keep at most 256 samples per bucket so the p95/p99
     * computation is O(1) sort on a small array.
     */
    private val latencySamples = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val latencySamplesLock = Mutex()
    private val maxSamplesPerBucket = 256

    init {
        // Seed the cache from the durable store on first construction so
        // metrics survive process death. We do NOT block init — the seed
        // runs on the write scope and the cache will be empty only for the
        // brief startup window.
        writeScope.launch { seedCacheFromDisk() }
    }

    private suspend fun seedCacheFromDisk() {
        try {
            val buckets = metricEventDao.aggregateBuckets()
            if (buckets.isEmpty()) return
            val newCache = mutableMapOf<String, MetricSnapshot>()
            for (row in buckets) {
                val type = MetricType.fromCode(row.metric_type) ?: continue
                val key = "${type.code}|${row.dimensions_key}"
                newCache[key] = MetricSnapshot(
                    type = type,
                    dimensions = MetricDimensions(extras = mapOf("dimensions_key" to row.dimensions_key)),
                    count = row.cnt,
                    sum = row.sum,
                    min = row.mn,
                    max = row.mx,
                    lastValue = row.mx,
                    lastUpdatedEpochMs = System.currentTimeMillis()
                )
            }
            cacheLock.withLock { _snapshotsFlow.value = newCache }
        } catch (_: Throwable) {
            // Best-effort seed; the cache will rebuild as new samples arrive.
        }
    }

    override suspend fun record(sample: MetricSample) {
        // Update durable store.
        val entity = sample.toEntity()
        writeScope.launch {
            try {
                metricEventDao.insertAll(listOf(entity))
                // Prune anything older than 7 days to keep the table bounded.
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                metricEventDao.pruneOlderThan(cutoff)
            } catch (_: Throwable) {
                // Persistence failures must never break the runtime path.
            }
        }

        // Update aggregate cache.
        val key = "${sample.type.code}|${sample.dimensions.toKey()}"
        cacheLock.withLock {
            val current = _snapshotsFlow.value.toMutableMap()
            val prev = current[key]
            val merged = if (prev == null) {
                MetricSnapshot(
                    type = sample.type,
                    dimensions = sample.dimensions,
                    count = 1,
                    sum = sample.value,
                    min = sample.value,
                    max = sample.value,
                    lastValue = sample.value,
                    lastUpdatedEpochMs = sample.recordedAtEpochMs
                )
            } else {
                prev.copy(
                    count = prev.count + 1,
                    sum = prev.sum + sample.value,
                    min = minOf(prev.min, sample.value),
                    max = maxOf(prev.max, sample.value),
                    lastValue = sample.value,
                    lastUpdatedEpochMs = sample.recordedAtEpochMs
                )
            }
            current[key] = merged
            _snapshotsFlow.value = current
        }

        // Track latency samples for percentile computation.
        if (sample.type == MetricType.LATENCY_HISTOGRAM) {
            latencySamplesLock.withLock {
                val deque = latencySamples.getOrPut(key) { ArrayDeque() }
                deque.addLast(sample.value)
                while (deque.size > maxSamplesPerBucket) deque.removeFirst()
            }
        }
    }

    override suspend fun recordBatch(samples: List<MetricSample>) {
        // Update durable store in one transaction.
        val entities = samples.map { it.toEntity() }
        writeScope.launch {
            try {
                metricEventDao.insertAll(entities)
            } catch (_: Throwable) {
                // Persistence failures must never break the runtime path.
            }
        }
        // Update cache sequentially to preserve monotonicity.
        for (s in samples) record(s)
    }

    override suspend fun recordAudit(event: AuditEvent): Long = withContext(Dispatchers.IO) {
        val entity = AuditTrailEntity(
            id = event.id,
            severity = event.severity.name,
            actor = event.actor,
            action = event.action,
            resourceType = event.resourceType,
            resourceId = event.resourceId,
            decision = event.decision,
            reason = event.reason,
            workspaceId = event.workspaceId,
            attributesJson = encodeAttributes(event.attributes),
            occurredAtEpochMs = event.occurredAtEpochMs
        )
        try {
            auditTrailDao.insert(entity)
        } catch (_: Throwable) {
            -1L
        }
    }

    override suspend fun recordHealthProbe(probe: HealthProbe) = withContext(Dispatchers.IO) {
        val entity = HealthProbeEntity(
            id = 0L,
            resourceId = probe.resourceId,
            resourceType = probe.resourceType,
            isHealthy = probe.isHealthy,
            latencyMs = probe.latencyMs,
            errorMessage = probe.errorMessage,
            probedAtEpochMs = probe.probedAtEpochMs
        )
        try {
            healthProbeDao.insert(entity)
        } catch (_: Throwable) {
            // best-effort
        }
        Unit
    }

    override suspend fun recordTraceNode(node: ExecutionTraceNode) = withContext(Dispatchers.IO) {
        // ALSO write a row into the legacy execution_logs table so existing
        // UI components that read from there (AgentStudioScreen execution log
        // panel) keep working without a rebuild of the screen. The new
        // execution_trace_nodes table is the source of truth going forward.
        val payloadJson = JSONObject().apply {
            put("stepIndex", node.stepIndex)
            put("actionType", node.actionType)
            put("targetResourceId", node.targetResourceId)
            put("agentId", node.agentId)
            put("outcome", node.outcome)
            put("summary", node.summary)
            put("observationSummary", node.observationSummary)
            put("durationMs", node.durationMs ?: 0L)
        }.toString()

        try {
            executionLogDao.insertLog(
                ExecutionLogEntity(
                    executionId = node.executionId,
                    sessionId = node.agentId ?: "agent",
                    eventType = "TRACE_NODE",
                    payloadJson = payloadJson,
                    timestampEpochMs = node.startedAtEpochMs
                )
            )
        } catch (_: Throwable) { /* best-effort */ }

        try {
            executionTraceDao.insert(
                ExecutionTraceNodeEntity(
                    id = 0L,
                    executionId = node.executionId,
                    stepIndex = node.stepIndex,
                    actionType = node.actionType,
                    targetResourceId = node.targetResourceId,
                    agentId = node.agentId,
                    startedAtEpochMs = node.startedAtEpochMs,
                    completedAtEpochMs = node.completedAtEpochMs,
                    durationMs = node.durationMs,
                    outcome = node.outcome,
                    summary = node.summary,
                    observationSummary = node.observationSummary
                )
            )
        } catch (_: Throwable) { /* best-effort */ }
        Unit
    }

    override fun snapshots(): Flow<List<MetricSnapshot>> =
        snapshotsFlow.map { it.values.toList() }

    override fun dimensionSummaries(): Flow<List<DimensionSummary>> =
        snapshotsFlow.map { snapshotMap ->
            snapshotMap.values
                .filter { it.type == MetricType.LATENCY_HISTOGRAM || it.type == MetricType.COUNTER }
                .groupBy { it.dimensions.toKey() }
                .map { (_, snapshots) ->
                    val first = snapshots.first()
                    val dimensions = first.dimensions
                    val latencies = latencySamples["${MetricType.LATENCY_HISTOGRAM.code}|${first.dimensions.toKey()}"]
                        ?.toList()
                        ?.sorted()
                        ?: emptyList()
                    val count = snapshots.sumOf { it.count }
                    val sum = snapshots.sumOf { it.sum }
                    val p50 = latencies.elementAtOrNull((latencies.size * 0.5).toInt().coerceAtMost(latencies.size - 1)) ?: 0L
                    val p95 = latencies.elementAtOrNull((latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)) ?: 0L
                    val p99 = latencies.elementAtOrNull((latencies.size * 0.99).toInt().coerceAtMost(latencies.size - 1)) ?: 0L
                    DimensionSummary(
                        dimensionKey = first.dimensions.toKey(),
                        providerId = dimensions.providerId,
                        toolName = dimensions.toolName,
                        agentId = dimensions.agentId,
                        callCount = count,
                        successCount = snapshots.firstOrNull { it.type == MetricType.COUNTER && it.dimensions.actionType == "SUCCESS" }?.count ?: 0L,
                        failureCount = snapshots.firstOrNull { it.type == MetricType.FAILURE_RATE }?.count ?: 0L,
                        degradedCount = snapshots.firstOrNull { it.type == MetricType.COUNTER && it.dimensions.actionType == "DEGRADED" }?.count ?: 0L,
                        averageLatencyMs = if (count > 0) sum.toDouble() / count else 0.0,
                        p50LatencyMs = p50,
                        p95LatencyMs = p95,
                        p99LatencyMs = p99,
                        totalPromptTokens = snapshots.firstOrNull { it.type == MetricType.TOKEN_USAGE && it.dimensions.actionType == "PROMPT" }?.sum ?: 0L,
                        totalCompletionTokens = snapshots.firstOrNull { it.type == MetricType.TOKEN_USAGE && it.dimensions.actionType == "COMPLETION" }?.sum ?: 0L,
                        totalCostUsd = (snapshots.firstOrNull { it.type == MetricType.COST_USD }?.sum ?: 0L) / 10000.0,
                        lastUpdatedEpochMs = snapshots.maxOf { it.lastUpdatedEpochMs }
                    )
                }
        }

    override fun auditEvents(limit: Int): Flow<List<AuditEvent>> =
        auditTrailDao.recent(limit).map { rows ->
            rows.map { it.toDomain() }
        }

    override fun traceForExecution(executionId: String): Flow<List<ExecutionTraceNode>> =
        executionTraceDao.forExecution(executionId).map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun snapshotByType(type: MetricType): List<MetricSnapshot> =
        snapshotsFlow.value.values.filter { it.type == type }

    // --- Helpers ---

    private fun MetricSample.toEntity(): MetricEventEntity = MetricEventEntity(
        id = 0L,
        metricType = type.code,
        dimensionsKey = dimensions.toKey(),
        executionId = dimensions.executionId,
        sessionId = dimensions.sessionId,
        workspaceId = dimensions.workspaceId,
        providerId = dimensions.providerId,
        toolName = dimensions.toolName,
        agentId = dimensions.agentId,
        resourceType = dimensions.resourceType,
        actionType = dimensions.actionType,
        value = value,
        attributesJson = encodeAttributes(attributes),
        recordedAtEpochMs = recordedAtEpochMs
    )

    private fun encodeAttributes(attrs: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in attrs) obj.put(k, v)
        return obj.toString()
    }

    private fun AuditTrailEntity.toDomain(): AuditEvent = AuditEvent(
        id = id,
        severity = runCatching { AuditSeverity.valueOf(severity) }.getOrDefault(AuditSeverity.INFO),
        actor = actor,
        action = action,
        resourceType = resourceType,
        resourceId = resourceId,
        decision = decision,
        reason = reason,
        workspaceId = workspaceId,
        attributes = runCatching {
            val obj = JSONObject(attributesJson)
            val map = mutableMapOf<String, String>()
            for (k in obj.keys()) map[k] = obj.getString(k)
            map
        }.getOrDefault(emptyMap()),
        occurredAtEpochMs = occurredAtEpochMs
    )

    private fun ExecutionTraceNodeEntity.toDomain(): ExecutionTraceNode = ExecutionTraceNode(
        executionId = executionId,
        stepIndex = stepIndex,
        actionType = actionType,
        targetResourceId = targetResourceId,
        agentId = agentId,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        durationMs = durationMs,
        outcome = outcome,
        summary = summary,
        observationSummary = observationSummary
    )
}
