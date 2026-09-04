package com.example.domain.ports.provider

import com.example.domain.core.provider.ServiceHealthRecord

/**
 * ============================================================================
 * ServiceHealthRepository — Phase 4 canonical port (Correction #2)
 * ============================================================================
 *
 * Durable health history for ServiceConfigurations. Every explicit connection
 * test / resource validation writes a record here so health evidence survives
 * restarts and can be audited (the UI shows the latest snapshot).
 */
interface ServiceHealthRepository {
    /** Insert (history append). */
    suspend fun saveHealthRecord(record: ServiceHealthRecord)

    suspend fun getLatestForConfiguration(serviceConfigurationId: String): ServiceHealthRecord?

    suspend fun getHistoryForConfiguration(
        serviceConfigurationId: String,
        limit: Int = 20
    ): List<ServiceHealthRecord>
}
