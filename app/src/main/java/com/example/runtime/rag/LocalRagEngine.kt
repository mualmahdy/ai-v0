package com.example.runtime.rag

import com.example.data.local.db.daos.KnowledgeDao
import com.example.data.local.db.entities.DocumentChunkEntity
import com.example.data.local.db.entities.DocumentEntity
import com.example.data.local.db.entities.KnowledgeCollectionEntity
import org.json.JSONArray
import java.util.regex.Pattern
import kotlin.math.sqrt

object VectorMath {
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        val denom = sqrt(norm1) * sqrt(norm2)
        if (denom == 0f) return 0f
        return dot / denom
    }

    fun floatsToJson(floats: FloatArray): String {
        val array = JSONArray()
        for (f in floats) {
            array.put(f.toDouble())
        }
        return array.toString()
    }

    fun jsonToFloats(json: String?): FloatArray {
        if (json.isNullOrEmpty()) return FloatArray(0)
        return try {
            val array = JSONArray(json)
            val result = FloatArray(array.length())
            for (i in 0 until array.length()) {
                result[i] = array.getDouble(i).toFloat()
            }
            result
        } catch (e: Exception) {
            FloatArray(0)
        }
    }
}

class NativeOfflineEmbedder(private val dimension: Int = 64) {
    private val wordPattern = Pattern.compile("[\\p{L}\\p{Nd}]+")

    fun embed(text: String): FloatArray {
        val vector = FloatArray(dimension) { 0f }
        if (text.isEmpty()) return vector

        val matcher = wordPattern.matcher(text.lowercase())
        var totalTokens = 0
        while (matcher.find()) {
            val word = matcher.group()
            val hash = (word.hashCode() and 0x7FFFFFFF) % dimension
            vector[hash] += 1f
            totalTokens++
        }

        // L2 Normalization
        var sumSquares = 0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }
}

data class RagSearchResult(
    val docId: String,
    val text: String,
    val similarity: Float,
    val title: String = ""
)

class LocalRagEngine(
    private val knowledgeDao: KnowledgeDao,
    private val embedder: NativeOfflineEmbedder = NativeOfflineEmbedder(64)
) {
    suspend fun addDocument(
        projectId: Long,
        docId: String,
        title: String,
        content: String,
        collectionName: String = "default"
    ) {
        knowledgeDao.insertCollection(
            KnowledgeCollectionEntity(
                projectId = projectId,
                name = collectionName,
                description = "مجموعة $collectionName"
            )
        )

        knowledgeDao.deleteChunks(projectId, docId)
        knowledgeDao.insertDocument(
            DocumentEntity(
                projectId = projectId,
                docId = docId,
                title = title,
                content = content,
                collectionName = collectionName
            )
        )

        val chunks = chunkText(content, chunkSize = 300)
        val chunkEntities = chunks.mapIndexed { index, chunkText ->
            val embedding = embedder.embed(chunkText)
            DocumentChunkEntity(
                projectId = projectId,
                docId = docId,
                chunkIndex = index,
                text = chunkText,
                embeddingJson = VectorMath.floatsToJson(embedding),
                embeddingProvider = "native_offline",
                embeddingDimension = embedding.size
            )
        }
        knowledgeDao.insertChunks(chunkEntities)
    }

    suspend fun search(
        projectId: Long,
        query: String,
        topK: Int = 5,
        threshold: Float = 0.1f
    ): List<RagSearchResult> {
        val queryEmbedding = embedder.embed(query)
        val chunks = knowledgeDao.getAllChunks(projectId)

        val scored = mutableListOf<RagSearchResult>()
        for (chunk in chunks) {
            val chunkEmb = VectorMath.jsonToFloats(chunk.embeddingJson)
            if (chunkEmb.size == queryEmbedding.size && chunkEmb.isNotEmpty()) {
                val sim = VectorMath.cosineSimilarity(queryEmbedding, chunkEmb)
                if (sim >= threshold) {
                    scored.add(
                        RagSearchResult(
                            docId = chunk.docId,
                            text = chunk.text,
                            similarity = sim
                        )
                    )
                }
            } else if (chunk.text.contains(query, ignoreCase = true)) {
                // Lexical fallback
                scored.add(
                    RagSearchResult(
                        docId = chunk.docId,
                        text = chunk.text,
                        similarity = 0.5f
                    )
                )
            }
        }

        return scored.sortedByDescending { it.similarity }.take(topK)
    }

    private fun chunkText(text: String, chunkSize: Int = 300): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            chunks.add(text.substring(start, end))
            start += (chunkSize * 0.8).toInt() // 20% overlap
        }
        return chunks
    }
}
