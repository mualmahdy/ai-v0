package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.resilience.CircuitBreakerService
import com.example.application.resilience.CircuitBreakerStateSink
import com.example.domain.core.resilience.CircuitBreakerConfig
import com.example.domain.core.resilience.CircuitBreakerState
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ToolHealthSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 * GAP-17 closure (Design Closure 2026, Phase 4) — CircuitBreakerPersistenceTest
 * ============================================================================
 *
 * The deferral is lifted: breaker state is now DURABLE.
 *   1. STATE CHANGES persist to `tool_health_snapshots.circuitState` via
 *      [CircuitBreakerStateSink] (identity mapping: resourceId ↔ toolId);
 *      unchanged emissions do NOT rewrite (no per-call DB churn).
 *   2. RESTORE: a fresh service re-seeded from the persisted row (the exact
 *      bootstrapRuntime flow) keeps fail-fasting an OPEN breaker — a process
 *      restart no longer resets failure history.
 *   3. FAIL-OPEN: a throwing writer is counted + logged, never propagated.
 */
@RunWith(RobolectricTestRunner::class)
class CircuitBreakerPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: CircuitBreakerService

    private companion object {
        const val RESOURCE = "llm_gemini_flash"
        /** Long cooldown so a restored OPEN breaker stays OPEN in-test. */
        val CONFIG = CircuitBreakerConfig(
            minCallsToOpen = 2,
            failureThreshold = 2,
            openStateCooldownMs = 10 * 60 * 1000L
        )
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = CircuitBreakerService(CONFIG)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun roomSink(): CircuitBreakerStateSink = CircuitBreakerStateSink(
        service = service,
        writer = { resourceId, snapshot ->
            db.toolHealthDao().upsert(
                ToolHealthSnapshotEntity(
                    toolId = resourceId,
                    totalCalls = snapshot.totalCalls,
                    successCount = snapshot.successCount,
                    failureCount = snapshot.failureCount,
                    degradedCount = 0L,
                    averageLatencyMs = 0.0,
                    p95LatencyMs = 0L,
                    lastFailureCode = snapshot.lastFailureCode,
                    lastErrorMessage = snapshot.lastErrorMessage,
                    circuitState = snapshot.state.storageCode,
                    openedAtEpochMs = snapshot.openedAtEpochMs,
                    lastUpdatedEpochMs = snapshot.lastUpdatedEpochMs
                )
            )
        }
    )

    private suspend fun openTheBreaker() {
        // CLOSED: calls allowed while failures accumulate.
        assertTrue("a fresh CLOSED breaker allows calls", service.allowCall(RESOURCE))
        // THREE recorded failures open a (minCallsToOpen=2, failureThreshold=2)
        // breaker: recordFailure evaluates the threshold against the
        // PRE-increment totalCalls (update() counts this call only AFTER
        // the state decision) — pinned by CircuitBreakerServiceTest's
        // `repeat(3)` recipe. Two failures leave it CLOSED.
        service.recordFailure(RESOURCE, "LLM_STEP_FAILED", "boom 1")
        service.recordFailure(RESOURCE, "LLM_STEP_FAILED", "boom 2")
        service.recordFailure(RESOURCE, "LLM_STEP_FAILED", "boom 3")
        assertEquals(CircuitBreakerState.OPEN, service.current(RESOURCE).state)
        assertFalse("an OPEN breaker fail-fasts", service.allowCall(RESOURCE))
    }

    @Test
    fun `state changes persist to tool_health_snapshots and unchanged emissions do not rewrite`() = runBlocking {
        openTheBreaker()
        assertEquals(CircuitBreakerState.OPEN, service.current(RESOURCE).state)

        val writes = AtomicInteger(0)
        val countingSink = CircuitBreakerStateSink(
            service = service,
            writer = { resourceId, snapshot ->
                writes.incrementAndGet()
                db.toolHealthDao().upsert(
                    ToolHealthSnapshotEntity(
                        toolId = resourceId,
                        totalCalls = snapshot.totalCalls,
                        successCount = snapshot.successCount,
                        failureCount = snapshot.failureCount,
                        degradedCount = 0L,
                        averageLatencyMs = 0.0,
                        p95LatencyMs = 0L,
                        lastFailureCode = snapshot.lastFailureCode,
                        lastErrorMessage = snapshot.lastErrorMessage,
                        circuitState = snapshot.state.storageCode,
                        openedAtEpochMs = snapshot.openedAtEpochMs,
                        lastUpdatedEpochMs = snapshot.lastUpdatedEpochMs
                    )
                )
            }
        )
        countingSink.persistIfChanged(service.states.value)
        val afterFirst = writes.get()
        assertTrue("the OPEN transition must be written", afterFirst >= 1)

        val row = db.toolHealthDao().byTool(RESOURCE)
        assertNotNull("GAP-17: the breaker row must exist", row)
        assertEquals("OPEN", row!!.circuitState)
        assertNotNull("the openedAt anchor must persist", row.openedAtEpochMs)
        assertTrue(row.failureCount >= 2)

        // Same state again → change detection skips the write.
        countingSink.persistIfChanged(service.states.value)
        assertEquals("unchanged (state, openedAt) must NOT rewrite", afterFirst, writes.get())
    }

    @Test
    fun `restore re-seeds a fresh service and the breaker stays OPEN after a restart`() = runBlocking {
        // "First process": open + persist.
        openTheBreaker()
        val sink = roomSink()
        sink.persistIfChanged(service.states.value)
        val persisted = db.toolHealthDao().byTool(RESOURCE)!!
        assertEquals("OPEN", persisted.circuitState)

        // "Restart": a FRESH service (empty in-memory breakers) re-seeded from
        // the persisted row — the exact bootstrapRuntime restore flow.
        val restarted = CircuitBreakerService(CONFIG)
        restarted.restorePersisted(
            com.example.domain.core.resilience.CircuitBreakerSnapshot(
                resourceId = persisted.toolId,
                state = CircuitBreakerState.valueOf(persisted.circuitState),
                failureCount = persisted.failureCount,
                successCount = persisted.successCount,
                consecutiveFailures = 0,
                totalCalls = persisted.totalCalls,
                openedAtEpochMs = persisted.openedAtEpochMs,
                lastFailureCode = persisted.lastFailureCode,
                lastErrorMessage = persisted.lastErrorMessage,
                lastUpdatedEpochMs = persisted.lastUpdatedEpochMs
            )
        )
        assertFalse(
            "GAP-17: a restored OPEN breaker must keep fail-fasting (cooldown anchored by persisted openedAt)",
            restarted.allowCall(RESOURCE)
        )
        assertEquals(CircuitBreakerState.OPEN, restarted.current(RESOURCE).state)
    }

    @Test
    fun `sink write failure is counted and fail-open - never propagated`() = runBlocking {
        openTheBreaker()
        val sink = CircuitBreakerStateSink(
            service = service,
            writer = { _, _ -> throw IllegalStateException("disk full") }
        )
        // Must NOT throw — the breaker's job is fast-failing calls.
        sink.persistIfChanged(service.states.value)
        assertEquals(
            "GAP-13 honest degradation: the swallowed failure must be counted",
            1L,
            sink.persistenceFailureCount
        )
    }
}
