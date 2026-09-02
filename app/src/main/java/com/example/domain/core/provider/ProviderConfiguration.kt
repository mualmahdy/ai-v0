package com.example.domain.core.provider

/**
 * Functional categories of AI and knowledge providers.
 */
enum class ProviderCategory(val code: String, val displayName: String) {
    LLM("llm", "نماذج التوليد واللغة (LLM)"),
    EMBEDDING("embedding", "نماذج التضمين الدلالي (Embedding)"),
    SEARCH("search", "محركات البحث الشبكي (Web Search)"),
    VECTOR_STORE("vector_store", "مستودعات المتجهات (Vector Store)")
}

/**
 * Architectural flavors / protocols for provider adapters.
 */
enum class ProviderFlavor(val code: String, val displayName: String, val defaultEndpoint: String, val defaultModel: String) {
    GEMINI("gemini", "Google Gemini AI (Official SDK)", "https://generativelanguage.googleapis.com", "gemini-2.5-flash"),
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI-Compatible REST API", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    OLLAMA("ollama", "Ollama Local Daemon (Local Edge)", "http://127.0.0.1:11434/v1/chat/completions", "llama3.2"),
    TAVILY("tavily", "Tavily Search API", "https://api.tavily.com/search", ""),
    MULTI_SOURCE_SEARCH("multi_source_search", "Unified Web & Workspace Search", "", ""),
    LOCAL_EMBEDDING("local_embedding", "Built-in Local Embedding Engine", "local://memory", "dense-semantic-128")
}

/**
 * Real persistent configuration of a runtime provider entity.
 * NOTE: API keys and sensitive secrets are NEVER stored directly in this entity.
 */
data class ProviderConfiguration(
    val id: String,
    val name: String,
    val category: ProviderCategory,
    val flavor: ProviderFlavor,
    val endpointUrl: String = flavor.defaultEndpoint,
    val defaultModelId: String = flavor.defaultModel,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val lastValidatedEpochMs: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastErrorMessage: String? = null,
    val extraHeadersJson: String? = null,
    val timeoutSeconds: Int = 30,
    val hasSecretKey: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Result of executing real runtime health/ping validation against a provider.
 */
data class ProviderValidationResult(
    val isSuccess: Boolean,
    val health: HealthStatus,
    val latencyMs: Long,
    val message: String,
    val discoveredModelsCount: Int = 0
)
