package com.example.domain.core.rag.intelligence

import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.RetrievedContextChunk
import com.example.domain.core.memory.RetrievalMode

/**
 * ============================================================================
 * RAG Intelligence Domain Models — Phase 5
 * ============================================================================
 *
 * Extends the basic `RagPipelineService.retrieveRelevantContext` (which
 * computed a single-pass `sim*0.7 + lexicalMatch*0.3` score over one
 * chunk list) with a real hybrid retrieval + reranking pipeline.
 *
 * The audit specifically asked for:
 *   - Hybrid lexical + vector retrieval with RRF (Reciprocal Rank Fusion)
 *   - Second-stage cross-encoder reranking
 *   - Token-budget-aware context assembly
 *   - Metadata filtering
 *   - Evidence confidence calibration
 *
 * This module is the domain model layer; the actual fusion math and
 * reranker live in `RagIntelligenceService`.
 */

/**
 * Request to the hybrid retrieval pipeline.
 */
data class HybridRetrievalRequest(
    val query: String,
    val workspaceId: String,
    val topK: Int = 10,
    val maxTokenBudget: Int = 4000,
    val metadataFilters: Map<String, String> = emptyMap(),
    val minScoreThreshold: Float = 0.2f,
    val enableReranking: Boolean = true,
    val enableLexicalIndex: Boolean = true,
    val enableSemanticIndex: Boolean = true
)

/**
 * A single candidate produced by one of the retrieval indices (lexical
 * or semantic) BEFORE fusion. The `rank` field is the position in
 * that index's result list (used by RRF).
 */
data class RetrievalCandidate(
    val chunk: DocumentChunk,
    val score: Float,
    val rank: Int,
    val source: RetrievalSource
)

enum class RetrievalSource(val storageCode: String) {
    SEMANTIC("SEMANTIC"),
    LEXICAL("LEXICAL"),
    WORKSPACE_MEMORY("WORKSPACE_MEMORY")
}

/**
 * Result of RRF fusion — one row per unique chunk, with the fused score.
 */
data class RrfFusedCandidate(
    val chunk: DocumentChunk,
    val fusedScore: Float,
    val semanticRank: Int?,
    val lexicalRank: Int?,
    val sources: List<RetrievalSource>
)

/**
 * Reranking result after the second-stage cross-encoder (or, in the
 * absence of a real cross-encoder model, a feature-based heuristic
 * reranker that combines fuzzy title match, snippet relevance, and
 * chunk recency).
 */
data class RerankResult(
    val chunk: DocumentChunk,
    val rerankedScore: Float,
    val evidenceConfidence: Float,
    val rationale: String
)

/**
 * Final assembled context — output of `RagIntelligenceService.assembleContext`.
 * Carries the formatted text PLUS the structured provenance chain so the
 * model output can be grounded back to specific documents/chunks.
 */
data class AssembledRagContextV2(
    val query: String,
    val formattedContextText: String,
    val retrievedChunks: List<RerankResult>,
    val totalTokensEstimated: Int,
    val isTruncated: Boolean,
    val evidenceProvenance: List<EvidenceProvenance>,
    val averageConfidence: Float,
    val retrievalMode: RetrievalMode
)

/**
 * Provenance for a single piece of evidence in the assembled context.
 * Lets the model cite its sources explicitly.
 */
data class EvidenceProvenance(
    val chunkId: String,
    val documentId: String,
    val documentTitle: String,
    val chunkIndex: Int,
    val confidence: Float,
    val snippet: String
)

/**
 * RRF hyperparameters.
 */
data class RrfConfig(
    val k: Int = 60,           // standard RRF constant
    val semanticWeight: Float = 1.0f,
    val lexicalWeight: Float = 0.7f,
    val memoryWeight: Float = 0.5f
)

/**
 * Reranker hyperparameters.
 */
data class RerankerConfig(
    val titleMatchWeight: Float = 0.25f,
    val snippetRelevanceWeight: Float = 0.5f,
    val recencyWeight: Float = 0.15f,
    val authorityWeight: Float = 0.1f
)
