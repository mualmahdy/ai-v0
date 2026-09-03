package com.example.domain.core.resource

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.HealthStatus

/**
 * Explicit, strongly-typed Resource identifier representing the authoritative runtime resource identity.
 */
data class ResourceId(val value: String) {
    override fun toString(): String = value
}

/**
 * Functional categories of system runtime resources.
 */
enum class ResourceType {
    LLM,
    SEARCH,
    EMBEDDING,
    TOOL,
    STORAGE,
    INTEGRATION
}

/**
 * Lifecycle states of managed runtime resources.
 */
enum class ResourceLifecycleState {
    REGISTERED,
    CONFIGURED,
    ENABLED,
    ACTIVE,
    DISABLED,
    DEPRECATED,
    UNREGISTERED
}

/**
 * Authoritative record of a managed system resource in the ResourceRegistry.
 *
 * FIX DOM-P2-19: Previously defaulted to `lifecycleState = ENABLED`,
 * `runtimeSupported = true`, `healthStatus = HEALTHY` — all of which were MISLEADING
 * because a freshly-constructed ResourceRecord had not actually been enabled, verified
 * at runtime, or health-checked. Now the defaults are honest:
 *   - lifecycleState = REGISTERED (just registered, not yet configured/enabled)
 *   - runtimeSupported = false (must be explicitly verified)
 *   - healthStatus = UNKNOWN (must be explicitly probed)
 *
 * Note: ComponentRegistry.registerLlmProvider / registerSearchProvider / etc. continue
 * to override these defaults with explicit values appropriate for those registration
 * paths. The defaults here are the safe-honest baseline for direct construction.
 */
data class ResourceRecord(
    val resourceId: ResourceId,
    val providerId: String,
    val serviceId: String,
    val resourceType: ResourceType,
    val capabilities: Set<CapabilityType>,
    val configurationVersion: Long = 1L,
    val lifecycleState: ResourceLifecycleState = ResourceLifecycleState.REGISTERED,
    val runtimeSupported: Boolean = false,
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val isLocal: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toCandidate(): ResourceCandidate = ResourceCandidate(
        resourceId = resourceId,
        providerId = providerId,
        serviceId = serviceId,
        resourceType = resourceType,
        capabilities = capabilities,
        lifecycleState = lifecycleState,
        runtimeSupported = runtimeSupported,
        healthStatus = healthStatus,
        configurationVersion = configurationVersion,
        isLocal = isLocal,
        metadata = metadata
    )
}

/**
 * Derived usable resource candidate surfaced through ResourceCapabilityGraph.
 */
data class ResourceCandidate(
    val resourceId: ResourceId,
    val providerId: String,
    val serviceId: String,
    val resourceType: ResourceType,
    val capabilities: Set<CapabilityType>,
    val lifecycleState: ResourceLifecycleState,
    val runtimeSupported: Boolean,
    val healthStatus: HealthStatus,
    val configurationVersion: Long,
    val isLocal: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Explicit resolution failures returned by RuntimeAdapterResolver.
 */
sealed class ResourceResolutionFailure(val message: String) {
    data class InvalidResourceId(val desc: String) : ResourceResolutionFailure(desc)
    data class StaleConfigurationVersion(val desc: String) : ResourceResolutionFailure(desc)
    data class ResourceDisabled(val desc: String) : ResourceResolutionFailure(desc)
    data class RuntimeUnsupported(val desc: String) : ResourceResolutionFailure(desc)
    data class ResourceUnavailable(val desc: String) : ResourceResolutionFailure(desc)
    data class AdapterNotFound(val desc: String) : ResourceResolutionFailure(desc)
    data class ExecutionFailed(val desc: String) : ResourceResolutionFailure(desc)
}
