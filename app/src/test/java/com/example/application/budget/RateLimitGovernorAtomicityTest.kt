package com.example.application.budget

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P1-12 (audit 2026 §25) — RateLimitGovernor atomic check-and-reserve.
 * ============================================================================
 *
 * The audit's TOCTOU finding: `allowsRequest()` (pure read) followed by a
 * separately-invoked `recordRequest()` let N concurrent callers ALL pass a
 * limit meant to admit fewer than N. [RateLimitGovernor.tryAcquire] is the
 * atomic replacement — this test proves it under real concurrency.
 */
class RateLimitGovernorAtomicityTest {

    @Test
    fun `concurrent tryAcquire admits EXACTLY the RPM limit - no TOCTOU overshoot`() = runBlocking {
        val governor = RateLimitGovernor()
        governor.configureLimits("scope", rpmLimit = 10, tpmLimit = null)

        val verdicts = (1..200).map {
            async { governor.tryAcquire("scope") }
        }.awaitAll()

        assertEquals("Exactly 10 admissions must succeed", 10, verdicts.count { it })
        assertEquals("190 admissions must be refused", 190, verdicts.count { !it })
    }

    @Test
    fun `blocked window refuses tryAcquire`() = runBlocking {
        val governor = RateLimitGovernor()
        governor.recordRateLimitEncounter("scope", retryAfterMs = 60_000)
        assertFalse(governor.tryAcquire("scope"))
    }

    @Test
    fun `recordTokens accumulates TPM WITHOUT double-counting RPM`() = runBlocking {
        val governor = RateLimitGovernor()
        governor.configureLimits("scope", rpmLimit = 5, tpmLimit = 1000)
        // Admission reserved 3 request slots.
        repeat(3) { assertTrue(governor.tryAcquire("scope")) }
        // Post-completion token accounting (the EconomicGovernance path).
        governor.recordTokens("scope", 400)
        governor.recordTokens("scope", 350)
        val status = governor.statusFor("scope")
        assertEquals("RPM must stay at the reserved count (no double counting)", 3, status.rpmUsed)
        assertEquals("TPM must accumulate tokens", 750L, status.tpmUsed)
    }

    @Test
    fun `RPM-exceeded scope refuses tryAcquire even after tokens-only accounting`() = runBlocking {
        val governor = RateLimitGovernor()
        governor.configureLimits("scope", rpmLimit = 2, tpmLimit = null)
        assertTrue(governor.tryAcquire("scope"))
        assertTrue(governor.tryAcquire("scope"))
        assertFalse(governor.tryAcquire("scope"))
        // Token accounting does not re-open the RPM window.
        governor.recordTokens("scope", 10)
        assertFalse(governor.tryAcquire("scope"))
    }
}
