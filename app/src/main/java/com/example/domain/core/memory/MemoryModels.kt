package com.example.domain.core.memory

/**
 * Mode through which a memory or document was retrieved.
 */
enum class RetrievalMode {
    SEMANTIC,
    LEXICAL_FALLBACK,
    HYBRID
}

/**
 * Category of persistent memory entry.
 */
enum class MemoryType {
    PREFERENCE,
    FACTUAL_INSIGHT,
    CASE_EXAMPLE,
    CONVERSATION_SUMMARY
}

/**
 * Provenance tracking where a piece of memory originated.
 */
data class MemoryProvenance(
    val sourceSessionId: String? = null,
    val sourceTaskId: String? = null,
    val sourceDocumentId: String? = null,
    val createdAtTimestampMs: Long = System.currentTimeMillis()
)

/**
 * True mathematical embedding vector (Float array wrapper).
 */
data class EmbeddingVector(
    val values: FloatArray,
    val dimension: Int = values.size
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingVector
        if (dimension != other.dimension) return false
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + dimension
        return result
    }
}

/**
 * Single long-term memory entity.
 */
data class MemoryEntry(
    val id: String,
    val content: String,
    val type: MemoryType,
    val importance: Float = 1.0f,
    val confidence: Float = 1.0f,
    val provenance: MemoryProvenance = MemoryProvenance(),
    val vector: EmbeddingVector? = null,
    val isActive: Boolean = true
)

/**
 * Memory record retrieved from search with explicit similarity and retrieval mode.
 */
data class ScoredMemoryRecord(
    val entry: MemoryEntry,
    val similarityScore: Float,
    val retrievalMode: RetrievalMode,
    val degradedReason: String? = null
)

/**
 * Vector store record container.
 */
data class VectorStoreRecord(
    val id: String,
    val vector: EmbeddingVector,
    val payloadText: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Safe metadata for embedding providers (no secrets).
 */
data class SafeEmbeddingProviderMetadata(
    val id: String,
    val name: String,
    val providerType: String,
    val dimension: Int,
    val isLocal: Boolean,
    val isEnabled: Boolean
)

/**
 * Failures during embedding generation.
 */
sealed interface EmbeddingFailure {
    data class EmbeddingUnavailable(val providerId: String, val reason: String) : EmbeddingFailure
    data class ModelNotFound(val modelName: String) : EmbeddingFailure
    data class DimensionMismatch(val expected: Int, val actual: Int) : EmbeddingFailure
    data class QuotaExceeded(val message: String) : EmbeddingFailure
}

/**
 * Failures during vector storage and similarity lookup.
 */
sealed interface VectorStoreFailure {
    data class StorageReadError(val message: String) : VectorStoreFailure
    data class StorageWriteError(val message: String) : VectorStoreFailure
    data class RecordNotFound(val id: String) : VectorStoreFailure
    data class DimensionIncompatible(val expected: Int, val received: Int) : VectorStoreFailure
}
