package com.example.domain.ports.provider

import com.example.domain.core.provider.ProviderService
import com.example.domain.core.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * ProviderServiceRepository — Phase 4 canonical port
 * ============================================================================
 */
interface ProviderServiceRepository {
    fun observeAllServices(): Flow<List<ProviderService>>

    suspend fun getServiceById(id: String): ProviderService?

    suspend fun getServicesForProvider(providerId: String): List<ProviderService>

    /** Insert-or-replace. Pure persistence — no network calls (Correction #10). */
    suspend fun saveService(service: ProviderService): Outcome<Unit, String>

    suspend fun deleteService(id: String): Outcome<Unit, String>

    suspend fun toggleService(id: String, isEnabled: Boolean): Outcome<Unit, String>
}
