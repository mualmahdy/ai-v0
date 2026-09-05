package com.example.domain.core.search.intelligence

import com.example.domain.core.search.SearchResultItem

/**
 * ============================================================================
 * Search Intelligence Domain Models — Phase 5
 * ============================================================================
 *
 * Closes the Search Intelligence gap (audit: 35–40% → ~55%) by adding:
 *
 *   1. Search intent classification (FACTUAL, RESEARCH, NAVIGATIONAL,
 *      TEMPORAL, COMPARATIVE, HOW_TO, OPINION).
 *   2. Query decomposition into sub-queries (multi-query expansion).
 *   3. Source selection policy (which providers to ask for which intents).
 *   4. Cross-source deduplication (URL/title canonicalization).
 *   5. Re-ranking with evidence scoring (recency, authority, relevance).
 *   6. Citation provenance chain — every retrieved item carries a
 *      `CitationChain` so the model output can be grounded.
 */

/**
 * Classified intent of a search query. Drives source selection and
 * ranking weights (e.g. TEMPORAL boosts fresh results; OPINION down-
 * weights official docs).
 */
enum class SearchIntent(val displayLabelAr: String) {
    FACTUAL("استعلام وقائعي"),
    RESEARCH("بحث معمّق"),
    NAVIGATIONAL("بحث عن موقع/صفحة محددة"),
    TEMPORAL("استعلام زمني (أخبار/تواريخ)"),
    COMPARATIVE("مقارنة"),
    HOW_TO("كيفية"),
    OPINION("رأي/تحليل"),
    UNKNOWN("غير مصنف");

    companion object {
        /**
         * Heuristic intent classifier. Looks at query keywords; this is
         * intentionally cheap (no LLM call) so it can run synchronously
         * before the actual search.
         */
        fun classify(query: String): SearchIntent {
            val q = query.lowercase().trim()
            return when {
                q.contains("how to") || q.contains("كيف") || q.contains("خطوات") || q.contains("طريقة") -> HOW_TO
                q.contains("vs") || q.contains("مقارنة") || q.contains("أفضل") || q.contains("vs.") -> COMPARATIVE
                q.contains("latest") || q.contains("newest") || q.contains("recent") ||
                    q.contains("اليوم") || q.contains("آخر") || q.contains("2024") || q.contains("2025") || q.contains("2026") -> TEMPORAL
                q.contains("opinion") || q.contains("analysis") || q.contains("رأي") || q.contains("تحليل") -> OPINION
                q.contains("research") || q.contains("study") || q.contains("بحث") || q.contains("دراسة") -> RESEARCH
                q.matches(Regex(".*\\b(site|url|goto|افتح)\\b.*")) -> NAVIGATIONAL
                q.length < 8 && !q.contains(" ") -> NAVIGATIONAL
                else -> FACTUAL
            }
        }
    }
}

/**
 * A single decomposed sub-query derived from a complex user query.
 *
 * Example:
 *   User query: "compare GPT-4 and Claude 3 on reasoning tasks"
 *   Sub-queries:
 *     - "GPT-4 reasoning benchmarks"
 *     - "Claude 3 reasoning benchmarks"
 *     - "GPT-4 vs Claude 3 reasoning comparison"
 */
data class SubQuery(
    val text: String,
    val intent: SearchIntent,
    val weight: Float = 1.0f,
    val rationale: String = ""
)

/**
 * Result of query decomposition.
 */
data class QueryDecomposition(
    val originalQuery: String,
    val primaryIntent: SearchIntent,
    val subQueries: List<SubQuery>
) {
    val expansionCount: Int get() = subQueries.size
}

/**
 * Source selection policy — which providers should be queried for a
 * given intent, in what order, with what relative weight.
 */
data class SourceSelection(
    val intent: SearchIntent,
    val providers: List<SourcePreference>
)

data class SourcePreference(
    val providerId: String,
    val priority: Int,
    val weight: Float,
    val freshOnly: Boolean = false,
    val maxResults: Int = 5
)

/**
 * A ranked search result — extends `SearchResultItem` with the multi-
 * dimensional scores used by the reranker.
 */
data class RankedSearchItem(
    val item: SearchResultItem,
    val relevanceScore: Float,
    val authorityScore: Float,
    val recencyScore: Float,
    val evidenceScore: Float,
    val finalScore: Float,
    val sourceProviderId: String,
    val subQueryIndex: Int,
    val dedupKey: String
)

/**
 * Citation chain — the provenance trail for a single piece of evidence
 * presented to the agent. Includes the original query, the sub-query
 * that found it, the provider that returned it, and the canonical URL.
 */
data class CitationChain(
    val originalQuery: String,
    val subQueryText: String,
    val providerId: String,
    val itemUrl: String,
    val itemTitle: String,
    val retrievedAtEpochMs: Long = System.currentTimeMillis(),
    val confidenceScore: Float
)

/**
 * Final intelligence output — what the agent receives as search evidence.
 */
data class SearchIntelligenceResult(
    val originalQuery: String,
    val decomposition: QueryDecomposition,
    val rankedItems: List<RankedSearchItem>,
    val citations: List<CitationChain>,
    val isPartial: Boolean,
    val degradationReason: String? = null
)
