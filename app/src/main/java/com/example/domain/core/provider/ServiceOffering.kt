package com.example.domain.core.provider

/**
 * ServiceOffering — Phase 4
 * 
 * Specific offering (e.g. model variant, API tier) provided by a service.
 */
data class ServiceOffering(
    val id: Long? = null,
    val serviceId: Long,
    val name: String,
    val description: String = "",
    val version: String = "1.0",
    val capabilities: Set<String> = emptySet(),
    val metadata: Map<String, Any> = emptyMap(),
    val isAvailable: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
