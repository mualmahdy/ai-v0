package com.example.domain.ports.search

import com.example.domain.core.Outcome
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultSet

/**
 * Standard Port for Search Providers (Tavily, SearXNG, etc.).
 *
 * Scraped/unofficial endpoints are forbidden by architecture policy.
 */
interface SearchProviderPort {
    val providerId: String
    val metadata: SafeSearchProviderMetadata

    /**
     * Executes a search query using the official provider API.
     */
    suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure>
}
