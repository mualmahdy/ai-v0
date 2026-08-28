package com.example.application.registry

import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityState
import com.example.domain.core.capability.CapabilityType
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.domain.ports.tools.ToolPort
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe Registry for system capabilities, providers, and tools.
 *
 * Implements clean decoupling: Providers are registered in the Composition Root,
 * while Application/Orchestrator resolves them by ID or capability type without concrete coupling.
 */
class ComponentRegistry {

    private val llmProviders = ConcurrentHashMap<String, LlmProviderPort>()
    private val searchProviders = ConcurrentHashMap<String, SearchProviderPort>()
    private val embeddingProviders = ConcurrentHashMap<String, EmbeddingProviderPort>()
    private val tools = ConcurrentHashMap<String, ToolPort>()

    private var defaultLlmProviderId: String? = null
    private var defaultSearchProviderId: String? = null
    private var defaultEmbeddingProviderId: String? = null

    // --- LLM Providers ---
    fun registerLlmProvider(provider: LlmProviderPort, isDefault: Boolean = false) {
        val key = provider.providerId.lowercase()
        llmProviders[key] = provider
        if (isDefault || defaultLlmProviderId == null) {
            defaultLlmProviderId = key
        }
    }

    fun getLlmProvider(providerId: String? = null): LlmProviderPort? {
        val targetId = providerId?.lowercase() ?: defaultLlmProviderId
        return targetId?.let { llmProviders[it] }
    }

    fun listLlmProviders(): List<LlmProviderPort> = llmProviders.values.toList()

    // --- Search Providers ---
    fun registerSearchProvider(provider: SearchProviderPort, isDefault: Boolean = false) {
        val key = provider.providerId.lowercase()
        searchProviders[key] = provider
        if (isDefault || defaultSearchProviderId == null) {
            defaultSearchProviderId = key
        }
    }

    fun getSearchProvider(providerId: String? = null): SearchProviderPort? {
        val targetId = providerId?.lowercase() ?: defaultSearchProviderId
        return targetId?.let { searchProviders[it] }
    }

    fun listSearchProviders(): List<SearchProviderPort> = searchProviders.values.toList()

    // --- Embedding Providers ---
    fun registerEmbeddingProvider(provider: EmbeddingProviderPort, isDefault: Boolean = false) {
        val key = provider.providerId.lowercase()
        embeddingProviders[key] = provider
        if (isDefault || defaultEmbeddingProviderId == null) {
            defaultEmbeddingProviderId = key
        }
    }

    fun getEmbeddingProvider(providerId: String? = null): EmbeddingProviderPort? {
        val targetId = providerId?.lowercase() ?: defaultEmbeddingProviderId
        return targetId?.let { embeddingProviders[it] }
    }

    fun listEmbeddingProviders(): List<EmbeddingProviderPort> = embeddingProviders.values.toList()

    // --- Tools ---
    fun registerTool(tool: ToolPort) {
        tools[tool.declaration.name.lowercase()] = tool
    }

    fun getTool(toolName: String): ToolPort? = tools[toolName.lowercase()]

    fun listTools(): List<ToolPort> = tools.values.toList()

    // --- Memory Repository ---
    private var memoryRepository: com.example.domain.ports.memory.MemoryRepositoryPort? = null

    fun registerMemoryRepository(repository: com.example.domain.ports.memory.MemoryRepositoryPort) {
        memoryRepository = repository
    }

    fun getMemoryRepository(): com.example.domain.ports.memory.MemoryRepositoryPort? = memoryRepository

    // --- Capability Descriptors ---
    fun getCapabilityDescriptors(): List<CapabilityDescriptor> {
        val descriptors = mutableListOf<CapabilityDescriptor>()

        llmProviders.values.forEach { provider ->
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.LLM_GENERATION,
                    state = if (provider.metadata.isOnline) CapabilityState.AVAILABLE else CapabilityState.DEGRADED,
                    providerId = provider.providerId,
                    isLocal = provider.metadata.isLocal
                )
            )
        }

        searchProviders.values.forEach { provider ->
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.SEARCH,
                    state = if (provider.metadata.isConfigured) CapabilityState.AVAILABLE else CapabilityState.DEGRADED,
                    providerId = provider.providerId,
                    isLocal = false
                )
            )
        }

        embeddingProviders.values.forEach { provider ->
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.EMBEDDING,
                    state = if (provider.metadata.isEnabled) CapabilityState.AVAILABLE else CapabilityState.DEGRADED,
                    providerId = provider.providerId,
                    isLocal = provider.metadata.isLocal
                )
            )
        }

        tools.values.forEach { tool ->
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.TOOL_EXECUTION,
                    state = CapabilityState.AVAILABLE,
                    providerId = tool.declaration.name,
                    isLocal = true,
                    attributes = mapOf("description" to tool.declaration.description)
                )
            )
        }

        return descriptors
    }
}
