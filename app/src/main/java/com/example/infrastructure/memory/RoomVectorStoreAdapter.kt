package com.example.infrastructure.memory

import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.memory.VectorStoreFailure
import com.example.domain.core.memory.VectorStoreRecord
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.memory.MemoryRepositoryPort
import com.example.domain.ports.memory.VectorStorePort
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.entities.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.sqrt

/**
 * Clean Infrastructure Adapter for Room-backed Vector Store and Memory Repository.
 *
 * Implements real Cosine Similarity mathematical calculations across normalized embedding vectors.
 */
class RoomVectorStoreAdapter(
    private val memoryDao: MemoryDao,
    private val embeddingProvider: EmbeddingProviderPort? = null
) : VectorStorePort, MemoryRepositoryPort {

    override suspend fun upsert(record: VectorStoreRecord): Outcome<Unit, VectorStoreFailure> = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            record.vector.values.forEach { jsonArray.put(it.toDouble()) }

            val entity = MemoryEntity(
                id = record.id,
                text = record.payloadText,
                vectorDimension = record.vector.dimension,
                vectorJson = jsonArray.toString(),
                source = record.metadata["source"] ?: "USER_INPUT",
                confidence = record.metadata["confidence"]?.toFloatOrNull() ?: 1.0f,
                createdAtEpochMs = System.currentTimeMillis(),
                lastAccessedEpochMs = System.currentTimeMillis()
            )
            memoryDao.insertMemory(entity)
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error(
                VectorStoreFailure.StorageWriteError("فشل حفظ السجل في الذاكرة: ${e.localizedMessage}")
            )
        }
    }

    override suspend fun querySimilar(
        queryVector: EmbeddingVector,
        topK: Int,
        minScoreThreshold: Float
    ): Outcome<List<VectorStoreRecord>, VectorStoreFailure> = withContext(Dispatchers.IO) {
        try {
            val entities = memoryDao.getAllActiveMemories()
            if (entities.isEmpty()) {
                return@withContext Outcome.Success(emptyList())
            }

            val scored = mutableListOf<Pair<VectorStoreRecord, Float>>()

            for (entity in entities) {
                val storedVector = parseVectorJson(entity.vectorJson, entity.vectorDimension)
                val similarity = calculateCosineSimilarity(queryVector.values, storedVector.values)

                if (similarity >= minScoreThreshold) {
                    val record = VectorStoreRecord(
                        id = entity.id,
                        vector = storedVector,
                        payloadText = entity.text,
                        metadata = mapOf(
                            "source" to entity.source,
                            "confidence" to entity.confidence.toString()
                        )
                    )
                    scored.add(record to similarity)
                }
            }

            val topResults = scored.sortedByDescending { it.second }.take(topK).map { it.first }
            Outcome.Success(topResults)
        } catch (e: Exception) {
            Outcome.Error(
                VectorStoreFailure.StorageReadError("خطأ أثناء استعلام المتجهات الدلالية: ${e.localizedMessage}")
            )
        }
    }

    override suspend fun delete(id: String): Outcome<Unit, VectorStoreFailure> = withContext(Dispatchers.IO) {
        try {
            memoryDao.deleteMemory(id)
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error(VectorStoreFailure.StorageWriteError("فشل حذف السجل: ${e.localizedMessage}"))
        }
    }

    override suspend fun clear(): Outcome<Unit, VectorStoreFailure> = withContext(Dispatchers.IO) {
        try {
            memoryDao.clearAll()
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error(VectorStoreFailure.StorageWriteError("فشل تفريغ الذاكرة: ${e.localizedMessage}"))
        }
    }

    // --- MemoryRepositoryPort Implementation ---

    override suspend fun storeMemory(entry: MemoryEntry): Outcome<Unit, VectorStoreFailure> = withContext(Dispatchers.IO) {
        val vector = if (embeddingProvider != null) {
            when (val embedResult = embeddingProvider.generateEmbeddings(listOf(entry.content))) {
                is Outcome.Success -> embedResult.value.firstOrNull() ?: createLexicalSparseVector(entry.content)
                else -> createLexicalSparseVector(entry.content)
            }
        } else {
            createLexicalSparseVector(entry.content)
        }

        val record = VectorStoreRecord(
            id = entry.id,
            vector = vector,
            payloadText = entry.content,
            metadata = mapOf(
                "source" to (entry.provenance.sourceSessionId ?: "USER_INPUT"),
                "confidence" to entry.confidence.toString()
            )
        )
        upsert(record)
    }

    override suspend fun retrieveMemories(
        query: String,
        topK: Int,
        minConfidence: Float
    ): Outcome<List<ScoredMemoryRecord>, VectorStoreFailure> = withContext(Dispatchers.IO) {
        try {
            val entities = memoryDao.getAllActiveMemories()
            if (entities.isEmpty()) return@withContext Outcome.Success(emptyList())

            val queryVector = if (embeddingProvider != null) {
                when (val embResult = embeddingProvider.generateEmbeddings(listOf(query))) {
                    is Outcome.Success -> embResult.value.firstOrNull() ?: createLexicalSparseVector(query)
                    else -> createLexicalSparseVector(query)
                }
            } else {
                createLexicalSparseVector(query)
            }

            val scoredRecords = mutableListOf<ScoredMemoryRecord>()
            for (entity in entities) {
                val storedVector = parseVectorJson(entity.vectorJson, entity.vectorDimension)
                val similarity = calculateCosineSimilarity(queryVector.values, storedVector.values)

                if (similarity >= 0.2f && entity.confidence >= minConfidence) {
                    val entry = MemoryEntry(
                        id = entity.id,
                        content = entity.text,
                        type = MemoryType.FACTUAL_INSIGHT,
                        confidence = entity.confidence,
                        provenance = MemoryProvenance(sourceSessionId = entity.source, createdAtTimestampMs = entity.createdAtEpochMs),
                        isActive = true
                    )
                    scoredRecords.add(
                        ScoredMemoryRecord(
                            entry = entry,
                            similarityScore = similarity,
                            retrievalMode = if (embeddingProvider != null) RetrievalMode.SEMANTIC else RetrievalMode.LEXICAL_FALLBACK
                        )
                    )
                    memoryDao.touchMemory(entity.id, System.currentTimeMillis())
                }
            }

            val sorted = scoredRecords.sortedByDescending { it.similarityScore }.take(topK)
            Outcome.Success(sorted)
        } catch (e: Exception) {
            Outcome.Error(VectorStoreFailure.StorageReadError("فشل استرجاع الذاكرة: ${e.localizedMessage}"))
        }
    }

    override suspend fun getAllActiveMemories(): Outcome<List<MemoryEntry>, VectorStoreFailure> = withContext(Dispatchers.IO) {
        try {
            val list = memoryDao.getAllActiveMemories().map { entity ->
                MemoryEntry(
                    id = entity.id,
                    content = entity.text,
                    type = MemoryType.FACTUAL_INSIGHT,
                    confidence = entity.confidence,
                    provenance = MemoryProvenance(sourceSessionId = entity.source, createdAtTimestampMs = entity.createdAtEpochMs),
                    isActive = true
                )
            }
            Outcome.Success(list)
        } catch (e: Exception) {
            Outcome.Error(VectorStoreFailure.StorageReadError("فشل جلب الذاكرة: ${e.localizedMessage}"))
        }
    }

    override suspend fun deleteMemory(id: String): Outcome<Unit, VectorStoreFailure> = delete(id)

    // --- Math & Vector Parsing Helpers ---

    private fun parseVectorJson(json: String, dimension: Int): EmbeddingVector {
        val array = JSONArray(json)
        val floats = FloatArray(array.length()) { i -> array.getDouble(i).toFloat() }
        return EmbeddingVector(dimension = dimension, values = floats)
    }

    private fun calculateCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) (dotProduct / denom).coerceIn(-1.0f, 1.0f) else 0.0f
    }

    private fun createLexicalSparseVector(text: String, dimension: Int = 128): EmbeddingVector {
        val values = FloatArray(dimension)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        for (word in words) {
            val hash = kotlin.math.abs(word.hashCode()) % dimension
            values[hash] += 1.0f
        }
        var sumSquares = 0f
        for (v in values) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for (i in values.indices) values[i] /= norm
        }
        return EmbeddingVector(dimension = dimension, values = values)
    }
}
