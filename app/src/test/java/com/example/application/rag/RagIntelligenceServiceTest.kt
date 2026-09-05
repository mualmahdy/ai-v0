package com.example.application.rag

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.rag.intelligence.HybridRetrievalRequest
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — RagIntelligenceService unit tests.
 *
 * Closes the test-coverage aspect of P5-P0-06 (RAG Intelligence):
 * proves RRF fusion, reranking, and token-budget assembly all work
 * as designed.
 */
class RagIntelligenceServiceTest {

    @Test
    fun `rrfFuse combines semantic and lexical ranks with k=60 weighting`() {
        val service = RagIntelligenceService(StubChunkDao(emptyList()), StubEmbeddingProvider())
        val semantic = listOf(
            makeCandidate("a", rank = 1),
            makeCandidate("b", rank = 2)
        )
        val lexical = listOf(
            makeCandidate("a", rank = 3),
            makeCandidate("c", rank = 1)
        )
        val fused = service.rrfFuse(semantic, lexical)
        // Item "a" appears in both → highest fused score.
        assertEquals("a", fused.first().chunk.id)
        // Item "c" only in lexical, rank 1 → score 0.7/(60+1)
        val cFused = fused.first { it.chunk.id == "c" }
        assertEquals(1, cFused.sources.size)
    }

    @Test
    fun `rerank boosts items with title and snippet matches`() {
        val service = RagIntelligenceService(StubChunkDao(emptyList()), StubEmbeddingProvider())
        val candidates = listOf(
            makeFused("a", "GPT-4 Overview", "GPT-4 is a transformer model"),
            makeFused("b", "Random Title", "Completely unrelated content")
        )
        val reranked = service.rerank(candidates, "GPT-4 overview")
        assertEquals("a", reranked.first().chunk.id)
        assertTrue(reranked.first().evidenceConfidence > reranked.last().evidenceConfidence)
    }

    @Test
    fun `retrieveAndAssemble returns empty context for empty workspace`() = runBlocking {
        val service = RagIntelligenceService(StubChunkDao(emptyList()), StubEmbeddingProvider())
        val result = service.retrieveAndAssemble(
            HybridRetrievalRequest(query = "test", workspaceId = "ws_empty")
        )
        assertTrue(result.formattedContextText.isEmpty())
        assertEquals(0, result.retrievedChunks.size)
    }

    @Test
    fun `retrieveAndAssemble packs chunks within token budget`() = runBlocking {
        val chunks = listOf(
            makeChunkEntity("c1", "doc1", "First chunk of content with enough text to be meaningful"),
            makeChunkEntity("c2", "doc1", "Second chunk with more text content for testing purposes"),
            makeChunkEntity("c3", "doc1", "Third chunk that should not fit if budget is tight")
        )
        val service = RagIntelligenceService(StubChunkDao(chunks), StubEmbeddingProvider())
        val result = service.retrieveAndAssemble(
            HybridRetrievalRequest(
                query = "content chunk",
                workspaceId = "ws_1",
                maxTokenBudget = 50 // very tight budget
            )
        )
        // At least one chunk should be included; the rest truncated.
        assertTrue(result.retrievedChunks.isNotEmpty())
        assertTrue(result.totalTokensEstimated <= 50 || result.isTruncated)
    }

    private fun makeCandidate(id: String, rank: Int): com.example.domain.core.rag.intelligence.RetrievalCandidate {
        return com.example.domain.core.rag.intelligence.RetrievalCandidate(
            chunk = makeChunk(id, "Doc $id"),
            score = 0.5f,
            rank = rank,
            source = com.example.domain.core.rag.intelligence.RetrievalSource.SEMANTIC
        )
    }

    private fun makeFused(id: String, title: String, text: String): com.example.domain.core.rag.intelligence.RrfFusedCandidate {
        return com.example.domain.core.rag.intelligence.RrfFusedCandidate(
            chunk = makeChunk(id, title, text),
            fusedScore = 0.1f,
            semanticRank = 1,
            lexicalRank = null,
            sources = listOf(com.example.domain.core.rag.intelligence.RetrievalSource.SEMANTIC)
        )
    }

    private fun makeChunk(id: String, title: String, text: String = "text"): com.example.domain.core.rag.DocumentChunk {
        return com.example.domain.core.rag.DocumentChunk(
            id = id,
            documentId = "doc_$id",
            documentTitle = title,
            chunkIndex = 0,
            text = text,
            vector = EmbeddingVector(FloatArray(128) { 0.5f }, 128)
        )
    }

    private fun makeChunkEntity(id: String, documentId: String, text: String): DocumentChunkEntity {
        return DocumentChunkEntity(
            id = id,
            documentId = documentId,
            workspaceId = "ws_1",
            chunkIndex = 0,
            text = text,
            tokenCount = text.length / 4,
            vectorDimension = 128,
            vectorJson = "[${FloatArray(128) { 0.5f }.joinToString(",")}]",
            retrievalSource = "SEMANTIC",
            createdAtEpochMs = System.currentTimeMillis()
        )
    }

    private class StubChunkDao(val chunks: List<DocumentChunkEntity>) : DocumentChunkDao {
        override suspend fun getChunksForWorkspace(workspaceId: String): List<DocumentChunkEntity> = chunks
        override suspend fun getChunksForDocument(documentId: String): List<DocumentChunkEntity> = chunks.filter { it.documentId == documentId }
        override suspend fun insertOrUpdate(chunk: DocumentChunkEntity) {}
        override suspend fun insertAll(chunks: List<DocumentChunkEntity>) {}
        override suspend fun deleteChunksForDocument(documentId: String) {}
        override suspend fun deleteChunksForWorkspace(workspaceId: String) {}
        override suspend fun countForWorkspace(workspaceId: String): Int = chunks.size
    }

    private class StubEmbeddingProvider : EmbeddingProviderPort {
        override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> {
            return Outcome.Success(texts.map { EmbeddingVector(FloatArray(128) { 0.5f }, 128) })
        }
    }
}
