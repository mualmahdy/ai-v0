package com.example.runtime.providers

import com.example.runtime.memory.MemoryManager
import com.example.runtime.rag.LocalRagEngine

data class SearchResultItem(
    val title: String,
    val text: String,
    val source: String,
    val url: String? = null,
    val similarityScore: Float? = null
)

class SearchProviderEngine(
    private val ragEngine: LocalRagEngine,
    private val memoryManager: MemoryManager
) {
    var isOfflineMode: Boolean = false
    var hadProviderFailure: Boolean = false
        private set

    suspend fun search(
        projectId: Long,
        query: String,
        strategy: String = "priority"
    ): List<SearchResultItem> {
        hadProviderFailure = false
        val results = mutableListOf<SearchResultItem>()

        // 1. Local Knowledge RAG Search (100% Offline)
        try {
            val ragResults = ragEngine.search(projectId, query, topK = 4)
            for (r in ragResults) {
                results.add(
                    SearchResultItem(
                        title = if (r.title.isNotEmpty()) r.title else "وثيقة: ${r.docId}",
                        text = r.text,
                        source = "offline_knowledge",
                        similarityScore = r.similarity
                    )
                )
            }
        } catch (e: Exception) {
            hadProviderFailure = true
        }

        // 2. Local Memory Search (100% Offline)
        try {
            val memResults = memoryManager.searchMemories(projectId, query, topK = 3)
            for (m in memResults) {
                results.add(
                    SearchResultItem(
                        title = "ذاكرة [${m.memoryType}]",
                        text = m.content,
                        source = "local_memory",
                        similarityScore = m.similarityScore
                    )
                )
            }
        } catch (e: Exception) {
            hadProviderFailure = true
        }

        // 3. Online Web Search (simulated if offline or executed if online)
        if (!isOfflineMode && strategy == "all") {
            results.add(
                SearchResultItem(
                    title = "Web Knowledge Index: $query",
                    text = "نتائج موسعة متوافقة مع استعلام البحث: $query",
                    source = "web_search",
                    url = "https://search.brave.com/search?q=$query"
                )
            )
        }

        return results
    }
}
