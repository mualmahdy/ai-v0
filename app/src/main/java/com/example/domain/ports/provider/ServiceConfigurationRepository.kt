package com.example.domain.ports.provider

import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * ServiceConfigurationRepository — Phase 4 canonical port
 * ============================================================================
 *
 * Persistence for ServiceConfiguration. Per Correction #3 the implementation
 * bumps `configurationVersion` atomically on every update of an existing row
 * so decision records holding an older version fail resolution explicitly
 * instead of silently reusing changed credentials/endpoints.
 */
interface ServiceConfigurationRepository {
    fun observeAllConfigurations(): Flow<List<ServiceConfiguration>>

    suspend fun getConfigurationById(id: String): ServiceConfiguration?

    /** Latest (by updatedAtEpochMs) configuration bound to a service. */
    suspend fun getCurrentConfigurationForService(serviceId: String): ServiceConfiguration?

    /**
     * Insert-or-replace with monotonic version bump on update. Pure
     * persistence — no network calls (Correction #10).
     */
    suspend fun saveConfiguration(config: ServiceConfiguration): Outcome<Unit, String>

    suspend fun deleteConfiguration(id: String): Outcome<Unit, String>

    suspend fun toggleConfiguration(id: String, isEnabled: Boolean): Outcome<Unit, String>

    /** Cascade delete for a service. */
    suspend fun deleteConfigurationsForService(serviceId: String)
}
