package com.example.domain.core.provider

import com.example.domain.core.resource.ResourceId

/**
 * ResourceRecord — Phase 4
 * 
 * Persistent representation of an authorized, registered resource (LLM model,
 * search service, embedding provider, etc.) in the system.
 * 
 * ResourceRecords are authored by ProviderControlPlaneService via the
 * ServiceConfiguration-to-Resource pipeline and queried by
 * ResourceCapabilityGraph.findCandidatesByType() during decision-making.
 */
data class ResourceRecord(
    val id: Long? = null,
    val resourceId: ResourceId,
    val providerId: String,
    val serviceId: String,
    val resourceType: String,  // LLM, EMBEDDING, SEARCH, TOOL, etc.
    val protocolName: String,   // GEMINI, OPENAI_COMPATIBLE, OLLAMA, etc.
    val displayName: String,
    val description: String = "",
    val configurationVersion: Int = 1,
    val isActive: Boolean = true,
    val capabilities: Set<String> = emptySet(),
    val metadata: Map<String, Any> = emptyMap(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastValidatedEpochMs: Long? = null
)
