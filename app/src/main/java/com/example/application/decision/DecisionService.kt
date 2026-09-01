package com.example.application.decision

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityGapAnalysis
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
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy()
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

        // Derive evidence-based satisfied capabilities
        val currentlySatisfied = mutableSetOf<CapabilityType>()
        if (accumulatedEvidence.containsKey("searchResults") || (lastAction?.type == DecisionActionType.SEARCH && lastObservation?.isSuccess == true)) {
            currentlySatisfied.add(CapabilityType.SEARCH)
        }
        if (accumulatedEvidence.containsKey("memorySnippets") || ((lastAction?.type == DecisionActionType.RETRIEVE_MEMORY || lastAction?.type == DecisionActionType.RETRIEVE_KNOWLEDGE) && lastObservation?.isSuccess == true)) {
            currentlySatisfied.add(CapabilityType.MEMORY_RETRIEVAL)
            currentlySatisfied.add(CapabilityType.EMBEDDING)
        }
        if (accumulatedEvidence.containsKey("synthesizedText") || ((lastAction?.type == DecisionActionType.EXECUTE_STEP || lastAction?.type == DecisionActionType.SELECT_MODEL) && lastObservation?.isSuccess == true)) {
            currentlySatisfied.add(CapabilityType.LLM_GENERATION)
            currentlySatisfied.add(CapabilityType.REASONING)
        }
        if (accumulatedEvidence.containsKey("toolOutput") || accumulatedEvidence.containsKey("toolAttributes") || (lastAction?.type == DecisionActionType.EXECUTE_TOOL && lastObservation?.isSuccess == true)) {
            currentlySatisfied.add(CapabilityType.TOOL_EXECUTION)
            val executedToolName = lastAction?.targetId
            if (executedToolName != null) {
                val executedTool = componentRegistry.getTool(executedToolName)
                if (executedTool != null) {
                    currentlySatisfied.addAll(executedTool.declaration.providedCapabilities)
                }
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

            // LLM Synthesis / Direct Execution step candidate
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.EXECUTE_STEP,
                    targetId = "standard_llm_stream",
                    payload = mapOf("prompt" to task.input.rawPrompt, "step" to currentStep.toString()),
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
     * Resolves task requirements using structured TaskCapabilityRequirements as the authoritative source of truth (Rule 6, 7, 20).
     */
    fun resolveTaskRequirements(task: TaskDefinition): TaskCapabilityRequirements {
        val existing = task.requirements
        if (existing.requiredCapabilities.isNotEmpty() || existing.optionalCapabilities.isNotEmpty()) {
            return existing
        }

        // Check if requirements are embedded in task parameters / context variables
        val paramReqs = task.input.parameters["requirements"]
        if (paramReqs is TaskCapabilityRequirements && (paramReqs.requiredCapabilities.isNotEmpty() || paramReqs.optionalCapabilities.isNotEmpty())) {
            return paramReqs
        }

        // Infer requirements only as structured fallback
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

        // Deterministic tasks (e.g. pure hash/file without generative instructions) do NOT require LLM_GENERATION
        val isPurelyDeterministic = isHashTask && !isCodeTask && !isSearchTask && !prompt.contains("explain") && !prompt.contains("اشرح")
        if (!isPurelyDeterministic) {
            reqCaps.add(CapabilityType.LLM_GENERATION)
        }

        return TaskCapabilityRequirements(
            requiredCapabilities = reqCaps,
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

