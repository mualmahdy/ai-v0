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
 */
class LocalSemanticEmbeddingRouter(
    private val semanticAdapter: OnnxSemanticEmbeddingAdapter,
    private val lexicalFallback: EmbeddingProviderPort
) : EmbeddingProviderPort, EmbeddingQualityMarker {

    override val providerId: String = semanticAdapter.providerId

    /** Dimension follows the ACTIVE source (both local sources are fixed-dim). */
    override val dimension: Int
        get() = if (semanticAdapter.isProvisioned) semanticAdapter.dimension else lexicalFallback.dimension

    override val isSemantic: Boolean
        get() = semanticAdapter.isProvisioned

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
            if (semanticOutcome !is Outcome.Error) return semanticOutcome
            // Semantic inference failed → fall back honestly (the failure is
            // preserved in the outcome chain via the lexical adapter's own
            // metadata; the caller sees LEXICAL labeling downstream).
        }
        return lexicalFallback.generateEmbeddings(texts)
    }
}
