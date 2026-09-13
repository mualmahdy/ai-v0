package com.example.application.resilience

import com.example.domain.core.resilience.CircuitBreakerSnapshot
import com.example.domain.core.resilience.CircuitBreakerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * CircuitBreakerStateSink — GAP-17 closure (Design Closure 2026, Phase 4)
 * ============================================================================
 *
 * Persists circuit-breaker STATE CHANGES to `tool_health_snapshots` via the
 * composition root's writer (identity mapping: breaker resourceId ↔
 * toolId). Only TRANSITIONS are written (state + openedAt pair changes),
 * not every counter bump — the durable value is the circuit state, so a
 * process restart restores OPEN/OPENED-AT and the cooldown keeps running
 * (see [CircuitBreakerService.restorePersisted]); counter rows reflect the
 * last persisted transition, which the KDoc declares honestly.
 *
 * FAIL-OPEN OBSERVABILITY: a persistence failure is counted and reported
 * through [onError] (default: stderr) and NEVER propagates — the breaker's
 * job is fast-failing calls, not durability enforcement.
 */
class CircuitBreakerStateSink(
    private val service: CircuitBreakerService,
    private val writer: suspend (resourceId: String, snapshot: CircuitBreakerSnapshot) -> Unit,
    private val onError: (resourceId: String, error: Throwable) -> Unit = { resourceId, error ->
        System.err.println(
            "CIRCUIT_BREAKER_PERSISTENCE_FAILED[$resourceId]: ${error::class.simpleName}: ${error.message}"
        )
    }
) {

    /** GAP-13 honest-degradation: every swallowed persistence failure is counted. */
    @Volatile
    var persistenceFailureCount: Long = 0L
        private set

    private val lastPersisted = ConcurrentHashMap<String, Pair<CircuitBreakerState, Long?>>()

    /**
     * Writes only the resources whose (state, openedAt) pair changed since
     * the last persisted value. Called for every `states` emission — cheap
     * no-op when nothing transitioned.
     */
    suspend fun persistIfChanged(states: Map<String, CircuitBreakerSnapshot>) {
        for ((resourceId, snapshot) in states) {
            val persistKey = snapshot.state to snapshot.openedAtEpochMs
            if (lastPersisted[resourceId] == persistKey) continue
            try {
                writer(resourceId, snapshot)
                lastPersisted[resourceId] = persistKey
            } catch (e: Exception) {
                persistenceFailureCount += 1
                onError(resourceId, e)
            }
        }
    }

    /** Starts the collector in [scope]; each emission goes through change detection. */
    fun startIn(scope: CoroutineScope) {
        scope.launch {
            service.states.collect { persistIfChanged(it) }
        }
    }
}
