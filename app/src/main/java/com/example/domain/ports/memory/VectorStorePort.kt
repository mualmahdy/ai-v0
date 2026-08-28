package com.example.domain.ports.memory

import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.memory.VectorStoreFailure
import com.example.domain.core.memory.VectorStoreRecord

/**
 * Standard Port for Vector and Long-Term Memory Storage.
 */
interface VectorStorePort {
    /**
     * Inserts or updates a vector record.
     */
    suspend fun upsert(record: VectorStoreRecord): Outcome<Unit, VectorStoreFailure>

    /**
     * Searches for records similar to the query vector with cosine/dot-product similarity.
     */
    suspend fun querySimilar(
        queryVector: EmbeddingVector,
        topK: Int = 5,
        minScoreThreshold: Float = 0.65f
    ): Outcome<List<VectorStoreRecord>, VectorStoreFailure>

    /**
     * Deletes a vector record by ID.
     */
    suspend fun delete(id: String): Outcome<Unit, VectorStoreFailure>

    /**
     * Clears all records.
     */
    suspend fun clear(): Outcome<Unit, VectorStoreFailure>
}

/**
 * Standard Port for High-Level Memory Management (RAG & Provenance).
 */
interface MemoryRepositoryPort {
    /**
     * Stores a new memory entry.
     */
    suspend fun storeMemory(entry: MemoryEntry): Outcome<Unit, VectorStoreFailure>

    /**
     * Retrieves relevant memories with semantic or lexical fallback modes.
     */
    suspend fun retrieveMemories(
        query: String,
        topK: Int = 5,
        minConfidence: Float = 0.5f
    ): Outcome<List<ScoredMemoryRecord>, VectorStoreFailure>

    /**
     * Retrieves all active memories.
     */
    suspend fun getAllActiveMemories(): Outcome<List<MemoryEntry>, VectorStoreFailure>

    /**
     * Deletes a memory entry by ID.
     */
    suspend fun deleteMemory(id: String): Outcome<Unit, VectorStoreFailure>
}
