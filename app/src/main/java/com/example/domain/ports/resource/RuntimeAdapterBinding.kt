package com.example.domain.ports.resource

import com.example.domain.core.resource.ConfigurationVersion
import com.example.domain.core.resource.ResourceId
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort

/**
 * P0.5 — Runtime adapter binding (APPROVED-BASELINE v2.1, Section C binding chain /
 * Section J).
 *
 * A RuntimeBinding is an adapter bound to a specific configurationVersion. It is
 * EPHEMERAL (Section C): process restart, config change, or adapter recreation
 * invalidates it.
 */
sealed class RuntimeAdapterBinding {
    abstract val resourceId: ResourceId
    abstract val configurationVersion: ConfigurationVersion

    data class Llm(
        val port: LlmProviderPort,
        override val resourceId: ResourceId,
        override val configurationVersion: ConfigurationVersion
    ) : RuntimeAdapterBinding()

    data class Search(
        val port: SearchProviderPort,
        override val resourceId: ResourceId,
        override val configurationVersion: ConfigurationVersion
    ) : RuntimeAdapterBinding()

    data class Embedding(
        val port: EmbeddingProviderPort,
        override val resourceId: ResourceId,
        override val configurationVersion: ConfigurationVersion
    ) : RuntimeAdapterBinding()
}

/**
 * P0.5 — Adapter resolution port used by ExecutionService's LOCKED resolution chain:
 *
 *   selectedResourceId -> registry.get() -> controlPlane.getAdapter() -> execute
 *
 * The production implementation is the ProviderControlPlaneService. Resolving a
 * nonexistent/unusable adapter returns null, which ExecutionService maps to the
 * explicit failure "adapter_binding_failed" — never to a substituted resource.
 */
fun interface RuntimeAdapterResolver {
    suspend fun resolveAdapter(
        resourceId: ResourceId,
        configurationVersion: ConfigurationVersion
    ): RuntimeAdapterBinding?
}
