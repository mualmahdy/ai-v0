package com.example.domain.ports.memory

import com.example.domain.core.memory.lifecycle.MemoryConsolidationRequest
import com.example.domain.core.memory.lifecycle.MemoryNamespace

/**
 * Memory Lifecycle Port — the maintenance side of the memory system.
 *
 * `MemoryRepositoryPort` (pre-existing) owns write/retrieve/delete.
 * This port owns the production-called lifecycle operations: decay,
 * consolidate, namespace.
 *
 * (Design Closure 2026, ADR-7 fate — D-9: rank / forget / storeScoped /
 *  retrieveScoped were DELETED with their zero-caller implementations.
 *  The production surface is exactly what production calls.)
 */
interface MemoryLifecyclePort {

    /**
     * Apply decay to all memories whose `lastDecayEvaluatedAtEpochMs` is
     * older than `now - intervalMs`. Returns the number of memories
     * whose `decayScore` changed.
     */
    suspend fun applyDecay(now: Long = System.currentTimeMillis()): Int

    /**
     * Consolidate near-duplicate memories. The consolidator finds
     * semantic memories in the same namespace with similarity above
     * `similarityThreshold` and merges them into a single memory with
     * boosted confidence.
     */
    suspend fun consolidate(
        workspaceId: String? = null,
        agentId: String? = null,
        similarityThreshold: Float = 0.85f
    ): List<MemoryConsolidationRequest>

    /**
     * Get or create a per-agent memory namespace within a workspace.
     */
    suspend fun ensureNamespace(workspaceId: String, agentId: String): MemoryNamespace
}
