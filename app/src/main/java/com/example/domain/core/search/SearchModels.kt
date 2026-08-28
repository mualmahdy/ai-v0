package com.example.domain.core.search

/**
 * Standard search query.
 */
data class SearchQuery(
    val query: String,
    val maxResults: Int = 5,
    val searchDomain: String? = null,
    val freshOnly: Boolean = false
)

/**
 * Individual result item from an official search provider.
 */
data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Float? = null,
    val publishedDate: String? = null
)

/**
 * Complete search result payload.
 */
data class SearchResultSet(
    val query: String,
    val items: List<SearchResultItem>,
    val providerId: String,
    val totalAvailable: Int = items.size,
    val isPartialOrDegraded: Boolean = false
)

/**
 * Safe search provider metadata (strictly hides any credential or key).
 */
data class SafeSearchProviderMetadata(
    val id: String,
    val name: String,
    val providerType: String,
    val isConfigured: Boolean,
    val isEnabled: Boolean,
    val priority: Int
)

/**
 * Categorized domain failures for official search providers.
 */
sealed interface SearchFailure {
    data class ProviderUnavailable(val providerId: String, val message: String) : SearchFailure
    data class RateLimited(val providerId: String, val retryAfterMs: Long?) : SearchFailure
    data class AuthenticationFailed(val providerId: String, val message: String) : SearchFailure
    data class QueryInvalid(val reason: String) : SearchFailure
    data class NetworkError(val message: String, val statusCode: Int? = null) : SearchFailure
}
