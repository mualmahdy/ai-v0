package com.example.infrastructure.memory

import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.ports.memory.EmbeddingProviderPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Built-in Local High-Dimensional Deterministic Semantic Embedding Adapter.
 * Generates 128-dimensional normalized dense vectors directly on-device with zero external API calls.
 * Ensures the RAG pipeline always has a real, functional embedding provider.
 */
class LocalDeterministicEmbeddingAdapter(
    override val providerId: String = "local_embedding_engine",
    override val dimension: Int = 128
) : EmbeddingProviderPort {

    override val metadata: SafeEmbeddingProviderMetadata
        get() = SafeEmbeddingProviderMetadata(
            id = providerId,
            name = "Built-in Local Embedding Engine",
            providerType = "LOCAL_ON_DEVICE_EMBEDDING",
            dimension = dimension,
            isLocal = true,
            isEnabled = true
        )

    override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) {
            return@withContext Outcome.Success(emptyList())
        }

        val startTime = System.currentTimeMillis()
        try {
            val vectors = texts.map { text ->
                computeDenseVector(text, dimension)
            }
            val duration = System.currentTimeMillis() - startTime
            Outcome.Success(
                value = vectors,
                metadata = OutcomeMetadata(durationMs = duration, providerId = providerId)
            )
        } catch (e: Exception) {
            Outcome.Error(
                failure = EmbeddingFailure.EmbeddingUnavailable(providerId, "فشل توليد التضمينات الدلالية محلياً: ${e.localizedMessage}"),
                diagnosticMessage = e.message ?: "Local embedding error"
            )
        }
    }


    private fun computeDenseVector(text: String, dim: Int): EmbeddingVector {
        val vector = FloatArray(dim) { 0.0f }
        val cleanText = text.lowercase().trim()
        if (cleanText.isEmpty()) {
            return EmbeddingVector(dimension = dim, values = vector)
        }

        // 1. Word token hashing with position-weighted frequency
        val words = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        words.forEachIndexed { index, word ->
            val posWeight = 1.0f / (1.0f + index * 0.05f)
            val h1 = (word.hashCode() and 0x7FFFFFFF) % dim
            val h2 = ((word.hashCode() * 31 + 17) and 0x7FFFFFFF) % dim
            vector[h1] += 1.5f * posWeight
            vector[h2] += 0.8f * posWeight

            // Character n-grams (subwords) for morphological capture
            if (word.length >= 3) {
                for (i in 0..word.length - 3) {
                    val tri = word.substring(i, i + 3)
                    val hTri = (tri.hashCode() and 0x7FFFFFFF) % dim
                    vector[hTri] += 0.5f * posWeight
                }
            }
        }

        // 2. L2-Normalize vector so cosine similarity equals dot product
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares.toDouble()).toFloat()
        if (norm > 1e-6f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return EmbeddingVector(dimension = dim, values = vector)
    }
}
