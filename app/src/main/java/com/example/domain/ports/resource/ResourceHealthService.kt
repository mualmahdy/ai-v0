package com.example.domain.ports.resource

import com.example.domain.core.resource.ResourceHealth
import com.example.domain.core.resource.ResourceId
import kotlinx.coroutines.flow.Flow

/**
 * P0.3 — ResourceHealthService (APPROVED-BASELINE v2.1, Section G — LOCKED).
 *
 * OWNS transport health metrics ONLY (Section D ownership boundary).
 * MUST NOT set lifecycle state and MUST NOT measure task correctness:
 *  - RULE RH-1: HTTP 200 + semantically wrong answer = healthy resource + failed
 *    verification (separate observations feeding separate state).
 *  - RULE RH-2: health data is one INPUT to candidate evaluation; it does not gate
 *    execution by itself.
 *  - RULE LC-2: health events never directly change lifecycle; they inform the
 *    observation layer, which may REQUEST a lifecycle correction through the
 *    control plane / orchestrator.
 *
 * P0 locking (Section G): passive, execution-driven only (no health-check pinger),
 * sliding window = last 20 transport outcomes, cooldown = 3 consecutive transport
 * failures -> 5 minutes, cooldown recovery = first successful execution after
 * cooldown clears the cooldown and resets the consecutive-failure counter.
 */
interface ResourceHealthService {

    /** Records a successful transport outcome (transport layer only). */
    suspend fun recordTransportSuccess(resourceId: ResourceId, latencyMs: Long)

    /**
     * Records a failed transport outcome (transport layer only).
     * [isTimeout] marks connection/read timeouts for the timeoutRate dimension.
     */
    suspend fun recordTransportFailure(resourceId: ResourceId, reason: String, isTimeout: Boolean = false)

    /** Current health snapshot; zero-window defaults when no observation exists yet. */
    suspend fun getHealth(resourceId: ResourceId): ResourceHealth

    /** True while the resource is inside a cooldown window. */
    suspend fun isInCooldown(resourceId: ResourceId): Boolean

    /** Consecutive-failure counter (used by the locked cooldown trigger rule). */
    suspend fun consecutiveFailures(resourceId: ResourceId): Int

    /** Health change stream for candidate scoring / observation layers. */
    fun observeHealthChanges(): Flow<ResourceHealth>

    /**
     * Restores health snapshots (best-effort, restart survival). Implementation detail:
     * in-memory service loads periodic Room snapshots through this call.
     */
    suspend fun restoreSnapshots(snapshots: List<ResourceHealth>)

    /** Exports current snapshots for periodic Room persistence (best-effort). */
    suspend fun exportSnapshots(): List<ResourceHealth>
}
