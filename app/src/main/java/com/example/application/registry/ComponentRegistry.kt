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

    fun unregisterLlmProvider(providerId: String) {
        val key = providerId.lowercase()
        llmProviders.remove(key)
        if (defaultLlmProviderId == key) {
            defaultLlmProviderId = llmProviders.keys.firstOrNull()
        }
    }

    fun setDefaultLlmProvider(providerId: String) {
        defaultLlmProviderId = providerId.lowercase()
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

    fun unregisterSearchProvider(providerId: String) {
        val key = providerId.lowercase()
        searchProviders.remove(key)
        if (defaultSearchProviderId == key) {
            defaultSearchProviderId = searchProviders.keys.firstOrNull()
        }
    }

    fun setDefaultSearchProvider(providerId: String) {
        defaultSearchProviderId = providerId.lowercase()
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

    fun unregisterEmbeddingProvider(providerId: String) {
        val key = providerId.lowercase()
        embeddingProviders.remove(key)
        if (defaultEmbeddingProviderId == key) {
            defaultEmbeddingProviderId = embeddingProviders.keys.firstOrNull()
        }
    }

    fun setDefaultEmbeddingProvider(providerId: String) {
        defaultEmbeddingProviderId = providerId.lowercase()
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

    // --- Capability Descriptors & Graph ---
    fun getCapabilityDescriptors(): List<CapabilityDescriptor> {
        val descriptors = mutableListOf<CapabilityDescriptor>()

        // 1. LLM Providers
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
                    resourceType = "MODEL",
                    isLocal = provider.metadata.isLocal,
                    attributes = mapOf("modelName" to (provider.metadata.defaultModel ?: "default"), "providerType" to provider.metadata.providerType)
                )
            )
            if (provider.metadata.supportedCapabilities.contains("streaming")) {
                descriptors.add(
                    CapabilityDescriptor(
                        type = CapabilityType.STREAMING,
                        state = state,
                        providerId = provider.providerId,
                        resourceType = "MODEL",
                        isLocal = provider.metadata.isLocal
                    )
                )
            }
            if (provider.metadata.supportedCapabilities.contains("vision")) {
                descriptors.add(
                    CapabilityDescriptor(
                        type = CapabilityType.VISION,
                        state = state,
                        providerId = provider.providerId,
                        resourceType = "MODEL",
                        isLocal = provider.metadata.isLocal
                    )
                )
            }
        }


        // 2. Search Providers
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
                    resourceType = "SEARCH_PROVIDER",
                    isLocal = false
                )
            )
        }

        // 3. Embedding Providers
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
                    resourceType = "EMBEDDING_PROVIDER",
                    isLocal = provider.metadata.isLocal
                )
            )
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.VECTOR_STORE,
                    state = state,
                    providerId = provider.providerId,
                    resourceType = "EMBEDDING_PROVIDER",
                    isLocal = provider.metadata.isLocal
                )
            )
        }

        // 4. Memory Repository
        memoryRepository?.let {
            descriptors.add(
                CapabilityDescriptor(
                    type = CapabilityType.MEMORY_RETRIEVAL,
                    state = CapabilityState.AVAILABLE,
                    providerId = "memory_repository",
                    resourceType = "STORAGE",
                    isLocal = true
                )
            )
        }

        // 5. Tools (extracting all structured provided capabilities!)
        tools.values.forEach { tool ->
            val decl = tool.declaration
            val failures = getFailureCount(decl.name)
            val state = when {
                failures >= 3 -> CapabilityState.UNAVAILABLE
                failures > 0 -> CapabilityState.DEGRADED
                else -> CapabilityState.AVAILABLE
            }
            val isLocal = decl.locality == com.example.domain.core.capability.Locality.LOCAL_ON_DEVICE

            for (capType in decl.providedCapabilities) {
                descriptors.add(
                    CapabilityDescriptor(
                        type = capType,
                        state = state,
                        providerId = decl.name,
                        resourceType = "TOOL",
                        isLocal = isLocal,
                        attributes = mapOf(
                            "description" to decl.description,
                            "networkRequirement" to decl.networkRequirement.name,
                            "sideEffects" to decl.sideEffects.name
                        )
                    )
                )
            }
        }

        // 6. Agents
        agents.values.forEach { agent ->
            if (agent.enabled) {
                for (capType in agent.allowedCapabilities) {
                    descriptors.add(
                        CapabilityDescriptor(
                            type = capType,
                            state = CapabilityState.AVAILABLE,
                            providerId = agent.identity.id.value,
                            resourceType = "AGENT",
                            isLocal = agent.locality == com.example.domain.core.capability.Locality.LOCAL_ON_DEVICE,
                            attributes = mapOf("role" to agent.identity.role.name)
                        )
                    )
                }
            }
        }

        return descriptors
    }

    /**
     * Builds and returns a live snapshot of the CapabilityResourceGraph.
     */
    fun getCapabilityResourceGraph(): com.example.domain.core.capability.CapabilityResourceGraph {
        return com.example.domain.core.capability.CapabilityResourceGraph(getCapabilityDescriptors())
    }
}

