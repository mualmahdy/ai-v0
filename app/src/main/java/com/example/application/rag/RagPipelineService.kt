package com.example.application.rag

import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.rag.AssembledRagContext
import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.KnowledgeDocument
import com.example.domain.core.rag.RetrievedContextChunk
import com.example.domain.ports.memory.EmbeddingProviderPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.sqrt

/**
 * Real RAG & Knowledge Subsystem for document ingestion, semantic chunking, and context assembly.
 */
class RagPipelineService(
    private val embeddingPort: EmbeddingProviderPort? = null
) {
    private val _documents = MutableStateFlow<List<KnowledgeDocument>>(emptyList())
    val documents: StateFlow<List<KnowledgeDocument>> = _documents.asStateFlow()

    private val chunks = mutableListOf<DocumentChunk>()

    init {
        bootstrapDefaultKnowledge()
    }

    private fun bootstrapDefaultKnowledge() {
        val initialDocs = listOf(
            KnowledgeDocument(
                id = "doc_clean_architecture",
                title = "AI-V0 Architectural Blueprint & Domain Rules",
                sourceUri = "workspace://docs/architecture.md",
                content = """
                    # مبادئ معمارية AI-V0 Platform
                    1. المعمارية النظيفة (Clean Architecture): فصل تام بين مجالات Domain و Application و Ports و Infrastructure و Presentation.
                    2. خلو طبقة النطاق من الاعتمادات الخارجية (Pure Kotlin Domain).
                    3. نظام اتخاذ القرار الذاتي CBR-MDP: دمج الذاكرة الإجرائية السابقة مع القيمة المستقبلية للمنفعة.
                    4. عدم استبدال المنطق الحقيقي بأي محاكاة أو أكواد مزيفة.
                    5. دعم كامل لنمط العمل دون اتصال بالإنترنت (Offline First) والنمط الهجين (Intelligent Hybrid).
                """.trimIndent()
            ),
            KnowledgeDocument(
                id = "doc_security_governance",
                title = "Security & Sanitization Guidelines",
                sourceUri = "workspace://docs/security.md",
                content = """
                    # إرشادات الأمان والحوكمة
                    1. حجب وتنقيح المفاتيح السرية (API Keys, Bearer Tokens) قبل الإرسال أو التخزين.
                    2. تأطير مخرجات الأدوات غير الموثوقة داخل علامات <tool_output untrusted="true">.
                    3. عزل مسار الملفات داخل بيئة العمل ومنع ثغرات Path Traversal (../).
                    4. طلب موافقة صريحة للمهام الحساسة وفق سياسة AutonomyPolicy.
                """.trimIndent()
            )
        )

        for (doc in initialDocs) {
            ingestDocumentSync(doc)
        }
    }

    private fun ingestDocumentSync(doc: KnowledgeDocument) {
        val generatedChunks = splitIntoChunks(doc)
        val updatedDoc = doc.copy(totalChunks = generatedChunks.size)
        _documents.update { it + updatedDoc }
        chunks.addAll(generatedChunks)
    }

    /**
     * Ingests a new document into the knowledge base, splits into chunks, and computes embeddings.
     */
    suspend fun ingestDocument(title: String, content: String, sourceUri: String, tags: List<String> = emptyList()): KnowledgeDocument = withContext(Dispatchers.Default) {
        val docId = "doc_${System.currentTimeMillis()}"
        val doc = KnowledgeDocument(
            id = docId,
            title = title,
            sourceUri = sourceUri,
            content = content,
            tags = tags
        )

        val splitChunks = splitIntoChunks(doc)
        val embeddedChunks = mutableListOf<DocumentChunk>()

        for (chunk in splitChunks) {
            var vector: EmbeddingVector? = null
            if (embeddingPort != null) {
                val embOutcome = embeddingPort.generateEmbeddings(listOf(chunk.text))
                if (embOutcome is Outcome.Success) {
                    vector = embOutcome.value.firstOrNull()
                }
            }
            // Generate lexical fallback vector if embedding not available
            if (vector == null) {
                vector = generateLexicalVector(chunk.text)
            }
            embeddedChunks.add(chunk.copy(vector = vector))
        }

        val completedDoc = doc.copy(totalChunks = embeddedChunks.size)
        _documents.update { it + completedDoc }
        chunks.addAll(embeddedChunks)
        completedDoc
    }

    private fun splitIntoChunks(doc: KnowledgeDocument, chunkSize: Int = 300, overlap: Int = 50): List<DocumentChunk> {
        val text = doc.content
        val chunkList = mutableListOf<DocumentChunk>()
        var start = 0
        var chunkIndex = 0

        while (start < text.length) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            val chunkText = text.substring(start, end).trim()
            if (chunkText.isNotBlank()) {
                chunkList.add(
                    DocumentChunk(
                        id = "${doc.id}_c$chunkIndex",
                        documentId = doc.id,
                        documentTitle = doc.title,
                        chunkIndex = chunkIndex,
                        text = chunkText,
                        tokenCount = chunkText.length / 4
                    )
                )
                chunkIndex++
            }
            if (end == text.length) break
            start += (chunkSize - overlap).coerceAtLeast(1)
        }
        return chunkList
    }

    /**
     * Semantic and keyword hybrid search over chunked knowledge base.
     */
    suspend fun retrieveRelevantContext(query: String, topK: Int = 4, maxTokenBudget: Int = 2000): AssembledRagContext = withContext(Dispatchers.Default) {
        val queryVector = if (embeddingPort != null) {
            val outcome = embeddingPort.generateEmbeddings(listOf(query))
            if (outcome is Outcome.Success) (outcome.value.firstOrNull() ?: generateLexicalVector(query)) else generateLexicalVector(query)
        } else {
            generateLexicalVector(query)
        }

        val scoredChunks = chunks.map { chunk ->
            val sim = computeCosineSimilarity(queryVector.values, chunk.vector?.values ?: generateLexicalVector(chunk.text).values)
            val lexicalMatch = if (chunk.text.contains(query, ignoreCase = true)) 0.3f else 0.0f
            val combinedScore = (sim * 0.7f + lexicalMatch * 0.3f).coerceIn(0.0f, 1.0f)

            RetrievedContextChunk(
                chunk = chunk,
                relevanceScore = combinedScore,
                retrievalMode = if (embeddingPort != null) RetrievalMode.HYBRID else RetrievalMode.LEXICAL_FALLBACK,
                snippet = chunk.text
            )
        }
            .filter { it.relevanceScore > 0.15f }
            .sortedByDescending { it.relevanceScore }
            .take(topK)

        val assembledText = buildString {
            appendLine("=== سياق المعرفة المسترجع (RAG Knowledge Base) ===")
            scoredChunks.forEachIndexed { i, c ->
                appendLine("[مصدر ${i + 1}: ${c.chunk.documentTitle} (صلة: ${"%.2f".format(c.relevanceScore)})]")
                appendLine(c.chunk.text)
                appendLine()
            }
        }

        AssembledRagContext(
            query = query,
            formattedContextText = assembledText,
            retrievedChunks = scoredChunks,
            totalTokensEstimated = assembledText.length / 4,
            isTruncated = false
        )
    }

    private fun generateLexicalVector(text: String): EmbeddingVector {
        val tokens = text.lowercase().split(Regex("[^\\w\\d]+")).filter { it.isNotBlank() }
        val vector = FloatArray(32) { 0.0f }
        for (token in tokens) {
            val hash = (token.hashCode() and 0x7FFFFFFF) % 32
            vector[hash] += 1.0f
        }
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0.001f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return EmbeddingVector(vector)
    }

    private fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        val len = minOf(vecA.size, vecB.size)
        if (len == 0) return 0.0f
        var dot = 0.0f
        var nA = 0.0f
        var nB = 0.0f
        for (i in 0 until len) {
            dot += vecA[i] * vecB[i]
            nA += vecA[i] * vecA[i]
            nB += vecB[i] * vecB[i]
        }
        val denom = (sqrt(nA.toDouble()) * sqrt(nB.toDouble())).toFloat()
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1.0f, 1.0f) else 0.0f
    }
}
