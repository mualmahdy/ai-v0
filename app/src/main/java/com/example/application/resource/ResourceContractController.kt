package com.example.application.resource

import com.example.domain.core.resource.ResourceCapabilityGraph
import com.example.domain.core.resource.ResourceHealth
import com.example.domain.core.resource.ResourceId
import com.example.infrastructure.persistence.dao.ResourceHealthSnapshotDao
import com.example.infrastructure.persistence.entities.ResourceHealthSnapshotEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * P0 — Resource Contract startup controller.
 *
 * Composes the P0 startup sequence (idempotent, best-effort):
 * 1. Restores health snapshots (Section G: "Periodic Room snapshot for restart survival").
 * 2. Runs the RULE REG-3 first-start migration (legacy providers -> ResourceRecords,
 *    CONFIGURED + runtimeSupported=false) and the RULE AD-4 local-embedding registration.
 * 3. Rebuilds the ResourceCapabilityGraph from the registry (P0.7: "Graph does not own
 *    persistence; rebuilt from registry on startup").
 * 4. Subscribes the graph to registry change events (P0.7: populated ONLY from
 *    ResourceRegistryService events).
 * 5. Persists periodic health snapshots to Room (best-effort).
 */
class ResourceContractController(
    private val scope: CoroutineScope,
    private val migration: ResourceContractMigration,
    private val graph: ResourceCapabilityGraph,
    private val healthService: com.example.domain.ports.resource.ResourceHealthService,
    private val healthSnapshotDao: ResourceHealthSnapshotDao,
    private val snapshotIntervalMs: Long = 60_000L
) {

    @Volatile
    private var initialized = false

    /** Starts the P0 startup sequence exactly once (subsequent calls are no-ops). */
    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        scope.launch {
            // 1. Best-effort health snapshot restore (restart survival).
            runCatching {
                val snapshots = healthSnapshotDao.getAll().map { it.toDomainHealth() }
                if (snapshots.isNotEmpty()) {
                    healthService.restoreSnapshots(snapshots)
                }
            }

            // 2. RULE REG-3 / AD-4 idempotent migration.
            runCatching { migration.migrateIfNeeded() }

            // 3. Graph rebuild from the authoritative registry.
            runCatching { graph.rebuildFromRegistry() }

            // 4. Graph event subscription (registry events are its only input).
            graph.start(scope)

            // 5. Periodic best-effort health snapshots.
            while (isActive) {
                delay(snapshotIntervalMs)
                runCatching {
                    healthSnapshotDao.upsertAll(
                        healthService.exportSnapshots().map { it.toEntitySnapshot() }
                    )
                }
            }
        }
    }
}

internal fun ResourceHealthSnapshotEntity.toDomainHealth(): ResourceHealth = ResourceHealth(
    resourceId = ResourceId(resourceId),
    successRate = successRate,
    averageLatencyMs = averageLatencyMs,
    p95LatencyMs = p95LatencyMs,
    timeoutRate = timeoutRate,
    lastSuccessAt = lastSuccessAt,
    lastFailureAt = lastFailureAt,
    lastFailureReason = lastFailureReason,
    inCooldownUntil = inCooldownUntil,
    sampleSize = sampleSize,
    healthScore = healthScore
)

internal fun ResourceHealth.toEntitySnapshot(): ResourceHealthSnapshotEntity =
    ResourceHealthSnapshotEntity(
        resourceId = resourceId.value,
        successRate = successRate,
        averageLatencyMs = averageLatencyMs,
        p95LatencyMs = p95LatencyMs,
        timeoutRate = timeoutRate,
        lastSuccessAt = lastSuccessAt,
        lastFailureAt = lastFailureAt,
        lastFailureReason = lastFailureReason,
        inCooldownUntil = inCooldownUntil,
        sampleSize = sampleSize,
        healthScore = healthScore,
        consecutiveFailures = 0, // consecutive counter resets across restarts (best-effort snapshot)
        windowJson = "[]",       // window contents are not snapshotted; aggregates are
        updatedAt = System.currentTimeMillis()
    )
