package com.example.domain.ports.provider

import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import kotlinx.coroutines.flow.Flow

/**
 * Port for managing persistent provider configurations.
 */
interface ProviderRepositoryPort {

    /**
     * Continuous flow of all configured providers.
     */
    fun observeProviders(): Flow<List<ProviderConfiguration>>

    /**
     * Synchronous/one-shot snapshot of all configured providers.
     */
    suspend fun getAllProviders(): List<ProviderConfiguration>

    /**
     * Retrieves a single provider configuration by ID.
     */
    suspend fun getProviderById(id: String): ProviderConfiguration?

    /**
     * Saves or updates a provider configuration, optionally saving its secret API key.
     */
    suspend fun saveProvider(config: ProviderConfiguration, secretApiKey: String? = null): Outcome<Unit, String>

    /**
     * Deletes a provider configuration and removes its stored credentials.
     */
    suspend fun deleteProvider(id: String): Outcome<Unit, String>

    /**
     * Toggles the enabled state of a provider.
     */
    suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String>

    /**
     * Sets the specified provider as the default for its category.
     */
    suspend fun setAsDefaultProvider(id: String, category: ProviderCategory): Outcome<Unit, String>

    /**
     * Updates the health check results for a provider.
     */
    suspend fun updateProviderHealth(
        id: String,
        health: HealthStatus,
        lastValidatedMs: Long,
        latencyMs: Long,
        error: String? = null
    ): Outcome<Unit, String>

    /**
     * Retrieves the decrypted secret key for the provider if available.
     */
    suspend fun getSecretForProvider(id: String): String?
}
