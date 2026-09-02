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
     * Evaluates deterministic matching between requirements and candidate capabilities as mandated by Rule 2 and Rule 3.
     */
    fun matchCapabilities(
        required: Set<CapabilityType>,
        optional: Set<CapabilityType> = emptySet(),
        prohibited: Set<CapabilityType> = emptySet(),
        candidateCapabilities: Set<CapabilityType>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        isCandidateLocal: Boolean = true,
        candidateHealthState: CapabilityState = CapabilityState.AVAILABLE
    ): CapabilityMatchResult {
        // 1. Availability check
        if (candidateHealthState == CapabilityState.UNAVAILABLE) {
            val unavailableCaps = candidateCapabilities.intersect(required)
            return CapabilityMatchResult(
                matchLevel = CapabilityMatchLevel.UNAVAILABLE,
                satisfiedCapabilities = emptySet(),
                missingCapabilities = required,
                unavailableCapabilities = if (unavailableCaps.isNotEmpty()) unavailableCaps else required,
                coverageRatio = 0.0f,
                matchRationale = "Candidate resource is currently UNAVAILABLE"
            )
        }

        // 2. Prohibited Check (CONFLICT)
        val prohibitedViolations = candidateCapabilities.intersect(prohibited)
        if (prohibitedViolations.isNotEmpty()) {
            return CapabilityMatchResult(
                matchLevel = CapabilityMatchLevel.CONFLICT,
                satisfiedCapabilities = emptySet(),
                missingCapabilities = required,
                conflictingCapabilities = prohibitedViolations,
                prohibitedViolations = prohibitedViolations,
                coverageRatio = 0.0f,
                matchRationale = "Candidate possesses PROHIBITED capabilities: ${prohibitedViolations.joinToString()}"
            )
        }

        // 3. Network Compatibility Check
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
            conflictingNetwork.isNotEmpty() && satisfied.isEmpty() && required.isNotEmpty() -> CapabilityMatchLevel.CONFLICT
            required.isEmpty() -> CapabilityMatchLevel.FULL_MATCH
            missing.isEmpty() -> CapabilityMatchLevel.FULL_MATCH
            satisfied.isNotEmpty() -> CapabilityMatchLevel.PARTIAL_MATCH
            else -> CapabilityMatchLevel.NO_MATCH
        }

        val rationale = when (matchLevel) {
            CapabilityMatchLevel.FULL_MATCH -> "All ${required.size} required capabilities fully satisfied."
            CapabilityMatchLevel.PARTIAL_MATCH -> "Partial match: satisfied ${satisfied.size}/${required.size} (missing: ${missing.joinToString()})."
            CapabilityMatchLevel.NO_MATCH -> "No required capabilities satisfied."
            CapabilityMatchLevel.CONFLICT -> "Network or policy conflict: ${conflictingNetwork.joinToString()}."
            CapabilityMatchLevel.UNAVAILABLE -> "Resource unavailable."
        }

        return CapabilityMatchResult(
            matchLevel = matchLevel,
            satisfiedCapabilities = satisfied,
            missingCapabilities = missing,
            partiallySatisfiedCapabilities = partiallySatisfied,
            conflictingCapabilities = conflictingNetwork,
            prohibitedViolations = prohibitedViolations,
            coverageRatio = coverageRatio,
            matchRationale = rationale
        )
    }

    /**
     * Conducts comprehensive Capability Gap Analysis for a task.
     * Respects evidence-based satisfaction (Rule 14).
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

        // Only mark capabilities as satisfied if verified by evidence / currentlySatisfied
        val satisfied = currentlySatisfied.intersect(required).toMutableSet()
        val pending = (required - satisfied).toMutableSet()

        val missing = mutableSetOf<CapabilityType>()
        val conflicting = mutableSetOf<CapabilityType>()
        val unavailable = mutableSetOf<CapabilityType>()
        val candidateMap = mutableMapOf<CapabilityType, MutableList<CapabilityDescriptor>>()

        for (cap in pending) {
            val allProviders = getResourcesProviding(cap)
            val healthyProviders = allProviders.filter { desc ->
                val failCount = failureCounts[desc.providerId] ?: 0
                val isHealthy = desc.state != CapabilityState.UNAVAILABLE && failCount < 3
                val isNetworkOk = when {
                    networkPolicy == NetworkPolicy.OFFLINE && !desc.isLocal -> false
                    !isNetworkAvailable && !desc.isLocal -> false
                    else -> true
                }
                isHealthy && isNetworkOk
            }

            if (healthyProviders.isNotEmpty()) {
                candidateMap[cap] = healthyProviders.toMutableList()
            } else {
                // Determine root cause
                val offlineProviders = allProviders.filter { !it.isLocal }
                val failedProviders = allProviders.filter { desc ->
                    desc.state == CapabilityState.UNAVAILABLE || (failureCounts[desc.providerId] ?: 0) >= 3
                }

                if (offlineProviders.isNotEmpty() && (networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable)) {
                    conflicting.add(cap)
                } else if (failedProviders.isNotEmpty() && failedProviders.size == allProviders.size) {
                    unavailable.add(cap)
                }
                missing.add(cap)
            }
        }

        val status = when {
            satisfied.containsAll(required) && required.isNotEmpty() -> CapabilityStatus.CAPABILITY_SATISFIED
            required.isEmpty() -> CapabilityStatus.CAPABILITY_SATISFIED
            conflicting.isNotEmpty() && satisfied.isEmpty() && candidateMap.isEmpty() -> CapabilityStatus.BLOCKED
            unavailable.isNotEmpty() && satisfied.isEmpty() && candidateMap.isEmpty() -> CapabilityStatus.CAPABILITY_UNAVAILABLE
            missing.isNotEmpty() && satisfied.isEmpty() && candidateMap.isEmpty() -> CapabilityStatus.NO_CAPABLE_RESOURCE
            satisfied.isNotEmpty() || candidateMap.isNotEmpty() -> CapabilityStatus.CAPABILITY_PARTIAL
            else -> CapabilityStatus.CAPABILITY_PARTIAL
        }

        val report = buildString {
            if (missing.isEmpty()) {
                if (satisfied.containsAll(required) && required.isNotEmpty()) {
                    append("جميع القدرات المطلوبة (${required.size}) مكتملة ومحققة بالأدلة.")
                } else {
                    append("جميع القدرات المطلوبة (${required.size}) يتوفر لها موارد تنفيذ مؤهلة.")
                }
            } else {
                append("نقص في الموارد للقدرات المطلوبة: ${missing.joinToString { it.displayName }}")
                if (conflicting.isNotEmpty()) {
                    append(" (محجوبة بسبب سياسة الشبكة: ${conflicting.joinToString { it.displayName }})")
                }
                if (unavailable.isNotEmpty()) {
                    append(" (غير متاحة بسبب تكرار الفشل: ${unavailable.joinToString { it.displayName }})")
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
            pendingCapabilities = pending,
            conflictingCapabilities = conflicting,
            unavailableCapabilities = unavailable,
            candidateResourcesForPending = candidateMap,
            status = status,
            gapReport = report,
            isFullySatisfied = required.isEmpty() || (satisfied.containsAll(required) && conflicting.isEmpty())
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
     * Finds capable and authorized agents for a given set of task requirements (Rule 8).
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

            // 3. Locality constraint
            if (requirements.localityConstraint != null && agent.locality != requirements.localityConstraint) {
                return@mapNotNull null
            }

            // 4. Capability Match
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

            // If task specifies required capabilities, agent must satisfy at least one required capability
            if (required.isNotEmpty() && match.satisfiedCapabilities.isEmpty()) {
                return@mapNotNull null
            }

            // Scoring: strictly capability-grounded
            val requiredScore = match.coverageRatio * 6.0f
            val optionalScore = (agent.allowedCapabilities.intersect(optional).size) * 1.5f
            val fullMatchBonus = if (match.isFullMatch) 3.0f else 0.0f
            val budgetScore = if (agent.budget.maxTokens > 0) 0.5f else 0.0f
            val totalScore = requiredScore + optionalScore + fullMatchBonus + budgetScore

            Pair(agent, totalScore)
        }

        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Finds capable, available, and policy-compliant tools for a given set of task requirements (Rule 9).
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

            // 5. Side-effects check
            if (requirements.maxAllowedSideEffect != null && toolDecl.sideEffects > requirements.maxAllowedSideEffect) {
                return@filter false
            }

            // 6. Capability Match: Must satisfy at least one required or optional capability
            if (required.isEmpty() && optional.isEmpty()) {
                true
            } else {
                toolCaps.intersect(required).isNotEmpty() || toolCaps.intersect(optional).isNotEmpty()
            }
        }
    }

    /**
     * Finds capable LLM descriptors matching task requirements (Rule 10).
     */
    fun findCapableModels(
        requirements: TaskCapabilityRequirements,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        failureCounts: Map<String, Int> = emptyMap()
    ): List<CapabilityDescriptor> {
        val allModelDescriptors = getResourcesProviding(CapabilityType.LLM_GENERATION)
        val isOffline = networkPolicy == NetworkPolicy.OFFLINE || !isNetworkAvailable

        return allModelDescriptors.filter { desc ->
            val failCount = failureCounts[desc.providerId] ?: 0
            if (desc.state == CapabilityState.UNAVAILABLE || failCount >= 3) return@filter false
            if (isOffline && !desc.isLocal) return@filter false
            if (requirements.requiresLocalInference && !desc.isLocal) return@filter false

            if (requirements.requiredCapabilities.contains(CapabilityType.VISION)) {
                val hasVision = getCapabilitiesProvidedBy(desc.providerId).any { it.type == CapabilityType.VISION }
                if (!hasVision) return@filter false
            }

            true
        }
    }

    /**
     * Resolves transitive prerequisites for the required capabilities in a task specification (Rule 6).
     */
    fun resolveRequiredWithDependencies(
        requirements: TaskCapabilityRequirements
    ): TaskCapabilityRequirements {
        val dependencyResult = CapabilityPrerequisites.resolvePrerequisites(requirements.requiredCapabilities)
        return requirements.copy(
            requiredCapabilities = dependencyResult.resolvedCapabilities
        )
    }

    /**
     * Evaluates whether a required capability set is fully, partially, or unsatisfiable (Rule 7, 10).
     */
    fun evaluateSatisfiability(
        requirements: TaskCapabilityRequirements,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        failureCounts: Map<String, Int> = emptyMap()
    ): CapabilitySatisfiability {
        val gap = analyzeGap(
            taskId = "eval",
            requirements = requirements,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            failureCounts = failureCounts
        )
        return when (gap.status) {
            CapabilityStatus.CAPABILITY_SATISFIED -> CapabilitySatisfiability.FULLY_SATISFIABLE
            CapabilityStatus.BLOCKED -> CapabilitySatisfiability.BLOCKED
            CapabilityStatus.CAPABILITY_PARTIAL -> {
                if (gap.missingCapabilities.isEmpty()) CapabilitySatisfiability.FULLY_SATISFIABLE
                else CapabilitySatisfiability.PARTIALLY_SATISFIABLE
            }
            CapabilityStatus.CAPABILITY_MISSING,
            CapabilityStatus.NO_CAPABLE_RESOURCE,
            CapabilityStatus.CAPABILITY_UNAVAILABLE -> {
                if (gap.satisfiedCapabilities.isNotEmpty() || gap.candidateResourcesForPending.isNotEmpty()) {
                    CapabilitySatisfiability.PARTIALLY_SATISFIABLE
                } else {
                    CapabilitySatisfiability.UNSATISFIABLE
                }
            }
            else -> CapabilitySatisfiability.PARTIALLY_SATISFIABLE
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

