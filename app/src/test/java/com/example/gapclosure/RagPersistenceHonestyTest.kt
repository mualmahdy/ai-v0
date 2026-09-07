package com.example.gapclosure

import com.example.application.rag.KnowledgePersistenceService
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.domain.core.rag.KnowledgePersistenceState
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * RagPersistenceHonestyTest — gap-closure P1-14 / P1-15
 * ============================================================================
 *
 *  - P1-14: a FAILED durable write is no longer swallowed — the ingested
 *    document carries persistenceState=FAILED + a diagnostic (previously
 *    the ingest looked successful and the knowledge vanished on restart).
 *  - P1-15: a STALE async load (workspace A finishes AFTER workspace B)
 *    cannot clobber the newer workspace's working set (generation guard).
 */
class RagPersistenceHonestyTest {

    // ---------------- fakes ----------------

    private class ThrowingDocumentDao : KnowledgeDocumentDao {
        override fun observeDocumentsForWorkspace(workspaceId: String) =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList<KnowledgeDocumentEntity>())
        override suspend fun getDocumentsForWorkspace(workspaceId: String): List<KnowledgeDocumentEntity> = emptyList()
        override suspend fun getDocumentsForProject(projectId: Long): List<KnowledgeDocumentEntity> = emptyList()
        override suspend fun getDocumentById(id: String): KnowledgeDocumentEntity? = null
        override suspend fun insertOrUpdate(document: KnowledgeDocumentEntity) {
            throw IllegalStateException("DOC_WRITE_FAILED")
        }
        override suspend fun insertAll(documents: List<KnowledgeDocumentEntity>) {
            throw IllegalStateException("DOC_WRITE_FAILED")
        }
        override suspend fun archive(id: String, now: Long) {}
        override suspend fun deleteById(id: String) {}
        override suspend fun deleteAllForWorkspace(workspaceId: String) {}
        override suspend fun countForWorkspace(workspaceId: String): Int = 0
    }

    private class ThrowingChunkDao : DocumentChunkDao {
        override suspend fun getChunksForWorkspace(workspaceId: String): List<DocumentChunkEntity> = emptyList()
        override suspend fun getChunksForDocument(documentId: String): List<DocumentChunkEntity> = emptyList()
        override suspend fun insertOrUpdate(chunk: DocumentChunkEntity) {}
        override suspend fun insertAll(chunks: List<DocumentChunkEntity>) {}
        override suspend fun deleteChunksForDocument(documentId: String) {}
        override suspend fun deleteChunksForWorkspace(workspaceId: String) {}
        override suspend fun countForWorkspace(workspaceId: String): Int = 0
    }

    /** Delayed/controlled fake for the stale-load race (P1-15). */
    private class ScriptedPersistence(
        var delayByWorkspace: Map<String, Long> = emptyMap(),
        var docsByWorkspace: Map<String, List<KnowledgeDocumentEntity>> = emptyMap()
    ) : KnowledgePersistenceService(ThrowingDocumentDao(), ThrowingChunkDao()) {
        override suspend fun loadWorkspaceKnowledge(
            workspaceId: String
        ): Pair<List<com.example.domain.core.rag.KnowledgeDocument>, List<com.example.domain.core.rag.DocumentChunk>> {
            delayByWorkspace[workspaceId]?.let { delay(it) }
            val docs = docsByWorkspace[workspaceId].orEmpty().map {
                com.example.domain.core.rag.KnowledgeDocument(
                    id = it.id,
                    title = it.title,
                    sourceUri = it.sourceUri,
                    mimeType = "text/markdown",
                    content = it.content
                )
            }
            return docs to emptyList()
        }
    }

    private fun newPipeline(
        persistence: KnowledgePersistenceService?,
        workspaceIdProvider: () -> String = { "ws_x" }
    ): RagPipelineService = RagPipelineService(
        resourceRegistry = ComponentRegistry().resourceRegistry,
        runtimeAdapterResolver = ComponentRegistry().runtimeAdapterResolver,
        fallbackEmbeddingProvider = null,
        persistenceService = persistence,
        workspaceIdProvider = workspaceIdProvider
    )

    // ---------------- P1-14 ----------------

    @Test
    fun `failed durable write surfaces as FAILED persistenceState with diagnostic`() = runBlocking {
        val failingPersistence = KnowledgePersistenceService(ThrowingDocumentDao(), ThrowingChunkDao())
        val pipeline = newPipeline(failingPersistence)

        val doc = pipeline.ingestDocument(
            title = "مستند تجريبي",
            content = "محتوى معرفي هام يجب أن يُحفظ. ".repeat(20),
            sourceUri = "workspace://docs/test.md"
        )

        assertEquals(
            "P1-14: a failed write must NOT look like a successful ingest",
            KnowledgePersistenceState.FAILED,
            doc.persistenceState
        )
        assertNotNull(doc.persistenceDiagnostic)
        assertTrue(
            "Diagnostic must be honest about the failure",
            doc.persistenceDiagnostic!!.contains("RAG_PERSISTENCE_FAILED")
        )
    }

    @Test
    fun `ingest without a durable store reports PENDING honestly`() = runBlocking {
        val pipeline = newPipeline(persistence = null)
        val doc = pipeline.ingestDocument("t", "c", "workspace://docs/t.md")
        assertEquals(
            "No store wired = in-memory only, reported honestly (not 'persisted')",
            KnowledgePersistenceState.PENDING,
            doc.persistenceState
        )
    }

    // ---------------- P1-15 ----------------

    @Test
    fun `stale async load cannot clobber the newer workspace working set`() = runBlocking {
        val docA = KnowledgeDocumentEntity(
            id = "doc_a", workspaceId = "ws_a", projectId = null, title = "A",
            sourceUri = "a", content = "A-content", tagsJson = "[]", totalChunks = 0,
            totalTokensEstimated = 0, createdAtEpochMs = 1, updatedAtEpochMs = 1, isArchived = false
        )
        val docB = KnowledgeDocumentEntity(
            id = "doc_b", workspaceId = "ws_b", projectId = null, title = "B",
            sourceUri = "b", content = "B-content", tagsJson = "[]", totalChunks = 0,
            totalTokensEstimated = 0, createdAtEpochMs = 2, updatedAtEpochMs = 2, isArchived = false
        )
        // Workspace A's load is SLOW (300ms); workspace B's is instant.
        val scripted = ScriptedPersistence(
            delayByWorkspace = mapOf("ws_a" to 300L, "ws_b" to 0L),
            docsByWorkspace = mapOf("ws_a" to listOf(docA), "ws_b" to listOf(docB))
        )
        var currentWorkspace = "ws_a"
        val pipeline = newPipeline(scripted, workspaceIdProvider = { currentWorkspace })

        // load A (slow), then IMMEDIATELY switch + load B (fast).
        val slowLoad = launch(Dispatchers.Default) { pipeline.loadFromPersistence() }
        delay(50) // let A's load start
        currentWorkspace = "ws_b"
        pipeline.loadFromPersistence() // B completes first
        slowLoad.join() // A finishes LAST — the P1-15 race

        val titles = pipeline.documents.value.map { it.title }
        assertEquals(
            "The NEWER workspace (B) must own the working set; A's late result must be discarded",
            listOf("B"),
            titles
        )
    }
}
