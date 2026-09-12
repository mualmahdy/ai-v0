package com.example.domain.core.memory

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ============================================================================
 * GAP-28 (Design Closure 2026) — the SINGLE vector-similarity kernel
 * ============================================================================
 *
 * Four divergent cosine implementations previously lived across the RAG /
 * memory subsystems (RagPipelineService, RagIntelligenceService,
 * MemoryLifecycleService, RoomVectorStoreAdapter) with INCONSISTENT
 * dimension-mismatch semantics (one silently truncated to min-length, three
 * returned 0) — the gap register's "duplicated authority" for vector
 * similarity. This object is now the one kernel every subsystem calls.
 *
 * DELIBERATELY EXEMPT: `domain.core.decision.CaseBase` keeps its own
 * ZERO-PADDING cosine — that behavior is a documented, test-pinned domain
 * fix (FIX DOM-P0-01) for legacy persisted feature vectors of shorter
 * length; unifying it here would break the pinned contract. The exemption
 * is recorded here so nobody "fixes" it back into duplication.
 *
 * Storage debt (documented, deferred): vectors persist as JSON text arrays
 * (~3-5x bloat vs BLOB) and the lexical fallback spaces are split (32-dim
 * in the RAG pipeline for persisted-chunk compatibility, 128-dim in the
 * memory subsystem) — both are recorded in docs/PRODUCT-DECISIONS.md and
 * must NOT be silently unified while old rows exist.
 */
object VectorMath {

    /**
     * Cosine similarity over equal-length vectors.
     *
     * Semantics (the contract every caller relies on):
     *  - dimension mismatch (a.size != b.size) → **0f** — vectors from
     *    different embedding spaces are NEVER partially compared (no
     *    min-length truncation: a truncated dot product is a fabricated
     *    score, not a similarity);
     *  - empty input → 0f;
     *  - zero vector (denominator <= 0) → 0f;
     *  - result coerced to [-1, 1].
     *
     * Note: RagPipelineService previously TRUNCATED mismatched vectors to
     * min length. The switch to this strict kernel is a deliberate,
     * documented semantic upgrade: mismatched vectors can only reach the
     * cosine there when their score is discarded by the vectorCompatible
     * gate anyway (metadata-lying corruption case), where 0 is more honest
     * than a truncated pseudo-score.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    /**
     * Lexical sparse vector: whitespace-tokenized term-frequency hashing
     * into [dimension] buckets, L2-normalized (so cosine == dot).
     *
     * This unifies the two previously byte-identical copies
     * (MemoryLifecycleService + RoomVectorStoreAdapter — 128-dim memory
     * space). The RAG pipeline's own 32-dim lexical generator is NOT this
     * function: it uses a different tokenizer and dimension for
     * persisted-chunk vector-space compatibility (see class KDoc).
     */
    fun lexicalSparseVector(text: String, dimension: Int = 128): EmbeddingVector {
        val values = FloatArray(dimension)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        for (word in words) {
            val hash = abs(word.hashCode()) % dimension
            values[hash] += 1.0f
        }
        var sumSquares = 0f
        for (v in values) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for (i in values.indices) values[i] /= norm
        }
        return EmbeddingVector(dimension = dimension, values = values)
    }
}
