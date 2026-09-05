package com.example.application.testing

import com.example.application.registry.ComponentRegistry
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort

/**
 * ============================================================================
 * TestResourceRegistration — Phase 4 test helper
 * ============================================================================
 *
 * Helper for tests that need to register mock LLM/Search/Embedding providers as
 * authoritative ResourceRecords in the new generalized architecture.
 *
 * Per Phase 4: the legacy `ComponentRegistry.registerLlmProvider(...)` etc.
 * methods are REMOVED. Tests must register via this helper which creates a
 * proper `ResourceRecord` (with stable ResourceId via `ResourceIdScheme`)
 * and registers the adapter via `RuntimeAdapterResolver`.
 *
 * Production code uses `ProviderControlPlaneService.materializeResource(...)` +
 * `validateResource(...)` to do the same thing through the user-facing flow.
 */
object TestResourceRegistration {

    /**
     * Register a mock LLM provider as an authoritative ResourceRecord of type
     * LLM with the given capabilities, lifecycle=ENABLED, runtimeSupported=true,
     * health=HEALTHY. The ResourceId is derived from the provider's `providerId`.
     */
    fun registerLlmProvider(
        registry: ComponentRegistry,
        provider: LlmProviderPort,
        capabilities: Set<CapabilityType> = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING),
        isLocal: Boolean = provider.metadata.isLocal
    ): ResourceId {
        val resourceId = ResourceId(provider.providerId.lowercase())
        val record = ResourceRecord(
            resourceId = resourceId,
            providerId = provider.providerId,
            serviceId = provider.metadata.defaultModel ?: "test-service",
            resourceType = ResourceType.LLM,
            capabilities = capabilities,
            configurationVersion = 1L,
            lifecycleState = ResourceLifecycleState.ENABLED,
            runtimeSupported = true,
            healthStatus = HealthStatus.HEALTHY,
            isLocal = isLocal
        )
        registry.resourceRegistry.registerResource(record)
        registry.runtimeAdapterResolver.registerLlmAdapter(resourceId, provider)
        return resourceId
    }

    /**
     * Register a mock Search provider as an authoritative ResourceRecord of type
     * SEARCH.
     */
    fun registerSearchProvider(
        registry: ComponentRegistry,
        provider: SearchProviderPort,
        isLocal: Boolean = false
    ): ResourceId {
        val resourceId = ResourceId(provider.providerId.lowercase())
        val record = ResourceRecord(
            resourceId = resourceId,
            providerId = provider.providerId,
            serviceId = "${provider.providerId}-search-service",
            resourceType = ResourceType.SEARCH,
            capabilities = setOf(CapabilityType.SEARCH),
            configurationVersion = 1L,
            lifecycleState = ResourceLifecycleState.ENABLED,
            runtimeSupported = true,
            healthStatus = HealthStatus.HEALTHY,
            isLocal = isLocal
        )
        registry.resourceRegistry.registerResource(record)
        registry.runtimeAdapterResolver.registerSearchAdapter(resourceId, provider)
        return resourceId
    }

    /**
     * Register a mock Embedding provider as an authoritative ResourceRecord of type
     * EMBEDDING.
     */
    fun registerEmbeddingProvider(
        registry: ComponentRegistry,
        provider: EmbeddingProviderPort,
        isLocal: Boolean = provider.metadata.isLocal
    ): ResourceId {
        val resourceId = ResourceId(provider.providerId.lowercase())
        val record = ResourceRecord(
            resourceId = resourceId,
            providerId = provider.providerId,
            serviceId = "${provider.providerId}-embedding-service",
            resourceType = ResourceType.EMBEDDING,
            capabilities = setOf(CapabilityType.EMBEDDING, CapabilityType.MEMORY_RETRIEVAL),
            configurationVersion = 1L,
            lifecycleState = ResourceLifecycleState.ENABLED,
            runtimeSupported = true,
            healthStatus = HealthStatus.HEALTHY,
            isLocal = isLocal
        )
        registry.resourceRegistry.registerResource(record)
        registry.runtimeAdapterResolver.registerEmbeddingAdapter(resourceId, provider)
        return resourceId
    }
}
