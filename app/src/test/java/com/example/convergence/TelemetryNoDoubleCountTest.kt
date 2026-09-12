package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.observability.MetricDimensions
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricType
import com.example.infrastructure.observability.RoomTelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * P0 CONVERGENCE — TelemetryNoDoubleCountTest
 * ============================================================================
 *
 * Audit step 12 §12 (telemetry correctness): `recordBatch()` previously
 * persisted the batch via `insertAll(entities)` and THEN delegated every
 * sample to `record()` — which persisted the SAME sample AGAIN. Batches
 * were written twice to `metric_events`, so aggregate reads
 * (aggregateBuckets / analytics / economics / adaptive learning inputs)
 * double-counted counters, token usage and cost.
 *
 * This test pins EXACTLY-ONCE persistence for both paths against a real
 * Room database:
 *   - recordBatch(N samples) → exactly N rows, sums not doubled;
 *   - record(1 sample)      → exactly 1 row.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryNoDoubleCountTest {

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var repository: RoomTelemetryRepository

    /** Unconfined write scope → deterministic synchronous persistence in-test. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTelemetryRepository(
            metricEventDao = db.metricEventDao(),
            auditTrailDao = db.auditTrailDao(),
            executionTraceDao = db.executionTraceDao(),
            executionLogDao = db.executionLogDao(),
            writeScope = writeScope
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tokenSample(actionType: String, value: Long) = MetricSample(
        type = MetricType.TOKEN_USAGE,
        dimensions = MetricDimensions(
            executionId = "exec_no_double",
            providerId = "prov_x",
            actionType = actionType
        ),
        value = value
    )

    @Test
    fun `recordBatch persists every sample EXACTLY ONCE`() = runBlocking {
        val prompt = tokenSample("PROMPT", 120)
        val completion = tokenSample("COMPLETION", 80)
        repository.recordBatch(listOf(prompt, completion))

        val rows = db.metricEventDao().getByType(MetricType.TOKEN_USAGE.code, limit = 100)
        assertEquals(
            "Two batched samples must persist exactly TWO rows (the previous double-write persisted four)",
            2,
            rows.size
        )
        val promptSum = rows.filter { it.actionType == "PROMPT" }.sumOf { it.value }
        val completionSum = rows.filter { it.actionType == "COMPLETION" }.sumOf { it.value }
        assertEquals(120L, promptSum)
        assertEquals(80L, completionSum)
    }

    @Test
    fun `recordBatch aggregate buckets are not doubled`() = runBlocking {
        val prompt = tokenSample("PROMPT", 1000)
        val completion = tokenSample("COMPLETION", 500)
        repository.recordBatch(listOf(prompt, completion))

        val buckets = db.metricEventDao().aggregateBuckets()
        val promptBucket = buckets.first { it.dimensionsKey.contains("PROMPT") }
        val completionBucket = buckets.first { it.dimensionsKey.contains("COMPLETION") }
        assertEquals(
            "The aggregate read (what analytics/economics consume) must NOT double-count",
            1000L,
            promptBucket.sum
        )
        assertEquals(500L, completionBucket.sum)
        assertEquals(1, promptBucket.cnt)
        assertEquals(1, completionBucket.cnt)
    }

    @Test
    fun `record persists a single sample exactly once`() = runBlocking {
        repository.record(tokenSample("PROMPT", 7))
        val rows = db.metricEventDao().getByType(MetricType.TOKEN_USAGE.code, limit = 100)
        assertEquals(1, rows.size)
        assertEquals(7L, rows[0].value)
    }

    @Test
    fun `batched and single records combine without duplication`() = runBlocking {
        repository.recordBatch(listOf(tokenSample("PROMPT", 10), tokenSample("COMPLETION", 5)))
        repository.record(tokenSample("PROMPT", 3))
        val rows = db.metricEventDao().getByType(MetricType.TOKEN_USAGE.code, limit = 100)
        assertEquals(3, rows.size)
        assertEquals(18L, rows.sumOf { it.value })
    }
}
