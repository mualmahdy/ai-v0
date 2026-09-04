package com.example.domain.core.provider

/**
 * ServiceHealth — Phase 4
 * 
 * Health status record for a service at a point in time.
 */
data class ServiceHealth(
    val id: Long? = null,
    val serviceId: Long,
    val status: String,  // HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val lastCheckEpochMs: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis()
)
