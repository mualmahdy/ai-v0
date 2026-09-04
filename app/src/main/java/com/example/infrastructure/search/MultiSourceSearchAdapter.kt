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
import com.example.domain.ports.storage.WorkspaceStoragePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Multi-Source Search Adapter:
 * 1. Primary: Official Tavily Search API (when API key is provided).
 * 2. Fallback: Public Instant Search / Wikipedia API (when no Tavily API key is provided).
 * 3. Offline/Workspace Fallback: Local Workspace files search.
 */
class MultiSourceSearchAdapter(
    private val tavilyApiKeyProvider: suspend () -> String? = { null },
    private val workspaceStoragePort: WorkspaceStoragePort? = null,
    private val defaultProjectId: Long = 1L,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : SearchProviderPort {

    override val providerId: String = "multi_source_search"

    override val metadata: SafeSearchProviderMetadata
        get() = SafeSearchProviderMetadata(
            id = providerId,
            name = "Unified Web & Workspace Search",
            providerType = "MULTI_SOURCE_API",
            isConfigured = true,
            isEnabled = true,
            priority = 1
        )

    override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> = withContext(Dispatchers.IO) {
        val tavilyKey = tavilyApiKeyProvider()
        val startTime = System.currentTimeMillis()

        // 1. Try Tavily Search API if key is available
        if (!tavilyKey.isNullOrBlank()) {
            try {
                val jsonBody = JSONObject().apply {
                    put("api_key", tavilyKey)
                    put("query", query.query)
                    put("max_results", query.maxResults.coerceIn(1, 10))
                    put("search_depth", "basic")
                    put("include_answer", true)
                }

                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val resultsArray = json.optJSONArray("results") ?: JSONArray()
                    val items = mutableListOf<SearchResultItem>()
                    for (i in 0 until resultsArray.length()) {
                        val obj = resultsArray.getJSONObject(i)
                        items.add(
                            SearchResultItem(
                                title = obj.optString("title", "نتيجة بحث"),
                                url = obj.optString("url", ""),
                                snippet = obj.optString("content", ""),
                                score = obj.optDouble("score", 0.9).toFloat()
                            )
                        )
                    }
                    val duration = System.currentTimeMillis() - startTime
                    return@withContext Outcome.Success(
                        value = SearchResultSet(
                            query = query.query,
                            items = items,
                            providerId = "tavily"
                        ),
                        metadata = OutcomeMetadata(durationMs = duration, providerId = "tavily")
                    )
                }
            } catch (_: Exception) {
                // Fallback to secondary source
            }
        }

        // 2. Secondary Fallback: Public Wikipedia / Open Knowledge Search
        try {
            val encodedQuery = URLEncoder.encode(query.query, "UTF-8")
            val wikiUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=5&namespace=0&format=json"
            val request = Request.Builder()
                .url(wikiUrl)
                .header("User-Agent", "AI-V0-Search/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                val json = JSONArray(body)
                if (json.length() >= 4) {
                    val titles = json.getJSONArray(1)
                    val snippets = json.getJSONArray(2)
                    val urls = json.getJSONArray(3)
                    val items = mutableListOf<SearchResultItem>()

                    for (i in 0 until titles.length()) {
                        items.add(
                            SearchResultItem(
                                title = titles.optString(i, "معرفة عامة"),
                                url = urls.optString(i, ""),
                                snippet = snippets.optString(i, ""),
                                score = 0.85f
                            )
                        )
                    }

                    if (items.isNotEmpty()) {
                        val duration = System.currentTimeMillis() - startTime
                        return@withContext Outcome.Success(
                            value = SearchResultSet(
                                query = query.query,
                                items = items,
                                providerId = "public_knowledge"
                            ),
                            metadata = OutcomeMetadata(durationMs = duration, providerId = "public_knowledge")
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to workspace search
        }

        // 3. Local Workspace Fallback
        if (workspaceStoragePort != null) {
            when (val files = workspaceStoragePort.listFiles(defaultProjectId)) {
                is Outcome.Success -> {
                    val matching = files.value.filter {
                        it.relativePath.contains(query.query, ignoreCase = true)
                    }
                    val items = matching.map { file ->
                        SearchResultItem(
                            title = "ملف محلي: ${file.relativePath}",
                            url = "workspace://${file.relativePath}",
                            snippet = "ملف في مساحة العمل (${file.sizeBytes} بايت)",
                            score = 0.90f
                        )
                    }
                    val duration = System.currentTimeMillis() - startTime
                    return@withContext Outcome.Degraded(
                        partialValue = SearchResultSet(
                            query = query.query,
                            items = items,
                            providerId = "local_workspace"
                        ),
                        reason = DegradedReason.CACHE_FALLBACK,
                        diagnosticMessage = "تم البحث محلياً في مساحة العمل بسبب عدم توفر مفتاح Tavily أو تعذر الاتصال بالشبكة.",
                        metadata = OutcomeMetadata(durationMs = duration, providerId = "local_workspace")
                    )
                }
                else -> Unit
            }
        }

        Outcome.Error(
            failure = SearchFailure.NetworkError("تعذر إتمام البحث عبر المصادر المتاحة."),
            diagnosticMessage = "تعذر تنفيذ عملية البحث."
        )
    }
}
