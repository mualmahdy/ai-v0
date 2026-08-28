package com.example.application.provider

import com.example.domain.core.Outcome
import com.example.domain.core.model.Modality
import com.example.domain.core.model.ModelDescriptor
import com.example.domain.core.model.TriStateCapability
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderDescriptor
import com.example.domain.core.provider.ProviderType
import com.example.domain.ports.provider.ModelDiscoveryPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Open-ended Provider and Model Registry service implementing automatic discovery and capability matrix.
 */
class ProviderRegistryService {

    private val discoveryAdapters = mutableMapOf<String, ModelDiscoveryPort>()

    private val _registeredProviders = MutableStateFlow<List<ProviderDescriptor>>(emptyList())
    val registeredProviders: StateFlow<List<ProviderDescriptor>> = _registeredProviders.asStateFlow()

    private val _discoveredModels = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val discoveredModels: StateFlow<List<ModelDescriptor>> = _discoveredModels.asStateFlow()

    fun registerDiscoveryAdapter(adapter: ModelDiscoveryPort) {
        discoveryAdapters[adapter.providerId] = adapter
    }

    suspend fun discoverAllProvidersAndModels(): List<ModelDescriptor> {
        val allModels = mutableListOf<ModelDescriptor>()
        val providerList = mutableListOf<ProviderDescriptor>()

        for ((_, adapter) in discoveryAdapters) {
            when (val healthOutcome = adapter.checkHealth()) {
                is Outcome.Success -> providerList.add(healthOutcome.value)
                is Outcome.Degraded -> healthOutcome.partialValue?.let { providerList.add(it) }
                is Outcome.Error -> {
                    providerList.add(
                        ProviderDescriptor(
                            id = adapter.providerId,
                            name = adapter.providerId,
                            type = ProviderType.LLM,
                            isConfigured = false,
                            isLocal = false,
                            health = HealthStatus.UNAVAILABLE
                        )
                    )
                }
            }

            when (val discoveryOutcome = adapter.discoverModels()) {
                is Outcome.Success -> allModels.addAll(discoveryOutcome.value)
                is Outcome.Degraded -> discoveryOutcome.partialValue?.let { allModels.addAll(it) }
                is Outcome.Error -> Unit
            }
        }

        _registeredProviders.update { providerList }
        _discoveredModels.update { allModels }
        return allModels
    }

    /**
     * Finds matching candidate models based on task constraints and network policy.
     */
    fun findCandidateModels(
        requiresVision: Boolean = false,
        requiresToolCalling: Boolean = false,
        requiresReasoning: Boolean = false,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID
    ): List<ModelDescriptor> {
        val currentModels = _discoveredModels.value
        return currentModels.filter { model ->
            // Network policy filter
            if (networkPolicy == NetworkPolicy.OFFLINE && !model.isLocalOnDevice) {
                return@filter false
            }

            // Vision requirement
            if (requiresVision && model.supportsVision == TriStateCapability.UNSUPPORTED) {
                return@filter false
            }

            // Tool calling requirement
            if (requiresToolCalling && model.supportsToolCalling == TriStateCapability.UNSUPPORTED) {
                return@filter false
            }

            // Reasoning requirement
            if (requiresReasoning && model.supportsReasoning == TriStateCapability.UNSUPPORTED) {
                return@filter false
            }

            true
        }.sortedByDescending { model ->
            // Preference scoring: local preferred if local_first, healthy preferred
            var score = 0
            if (model.health == HealthStatus.HEALTHY) score += 50
            if (networkPolicy == NetworkPolicy.LOCAL_FIRST && model.isLocalOnDevice) score += 30
            if (model.supportsReasoning == TriStateCapability.SUPPORTED) score += 20
            score
        }
    }
}
