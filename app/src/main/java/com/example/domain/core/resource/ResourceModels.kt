package com.example.domain.core.resource

/**
 * P0.2 — Final Resource Model (APPROVED-BASELINE v2.1, Section B — LOCKED).
 *
 * A [Resource] is a uniquely addressable runtime capability provider that can be
 * selected and invoked by the workspace. This definition covers remote LLMs, remote
 * embedding services, remote search providers, local LLMs, local embedding engines,
 * local search engines, and future tool/agent/storage/MCP resources.
 */

/** Functional type of the runtime capability provider. */
enum class ResourceType(val code: String) {
    LLM("llm"),
    EMBEDDING("embedding"),
    SEARCH("search"),
    TOOL("tool"),
    AGENT("agent"),
    STORAGE("storage");

    companion object {
        fun fromCode(code: String): ResourceType? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/** Physical category: REMOTE (cloud/edge service) or LOCAL (on-device). */
enum class ResourceCategory(val code: String) {
    REMOTE("remote"),
    LOCAL("local");

    companion object {
        fun fromCode(code: String): ResourceCategory? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * Track 1 — LIFECYCLE (user/registry intent). The locked 5-state lifecycle
 * (Section E):
 *  CONFIGURED  -> registered; validation not yet attempted
 *  VALIDATING  -> control plane currently testing adapter + transport
 *  HEALTHY     -> validated + runtime-supported + transport-verified
 *  UNAVAILABLE -> previously healthy; transport/health lost
 *  DISABLED    -> user explicitly turned resource off
 */
enum class ResourceLifecycleState(val code: String) {
    CONFIGURED("configured"),
    VALIDATING("validating"),
    HEALTHY("healthy"),
    UNAVAILABLE("unavailable"),
    DISABLED("disabled");

    companion object {
        fun fromCode(code: String): ResourceLifecycleState? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * Immutable resource record owned by ResourceRegistryService (Section B — LOCKED).
 *
 * The three tracks are independent (Section B, critical separation):
 *  - [lifecycleState]   : Track 1 (lifecycle intent)
 *  - [runtimeSupported] : Track 2 (adapter existence — binary; determined ONLY by
 *                         ProviderAdapterFactory capability, never by lifecycle ops)
 *  - health metrics     : Track 3 (NOT stored here; owned exclusively by
 *                         ResourceHealthService)
 */
data class ResourceRecord(
    val resourceId: ResourceId,
    val providerId: ProviderId,
    val serviceId: ServiceId,
    val resourceType: ResourceType,
    val category: ResourceCategory,
    val capabilities: List<String>,
    val lifecycleState: ResourceLifecycleState,
    val runtimeSupported: Boolean,
    val configurationVersion: ConfigurationVersion,
    val isFallback: Boolean = false,
    val registeredAt: Long,
    val lastStateChangeAt: Long,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * RULE LC (Section E — Usable Definition): a resource is USABLE if and only if
     * lifecycle == HEALTHY AND runtimeSupported == true AND not in cooldown.
     * Cooldown (health track) is evaluated by the caller via ResourceHealthService;
     * this flag covers the two registry-owned conjuncts.
     */
    val isUsableIgnoringCooldown: Boolean
        get() = lifecycleState == ResourceLifecycleState.HEALTHY && runtimeSupported
}

/** Registration input accepted by ResourceRegistryService.register(). */
data class ResourceRecordInput(
    val providerId: ProviderId,
    val serviceId: ServiceId,
    val resourceType: ResourceType,
    val category: ResourceCategory,
    val capabilities: List<String>,
    val isFallback: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

/** Usable resource projection returned by queryUsableByCapability(). */
data class UsableResource(
    val resourceId: ResourceId,
    val providerId: ProviderId,
    val serviceId: ServiceId,
    val resourceType: ResourceType,
    val category: ResourceCategory,
    val capabilities: List<String>,
    val configurationVersion: ConfigurationVersion,
    val isFallback: Boolean
)

/**
 * Resource change events emitted by ResourceRegistryService (Section D).
 * CapabilityResourceGraph (P0.7) subscribes to this stream and rebuilds/updates
 * from these events only — no external component writes to the graph.
 */
sealed interface ResourceChangeEvent {
    /** Emitted after a resource was successfully registered. */
    data class Registered(val record: ResourceRecord) : ResourceChangeEvent

    /** Emitted after a lifecycle transition completed. */
    data class LifecycleChanged(
        val resourceId: ResourceId,
        val from: ResourceLifecycleState,
        val to: ResourceLifecycleState
    ) : ResourceChangeEvent

    /** Emitted after the runtime-support flag changed (control plane only). */
    data class RuntimeSupportChanged(
        val resourceId: ResourceId,
        val supported: Boolean
    ) : ResourceChangeEvent

    /** Emitted after the configuration version was bumped (ResourceId unchanged). */
    data class ConfigurationBumped(
        val resourceId: ResourceId,
        val newVersion: ConfigurationVersion
    ) : ResourceChangeEvent
}
