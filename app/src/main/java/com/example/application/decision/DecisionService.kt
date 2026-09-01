package com.example.application.decision

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityState
import com.example.domain.core.capability.CapabilityType
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
        val capabilities = componentRegistry.getCapabilityDescriptors()
        val availableTools = componentRegistry.listTools().map { it.declaration.name }

        return DecisionContext(
            task = task,
            workspace = workspace,
            resourceGraph = workspace?.resourceGraph ?: com.example.domain.core.workspace.ResourceGraph(),
            capabilities = capabilities,
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
        val effectiveRequirements = resolveTaskRequirements(task)

        // 1. LLM Model / Provider Candidates
        val llmProviders = componentRegistry.listLlmProviders()
        for (provider in llmProviders) {
            val isAvailable = componentRegistry.isResourceAvailable(provider.providerId)
            val isLocal = provider.metadata.isLocal
            if (isOffline && !isLocal) continue
            if (!isAvailable && llmProviders.size > 1) continue

            val modelId = provider.metadata.defaultModel ?: "default"
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SELECT_MODEL,
                    targetId = modelId,
                    payload = mapOf(
                        "providerId" to provider.providerId,
                        "isLocal" to isLocal.toString(),
                        "step" to currentStep.toString()
                    ),
                    estimatedLatencyMs = if (isLocal) 200L else 650L
                )
            )
        }

        // If no provider candidates added (e.g. empty registry), add candidate so execution service can evaluate and report error
        if (candidates.isEmpty() && !isOffline) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SELECT_MODEL,
                    targetId = "default",
                    payload = mapOf("providerId" to "default", "step" to currentStep.toString()),
                    estimatedLatencyMs = 700L
                )
            )
        }

        // 2. Search Candidates (only if search capability is required or helpful, online, and not already gathered)
        val needsSearch = effectiveRequirements.requiredCapabilities.contains(CapabilityType.SEARCH) ||
                effectiveRequirements.optionalCapabilities.contains(CapabilityType.SEARCH)
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
        val needsMemory = effectiveRequirements.requiredCapabilities.contains(CapabilityType.MEMORY_RETRIEVAL) ||
                effectiveRequirements.requiredCapabilities.contains(CapabilityType.EMBEDDING) ||
                effectiveRequirements.optionalCapabilities.contains(CapabilityType.MEMORY_RETRIEVAL)
        if (memoryRepo != null && (needsMemory || (!context.hasMemoryEvidence && currentStep == 0))) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                    targetId = "rag_vector_store",
                    payload = mapOf("query" to task.input.rawPrompt.take(100)),
                    estimatedLatencyMs = 150L
                )
            )
        }

        // 4. Governed Capability-Driven Tools from Unified Registry
        val tools = componentRegistry.listTools()
        for (tool in tools) {
            val toolName = tool.declaration.name
            if (!componentRegistry.isResourceAvailable(toolName)) continue

            // Determine tool's capability suitability for the task
            val isMatchingCapability = isToolMatchingRequirements(toolName, tool.declaration.description, effectiveRequirements)

            if (isMatchingCapability) {
                val toolInput = ToolInput(
                    toolName = toolName,
                    arguments = mapOf("description" to tool.declaration.description),
                    executionId = task.id.value
                )
                val secEvaluation = securityGuard.evaluateToolExecution(toolInput, defaultSecurityPolicy)
                if (secEvaluation.decision != SecurityDecision.DENY) {
                    candidates.add(
                        DecisionAction(
                            type = DecisionActionType.EXECUTE_TOOL,
                            targetId = toolName,
                            payload = mapOf("description" to tool.declaration.description),
                            estimatedLatencyMs = 300L
                        )
                    )
                }
            }
        }

        // 5. LLM Synthesis / Direct Execution step candidate
        candidates.add(
            DecisionAction(
                type = DecisionActionType.EXECUTE_STEP,
                targetId = "standard_llm_stream",
                payload = mapOf("prompt" to task.input.rawPrompt, "step" to currentStep.toString()),
                estimatedLatencyMs = 750L
            )
        )

        // 6. Workflow / Plan Creation candidate for explicit high-complexity multi-step goals
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

        // 7. Multi-step Completion Proposal (if evidence gathered or step executed)
        if (currentStep >= 1 && (context.hasSearchEvidence || context.hasMemoryEvidence || context.hasToolExecutionEvidence || context.accumulatedEvidence.containsKey("synthesizedText"))) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.COMPLETE,
                    targetId = "terminal_complete",
                    payload = mapOf("summary" to "تم استيفاء متطلبات المهمة وتوليد النتيجة النهائية.")
                )
            )
        }

        // 8. Replan or Retry upon consecutive failures
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
     * Dynamically selects the most suitable agent using multi-attribute capability matching.
     */
    fun selectSuitableAgent(
        task: TaskDefinition,
        availableAgents: List<AgentDefinition>
    ): AgentDefinition? {
        if (availableAgents.isEmpty()) return null

        val requirements = resolveTaskRequirements(task)
        val requiredCaps = requirements.requiredCapabilities
        val prohibitedCaps = requirements.prohibitedCapabilities

        // Filter: Agent must have budget and must not have prohibited capabilities
        val eligibleAgents = availableAgents.filter { agent ->
            val hasBudget = agent.budget.maxTokens > 0
            val noProhibited = prohibitedCaps.none { agent.allowedCapabilities.contains(it) }
            hasBudget && noProhibited
        }

        if (eligibleAgents.isEmpty()) return null

        // Score agents based on capability overlap, role alignment, and budget
        val scoredAgents = eligibleAgents.map { agent ->
            var score = 0.0

            // Required capability overlap
            val matchedRequired = requiredCaps.count { agent.allowedCapabilities.contains(it) }
            score += matchedRequired * 3.0

            // Optional capability overlap
            val matchedOptional = requirements.optionalCapabilities.count { agent.allowedCapabilities.contains(it) }
            score += matchedOptional * 1.0

            // Role alignment
            if (requiredCaps.contains(CapabilityType.TOOL_EXECUTION) && agent.identity.role == AgentRole.CODER) score += 2.0
            if (requiredCaps.contains(CapabilityType.SEARCH) && agent.identity.role == AgentRole.RESEARCHER) score += 2.0
            if (requiredCaps.contains(CapabilityType.SHELL_EXECUTION) && agent.identity.role == AgentRole.SECURITY_GUARD) score += 2.0
            if (requiredCaps.contains(CapabilityType.LLM_GENERATION) && agent.identity.role == AgentRole.PLANNER) score += 1.5

            // Fallback general suitability
            if (agent.identity.role == AgentRole.GENERAL_ASSISTANT) score += 0.5

            Pair(agent, score)
        }.sortedByDescending { it.second }

        return scoredAgents.firstOrNull()?.first ?: availableAgents.firstOrNull()
    }

    /**
     * Resolves task requirements either from explicit structured TaskCapabilityRequirements
     * or by inferring them from task metadata and input parameters.
     */
    fun resolveTaskRequirements(task: TaskDefinition): TaskCapabilityRequirements {
        val existing = task.requirements
        if (existing.requiredCapabilities.isNotEmpty() || existing.optionalCapabilities.isNotEmpty()) {
            return existing
        }

        // Infer requirements from task definition properties and prompt semantics
        val reqCaps = mutableSetOf<CapabilityType>()
        val optCaps = mutableSetOf<CapabilityType>()
        val prompt = task.input.rawPrompt.lowercase()

        // Core capability types based on task content
        if (prompt.contains("code") || prompt.contains("برمج") || prompt.contains("kotlin") || prompt.contains("class") || prompt.contains("function")) {
            reqCaps.add(CapabilityType.TOOL_EXECUTION)
            optCaps.add(CapabilityType.SHELL_EXECUTION)
        }
        if (prompt.contains("search") || prompt.contains("بحث") || prompt.contains("latest") || prompt.contains("أحدث") || prompt.contains("internet") || prompt.contains("ويب")) {
            reqCaps.add(CapabilityType.SEARCH)
        }
        if (prompt.contains("file") || prompt.contains("ملف") || prompt.contains("مجلد") || prompt.contains("directory") || prompt.contains("storage")) {
            reqCaps.add(CapabilityType.TOOL_EXECUTION)
            reqCaps.add(CapabilityType.FILE_STORAGE)
        }
        if (prompt.contains("memory") || prompt.contains("ذاكرة") || prompt.contains("rag") || prompt.contains("وثيقة") || prompt.contains("document")) {
            reqCaps.add(CapabilityType.MEMORY_RETRIEVAL)
            reqCaps.add(CapabilityType.EMBEDDING)
        }
        if (prompt.contains("security") || prompt.contains("أمان") || prompt.contains("audit") || prompt.contains("فحص")) {
            reqCaps.add(CapabilityType.SHELL_EXECUTION)
        }

        // Always requires LLM generation by default for synthesis
        reqCaps.add(CapabilityType.LLM_GENERATION)

        return TaskCapabilityRequirements(
            requiredCapabilities = reqCaps,
            optionalCapabilities = optCaps,
            networkRequirement = if (prompt.contains("offline") || prompt.contains("دون اتصال")) NetworkPolicy.OFFLINE else NetworkPolicy.HYBRID
        )
    }

    private fun isToolMatchingRequirements(
        toolName: String,
        toolDescription: String,
        requirements: TaskCapabilityRequirements
    ): Boolean {
        val lowerName = toolName.lowercase()
        val lowerDesc = toolDescription.lowercase()

        val reqCaps = requirements.requiredCapabilities + requirements.optionalCapabilities

        if (reqCaps.contains(CapabilityType.FILE_STORAGE) && (lowerName.contains("file") || lowerName.contains("storage") || lowerDesc.contains("file"))) {
            return true
        }
        if (reqCaps.contains(CapabilityType.TOOL_EXECUTION)) {
            return true
        }
        if (reqCaps.contains(CapabilityType.SHELL_EXECUTION) && (lowerName.contains("shell") || lowerName.contains("exec") || lowerName.contains("security"))) {
            return true
        }

        return false
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
