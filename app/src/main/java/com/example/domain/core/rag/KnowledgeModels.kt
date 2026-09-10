package com.example.domain.core.rag

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.RetrievalMode

/**
 * Ingested document in the Knowledge Base.
 *
 * Phase 2: Added `createdAtTimestampMs` so KnowledgePersistenceService can
 * round-trip the creation timestamp. The legacy `ingestedTimestampMs` field
 * is kept for backwards compatibility and mirrors `createdAtTimestampMs`.
 */
/**
 * GAP-CLOSURE P1-14: honest persistence state of an ingested knowledge
 * document. Previously a failed Room write was swallowed and the ingest
 * looked successful (RAM = ingested, DB = missing — the knowledge silently
 * vanished after restart). The state is now explicit and surfaces in the UI.
 */
enum class KnowledgePersistenceState {
    /** Durable in Room — survives restarts. */
    PERSISTED,
    /** No durable store wired — in-memory only (honest, e.g. tests). */
    PENDING,
    /** Persistence ATTEMPTED AND FAILED (with diagnostic) — volatile only. */
    FAILED
}

data class KnowledgeDocument(
    val id: String,
    val title: String,
    val sourceUri: String,
    val mimeType: String = "text/markdown",
    val content: String,
    val tags: List<String> = emptyList(),
    val totalChunks: Int = 0,
    val ingestedTimestampMs: Long = System.currentTimeMillis(),
    val createdAtTimestampMs: Long = ingestedTimestampMs,
    /** P1-14: honest durability state of this document. */
    val persistenceState: KnowledgePersistenceState = KnowledgePersistenceState.PERSISTED,
    /** P1-14: human-readable diagnostic when persistence FAILED/PENDING. */
    val persistenceDiagnostic: String? = null,
    /**
     * REPAIR ORDER §15 — knowledge ownership: projectId is NULL for
     * WORKSPACE-scoped (shared) knowledge, non-null for PROJECT-private
     * knowledge. Retrieval NEVER crosses the boundary implicitly.
     */
    val projectId: Long? = null
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
 * REPAIR ORDER §15 — explicit retrieval scope modes. Retrieval is bounded
 * by the caller's pinned scope; cross-project knowledge is NEVER silently
 * retrieved.
 */
enum class RetrievalScopeMode {
    /** Only the pinned project's private knowledge. */
    PROJECT_ONLY,
    /** Pinned project's private knowledge + workspace-shared knowledge. */
    PROJECT_AND_WORKSPACE,
    /** Only workspace-shared knowledge (no project-private). */
    WORKSPACE_ONLY,
    /** Application-scoped shared knowledge (explicitly shared app-wide only). */
    APPLICATION
}

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
