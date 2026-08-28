package com.example.domain.ports.memory

import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata

/**
 * Standard Port for Vector Embedding Providers.
 *
 * Dedicated explicitly to true semantic embedding vectors.
 * Tokenizers/Lexical hashing must not masquerade as an EmbeddingProvider.
 */
interface EmbeddingProviderPort {
    val providerId: String
    val dimension: Int
    val metadata: SafeEmbeddingProviderMetadata

    /**
     * Generates true semantic embedding vectors for the given text inputs.
     */
    suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure>
}
