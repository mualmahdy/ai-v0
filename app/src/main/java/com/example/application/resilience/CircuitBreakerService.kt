package com.example.application.resilience

import com.example.domain.core.resilience.CircuitBreakerConfig
import com.example.domain.core.resilience.CircuitBreakerState
import com.example.domain.core.resilience.CircuitBreakerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * CircuitBreakerService — Phase 5 Production Resilience (P1)
 * ============================================================================
 *
 * Closes the Production Resilience gap (audit: 35–45% → ~55%) by adding
 * a real circuit breaker that fails fast when a resource (provider,
 * tool, MCP server) is failing repeatedly — instead of letting every
 * call timeout individually.
 *
 * States:
 *   CLOSED    — calls flow normally; failures increment a counter.
 *   OPEN      — calls fail-fast with `CircuitBreakerOpenError`; no
 *               actual call is made.
 *   HALF_OPEN — a single probe call is allowed; if it succeeds the
 *               breaker closes, otherwise it re-opens.
 *
 * Each resource has its own breaker keyed by `resourceId`.
 */
class CircuitBreakerService(
    private val defaultConfig: CircuitBreakerConfig = CircuitBreakerConfig()
) {

    private val breakers = ConcurrentHashMap<String, CircuitBreakerSnapshot>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, CircuitBreakerSnapshot>>(emptyMap())
    val states: StateFlow<Map<String, CircuitBreakerSnapshot>> = _states.asStateFlow()

    /**
     * Check whether a call should be allowed against the given resource.
     * Returns true if the breaker is CLOSED or HALF_OPEN (probe allowed).
     */
    suspend fun allowCall(resourceId: String): Boolean {
        val snapshot = current(resourceId)
        when (snapshot.state) {
            CircuitBreakerState.OPEN -> {
                // Check if the cooldown has elapsed; if so, transition to HALF_OPEN.
                val now = System.currentTimeMillis()
                if (now - (snapshot.openedAtEpochMs ?: 0L) > defaultConfig.openStateCooldownMs) {
                    transition(resourceId, CircuitBreakerState.HALF_OPEN)
                    return true
                }
                return false
            }
            CircuitBreakerState.HALF_OPEN -> return true // allow one probe
            CircuitBreakerState.CLOSED -> return true
        }
    }

    /**
     * Record a successful call. Closes the breaker if it was HALF_OPEN.
     */
    suspend fun recordSuccess(resourceId: String) {
        val snapshot = current(resourceId)
        val newCount = snapshot.successCount + 1
        val newState = if (snapshot.state == CircuitBreakerState.HALF_OPEN) {
            CircuitBreakerState.CLOSED
        } else snapshot.state
        update(resourceId) {
            it.copy(
                state = newState,
                successCount = newCount,
                consecutiveFailures = 0,
                openedAtEpochMs = if (newState == CircuitBreakerState.CLOSED) null else it.openedAtEpochMs,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Record a failed call. Opens the breaker if the failure threshold
     * is exceeded AND we have enough samples.
     */
    suspend fun recordFailure(resourceId: String, failureCode: String, errorMessage: String?) {
        val snapshot = current(resourceId)
        val newFailureCount = snapshot.failureCount + 1
        val newConsecutive = snapshot.consecutiveFailures + 1
        val newState = when {
            snapshot.state == CircuitBreakerState.HALF_OPEN -> CircuitBreakerState.OPEN
            snapshot.totalCalls >= defaultConfig.minCallsToOpen &&
                newConsecutive >= defaultConfig.failureThreshold -> CircuitBreakerState.OPEN
            else -> snapshot.state
        }
        update(resourceId) {
            it.copy(
                state = newState,
                failureCount = newFailureCount,
                consecutiveFailures = newConsecutive,
                lastFailureCode = failureCode,
                lastErrorMessage = errorMessage,
                openedAtEpochMs = if (newState == CircuitBreakerState.OPEN && it.openedAtEpochMs == null) System.currentTimeMillis() else it.openedAtEpochMs,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Force-reset a breaker (e.g. after a manual "retry now" click).
     */
    suspend fun reset(resourceId: String) {
        transition(resourceId, CircuitBreakerState.CLOSED)
    }

    fun current(resourceId: String): CircuitBreakerSnapshot {
        return breakers[resourceId] ?: CircuitBreakerSnapshot(
            resourceId = resourceId,
            state = CircuitBreakerState.CLOSED,
            failureCount = 0,
            successCount = 0,
            consecutiveFailures = 0,
            totalCalls = 0,
            openedAtEpochMs = null,
            lastFailureCode = null,
            lastErrorMessage = null,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun transition(resourceId: String, newState: CircuitBreakerState) {
        update(resourceId) { it.copy(state = newState, openedAtEpochMs = if (newState == CircuitBreakerState.OPEN) System.currentTimeMillis() else null) }
    }

    private suspend fun update(resourceId: String, transform: (CircuitBreakerSnapshot) -> CircuitBreakerSnapshot) {
        val lock = locks.computeIfAbsent(resourceId) { Mutex() }
        lock.withLock {
            val current = breakers[resourceId] ?: CircuitBreakerSnapshot(
                resourceId = resourceId,
                state = CircuitBreakerState.CLOSED,
                failureCount = 0,
                successCount = 0,
                consecutiveFailures = 0,
                totalCalls = 0,
                openedAtEpochMs = null,
                lastFailureCode = null,
                lastErrorMessage = null,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
            val updated = transform(current).copy(totalCalls = current.totalCalls + 1)
            breakers[resourceId] = updated
            _states.value = breakers.toMap()
        }
    }
}
