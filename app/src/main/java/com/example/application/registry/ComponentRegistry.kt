package com.example.application.registry

import com.example.application.resource.DurableResourceRegistryService
import com.example.application.resource.RuntimeAdapterResolver
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityState
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.ResourceCapabilityGraph
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.ports.tools.ToolPort
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * ComponentRegistry — Phase 4 (refactored)
 * ============================================================================
 *
 * Per the architectural plan (Section 5): `ComponentRegistry` must NOT be a
 * second provider/resource authority. The authoritative external resource
 * lifecycle is `ResourceRegistryService` (now `DurableResourceRegistryService`).
 *
 * `ComponentRegistry` retains ONLY genuine in-process runtime components/
 * extensions: in-app tools, in-app agents, and the memory repository. It
 * no longer registers LLM/Search/Embedding providers — those are now
 * registered as `ResourceRecord`s by the `ProviderControlPlaneService` via
 * the `ResourceRecordRepository` → `DurableResourceRegistryService`.
 *
 * REMOVED (these were the legacy provider authority — Phase 4):
 *   - `registerLlmProvider` / `unregisterLlmProvider` / `getLlmProvider` /
 *     `listLlmProviders` / `setDefaultLlmProvider`
 *   - `registerSearchProvider` / `unregisterSearchProvider` /
 *     `getSearchProvider` / `listSearchProviders` / `setDefaultSearchProvider`
 *   - `registerEmbeddingProvider` / `unregisterEmbeddingProvider` /
 *     `getEmbeddingProvider` / `listEmbeddingProviders` /
 *     `setDefaultEmbeddingProvider`
 *   - `defaultLlmProviderId` / `defaultSearchProviderId` /
 *     `defaultEmbeddingProviderId`
 *
 * KEPT (genuine runtime extensions):
 *   - `registerTool` / `getTool` / `listTools` — for in-app tools like
 *     FileSystemTool, SafeDiagnosticsTool (NOT for MCP-discovered tools —
 *     those become ResourceRecords via the Control Plane).
 *   - `registerAgent` / `getAgent` / `listAgents` — for in-app agent definitions.
 *   - `registerMemoryRepository` / `getMemoryRepository` — for the RoomVectorStoreAdapter.
 *   - `recordFailure` / `recordSuccess` / failure-tracking maps — for the
 *     capability graph's reliability scoring.
 *   - `getCapabilityDescriptors` — emits descriptors for the in-app components
 *     (tools, agents, memory repository). The descriptors for provider-backed
 *     resources are derived from `ResourceRegistryService` via
 *     `ResourceCapabilityGraph` (not from this registry).
 *
 * Note: `registerTool` continues to create a `ResourceRecord` of type `TOOL`
 * for in-app tools (since they ARE genuine operational resources). This is
 * the in-process side of the architecture — MCP-discovered tools come in
 * through the Control Plane materialization flow.
 */
class ComponentRegistry(
    val resourceRegistry: DurableResourceRegistryService = DurableResourceRegistryService()
) {

    val resourceCapabilityGraph: ResourceCapabilityGraph = ResourceCapabilityGraph(resourceRegistry)
    val runtimeAdapterResolver: RuntimeAdapterResolver = RuntimeAdapterResolver(resourceRegistry)

    // --- In-app Tools ---
    private val tools = ConcurrentHashMap<String, ToolPort>()

    fun registerTool(tool: ToolPort) {
        val key = tool.declaration.name.lowercase()
        tools[key] = tool

        // In-app tools ARE genuine operational resources. Register them with the
        // authoritative ResourceRegistry. This is consistent with how MCP tools
        // are materialized via the Control Plane.
        val resId = ResourceId(key)
        val record = ResourceRecord(
            resourceId = resId,
            providerId = tool.declaration.name,
            serviceId = tool.declaration.name,
            resourceType = ResourceType.TOOL,
            capabilities = setOf(CapabilityType.TOOL_EXECUTION),
            configurationVersion = 1L,
            lifecycleState = ResourceLifecycleState.ENABLED,
            runtimeSupported = true,
            healthStatus = HealthStatus.HEALTHY,
            isLocal = true
        )
        resourceRegistry.registerResource(record)
        runtimeAdapterResolver.registerToolAdapter(resId, tool)
    }

    fun unregisterTool(toolName: String) {
        val key = toolName.lowercase()
        tools.remove(key)
        val resId = ResourceId(key)
        resourceRegistry.unregisterResource(resId)
        runtimeAdapterResolver.unregister(resId)
    }

    fun getTool(toolName: String): ToolPort? = tools[toolName.lowercase()]

    fun listTools(): List<ToolPort> = tools.values.toList()

    // --- Agents ---
    private val agents = ConcurrentHashMap<String, com.example.domain.core.agent.AgentDefinition>()

    fun registerAgent(agent: com.example.domain.core.agent.AgentDefinition) {
        agents[agent.identity.id.value.lowercase()] = agent
    }

    fun getAgent(agentId: String): com.example.domain.core.agent.AgentDefinition? =
        agents[agentId.lowercase()]

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
        val count = (failureCounts[key] ?: 0) + 1
        failureCounts[key] = count
        lastErrors[key] = error
        val resId = ResourceId(key)
        if (count >= 3) {
            resourceRegistry.updateHealth(resId, HealthStatus.UNAVAILABLE)
        } else if (count > 0) {
            resourceRegistry.updateHealth(resId, HealthStatus.DEGRADED)
        }
    }

    fun recordSuccess(resourceId: String) {
        val key = resourceId.lowercase()
        failureCounts[key] = 0
        lastErrors.remove(key)
        resourceRegistry.updateHealth(ResourceId(key), HealthStatus.HEALTHY)
    }

    fun getFailureCount(resourceId: String): Int = failureCounts[resourceId.lowercase()] ?: 0

    fun getLastError(resourceId: String): String? = lastErrors[resourceId.lowercase()]

    fun isResourceAvailable(resourceId: String): Boolean = getFailureCount(resourceId) < 3

    // --- Capability Descriptors (in-app components only) ---

    /**
     * Returns capability descriptors for in-app components (tools, agents, memory
     * repository). Provider-backed resources (LLM, Search, Embedding) are surfaced
     * via the `ResourceCapabilityGraph` derived from `ResourceRegistryService` —
     * NOT from this method.
     *
     * This method exists for backward compatibility with `CapabilityResourceGraph`
     * consumers that expect a flat list of descriptors. The CapabilityGraph itself
     * is the authoritative source — it derives its candidates from
     * `ResourceRegistryService.listResources()` and consults this method only
     * for in-app extensions that don't have a ResourceRecord (like agents and
     * the memory repository).
     */
    fun getCapabilityDescriptors(): List<CapabilityDescriptor> {
        val descriptors = mutableListOf<CapabilityDescriptor>()

        // 1. Memory Repository
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

        // 2. Tools (in-app only — MCP tools come in via ResourceRegistry)
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

        // 3. Agents
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
     * The graph derives its provider-backed resource candidates from the
     * authoritative ResourceRegistryService, then augments with in-app
     * extensions from this method.
     */
    fun getCapabilityResourceGraph(): com.example.domain.core.capability.CapabilityResourceGraph {
        return com.example.domain.core.capability.CapabilityResourceGraph(getCapabilityDescriptors())
    }
}
