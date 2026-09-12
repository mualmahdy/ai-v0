package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.memory.MemoryLifecycleService
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.lifecycle.CognitiveMemoryType
import com.example.domain.core.Outcome
import com.example.domain.ports.memory.MemoryRepositoryPort
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-12 (Design Closure 2026) — MemoryConsolidationScopeTest
 * ============================================================================
 *
 * The audit finding: the bootstrap called `consolidate()` with all-default
 * (null) scope → `getAllActiveMemories()` → near-duplicate memories from
 * DIFFERENT workspaces were MERGED (the survivor took the first row's
 * workspaceId — a cross-workspace isolation violation and data corruption).
 *
 * This test pins, against a REAL in-memory Room database:
 *   1. two near-identical memories in two DIFFERENT workspaces are NEVER
 *      merged by a workspace-scoped consolidation;
 *   2. near-identical memories WITHIN one workspace ARE merged (the feature
 *      still works inside its scope);
 *   3. the per-workspace consolidation is the semantic the fixed bootstrap
 *      invokes (workspaceId is a real scoping parameter, not decoration).
 */
@RunWith(RobolectricTestRunner::class)
class MemoryConsolidationScopeTest {

    private lateinit var db: AppDatabase
    private lateinit var service: MemoryLifecycleService

    /** The memory repository port is not exercised by consolidate — a stub. */
    private object StubMemoryRepository : MemoryRepositoryPort {
        override suspend fun storeMemory(entry: MemoryEntry) = Outcome.Success(Unit)
        override suspend fun retrieveMemories(
            query: String,
            topK: Int,
            minConfidence: Float
        ) = Outcome.Success(emptyList<com.example.domain.core.memory.ScoredMemoryRecord>())
        override suspend fun getAllActiveMemories() = Outcome.Success(emptyList<MemoryEntry>())
        override suspend fun deleteMemory(id: String) = Outcome.Success(Unit)
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = MemoryLifecycleService(
            memoryDao = db.memoryDao(),
            namespaceDao = db.agentMemoryNamespaceDao(),
            memoryRepository = StubMemoryRepository
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertMemory(id: String, workspaceId: String?, text: String) {
        // Identical 4-dim vectors across all rows → cosine similarity = 1.0
        // (any two rows are "near duplicates" when the scope allows it).
        val vector = EmbeddingVector(floatArrayOf(1f, 0f, 0f, 0f))
        val json = org.json.JSONArray().apply { vector.values.forEach { put(it.toDouble()) } }.toString()
        db.memoryDao().insertMemory(
            com.example.infrastructure.persistence.entities.MemoryEntity(
                id = id,
                text = text,
                vectorDimension = 4,
                vectorJson = json,
                source = "TEST",
                confidence = 0.9f,
                createdAtEpochMs = System.currentTimeMillis(),
                lastAccessedEpochMs = System.currentTimeMillis(),
                memoryType = CognitiveMemoryType.SEMANTIC.storageCode,
                importance = 0.5f,
                decayScore = 1.0f,
                workspaceId = workspaceId,
                agentId = null,
                tagsJson = "[]",
                lastDecayEvaluatedAtEpochMs = 0L
            )
        )
    }

    @Test
    fun `near-identical memories in two different workspaces are NEVER merged`() = runBlocking {
        insertMemory("mem-a1", "ws-alpha", "The quarterly report is due in March")
        insertMemory("mem-a2", "ws-alpha", "The quarterly report is due in March")
        insertMemory("mem-b1", "ws-beta", "The quarterly report is due in March")
        insertMemory("mem-b2", "ws-beta", "The quarterly report is due in March")

        // The FIXED bootstrap semantic: consolidate WITHIN each workspace.
        service.consolidate(workspaceId = "ws-alpha")
        service.consolidate(workspaceId = "ws-beta")

        val alphaRows = db.memoryDao().getActiveForWorkspace("ws-alpha")
        val betaRows = db.memoryDao().getActiveForWorkspace("ws-beta")

        // Within each workspace the pair merged (2 rows → 1 active survivor)…
        assertTrue(
            "within-scope consolidation still works: alpha should retain ONE active row, " +
                    "had ${alphaRows.size}",
            alphaRows.count { !it.isArchived } == 1
        )
        assertTrue(
            "within-scope consolidation still works: beta should retain ONE active row, " +
                    "had ${betaRows.size}",
            betaRows.count { !it.isArchived } == 1
        )
        // …and the SURVIVOR KEPT ITS OWN WORKSPACE identity (no cross-scope
        // merge happened — each scope merged only its own rows).
        assertEquals("ws-alpha", alphaRows.first { !it.isArchived }.workspaceId)
        assertEquals("ws-beta", betaRows.first { !it.isArchived }.workspaceId)
    }

    @Test
    fun `global-scope memories merge only among themselves - never with workspace memories`() = runBlocking {
        insertMemory("mem-g1", null, "Identical global knowledge")
        insertMemory("mem-g2", null, "Identical global knowledge")
        insertMemory("mem-w1", "ws-alpha", "Identical global knowledge")

        // The FIXED bootstrap also consolidates the global (workspaceId IS
        // NULL) scope separately.
        service.consolidate(workspaceId = null)

        val globals = db.memoryDao().getActiveForWorkspace(null)
        val workspace = db.memoryDao().getActiveForWorkspace("ws-alpha")

        assertEquals(
            "the global pair merges to ONE active survivor",
            1,
            globals.count { !it.isArchived }
        )
        assertEquals(
            "GAP-12: the workspace memory must NOT be merged into the global scope",
            1,
            workspace.count { !it.isArchived }
        )
    }
}
