package com.example.application.resilience

import com.example.domain.core.resilience.CircuitBreakerConfig
import com.example.domain.core.resilience.CircuitBreakerState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P1-12 (audit 2026 §25) — HALF_OPEN probe exclusivity.
 * ============================================================================
 *
 * The audit found that `HALF_OPEN -> return true` admitted EVERY concurrent
 * caller ("allow one probe" was only a comment). The fix arms an exclusive
 * probe lease; this test proves exactly ONE caller passes.
 */
class CircuitBreakerHalfOpenExclusivityTest {

    private fun config() = CircuitBreakerConfig(
        minCallsToOpen = 2,
        failureThreshold = 2,
        openStateCooldownMs = 50
    )

    @Test
    fun `exactly ONE concurrent caller wins the HALF_OPEN probe`() = runBlocking {
        val service = CircuitBreakerService(config())
        // Open the breaker.
        repeat(3) { service.recordFailure("res", "TIMEOUT", "timeout") }
        assertEquals(CircuitBreakerState.OPEN, service.current("res").state)
        // Cooldown elapses → HALF_OPEN.
        delay(80)

        val verdicts = (1..24).map { async { service.allowCall("res") } }.awaitAll()
        assertEquals("Exactly one probe must be admitted", 1, verdicts.count { it })
        assertEquals("Every other concurrent caller must be denied", 23, verdicts.count { !it })
        assertEquals(CircuitBreakerState.HALF_OPEN, service.current("res").state)
    }

    @Test
    fun `the probe winner's SUCCESS closes the breaker for everyone`() = runBlocking {
        val service = CircuitBreakerService(config())
        repeat(3) { service.recordFailure("res", "TIMEOUT", "timeout") }
        delay(80)
        assertTrue(service.allowCall("res")) // the one probe
        service.recordSuccess("res") // probe succeeded
        assertEquals(CircuitBreakerState.CLOSED, service.current("res").state)
        // Everyone passes again.
        val verdicts = (1..8).map { async { service.allowCall("res") } }.awaitAll()
        assertTrue(verdicts.all { it })
    }

    @Test
    fun `the probe winner's FAILURE re-opens the breaker`() = runBlocking {
        val service = CircuitBreakerService(config())
        repeat(3) { service.recordFailure("res", "TIMEOUT", "timeout") }
        delay(80)
        assertTrue(service.allowCall("res")) // the one probe
        service.recordFailure("res", "PROBE_FAILED", "probe failed")
        assertEquals(CircuitBreakerState.OPEN, service.current("res").state)
        assertFalse(service.allowCall("res"))
    }
}
