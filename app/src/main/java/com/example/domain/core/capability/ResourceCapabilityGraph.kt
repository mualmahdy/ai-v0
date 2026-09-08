package com.example.domain.core.capability

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
 * It is purely a derived index over a supplied snapshot of [ResourceRecord]s — the
 * authoritative registry lives OUTSIDE the domain (application layer), which is
 * why this class accepts a plain record supplier and nothing else.
 *
 * ============================================================================
 * ARCHITECTURE BOUNDARY FIX (report: "domain → application violation"):
 * this class previously imported `com.example.application.resource.ResourceRegistryService`
 * and offered a `constructor(registry: ResourceRegistryService)`. That constructor
 * has been REMOVED — the domain layer must not reference application types.
 * Composition roots in the application layer now pass a record supplier:
 * `ResourceCapabilityGraph { resourceRegistry.listResources() }`.
 * ============================================================================
 *
 * ============================================================================
 * SINGLE AUTHORITATIVE CAPABILITY ADMISSION POLICY (report: "duplicate
 * capability semantics" / "broken capability matching"):
 *
 *   REQUIRED capabilities are a HARD ADMISSION GATE — a candidate is admitted
 *   only if it carries ALL of them (`containsAll`). Previously `any` let a
 *   candidate match when it satisfied just ONE required capability, and an
 *   OPTIONAL capability could admit a candidate that failed the required set
 *   (`matchesRequired || matchesOptional`).
 *
 *   OPTIONAL capabilities never admit — they only RANK admitted candidates
 *   (more optional coverage sorts first; ties keep registry order for
 *   determinism).
 *
 * Every runtime path that needs capability-based resource admission must
 * delegate to [admittedByRequirements] / [findEligibleCandidates] so there is
 * exactly ONE admission policy in the system (the decision layer no longer
 * re-implements its own `any`-based filter).
 * ============================================================================
 */
class ResourceCapabilityGraph(
    private val resourceSupplier: () -> List<ResourceRecord>
) {
    constructor(records: List<ResourceRecord>) : this({ records })

    /**
     * THE capability admission policy of the runtime.
     *
     * @param required hard gate — the candidate must carry ALL of these.
     * @param optional ranking hints — never admit, only sort.
     */
    fun admittedByRequirements(
        candidates: List<ResourceCandidate>,
        required: Set<CapabilityType>,
        optional: Set<CapabilityType> = emptySet()
    ): List<ResourceCandidate> {
        val admitted = candidates.filter { it.capabilities.containsAll(required) }
        if (optional.isEmpty()) return admitted
        return admitted.sortedByDescending { candidate ->
            candidate.capabilities.count { it in optional }
        }
    }

    /**
     * Capabilities a resource of [type] can meaningfully declare. Task-level
     * required sets mix capabilities across types (an LLM step of a coding
     * task legitimately requires TOOL_EXECUTION too — from the TOOL side).
     * Projecting the required set onto the type's admissible domain before
     * the hard gate keeps the semantics honest: required-of-THE-RESOURCE is
     * a strict ALL-gate; requirements satisfied by OTHER resource types are
     * not demanded from this one.
     */
    fun capabilitiesAdmissibleForType(type: ResourceType): Set<CapabilityType>? = when (type) {
        ResourceType.LLM -> setOf(
            CapabilityType.LLM_GENERATION,
            CapabilityType.REASONING,
            CapabilityType.STREAMING,
            CapabilityType.VISION
        )
        ResourceType.SEARCH -> setOf(
            CapabilityType.SEARCH,
            CapabilityType.INTEGRATION_SYNC
        )
        ResourceType.EMBEDDING -> setOf(
            CapabilityType.EMBEDDING,
            CapabilityType.MEMORY_RETRIEVAL
        )
        ResourceType.TOOL -> null // tools declare the full operational domain
        ResourceType.STORAGE, ResourceType.INTEGRATION -> null
    }

    /**
     * Type-scoped admission: projects [required] onto the capabilities the
     * resource [type] can provide, then applies the SAME hard ALL-gate +
     * optional ranking. ONE policy, correctly scoped per resource type.
     */
    fun admittedByTypeRequirements(
        candidates: List<ResourceCandidate>,
        type: ResourceType,
        required: Set<CapabilityType>,
        optional: Set<CapabilityType> = emptySet()
    ): List<ResourceCandidate> {
        val admissible = capabilitiesAdmissibleForType(type)
        return if (admissible == null) {
            admittedByRequirements(candidates, required, optional)
        } else {
            admittedByRequirements(
                candidates = candidates,
                required = required intersect admissible,
                optional = optional intersect admissible
            )
        }
    }

    /**
     * Finds eligible resource candidates matching required and optional capabilities.
     *
     * Required capabilities are a hard ALL-gate; optional capabilities only
     * influence ordering. A candidate that fails the required set can NOT be
     * admitted by possessing optional capabilities.
     */
    fun findEligibleCandidates(
        requiredCapabilities: Set<CapabilityType>,
        optionalCapabilities: Set<CapabilityType> = emptySet(),
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): List<ResourceCandidate> {
        val usable = getUsableResources(networkPolicy, isNetworkAvailable).map { it.toCandidate() }
        return admittedByRequirements(usable, requiredCapabilities, optionalCapabilities)
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
