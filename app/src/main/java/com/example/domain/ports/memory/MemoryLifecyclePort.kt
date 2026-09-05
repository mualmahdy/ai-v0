package com.example.domain.ports.memory

import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.lifecycle.CognitiveMemoryType
import com.example.domain.core.memory.lifecycle.ForgettingPolicy
import com.example.domain.core.memory.lifecycle.MemoryConsolidationRequest
import com.example.domain.core.memory.lifecycle.MemoryNamespace
import com.example.domain.core.memory.lifecycle.RankedMemoryRecord

/**
 * Memory Lifecycle Port — the write/maintenance side of the memory system.
 *
 * `MemoryRepositoryPort` (pre-existing) only has write/retrieve/delete.
 * This port adds the cognitive lifecycle operations requested in the
 * audit: decay, consolidate, rank, forget, namespace.
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
     * Rank memories by the cognitive ranking function:
     *   finalRank = similarity * 0.5 + importance * 0.2 + recency * 0.15 + decay * 0.15
     *
     * Used by `MemoryRepositoryPort.retrieveMemories` once the lifecycle
     * service has evaluated decay scores.
     */
    suspend fun rank(
        memories: List<MemoryEntry>,
        queryEmbedding: FloatArray? = null,
        queryText: String = ""
    ): List<RankedMemoryRecord>

    /**
     * Apply the forgetting policy: archive memories below
     * `policy.archiveDecayThreshold`, delete memories archived longer
     * than `policy.deleteArchivedAfterMs` ago, and enforce the per-
     * workspace/agent/global caps (LRU eviction).
     */
    suspend fun forget(policy: ForgettingPolicy = ForgettingPolicy()): ForgetResult

    /**
     * Get or create a per-agent memory namespace within a workspace.
     */
    suspend fun ensureNamespace(workspaceId: String, agentId: String): MemoryNamespace

    /**
     * Store a memory in a specific namespace (workspace + agent scope).
     */
    suspend fun storeScoped(
        workspaceId: String,
        agentId: String?,
        type: CognitiveMemoryType,
        content: String,
        importance: Float = 1.0f,
        confidence: Float = 1.0f,
        tags: List<String> = emptyList()
    ): String

    /**
     * Retrieve memories scoped to a workspace + optional agent.
     * If `agentId` is null, returns workspace-shared + global memories.
     */
    suspend fun retrieveScoped(
        workspaceId: String,
        agentId: String? = null,
        query: String,
        topK: Int = 5,
        types: List<CognitiveMemoryType> = emptyList()
    ): List<RankedMemoryRecord>
}

data class ForgetResult(
    val archivedCount: Int,
    val deletedCount: Int,
    val lruEvictedCount: Int
)
