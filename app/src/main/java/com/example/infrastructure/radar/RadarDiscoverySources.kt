package com.example.infrastructure.radar

import com.example.domain.core.Outcome
import com.example.domain.core.radar.ExtractedCapabilityProfile
import com.example.domain.core.radar.RadarCategory
import com.example.domain.core.radar.RadarItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Extensible Source Adapter for the Intelligence Radar.
 */
interface RadarSourcePort {
    val sourceName: String
    suspend fun fetchDiscoveries(): Outcome<List<RadarItem>, String>
}

/**
 * Real GitHub Releases Source fetching live open source releases for MCP, LLM runtimes, and Agent toolkits.
 */
class GitHubReleasesRadarSource(
    private val repositories: List<String> = listOf(
        "modelcontextprotocol/servers",
        "ollama/ollama",
        "firebase/firebase-android-sdk"
    ),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : RadarSourcePort {

    override val sourceName: String = "GitHub Open Source Releases"

    override suspend fun fetchDiscoveries(): Outcome<List<RadarItem>, String> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RadarItem>()
        try {
            for (repo in repositories) {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repo/releases?per_page=2")
                    .header("User-Agent", "AI-V0-Android-Radar")
                    .header("Accept", "application/vnd.github+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val array = JSONArray(body)
                        for (i in 0 until array.length()) {
                            val releaseObj = array.getJSONObject(i)
                            val tagName = releaseObj.optString("tag_name", "Release")
                            val name = releaseObj.optString("name", "$repo $tagName")
                            val desc = releaseObj.optString("body", "تحديث جديد لمستودع مفتوح المصدر").take(250)
                            val htmlUrl = releaseObj.optString("html_url", "https://github.com/$repo")

                            val isMcp = repo.contains("modelcontextprotocol", ignoreCase = true)
                            val category = if (isMcp) RadarCategory.MCP_ECOSYSTEM else RadarCategory.OPEN_SOURCE_REPO

                            items.add(
                                RadarItem(
                                    id = "gh_${repo.replace("/", "_")}_$tagName",
                                    title = "$repo: $name",
                                    summary = desc.ifBlank { "إصدار برمجي جديد $tagName للمستودع $repo" },
                                    category = category,
                                    sourceUrl = htmlUrl,
                                    sourceName = "GitHub ($repo)",
                                    relevanceScore = if (isMcp) 0.96f else 0.90f,
                                    extractedCapability = ExtractedCapabilityProfile(
                                        suggestedCapabilityType = if (isMcp) "TOOL_EXECUTION" else "LLM_GENERATION",
                                        suggestedIntegrationTarget = if (isMcp) "MCP_SERVER" else "LIBRARY",
                                        compatibilityScore = 0.95f,
                                        requiresCloudAuth = false,
                                        isOfflineCompatible = true,
                                        estimatedIntegrationRisk = "LOW"
                                    ),
                                    tags = listOf("GitHub", "Release", if (isMcp) "MCP" else "Runtime", "OpenSource")
                                )
                            )
                        }
                    }
                }
            }
            Outcome.Success(items)
        } catch (e: Exception) {
            Outcome.Error("تعذر الاتصال بمصدر GitHub: ${e.localizedMessage}")
        }
    }
}

/**
 * Real RSS/Atom XML Feed Source for AI & Research intelligence.
 */
class RssFeedRadarSource(
    private val feedUrls: List<Pair<String, String>> = listOf(
        "Google AI Blog" to "https://blog.google/technology/ai/rss/",
        "ArXiv CS.AI" to "https://rss.arxiv.org/rss/cs.AI"
    ),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : RadarSourcePort {

    override val sourceName: String = "AI Research & Industry RSS"

    override suspend fun fetchDiscoveries(): Outcome<List<RadarItem>, String> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RadarItem>()
        try {
            for ((sourceTitle, url) in feedUrls) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AI-V0-Android-Radar")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val xml = response.body?.string() ?: ""
                        val parsedItems = parseRssXml(xml, sourceTitle)
                        items.addAll(parsedItems)
                    }
                }
            }
            Outcome.Success(items)
        } catch (e: Exception) {
            Outcome.Error("تعذر قراءة خلاصات RSS: ${e.localizedMessage}")
        }
    }

    private fun parseRssXml(xml: String, sourceTitle: String): List<RadarItem> {
        val result = mutableListOf<RadarItem>()
        if (xml.isBlank()) return result

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var inItem = false
            var currentTitle = ""
            var currentLink = ""
            var currentDesc = ""

            while (eventType != XmlPullParser.END_DOCUMENT && result.size < 5) {
                val tagName = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true) || tagName.equals("entry", ignoreCase = true)) {
                            inItem = true
                            currentTitle = ""
                            currentLink = ""
                            currentDesc = ""
                        } else if (inItem) {
                            when (tagName.lowercase()) {
                                "title" -> currentTitle = parser.nextText().trim()
                                "link" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    currentLink = href ?: parser.nextText().trim()
                                }
                                "description", "summary" -> currentDesc = parser.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((tagName.equals("item", ignoreCase = true) || tagName.equals("entry", ignoreCase = true)) && inItem) {
                            inItem = false
                            if (currentTitle.isNotBlank()) {
                                val cleanDesc = currentDesc.replace(Regex("<.*?>"), "").take(200)
                                val isResearch = sourceTitle.contains("ArXiv", ignoreCase = true)
                                result.add(
                                    RadarItem(
                                        id = "rss_${UUID.randomUUID().toString().take(8)}",
                                        title = currentTitle,
                                        summary = cleanDesc.ifBlank { "تقرير استخباراتي مستكشف من $sourceTitle" },
                                        category = if (isResearch) RadarCategory.RESEARCH_PAPER else RadarCategory.MODEL_RELEASE,
                                        sourceUrl = currentLink.ifBlank { "https://ai.google.dev" },
                                        sourceName = sourceTitle,
                                        relevanceScore = if (isResearch) 0.89f else 0.94f,
                                        extractedCapability = ExtractedCapabilityProfile(
                                            suggestedCapabilityType = if (isResearch) "DECISION_INTELLIGENCE" else "LLM_GENERATION",
                                            suggestedIntegrationTarget = if (isResearch) "SKILL" else "MODEL",
                                            compatibilityScore = 0.92f,
                                            requiresCloudAuth = !isResearch,
                                            isOfflineCompatible = isResearch,
                                            estimatedIntegrationRisk = "LOW"
                                        ),
                                        tags = listOf("AI", if (isResearch) "Research" else "Industry", "Feed")
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Ignore parsing failures on partial XML
        }
        return result
    }
}
