package com.example.application.decision

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
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
        complexity: Float = 0.5f
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
            taskComplexity = complexity
        )
    }

    /**
     * Dynamically generates the candidate action space based on active system capabilities,
     * tool registries, network policies, and task requirements.
     */
    fun generateCandidateActions(context: DecisionContext): List<DecisionAction> {
        val candidates = mutableListOf<DecisionAction>()
        val prompt = context.task.input.rawPrompt

        // 1. Agent Selection candidates
        candidates.add(
            DecisionAction(
                type = DecisionActionType.SELECT_AGENT,
                targetId = "code_craftsman",
                payload = mapOf("role" to "CODER")
            )
        )
        candidates.add(
            DecisionAction(
                type = DecisionActionType.SELECT_AGENT,
                targetId = "architect_orchestrator",
                payload = mapOf("role" to "PLANNER")
            )
        )
        candidates.add(
            DecisionAction(
                type = DecisionActionType.SELECT_AGENT,
                targetId = "security_guardian",
                payload = mapOf("role" to "SECURITY_GUARD")
            )
        )

        // 2. Model & Provider candidates
        val llmProviders = componentRegistry.listLlmProviders()
        for (provider in llmProviders) {
            val isOnline = provider.metadata.isOnline
            val isLocal = provider.metadata.isLocal
            if (context.networkPolicy == NetworkPolicy.OFFLINE && !isLocal) continue

            val modelId = provider.metadata.defaultModel ?: "default"
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SELECT_MODEL,
                    targetId = modelId,
                    payload = mapOf("providerId" to provider.providerId, "isLocal" to isLocal.toString()),
                    estimatedLatencyMs = if (isLocal) 200L else 700L
                )
            )
        }

        // 3. Web & Knowledge Search candidate
        if (componentRegistry.getSearchProvider() != null &&
            (context.networkPolicy != NetworkPolicy.OFFLINE || context.isNetworkAvailable)) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SEARCH,
                    targetId = "multi_source_search",
                    payload = mapOf("query" to prompt.take(80)),
                    estimatedLatencyMs = 600L
                )
            )
        }

        // 4. Memory & RAG Retrieval candidate
        if (componentRegistry.getMemoryRepository() != null) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                    targetId = "rag_vector_store",
                    payload = mapOf("query" to prompt.take(80)),
                    estimatedLatencyMs = 150L
                )
            )
        }

        // 5. Tool Selection candidates from unified registry (Built-in, MCP, Skills, Plugins)
        for (tool in componentRegistry.listTools()) {
            val toolName = tool.declaration.name
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.SELECT_TOOL,
                    targetId = toolName,
                    payload = mapOf("description" to tool.declaration.description),
                    estimatedLatencyMs = 300L
                )
            )
        }

        // 6. Workflow / Plan Creation candidate for multi-step goals
        if (context.taskComplexity > 0.6f || prompt.contains("خطة", ignoreCase = true) || prompt.contains("plan", ignoreCase = true)) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.CREATE_PLAN,
                    targetId = "dag_workflow_planner",
                    payload = mapOf("goal" to prompt),
                    estimatedLatencyMs = 800L
                )
            )
        }

        // 7. General Step Execution
        candidates.add(
            DecisionAction(
                type = DecisionActionType.EXECUTE_STEP,
                targetId = "standard_llm_stream",
                payload = mapOf("prompt" to prompt),
                estimatedLatencyMs = 1000L
            )
        )

        // 8. Replan or Retry if previous failures occurred
        if (context.consecutiveFailures in 1..2) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.RETRY,
                    targetId = "retry_with_backoff"
                )
            )
        } else if (context.consecutiveFailures > 2) {
            candidates.add(
                DecisionAction(
                    type = DecisionActionType.REPLAN,
                    targetId = "degraded_fallback_replan"
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
        if (action.type == DecisionActionType.SELECT_TOOL && action.targetId != null) {
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
