package com.example.domain.core.capability

import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskCapabilityRequirements
import com.example.domain.ports.tools.ToolPort

/**
 * Capability-Resource Bipartite Graph and Deterministic Matching Substrate.
 *
 * Implements clean queries:
 * 1. Which resources provide capability X?
 * 2. Which capabilities does resource Y provide?
 * 3. Which capabilities are missing for task Z?
 * 4. Which agents can perform task Z?
 * 5. Which tools can satisfy capability X?
 * 6. Can a required capability set be satisfied by existing combinations of resources?
 */
class CapabilityResourceGraph(
    initialDescriptors: Collection<CapabilityDescriptor> = emptyList()
) {
    private val descriptorsByCapability = mutableMapOf<CapabilityType, MutableList<CapabilityDescriptor>>()
    private val descriptorsByResource = mutableMapOf<String, MutableList<CapabilityDescriptor>>()

    init {
        registerDescriptors(initialDescriptors)
    }

    /**
     * Registers a capability descriptor into the graph.
     */
    fun registerDescriptor(descriptor: CapabilityDescriptor) {
        descriptorsByCapability.getOrPut(descriptor.type) { mutableListOf() }.add(descriptor)
        descriptorsByResource.getOrPut(descriptor.providerId) { mutableListOf() }.add(descriptor)
    }

    /**
     * Bulk registers capability descriptors.
     */
    fun registerDescriptors(descriptors: Collection<CapabilityDescriptor>) {
        descriptors.forEach { registerDescriptor(it) }
    }

    /**
     * Clears all registered descriptors in the graph.
     */
    fun clear() {
        descriptorsByCapability.clear()
        descriptorsByResource.clear()
    }

    /**
     * Returns all capability descriptors for a given capability type.
     */
    fun getResourcesProviding(capability: CapabilityType): List<CapabilityDescriptor> {
        return descriptorsByCapability[capability]?.toList() ?: emptyList()
    }

    /**
     * Returns all capability descriptors provided by a given resource id.
     */
    fun getCapabilitiesProvidedBy(resourceId: String): List<CapabilityDescriptor> {
        return descriptorsByResource[resourceId]?.toList() ?: emptyList()
    }

    /**
     * Returns all registered descriptors.
     */
    fun getAllDescriptors(): List<CapabilityDescriptor> {
        return descriptorsByCapability.values.flatten()
    }

    /**
     * Evaluates deterministic matching between requirements and candidate capabilities.
     */
    fun matchCapabilities(
        required: Set<CapabilityType>,
        optional: Set<CapabilityType> = emptySet(),
        prohibited: Set<CapabilityType> = emptySet(),
        candidateCapabilities: Set<CapabilityType>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        isCandidateLocal: Boolean = true
    ): CapabilityMatchResult {
        // 1. Prohibited Check
        val prohibitedViolations = candidateCapabilities.intersect(prohibited)
        if (prohibitedViolations.isNotEmpty()) {
            return CapabilityMatchResult(
                matchLevel = CapabilityMatchLevel.CONFLICT,
                satisfiedCapabilities = emptySet(),
                missingCapabilities = required,
                conflictingCapabilities = prohibitedViolations,
                prohibitedViolations = prohibitedViolations,
                coverageRatio = 0.0f
            )
        }

        // 2. Network Compatibility Check
        val conflictingNetwork = mutableSetOf<CapabilityType>()
        if (networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable) {
            for (cap in candidateCapabilities) {
                if (cap.defaultNetworkRequirement == NetworkRequirement.ONLINE_ONLY && !isCandidateLocal) {
                    conflictingNetwork.add(cap)
                }
            }
        }

        val effectiveCandidate = candidateCapabilities - conflictingNetwork
        val satisfied = effectiveCandidate.intersect(required)
        val missing = required - satisfied
        val partiallySatisfied = if (missing.isNotEmpty() && satisfied.isNotEmpty()) satisfied else emptySet()

        val coverageRatio = if (required.isEmpty()) {
            if (candidateCapabilities.intersect(optional).isNotEmpty()) 1.0f else 0.5f
        } else {
            satisfied.size.toFloat() / required.size.toFloat()
        }

        val matchLevel = when {
            conflictingNetwork.isNotEmpty() && satisfied.isEmpty() -> CapabilityMatchLevel.CONFLICT
            required.isEmpty() -> CapabilityMatchLevel.FULL_MATCH
            missing.isEmpty() -> CapabilityMatchLevel.FULL_MATCH
            satisfied.isNotEmpty() -> CapabilityMatchLevel.PARTIAL_MATCH
            else -> CapabilityMatchLevel.NO_MATCH
        }

        return CapabilityMatchResult(
            matchLevel = matchLevel,
            satisfiedCapabilities = satisfied,
            missingCapabilities = missing,
            partiallySatisfiedCapabilities = partiallySatisfied,
            conflictingCapabilities = conflictingNetwork,
            prohibitedViolations = prohibitedViolations,
            coverageRatio = coverageRatio
        )
    }

    /**
     * Conducts comprehensive Capability Gap Analysis for a task.
     */
    fun analyzeGap(
        taskId: String = "task_adhoc",
        requirements: TaskCapabilityRequirements,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        failureCounts: Map<String, Int> = emptyMap(),
        currentlySatisfied: Set<CapabilityType> = emptySet()
    ): CapabilityGapAnalysis {
        val required = requirements.requiredCapabilities
        val optional = requirements.optionalCapabilities
        val prohibited = requirements.prohibitedCapabilities

        val satisfied = mutableSetOf<CapabilityType>()
        satisfied.addAll(currentlySatisfied.intersect(required))

        val missing = mutableSetOf<CapabilityType>()
        val conflicting = mutableSetOf<CapabilityType>()
        val candidateMap = mutableMapOf<CapabilityType, MutableList<CapabilityDescriptor>>()

        for (cap in required) {
            if (satisfied.contains(cap)) continue

            val candidateDescriptors = getResourcesProviding(cap).filter { desc ->
                val failCount = failureCounts[desc.providerId] ?: 0
                val isHealthy = desc.state != CapabilityState.UNAVAILABLE && failCount < 3
                val isNetworkOk = when {
                    networkPolicy == NetworkPolicy.OFFLINE && !desc.isLocal -> false
                    !isNetworkAvailable && !desc.isLocal -> false
                    else -> true
                }
                isHealthy && isNetworkOk
            }

            if (candidateDescriptors.isNotEmpty()) {
                satisfied.add(cap)
                candidateMap[cap] = candidateDescriptors.toMutableList()
            } else {
                // Check if missing due to offline / network conflict
                val offlineDescriptors = getResourcesProviding(cap).filter { !it.isLocal }
                if (offlineDescriptors.isNotEmpty() && (networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable)) {
                    conflicting.add(cap)
                }
                missing.add(cap)
            }
        }

        val status = when {
            conflicting.isNotEmpty() && satisfied.isEmpty() -> CapabilityStatus.BLOCKED
            missing.isEmpty() -> CapabilityStatus.CAPABILITY_SATISFIED
            satisfied.isNotEmpty() -> CapabilityStatus.CAPABILITY_PARTIAL
            else -> CapabilityStatus.NO_CAPABLE_RESOURCE
        }

        val report = buildString {
            if (missing.isEmpty()) {
                append("جميع القدرات المطلوبة (${required.size}) متوفرة ومستوفاة.")
            } else {
                append("نقص في القدرات المطلوبة: ${missing.joinToString { it.displayName }}")
                if (conflicting.isNotEmpty()) {
                    append(" (محجوبة بسبب سياسة الشبكة/عدم توفر الاتصال: ${conflicting.joinToString { it.displayName }})")
                }
            }
        }

        return CapabilityGapAnalysis(
            targetTaskId = taskId,
            requiredCapabilities = required,
            optionalCapabilities = optional,
            prohibitedCapabilities = prohibited,
            satisfiedCapabilities = satisfied,
            missingCapabilities = missing,
            conflictingCapabilities = conflicting,
            candidateResourcesForMissing = candidateMap,
            status = status,
            gapReport = report,
            isFullySatisfied = missing.isEmpty() && conflicting.isEmpty()
        )
    }

    fun analyzeGaps(
        taskId: String = "task_adhoc",
        requirements: TaskCapabilityRequirements,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        failureCounts: Map<String, Int> = emptyMap(),
        currentlySatisfied: Set<CapabilityType> = emptySet()
    ): CapabilityGapAnalysis = analyzeGap(taskId, requirements, networkPolicy, isNetworkAvailable, failureCounts, currentlySatisfied)

    /**
     * Finds capable and authorized agents for a given set of task requirements.
     */
    fun findCapableAgents(
        requirements: TaskCapabilityRequirements,
        availableAgents: List<AgentDefinition>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): List<AgentDefinition> {
        val required = requirements.requiredCapabilities
        val optional = requirements.optionalCapabilities
        val prohibited = requirements.prohibitedCapabilities

        val scored = availableAgents.mapNotNull { agent ->
            if (!agent.enabled) return@mapNotNull null

            // 1. Prohibited check
            if (agent.allowedCapabilities.intersect(prohibited).isNotEmpty()) return@mapNotNull null

            // 2. Network check
            if ((networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable) &&
                agent.networkRequirement == NetworkRequirement.ONLINE_ONLY
            ) {
                return@mapNotNull null
            }

            // 3. Capability Match
            val match = matchCapabilities(
                required = required,
                optional = optional,
                prohibited = prohibited,
                candidateCapabilities = agent.allowedCapabilities,
                networkPolicy = networkPolicy,
                isNetworkAvailable = isNetworkAvailable,
                isCandidateLocal = agent.locality == Locality.LOCAL_ON_DEVICE
            )

            if (match.hasViolations) return@mapNotNull null

            // If task specifies required capabilities, agent must satisfy at least one if required > 0
            if (required.isNotEmpty() && match.satisfiedCapabilities.isEmpty()) {
                return@mapNotNull null
            }

            // Scoring
            val requiredScore = match.coverageRatio * 4.0f
            val optionalScore = (agent.allowedCapabilities.intersect(optional).size) * 1.5f
            val budgetScore = if (agent.budget.maxTokens > 0) 0.5f else 0.0f
            val totalScore = requiredScore + optionalScore + budgetScore

            Pair(agent, totalScore)
        }

        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Finds capable, available, and policy-compliant tools for a given set of task requirements.
     */
    fun findCapableTools(
        requirements: TaskCapabilityRequirements,
        availableTools: List<ToolPort>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        failureCounts: Map<String, Int> = emptyMap()
    ): List<ToolPort> {
        val required = requirements.requiredCapabilities
        val optional = requirements.optionalCapabilities
        val prohibited = requirements.prohibitedCapabilities

        return availableTools.filter { tool ->
            val toolDecl = tool.declaration
            val toolCaps = toolDecl.providedCapabilities
            val failCount = failureCounts[toolDecl.name] ?: 0

            // 1. Health check: fail count >= 3 means degraded/unavailable
            if (failCount >= 3) return@filter false

            // 2. Prohibited check
            if (toolCaps.intersect(prohibited).isNotEmpty()) return@filter false

            // 3. Network check
            if ((networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable) &&
                toolDecl.networkRequirement == NetworkRequirement.ONLINE_ONLY
            ) {
                return@filter false
            }

            // 4. Locality check
            if (requirements.localityConstraint != null && toolDecl.locality != requirements.localityConstraint) {
                return@filter false
            }

            // 5. Capability Match: Must satisfy at least one required or optional capability
            if (required.isEmpty() && optional.isEmpty()) {
                true
            } else {
                toolCaps.intersect(required).isNotEmpty() || toolCaps.intersect(optional).isNotEmpty()
            }
        }
    }

    /**
     * Evaluates if a set of required capabilities can be satisfied by existing registered descriptors.
     */
    fun canSatisfyByComposition(
        required: Set<CapabilityType>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): Boolean {
        for (cap in required) {
            val validProviders = getResourcesProviding(cap).filter { desc ->
                desc.state != CapabilityState.UNAVAILABLE &&
                        !(networkPolicy == NetworkPolicy.OFFLINE && !desc.isLocal) &&
                        !(!isNetworkAvailable && !desc.isLocal)
            }
            if (validProviders.isEmpty()) return false
        }
        return true
    }
}

