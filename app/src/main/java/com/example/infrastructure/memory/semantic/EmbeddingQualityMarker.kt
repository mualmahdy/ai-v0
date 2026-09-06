package com.example.infrastructure.memory.semantic

import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.ports.memory.EmbeddingProviderPort

/**
 * Honest marker for embedding providers (audit 2026 fix).
 *
 * The retrieval pipeline labels its mode (SEMANTIC vs LEXICAL_FALLBACK)
 * based on whether the embedding source actually produces semantic vectors.
 * A hash-based generator MUST be able to declare itself lexical so downstream
 * retrieval never labels hash similarity as "HYBRID semantic" — that was the
 * previous dishonesty.
 */
interface EmbeddingQualityMarker {
    /** TRUE only when vectors come from a trained semantic model. */
    val isSemantic: Boolean
}
