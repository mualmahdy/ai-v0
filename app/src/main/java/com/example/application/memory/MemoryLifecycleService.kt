package com.example.application.memory

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.lifecycle.CognitiveMemoryType
import com.example.domain.core.memory.lifecycle.MemoryConsolidationRequest
import com.example.domain.core.memory.lifecycle.MemoryDecayPolicy
import com.example.domain.core.memory.lifecycle.MemoryNamespace
import com.example.domain.core.memory.lifecycle.MemoryScope
import com.example.domain.ports.memory.MemoryLifecyclePort
import com.example.domain.ports.memory.MemoryRepositoryPort
import com.example.infrastructure.persistence.dao.AgentMemoryNamespaceDao
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.entities.AgentMemoryNamespaceEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import kotlin.math.abs

/**
 * ============================================================================
 * MemoryLifecycleService — Phase 5 Memory Intelligence (P0 remediation)
 * ============================================================================
 *
 * Implements the cognitive memory lifecycle requested in the audit:
 *
 *   write → consolidate → decay
 *
 * (Design Closure 2026, ADR-7 fate — D-9: the rank / forget / storeScoped /
 *  retrieveScoped APIs were DELETED. All four had ZERO production callers
 *  since inception — rank was reachable only from the uncalled
 *  retrieveScoped and carried an N+1 (one getMemoryById round-trip per
 *  memory); forget's semantics overlap the production-called applyDecay +
 *  consolidate; the scoped store/retrieve pair never gained a consumer.
 *  The production surface is exactly what production calls: decay +
 *  consolidate from bootstrap, namespaces via AgentLifecycleService.)
 *
 *   1. Decay: each memory type has a half-life; `decayScore` halves every
 *      half-life period. Importance slows decay; access boosts decay back
 *      toward 1.0.
 *   2. Consolidation: detects near-duplicate semantic memories (cosine
 *      similarity ≥ threshold within the same workspace scope — GAP-12)
 *      and merges them into a single memory with boosted confidence.
 *   3. Namespacing: per-(workspace, agent) memory scopes so different
 *      agents don't share private context.
 */
class MemoryLifecycleService(
    private val memoryDao: MemoryDao,
    private val namespaceDao: AgentMemoryNamespaceDao
) : MemoryLifecyclePort {

    override suspend fun applyDecay(now: Long): Int = withContext(Dispatchers.IO) {
        // Process memories that haven't been evaluated in the last hour.
        val cutoff = now - 60L * 60 * 1000
        val due = memoryDao.getMemoriesDueForDecay(cutoff)
        if (due.isEmpty()) return@withContext 0

        var changed = 0
        for (entity in due) {
            val type = CognitiveMemoryType.fromStorageCode(entity.memoryType)
            val policy = MemoryDecayPolicy.forType(type)
            val elapsed = now - entity.lastDecayEvaluatedAtEpochMs
            val newDecay = computeDecayedScore(
                currentScore = entity.decayScore,
                elapsedMs = elapsed,
                policy = policy,
                importance = entity.importance
            )
            if (abs(newDecay - entity.decayScore) > 0.001f) {
                memoryDao.updateDecayScore(entity.id, newDecay, now)
                changed++
            }
        }
        changed
    }

    /**
     * Exponential decay with importance weighting:
     *
     *   newDecay = currentScore * 2^(-elapsed / effectiveHalfLife)
     *
     * where effectiveHalfLife = baseHalfLife * (1 + importance * weight).
     * Importance 1.0 doubles the half-life; importance 0 keeps it as-is.
     * Floor at `policy.minDecayScore` so memories never fully vanish
     * (the consolidator/forgetting policy handles actual removal).
     */
    private fun computeDecayedScore(
        currentScore: Float,
        elapsedMs: Long,
        policy: MemoryDecayPolicy,
        importance: Float
    ): Float {
        val effectiveHalfLife = (policy.halfLifeMs * (1.0 + importance * policy.importanceWeight)).toFloat()
        if (effectiveHalfLife <= 0f) return policy.minDecayScore
        val decayFactor = Math.pow(2.0, -elapsedMs.toDouble() / effectiveHalfLife.toDouble()).toFloat()
        val newScore = currentScore * decayFactor
        return maxOf(newScore, policy.minDecayScore)
    }

    override suspend fun consolidate(
        workspaceId: String?,
        agentId: String?,
        similarityThreshold: Float
    ): List<MemoryConsolidationRequest> = withContext(Dispatchers.IO) {
        val memories = if (workspaceId != null && agentId != null) {
            memoryDao.getActiveForWorkspaceAndAgent(workspaceId, agentId)
        } else if (workspaceId != null) {
            memoryDao.getActiveForWorkspace(workspaceId)
        } else if (agentId != null) {
            // Global scope narrowed by agent — every row here has
            // workspaceId IS NULL, so no workspace boundary is crossed.
            memoryDao.getActiveForWorkspaceAndAgent(null, agentId)
        } else {
            // GAP-12 (Design Closure 2026): workspaceId == null means the
            // GLOBAL scope (workspaceId IS NULL) — NOT "all memories". The
            // previous getAllActiveMemories() loaded every workspace's rows,
            // letting a global consolidation absorb workspace-scoped
            // memories (the survivor took the first row's workspaceId —
            // cross-workscope corruption). Consolidation NEVER crosses a
            // workspace boundary, in any direction.
            memoryDao.getActiveForWorkspace(null)
        }

        // Only consolidate SEMANTIC / FACTUAL_INSIGHT / WORKSPACE types —
        // EPISODIC and CASE_EXAMPLE memories are intentionally distinct.
        val consolidatable = memories.filter {
            val type = CognitiveMemoryType.fromStorageCode(it.memoryType)
            type in setOf(
                CognitiveMemoryType.SEMANTIC,
                CognitiveMemoryType.FACTUAL_INSIGHT,
                CognitiveMemoryType.WORKSPACE,
                CognitiveMemoryType.USER_PREFERENCE
            )
        }

        val requests = mutableListOf<MemoryConsolidationRequest>()
        val consumed = mutableSetOf<String>()

        for (i in consolidatable.indices) {
            val a = consolidatable[i]
            if (a.id in consumed) continue
            // GAP-28: honest per-row decode — a corrupt vector row is
            // SKIPPED (counted), never allowed to abort the whole
            // consolidation pass (previously parseVector threw and the
            // bootstrap runCatching swallowed the entire sweep).
            val vecA = parseVector(a.vectorJson, a.vectorDimension) ?: continue
            val cluster = mutableListOf(a.id)

            for (j in (i + 1) until consolidatable.size) {
                val b = consolidatable[j]
                if (b.id in consumed) continue
                val vecB = parseVector(b.vectorJson, b.vectorDimension) ?: continue
                val sim = com.example.domain.core.memory.VectorMath.cosine(vecA.values, vecB.values)
                if (sim >= similarityThreshold) {
                    cluster.add(b.id)
                    consumed.add(b.id)
                }
            }

            if (cluster.size > 1) {
                consumed.add(a.id)
                val mergedContent = consolidatable.filter { it.id in cluster }.joinToString(" | ") { it.text }
                val mergedConfidence = (consolidatable.filter { it.id in cluster }.maxOf { it.confidence } + 0.1f).coerceAtMost(1.0f)
                val targetType = CognitiveMemoryType.fromStorageCode(a.memoryType)
                requests.add(
                    MemoryConsolidationRequest(
                        sourceMemoryIds = cluster,
                        targetContent = mergedContent,
                        targetType = targetType,
                        confidenceBoost = 0.1f,
                        reason = "تجميع ذكريات متماثلة دلالياً (${cluster.size} ذاكرة)"
                    )
                )

                // Apply the consolidation: keep the first memory, delete the rest.
                val survivorId = a.id
                memoryDao.mergeContent(survivorId, mergedContent, mergedConfidence, targetType.storageCode)
                for (id in cluster) {
                    if (id != survivorId) memoryDao.archive(id)
                }
            }
        }

        requests
    }

    override suspend fun ensureNamespace(workspaceId: String, agentId: String): MemoryNamespace = withContext(Dispatchers.IO) {
        val existing = namespaceDao.forAgentInWorkspace(agentId, workspaceId)
        if (existing != null) {
            return@withContext MemoryNamespace(
                namespaceId = existing.namespaceId,
                workspaceId = existing.workspaceId,
                agentId = existing.agentId,
                scope = MemoryScope.valueOf(existing.memoryScope)
            )
        }
        val namespaceId = UUID.randomUUID().toString()
        namespaceDao.upsert(
            AgentMemoryNamespaceEntity(
                namespaceId = namespaceId,
                workspaceId = workspaceId,
                agentId = agentId,
                memoryScope = MemoryScope.PRIVATE.name,
                createdAtEpochMs = System.currentTimeMillis(),
                isActive = true
            )
        )
        MemoryNamespace(namespaceId, workspaceId, agentId, MemoryScope.PRIVATE)
    }

    // --- Vector math helpers (GAP-28: the cosine + lexical builders were
    // duplicated from RoomVectorStoreAdapter — both now delegate to the ONE
    // shared kernel, com.example.domain.core.memory.VectorMath) ---

    /**
     * GAP-28/GAP-13: honest per-row decode — returns null (counted + logged)
     * instead of throwing, so one corrupt row skips itself rather than
     * aborting the caller's whole pass.
     */
    private val vectorDecodeFailures = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var lastVectorDecodeFailure: String? = null
        private set

    private fun parseVector(json: String, dimension: Int): EmbeddingVector? = try {
        val array = JSONArray(json)
        val floats = FloatArray(array.length()) { i -> array.getDouble(i).toFloat() }
        if (floats.size != dimension) {
            vectorDecodeFailures.incrementAndGet()
            lastVectorDecodeFailure =
                "VECTOR_DIMENSION_MISMATCH: expected=$dimension actual=${floats.size}"
            System.err.println("MEMORY-LIFECYCLE $lastVectorDecodeFailure")
            null
        } else {
            EmbeddingVector(dimension = dimension, values = floats)
        }
    } catch (e: Exception) {
        vectorDecodeFailures.incrementAndGet()
        lastVectorDecodeFailure =
            "VECTOR_DECODE_FAILED: ${e::class.simpleName}: ${e.message?.take(120)}"
        System.err.println("MEMORY-LIFECYCLE $lastVectorDecodeFailure")
        null
    }
}
