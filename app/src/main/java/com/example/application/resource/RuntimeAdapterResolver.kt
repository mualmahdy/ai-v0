package com.example.application.resource

import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceResolutionFailure
import com.example.domain.core.resource.ResourceType
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.domain.ports.tools.ToolPort
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative runtime adapter resolver.
 *
 * Resolves exact ResourceId + configurationVersion combinations into concrete execution adapters.
 * Validates resource existence, configuration version freshness, lifecycle state, runtime support,
 * and health.
 *
 * Strictly rejects any silent substitutions or fallback reinterpretation at resolution time.
 */
class RuntimeAdapterResolver(
    private val resourceRegistry: ResourceRegistryService
) {
    private val llmAdapters = ConcurrentHashMap<ResourceId, LlmProviderPort>()
    private val searchAdapters = ConcurrentHashMap<ResourceId, SearchProviderPort>()
    private val embeddingAdapters = ConcurrentHashMap<ResourceId, EmbeddingProviderPort>()
    private val toolAdapters = ConcurrentHashMap<ResourceId, ToolPort>()

    fun registerLlmAdapter(resourceId: ResourceId, adapter: LlmProviderPort) {
        llmAdapters[resourceId] = adapter
    }

    fun registerSearchAdapter(resourceId: ResourceId, adapter: SearchProviderPort) {
        searchAdapters[resourceId] = adapter
    }

    fun registerEmbeddingAdapter(resourceId: ResourceId, adapter: EmbeddingProviderPort) {
        embeddingAdapters[resourceId] = adapter
    }

    fun registerToolAdapter(resourceId: ResourceId, adapter: ToolPort) {
        toolAdapters[resourceId] = adapter
    }

    fun unregister(resourceId: ResourceId) {
        llmAdapters.remove(resourceId)
        searchAdapters.remove(resourceId)
        embeddingAdapters.remove(resourceId)
        toolAdapters.remove(resourceId)
    }

    /**
     * FIX F-7 (audit c03919d): exposes the declarations of every registered tool
     * adapter so the LLM prompt can advertise real callable tools to the model
     * (tool-calling / delegation loop). Previously the ExecutionService built an
     * always-empty tool list, so models could never request a tool.
     */
    fun listToolDeclarations(): List<com.example.domain.core.tools.ToolDeclaration> =
        toolAdapters.values.map { it.declaration }

    /** Number of currently registered LLM adapters (observability / diagnostics). */
    fun registeredAdapterCount(): Int =
        llmAdapters.size + searchAdapters.size + embeddingAdapters.size + toolAdapters.size

    fun resolveLlmAdapter(
        resourceId: ResourceId,
        expectedVersion: Long? = null
    ): Outcome<LlmProviderPort, ResourceResolutionFailure> {
        val validation = validateResourceRecord(resourceId, expectedVersion, ResourceType.LLM)
        if (validation is Outcome.Error) return validation

        val adapter = llmAdapters[resourceId]
            ?: return Outcome.Error(
                ResourceResolutionFailure.AdapterNotFound("No LLM runtime adapter registered for ResourceId: $resourceId")
            )
        return Outcome.Success(adapter)
    }

    fun resolveSearchAdapter(
        resourceId: ResourceId,
        expectedVersion: Long? = null
    ): Outcome<SearchProviderPort, ResourceResolutionFailure> {
        val validation = validateResourceRecord(resourceId, expectedVersion, ResourceType.SEARCH)
        if (validation is Outcome.Error) return validation

        val adapter = searchAdapters[resourceId]
            ?: return Outcome.Error(
                ResourceResolutionFailure.AdapterNotFound("No Search runtime adapter registered for ResourceId: $resourceId")
            )
        return Outcome.Success(adapter)
    }

    fun resolveEmbeddingAdapter(
        resourceId: ResourceId,
        expectedVersion: Long? = null
    ): Outcome<EmbeddingProviderPort, ResourceResolutionFailure> {
        val validation = validateResourceRecord(resourceId, expectedVersion, ResourceType.EMBEDDING)
        if (validation is Outcome.Error) return validation

        val adapter = embeddingAdapters[resourceId]
            ?: return Outcome.Error(
                ResourceResolutionFailure.AdapterNotFound("No Embedding runtime adapter registered for ResourceId: $resourceId")
            )
        return Outcome.Success(adapter)
    }

    fun resolveToolAdapter(
        resourceId: ResourceId,
        expectedVersion: Long? = null
    ): Outcome<ToolPort, ResourceResolutionFailure> {
        val validation = validateResourceRecord(resourceId, expectedVersion, ResourceType.TOOL)
        if (validation is Outcome.Error) return validation

        val adapter = toolAdapters[resourceId]
            ?: return Outcome.Error(
                ResourceResolutionFailure.AdapterNotFound("No Tool runtime adapter registered for ResourceId: $resourceId")
            )
        return Outcome.Success(adapter)
    }

    private fun validateResourceRecord(
        resourceId: ResourceId,
        expectedVersion: Long?,
        expectedType: ResourceType
    ): Outcome<ResourceRecord, ResourceResolutionFailure> {
        val record = resourceRegistry.getResource(resourceId)
            ?: return Outcome.Error(
                ResourceResolutionFailure.InvalidResourceId("Authoritative resource '$resourceId' not found in ResourceRegistry")
            )

        if (record.resourceType != expectedType) {
            return Outcome.Error(
                ResourceResolutionFailure.ExecutionFailed(
                    "Resource '$resourceId' is of type ${record.resourceType}, but execution expected $expectedType"
                )
            )
        }

        if (expectedVersion != null && record.configurationVersion != expectedVersion) {
            return Outcome.Error(
                ResourceResolutionFailure.StaleConfigurationVersion(
                    "Stale configuration version for '$resourceId': expected $expectedVersion, authoritative is ${record.configurationVersion}"
                )
            )
        }

        if (record.lifecycleState != ResourceLifecycleState.ENABLED &&
            record.lifecycleState != ResourceLifecycleState.ACTIVE
        ) {
            return Outcome.Error(
                ResourceResolutionFailure.ResourceDisabled("Resource '$resourceId' is not active (state: ${record.lifecycleState})")
            )
        }

        if (!record.runtimeSupported) {
            return Outcome.Error(
                ResourceResolutionFailure.RuntimeUnsupported("Resource '$resourceId' is not supported in the active runtime")
            )
        }

        if (record.healthStatus == HealthStatus.UNAVAILABLE) {
            return Outcome.Error(
                ResourceResolutionFailure.ResourceUnavailable("Resource '$resourceId' is currently UNAVAILABLE")
            )
        }

        return Outcome.Success(record)
    }
}
