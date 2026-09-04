package com.example.domain.ports.provider

import com.example.domain.core.provider.Provider
import com.example.domain.core.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * ProviderRepository — Phase 4 canonical port (rich String-id model)
 * ============================================================================
 */
interface ProviderRepository {
    /** Continuous flow of all providers (UI rendering). */
    fun observeProviders(): Flow<List<Provider>>

    suspend fun getProviderById(id: String): Provider?

    /** Insert-or-replace. Pure persistence — no network calls (Correction #10). */
    suspend fun saveProvider(provider: Provider): Outcome<Unit, String>

    suspend fun deleteProvider(id: String): Outcome<Unit, String>

    suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String>
}
