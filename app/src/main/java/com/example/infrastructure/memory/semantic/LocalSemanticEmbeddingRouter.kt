package com.example.infrastructure.memory.semantic

import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.ports.memory.EmbeddingProviderPort

/**
 * Local embedding router (audit 2026 fix).
 *
 * Routes embedding requests to the best available LOCAL source:
 *   1. ONNX sentence-transformer (REAL semantic, when provisioned).
 *   2. Deterministic hash generator (lexical fallback — honestly labeled).
 *
 * The router exists so the RAG pipeline / memory subsystems always have ONE
 * local embedding resource, while the retrieval mode labeling stays truthful
 * via [isSemantic].
 *
 * GAP-25 (Design Closure 2026): the fallback is now HONEST at the source.
 * Previously, when the provisioned ONNX model FAILED at inference time, the
 * router silently returned lexical vectors while `isSemantic` stayed TRUE
 * and metadata kept describing the SEMANTIC adapter — downstream retrieval
 * labeling (RagPipelineService) then claimed SEMANTIC mode for lexical
 * vectors. Now a semantic inference failure flips [isSemantic] to false
 * (lexical) until a semantic call SUCCEEDS again, so the whole outcome
 * chain (dimension/metadata/retrieval-mode) tells the same truth.
 */
class LocalSemanticEmbeddingRouter(
    private val semanticAdapter: OnnxSemanticEmbeddingAdapter,
    private val lexicalFallback: EmbeddingProviderPort
) : EmbeddingProviderPort, EmbeddingQualityMarker {

    override val providerId: String = semanticAdapter.providerId

    /**
     * GAP-25: TRUE while the last actual generation came from the ONNX
     * model. Flips to false when a semantic inference fails and the lexical
     * fallback serves the request; flips back on the next successful
     * semantic generation.
     */
    @Volatile
    private var degradedToLexical = false

    /** Dimension follows the ACTIVE source (both local sources are fixed-dim). */
    override val dimension: Int
        get() = if (isSemantic) semanticAdapter.dimension else lexicalFallback.dimension

    override val isSemantic: Boolean
        get() = semanticAdapter.isProvisioned && !degradedToLexical

    override val metadata: SafeEmbeddingProviderMetadata
        get() = if (isSemantic) semanticAdapter.metadata else lexicalFallback.metadata

    /**
     * One-time provisioning of the ONNX semantic model (~23MB). Idempotent;
     * honest failure on network/IO errors. After Success, the router serves
     * REAL semantic embeddings and reports `isSemantic = true`.
     */
    suspend fun provision(): Outcome<Unit, String> = semanticAdapter.provision()

    override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> {
        if (semanticAdapter.isProvisioned) {
            val semanticOutcome = semanticAdapter.generateEmbeddings(texts)
            if (semanticOutcome !is Outcome.Error) {
                degradedToLexical = false
                return semanticOutcome
            }
            // Semantic inference failed → degrade HONESTLY: the vectors
            // returned by the lexical fallback below are labeled lexical
            // (isSemantic = false, lexical metadata/dimension) — the
            // previous comment claimed the failure was "preserved in the
            // outcome chain" while every downstream label still read
            // SEMANTIC.
            degradedToLexical = true
        }
        return lexicalFallback.generateEmbeddings(texts)
    }
}
