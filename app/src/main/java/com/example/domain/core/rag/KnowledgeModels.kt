package com.example.domain.core.rag

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.RetrievalMode

/**
 * Ingested document in the Knowledge Base.
 */
data class KnowledgeDocument(
    val id: String,
    val title: String,
    val sourceUri: String,
    val mimeType: String = "text/markdown",
    val content: String,
    val tags: List<String> = emptyList(),
    val totalChunks: Int = 0,
    val ingestedTimestampMs: Long = System.currentTimeMillis()
)

/**
 * Text chunk derived from document splitting with semantic embedding and metadata.
 */
data class DocumentChunk(
    val id: String,
    val documentId: String,
    val documentTitle: String,
    val chunkIndex: Int,
    val text: String,
    val vector: EmbeddingVector? = null,
    val tokenCount: Int = 0,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Retrieved RAG context chunk with relevance score and provenance.
 */
data class RetrievedContextChunk(
    val chunk: DocumentChunk,
    val relevanceScore: Float,
    val retrievalMode: RetrievalMode,
    val snippet: String
)

/**
 * Complete assembled RAG prompt context with safety and token limits.
 */
data class AssembledRagContext(
    val query: String,
    val formattedContextText: String,
    val retrievedChunks: List<RetrievedContextChunk>,
    val totalTokensEstimated: Int,
    val isTruncated: Boolean = false
)
