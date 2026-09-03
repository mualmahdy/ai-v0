package com.example.domain.core.capability

import com.example.application.resource.ResourceRegistryService
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceCandidate
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType

/**
 * Derived usable-resource index that maps system capabilities to eligible ResourceIds.
 *
 * It filters for runtime supported, active/enabled, healthy resources adhering to network policy.
 * It is purely a derived index of the authoritative ResourceRegistryService.
 */
class ResourceCapabilityGraph(
    private val resourceSupplier: () -> List<ResourceRecord>
) {
    constructor(registry: ResourceRegistryService) : this({ registry.listResources() })
    constructor(records: List<ResourceRecord>) : this({ records })

    /**
     * Finds eligible resource candidates matching required and optional capabilities.
     */
    fun findEligibleCandidates(
        requiredCapabilities: Set<CapabilityType>,
        optionalCapabilities: Set<CapabilityType> = emptySet(),
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): List<ResourceCandidate> {
        val usable = getUsableResources(networkPolicy, isNetworkAvailable)
        return usable.filter { candidate ->
            val matchesRequired = requiredCapabilities.isEmpty() || candidate.capabilities.any { it in requiredCapabilities }
            val matchesOptional = optionalCapabilities.isNotEmpty() && candidate.capabilities.any { it in optionalCapabilities }
            matchesRequired || matchesOptional
        }.map { it.toCandidate() }
    }

    /**
     * Finds eligible candidates that explicitly provide a given capability.
     */
    fun findCandidatesForCapability(
        capability: CapabilityType,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): List<ResourceCandidate> {
        return getUsableResources(networkPolicy, isNetworkAvailable)
            .filter { it.capabilities.contains(capability) }
            .map { it.toCandidate() }
    }

    /**
     * Finds eligible candidates of a specific ResourceType (LLM, SEARCH, EMBEDDING, TOOL).
     */
    fun findCandidatesByType(
        type: ResourceType,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): List<ResourceCandidate> {
        return getUsableResources(networkPolicy, isNetworkAvailable)
            .filter { it.resourceType == type }
            .map { it.toCandidate() }
    }

    /**
     * Resolves a candidate for a specific ResourceId if usable.
     */
    fun getCandidate(
        resourceId: ResourceId,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): ResourceCandidate? {
        return getUsableResources(networkPolicy, isNetworkAvailable)
            .firstOrNull { it.resourceId == resourceId }
            ?.toCandidate()
    }

    /**
     * Filters for active, supported, healthy resources adhering to offline/network policies.
     */
    private fun getUsableResources(
        networkPolicy: NetworkPolicy,
        isNetworkAvailable: Boolean
    ): List<ResourceRecord> {
        return resourceSupplier().filter { record ->
            val isLifecycleActive = record.lifecycleState == ResourceLifecycleState.ENABLED ||
                    record.lifecycleState == ResourceLifecycleState.ACTIVE
            val isHealthy = record.healthStatus != HealthStatus.UNAVAILABLE
            val adheresToOffline = if (networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable) {
                record.isLocal
            } else {
                true
            }
            record.runtimeSupported && isLifecycleActive && isHealthy && adheresToOffline
        }
    }
}
