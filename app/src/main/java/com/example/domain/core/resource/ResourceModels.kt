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
 */
data class ResourceRecord(
    val resourceId: ResourceId,
    val providerId: String,
    val serviceId: String,
    val resourceType: ResourceType,
    val capabilities: Set<CapabilityType>,
    val configurationVersion: Long = 1L,
    val lifecycleState: ResourceLifecycleState = ResourceLifecycleState.ENABLED,
    val runtimeSupported: Boolean = true,
    val healthStatus: HealthStatus = HealthStatus.HEALTHY,
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
