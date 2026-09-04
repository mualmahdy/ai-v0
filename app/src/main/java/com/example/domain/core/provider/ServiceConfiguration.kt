package com.example.domain.core.provider

/**
 * ServiceConfiguration — Phase 4
 * 
 * Configuration for a specific service instance (e.g., API credentials, model parameters).
 */
data class ServiceConfiguration(
    val id: Long? = null,
    val serviceId: Long,
    val configKey: String,
    val configValue: String,
    val isSecret: Boolean = false,
    val version: Int = 1,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
