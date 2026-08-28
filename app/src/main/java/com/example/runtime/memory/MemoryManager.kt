package com.example.runtime.memory

import com.example.data.local.db.daos.MemoryDao
import com.example.data.local.db.entities.LongTermMemoryEntity
import com.example.domain.models.LongTermMemory
import com.example.domain.models.WorkflowExecutionResult
import com.example.runtime.events.EventBus
import com.example.runtime.rag.NativeOfflineEmbedder
import com.example.runtime.rag.VectorMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

class MemoryManager(
    private val memoryDao: MemoryDao,
    private val embedder: NativeOfflineEmbedder = NativeOfflineEmbedder(64)
) {
    suspend fun addLongTermMemory(
        projectId: Long,
        content: String,
        memoryType: String = "preference",
        importance: Float = 1.0f,
        confidence: Float = 1.0f,
        provenance: String? = null,
        sessionId: String? = null,
        taskId: String? = null,
        agentId: String? = null
    ): Long {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val embedding = embedder.embed(content)
        val embeddingJson = VectorMath.floatsToJson(embedding)

        val memoryId = memoryDao.insertMemory(
            LongTermMemoryEntity(
                projectId = projectId,
                content = content,
                embeddingJson = embeddingJson,
                embeddingDimension = embedding.size,
                importance = importance,
                memoryType = memoryType,
                status = "active",
                confidence = confidence,
                provenance = provenance,
                sessionId = sessionId,
                taskId = taskId,
                agentId = agentId,
                timestamp = now
            )
        )

        // Preference Supersession rule: supersede older active preferences with high similarity
        if (memoryType == "preference") {
            val existing = memoryDao.getMemoriesWithEmbeddings(projectId)
            for (oldMem in existing) {
                if (oldMem.id != memoryId && oldMem.memoryType == "preference" && oldMem.status == "active") {
                    val oldVec = VectorMath.jsonToFloats(oldMem.embeddingJson)
                    if (oldVec.size == embedding.size) {
                        val sim = VectorMath.cosineSimilarity(embedding, oldVec)
                        if (sim >= 0.85f) {
                            memoryDao.updateMemory(
                                oldMem.copy(
                                    status = "superseded",
                                    supersededBy = memoryId
                                )
                            )
                        }
                    }
                }
            }
        }

        EventBus.publishMemoryFormed(memoryType, content)
        return memoryId
    }

    suspend fun searchMemories(
        projectId: Long,
        query: String,
        topK: Int = 5,
        halfLifeDays: Float = 90f
    ): List<LongTermMemory> {
        val queryVec = embedder.embed(query)
        val allActive = memoryDao.getMemoriesWithEmbeddings(projectId)

        val scored = mutableListOf<Pair<LongTermMemoryEntity, Float>>()
        val currentTime = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (entity in allActive) {
            val vec = VectorMath.jsonToFloats(entity.embeddingJson)
            val baseSim = if (vec.size == queryVec.size && vec.isNotEmpty()) {
                VectorMath.cosineSimilarity(queryVec, vec)
            } else if (entity.content.contains(query, ignoreCase = true)) {
                0.5f
            } else {
                0.0f
            }

            if (baseSim > 0.1f) {
                // Calculate Recency Factor: decay exponentially by age
                val ageMillis = try {
                    val date = dateFormat.parse(entity.timestamp)
                    date?.let { currentTime - it.time } ?: 0L
                } catch (e: Exception) {
                    0L
                }
                val ageDays = (ageMillis / (1000.0 * 60 * 60 * 24)).toFloat().coerceAtLeast(0f)
                val recencyFactor = 0.5.pow((ageDays / halfLifeDays).toDouble()).toFloat()

                val effectiveScore = baseSim * entity.importance * entity.confidence * recencyFactor
                scored.add(entity to effectiveScore)
            }
        }

        return scored.sortedByDescending { it.second }.take(topK).map { (entity, score) ->
            LongTermMemory(
                id = entity.id,
                projectId = entity.projectId,
                content = entity.content,
                memoryType = entity.memoryType,
                status = entity.status,
                importance = entity.importance,
                confidence = entity.confidence,
                provenance = entity.provenance,
                timestamp = entity.timestamp,
                similarityScore = score
            )
        }
    }

    /**
     * Automatic Case Formation from Workflow Execution:
     * Extreme quality results (expectedValue >= 0.8 or expectedValue <= 0.3) are saved as Case memories.
     */
    suspend fun evaluateAndFormWorkflowCase(projectId: Long, result: WorkflowExecutionResult) {
        val ev = result.beliefExpectedValue
        if (ev >= 0.8f || ev <= 0.3f) {
            val confidence = abs(ev - 0.5f) * 2f
            val summary = "سجل حالة تنفيذ (Case): الهدف '${result.goal}' — النتيجة: ${result.status}، الجودة: ${result.quality}، قيمة الاعتقاد: ${String.format(Locale.US, "%.2f", ev)}"
            addLongTermMemory(
                projectId = projectId,
                content = summary,
                memoryType = "case",
                importance = 0.9f,
                confidence = confidence,
                provenance = "cbr_workflow_formation"
            )
        }
    }
}
