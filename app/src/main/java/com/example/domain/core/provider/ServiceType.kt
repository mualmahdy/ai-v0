package com.example.domain.core.provider

/**
 * ServiceType — Phase 4
 * 
 * Enumeration of service types provided in the system.
 */
enum class ServiceType(val displayName: String) {
    LLM("Large Language Model"),
    EMBEDDING("Embedding Service"),
    SEARCH("Search Service"),
    VECTOR_STORE("Vector Store"),
    TOOL("Tool Service")
}
