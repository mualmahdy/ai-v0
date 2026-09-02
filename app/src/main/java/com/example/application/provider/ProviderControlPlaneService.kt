package com.example.application.provider

import com.example.application.registry.ComponentRegistry
import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderValidationResult
import com.example.domain.ports.provider.ProviderRepositoryPort
import com.example.infrastructure.provider.ProviderAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider & Resource Management Control Plane Service.
 *
 * Bridges persistent Provider Configurations (Room) to live runtime ports in ComponentRegistry.
 * Manages runtime lifecycle: ADD -> CONFIGURE -> VALIDATE -> ENABLE -> REGISTER -> USE -> MONITOR -> DISABLE -> REMOVE.
 */
class ProviderControlPlaneService(
    private val providerRepository: ProviderRepositoryPort,
    private val adapterFactory: ProviderAdapterFactory,
    private val componentRegistry: ComponentRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) {

    val allProvidersFlow: Flow<List<ProviderConfiguration>> = providerRepository.observeProviders()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var syncJob: Job? = null

    fun initialize() {
        if (syncJob != null) return
        syncJob = scope.launch {
            allProvidersFlow.collectLatest { providers ->
                syncRegistryWithConfigurations(providers)
            }
        }
    }

    private suspend fun syncRegistryWithConfigurations(configs: List<ProviderConfiguration>) = withContext(Dispatchers.Default) {
        _isSyncing.value = true
        try {
            val configuredIds = configs.map { it.id.lowercase() }.toSet()

            // 1. Process each configuration
            for (config in configs) {
                if (config.isEnabled) {
                    when (config.category) {
                        ProviderCategory.LLM -> {
                            val adapter = adapterFactory.createLlmAdapter(config) {
                                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                            }
                            componentRegistry.registerLlmProvider(adapter, isDefault = config.isDefault)
                            if (config.isDefault) {
                                componentRegistry.setDefaultLlmProvider(config.id)
                            }
                        }
                        ProviderCategory.SEARCH -> {
                            val adapter = adapterFactory.createSearchAdapter(config) {
                                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                            }
                            componentRegistry.registerSearchProvider(adapter, isDefault = config.isDefault)
                            if (config.isDefault) {
                                componentRegistry.setDefaultSearchProvider(config.id)
                            }
                        }
                        ProviderCategory.EMBEDDING -> {
                            val adapter = adapterFactory.createEmbeddingAdapter(config) {
                                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                            }
                            componentRegistry.registerEmbeddingProvider(adapter, isDefault = config.isDefault)
                            if (config.isDefault) {
                                componentRegistry.setDefaultEmbeddingProvider(config.id)
                            }
                        }
                        else -> Unit
                    }
                } else {
                    // Disabled: unregister from runtime
                    when (config.category) {
                        ProviderCategory.LLM -> componentRegistry.unregisterLlmProvider(config.id)
                        ProviderCategory.SEARCH -> componentRegistry.unregisterSearchProvider(config.id)
                        ProviderCategory.EMBEDDING -> componentRegistry.unregisterEmbeddingProvider(config.id)
                        else -> Unit
                    }
                }
            }

            // 2. Clean up any runtime instances whose config was deleted
            componentRegistry.listLlmProviders().forEach { llm ->
                if (!configuredIds.contains(llm.providerId.lowercase())) {
                    componentRegistry.unregisterLlmProvider(llm.providerId)
                }
            }
            componentRegistry.listSearchProviders().forEach { search ->
                if (!configuredIds.contains(search.providerId.lowercase())) {
                    componentRegistry.unregisterSearchProvider(search.providerId)
                }
            }
            componentRegistry.listEmbeddingProviders().forEach { emb ->
                if (!configuredIds.contains(emb.providerId.lowercase())) {
                    componentRegistry.unregisterEmbeddingProvider(emb.providerId)
                }
            }
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Executes real health and connection validation for a provider and updates persistence.
     */
    suspend fun validateAndRecordHealth(providerId: String): Outcome<ProviderValidationResult, String> {
        val config = providerRepository.getProviderById(providerId)
            ?: return Outcome.Error("المزود غير موجود بالمعرف: $providerId")

        val secret = providerRepository.getSecretForProvider(providerId)
        val resultOutcome = adapterFactory.validateProvider(config, secret)

        if (resultOutcome is Outcome.Success) {
            val result = resultOutcome.value
            providerRepository.updateProviderHealth(
                id = providerId,
                health = result.health,
                lastValidatedMs = System.currentTimeMillis(),
                latencyMs = result.latencyMs,
                error = if (result.isSuccess) null else result.message
            )
        }
        return resultOutcome
    }

    suspend fun saveProvider(config: ProviderConfiguration, secretApiKey: String?): Outcome<Unit, String> {
        return providerRepository.saveProvider(config, secretApiKey)
    }

    suspend fun deleteProvider(id: String): Outcome<Unit, String> {
        return providerRepository.deleteProvider(id)
    }

    suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String> {
        return providerRepository.toggleProvider(id, isEnabled)
    }

    suspend fun setAsDefaultProvider(id: String, category: ProviderCategory): Outcome<Unit, String> {
        return providerRepository.setAsDefaultProvider(id, category)
    }
}
