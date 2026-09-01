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

    // --- Agents ---
    private val agents = ConcurrentHashMap<String, com.example.domain.core.agent.AgentDefinition>()

    fun registerAgent(agent: com.example.domain.core.agent.AgentDefinition) {
        agents[agent.identity.id.value.lowercase()] = agent
    }

    fun getAgent(agentId: String): com.example.domain.core.agent.AgentDefinition? = agents[agentId.lowercase()]

    fun listAgents(): List<com.example.domain.core.agent.AgentDefinition> = agents.values.toList()

    // --- Memory Repository ---
    private var memoryRepository: com.example.domain.ports.memory.MemoryRepositoryPort? = null

    fun registerMemoryRepository(repository: com.example.domain.ports.memory.MemoryRepositoryPort) {
        memoryRepository = repository
    }

    fun getMemoryRepository(): com.example.domain.ports.memory.MemoryRepositoryPort? = memoryRepository

    // --- Resource Health & Failure Tracking ---
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val lastErrors = ConcurrentHashMap<String, String>()

    fun recordFailure(resourceId: String, error: String) {
        val key = resourceId.lowercase()
        failureCounts[key] = (failureCounts[key] ?: 0) + 1
        lastErrors[key] = error
    }

    fun recordSuccess(resourceId: String) {
        val key = resourceId.lowercase()
        failureCounts[key] = 0
        lastErrors.remove(key)
    }

    fun getFailureCount(resourceId: String): Int = failureCounts[resourceId.lowercase()] ?: 0

    fun getLastError(resourceId: String): String? = lastErrors[resourceId.lowercase()]

    fun isResourceAvailable(resourceId: String): Boolean = getFailureCount(resourceId) < 3

    // --- Capability Descriptors ---
    fun getCapabilityDescriptors(): List<CapabilityDescriptor> {
        val descriptors = mutableListOf<CapabilityDescriptor>()

        llmProviders.values.forEach { provider ->
            val failures = getFailureCount(provider.providerId)
            val state = when {
                !provider.metadata.isOnline || failures >= 3 -> CapabilityState.UNAVAILABLE
                failures > 0 -> CapabilityState.DEGRADED
                else -> CapabilityState.AVAILABLE
            }
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.LLM_GENERATION,
                    state = state,
                    providerId = provider.providerId,
                    isLocal = provider.metadata.isLocal
                )
            )
        }

        searchProviders.values.forEach { provider ->
            val failures = getFailureCount(provider.providerId)
            val state = when {
                !provider.metadata.isConfigured || failures >= 3 -> CapabilityState.UNAVAILABLE
                failures > 0 -> CapabilityState.DEGRADED
                else -> CapabilityState.AVAILABLE
            }
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.SEARCH,
                    state = state,
                    providerId = provider.providerId,
                    isLocal = false
                )
            )
        }

        embeddingProviders.values.forEach { provider ->
            val failures = getFailureCount(provider.providerId)
            val state = when {
                !provider.metadata.isEnabled || failures >= 3 -> CapabilityState.UNAVAILABLE
                failures > 0 -> CapabilityState.DEGRADED
                else -> CapabilityState.AVAILABLE
            }
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.EMBEDDING,
                    state = state,
                    providerId = provider.providerId,
                    isLocal = provider.metadata.isLocal
                )
            )
        }

        tools.values.forEach { tool ->
            val failures = getFailureCount(tool.declaration.name)
            val state = when {
                failures >= 3 -> CapabilityState.UNAVAILABLE
                failures > 0 -> CapabilityState.DEGRADED
                else -> CapabilityState.AVAILABLE
            }
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.TOOL_EXECUTION,
                    state = state,
                    providerId = tool.declaration.name,
                    isLocal = true,
                    attributes = mapOf("description" to tool.declaration.description)
                )
            )
        }

        return descriptors
    }
}
