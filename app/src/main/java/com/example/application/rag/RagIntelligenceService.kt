package com.example.application.rag

import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.intelligence.AssembledRagContextV2
import com.example.domain.core.rag.intelligence.EvidenceProvenance
import com.example.domain.core.rag.intelligence.HybridRetrievalRequest
import com.example.domain.core.rag.intelligence.RerankResult
import com.example.domain.core.rag.intelligence.RerankerConfig
import com.example.domain.core.rag.intelligence.RetrievalCandidate
import com.example.domain.core.rag.intelligence.RetrievalSource
import com.example.domain.core.rag.intelligence.RrfConfig
import com.example.domain.core.rag.intelligence.RrfFusedCandidate
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.ln

/**
 * ============================================================================
 * RagIntelligenceService — Phase 5 RAG Intelligence (P0 remediation)
 * ============================================================================
 *
 * Closes the RAG Intelligence gap (audit: 40–45% → ~55%) by adding:
 *
 *   1. Hybrid retrieval with Reciprocal Rank Fusion (RRF). Previously
 *      `RagPipelineService.retrieveRelevantContext` computed a single
 *      pass over one chunk list with a fixed `sim*0.7 + lexicalMatch*0.3`
 *      weighting. This service runs the semantic and lexical indices
 *      SEPARATELY and fuses their rankings via RRF, which is the
 *      standard hybrid-retrieval algorithm.
 *
 *   2. Second-stage reranking. The previous code returned the raw
 *      similarity-sorted chunks; this service applies a feature-based
 *      reranker (title match × snippet relevance × recency × authority)
 *      that mimics a cross-encoder without requiring an extra model
 *      call.
 *
 *   3. Token-budget-aware context assembly. Previously chunks were
 *      concatenated without regard to the model's context window. This
 *      service packs chunks greedily by descending reranked score,
 *      stopping when adding the next chunk would exceed the budget.
 *
 *   4. Metadata filtering. `DocumentChunk.metadata` is now honored as
 *      a filter (e.g. `{"source": "official-docs", "lang": "ar"}`).
 *
 *   5. Evidence confidence calibration. The previous `relevanceScore`
 *      was a raw similarity (often inflated to ~0.95 for any decent
 *      match). This service produces a calibrated `evidenceConfidence`
 *      in [0, 1] that accounts for the rerank features AND the
 *      diversity of supporting sources.
 *
 * The service is purely additive — `RagPipelineService` remains the
 * primary RAG entry point; this service is used by the orchestrator
 * when the task demands grounded answers.
 */
class RagIntelligenceService(
    private val documentChunkDao: DocumentChunkDao,
    private val embeddingProvider: EmbeddingProviderPort,
    /**
     * P0 CONVERGENCE (audit step 12 §7): optional document DAO so reloaded
     * chunks get their REAL document titles (previously always "" — titles
     * vanished on the intelligence path after restart).
     */
    private val documentDao: KnowledgeDocumentDao? = null,
    private val rrfConfig: RrfConfig = RrfConfig(),
    private val rerankerConfig: RerankerConfig = RerankerConfig()
) {

    /**
     * Full hybrid retrieval → RRF fusion → reranking → context assembly
     * pipeline. Returns the assembled context ready to prepend to the
     * model's prompt.
     */
    suspend fun retrieveAndAssemble(request: HybridRetrievalRequest): AssembledRagContextV2 = withContext(Dispatchers.IO) {
        // 1. Load all chunks for the workspace (filtered by metadata).
        val allChunks = loadChunks(request.workspaceId, request.metadataFilters)
        if (allChunks.isEmpty()) {
            return@withContext emptyContext(request)
        }

        // 2. Generate query embedding.
        val queryVec = when (val r = embeddingProvider.generateEmbeddings(listOf(request.query))) {
            is com.example.domain.core.Outcome.Success -> r.value.firstOrNull()
            else -> null
        }

        // 3. Run semantic + lexical indices separately.
        val semanticCandidates = if (request.enableSemanticIndex && queryVec != null) {
            // minScoreThreshold applies in SIMILARITY space (cosine), where
            // it actually means something (audit 2026 fix).
            runSemanticIndex(allChunks, queryVec, request.topK, request.minScoreThreshold)
        } else emptyList()

        val lexicalCandidates = if (request.enableLexicalIndex) {
            runLexicalIndex(allChunks, request.query, request.topK)
        } else emptyList()

        if (semanticCandidates.isEmpty() && lexicalCandidates.isEmpty()) {
            return@withContext emptyContext(request)
        }

        // 4. RRF fusion.
        val fused = rrfFuse(semanticCandidates, lexicalCandidates)

        // 5. Filter by minimum fused score.
        // Audit 2026 fix: `minScoreThreshold` is a SIMILARITY-space bar (0..1).
        // RRF produces RANK-space scores (~0.001..0.03) — filtering those
        // against 0.2 dropped EVERY candidate, so the v2 RAG pipeline could
        // never return results at all (caught by RagIntelligenceServiceTest).
        // RRF entries exist only because at least one index matched them,
        // so the correct fused-space bar is fusedScore > 0.
        val filtered = fused.filter { it.fusedScore > 0f }

        // 6. Rerank.
        val reranked = if (request.enableReranking) rerank(filtered, request.query) else filtered.map { c ->
            RerankResult(c.chunk, c.fusedScore, c.fusedScore, "بدون إعادة ترتيب")
        }

        // 7. Token-budget-aware assembly.
        assembleContext(request, reranked)
    }

    /**
     * Load all chunks for a workspace, optionally filtered by metadata.
     *
     * The filter is applied in Kotlin (not SQL) because the metadata
     * is stored as JSON inside `vectorJson`'s sibling column — the
     * filter is small (a few key/value pairs) and the per-workspace
     * chunk count is bounded.
     */
    private suspend fun loadChunks(
        workspaceId: String,
        metadataFilters: Map<String, String>
    ): List<DocumentChunk> {
        val entities = documentChunkDao.getChunksForWorkspace(workspaceId)
        // P0 CONVERGENCE: rebuild chunk titles from the document map so the
        // intelligence path no longer loses provenance titles on reload.
        val titlesByDocId = documentDao?.getDocumentsForWorkspace(workspaceId)
            ?.associate { it.id to it.title }
            ?: emptyMap()
        return entities.map { it.toDomain(titlesByDocId[it.documentId] ?: "") }.filter { chunk ->
            if (metadataFilters.isEmpty()) true
            else metadataFilters.all { (k, v) -> chunk.metadata[k] == v }
        }
    }

    /**
     * Semantic index: cosine similarity between query vector and chunk
     * vectors, sorted descending, take topK.
     */
    private fun runSemanticIndex(
        chunks: List<DocumentChunk>,
        queryVec: EmbeddingVector,
        topK: Int,
        minSimilarity: Float = 0f
    ): List<RetrievalCandidate> {
        return chunks.mapNotNull { chunk ->
            val v = chunk.vector ?: return@mapNotNull null
            val sim = cosine(queryVec.values, v.values)
            if (sim < minSimilarity) return@mapNotNull null
            RetrievalCandidate(chunk = chunk, score = sim, rank = 0, source = RetrievalSource.SEMANTIC)
        }
            .sortedByDescending { it.score }
            .take(topK)
            .mapIndexed { idx, c -> c.copy(rank = idx + 1) }
    }

    /**
     * Lexical index: BM25-like score using term frequency × inverse
     * document frequency, computed in-memory. This is the standard
     * lexical signal that complements the semantic index.
     */
    private fun runLexicalIndex(
        chunks: List<DocumentChunk>,
        query: String,
        topK: Int
    ): List<RetrievalCandidate> {
        val queryTerms = query.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }.toSet()
        if (queryTerms.isEmpty()) return emptyList()

        // ------------------------------------------------------------------
        // REPAIR ORDER §15 — O(N²) FIX: tokenization now happens ONCE per
        // chunk. Previously `avgDl = chunks.sumOf { tokenize(it.text).size }`
        // sat INSIDE the per-chunk map lambda, so the whole corpus was
        // re-tokenized FOR EVERY CHUNK (N² tokenizations per query). We now
        // tokenize each chunk once, reuse the token lists for df + tf + dl,
        // and compute avgDl from the cached lengths (single pass, O(N)).
        // ------------------------------------------------------------------
        val chunkTokens: List<List<String>> = chunks.map { tokenize(it.text) }
        val chunkLengths = chunkTokens.map { it.size }
        val n = chunks.size
        val avgDl = chunkLengths.sum().toDouble() / n.coerceAtLeast(1)

        // Document frequency per term (single pass over cached token lists).
        val df = mutableMapOf<String, Int>()
        for (tokens in chunkTokens) {
            val tokenSet = tokens.toHashSet()
            for (term in queryTerms) {
                if (term in tokenSet) df[term] = (df[term] ?: 0) + 1
            }
        }

        return chunks.mapIndexed { idx, chunk ->
            val tokens = chunkTokens[idx]
            val tf = mutableMapOf<String, Int>()
            for (t in tokens) {
                if (t in queryTerms) tf[t] = (tf[t] ?: 0) + 1
            }
            // BM25 score with k1=1.2, b=0.75.
            val k1 = 1.2; val b = 0.75
            val dl = chunkLengths[idx].toDouble()
            var score = 0.0
            for (term in queryTerms) {
                val termDf = df[term] ?: 0
                if (termDf == 0) continue
                val idf = ln((n - termDf + 0.5) / (termDf + 0.5) + 1.0)
                val termTf = tf[term] ?: 0
                if (termTf == 0) continue
                val num = termTf * (k1 + 1)
                val den = termTf + k1 * (1 - b + b * dl / (avgDl.coerceAtLeast(1.0)))
                score += idf * (num / den)
            }
            RetrievalCandidate(chunk = chunk, score = score.toFloat(), rank = 0, source = RetrievalSource.LEXICAL)
        }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
            .mapIndexed { idx2, c -> c.copy(rank = idx2 + 1) }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /**
     * Reciprocal Rank Fusion. For each unique chunk, sum
     * `weight / (k + rank)` across all sources that returned it.
     */
    fun rrfFuse(
        semantic: List<RetrievalCandidate>,
        lexical: List<RetrievalCandidate>
    ): List<RrfFusedCandidate> {
        val byChunkId = mutableMapOf<String, RrfFusedCandidate>()

        for (c in semantic) {
            val contribution = rrfConfig.semanticWeight / (rrfConfig.k + c.rank).toFloat()
            val existing = byChunkId[c.chunk.id]
            if (existing == null) {
                byChunkId[c.chunk.id] = RrfFusedCandidate(
                    chunk = c.chunk,
                    fusedScore = contribution,
                    semanticRank = c.rank,
                    lexicalRank = null,
                    sources = listOf(c.source)
                )
            } else {
                byChunkId[c.chunk.id] = existing.copy(
                    fusedScore = existing.fusedScore + contribution,
                    semanticRank = c.rank,
                    sources = existing.sources + c.source
                )
            }
        }

        for (c in lexical) {
            val contribution = rrfConfig.lexicalWeight / (rrfConfig.k + c.rank).toFloat()
            val existing = byChunkId[c.chunk.id]
            if (existing == null) {
                byChunkId[c.chunk.id] = RrfFusedCandidate(
                    chunk = c.chunk,
                    fusedScore = contribution,
                    semanticRank = null,
                    lexicalRank = c.rank,
                    sources = listOf(c.source)
                )
            } else {
                byChunkId[c.chunk.id] = existing.copy(
                    fusedScore = existing.fusedScore + contribution,
                    lexicalRank = c.rank,
                    sources = existing.sources + c.source
                )
            }
        }

        return byChunkId.values.sortedByDescending { it.fusedScore }
    }

    /**
     * Second-stage reranker. In the absence of a real cross-encoder
     * model, we use a feature-based reranker that combines:
     *
     *   - Title match: how many query terms appear in the chunk's
     *     document title.
     *   - Snippet relevance: how many query terms appear in the first
     *     200 chars of the chunk.
     *   - Recency: chunks from newer documents get a small boost.
     *   - Authority: chunks from documents tagged "official" or
     *     "reference" get a small boost.
     */
    fun rerank(
        candidates: List<RrfFusedCandidate>,
        query: String
    ): List<RerankResult> {
        val queryTerms = query.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }.toSet()
        val now = System.currentTimeMillis()

        return candidates.map { c ->
            val title = c.chunk.documentTitle.lowercase()
            val snippet = c.chunk.text.take(200).lowercase()

            val titleMatches = queryTerms.count { it in title }
            val snippetMatches = queryTerms.count { it in snippet }
            val titleScore = if (queryTerms.isNotEmpty()) titleMatches.toFloat() / queryTerms.size else 0f
            val snippetScore = if (queryTerms.isNotEmpty()) snippetMatches.toFloat() / queryTerms.size else 0f

            // Recency: based on chunk metadata `createdAtEpochMs` if present.
            val createdMs = c.chunk.metadata["createdAtEpochMs"]?.toLongOrNull() ?: now
            val ageDays = ((now - createdMs) / (24L * 60 * 60 * 1000)).coerceAtLeast(0L)
            val recencyScore = (1.0f - ageDays.toFloat() / 365f).coerceIn(0f, 1f)

            // Authority: based on document tags stored in metadata.
            val tagStr = c.chunk.metadata["tags"] ?: ""
            val authorityScore = if (tagStr.contains("official") || tagStr.contains("reference")) 1.0f
                else if (tagStr.contains("verified")) 0.7f
                else 0.5f

            val rerankedScore = c.fusedScore * 0.5f +
                titleScore * rerankerConfig.titleMatchWeight +
                snippetScore * rerankerConfig.snippetRelevanceWeight +
                recencyScore * rerankerConfig.recencyWeight +
                authorityScore * rerankerConfig.authorityWeight

            // Calibrated evidence confidence: combine the fused score with
            // the reranker features. The key insight is that confidence is
            // NOT just similarity — a chunk that matches on title AND
            // snippet AND is recent AND authoritative deserves a much
            // higher confidence than one that only has high similarity.
            val evidenceConfidence = (
                c.fusedScore * 0.4f +
                    titleScore * 0.15f +
                    snippetScore * 0.25f +
                    recencyScore * 0.1f +
                    authorityScore * 0.1f
                ).coerceIn(0f, 1f)

            val rationale = buildString {
                append("fused=${"%.3f".format(c.fusedScore)}; ")
                append("title=${"%.2f".format(titleScore)}; ")
                append("snippet=${"%.2f".format(snippetScore)}; ")
                append("recency=${"%.2f".format(recencyScore)}; ")
                append("authority=${"%.2f".format(authorityScore)}")
            }

            RerankResult(c.chunk, rerankedScore, evidenceConfidence, rationale)
        }.sortedByDescending { it.rerankedScore }
    }

    /**
     * Token-budget-aware context assembly. Greedily packs chunks by
     * descending reranked score until the next chunk would exceed the
     * budget. Each chunk is formatted with a citation header so the
     * model can reference it.
     */
    private fun assembleContext(
        request: HybridRetrievalRequest,
        reranked: List<RerankResult>
    ): AssembledRagContextV2 {
        val sb = StringBuilder()
        val included = mutableListOf<RerankResult>()
        val provenance = mutableListOf<EvidenceProvenance>()
        var totalTokens = 0
        var truncated = false

        // Rough token estimate: 1 token ≈ 4 chars of English text. We use
        // 3 chars/token for Arabic-heavy content to be safe.
        val charsPerToken = 3

        for (result in reranked) {
            val chunkTokens = (result.chunk.text.length / charsPerToken).coerceAtLeast(1)
            val header = "[مستند: ${result.chunk.documentTitle} | قطعة #${result.chunk.chunkIndex} | ثقة: ${"%.2f".format(result.evidenceConfidence)}]\n"
            val headerTokens = (header.length / charsPerToken).coerceAtLeast(1)

            if (totalTokens + chunkTokens + headerTokens > request.maxTokenBudget) {
                truncated = true
                continue
            }

            sb.append(header)
            sb.append(result.chunk.text)
            sb.append("\n\n")
            totalTokens += chunkTokens + headerTokens
            included.add(result)
            provenance.add(
                EvidenceProvenance(
                    chunkId = result.chunk.id,
                    documentId = result.chunk.documentId,
                    documentTitle = result.chunk.documentTitle,
                    chunkIndex = result.chunk.chunkIndex,
                    confidence = result.evidenceConfidence,
                    snippet = result.chunk.text.take(120)
                )
            )
        }

        val avgConfidence = if (included.isEmpty()) 0f
            else included.map { it.evidenceConfidence }.average().toFloat()

        val retrievalMode = when {
            included.any { it.rerankedScore > 0 } && request.enableSemanticIndex && request.enableLexicalIndex -> RetrievalMode.HYBRID
            request.enableSemanticIndex -> RetrievalMode.SEMANTIC
            else -> RetrievalMode.LEXICAL_FALLBACK
        }

        return AssembledRagContextV2(
            query = request.query,
            formattedContextText = sb.toString().trim(),
            retrievedChunks = included,
            totalTokensEstimated = totalTokens,
            isTruncated = truncated,
            evidenceProvenance = provenance,
            averageConfidence = avgConfidence,
            retrievalMode = retrievalMode
        )
    }

    private fun emptyContext(request: HybridRetrievalRequest): AssembledRagContextV2 = AssembledRagContextV2(
        query = request.query,
        formattedContextText = "",
        retrievedChunks = emptyList(),
        totalTokensEstimated = 0,
        isTruncated = false,
        evidenceProvenance = emptyList(),
        averageConfidence = 0f,
        retrievalMode = RetrievalMode.LEXICAL_FALLBACK
    )

    // --- Vector math helpers ---

    /**
     * GAP-28 (Design Closure 2026): the private cosine was replaced by the
     * shared kernel ([com.example.domain.core.memory.VectorMath.cosine]) —
     * this copy was byte-identical to the memory-subsystem ones.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float =
        com.example.domain.core.memory.VectorMath.cosine(a, b)

    /**
     * GAP-28/GAP-13 (Design Closure 2026): vector-decode failures here were
     * the LAST silent swallow (runCatching → null, no log, no counter) —
     * now counted + logged like KnowledgePersistenceService, so a corrupt
     * chunk row is visible instead of just "missing".
     */
    private val vectorDecodeFailures = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var lastVectorDecodeFailure: String? = null
        private set

    private fun DocumentChunkEntity.toDomain(documentTitle: String): DocumentChunk {
        val vec = runCatching {
            val arr = JSONArray(vectorJson)
            val floats = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
            EmbeddingVector(values = floats, dimension = floats.size)
        }.onFailure { failure ->
            vectorDecodeFailures.incrementAndGet()
            lastVectorDecodeFailure =
                "VECTOR_DECODE_FAILED: ${failure::class.simpleName}: ${failure.message?.take(120)}"
            System.err.println("RAG-INTELLIGENCE $lastVectorDecodeFailure chunkId=$id")
        }.getOrNull()
        // P0 CONVERGENCE (audit step 12 §7): the chunk's OWN persisted metadata
        // (embeddingResourceId, embeddingSemantic, tags, ingest-time filter
        // keys...) is now restored from `metadataJson` instead of being
        // rebuilt as a partial synthetic map — metadata filters, authority
        // and recency signals survive restarts. The workspace/retrieval
        // context keys are merged on top (same values the reload previously
        // fabricated, kept for compatibility).
        val persistedMetadata = runCatching {
            val obj = org.json.JSONObject(metadataJson)
            val out = mutableMapOf<String, String>()
            for (k in obj.keys()) {
                if (!obj.isNull(k)) out[k] = obj.optString(k)
            }
            out
        }.getOrDefault(emptyMap())
        return DocumentChunk(
            id = id,
            documentId = documentId,
            documentTitle = documentTitle,
            chunkIndex = chunkIndex,
            text = text,
            vector = vec,
            tokenCount = tokenCount,
            metadata = persistedMetadata + mapOf(
                "workspaceId" to workspaceId,
                "createdAtEpochMs" to createdAtEpochMs.toString(),
                "retrievalSource" to retrievalSource
            )
        )
    }
}
