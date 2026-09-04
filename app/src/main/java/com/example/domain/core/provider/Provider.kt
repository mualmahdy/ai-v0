package com.example.domain.core.provider

/**
 * Provider — Phase 4
 * 
 * Represents a service provider (e.g., Google for Gemini, OpenAI, etc.).
 */
data class Provider(
    val id: Long? = null,
    val name: String,
    val description: String = "",
    val category: String,  // LLM, EMBEDDING, SEARCH, VECTOR_STORE
    val isEnabled: Boolean = true,
    val isHealthy: Boolean = true,
    val metadata: Map<String, Any> = emptyMap(),
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
