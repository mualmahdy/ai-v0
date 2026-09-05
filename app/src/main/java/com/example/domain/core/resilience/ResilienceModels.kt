package com.example.domain.core.resilience

/**
 * ============================================================================
 * Resilience Domain Models — Phase 5 Production Resilience (P1)
 * ============================================================================
 *
 * Closes the Production Resilience gap (audit: 35–45% → ~55%) by
 * introducing domain types for circuit breakers, provider failover,
 * and resource quotas — none of which existed before.
 */

enum class CircuitBreakerState(val storageCode: String) {
    CLOSED("CLOSED"),
    OPEN("OPEN"),
    HALF_OPEN("HALF_OPEN")
}

data class CircuitBreakerConfig(
    val minCallsToOpen: Long = 5,
    val failureThreshold: Int = 3,
    val openStateCooldownMs: Long = 30_000L,
    val halfOpenProbeTimeoutMs: Long = 10_000L
)

data class CircuitBreakerSnapshot(
    val resourceId: String,
    val state: CircuitBreakerState,
    val failureCount: Long,
    val successCount: Long,
    val consecutiveFailures: Int,
    val totalCalls: Long,
    val openedAtEpochMs: Long?,
    val lastFailureCode: String?,
    val lastErrorMessage: String?,
    val lastUpdatedEpochMs: Long
) {
    val isOpen: Boolean get() = state == CircuitBreakerState.OPEN
    val isClosed: Boolean get() = state == CircuitBreakerState.CLOSED
    val isHalfOpen: Boolean get() = state == CircuitBreakerState.HALF_OPEN
}

/**
 * Failover attempt record. One row per attempt when the system tries
 * the next-best candidate after a primary failure.
 */
data class FailoverAttempt(
    val primaryResourceId: String,
    val primaryFailureCode: String,
    val fallbackResourceId: String,
    val attemptIndex: Int,
    val isSuccessful: Boolean,
    val attemptedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Resource quota per workspace. Prevents runaway agents from
 * exhausting the global token budget.
 */
data class ResourceQuota(
    val workspaceId: String,
    val maxTokensPerHour: Long = 200_000L,
    val maxTokensPerDay: Long = 1_000_000L,
    val maxToolCallsPerHour: Long = 500L,
    val maxSearchCallsPerHour: Long = 100L,
    val maxCostUsdPerDay: Double = 5.0
)

/**
 * Current quota usage snapshot.
 */
data class QuotaUsage(
    val workspaceId: String,
    val tokensUsedThisHour: Long,
    val tokensUsedToday: Long,
    val toolCallsThisHour: Long,
    val searchCallsThisHour: Long,
    val costUsdToday: Double,
    val isOverBudget: Boolean,
    val recommendedAction: QuotaAction
)

enum class QuotaAction { PROCEED, THROTTLE, BLOCK }
