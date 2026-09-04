package com.example.domain.core.provider

/**
 * ============================================================================
 * ServiceProtocolCompatibility — Phase 4 (Correction #5)
 * ============================================================================
 *
 * Explicit ServiceType ↔ ServiceProtocol compatibility matrix. Saving a service
 * or configuration with an incompatible protocol must be rejected with a clear
 * error instead of failing later at runtime.
 */
object ServiceProtocolCompatibility {

    private val compatible: Map<ServiceType, Set<ServiceProtocolId>> = mapOf(
        ServiceType.LLM to setOf(
            ServiceProtocolId.GEMINI_NATIVE,
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE,
            ServiceProtocolId.ANTHROPIC_NATIVE,
            ServiceProtocolId.OLLAMA_NATIVE
        ),
        ServiceType.EMBEDDING to setOf(
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE,
            ServiceProtocolId.OLLAMA_NATIVE,
            ServiceProtocolId.GEMINI_NATIVE,
            ServiceProtocolId.IN_PROCESS,
            ServiceProtocolId.NATIVE_SDK
        ),
        ServiceType.SEARCH to setOf(
            ServiceProtocolId.TAVILY_NATIVE,
            ServiceProtocolId.IN_PROCESS,
            ServiceProtocolId.NATIVE_SDK
        ),
        ServiceType.IMAGE_GENERATION to setOf(
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE,
            ServiceProtocolId.NATIVE_SDK
        ),
        ServiceType.SPEECH to setOf(
            ServiceProtocolId.OPENAI_COMPATIBLE,
            ServiceProtocolId.OPENAI_NATIVE,
            ServiceProtocolId.NATIVE_SDK
        ),
        ServiceType.VECTOR_STORE to setOf(
            ServiceProtocolId.IN_PROCESS,
            ServiceProtocolId.NATIVE_SDK,
            ServiceProtocolId.OPENAI_COMPATIBLE
        ),
        ServiceType.MCP to setOf(
            ServiceProtocolId.NATIVE_SDK,
            ServiceProtocolId.IN_PROCESS
        )
    )

    /**
     * Returns true when the protocol can legitimately serve the given service
     * type. Unknown combinations return false — the caller rejects the save.
     */
    fun isCompatible(serviceType: ServiceType, protocolId: ServiceProtocolId): Boolean {
        return compatible[serviceType]?.contains(protocolId) == true
    }

    /**
     * All protocols legitimately compatible with a service type (used by UI
     * pickers so users cannot construct invalid combinations).
     */
    fun protocolsFor(serviceType: ServiceType): List<ServiceProtocolId> {
        return compatible[serviceType]?.toList() ?: emptyList()
    }
}
