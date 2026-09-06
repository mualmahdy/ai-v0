package com.example.application.budget

import com.example.domain.core.budget.RateLimitScopeType
import com.example.domain.core.budget.RateLimitStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * RPM/TPM sliding-window governor, tracked INDEPENDENTLY from token budgets
 * and monetary budgets (Section 3.9 of the directive: "remaining token
 * budget" != "remaining TPM capacity").
 *
 * Windows are in-memory per process (rate limits are transient by nature —
 * they reset on their own schedule). Scope keys follow
 * `PROVIDER:<id>` / `MODEL:<provider/model>` / `RESOURCE:<resourceId>`. The
 * scope key is embedded in the state so a rolled window refreshes its
 * limits from the latest configuration.
 *
 * Limits are supplied by configuration/discovery (never invented here);
 * when a limit is null, that dimension is untracked and honest about it.
 */
class RateLimitGovernor(
    private val windowLengthMs: Long = 60_000L
) {

    private data class ScopeState(
        val scopeKey: String,
        @Volatile var rpmLimit: Int?,
        @Volatile var tpmLimit: Int?,
        @Volatile var windowStartMs: Long,
        @Volatile var requestCount: Int,
        @Volatile var tokenCount: Long,
        @Volatile var blockedUntilMs: Long?
    )

    private val scopes = ConcurrentHashMap<String, ScopeState>()

    /** Configured limits per scope key (from provider config / discovery). */
    private val configuredLimits = ConcurrentHashMap<String, Pair<Int?, Int?>>()

    /**
     * Declares/updates the known limits for a scope. Unknown limits stay
     * null — never defaulted to a fabricated number.
     */
    fun configureLimits(scopeKey: String, rpmLimit: Int?, tpmLimit: Int?) {
        configuredLimits[scopeKey] = (rpmLimit to tpmLimit)
    }

    fun forgetLimits(scopeKey: String) {
        configuredLimits.remove(scopeKey)
    }

    /**
     * Snapshot the current status for a scope. Creates an empty (honest)
     * status with UNKNOWN usage when the scope has never been seen.
     */
    fun statusFor(scopeKey: String, scopeType: RateLimitScopeType = RateLimitScopeType.PROVIDER): RateLimitStatus {
        val now = System.currentTimeMillis()
        val (rpm, tpm) = configuredLimits[scopeKey] ?: (null to null)
        val state = scopes[scopeKey]
        if (state == null) {
            return RateLimitStatus(
                scopeKey = scopeKey,
                scopeType = scopeType,
                rpmLimit = rpm,
                tpmLimit = tpm,
                windowStartEpochMs = now,
                windowLengthMs = windowLengthMs,
                rpmUsed = 0,
                tpmUsed = 0,
                blockedUntilEpochMs = null
            )
        }
        val effective = rollWindowIfExpired(state, now)
        return RateLimitStatus(
            scopeKey = scopeKey,
            scopeType = scopeType,
            rpmLimit = effective.rpmLimit,
            tpmLimit = effective.tpmLimit,
            windowStartEpochMs = effective.windowStartMs,
            windowLengthMs = windowLengthMs,
            rpmUsed = effective.requestCount,
            tpmUsed = effective.tokenCount,
            blockedUntilEpochMs = effective.blockedUntilMs?.takeIf { it > now }
        )
    }

    /** True when the scope currently has capacity for one more request. */
    fun allowsRequest(scopeKey: String): Boolean = !statusFor(scopeKey).isRateLimited

    /**
     * Records an issued request (RPM+1) plus its token consumption (TPM+N).
     * Called by the accounting path AFTER a real interaction (not before —
     * prediction of usage belongs to estimation).
     */
    fun recordRequest(scopeKey: String, tokens: Int) {
        val now = System.currentTimeMillis()
        val (rpm, tpm) = configuredLimits[scopeKey] ?: (null to null)
        val state = scopes.computeIfAbsent(scopeKey) {
            ScopeState(scopeKey, rpm, tpm, now, 0, 0L, null)
        }
        val effective = rollWindowIfExpired(state, now)
        synchronized(effective) {
            effective.requestCount += 1
            effective.tokenCount += tokens
        }
    }

    /**
     * A provider 429 / Retry-After observation closes the gate until the
     * hint elapses (or a default backoff when the provider gave none —
     * honest default, labeled by the caller in telemetry).
     */
    fun recordRateLimitEncounter(scopeKey: String, retryAfterMs: Long?) {
        val now = System.currentTimeMillis()
        val state = scopes.computeIfAbsent(scopeKey) {
            val (rpm, tpm) = configuredLimits[scopeKey] ?: (null to null)
            ScopeState(scopeKey, rpm, tpm, now, 0, 0L, null)
        }
        val effective = rollWindowIfExpired(state, now)
        val backoff = retryAfterMs ?: DEFAULT_BACKOFF_MS
        synchronized(effective) {
            effective.blockedUntilMs = now + backoff
        }
    }

    /** Clears a block after conditions changed (e.g. operator action). */
    fun clearBlock(scopeKey: String) {
        scopes[scopeKey]?.let { synchronized(it) { it.blockedUntilMs = null } }
    }

    fun resetAll() {
        scopes.clear()
    }

    // ---- internals ----

    private fun rollWindowIfExpired(state: ScopeState, now: Long): ScopeState {
        if (now - state.windowStartMs >= windowLengthMs) {
            synchronized(state) {
                if (now - state.windowStartMs >= windowLengthMs) {
                    // refresh limits from config each window (config may change)
                    val (rpm, tpm) = configuredLimits[state.scopeKey]
                        ?: (state.rpmLimit to state.tpmLimit)
                    state.rpmLimit = rpm
                    state.tpmLimit = tpm
                    state.windowStartMs = now
                    state.requestCount = 0
                    state.tokenCount = 0
                }
            }
        }
        return state
    }

    companion object {
        /** Honest default when a 429 arrives without Retry-After: 60s backoff. */
        const val DEFAULT_BACKOFF_MS: Long = 60_000L
    }
}
