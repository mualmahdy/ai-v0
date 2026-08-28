package com.example.application.usecases

import com.example.domain.core.Outcome
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.memory.VectorStoreFailure
import com.example.domain.ports.memory.MemoryRepositoryPort

/**
 * High-level Use Case: Memory management and contextual RAG retrieval.
 */
class ManageMemoryUseCase(
    private val memoryRepository: MemoryRepositoryPort
) {

    suspend fun retrieveContext(query: String, maxResults: Int = 4): Outcome<List<ScoredMemoryRecord>, VectorStoreFailure> {
        return memoryRepository.retrieveMemories(query, topK = maxResults)
    }

    suspend fun recordInsight(entry: MemoryEntry): Outcome<Unit, VectorStoreFailure> {
        return memoryRepository.storeMemory(entry)
    }

    suspend fun getActiveMemories(): Outcome<List<MemoryEntry>, VectorStoreFailure> {
        return memoryRepository.getAllActiveMemories()
    }

    suspend fun deleteMemory(id: String): Outcome<Unit, VectorStoreFailure> {
        return memoryRepository.deleteMemory(id)
    }
}
