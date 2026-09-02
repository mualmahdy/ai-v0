package com.example.application.decision

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityEvidenceRegistry
import com.example.domain.core.capability.CapabilityGapAnalysis
import com.example.domain.core.capability.CapabilityPrerequisites
import com.example.domain.core.capability.CapabilityRequirement
import com.example.domain.core.capability.CapabilityResourceGraph
import com.example.domain.core.capability.CapabilityState
import com.example.domain.core.capability.CapabilityStatus
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.NetworkRequirement
import com.example.domain.core.capability.SideEffectClassification
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.task.TaskCapabilityRequirements
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskSpecification
import com.example.domain.core.task.TaskSpecificationProvenance
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.workspace.Workspace

/**
 * Dedicated Decision Service establishing the explicit architectural boundary for all
 * system-level reasoning, capability selection, and CBR-MDP decision intelligence.
 */
class DecisionService(
    private val cbrMdpEngine: CbrMdpEngine,
    private val componentRegistry: ComponentRegistry,
    private val securityGuard: SecurityGuardService,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy(),
    // P0 RESOURCE CONTRACT (APPROVED-BASELINE v2.1) — optional collaborators wired by
    // the composition root. When wired, evaluateWithRecord() produces the LOCKED
    // DecisionRecord for every capability-requiring decision (Section F / P0.4).
    private val resourceRegistry: com.example.domain.ports.resource.ResourceRegistryService? = null,
    private val resourceHealthService: com.example.domain.ports.resource.ResourceHealthService? = null,
    private val decisionRecordStore: com.example.domain.ports.resource.DecisionRecordStorePort? = null
) {

    /**
     * Builds the complete multi-dimensional DecisionContext by aggregating Workspace,
     * Resource Graph, Tool/Capability states, Memory, and Environment telemetry.
     */
    fun buildDecisionContext(
        task: TaskDefinition,
        workspace: Workspace? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        remainingTokens: Int = 30000,
        consecutiveFailures: Int = 0,
        uncertaintyScore: Float = 0.2f,
        historyCount: Int = 0,
        memoriesCount: Int = 0,
        complexity: Float = 0.5f,
        accumulatedEvidence: Map<String, Any?> = emptyMap(),
        lastAction: DecisionAction? = null,
        lastObservation: EnvironmentObservation? = null,
        decisionHistory: List<DecisionResult> = emptyList()
    ): DecisionContext {
        val effectiveRequirements = resolveTaskRequirements(task)
        val taskWithRequirements = if (task.requirements == effectiveRequirements) task else task.copy(requirements = effectiveRequirements)

        // Derive evidence-based satisfied capabilities dynamically via Evidence Contracts (Rule 13, 14)
        val currentlySatisfied = mutableSetOf<CapabilityType>()
        for (cap in CapabilityType.values()) {
            val contract = CapabilityEvidenceRegistry.getContract(cap)
            val hasEvidence = contract.requiredEvidenceKeys.any { key ->
                val value = accumulatedEvidence[key]
                when (value) {
                    null -> false
                    is String -> value.isNotBlank()
                    is Collection<*> -> value.isNotEmpty()
                    is Map<*, *> -> value.isNotEmpty()
                    is Boolean -> value
                    else -> true
                }
            }
            if (hasEvidence) {
                currentlySatisfied.add(cap)
            }
        }

        // Add dynamically satisfied capabilities from last successful action
        if (lastObservation?.isSuccess == true && lastAction != null) {
            when (lastAction.type) {
                DecisionActionType.SEARCH -> currentlySatisfied.add(CapabilityType.SEARCH)
                DecisionActionType.RETRIEVE_MEMORY,
                DecisionActionType.RETRIEVE_KNOWLEDGE -> {
                    currentlySatisfied.add(CapabilityType.MEMORY_RETRIEVAL)
                    currentlySatisfied.add(CapabilityType.EMBEDDING)
                }
                DecisionActionType.EXECUTE_STEP,
                DecisionActionType.SELECT_MODEL -> {
                    currentlySatisfied.add(CapabilityType.LLM_GENERATION)
                    currentlySatisfied.add(CapabilityType.REASONING)
                }
                DecisionActionType.EXECUTE_TOOL -> {
                    currentlySatisfied.add(CapabilityType.TOOL_EXECUTION)
                    lastAction.targetId?.let { toolName ->
                        componentRegistry.getTool(toolName)?.declaration?.providedCapabilities?.let {
                            currentlySatisfied.addAll(it)
                        }
                    }
                }
                else -> Unit
            }
        }

        val capabilities = componentRegistry.getCapabilityDescriptors()
        val graph = CapabilityResourceGraph(capabilities)

        // Build failure counts map for graph analysis
        val failureCounts = mutableMapOf<String, Int>()
        componentRegistry.listTools().forEach { tool ->
            failureCounts[tool.declaration.name] = componentRegistry.getFailureCount(tool.declaration.name)
        }
        componentRegistry.listLlmProviders().forEach { provider ->
            failureCounts[provider.providerId] = componentRegistry.getFailureCount(provider.providerId)
        }
        componentRegistry.getSearchProvider()?.let { search ->
            failureCounts[search.providerId] = componentRegistry.getFailureCount(search.providerId)
        }

        val gapAnalysis = graph.analyzeGap(
            taskId = taskWithRequirements.id.value,
            requirements = effectiveRequirements,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            failureCounts = failureCounts,
            currentlySatisfied = currentlySatisfied
        )

        val availableTools = componentRegistry.listTools().map { it.declaration.name }

        return DecisionContext(
            task = taskWithRequirements,
            workspace = workspace,
            resourceGraph = workspace?.resourceGraph ?: com.example.domain.core.workspace.ResourceGraph(),
            capabilities = capabilities,
            capabilityGraph = graph,
            capabilityGap = gapAnalysis,
            satisfiedCapabilities = gapAnalysis.satisfiedCapabilities,
            missingCapabilities = gapAnalysis.missingCapabilities,
            availableTools = availableTools,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            remainingTokenBudget = remainingTokens,
            consecutiveFailures = consecutiveFailures,
            uncertaintyScore = uncertaintyScore,
            conversationHistoryCount = historyCount,
            retrievedMemoriesCount = memoriesCount,
            taskComplexity = complexity,
            accumulatedEvidence = accumulatedEvidence,
            lastAction = lastAction,
            lastObservation = lastObservation,
            decisionHistory = decisionHistory
        )
    }

    /**
     * Dynamically generates the candidate action space based on capability matching,
     * resource availability, agent authorization, network policies, task requirements,
     * and closed-loop progress.
     */
    fun generateCandidateActions(context: DecisionContext): List<DecisionAction> {
        val candidates = mutableListOf<DecisionAction>()
        val task = context.task
        val currentStep = task.currentStepIndex
        val isOffline = context.networkPolicy == NetworkPolicy.OFFLINE || !context.isNetworkAvailable
        val effectiveRequirements = task.requirements
        val graph = context.capabilityGraph
        val requiredCaps = effectiveRequirements.requiredCapabilities
        val optionalCaps = effectiveRequirements.optionalCapabilities

        val failureCounts = mutableMapOf<String, Int>()
        componentRegistry.listTools().forEach { tool ->
            failureCounts[tool.declaration.name] = componentRegistry.getFailureCount(tool.declaration.name)
        }
        componentRegistry.listLlmProviders().forEach { provider ->
            failureCounts[provider.providerId] = componentRegistry.getFailureCount(provider.providerId)
        }
        componentRegistry.getSearchProvider()?.let { search ->
            failureCounts[search.providerId] = componentRegistry.getFailureCount(search.providerId)
        }

        // 1. Governed Capability-Driven Tools from Unified Registry (Rule 9)
        val allTools = componentRegistry.listTools()
        val capableTools = graph.findCapableTools(
            requirements = effectiveRequirements,
            availableTools = allTools,
            networkPolicy = context.networkPolicy,
            isNetworkAvailable = context.isNetworkAvailable,
            failureCounts = failureCounts
        )

        for (tool in capableTools) {
            val decl = tool.declaration
            val toolName = decl.name
            val toolInput = ToolInput(
                toolName = toolName,
                arguments = mapOf("description" to decl.description),
                executionId = task.id.value
            )
            // Pre-CBR-MDP security policy filtering (Rule 12 & Rule 16)
            val secEvaluation = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
            if (secEvaluation.decision != SecurityDecision.DENY) {
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.EXECUTE_TOOL,
                        targetId = toolName,
                        payload = mapOf("description" to decl.description),
                        estimatedLatencyMs = 300L
                    )
                )
            }
        }

        // 2. Search Candidates (only if search capability is required or optional, online, and not already gathered)
        val needsSearch = requiredCaps.contains(CapabilityType.SEARCH) || optionalCaps.contains(CapabilityType.SEARCH)
        val searchAvailable = !isOffline && componentRegistry.getSearchProvider() != null &&
                componentRegistry.isResourceAvailable(componentRegistry.getSearchProvider()?.providerId ?: "")

        if (needsSearch && searchAvailable && !context.hasSearchEvidence) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SEARCH,
                    targetId = "multi_source_search",
                    payload = mapOf("query" to task.input.rawPrompt.take(100)),
                    estimatedLatencyMs = 600L
                )
            )
        }

        // 3. Memory & Knowledge Retrieval (if required/optional or memory repo available)
        val memoryRepo = componentRegistry.getMemoryRepository()
        val needsMemory = requiredCaps.contains(CapabilityType.MEMORY_RETRIEVAL) ||
                requiredCaps.contains(CapabilityType.EMBEDDING) ||
                optionalCaps.contains(CapabilityType.MEMORY_RETRIEVAL)
        if (memoryRepo != null && (needsMemory || (!context.hasMemoryEvidence && currentStep == 0 && requiredCaps.isEmpty()))) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                    targetId = "rag_vector_store",
                    payload = mapOf("query" to task.input.rawPrompt.take(100)),
                    estimatedLatencyMs = 150L
                )
            )
        }

        // 4. LLM Model / Provider Candidates (Rule 7 & Rule 10: Only when LLM capabilities are required/helpful)
        val requiresLlm = requiredCaps.contains(CapabilityType.LLM_GENERATION) ||
                requiredCaps.contains(CapabilityType.REASONING) ||
                requiredCaps.contains(CapabilityType.STREAMING) ||
                requiredCaps.contains(CapabilityType.VISION) ||
                optionalCaps.contains(CapabilityType.LLM_GENERATION) ||
                optionalCaps.contains(CapabilityType.REASONING) ||
                effectiveRequirements.requiredModelCapabilities.isNotEmpty() ||
                (requiredCaps.isEmpty() && capableTools.isEmpty())

        if (requiresLlm) {
            val capableModelDescriptors = graph.findCapableModels(
                requirements = effectiveRequirements,
                networkPolicy = context.networkPolicy,
                isNetworkAvailable = context.isNetworkAvailable,
                failureCounts = failureCounts
            )

            for (desc in capableModelDescriptors) {
                val provider = componentRegistry.getLlmProvider(desc.providerId) ?: continue
                val modelId = provider.metadata.defaultModel ?: "default"
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.SELECT_MODEL,
                        targetId = modelId,
                        payload = mapOf(
                            "providerId" to provider.providerId,
                            "isLocal" to provider.metadata.isLocal.toString(),
                            "step" to currentStep.toString()
                        ),
                        estimatedLatencyMs = if (provider.metadata.isLocal) 200L else 650L
                    )
                )
            }

            // LLM Synthesis / Direct Execution step candidate.
            // P0.5 (Section M — Remove): silent first-provider substitution in
            // ExecutionService is removed, so this candidate now carries an EXPLICIT
            // decision-time provider selection (the explicitly configured default
            // provider) in its payload.
            val defaultProvider = componentRegistry.getLlmProvider()
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.EXECUTE_STEP,
                    targetId = "standard_llm_stream",
                    payload = buildMap {
                        put("prompt", task.input.rawPrompt)
                        put("step", currentStep.toString())
                        if (defaultProvider != null) {
                            put("providerId", defaultProvider.providerId)
                            put("isLocal", defaultProvider.metadata.isLocal.toString())
                        }
                    },
                    estimatedLatencyMs = 750L
                )
            )
        }

        // 5. Workflow / Plan Creation candidate for explicit high-complexity multi-step goals
        if (currentStep == 0 && context.taskComplexity >= 0.85f && task.constraints.maxRetries > 2) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.CREATE_PLAN,
                    targetId = "dag_workflow_planner",
                    payload = mapOf("goal" to task.input.rawPrompt),
                    estimatedLatencyMs = 800L
                )
            )
        }

        // 6. Multi-step Completion Proposal (if required capabilities satisfied or evidence gathered)
        val allRequiredSatisfied = requiredCaps.isEmpty() || context.satisfiedCapabilities.containsAll(requiredCaps)
        if (currentStep >= 1 && (allRequiredSatisfied || context.hasSearchEvidence || context.hasMemoryEvidence || context.hasToolExecutionEvidence || context.accumulatedEvidence.containsKey("synthesizedText"))) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.COMPLETE,
                    targetId = "terminal_complete",
                    payload = mapOf("summary" to "تم استيفاء متطلبات المهمة وتوليد النتيجة النهائية.")
                )
            )
        }

        // 7. Control Actions: Replan, User Intervention, or Blocking Fallbacks (Rule 11 & Rule 18)
        if (context.capabilityGap.status == CapabilityStatus.BLOCKED ||
            context.capabilityGap.status == CapabilityStatus.NO_CAPABLE_RESOURCE ||
            context.capabilityGap.status == CapabilityStatus.CAPABILITY_UNAVAILABLE ||
            (context.capabilityGap.status == CapabilityStatus.CAPABILITY_MISSING && context.missingCapabilities.isNotEmpty() && capableTools.isEmpty())
        ) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.ASK_USER,
                    targetId = "capability_gap_resolution",
                    payload = mapOf(
                        "missingCapabilities" to context.missingCapabilities.joinToString { it.name },
                        "status" to context.capabilityGap.status.name,
                        "reason" to "توجد قدرات إلزامية غير متوفرة أو محجوبة في النظام: ${context.missingCapabilities.joinToString { it.name }}"
                    )
                )
            )
        }

        if (context.consecutiveFailures in 1..2) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.RETRY,
                    targetId = "retry_with_backoff",
                    payload = mapOf("failureCount" to context.consecutiveFailures.toString())
                )
            )
        } else if (context.consecutiveFailures > 2) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.REPLAN,
                    targetId = "degraded_fallback_replan",
                    payload = mapOf("failureCount" to context.consecutiveFailures.toString())
                )
            )
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.ASK_USER,
                    targetId = "user_intervention",
                    payload = mapOf("reason" to "تكرر فشل تنفيذ الإجراء، مطلوب إرشاد المستخدم.")
                )
            )
        }

        return candidates
    }

    /**
     * Executes the CBR-MDP decision evaluation over the given DecisionContext,
     * scoring all candidate actions against historical cases and expected MDP utility,
     * and enforcing deterministic security/governance constraints.
     */
    fun evaluate(context: DecisionContext): DecisionResult {
        val state = context.toDecisionState()
        val candidateActions = generateCandidateActions(context)

        // 1. Evaluate through CBR-MDP Engine
        val rawDecision = cbrMdpEngine.evaluateAndSelectAction(state, candidateActions)

        // 2. Deterministic Governance & Security Validation
        val validatedAction = enforceGovernance(rawDecision.chosenAction, context)

        return if (validatedAction != rawDecision.chosenAction) {
            rawDecision.copy(
                chosenAction = validatedAction,
                rationale = "${rawDecision.rationale} [تم تطبيق سياسة الحوكمة والأمان لمنع الإجراء غير المسموح به]"
            )
        } else {
            rawDecision
        }
    }

    /**
     * Enforces governance and security boundaries over the selected decision action.
     */
    private fun enforceGovernance(action: DecisionAction, context: DecisionContext): DecisionAction {
        // Enforce Offline Policy
        if (context.networkPolicy == NetworkPolicy.OFFLINE) {
            if (action.type == DecisionActionType.SEARCH) {
                return DecisionAction(
                    type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                    targetId = "local_memory",
                    payload = mapOf("offline_fallback" to "true")
                )
            }
        }

        // Check tool actions with SecurityGuardService
        if ((action.type == DecisionActionType.SELECT_TOOL || action.type == DecisionActionType.EXECUTE_TOOL) && action.targetId != null) {
            val toolInput = ToolInput(
                toolName = action.targetId,
                arguments = action.payload,
                executionId = context.task.id.value
            )
            val secEvaluation = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
            if (secEvaluation.decision == SecurityDecision.DENY) {
                return DecisionAction(
                    type = DecisionActionType.ASK_USER,
                    targetId = action.targetId,
                    payload = mapOf("reason" to secEvaluation.explanation)
                )
            }
        }

        return action
    }

    /**
     * Dynamically selects the most suitable agent using CapabilityResourceGraph as the authoritative truth (Rule 8).
     */
    fun selectSuitableAgent(
        task: TaskDefinition,
        availableAgents: List<AgentDefinition>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): AgentDefinition? {
        if (availableAgents.isEmpty()) return null

        val requirements = resolveTaskRequirements(task)
        val capabilities = componentRegistry.getCapabilityDescriptors()
        val graph = CapabilityResourceGraph(capabilities)

        val capableAgents = graph.findCapableAgents(
            requirements = requirements,
            availableAgents = availableAgents,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable
        )

        return capableAgents.firstOrNull()
    }

    /**
     * Resolves task requirements using structured TaskSpecification / TaskCapabilityRequirements
     * as the authoritative source of truth, expanding transitive prerequisites (Rule 2, 3, 6, 7).
     */
    fun resolveTaskRequirements(task: TaskDefinition): TaskCapabilityRequirements {
        // Tier 1: TaskSpecification
        task.specification?.requirements?.let { specReqs ->
            if (specReqs.requiredCapabilities.isNotEmpty() || specReqs.optionalCapabilities.isNotEmpty()) {
                val expandedRequired = CapabilityPrerequisites.resolvePrerequisites(specReqs.requiredCapabilities).resolvedCapabilities
                return specReqs.copy(requiredCapabilities = expandedRequired)
            }
        }

        // Tier 2: Existing task requirements
        val existing = task.requirements
        if (existing.requiredCapabilities.isNotEmpty() || existing.optionalCapabilities.isNotEmpty()) {
            val expandedRequired = CapabilityPrerequisites.resolvePrerequisites(existing.requiredCapabilities).resolvedCapabilities
            return existing.copy(requiredCapabilities = expandedRequired)
        }

        // Tier 3: Parameters / context variables
        val paramSpec = task.input.parameters["specification"]
        if (paramSpec is TaskSpecification && (paramSpec.requirements.requiredCapabilities.isNotEmpty() || paramSpec.requirements.optionalCapabilities.isNotEmpty())) {
            val expandedRequired = CapabilityPrerequisites.resolvePrerequisites(paramSpec.requirements.requiredCapabilities).resolvedCapabilities
            return paramSpec.requirements.copy(requiredCapabilities = expandedRequired)
        }

        val paramReqs = task.input.parameters["requirements"]
        if (paramReqs is TaskCapabilityRequirements && (paramReqs.requiredCapabilities.isNotEmpty() || paramReqs.optionalCapabilities.isNotEmpty())) {
            val expandedRequired = CapabilityPrerequisites.resolvePrerequisites(paramReqs.requiredCapabilities).resolvedCapabilities
            return paramReqs.copy(requiredCapabilities = expandedRequired)
        }

        // Tier 4: Heuristic fallback with explicit provenance tracking (Rule 3)
        val reqCaps = mutableSetOf<CapabilityType>()
        val optCaps = mutableSetOf<CapabilityType>()
        val prompt = task.input.rawPrompt.lowercase()

        val isCodeTask = prompt.contains("code") || prompt.contains("برمج") || prompt.contains("kotlin") || prompt.contains("function")
        val isSearchTask = prompt.contains("search") || prompt.contains("بحث") || prompt.contains("internet") || prompt.contains("ويب")
        val isFileTask = prompt.contains("file") || prompt.contains("ملف") || prompt.contains("مجلد") || prompt.contains("storage")
        val isMemoryTask = prompt.contains("memory") || prompt.contains("ذاكرة") || prompt.contains("rag")
        val isSecurityTask = prompt.contains("security") || prompt.contains("أمان") || prompt.contains("audit")
        val isHashTask = prompt.contains("hash") || prompt.contains("تجزئة") || prompt.contains("sha") || prompt.contains("md5")

        if (isCodeTask) {
            reqCaps.add(CapabilityType.TOOL_EXECUTION)
            reqCaps.add(CapabilityType.CODE_ENGINEERING)
            optCaps.add(CapabilityType.SHELL_EXECUTION)
        }
        if (isSearchTask) {
            reqCaps.add(CapabilityType.SEARCH)
        }
        if (isFileTask) {
            reqCaps.add(CapabilityType.TOOL_EXECUTION)
            reqCaps.add(CapabilityType.FILE_STORAGE)
        }
        if (isMemoryTask) {
            reqCaps.add(CapabilityType.MEMORY_RETRIEVAL)
            reqCaps.add(CapabilityType.EMBEDDING)
        }
        if (isSecurityTask) {
            reqCaps.add(CapabilityType.SECURITY_AUDIT)
            optCaps.add(CapabilityType.SHELL_EXECUTION)
        }
        if (isHashTask) {
            reqCaps.add(CapabilityType.TOOL_EXECUTION)
            reqCaps.add(CapabilityType.HASH_COMPUTATION)
        }

        // Deterministic tasks (e.g. pure hash/file without generative instructions) do NOT require LLM_GENERATION (Rule 20)
        val isPurelyDeterministic = isHashTask && !isCodeTask && !isSearchTask && !prompt.contains("explain") && !prompt.contains("اشرح")
        if (!isPurelyDeterministic) {
            reqCaps.add(CapabilityType.LLM_GENERATION)
        }

        val expandedRequired = CapabilityPrerequisites.resolvePrerequisites(reqCaps).resolvedCapabilities

        return TaskCapabilityRequirements(
            requiredCapabilities = expandedRequired,
            optionalCapabilities = optCaps,
            networkRequirement = if (prompt.contains("offline") || prompt.contains("دون اتصال")) NetworkPolicy.OFFLINE else NetworkPolicy.HYBRID
        )
    }

    /**
     * Updates CBR-MDP transition beliefs and case memory upon receiving an execution observation.
     */
    fun recordObservation(
        state: DecisionState,
        observation: EnvironmentObservation
    ): DecisionState {
        return cbrMdpEngine.processObservationAndUpdateBelief(state, observation)
    }

    fun getCbrMdpEngine(): CbrMdpEngine = cbrMdpEngine

    // =========================================================================
    // P0 RESOURCE CONTRACT — DecisionRecord emission (APPROVED-BASELINE v2.1,
    // Section F / P0.4 — LOCKED).
    // =========================================================================

    /**
     * Produces the LOCKED [DecisionRecord] for a capability-requiring decision
     * (P0.4: "DecisionService MUST produce this record for every capability-requiring
     * decision. No legacy output form.").
     *
     * Contract compliance:
     * - selectedResourceId is MANDATORY and non-null; when no usable resource exists
     *   (usable conjunction, Section E) this returns null — the caller treats it as
     *   an explicit no-capable-resource state, never as a silent substitution.
     * - governanceResult is NEVER null — explicit NOT_APPLICABLE default (RULE GOV-1/2).
     * - candidateEvaluations always contains at least the selected resource's evaluation.
     * - fallbackPolicy is present as a planning hint only — never execution authority
     *   (RULE FB-1).
     * - Scoring is the Section O heuristic multi-objective evaluation: capability fit,
     *   health, latency, cost — deterministic, with a documented formula.
     *
     * @param requiredCapabilityIds capability ids (CapabilityType.code) required by the step.
     * @param preferredResourceIds optional PreferAlternative hint (planning only).
     * @return the persisted DecisionRecord, or null when no usable resource exists.
     */
    suspend fun evaluateWithRecord(
        taskId: String,
        stepId: String,
        requiredCapabilityIds: Set<String>,
        preferredResourceIds: List<String> = emptyList()
    ): com.example.domain.core.resource.DecisionRecord? {
        val registry = resourceRegistry
            ?: error("ResourceRegistryService is not wired — P0 contract requires it for capability-requiring decisions")
        val health = resourceHealthService

        // 1. Candidate retrieval: union over required capabilities of the registry's
        //    usable conjunction (HEALTHY AND runtimeSupported AND NOT in cooldown).
        val candidates = linkedMapOf<com.example.domain.core.resource.ResourceId, com.example.domain.core.resource.UsableResource>()
        for (capabilityId in requiredCapabilityIds) {
            for (usable in registry.queryUsableByCapability(capabilityId)) {
                candidates.putIfAbsent(usable.resourceId, usable)
            }
        }
        if (candidates.isEmpty()) return null

        // 2. Deterministic multi-objective scoring (Section O heuristic):
        //    finalScore = 0.50*capabilityFit + 0.30*healthScore
        //               + 0.15*latencyFactor + 0.05*costFactor (+0.05 PreferAlternative hint)
        //    latencyFactor: 1.0 if avgLatency <= 500ms, 0.5 floor at >= 5000ms
        //    costFactor:    1.0 - min(costPer1kTokens metadata, 1.0) * 0.5
        val evaluations = candidates.values.map { usable ->
            val capabilitySet = usable.capabilities.toSet()
            val fit = if (requiredCapabilityIds.isEmpty()) 1.0
            else requiredCapabilityIds.count { capabilitySet.contains(it) }.toDouble() / requiredCapabilityIds.size
            val resourceHealth = health?.getHealth(usable.resourceId)
            val healthScore = resourceHealth?.healthScore ?: 0.0
            val avgLatency = resourceHealth?.averageLatencyMs ?: 0L
            val latencyFactor = when {
                avgLatency <= 500L -> 1.0
                avgLatency >= 5000L -> 0.5
                else -> 1.0 - 0.5 * (avgLatency - 500.0) / 4500.0
            }
            val cost = usable.run {
                registry.get(usable.resourceId)?.metadata?.get("costPer1kTokens")?.toDoubleOrNull() ?: 0.0
            }.coerceIn(0.0, 1.0)
            val costFactor = 1.0 - cost * 0.5
            val preferenceBoost = if (preferredResourceIds.contains(usable.resourceId.value)) 0.05 else 0.0
            val score = (0.50 * fit + 0.30 * healthScore + 0.15 * latencyFactor + 0.05 * costFactor + preferenceBoost)
                .coerceIn(0.0, 1.0)
            com.example.domain.core.resource.CandidateEvaluation(
                resourceId = usable.resourceId,
                providerId = usable.providerId,
                serviceId = usable.serviceId,
                capabilityFit = fit,
                healthScore = healthScore,
                estimatedLatencyMs = avgLatency,
                estimatedCost = cost,
                finalScore = score,
                isSelected = false,
                rationale = "fit=${"%.2f".format(fit)}, health=${"%.2f".format(healthScore)}, latency=${avgLatency}ms, cost=${"%.2f".format(cost)}"
            )
        }.sortedWith(
            compareByDescending<com.example.domain.core.resource.CandidateEvaluation> { it.finalScore }
                .thenBy { it.resourceId.value } // deterministic tie-break
        )

        val selected = evaluations.first()
        val selectedRecord = requireNotNull(candidates[selected.resourceId])

        // 3. Security & governance (explicit, auditable — Section F/H).
        val securityResult = com.example.domain.core.resource.SecurityResult.permitted(
            reason = "Resource selection (non-tool) has no SecurityGuard rule in P0; tool executions are evaluated at execution time."
        )
        val governanceResult = com.example.domain.core.resource.GovernanceResult.NOT_APPLICABLE

        // 4. Version + persistence. decisionVersion increments for re-decisions of the
        //    same (taskId, stepId) (acceptance test 10).
        val version = (decisionRecordStore?.latestVersionFor(taskId, stepId) ?: 0) + 1
        val decisionId = java.util.UUID.randomUUID().toString()
        val record = com.example.domain.core.resource.DecisionRecord(
            decisionId = decisionId,
            taskId = taskId,
            stepId = stepId,
            timestamp = System.currentTimeMillis(),
            decisionVersion = version,
            selectedResourceId = selected.resourceId,
            selectedProviderId = selected.providerId,
            selectedServiceId = selected.serviceId,
            selectedConfigurationVersion = selectedRecord.configurationVersion,
            selectedAgentId = null, // null in P0 (Section F)
            selectedToolIds = emptyList(),
            requiredCapabilities = requiredCapabilityIds,
            candidateEvaluations = evaluations.map { if (it.resourceId == selected.resourceId) it.copy(isSelected = true) else it },
            decisionRationale = "Heuristic multi-objective scoring selected ${selected.resourceId.value} (score=${"%.3f".format(selected.finalScore)}): ${selected.rationale}",
            confidence = selected.finalScore,
            securityResult = securityResult,
            governanceResult = governanceResult,
            fallbackPolicy = com.example.domain.core.resource.FallbackPolicy.Fail
        )
        decisionRecordStore?.save(record)
        return record
    }
}

