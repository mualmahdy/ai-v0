package com.example.domain.core.provider

/**
 * ProviderService — Phase 4
 * 
 * A service (LLM endpoint, Embedding service, Search service) offered by a Provider.
 */
data class ProviderService(
    val id: Long? = null,
    val providerId: Long,
    val name: String,
    val description: String = "",
    val serviceType: String,  // LLM, EMBEDDING, SEARCH, VECTOR_STORE
    val protocolName: String,  // GEMINI, OPENAI_COMPATIBLE, OLLAMA, etc.
    val endpoint: String = "",
    val isEnabled: Boolean = true,
    val isHealthy: Boolean = true,
    val capabilities: Set<String> = emptySet(),
    val metadata: Map<String, Any> = emptyMap(),
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
