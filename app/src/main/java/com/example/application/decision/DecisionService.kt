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
import com.example.domain.core.capability.ResourceCapabilityGraph
import com.example.domain.core.capability.SideEffectClassification
import com.example.domain.core.decision.CandidateEvaluation
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceType
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
    val resourceCapabilityGraph: ResourceCapabilityGraph,
    private val securityGuard: SecurityGuardService,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy(),
    private val componentRegistry: ComponentRegistry? = null
) {

    constructor(
        cbrMdpEngine: CbrMdpEngine,
        componentRegistry: ComponentRegistry,
        securityGuard: SecurityGuardService,
        defaultSecurityPolicy: SecurityPolicy = SecurityPolicy()
    ) : this(
        cbrMdpEngine = cbrMdpEngine,
        resourceCapabilityGraph = componentRegistry.resourceCapabilityGraph,
        securityGuard = securityGuard,
        defaultSecurityPolicy = defaultSecurityPolicy,
        componentRegistry = componentRegistry
    )

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
                        componentRegistry?.getTool(toolName)?.declaration?.providedCapabilities?.let {
                            currentlySatisfied.addAll(it)
                        }
                    }
                }
                else -> Unit
            }
        }

        val capabilities = componentRegistry?.getCapabilityDescriptors() ?: emptyList()
        val graph = CapabilityResourceGraph(capabilities)

        // Build failure counts map for graph analysis
        val failureCounts = mutableMapOf<String, Int>()
        componentRegistry?.listTools()?.forEach { tool ->
            failureCounts[tool.declaration.name] = componentRegistry.getFailureCount(tool.declaration.name)
        }
        componentRegistry?.listLlmProviders()?.forEach { provider ->
            failureCounts[provider.providerId] = componentRegistry.getFailureCount(provider.providerId)
        }
        componentRegistry?.getSearchProvider()?.let { search ->
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

        val availableTools = componentRegistry?.listTools()?.map { it.declaration.name } ?: emptyList()

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
     * Dynamically generates the candidate action space based on authoritative ResourceCapabilityGraph,
     * network policies, and task requirements.
     */
    fun generateCandidateActions(context: DecisionContext): List<DecisionAction> {
        val candidates = mutableListOf<DecisionAction>()
        val task = context.task
        val currentStep = task.currentStepIndex
        val isOffline = context.networkPolicy == NetworkPolicy.OFFLINE || !context.isNetworkAvailable
        val effectiveRequirements = task.requirements
        val requiredCaps = effectiveRequirements.requiredCapabilities
        val optionalCaps = effectiveRequirements.optionalCapabilities

        // 0. Agent Selection Candidate
        if (task.assignedAgentId.value.isNotBlank() && currentStep == 0 && context.lastAction?.type != DecisionActionType.SELECT_AGENT) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SELECT_AGENT,
                    targetId = task.assignedAgentId.value,
                    payload = mapOf("agentId" to task.assignedAgentId.value),
                    estimatedLatencyMs = 50L
                )
            )
        }

        // 1. Tool Candidates from ResourceCapabilityGraph
        val toolCandidates = resourceCapabilityGraph.findCandidatesByType(
            ResourceType.TOOL,
            context.networkPolicy,
            context.isNetworkAvailable
        )

        for (toolCand in toolCandidates) {
            if (componentRegistry?.isResourceAvailable(toolCand.resourceId.value) == false) {
                continue
            }
            val toolName = toolCand.serviceId
            val toolDesc = toolCand.metadata["description"] ?: toolName
            val toolInput = ToolInput(
                toolName = toolName,
                arguments = mapOf("description" to toolDesc),
                executionId = task.id.value
            )
            val secEvaluation = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
            if (secEvaluation.decision != SecurityDecision.DENY) {
                val decisionRecord = DecisionRecord(
                    selectedResourceId = toolCand.resourceId,
                    providerId = toolCand.providerId,
                    serviceId = toolCand.serviceId,
                    configurationVersion = toolCand.configurationVersion,
                    requiredCapabilities = setOf(CapabilityType.TOOL_EXECUTION),
                    rationale = "Selected tool resource '${toolCand.resourceId.value}' via ResourceCapabilityGraph",
                    confidence = 0.9f
                )
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.EXECUTE_TOOL,
                        targetId = toolCand.resourceId.value,
                        payload = mapOf(
                            "resourceId" to toolCand.resourceId.value,
                            "toolName" to toolName,
                            "description" to toolDesc
                        ),
                        estimatedLatencyMs = 300L,
                        decisionRecord = decisionRecord
                    )
                )
            }
        }

        // 2. Search Candidates from ResourceCapabilityGraph
        val needsSearch = requiredCaps.contains(CapabilityType.SEARCH) || optionalCaps.contains(CapabilityType.SEARCH)
        if (needsSearch && !isOffline && !context.hasSearchEvidence) {
            val searchCandidates = resourceCapabilityGraph.findCandidatesByType(
                ResourceType.SEARCH,
                context.networkPolicy,
                context.isNetworkAvailable
            ).filter { it.capabilities.contains(CapabilityType.SEARCH) }

            for (searchCand in searchCandidates) {
                val decisionRecord = DecisionRecord(
                    selectedResourceId = searchCand.resourceId,
                    providerId = searchCand.providerId,
                    serviceId = searchCand.serviceId,
                    configurationVersion = searchCand.configurationVersion,
                    requiredCapabilities = setOf(CapabilityType.SEARCH),
                    rationale = "Selected search resource '${searchCand.resourceId.value}' via ResourceCapabilityGraph",
                    confidence = 0.9f
                )
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.SEARCH,
                        targetId = searchCand.resourceId.value,
                        payload = mapOf(
                            "query" to task.input.rawPrompt.take(100),
                            "resourceId" to searchCand.resourceId.value
                        ),
                        estimatedLatencyMs = 600L,
                        decisionRecord = decisionRecord
                    )
                )
            }
        }

        // 3. Memory & Knowledge Retrieval from ResourceCapabilityGraph
        val needsMemory = requiredCaps.contains(CapabilityType.MEMORY_RETRIEVAL) ||
                requiredCaps.contains(CapabilityType.EMBEDDING) ||
                optionalCaps.contains(CapabilityType.MEMORY_RETRIEVAL)
        if (needsMemory || (!context.hasMemoryEvidence && currentStep == 0 && requiredCaps.isEmpty())) {
            val embeddingCandidates = resourceCapabilityGraph.findCandidatesByType(
                ResourceType.EMBEDDING,
                context.networkPolicy,
                context.isNetworkAvailable
            )
            for (embCand in embeddingCandidates) {
                val decisionRecord = DecisionRecord(
                    selectedResourceId = embCand.resourceId,
                    providerId = embCand.providerId,
                    serviceId = embCand.serviceId,
                    configurationVersion = embCand.configurationVersion,
                    requiredCapabilities = setOf(CapabilityType.EMBEDDING, CapabilityType.MEMORY_RETRIEVAL),
                    rationale = "Selected embedding resource '${embCand.resourceId.value}' via ResourceCapabilityGraph",
                    confidence = 0.85f
                )
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                        targetId = embCand.resourceId.value,
                        payload = mapOf(
                            "query" to task.input.rawPrompt.take(100),
                            "resourceId" to embCand.resourceId.value
                        ),
                        estimatedLatencyMs = 150L,
                        decisionRecord = decisionRecord
                    )
                )
            }
        }

        // 4. LLM Model / Provider Candidates from ResourceCapabilityGraph
        val requiresLlm = requiredCaps.contains(CapabilityType.LLM_GENERATION) ||
                requiredCaps.contains(CapabilityType.REASONING) ||
                requiredCaps.contains(CapabilityType.STREAMING) ||
                requiredCaps.contains(CapabilityType.VISION) ||
                optionalCaps.contains(CapabilityType.LLM_GENERATION) ||
                optionalCaps.contains(CapabilityType.REASONING) ||
                effectiveRequirements.requiredModelCapabilities.isNotEmpty() ||
                (requiredCaps.isEmpty() && candidates.isEmpty())

        if (requiresLlm) {
            val llmCandidates = resourceCapabilityGraph.findCandidatesByType(
                ResourceType.LLM,
                context.networkPolicy,
                context.isNetworkAvailable
            ).filter { cand ->
                requiredCaps.isEmpty() || cand.capabilities.any { it in requiredCaps }
            }

            for (llmCand in llmCandidates) {
                val decisionRecord = DecisionRecord(
                    selectedResourceId = llmCand.resourceId,
                    providerId = llmCand.providerId,
                    serviceId = llmCand.serviceId,
                    configurationVersion = llmCand.configurationVersion,
                    requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING),
                    rationale = "Selected LLM resource '${llmCand.resourceId.value}' via ResourceCapabilityGraph",
                    confidence = 0.95f
                )
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.SELECT_MODEL,
                        targetId = llmCand.resourceId.value,
                        payload = mapOf(
                            "resourceId" to llmCand.resourceId.value,
                            "providerId" to llmCand.providerId,
                            "serviceId" to llmCand.serviceId,
                            "isLocal" to llmCand.isLocal.toString(),
                            "step" to currentStep.toString()
                        ),
                        estimatedLatencyMs = if (llmCand.isLocal) 200L else 650L,
                        decisionRecord = decisionRecord
                    )
                )
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.EXECUTE_STEP,
                        targetId = llmCand.resourceId.value,
                        payload = mapOf(
                            "prompt" to task.input.rawPrompt,
                            "resourceId" to llmCand.resourceId.value,
                            "providerId" to llmCand.providerId,
                            "serviceId" to llmCand.serviceId,
                            "step" to currentStep.toString()
                        ),
                        estimatedLatencyMs = if (llmCand.isLocal) 300L else 750L,
                        decisionRecord = decisionRecord
                    )
                )
            }
            if (llmCandidates.isEmpty()) {
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.EXECUTE_STEP,
                        targetId = "default_llm_step",
                        payload = mapOf(
                            "prompt" to task.input.rawPrompt,
                            "step" to currentStep.toString()
                        ),
                        estimatedLatencyMs = 500L
                    )
                )
            }
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

        // 7. Control Actions: Replan, User Intervention, or Blocking Fallbacks
        val unsatisfiedRequiredCaps = requiredCaps.filter { reqCap ->
            !context.satisfiedCapabilities.contains(reqCap) &&
                    candidates.none { it.decisionRecord?.requiredCapabilities?.contains(reqCap) == true }
        }
        if (unsatisfiedRequiredCaps.isNotEmpty()) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.ASK_USER,
                    targetId = "capability_gap_resolution",
                    payload = mapOf(
                        "missingCapabilities" to unsatisfiedRequiredCaps.joinToString { it.name },
                        "reason" to "No usable resource in ResourceCapabilityGraph satisfies required capabilities: ${unsatisfiedRequiredCaps.joinToString { it.name }}"
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

        val candidateEvals = rawDecision.evaluatedAlternatives.mapNotNull { scored ->
            scored.action.decisionRecord?.let {
                CandidateEvaluation(
                    resourceId = it.selectedResourceId,
                    score = scored.finalScore,
                    rationale = scored.reason
                )
            }
        }

        val enrichedAction = if (validatedAction.decisionRecord != null) {
            validatedAction.copy(
                decisionRecord = validatedAction.decisionRecord.copy(
                    candidateEvaluations = candidateEvals,
                    confidence = rawDecision.confidence,
                    rationale = rawDecision.rationale
                )
            )
        } else {
            validatedAction
        }

        return rawDecision.copy(
            chosenAction = enrichedAction,
            decisionRecord = enrichedAction.decisionRecord,
            rationale = if (validatedAction != rawDecision.chosenAction) {
                "${rawDecision.rationale} [تم تطبيق سياسة الحوكمة والأمان لمنع الإجراء غير المسموح به]"
            } else {
                rawDecision.rationale
            }
        )
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
        val capabilities = componentRegistry?.getCapabilityDescriptors() ?: emptyList()
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
}

