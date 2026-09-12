package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.Outcome
import com.example.domain.core.memory.VectorMath
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.MemoryEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-28 (Design Closure 2026) — VectorStoreHonestDecodeTest
 * ============================================================================
 *
 * RoomVectorStoreAdapter previously had:
 *   - a THROWS-on-corrupt-JSON `parseVectorJson` — one bad row poisoned the
 *     WHOLE retrieval (outer catch → Outcome.Error(StorageReadError) for a
 *     query whose other rows were perfectly fine);
 *   - zero validation of the stored `vectorDimension` against the actual
 *     JSON length;
 *   - ZERO direct test coverage of any of this.
 *
 * Now decode is per-row honest: a corrupt/mismatched row is SKIPPED,
 * counted, and logged — the retrieval still returns every healthy row.
 * This test pins that against a REAL in-memory Room database with three
 * seeded rows: one healthy, one malformed-JSON, one dimension-mismatched.
 */
@RunWith(RobolectricTestRunner::class)
class VectorStoreHonestDecodeTest {

    private lateinit var db: AppDatabase
    private lateinit var adapter: RoomVectorStoreAdapter

    private val workspace = "ws-decode"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        adapter = RoomVectorStoreAdapter(
            memoryDao = db.memoryDao(),
            embeddingProvider = null,
            workspaceIdProvider = { workspace }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun encodeVector(values: FloatArray): String =
        JSONArray().also { arr -> values.forEach { arr.put(it.toDouble()) } }.toString()

    private suspend fun insertRow(id: String, vectorJson: String, vectorDimension: Int) {
        db.memoryDao().insertMemory(
            MemoryEntity(
                id = id,
                text = "memory row $id",
                vectorDimension = vectorDimension,
                vectorJson = vectorJson,
                source = "TEST",
                confidence = 1.0f,
                createdAtEpochMs = System.currentTimeMillis(),
                lastAccessedEpochMs = System.currentTimeMillis(),
                workspaceId = workspace
            )
        )
    }

    private fun healthyVectorJson(): String {
        // The exact 128-dim lexical vector a NULL-provider query for this
        // text produces — guarantees cosine == 1.0 against the query side.
        return encodeVector(VectorMath.lexicalSparseVector("decode honesty probe").values)
    }

    @Test
    fun `corrupt rows are skipped and counted - healthy rows still returned`() = runBlocking {
        insertRow("row-healthy", healthyVectorJson(), 128)
        insertRow("row-corrupt-json", "this-is-not-json", 128)
        insertRow("row-dim-mismatch", encodeVector(floatArrayOf(1f, 2f, 3f)), 128)

        // No embedding provider → the honest outcome is DEGRADED (lexical
        // fallback), carrying the healthy results as partialValue.
        val outcome = adapter.retrieveMemories(query = "decode honesty probe", topK = 10, minConfidence = 0f)

        assertTrue(
            "retrieval must not ERROR despite corrupt rows, was $outcome",
            outcome is Outcome.Success || outcome is Outcome.Degraded
        )
        val records = when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Degraded -> outcome.partialValue ?: emptyList()
            else -> emptyList()
        }
        assertEquals("ONLY the healthy row is returned (corrupt rows skipped)", 1, records.size)
        assertEquals("row-healthy", records.first().entry.id)

        assertEquals("both corrupt rows are counted", 2, adapter.vectorDecodeFailures.get())
        assertNotNull("last decode failure recorded", adapter.lastVectorDecodeFailure)
    }

    @Test
    fun `querySimilar survives corrupt rows too`() = runBlocking {
        insertRow("row-healthy", healthyVectorJson(), 128)
        insertRow("row-corrupt-json", "{broken", 128)

        val queryVector = VectorMath.lexicalSparseVector("decode honesty probe")
        val outcome = adapter.querySimilar(queryVector, topK = 10, minScoreThreshold = 0.5f)

        assertTrue("query must SUCCEED, was $outcome", outcome is com.example.domain.core.Outcome.Success)
        val records = (outcome as com.example.domain.core.Outcome.Success).value
        assertEquals(1, records.size)
        assertEquals("row-healthy", records.first().id)
        assertTrue(adapter.vectorDecodeFailures.get() >= 1)
    }

    @Test
    fun `dimension mismatch is detected as corruption - not silently accepted`() = runBlocking {
        // Stored dimension says 4 but the JSON holds 3 floats.
        insertRow("row-mismatch", encodeVector(floatArrayOf(1f, 2f, 3f)), 4)

        val outcome = adapter.retrieveMemories(query = "anything", topK = 10, minConfidence = 0f)

        assertTrue(outcome is Outcome.Success || outcome is Outcome.Degraded)
        val records = when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Degraded -> outcome.partialValue ?: emptyList()
            else -> emptyList()
        }
        assertEquals("the mismatched row is skipped, not scored", 0, records.size)
        assertTrue(
            "the mismatch must be recorded with the machine-readable prefix",
            adapter.lastVectorDecodeFailure?.startsWith("VECTOR_DIMENSION_MISMATCH") == true
        )
    }

    @Test
    fun `healthy-only store has zero decode failures`() = runBlocking {
        insertRow("row-healthy", healthyVectorJson(), 128)

        val outcome = adapter.retrieveMemories(query = "decode honesty probe", topK = 10, minConfidence = 0f)

        assertTrue(outcome is Outcome.Success || outcome is Outcome.Degraded)
        val records = when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Degraded -> outcome.partialValue ?: emptyList()
            else -> emptyList()
        }
        assertEquals(1, records.size)
        assertEquals(0, adapter.vectorDecodeFailures.get())
    }
}
