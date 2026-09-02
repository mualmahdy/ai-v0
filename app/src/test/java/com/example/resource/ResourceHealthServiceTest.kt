package com.example.resource

import com.example.application.resource.InMemoryResourceHealthService
import com.example.domain.core.resource.ResourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section N acceptance tests — ResourceHealthService (P0.3, Section G — LOCKED).
 * Covers matrix rows 12 (health side), 15b (health side), plus locked sliding-window,
 * cooldown trigger/recovery, and healthScore determinism.
 */
class ResourceHealthServiceTest {

    private var fakeNow: Long = 1_000_000L

    private fun newService(): InMemoryResourceHealthService =
        InMemoryResourceHealthService(now = { fakeNow })

    /**
     * Test 12 (health side) — transport outcomes only: a successful transport call
     * records success regardless of output correctness; verification is a separate
     * track (asserted in the closed-loop tests). RULE RH-1.
     */
    @Test
    fun test12_transportSuccess_recordsSuccess_notCorrectness() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")
        // "HTTP 200 + semantically wrong answer" — the transport call succeeded.
        service.recordTransportSuccess(id, latencyMs = 250L)

        val health = service.getHealth(id)
        assertEquals(1.0, health.successRate, 0.0001)
        assertEquals(250L, health.averageLatencyMs)
        assertFalse(health.isInCooldown)
        // Health track exposes transport metrics only — no task-correctness dimension exists.
        assertEquals(1, health.sampleSize)
    }

    /** Test 15b (health side) — 1 failure: health degrades, cooldown NOT triggered. */
    @Test
    fun test15b_singleFailure_degrades_noCooldown() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")

        service.recordTransportSuccess(id, 100L)
        service.recordTransportFailure(id, "HTTP 503")

        val health = service.getHealth(id)
        assertEquals(0.5, health.successRate, 0.0001) // degraded
        assertEquals(1, service.consecutiveFailures(id))
        assertFalse(service.isInCooldown(id)) // needs 3 consecutive
    }

    /** Locked cooldown trigger: 3 consecutive transport failures -> 5 minutes cooldown. */
    @Test
    fun cooldown_threeConsecutiveFailures_triggersFiveMinutes() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")

        service.recordTransportFailure(id, "timeout")
        service.recordTransportFailure(id, "timeout")
        assertFalse(service.isInCooldown(id))

        service.recordTransportFailure(id, "timeout") // 3rd consecutive
        assertTrue(service.isInCooldown(id))

        val health = service.getHealth(id)
        assertEquals(fakeNow + 5L * 60L * 1000L, health.inCooldownUntil)
    }

    /** Locked cooldown recovery: success AFTER cooldown expiry clears cooldown + counter. */
    @Test
    fun cooldown_recoveryAfterExpiry_clearsCooldownAndCounter() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")

        repeat(3) { service.recordTransportFailure(id, "conn refused") }
        assertTrue(service.isInCooldown(id))

        // Time advances past the cooldown window (5 minutes).
        fakeNow += 5L * 60L * 1000L + 1L
        assertFalse(service.isInCooldown(id)) // expired (timestamp-based)

        // First successful execution after cooldown clears counter.
        service.recordTransportSuccess(id, 120L)
        assertEquals(0, service.consecutiveFailures(id))
        val health = service.getHealth(id)
        assertEquals(null, health.inCooldownUntil)
        assertTrue(health.successRate > 0.0)
    }

    /** A success DURING cooldown clears the cooldown (locked recovery rule). */
    @Test
    fun cooldown_successDuringCooldown_clearsImmediately() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")
        repeat(3) { service.recordTransportFailure(id, "fail") }
        assertTrue(service.isInCooldown(id))

        service.recordTransportSuccess(id, 80L)
        assertFalse(service.isInCooldown(id))
        assertEquals(0, service.consecutiveFailures(id))
    }

    /** Locked window: only the last 20 transport outcomes are aggregated. */
    @Test
    fun window_onlyLastTwentyOutcomesAggregated() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")

        repeat(25) { service.recordTransportFailure(id, "fail") } // window full of failures
        repeat(15) { service.recordTransportSuccess(id, 100L) }  // 15 most recent are successes

        val health = service.getHealth(id)
        assertEquals(20, health.sampleSize)
        assertEquals(0.75, health.successRate, 0.0001) // 15/20 — the first 5 failures dropped off
    }

    /** healthScore is deterministic: identical windows produce identical scores. */
    @Test
    fun healthScore_deterministic() = runBlocking {
        val serviceA = newService()
        val serviceB = newService()
        val idA = ResourceId("res_a")
        val idB = ResourceId("res_b")

        val script: suspend (InMemoryResourceHealthService, ResourceId) -> Unit = { svc, id ->
            svc.recordTransportSuccess(id, 200L)
            svc.recordTransportSuccess(id, 300L)
            svc.recordTransportFailure(id, "err")
            svc.recordTransportSuccess(id, 400L)
        }
        script(serviceA, idA)
        script(serviceB, idB)

        val scoreA = serviceA.getHealth(idA).healthScore
        val scoreB = serviceB.getHealth(idB).healthScore
        assertEquals(scoreA, scoreB, 0.000001)
        assertTrue(scoreA in 0.0..1.0)
    }

    /** Latency penalty is part of the documented deterministic formula. */
    @Test
    fun healthScore_latencyPenaltyApplied() = runBlocking {
        val fast = newService()
        val slow = newService()

        repeat(5) { fast.recordTransportSuccess(ResourceId("res_fast"), 100L) }
        repeat(5) { slow.recordTransportSuccess(ResourceId("res_slow"), 9000L) }

        assertNotEquals(
            fast.getHealth(ResourceId("res_fast")).healthScore,
            slow.getHealth(ResourceId("res_slow")).healthScore,
            0.0001
        )
        assertTrue(
            fast.getHealth(ResourceId("res_fast")).healthScore >
                slow.getHealth(ResourceId("res_slow")).healthScore
        )
    }

    /** Timeout failures feed the timeoutRate dimension (transport-layer metric). */
    @Test
    fun timeoutRate_tracked() = runBlocking {
        val service = newService()
        val id = ResourceId("res_test")
        service.recordTransportFailure(id, "connect timed out", isTimeout = true)
        service.recordTransportSuccess(id, 150L)

        val health = service.getHealth(id)
        assertEquals(0.5, health.timeoutRate, 0.0001)
    }
}
