package com.example.application.resource

import com.example.domain.core.resource.ResourceHealth
import com.example.domain.core.resource.ResourceId
import com.example.domain.ports.resource.ResourceHealthService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P0.3 — In-memory ResourceHealthService implementation
 * (APPROVED-BASELINE v2.1, Section G — LOCKED).
 *
 * ## Locked semantics implemented here
 * - Consumes ExecutionResult TRANSPORT outcomes only. Semantic correctness,
 *   evidence quality, and task success belong to Verification — never here
 *   (RULE RH-1: HTTP 200 + wrong answer = healthy resource + failed verification).
 * - Sliding window: last [WINDOW_SIZE] = 20 transport outcomes per resource.
 * - No external health-check pinger in P0 — passive, execution-driven only.
 * - Cooldown trigger: [COOLDOWN_FAILURE_THRESHOLD] = 3 consecutive transport
 *   failures -> cooldown of [COOLDOWN_DURATION_MS] = 5 minutes.
 * - Cooldown recovery: the first successful execution after cooldown clears the
 *   cooldown and resets the consecutive-failure counter.
 * - NEVER touches lifecycle state (RULE LC-2): lifecycle corrections are requested
 *   by the observation/control-plane layer, not by this service.
 *
 * ## Deterministic healthScore formula (contract P0.3: deterministic + documented)
 *
 *   latencyFactor  = 1.0                                     if avgLatency <= 1000ms
 *                  = 1.0 - 0.5 * (avgLatency - 1000) / 9000  if 1000 < avg < 10000ms
 *                  = 0.5                                     if avgLatency >= 10000ms
 *   timeoutPenalty = 1.0 - 0.5 * timeoutRate
 *   healthScore    = successRate * latencyFactor * timeoutPenalty   (clamped 0.0..1.0)
 *
 * The score is a pure function of the sliding-window aggregates, so identical
 * windows always produce identical scores.
 *
 * Restart survival: [exportSnapshots] / [restoreSnapshots] integrate with a
 * periodic best-effort Room snapshot store (Section G / P0.3 rules).
 */
class InMemoryResourceHealthService(
    private val now: () -> Long = { System.currentTimeMillis() }
) : ResourceHealthService {

    private data class WindowEntry(val isSuccess: Boolean, val latencyMs: Long, val isTimeout: Boolean)

    private class HealthState {
        val window = ArrayDeque<WindowEntry>()
        var consecutiveFailures: Int = 0
        var inCooldownUntil: Long? = null
        var lastSuccessAt: Long? = null
        var lastFailureAt: Long? = null
        var lastFailureReason: String? = null
    }

    private val mutex = Mutex()
    private val states = HashMap<String, HealthState>()

    private val _healthFlow = MutableStateFlow<Map<String, ResourceHealth>>(emptyMap())
    val healthSnapshotFlow: StateFlow<Map<String, ResourceHealth>> = _healthFlow.asStateFlow()

    private val _changes = MutableSharedFlow<ResourceHealth>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override fun observeHealthChanges(): Flow<ResourceHealth> = _changes.asSharedFlow()

    override suspend fun recordTransportSuccess(resourceId: ResourceId, latencyMs: Long) = mutex.withLock {
        val state = states.getOrPut(resourceId.value) { HealthState() }
        state.window.addLast(WindowEntry(isSuccess = true, latencyMs = latencyMs, isTimeout = false))
        trimWindow(state)
        state.lastSuccessAt = now()
        // Locked recovery rule: first success after cooldown clears cooldown + counter.
        state.inCooldownUntil = null
        state.consecutiveFailures = 0
        publish(resourceId, state)
        Unit
    }

    override suspend fun recordTransportFailure(
        resourceId: ResourceId,
        reason: String,
        isTimeout: Boolean
    ) = mutex.withLock {
        val state = states.getOrPut(resourceId.value) { HealthState() }
        state.window.addLast(WindowEntry(isSuccess = false, latencyMs = 0L, isTimeout = isTimeout))
        trimWindow(state)
        state.lastFailureAt = now()
        state.lastFailureReason = reason
        state.consecutiveFailures += 1
        // Locked cooldown trigger: 3 consecutive transport failures -> 5 minutes.
        if (state.consecutiveFailures >= COOLDOWN_FAILURE_THRESHOLD) {
            state.inCooldownUntil = now() + COOLDOWN_DURATION_MS
        }
        publish(resourceId, state)
        Unit
    }

    override suspend fun getHealth(resourceId: ResourceId): ResourceHealth = mutex.withLock {
        computeHealth(resourceId, states.getOrPut(resourceId.value) { HealthState() })
    }

    override suspend fun isInCooldown(resourceId: ResourceId): Boolean = mutex.withLock {
        val state = states[resourceId.value] ?: return@withLock false
        val until = state.inCooldownUntil ?: return@withLock false
        until > now()
    }

    override suspend fun consecutiveFailures(resourceId: ResourceId): Int = mutex.withLock {
        states[resourceId.value]?.consecutiveFailures ?: 0
    }

    override suspend fun restoreSnapshots(snapshots: List<ResourceHealth>) = mutex.withLock {
        for (snapshot in snapshots) {
            val state = states.getOrPut(snapshot.resourceId.value) { HealthState() }
            state.inCooldownUntil = snapshot.inCooldownUntil
            state.lastSuccessAt = snapshot.lastSuccessAt
            state.lastFailureAt = snapshot.lastFailureAt
            state.lastFailureReason = snapshot.lastFailureReason
            // The consecutive-failure counter is NOT part of the locked ResourceHealth
            // (Section G); the persisted cooldown timestamp is what matters for the
            // usable conjunction. Counter restarts at 0 (best-effort restart semantics).
        }
        Unit
    }

    override suspend fun exportSnapshots(): List<ResourceHealth> = mutex.withLock {
        states.entries.map { (id, state) -> computeHealth(ResourceId(id), state) }
    }

    // --- Deterministic aggregation -------------------------------------------

    private fun trimWindow(state: HealthState) {
        while (state.window.size > WINDOW_SIZE) state.window.removeFirst()
    }

    private fun computeHealth(resourceId: ResourceId, state: HealthState): ResourceHealth {
        val sampleSize = state.window.size
        val successCount = state.window.count { it.isSuccess }
        val successRate = if (sampleSize == 0) 0.0 else successCount.toDouble() / sampleSize
        val latencies = state.window.filter { it.isSuccess }.map { it.latencyMs }.sorted()
        val averageLatency = if (latencies.isEmpty()) 0L else latencies.average().toLong()
        val p95 = if (latencies.isEmpty()) 0L else latencies[minOf(latencies.size - 1, ((latencies.size - 1) * 95) / 100)]
        val timeoutRate = if (sampleSize == 0) 0.0 else state.window.count { it.isTimeout }.toDouble() / sampleSize

        val latencyFactor = when {
            averageLatency <= 1000L -> 1.0
            averageLatency >= 10000L -> 0.5
            else -> 1.0 - 0.5 * (averageLatency - 1000.0) / 9000.0
        }
        val timeoutPenalty = 1.0 - 0.5 * timeoutRate
        val healthScore = (successRate * latencyFactor * timeoutPenalty).coerceIn(0.0, 1.0)

        return ResourceHealth(
            resourceId = resourceId,
            successRate = successRate,
            averageLatencyMs = averageLatency,
            p95LatencyMs = p95,
            timeoutRate = timeoutRate,
            lastSuccessAt = state.lastSuccessAt,
            lastFailureAt = state.lastFailureAt,
            lastFailureReason = state.lastFailureReason,
            inCooldownUntil = state.inCooldownUntil?.takeIf { it > now() },
            sampleSize = sampleSize,
            healthScore = healthScore
        )
    }

    private fun publish(resourceId: ResourceId, state: HealthState) {
        val health = computeHealth(resourceId, state)
        _healthFlow.value = _healthFlow.value + (resourceId.value to health)
        _changes.tryEmit(health)
    }

    companion object {
        /** Section G (Locked): sliding window = last 20 transport outcomes. */
        const val WINDOW_SIZE = 20

        /** Section G (Locked): 3 consecutive transport failures trigger cooldown. */
        const val COOLDOWN_FAILURE_THRESHOLD = 3

        /** Section G (Locked): cooldown duration = 5 minutes. */
        const val COOLDOWN_DURATION_MS = 5L * 60L * 1000L
    }
}
