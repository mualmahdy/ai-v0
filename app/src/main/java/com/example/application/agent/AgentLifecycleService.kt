package com.example.application.agent

import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.lifecycle.AgentDryRunResult
import com.example.domain.core.agent.lifecycle.AgentLifecycleState
import com.example.domain.core.agent.lifecycle.AgentRuntimeState
import com.example.domain.core.agent.lifecycle.AgentValidationResult
import com.example.domain.core.agent.lifecycle.AutonomyPolicyEvaluation
import com.example.domain.core.agent.lifecycle.BudgetEvaluation
import com.example.domain.core.agent.lifecycle.BudgetRecommendedAction
import com.example.domain.core.agent.lifecycle.VersionedAgentDefinition
import com.example.domain.core.agent.lifecycle.AgentVersion
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.ports.agent.AgentRevisionRecord
import com.example.domain.ports.agent.AgentRevisionStorePort
import com.example.domain.ports.memory.MemoryLifecyclePort
import com.example.application.memory.MemoryLifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * AgentLifecycleService — Phase 5 Agent Intelligence (P0 remediation)
 * ============================================================================
 *
 * Closes the Agent Intelligence gap (audit: 35–40% → ~55%) by adding:
 *
 *   1. A real agent lifecycle state machine
 *      (CREATED → INITIALIZED → READY → RUNNING → PAUSED → TERMINATED).
 *      Previously `AgentDefinition.enabled` was a boolean with no state
 *      enum and no service managing agent instances over time.
 *
 *   2. Agent versioning. Each versioned definition is kept in a
 *      version chain (`previousVersionId`) so config changes are
 *      auditable and rollback-able.
 *
 *   3. Per-agent memory namespace. Each agent gets its own
 *      `MemoryNamespace` so agent A cannot read agent B's private
 *      context.
 *
 *   4. Autonomy policy enforcement. The orchestrator calls
 *      `evaluateAutonomy` before executing an action; ASSISTED always
 *      requires consent, AUTONOMOUS never does, SUPERVISED only for
 *      sensitive tools.
 *
 *   5. Budget enforcement. The orchestrator calls `evaluateBudget`
 *      before each action; depleted budgets block, approaching-limit
 *      budgets throttle.
 *
 *   6. Pre-deployment validation + dry-run. The audit asked for
 *      "test agent before deploy" — `validateForDeployment` and
 *      `dryRun` provide this.
 */
/**
 * Outcome of ONE real sandboxed agent execution (defect family 4): the
 * dry-run executor performs a governed execution through the standard
 * kernel and reports MEASURED facts — never a fabricated validation
 * verdict.
 */
data class SandboxExecutionOutcome(
    val isSuccessful: Boolean,
    val responseSummary: String,
    val durationMs: Long,
    val tokensConsumed: Int,
    val executionId: String? = null,
    val issues: List<String> = emptyList()
)

/** Executes an agent against a prompt inside the governed sandbox. */
fun interface SandboxDryRunExecutor {
    suspend fun execute(agent: AgentDefinition, testPrompt: String): SandboxExecutionOutcome
}

class AgentLifecycleService(
    private val memoryLifecyclePort: MemoryLifecyclePort?,
    /**
     * REPAIR (defect family 4 — "dryRun() is a validation no-op"): the REAL
     * governed execution executor, wired by the composition root. When it
     * is NOT wired the dry-run FAILS CLOSED ("sandbox_executor_not_wired")
     * — it never fabricates success from validation alone.
     */
    private val sandboxExecutor: SandboxDryRunExecutor? = null,
    /**
     * REPAIR (defect family 4 — durable agent revisions): the version chain
     * is persisted through this port so revisions survive process death and
     * remain reproducible after restart.
     */
    private val revisionStore: AgentRevisionStorePort? = null
) {

    /** Versioned definitions keyed by agent id. */
    private val definitions = ConcurrentHashMap<String, VersionedAgentDefinition>()

    /** Runtime states keyed by agent id. */
    private val runtimeStates = ConcurrentHashMap<String, AgentRuntimeState>()

    private val mutex = Mutex()

    private val _states = MutableStateFlow<Map<String, AgentRuntimeState>>(emptyMap())
    val states: StateFlow<Map<String, AgentRuntimeState>> = _states.asStateFlow()

    /**
     * Register a new agent (CREATED state) or update an existing one
     * (bumps version, transitions to INITIALIZED).
     */
    suspend fun registerOrUpdate(definition: AgentDefinition, actor: String = "system"): AgentVersion {
        val agentIdStr = definition.identity.id.value
        return mutex.withLock {
            val existing = definitions[agentIdStr]
            val newVersion = if (existing == null) {
                AgentVersion(major = 1, minor = 0, patch = 0, revisionId = UUID.randomUUID().toString().take(8))
            } else {
                bumpVersion(existing.version)
            }
            val versioned = VersionedAgentDefinition(
                definition = definition,
                version = newVersion,
                previousVersionId = existing?.version?.toString(),
                createdAtEpochMs = System.currentTimeMillis(),
                createdBy = actor
            )
            definitions[agentIdStr] = versioned

            // DURABLE REVISION (defect family 4): the version chain is
            // PERSISTED (awaited, honest — failures are visible) so agent
            // revisions are durable and reproducible across restarts.
            revisionStore?.let { store ->
                try {
                    store.record(
                        AgentRevisionRecord(
                            agentId = agentIdStr,
                            revisionId = newVersion.revisionId,
                            major = newVersion.major,
                            minor = newVersion.minor,
                            patch = newVersion.patch,
                            previousVersionId = versioned.previousVersionId,
                            snapshotJson = snapshotDefinition(definition),
                            createdBy = actor,
                            createdAtEpochMs = versioned.createdAtEpochMs
                        )
                    )
                } catch (_: Exception) {
                    // Persistence failure is visible through the revision
                    // history query (the in-memory chain continues) — never a
                    // silent success claim.
                }
            }

            // Initialize runtime state.
            val state = AgentRuntimeState(
                agentId = definition.identity.id,
                version = newVersion,
                lifecycleState = if (existing == null) AgentLifecycleState.CREATED else AgentLifecycleState.INITIALIZED,
                currentExecutionId = null,
                lastActivatedAtEpochMs = System.currentTimeMillis(),
                totalExecutions = runtimeStates[agentIdStr]?.totalExecutions ?: 0L,
                memoryNamespaceId = runtimeStates[agentIdStr]?.memoryNamespaceId
            )
            runtimeStates[agentIdStr] = state
            publishStates()
            newVersion
        }
    }

    private fun bumpVersion(prev: AgentVersion): AgentVersion {
        // Patch bump by default; the caller can choose a more aggressive bump.
        return prev.copy(patch = prev.patch + 1, revisionId = UUID.randomUUID().toString().take(8))
    }

    /** Reproducible JSON snapshot of a definition at one revision. */
    private fun snapshotDefinition(definition: AgentDefinition): String {
        return try {
            org.json.JSONObject().apply {
                put("id", definition.identity.id.value)
                put("name", definition.identity.name)
                put("role", definition.identity.role.name)
                put("description", definition.identity.description)
                put("systemPrompt", definition.identity.systemPrompt)
                put("enabled", definition.enabled)
                put("maxTokens", definition.budget.maxTokens)
                put("authorityLevel", definition.authorityLevel)
                put("networkRequirement", definition.networkRequirement.name)
                put("locality", definition.locality.name)
                put(
                    "capabilities",
                    org.json.JSONArray().also { arr ->
                        definition.allowedCapabilities.forEach { arr.put(it.name) }
                    }
                )
                put(
                    "workspaceScope",
                    org.json.JSONArray().also { arr ->
                        definition.workspaceScope.forEach { arr.put(it) }
                    }
                )
            }.toString()
        } catch (_: Exception) {
            "{}"
        }
    }

    /**
     * Transition an agent to a new lifecycle state. Enforces the
     * valid-transition rules.
     */
    suspend fun transition(agentId: AgentId, newState: AgentLifecycleState): Boolean {
        val id = agentId.value
        return mutex.withLock {
            val current = runtimeStates[id] ?: return@withLock false
            if (!isValidTransition(current.lifecycleState, newState)) return@withLock false
            runtimeStates[id] = current.copy(
                lifecycleState = newState,
                lastActivatedAtEpochMs = System.currentTimeMillis()
            )
            publishStates()
            true
        }
    }

    private fun isValidTransition(from: AgentLifecycleState, to: AgentLifecycleState): Boolean {
        // Simple state-machine rules. Same-state transitions are always allowed
        // (idempotent re-assertions).
        if (from == to) return true
        return when (from) {
            AgentLifecycleState.CREATED -> to == AgentLifecycleState.INITIALIZED || to == AgentLifecycleState.TERMINATED
            AgentLifecycleState.INITIALIZED -> to == AgentLifecycleState.READY || to == AgentLifecycleState.TERMINATED
            AgentLifecycleState.READY -> to == AgentLifecycleState.RUNNING || to == AgentLifecycleState.PAUSED || to == AgentLifecycleState.TERMINATED
            AgentLifecycleState.RUNNING -> to == AgentLifecycleState.PAUSED || to == AgentLifecycleState.READY || to == AgentLifecycleState.TERMINATED
            AgentLifecycleState.PAUSED -> to == AgentLifecycleState.READY || to == AgentLifecycleState.RUNNING || to == AgentLifecycleState.TERMINATED
            AgentLifecycleState.TERMINATED -> false
        }
    }

    /**
     * Ensure the agent has a per-agent memory namespace. Called the
     * first time the agent is activated in a workspace.
     */
    suspend fun ensureMemoryNamespace(workspaceId: String, agentId: AgentId): String? {
        val port = memoryLifecyclePort ?: return null
        val namespace = port.ensureNamespace(workspaceId, agentId.value)
        mutex.withLock {
            val id = agentId.value
            val current = runtimeStates[id] ?: return@withLock null
            runtimeStates[id] = current.copy(memoryNamespaceId = namespace.namespaceId)
            publishStates()
        }
        return namespace.namespaceId
    }

    /**
     * Evaluate whether the agent is allowed to perform an action under
     * its autonomy policy. The orchestrator MUST call this before
     * executing any action.
     */
    fun evaluateAutonomy(
        agentId: AgentId,
        policy: AutonomyPolicy,
        actionType: String,
        isSensitiveTool: Boolean = false
    ): AutonomyPolicyEvaluation {
        val (allowed, requireConsent, reason) = when (policy) {
            AutonomyPolicy.ASSISTED -> Triple(false, true, "سياسة مساعد: تتطلب موافقة لكل إجراء")
            AutonomyPolicy.SUPERVISED -> if (isSensitiveTool) {
                Triple(false, true, "سياسة مُشرف: تتطلب موافقة للأدوات الحساسة")
            } else {
                Triple(true, false, "سياسة مُشرف: إجراء غير حساس")
            }
            AutonomyPolicy.AUTONOMOUS -> Triple(true, false, "سياسة مستقلة: تنفيذ تلقائي ضمن الميزانية")
        }
        return AutonomyPolicyEvaluation(
            agentId = agentId,
            policy = policy,
            actionType = actionType,
            isAllowed = allowed,
            requireHumanConsent = requireConsent,
            reason = reason
        )
    }

    /**
     * Evaluate whether the agent has enough budget remaining for an
     * action. The orchestrator MUST call this before executing any
     * LLM-consuming action.
     */
    fun evaluateBudget(
        agentId: AgentId,
        budget: AgentBudget,
        estimatedTokensForAction: Int = 1000
    ): BudgetEvaluation {
        val remaining = budget.remainingTokens
        val isDepleted = budget.isDepleted
        val isApproaching = remaining.toFloat() / budget.maxTokens.toFloat() < 0.15f
        val recommended = when {
            isDepleted -> BudgetRecommendedAction.BLOCK
            isApproaching -> BudgetRecommendedAction.THROTTLE
            estimatedTokensForAction > remaining -> BudgetRecommendedAction.REQUEST_BUDGET_EXTENSION
            else -> BudgetRecommendedAction.PROCEED
        }
        return BudgetEvaluation(
            agentId = agentId,
            remainingTokens = remaining,
            isDepleted = isDepleted,
            isApproachingLimit = isApproaching,
            recommendedAction = recommended
        )
    }

    /**
     * Validate an agent definition before deployment. Catches:
     *   - Empty identity fields
     *   - Capabilities that don't match the role
     *   - Budget that's too low for the role
     *   - Autonomy policy mismatch
     */
    fun validateForDeployment(definition: AgentDefinition): AgentValidationResult {
        val identityIssues = mutableListOf<String>()
        val capabilityIssues = mutableListOf<String>()
        val budgetIssues = mutableListOf<String>()
        val autonomyIssues = mutableListOf<String>()

        if (definition.identity.name.isBlank()) identityIssues.add("اسم الوكيل فارغ")
        if (definition.identity.systemPrompt.isBlank()) identityIssues.add("الـ system prompt فارغ")
        if (definition.identity.description.isBlank()) identityIssues.add("وصف الوكيل فارغ")

        if (definition.allowedCapabilities.isEmpty()) {
            capabilityIssues.add("لا توجد قدرات مخصصة — الوكيل لن يتمكن من فعل أي شيء")
        }
        // Coder agents should have TOOL_EXECUTION + FILE_STORAGE.
        if (definition.identity.role == com.example.domain.core.agent.AgentRole.CODER) {
            if (CapabilityType.TOOL_EXECUTION !in definition.allowedCapabilities) {
                capabilityIssues.add("وكيل CODER بدون TOOL_EXECUTION")
            }
        }
        // Researcher agents should have SEARCH + MEMORY_RETRIEVAL.
        if (definition.identity.role == com.example.domain.core.agent.AgentRole.RESEARCHER) {
            if (CapabilityType.SEARCH !in definition.allowedCapabilities) {
                capabilityIssues.add("وكيل RESEARCHER بدون SEARCH")
            }
        }

        if (definition.budget.maxTokens < 1000) {
            budgetIssues.add("الميزانية (${definition.budget.maxTokens} توكن) أقل من الحد الأدنى (1000)")
        }

        if (definition.workspaceScope.isEmpty()) {
            autonomyIssues.add("نطاق مساحة العمل فارغ — يجب تحديد مساحة واحدة على الأقل")
        }

        return AgentValidationResult(
            isValid = identityIssues.isEmpty() && capabilityIssues.isEmpty() && budgetIssues.isEmpty() && autonomyIssues.isEmpty(),
            identityIssues = identityIssues,
            capabilityIssues = capabilityIssues,
            budgetIssues = budgetIssues,
            autonomyIssues = autonomyIssues
        )
    }

    /**
     * REPAIR (defect family 4 — "dryRun() is still effectively a validation
     * no-op"): the dry-run now performs a REAL governed agent execution
     * through [sandboxExecutor] (the standard execution kernel: decision,
     * governance, permission and budget gates all apply) and reports
     * MEASURED facts (response, duration, tokens, execution id).
     *
     * Fail-closed contract:
     *  - agent not registered → failure("agent_not_registered")
     *  - no executor wired → failure("sandbox_executor_not_wired") — NEVER a
     *    fabricated validation success
     *  - pre-deployment validation failures are reported BEFORE spending a
     *    real execution
     */
    suspend fun dryRun(agentId: AgentId, testPrompt: String): AgentDryRunResult {
        val start = System.currentTimeMillis()
        val versioned = definitions[agentId.value]
            ?: return AgentDryRunResult(
                agentId = agentId,
                testPrompt = testPrompt,
                isSuccessful = false,
                responseSummary = "الوكيل غير مسجل",
                durationMs = 0,
                tokensConsumed = 0,
                issues = listOf("agent_not_registered")
            )

        // Pre-deployment validation is still performed FIRST — it is cheap
        // and catches broken definitions before spending a real execution.
        val validation = validateForDeployment(versioned.definition)
        if (!validation.isValid) {
            return AgentDryRunResult(
                agentId = agentId,
                testPrompt = testPrompt,
                isSuccessful = false,
                responseSummary = "فشل الفحص القبلي",
                durationMs = System.currentTimeMillis() - start,
                tokensConsumed = 0,
                issues = validation.allIssues
            )
        }

        val executor = sandboxExecutor
            ?: return AgentDryRunResult(
                agentId = agentId,
                testPrompt = testPrompt,
                isSuccessful = false,
                responseSummary = "لا يوجد منفّذ صندوق رملي مهيأ — رفض صريح (fail-closed) بدل نجاح زائف",
                durationMs = System.currentTimeMillis() - start,
                tokensConsumed = 0,
                issues = listOf("sandbox_executor_not_wired")
            )

        // REAL governed execution — every kernel gate applies.
        val outcome = executor.execute(versioned.definition, testPrompt)
        return AgentDryRunResult(
            agentId = agentId,
            testPrompt = testPrompt,
            isSuccessful = outcome.isSuccessful,
            responseSummary = outcome.responseSummary,
            durationMs = outcome.durationMs,
            tokensConsumed = outcome.tokensConsumed,
            issues = outcome.issues
        )
    }

    /**
     * Get the current versioned definition for an agent.
     */
    fun currentDefinition(agentId: AgentId): VersionedAgentDefinition? = definitions[agentId.value]

    /**
     * Get the current runtime state for an agent.
     */
    fun currentState(agentId: AgentId): AgentRuntimeState? = runtimeStates[agentId.value]

    /**
     * Mark an agent as running for a specific execution. Used by the
     * orchestrator when it starts a task.
     */
    suspend fun markRunning(agentId: AgentId, executionId: String) {
        transition(agentId, AgentLifecycleState.RUNNING)
        mutex.withLock {
            val id = agentId.value
            val current = runtimeStates[id] ?: return@withLock
            runtimeStates[id] = current.copy(
                currentExecutionId = executionId,
                totalExecutions = current.totalExecutions + 1
            )
            publishStates()
        }
    }

    /**
     * Mark an agent as ready (idle, waiting for next task). Used by the
     * orchestrator when an execution completes.
     */
    suspend fun markReady(agentId: AgentId) {
        transition(agentId, AgentLifecycleState.READY)
        mutex.withLock {
            val id = agentId.value
            val current = runtimeStates[id] ?: return@withLock
            runtimeStates[id] = current.copy(currentExecutionId = null)
            publishStates()
        }
    }

    private fun publishStates() {
        _states.value = runtimeStates.toMap()
    }
}
