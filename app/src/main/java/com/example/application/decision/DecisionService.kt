package com.example.application.decision

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.capability.CapabilityEvidenceRegistry
import com.example.domain.core.capability.CapabilityPrerequisites
import com.example.domain.core.capability.CapabilityResourceGraph
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.ResourceCapabilityGraph
import com.example.domain.core.decision.CandidateEvaluation
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.ServiceType
import com.example.domain.ports.provider.UserPreferenceRepository
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.task.TaskCapabilityRequirements
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskSpecification
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * DecisionService — Phase 4 (no silent defaults)
 * ============================================================================
 *
 * Per the architectural plan (Section 19 + Section 17):
 *
 * REMOVE all logic equivalent to:
 *   - `default_llm_step`
 *   - `if no candidates, use default LLM`
 *   - `select first provider`
 *   - `select first model`
 *
 * If no suitable resource exists:
 *   return an explicit UNAVAILABLE / REQUIRES_REPLAN decision result.
 *
 * Fallback creates a NEW decision according to the approved fallback
 * semantics. It NEVER silently mutates the current decision.
 *
 * UserResourcePreference (per Section 17): planning hint only. The
 * preference is consulted to boost the score of the preferred resource
 * (if it appears among the candidates). It MUST NOT:
 *   - execute a resource
 *   - bypass DecisionService
 *   - bypass ResourceRegistry
 *   - bypass governance
 *   - bypass health checks
 *   - substitute for DecisionRecord
 */
class DecisionService(
    private val cbrMdpEngine: CbrMdpEngine,
    val resourceCapabilityGraph: ResourceCapabilityGraph,
    private val securityGuard: SecurityGuardService,
    private val userPreferenceRepository: UserPreferenceRepository? = null,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy()
) {

    /**
     * Backwards-compat constructor: takes a `ComponentRegistry` and uses its
     * `resourceCapabilityGraph`. This is used by tests that pre-date the Phase 4
     * refactor. Production code uses the primary constructor with
     * `UserPreferenceRepository`.
     */
    constructor(
        cbrMdpEngine: CbrMdpEngine,
        componentRegistry: ComponentRegistry,
        securityGuard: SecurityGuardService,
        defaultSecurityPolicy: SecurityPolicy = SecurityPolicy()
    ) : this(
        cbrMdpEngine = cbrMdpEngine,
        resourceCapabilityGraph = componentRegistry.resourceCapabilityGraph,
        securityGuard = securityGuard,
        userPreferenceRepository = null,
        defaultSecurityPolicy = defaultSecurityPolicy
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
            if (hasEvidence) currentlySatisfied.add(cap)
        }

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
                }
                else -> Unit
            }
        }

        val capabilities = emptyList<com.example.domain.core.capability.CapabilityDescriptor>()  // not used; resource graph derives from ResourceRegistry
        val graph = CapabilityResourceGraph(capabilities)

        val gapAnalysis = graph.analyzeGap(
            taskId = taskWithRequirements.id.value,
            requirements = effectiveRequirements,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            currentlySatisfied = currentlySatisfied
        )

        return DecisionContext(
            task = taskWithRequirements,
            workspace = workspace,
            resourceGraph = workspace?.resourceGraph ?: com.example.domain.core.workspace.ResourceGraph(),
            capabilities = capabilities,
            capabilityGraph = graph,
            capabilityGap = gapAnalysis,
            satisfiedCapabilities = gapAnalysis.satisfiedCapabilities,
            missingCapabilities = gapAnalysis.missingCapabilities,
            availableTools = emptyList(),
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
     * Dynamically generates the candidate action space based on authoritative
     * ResourceCapabilityGraph, network policies, and task requirements.
     *
     * Per Phase 4 (Section 19):
     *   - No `default_llm_step` candidate. If no LLM candidates exist, the
     *     planner emits an explicit ASK_USER "no_llm_resource_available"
     *     action that the orchestrator must surface to the user.
     *   - UserResourcePreference boosts the score of the preferred resource
     *     IF it appears among the candidates (via `confidence` field). It
     *     does NOT inject the preference as a candidate.
     */
    suspend fun generateCandidateActions(context: DecisionContext): List<DecisionAction> {
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
                    rationale = "Selected search resource '${searchCand.resourceId.value}' via ResourceCapabilityGraph" +
                        preferenceSuffix(searchCand.resourceId, ServiceType.SEARCH),
                    confidence = 0.9f + preferenceBoost(searchCand.resourceId, ServiceType.SEARCH)
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

        // 3. Memory & Knowledge Retrieval — handled by RagPipelineService via the
        // resource pipeline. The DecisionService does NOT generate a RETRIVE_MEMORY
        // candidate with a fabricated embedding resource. If the planner needs RAG,
        // it emits a RETRIVE_KNOWLEDGE action with the configured embedding resource.
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
                    rationale = "Selected embedding resource '${embCand.resourceId.value}' via ResourceCapabilityGraph" +
                        preferenceSuffix(embCand.resourceId, ServiceType.EMBEDDING),
                    confidence = 0.85f + preferenceBoost(embCand.resourceId, ServiceType.EMBEDDING)
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
        // Per Phase 4: NO `default_llm_step` candidate. If no LLM candidates
        // exist, the planner emits an explicit ASK_USER "no_llm_resource_available".
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
                    rationale = "Selected LLM resource '${llmCand.resourceId.value}' via ResourceCapabilityGraph" +
                        preferenceSuffix(llmCand.resourceId, ServiceType.LLM),
                    confidence = 0.95f + preferenceBoost(llmCand.resourceId, ServiceType.LLM)
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
            // Per Phase 4 (Section 19): NO default_llm_step candidate. If no LLM
            // candidates exist, emit an explicit ASK_USER.
            if (llmCandidates.isEmpty()) {
                candidates.add(
                    DecisionAction(
                        type = DecisionActionType.ASK_USER,
                        targetId = "no_llm_resource_available",
                        payload = mapOf(
                            "reason" to "لا يوجد مورد LLM متاح في ResourceCapabilityGraph. " +
                                "يجب على المستخدم إنشاء مزود LLM، إضافة خدمة، حفظ التكوين، " +
                                "اختبار الاتصال، اكتشاف النماذج، اختيار نموذج، تفعيل المورد، " +
                                "ثم إعادة التخطيط."
                        )
                    )
                )
            }
        }

        // 5. Workflow / Plan Creation candidate for high-complexity multi-step goals
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
        if (currentStep >= 1 && (allRequiredSatisfied || context.hasSearchEvidence || context.hasMemoryEvidence || context.hasToolExecutionEvidence || context.accumulatedEvidence.containsKey("synthesis_complete"))) {
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
     * Returns a small confidence boost (0.0 - 0.05) if the given resourceId
     * matches the user's preferred resource for the given service type.
     * This is a PLANNING HINT only — it does not inject candidates or
     * bypass DecisionService. If the preferred resource is not in the
     * candidate pool, this method returns 0.0 and the preference has no
     * effect (the candidate selection proceeds normally).
     *
     * The lookup runs on Dispatchers.IO with a defensive try/catch: a failing
     * preference read must NEVER break the decision path (it degrades to a
     * no-op hint, which is architecturally identical to "no preference").
     */
    private suspend fun preferenceBoost(resourceId: ResourceId, serviceType: ServiceType): Float {
        val pref = lookupPreference(serviceType) ?: return 0.0f
        return if (pref.preferredResourceId == resourceId) PREFERENCE_BOOST else 0.0f
    }

    /**
     * Returns a suffix string for the rationale if the resource matches the
     * user's preference.
     */
    private suspend fun preferenceSuffix(resourceId: ResourceId, serviceType: ServiceType): String {
        val pref = lookupPreference(serviceType) ?: return ""
        return if (pref.preferredResourceId == resourceId) " ⭐ مفضّل المستخدم" else ""
    }

    /** Safe, non-blocking preference lookup (IO dispatcher + try/catch). */
    private suspend fun lookupPreference(serviceType: ServiceType): com.example.domain.core.provider.preference.UserResourcePreference? {
        val repo = userPreferenceRepository ?: return null
        return try {
            withContext(Dispatchers.IO) { repo.getPreference(serviceType) }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Maximum planning-hint boost for a user-preferred resource. */
        const val PREFERENCE_BOOST = 0.05f
    }

    /**
     * Executes the CBR-MDP decision evaluation over the given DecisionContext,
     * scoring all candidate actions against historical cases and expected MDP utility,
     * and enforcing deterministic security/governance constraints.
     */
    suspend fun evaluate(context: DecisionContext): DecisionResult {
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
     *
     * Per Phase 4 (Section 19): Fallback creates a NEW decision. It NEVER
     * silently mutates the current decision. Specifically:
     *   - OFFLINE + SEARCH → emit an explicit REPLAN with reason, not a silent
     *     RETRIEVE_KNOWLEDGE substitution that loses the original DecisionRecord.
     */
    private fun enforceGovernance(action: DecisionAction, context: DecisionContext): DecisionAction {
        // Offline policy: SEARCH is not allowed when offline. Emit an explicit
        // REPLAN instead of silently rewriting to RETRIEVE_KNOWLEDGE.
        if (context.networkPolicy == NetworkPolicy.OFFLINE && action.type == DecisionActionType.SEARCH) {
            return DecisionAction(
                type = DecisionActionType.REPLAN,
                targetId = "offline_policy_block",
                payload = mapOf(
                    "reason" to "OFFLINE_POLICY_BLOCK",
                    "originalAction" to action.type.code,
                    "originalResourceId" to (action.decisionRecord?.selectedResourceId?.value ?: action.targetId ?: "")
                )
            )
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
     * Dynamically selects the most suitable agent using CapabilityResourceGraph.
     */
    fun selectSuitableAgent(
        task: TaskDefinition,
        availableAgents: List<AgentDefinition>,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): AgentDefinition? {
        if (availableAgents.isEmpty()) return null
        val requirements = resolveTaskRequirements(task)
        val graph = CapabilityResourceGraph(emptyList())
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
     * as the authoritative source of truth, expanding transitive prerequisites.
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

        // Tier 4: Heuristic fallback with explicit provenance tracking
        val reqCaps = mutableSetOf<CapabilityType>()
        val optCaps = mutableSetOf<CapabilityType>()
        val prompt = task.input.rawPrompt.lowercase()
        val isCodeTask = prompt.contains("code") || prompt.contains("برمج") || prompt.contains("kotlin") || prompt.contains("function")
        val isSearchTask = prompt.contains("search") || prompt.contains("بحث") || prompt.contains("internet") || prompt.contains("ويب")
        val isFileTask = prompt.contains("file") || prompt.contains("ملف") || prompt.contains("مجلد") || prompt.contains("storage")
        val isMemoryTask = prompt.contains("memory") || prompt.contains("ذاكرة") || prompt.contains("rag")
        val isSecurityTask = prompt.contains("security") || prompt.contains("أمان") || prompt.contains("audit")
        val isHashTask = prompt.contains("hash") || prompt.contains("تجزئة") || prompt.contains("sha") || prompt.contains("md5")
        if (isCodeTask) { reqCaps.add(CapabilityType.TOOL_EXECUTION); reqCaps.add(CapabilityType.CODE_ENGINEERING); optCaps.add(CapabilityType.SHELL_EXECUTION) }
        if (isSearchTask) { reqCaps.add(CapabilityType.SEARCH) }
        if (isFileTask) { reqCaps.add(CapabilityType.TOOL_EXECUTION); reqCaps.add(CapabilityType.FILE_STORAGE) }
        if (isMemoryTask) { reqCaps.add(CapabilityType.MEMORY_RETRIEVAL); reqCaps.add(CapabilityType.EMBEDDING) }
        if (isSecurityTask) { reqCaps.add(CapabilityType.SECURITY_AUDIT); optCaps.add(CapabilityType.SHELL_EXECUTION) }
        if (isHashTask) { reqCaps.add(CapabilityType.TOOL_EXECUTION); reqCaps.add(CapabilityType.HASH_COMPUTATION) }
        val isPurelyDeterministic = isHashTask && !isCodeTask && !isSearchTask && !prompt.contains("explain") && !prompt.contains("اشرح")
        if (!isPurelyDeterministic) reqCaps.add(CapabilityType.LLM_GENERATION)
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
    ): DecisionState = cbrMdpEngine.processObservationAndUpdateBelief(state, observation)

    fun getCbrMdpEngine(): CbrMdpEngine = cbrMdpEngine
}
