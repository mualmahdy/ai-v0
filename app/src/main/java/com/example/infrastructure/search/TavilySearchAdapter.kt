package com.example.infrastructure.search

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SearchResultSet
import com.example.domain.ports.search.SearchProviderPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Official Tavily Search API Adapter.
 *
 * Adheres strictly to Architectural Constraints:
 * - Direct official API endpoint only (No web scraping / HTML parsing).
 * - Never logs or leaks API keys in metadata or errors.
 * - Accurately reports Outcome.Success, Outcome.Degraded (partial), or Outcome.Error.
 */
class TavilySearchAdapter(
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : SearchProviderPort {

    override val providerId: String = "tavily"

    override val metadata: SafeSearchProviderMetadata
        get() = SafeSearchProviderMetadata(
            id = providerId,
            name = "Tavily Search API",
            providerType = "OFFICIAL_REST_API",
            isConfigured = !apiKeyProvider().isNullOrBlank(),
            isEnabled = true,
            priority = 1
        )

    override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Outcome.Error(
                failure = SearchFailure.AuthenticationFailed(providerId, "مفتاح Tavily API غير مهيأ."),
                diagnosticMessage = "مفتاح البحث غير متوفر."
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val jsonBody = JSONObject().apply {
                put("api_key", apiKey)
                put("query", query.query)
                put("max_results", query.maxResults.coerceIn(1, 10))
                put("search_depth", "basic")
                put("include_answer", true)
            }

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    val code = response.code
                    return@withContext if (code == 401 || code == 403) {
                        Outcome.Error(
                            failure = SearchFailure.AuthenticationFailed(providerId, "فشل المصادقة مع Tavily (رمز $code)"),
                            diagnosticMessage = "مفتاح API غير صالح."
                        )
                    } else if (code == 429) {
                        Outcome.Error(
                            failure = SearchFailure.RateLimited(providerId, 60000L),
                            diagnosticMessage = "تم تجاوز حد الطلبات في Tavily."
                        )
                    } else {
                        Outcome.Error(
                            failure = SearchFailure.NetworkError("استجابة غير ناجحة من Tavily: $code", code),
                            diagnosticMessage = "خطأ في الاتصال بمزود البحث."
                        )
                    }
                }

                val responseBody = response.body?.string() ?: ""
                val json = JSONObject(responseBody)
                val resultsArray = json.optJSONArray("results") ?: org.json.JSONArray()

                val items = mutableListOf<SearchResultItem>()
                for (i in 0 until resultsArray.length()) {
                    val itemObj = resultsArray.getJSONObject(i)
                    items.add(
                        SearchResultItem(
                            title = itemObj.optString("title", "بدون عنوان"),
                            url = itemObj.optString("url", ""),
                            snippet = itemObj.optString("content", ""),
                            score = itemObj.optDouble("score", 0.0).toFloat()
                        )
                    )
                }

                val resultSet = SearchResultSet(
                    query = query.query,
                    items = items,
                    providerId = providerId,
                    totalAvailable = items.size,
                    isPartialOrDegraded = items.isEmpty()
                )

                if (items.isEmpty()) {
                    Outcome.Degraded(
                        partialValue = resultSet,
                        reason = DegradedReason.SEARCH_PROVIDER_PARTIAL,
                        diagnosticMessage = "لم يرجع البحث أي نتائج مطابقة.",
                        metadata = OutcomeMetadata(durationMs = duration, providerId = providerId)
                    )
                } else {
                    Outcome.Success(
                        value = resultSet,
                        metadata = OutcomeMetadata(durationMs = duration, providerId = providerId)
                    )
                }
            }
        } catch (e: Exception) {
            Outcome.Error(
                failure = SearchFailure.NetworkError(e.localizedMessage ?: "فشل الاتصال بـ Tavily Search API"),
                diagnosticMessage = e.message ?: "استثناء شبكي"
            )
        }
    }
}
