package com.example.application.search

import com.example.domain.core.Outcome
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SearchResultSet
import com.example.domain.core.search.intelligence.SearchIntent
import com.example.domain.ports.search.SearchProviderPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — SearchIntelligenceService unit tests.
 *
 * Closes the test-coverage aspect of P5-P0-05 (Search Intelligence):
 * proves query decomposition, intent classification, deduplication,
 * and ranking all work as designed.
 */
class SearchIntelligenceServiceTest {

    @Test
    fun `classifyIntent detects TEMPORAL for date-keyed queries`() {
        val intent = SearchIntent.classify("latest news about AI 2026")
        assertEquals(SearchIntent.TEMPORAL, intent)
    }

    @Test
    fun `classifyIntent detects HOW_TO for procedural queries`() {
        val intent = SearchIntent.classify("how to implement RAG with reranking")
        assertEquals(SearchIntent.HOW_TO, intent)
    }

    @Test
    fun `classifyIntent detects COMPARATIVE for vs queries`() {
        val intent = SearchIntent.classify("GPT-4 vs Claude 3 reasoning")
        assertEquals(SearchIntent.COMPARATIVE, intent)
    }

    @Test
    fun `decompose splits comparison into three sub-queries`() {
        val service = SearchIntelligenceService(StubProvider())
        val decomp = service.decompose("GPT-4 vs Claude 3 reasoning benchmarks")
        assertEquals(3, decomp.subQueries.size)
        assertTrue(decomp.subQueries.any { it.text.contains("GPT-4") })
        assertTrue(decomp.subQueries.any { it.text.contains("Claude") })
        assertTrue(decomp.subQueries.any { it.text.contains("vs") || it.text.contains("مقارنة") })
    }

    @Test
    fun `canonicalDedupKey is identical for the same URL+title regardless of query params`() {
        val service = SearchIntelligenceService(StubProvider())
        val key1 = service.canonicalDedupKey("https://example.com/article?utm_source=feed", "My Article")
        val key2 = service.canonicalDedupKey("https://example.com/article", "My Article")
        assertEquals(key1, key2)
    }

    @Test
    fun `dedup keeps the highest-relevance item when duplicates exist`() {
        val service = SearchIntelligenceService(StubProvider())
        val item = SearchResultItem(title = "T", url = "https://example.com/a", snippet = "s")
        val a = com.example.domain.core.search.intelligence.RankedSearchItem(
            item = item, relevanceScore = 0.9f, authorityScore = 0.5f, recencyScore = 0.5f,
            evidenceScore = 0f, finalScore = 0f, sourceProviderId = "p1", subQueryIndex = 0,
            dedupKey = "k1"
        )
        val b = a.copy(relevanceScore = 0.3f)
        val deduped = service.dedup(listOf(a, b))
        assertEquals(1, deduped.size)
        assertEquals(0.9f, deduped.first().relevanceScore, 0.001f)
    }

    @Test
    fun `rank for FACTUAL intent weights authority highest`() {
        val service = SearchIntelligenceService(StubProvider())
        val items = listOf(
            makeRanked("wikipedia", 0.6f, "https://en.wikipedia.org/wiki/X", "Wikipedia Article"),
            makeRanked("blog", 0.9f, "https://blog.example.com/x", "Blog Post")
        )
        val ranked = service.rank(items, SearchIntent.FACTUAL)
        // Wikipedia wins despite lower relevance because authority weight is 0.5 for FACTUAL.
        assertEquals("https://en.wikipedia.org/wiki/X", ranked.first().item.url)
    }

    @Test
    fun `rank for TEMPORAL intent weights recency highest`() {
        val service = SearchIntelligenceService(StubProvider())
        val recent = makeRanked("news", 0.3f, "https://news.example.com/today", "Today News", publishedDate = "2026-09-05T10:00:00Z")
        val old = makeRanked("docs", 0.9f, "https://docs.example.com/x", "Old Doc", publishedDate = "2024-01-01T00:00:00Z")
        val ranked = service.rank(listOf(recent, old), SearchIntent.TEMPORAL)
        // Recent wins despite lower relevance because recency weight is 0.5 for TEMPORAL.
        assertEquals("https://news.example.com/today", ranked.first().item.url)
    }

    @Test
    fun `selectSources returns multi-source for FACTUAL`() {
        val service = SearchIntelligenceService(StubProvider())
        val sel = service.selectSources(SearchIntent.FACTUAL)
        assertEquals(1, sel.providers.size)
        assertEquals("multi_source_search", sel.providers.first().providerId)
    }

    // ------------------------------------------------------------------
    // CHAT FINAL CLOSURE (§14) — search state truthfulness: zero results is
    // SUCCESS_EMPTY, NOT degraded; degraded means a source failed.
    // ------------------------------------------------------------------

    @Test
    fun `a healthy empty result set is NOT partial - zero results is success-empty`() = runBlocking {
        val service = SearchIntelligenceService(EmptyResultProvider())
        val result = service.searchIntelligent("لا نتائج لهذا الاستعلام")
        assertTrue("an empty-but-healthy search is not partial", !result.isPartial)
        assertNull("no degradation reason without a source failure", result.degradationReason)
        assertTrue(result.rankedItems.isEmpty())
    }

    @Test
    fun `a failed source marks the search partial - even with zero results`() = runBlocking {
        val service = SearchIntelligenceService(FailingProvider())
        val result = service.searchIntelligent("استعلام ستفشل مصادره")
        assertTrue("a failed source makes the search partial", result.isPartial)
        assertNotNull("the degradation reason names the real situation", result.degradationReason)
        assertTrue(result.rankedItems.isEmpty())
    }

    @Test
    fun `a degraded source marks the search partial with results`() = runBlocking {
        val service = SearchIntelligenceService(DegradedProvider())
        val result = service.searchIntelligent("استعلام بمصدر متراجع")
        assertTrue("a degraded source makes the search partial", result.isPartial)
        assertEquals(1, result.rankedItems.size)
        assertNotNull(result.degradationReason)
    }

    @Test
    fun `a fully successful search is not partial`() = runBlocking {
        val service = SearchIntelligenceService(StubProvider())
        val result = service.searchIntelligent("استعلام ناجح")
        assertTrue(result.rankedItems.isNotEmpty())
        assertFalse(result.isPartial)
        assertNull(result.degradationReason)
    }

    private class EmptyResultProvider : SearchProviderPort {
        override val providerId: String = "empty_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "empty_search", name = "Empty", providerType = "TEST",
            isConfigured = true, isEnabled = true, priority = 1
        )
        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> =
            Outcome.Success(SearchResultSet(query = query.query, items = emptyList(), providerId = providerId))
    }

    private class FailingProvider : SearchProviderPort {
        override val providerId: String = "failing_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "failing_search", name = "Failing", providerType = "TEST",
            isConfigured = true, isEnabled = true, priority = 1
        )
        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> =
            Outcome.Error(
                failure = SearchFailure.NetworkError("تعذر الوصول للمصدر"),
                diagnosticMessage = "تعذر الوصول للمصدر"
            )
    }

    private class DegradedProvider : SearchProviderPort {
        override val providerId: String = "degraded_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "degraded_search", name = "Degraded", providerType = "TEST",
            isConfigured = true, isEnabled = true, priority = 1
        )
        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> =
            Outcome.Degraded(
                partialValue = SearchResultSet(
                    query = query.query,
                    items = listOf(
                        SearchResultItem(title = "نتيجة جزئية", url = "https://example.com/partial", snippet = "جزء")
                    ),
                    providerId = providerId
                ),
                reason = com.example.domain.core.DegradedReason.CACHE_FALLBACK,
                diagnosticMessage = "نتائج جزئية"
            )
    }

    private fun makeRanked(
        authorityHint: String,
        relevance: Float,
        url: String,
        title: String,
        publishedDate: String? = null
    ): com.example.domain.core.search.intelligence.RankedSearchItem {
        val item = SearchResultItem(title = title, url = url, snippet = "snippet", publishedDate = publishedDate)
        return com.example.domain.core.search.intelligence.RankedSearchItem(
            item = item,
            relevanceScore = relevance,
            authorityScore = 0.5f, // placeholder; real authority computed inside rank()
            recencyScore = 0.5f,
            evidenceScore = 0f,
            finalScore = 0f,
            sourceProviderId = "p1",
            subQueryIndex = 0,
            dedupKey = url
        )
    }

    private class StubProvider : SearchProviderPort {
        override val providerId: String = "stub_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "stub_search",
            name = "Stub",
            providerType = "STUB",
            isConfigured = true,
            isEnabled = true,
            priority = 1
        )
        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> {
            return Outcome.Success(
                SearchResultSet(
                    query = query.query,
                    items = listOf(SearchResultItem(title = "Stub", url = "https://stub.example.com/$query.query", snippet = "stub")),
                    providerId = "stub"
                )
            )
        }
    }
}
