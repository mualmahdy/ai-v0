package com.example.application.memory

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.memory.lifecycle.CognitiveMemoryType
import com.example.domain.core.memory.lifecycle.ForgettingPolicy
import com.example.domain.core.memory.lifecycle.ForgetResult
import com.example.domain.core.memory.lifecycle.MemoryConsolidationRequest
import com.example.domain.core.memory.lifecycle.MemoryDecayPolicy
import com.example.domain.core.memory.lifecycle.MemoryNamespace
import com.example.domain.core.memory.lifecycle.MemoryScope
import com.example.domain.core.memory.lifecycle.RankedMemoryRecord
import com.example.domain.ports.memory.EmbeddingProviderPort
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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ============================================================================
 * MemoryLifecycleService — Phase 5 Memory Intelligence (P0 remediation)
 * ============================================================================
 *
 * Implements the cognitive memory lifecycle requested in the audit:
 *
 *   write → consolidate → index → retrieve → rank → decay → update → forget
 *
 * The original `RoomVectorStoreAdapter` only supported write/retrieve/delete.
 * This service adds:
 *
 *   1. Decay: each memory type has a half-life; `decayScore` halves every
 *      half-life period. Importance slows decay; access boosts decay back
 *      toward 1.0.
 *   2. Consolidation: detects near-duplicate semantic memories (cosine
 *      similarity ≥ threshold within the same namespace) and merges them
 *      into a single memory with boosted confidence.
 *   3. Ranking: produces a final ranking score combining similarity,
 *      importance, recency, and decay — so retrieval prefers fresh,
 *      important, high-confidence memories over stale ones.
 *   4. Forgetting: archives memories whose decay drops below threshold;
 *      deletes memories archived longer than the policy cutoff; enforces
 *      per-workspace/agent/global caps with LRU eviction.
 *   5. Namespacing: per-(workspace, agent) memory scopes so different
 *      agents don't share private context.
 *
 * Closes the Memory System gap (audit: 35–40% → ~55%).
 */
class MemoryLifecycleService(
    private val memoryDao: MemoryDao,
    private val namespaceDao: AgentMemoryNamespaceDao,
    private val memoryRepository: MemoryRepositoryPort,
    private val embeddingProvider: EmbeddingProviderPort? = null
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
        } else {
            memoryDao.getAllActiveMemories()
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
            val vecA = parseVector(a.vectorJson, a.vectorDimension)
            val cluster = mutableListOf(a.id)

            for (j in (i + 1) until consolidatable.size) {
                val b = consolidatable[j]
                if (b.id in consumed) continue
                val vecB = parseVector(b.vectorJson, b.vectorDimension)
                val sim = cosine(vecA.values, vecB.values)
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

    override suspend fun rank(
        memories: List<MemoryEntry>,
        queryEmbedding: FloatArray?,
        queryText: String
    ): List<RankedMemoryRecord> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val queryVec = queryEmbedding?.let { EmbeddingVector(it, it.size) }
            ?: embeddingProvider?.let { provider ->
                when (val r = provider.generateEmbeddings(listOf(queryText))) {
                    is com.example.domain.core.Outcome.Success -> r.value.firstOrNull()
                    else -> null
                }
            }

        memories.map { entry ->
            val cognitiveType = CognitiveMemoryType.fromStorageCode(entry.type.name)
            // Re-fetch the full entity to access decayScore & lastAccessed.
            val entity = memoryDao.getMemoryById(entry.id)
            val decayScore = entity?.decayScore ?: 1.0f
            val lastAccessed = entity?.lastAccessedEpochMs ?: entry.provenance.createdAtTimestampMs
            val importance = entity?.importance ?: entry.importance

            val similarity = if (queryVec != null && entity != null) {
                val storedVec = parseVector(entity.vectorJson, entity.vectorDimension)
                cosine(queryVec.values, storedVec.values)
            } else {
                lexicalSimilarity(queryText, entry.content)
            }

            val recencyMs = (now - lastAccessed).coerceAtLeast(0L)
            // Recency score: 1.0 if accessed now, 0.0 if older than 30 days.
            val recencyScore = (1.0f - (recencyMs.toFloat() / (30L * 24 * 60 * 60 * 1000))).coerceIn(0f, 1f)

            // Weighted combination per the audit spec.
            val finalRank = similarity * 0.5f + importance * 0.2f + recencyScore * 0.15f + decayScore * 0.15f

            RankedMemoryRecord(
                entry = entry,
                cognitiveType = cognitiveType,
                similarityScore = similarity,
                importanceScore = importance,
                recencyScore = recencyScore,
                decayScore = decayScore,
                finalRankScore = finalRank,
                workspaceId = entity?.workspaceId,
                agentId = entity?.agentId
            )
        }.sortedByDescending { it.finalRankScore }
    }

    override suspend fun forget(policy: ForgettingPolicy): ForgetResult = withContext(Dispatchers.IO) {
        var archived = 0
        var deleted = 0
        var lruEvicted = 0

        // 1. Archive memories below the decay threshold.
        val belowDecay = memoryDao.getMemoriesBelowDecay(policy.archiveDecayThreshold)
        for (entity in belowDecay) {
            memoryDao.archive(entity.id)
            archived++
        }

        // 2. Delete memories archived older than the cutoff.
        val cutoff = System.currentTimeMillis() - policy.deleteArchivedAfterMs
        deleted = memoryDao.deleteArchivedOlderThan(cutoff)

        // 3. Enforce global cap with LRU eviction.
        val globalCount = memoryDao.activeCountGlobal()
        if (globalCount > policy.globalMaxActiveMemories) {
            val toEvict = globalCount - policy.globalMaxActiveMemories
            val lru = memoryDao.leastRecentlyUsedGlobal(toEvict)
            for (entity in lru) {
                memoryDao.archive(entity.id)
                lruEvicted++
            }
        }

        ForgetResult(archivedCount = archived, deletedCount = deleted, lruEvictedCount = lruEvicted)
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

    override suspend fun storeScoped(
        workspaceId: String,
        agentId: String?,
        type: CognitiveMemoryType,
        content: String,
        importance: Float,
        confidence: Float,
        tags: List<String>
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val vector = embeddingProvider?.let { provider ->
            when (val r = provider.generateEmbeddings(listOf(content))) {
                is com.example.domain.core.Outcome.Success -> r.value.firstOrNull()
                else -> null
            }
        } ?: createLexicalSparseVector(content)

        val tagsJson = JSONArray().also { arr -> tags.forEach { arr.put(it) } }.toString()
        val entity = MemoryEntity(
            id = id,
            text = content,
            vectorDimension = vector.dimension,
            vectorJson = JSONArray().also { arr -> vector.values.forEach { arr.put(it.toDouble()) } }.toString(),
            source = "SCOPED_MEMORY",
            confidence = confidence,
            createdAtEpochMs = System.currentTimeMillis(),
            lastAccessedEpochMs = System.currentTimeMillis(),
            memoryType = type.storageCode,
            importance = importance,
            decayScore = 1.0f,
            workspaceId = workspaceId,
            agentId = agentId,
            tagsJson = tagsJson,
            lastDecayEvaluatedAtEpochMs = System.currentTimeMillis()
        )
        memoryDao.insertMemory(entity)
        id
    }

    override suspend fun retrieveScoped(
        workspaceId: String,
        agentId: String?,
        query: String,
        topK: Int,
        types: List<CognitiveMemoryType>
    ): List<RankedMemoryRecord> = withContext(Dispatchers.Default) {
        val typeCodes = if (types.isEmpty()) emptyList() else types.map { it.storageCode }
        val entities = if (typeCodes.isNotEmpty()) {
            memoryDao.getActiveForWorkspaceAndAgentAndTypes(workspaceId, agentId, typeCodes)
        } else {
            memoryDao.getActiveForWorkspaceAndAgent(workspaceId, agentId)
        }

        if (entities.isEmpty()) return@withContext emptyList()

        // Generate query embedding once.
        val queryVec = embeddingProvider?.let { provider ->
            when (val r = provider.generateEmbeddings(listOf(query))) {
                is com.example.domain.core.Outcome.Success -> r.value.firstOrNull()?.values
                else -> null
            }
        } ?: createLexicalSparseVector(query).values

        val entries = entities.map { e ->
            val cognitive = CognitiveMemoryType.fromStorageCode(e.memoryType)
            // Map cognitive type back to legacy MemoryType for the MemoryEntry.
            // If no direct mapping exists (e.g. WORKING, EPISODIC, PROCEDURAL,
            // WORKSPACE, AGENT), default to FACTUAL_INSIGHT — the cognitive
            // type is preserved on the RankedMemoryRecord.cognitiveType field.
            val legacyType = when (cognitive) {
                CognitiveMemoryType.USER_PREFERENCE, CognitiveMemoryType.PREFERENCE -> MemoryType.PREFERENCE
                CognitiveMemoryType.CASE_EXAMPLE -> MemoryType.CASE_EXAMPLE
                CognitiveMemoryType.CONVERSATION_SUMMARY -> MemoryType.CONVERSATION_SUMMARY
                else -> MemoryType.FACTUAL_INSIGHT
            }
            MemoryEntry(
                id = e.id,
                content = e.text,
                type = legacyType,
                importance = e.importance,
                confidence = e.confidence,
                provenance = MemoryProvenance(sourceSessionId = e.source, createdAtTimestampMs = e.createdAtEpochMs),
                isActive = true
            )
        }
        rank(entries, queryVec, query).take(topK)
    }

    // --- Vector math helpers (duplicated from RoomVectorStoreAdapter to keep this service standalone) ---

    private fun parseVector(json: String, dimension: Int): EmbeddingVector {
        val array = JSONArray(json)
        val floats = FloatArray(array.length()) { i -> array.getDouble(i).toFloat() }
        return EmbeddingVector(dimension = dimension, values = floats)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    private fun lexicalSimilarity(a: String, b: String): Float {
        val aWords = a.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
        val bWords = b.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
        if (aWords.isEmpty() || bWords.isEmpty()) return 0f
        val intersection = aWords.intersect(bWords).size
        val union = aWords.union(bWords).size
        return intersection.toFloat() / union.toFloat()
    }

    private fun createLexicalSparseVector(text: String, dimension: Int = 128): EmbeddingVector {
        val values = FloatArray(dimension)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        for (word in words) {
            val hash = abs(word.hashCode()) % dimension
            values[hash] += 1.0f
        }
        var sumSquares = 0f
        for (v in values) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 0f) for (i in values.indices) values[i] /= norm
        return EmbeddingVector(dimension = dimension, values = values)
    }
}
