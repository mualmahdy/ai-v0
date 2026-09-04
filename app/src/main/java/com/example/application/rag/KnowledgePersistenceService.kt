package com.example.application.rag

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.KnowledgeDocument
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Phase 2 — Persistent knowledge store for the RAG subsystem.
 *
 * Wraps KnowledgeDocumentDao + DocumentChunkDao so RagPipelineService can
 * load its in-memory index on startup and persist new ingestions to Room.
 *
 * Before Phase 2: RagPipelineService held `_documents: StateFlow` and
 * `chunks: CopyOnWriteArrayList` purely in memory — all ingested knowledge
 * was lost on app restart. After Phase 2: documents and chunks survive
 * restart, scoped to the active workspace.
 *
 * Vector storage note: vectors are stored as JSON Float arrays in v5. This
 * is ~3-5x storage bloat vs BLOB but keeps the schema simple for the Phase 2
 * milestone. Phase 4 will migrate to BLOB + sqlite-vec for production scale.
 */
class KnowledgePersistenceService(
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: DocumentChunkDao
) {

    /**
     * Loads all non-archived documents and their chunks for a workspace.
     * Returns a Pair of (documents, chunks) ready to be loaded into the
     * RagPipelineService in-memory index.
     */
    suspend fun loadWorkspaceKnowledge(workspaceId: String): Pair<List<KnowledgeDocument>, List<DocumentChunk>> = withContext(Dispatchers.IO) {
        val docEntities = documentDao.getDocumentsForWorkspace(workspaceId)
        if (docEntities.isEmpty()) return@withContext emptyList<KnowledgeDocument>() to emptyList()

        val chunkEntities = chunkDao.getChunksForWorkspace(workspaceId)
        val chunksByDoc = chunkEntities.groupBy { it.documentId }

        val documents = docEntities.map { entity ->
            entity.toDomain()
        }
        val chunks = chunkEntities.map { entity ->
            entity.toDomain()
        }
        documents to chunks
    }

    /**
     * Persists a document and its chunks to Room. Replaces any existing
     * document with the same id (idempotent re-ingest).
     */
    suspend fun persistDocument(
        workspaceId: String,
        document: KnowledgeDocument,
        chunks: List<DocumentChunk>
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val docEntity = KnowledgeDocumentEntity(
            id = document.id,
            workspaceId = workspaceId,
            projectId = null, // Phase 2: documents are workspace-scoped, not project-scoped yet
            title = document.title,
            sourceUri = document.sourceUri,
            content = document.content,
            tagsJson = encodeStringArray(document.tags),
            totalChunks = chunks.size,
            totalTokensEstimated = chunks.sumOf { it.tokenCount },
            createdAtEpochMs = document.createdAtTimestampMs.takeIf { it > 0 } ?: now,
            updatedAtEpochMs = now,
            isArchived = false
        )
        documentDao.insertOrUpdate(docEntity)

        // Replace chunks for this document (delete + insert)
        chunkDao.deleteChunksForDocument(document.id)
        val chunkEntities = chunks.map { chunk ->
            DocumentChunkEntity(
                id = chunk.id,
                documentId = chunk.documentId,
                workspaceId = workspaceId,
                chunkIndex = chunk.chunkIndex,
                text = chunk.text,
                tokenCount = chunk.tokenCount,
                vectorDimension = chunk.vector?.values?.size ?: 0,
                vectorJson = encodeVector(chunk.vector?.values),
                retrievalSource = if (chunk.vector != null) "SEMANTIC" else "LEXICAL_FALLBACK",
                createdAtEpochMs = now
            )
        }
        chunkDao.insertAll(chunkEntities)
    }

    /**
     * Archives a document (soft-delete). Archived documents are excluded from
     * loadWorkspaceKnowledge but remain in the database for audit/restore.
     */
    suspend fun archiveDocument(documentId: String) = withContext(Dispatchers.IO) {
        documentDao.archive(documentId, System.currentTimeMillis())
    }

    /**
     * Hard-deletes a document and all its chunks.
     */
    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        chunkDao.deleteChunksForDocument(documentId)
        documentDao.deleteById(documentId)
    }

    /**
     * Deletes all documents and chunks for a workspace. Used when a workspace
     * is deleted (cascade).
     */
    suspend fun deleteAllForWorkspace(workspaceId: String) = withContext(Dispatchers.IO) {
        chunkDao.deleteChunksForWorkspace(workspaceId)
        documentDao.deleteAllForWorkspace(workspaceId)
    }

    /**
     * Returns the count of active (non-archived) documents in a workspace.
     */
    suspend fun documentCount(workspaceId: String): Int = withContext(Dispatchers.IO) {
        documentDao.countForWorkspace(workspaceId)
    }

    // ────────────────────────────────────────────────────────────────────
    // Mappers
    // ────────────────────────────────────────────────────────────────────

    private fun KnowledgeDocumentEntity.toDomain(): KnowledgeDocument = KnowledgeDocument(
        id = id,
        title = title,
        sourceUri = sourceUri,
        content = content,
        tags = decodeStringArray(tagsJson),
        totalChunks = totalChunks,
        createdAtTimestampMs = createdAtEpochMs
    )

    private fun DocumentChunkEntity.toDomain(): DocumentChunk {
        val vector = decodeVector(vectorJson, vectorDimension)
        return DocumentChunk(
            id = id,
            documentId = documentId,
            documentTitle = "", // populated by caller from document map
            chunkIndex = chunkIndex,
            text = text,
            tokenCount = tokenCount,
            vector = vector
        )
    }

    private fun encodeStringArray(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStringArray(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeVector(values: FloatArray?): String {
        if (values == null) return "[]"
        val arr = JSONArray()
        values.forEach { arr.put(it.toDouble()) }
        return arr.toString()
    }

    private fun decodeVector(json: String, expectedDim: Int): EmbeddingVector? {
        if (json.isBlank() || json == "[]") return null
        return try {
            val arr = JSONArray(json)
            val floats = FloatArray(arr.length())
            for (i in 0 until arr.length()) {
                floats[i] = arr.getDouble(i).toFloat()
            }
            if (expectedDim > 0 && floats.size != expectedDim) {
                // Dimension mismatch — log and return null so caller falls back
                return null
            }
            EmbeddingVector(floats)
        } catch (_: Exception) {
            null
        }
    }
}
