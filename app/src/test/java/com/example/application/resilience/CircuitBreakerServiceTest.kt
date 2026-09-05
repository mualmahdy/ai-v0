package com.example.application.resilience

import com.example.domain.core.resilience.CircuitBreakerConfig
import com.example.domain.core.resilience.CircuitBreakerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — CircuitBreakerService unit tests.
 *
 * Closes the test-coverage aspect of P5-P1-10 (Production Resilience):
 * proves the circuit breaker transitions correctly between CLOSED →
 * OPEN → HALF_OPEN → CLOSED based on failure thresholds and cooldown.
 */
class CircuitBreakerServiceTest {

    @Test
    fun `fresh breaker is CLOSED and allows calls`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 3, failureThreshold = 3, openStateCooldownMs = 1000))
        assertTrue(service.allowCall("res_test"))
        assertEquals(CircuitBreakerState.CLOSED, service.current("res_test").state)
    }

    @Test
    fun `breaker stays CLOSED until minCallsToOpen is reached even with failures`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 5, failureThreshold = 3, openStateCooldownMs = 1000))
        // 3 failures but only 3 calls — below minCallsToOpen=5
        repeat(3) { service.recordFailure("res_test", "TIMEOUT", "timeout") }
        assertEquals(CircuitBreakerState.CLOSED, service.current("res_test").state)
    }

    @Test
    fun `breaker opens when failureThreshold exceeded with enough samples`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 3, failureThreshold = 3, openStateCooldownMs = 1000))
        repeat(5) { service.recordFailure("res_test", "TIMEOUT", "timeout") }
        assertEquals(CircuitBreakerState.OPEN, service.current("res_test").state)
        assertFalse(service.allowCall("res_test"))
    }

    @Test
    fun `breaker transitions to HALF_OPEN after cooldown elapses`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 2, failureThreshold = 2, openStateCooldownMs = 100))
        repeat(3) { service.recordFailure("res_test", "TIMEOUT", "timeout") }
        assertEquals(CircuitBreakerState.OPEN, service.current("res_test").state)
        // Wait for cooldown.
        kotlinx.coroutines.delay(150)
        // Next allowCall should transition to HALF_OPEN and return true.
        assertTrue(service.allowCall("res_test"))
        assertEquals(CircuitBreakerState.HALF_OPEN, service.current("res_test").state)
    }

    @Test
    fun `breaker closes after HALF_OPEN success`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 2, failureThreshold = 2, openStateCooldownMs = 50))
        repeat(3) { service.recordFailure("res_test", "TIMEOUT", "timeout") }
        kotlinx.coroutines.delay(60)
        assertTrue(service.allowCall("res_test")) // → HALF_OPEN
        service.recordSuccess("res_test") // → CLOSED
        assertEquals(CircuitBreakerState.CLOSED, service.current("res_test").state)
    }

    @Test
    fun `breaker re-opens after HALF_OPEN failure`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 2, failureThreshold = 2, openStateCooldownMs = 50))
        repeat(3) { service.recordFailure("res_test", "TIMEOUT", "timeout") }
        kotlinx.coroutines.delay(60)
        assertTrue(service.allowCall("res_test")) // → HALF_OPEN
        service.recordFailure("res_test", "TIMEOUT", "timeout") // → OPEN again
        assertEquals(CircuitBreakerState.OPEN, service.current("res_test").state)
    }

    @Test
    fun `reset forces breaker back to CLOSED`() = kotlinx.coroutines.runBlocking {
        val service = CircuitBreakerService(CircuitBreakerConfig(minCallsToOpen = 2, failureThreshold = 2, openStateCooldownMs = 10_000))
        repeat(5) { service.recordFailure("res_test", "TIMEOUT", "timeout") }
        assertEquals(CircuitBreakerState.OPEN, service.current("res_test").state)
        service.reset("res_test")
        assertEquals(CircuitBreakerState.CLOSED, service.current("res_test").state)
    }
}
