package com.example.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * DeterministicAwaitTest — pins the shared deterministic-await contract
 * ============================================================================
 *
 * The helper closed the documented load-flakiness family, so its OWN
 * contract is pinned here:
 *   1. an already-satisfied condition returns the current value at once;
 *   2. a late-landing terminal value is awaited by SUSPENDING (no polling
 *      window to miss) and the matching value is returned;
 *   3. a never-satisfied condition fails within the hang-guard timeout
 *      carrying the LAST OBSERVED VALUE (the diagnostic the old polling
 *      family never had);
 *   4. a sticky terminal state behind rapid transient updates is still
 *      observed (StateFlow conflation cannot hide a sticky terminal).
 *
 * Pure coroutine test — no Robolectric, no Room: the guarantees are
 * dispatcher-level, exercised with a real background producer thread.
 */
class DeterministicAwaitTest {

    @Test
    fun `an already-satisfied condition returns the current value immediately`() {
        val flow = MutableStateFlow(7)

        val result = flow.awaitWhere { it > 0 }

        assertEquals(7, result)
    }

    @Test
    fun `a late-landing terminal value is awaited by suspending and returned`() {
        val flow = MutableStateFlow(0)
        val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The terminal value lands from ANOTHER thread after a real
            // delay — exactly the Room-invalidation profile of the migrated
            // real-stack suites.
            producer.launch {
                delay(150)
                flow.value = 42
            }

            val startedAt = System.currentTimeMillis()
            val result = flow.awaitWhere { it == 42 }

            assertEquals(42, result)
            // Causal ordering: the await cannot complete BEFORE the producer
            // set the value (which happens >= 150ms after launch).
            assertTrue(
                "awaitWhere completed before the terminal value landed " +
                    "(${System.currentTimeMillis() - startedAt}ms)",
                System.currentTimeMillis() - startedAt >= 100
            )
        } finally {
            producer.cancel()
        }
    }

    @Test
    fun `a never-satisfied condition fails within the hang guard with the last observed value`() {
        val flow = MutableStateFlow("initial-state")

        val error = runCatching {
            flow.awaitWhere(timeoutMs = 200) { it == "never-arrives" }
        }.exceptionOrNull()

        assertTrue("expected AssertionError, got: $error", error is AssertionError)
        val message = error!!.message ?: ""
        assertTrue(
            "the failure must carry the hang-guard timeout, got: $message",
            message.contains("200")
        )
        assertTrue(
            "the failure must carry the LAST OBSERVED VALUE for diagnosis, got: $message",
            message.contains("initial-state")
        )
    }

    @Test
    fun `a sticky terminal state behind rapid transient updates is still observed`() {
        val flow = MutableStateFlow(-1)
        val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Rapid non-matching updates (the conflated-emission profile),
            // ending in a STICKY terminal state.
            producer.launch {
                repeat(100) { transient -> flow.value = transient }
                flow.value = 999
            }

            val result = flow.awaitWhere { it == 999 }

            assertEquals(999, result)
        } finally {
            producer.cancel()
        }
    }

    @Test
    fun `the negative terminal condition is awaited honestly`() {
        val flow = MutableStateFlow(true)
        val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The in-flight-flag-clearing profile (isTestingProvider-style):
            // wait for a flag to CLEAR — the condition must see the update
            // that clears it, not just the initial value.
            producer.launch {
                delay(100)
                flow.value = false
            }

            val result = flow.awaitWhere { !it }

            assertEquals(false, result)
        } finally {
            producer.cancel()
        }
    }
}
