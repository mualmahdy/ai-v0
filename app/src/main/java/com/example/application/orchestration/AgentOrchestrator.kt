package com.example.application.orchestration

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionContextCodec
import com.example.application.execution.ExecutionResult
import com.example.application.execution.ExecutionService
import com.example.application.execution.ActionIdempotencyService
import com.example.application.budget.EconomicGovernanceService
import com.example.application.observation.ObservationService
import com.example.application.outcome.OutcomeService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.budget.UsageAccountingInput
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.execution.CanonicalExecutionContext
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.execution.IntentGate
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.agent.AgentId
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskSuccessCriteria
import com.example.domain.core.task.VerificationStrategy
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.dao.ActionIntentDao
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core Orchestrator coordinating Closed-Loop Autonomous Execution:
 * DECIDE (CBR-MDP) -> EXECUTE -> OBSERVE -> BELIEF UPDATE -> RE-DECIDE -> COMPLETE.
 *
 * ============================================================================
 * CANONICAL EXECUTION KERNEL (gap-closure P0-02 / P0-03 / P0-05 / P0-06 /
 * P1-01 / P1-02 / P1-03 / P1-04 / P1-13)
 * ============================================================================
 *
 * Every execution now binds a [CanonicalExecutionContext] ONCE at launch:
 *  - a STABLE executionId that survives resume (attempt counter only);
 *  - a PINNED workspaceId — captured before the loop starts, propagated to
 *    memory/RAG/resource scoping via [ExecutionScope] and to telemetry via
 *    `ExecutionEvent.Started.workspaceId`;
 *  - a PINNED agent identity — a resume that cannot find the ORIGINAL agent
 *    fails honestly (AGENT_UNAVAILABLE) instead of silently migrating to a
 *    different agent with different prompts/permissions (P1-04);
 *  - an ACTION IDEMPOTENCY LEDGER (`action_intents`) — side-effectful actions
 *    are recorded intent -> outcome; a resume REPLAYS completed actions
 *    instead of re-executing them (P0-05 / P0-06, exactly-once recovery);
 *  - checkpoint persistence is AUTHORITATIVE: a failed checkpoint write now
 *    STOPS the execution with an honest fatal error instead of continuing
 *    with an unresumable, unsafe state (P0-05).
 */
class AgentOrchestrator(
    private val registry: ComponentRegistry,
    private val securityGuard: SecurityGuardService,
    private val decisionService: DecisionService,
    private val executionService: ExecutionService = ExecutionService(
        runtimeAdapterResolver = registry.runtimeAdapterResolver,
        resourceRegistry = registry.resourceRegistry,
        securityGuard = securityGuard,
        memoryRepositoryProvider = { registry.getMemoryRepository() }
    ).apply {
        // P0-1 (audit 2026 §15/§33): even the CONVENIENCE default execution
        // service runs every tool execution through the ordered admission
        // pipeline (registry-backed gate). Production wiring (AppContainer)
        // injects the full Room-backed gate; there is no ungoverned default
        // path left in the runtime.
        admissionControl = com.example.application.governed.AdmissionControlService.forRegistry(registry)
    },
    private val observationService: ObservationService = ObservationService(),
    private val outcomeService: OutcomeService = OutcomeService(),
    private val taskDao: TaskDao? = null,
    /** Action idempotency ledger (null in pure JVM tests = no exactly-once guarantee). */
    private val actionIntentDao: ActionIntentDao? = null,
    private val defaultSecurityPolicy: SecurityPolicy = SecurityPolicy(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /**
     * GOVERNANCE PHASE — the economic governance facade. When present the
     * orchestrator (a) enforces the TASK-scope token QUOTA (execution limit,
     * distinct from monetary budget), (b) accounts every executed action's
     * usage into the persistent cost ledger, and (c) surfaces budget-gate
     * verdicts on the event bus. Null = legacy behavior (tests).
     */
    private val economicGovernanceService: EconomicGovernanceService? = null,
    /**
     * Active workspace provider used ONLY at execution start to PIN the
     * canonical context. Never consulted mid-execution (P0-02). Late-bound
     * by the AppContainer (avoids a constructor dependency cycle).
     */
    var workspaceIdProvider: (() -> String?)? = null,
    /**
     * P0 CONVERGENCE (audit step 12 §6): resolves the ACTIVE workspace's OWN
     * sandbox project id ONCE at execution launch and pins it into the
     * canonical execution context — mid-run workspace switches can no longer
     * re-target agent file operations. Never an implicit 1L (null = not
     * bound; the fail-closed contract lives in FileSystemTool/skills/MCP).
     */
    var projectIdProvider: (() -> Long?)? = null,
    /**
     * REPAIR (defect family 4 — "agent autonomy/budget governance must
     * derive from authoritative effective policy rather than caller-supplied
     * authority"): autonomy governance hook, wired by the composition root
     * to the agent lifecycle service with the EFFECTIVE policy (the more
     * restrictive of the workspace's authoritative policy and the task's
     * persisted policy — never raw caller authority). When wired, sensitive
     * actions that the effective policy does not allow FAIL CLOSED.
     */
    var autonomyGovernor: (suspend (
        agent: AgentDefinition,
        action: DecisionAction,
        isSensitiveTool: Boolean,
        taskPolicy: AutonomyPolicy
    ) -> com.example.domain.core.agent.lifecycle.AutonomyPolicyEvaluation)? = null,
    /**
     * REPAIR (defect family 4): budget governance hook — the agent-budget
     * evaluation derives from the AGENT's durable budget intersected with
     * the task quota (authoritative effective budget), not caller-supplied
     * numbers. BLOCK stops further paid actions.
     */
    var budgetGovernor: (suspend (
        agent: AgentDefinition,
        task: TaskDefinition,
        accumulatedTokens: Int
    ) -> com.example.domain.core.agent.lifecycle.BudgetEvaluation)? = null
) {

    private val idempotency: ActionIdempotencyService = ActionIdempotencyService(actionIntentDao)

    /**
     * P1-8 (audit 2026 §18 — startup race): readiness gate wired by the
     * composition root to `AppContainer.awaitRuntimeReadiness`. Executions
     * started before bootstrap completed (resource restore, Q-table load,
     * canonical agent sync) WAIT here instead of racing a half-initialized
     * runtime. Null = no gate (tests / legacy wiring).
     */
    var readinessGate: (suspend () -> Unit)? = null

    /**
     * Observability bus (audit 2026 fix): every emitted execution event is
     * also published here so TelemetryService can persist traces/metrics
     * WITHOUT the orchestrator depending on the telemetry layer.
     *
     * P1-10 FIX (audit 2026 §20 — telemetry events can be LOST): the bus is
     * no longer DROP_OLDEST/tryEmit (which silently discarded the OLDEST
     * events — traces and audit rows — under a burst > 256). It is now a
     * large BACKPRESSURED channel: the emitter SUSPENDS when the buffer is
     * full instead of losing evidence. Slow persistence throttles the
     * runtime rather than corrupting the audit trail.
     */
    private val _executionEventPublisher = MutableSharedFlow<ExecutionEvent>(
        extraBufferCapacity = 2048,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
    )
    val executionEventPublisher: SharedFlow<ExecutionEvent> = _executionEventPublisher.asSharedFlow()

    /** Guards concurrent auto-resume sweeps on startup. */
    private val resumeSweepRunning = AtomicBoolean(false)

    /** Detailed execution outcome for workflow/DAG accounting (P1-07). */
    data class TaskExecutionSummary(
        val outcome: Outcome<String, String>,
        val totalTokensConsumed: Int,
        val executionId: String,
        val workspaceId: String?,
        val attempt: Int,
        /** Number of side-effectful actions replayed from the ledger (idempotency). */
        val replayedActions: Int
    )

    /**
     * Executes a task synchronously returning a comprehensive Outcome for workflow engines.
     */
    suspend fun executeTask(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true
    ): Outcome<String, String> {
        var finalResult = ""
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        var isError = false
        var errorMessage = ""

        executeTaskStream(
            agent = agent,
            task = task,
            conversationHistory = conversationHistory,
            preferredProviderId = preferredProviderId,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable
        ).collect { event ->
            when (event) {
                is ExecutionEvent.Completed -> {
                    finalResult = event.finalText
                    isDegraded = event.isDegraded
                    degradedReason = event.degradedReason
                }
                is ExecutionEvent.Error -> {
                    if (event.isFatal) {
                        isError = true
                        errorMessage = event.message
                    }
                }
                is ExecutionEvent.Degraded -> {
                    isDegraded = true
                    degradedReason = event.reason
                }
                else -> Unit
            }
        }

        return when {
            isError -> Outcome.Error(failure = errorMessage, diagnosticMessage = errorMessage)
            isDegraded -> Outcome.Degraded(
                partialValue = finalResult,
                reason = degradedReason ?: DegradedReason.UNKNOWN_DEGRADATION,
                diagnosticMessage = "تم التنفيذ بوضع متراجع"
            )
            else -> Outcome.Success(value = finalResult)
        }
    }

    /**
     * Executes a task and returns the detailed summary (REAL token usage,
     * execution identity, replay statistics) — used by the WorkflowEngine so
     * workflow accounting matches the economic ledger instead of the legacy
     * `output.length / 4` estimate (P1-07).
     */
    suspend fun executeTaskDetailed(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        pinnedWorkspaceId: String? = null
    ): TaskExecutionSummary {
        // P1-8 (audit 2026 §18 — startup race): wait for runtime readiness
        // (resource restore / Q-table load / canonical agent sync) BEFORE
        // any execution can start. The gate itself enforces its own timeout
        // and audits a warning when bootstrap is late; it never deadlocks
        // the execution layer.
        readinessGate?.invoke()
        var finalResult = ""
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        var isError = false
        var errorMessage = ""
        var tokens = 0
        var executionId = ""
        var attempt = 1
        var replayed = 0

        executeTaskStream(
            agent = agent,
            task = task,
            conversationHistory = conversationHistory,
            preferredProviderId = preferredProviderId,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            pinnedWorkspaceId = pinnedWorkspaceId
        ).collect { event ->
            when (event) {
                is ExecutionEvent.Started -> executionId = event.executionId
                is ExecutionEvent.UsageBudgetUpdate -> tokens = event.totalSessionTokens
                is ExecutionEvent.CostRecorded -> tokens = event.totalTokens.let { t ->
                    // CostRecorded carries per-action usage; keep the max observed.
                    maxOf(tokens, event.inputTokens + event.outputTokens)
                }
                is ExecutionEvent.ActionCompleted -> {
                    if (event.action.payload.containsKey("__replayed__")) replayed++
                }
                is ExecutionEvent.Completed -> {
                    finalResult = event.finalText
                    isDegraded = event.isDegraded
                    degradedReason = event.degradedReason
                }
                is ExecutionEvent.Error -> {
                    if (event.isFatal) {
                        isError = true
                        errorMessage = event.message
                    }
                }
                is ExecutionEvent.Degraded -> {
                    isDegraded = true
                    degradedReason = event.reason
                }
                else -> Unit
            }
        }

        // Final token count comes from the durable task row when persistence
        // is wired (authoritative accounting), else from the observed events.
        val durableTokens = runCatching {
            taskDao?.getTaskById(task.id.value)?.totalTokensConsumed
        }.getOrNull()
        val effectiveTokens = durableTokens?.takeIf { it > 0 } ?: tokens

        val outcome = when {
            isError -> Outcome.Error(failure = errorMessage, diagnosticMessage = errorMessage)
            isDegraded -> Outcome.Degraded(
                partialValue = finalResult,
                reason = degradedReason ?: DegradedReason.UNKNOWN_DEGRADATION,
                diagnosticMessage = "تم التنفيذ بوضع متراجع"
            )
            else -> Outcome.Success(value = finalResult)
        }
        return TaskExecutionSummary(
            outcome = outcome,
            totalTokensConsumed = effectiveTokens,
            executionId = executionId,
            workspaceId = pinnedWorkspaceId,
            attempt = attempt,
            replayedActions = replayed
        )
    }

    /**
     * Durable-execution checkpoint payload (audit 2026 fix): the resumable
     * state of the closed loop, serialized into `tasks.checkpointJson`.
     */
    data class TaskCheckpoint(
        val stepIndex: Int,
        val tokensConsumed: Int,
        val accumulatedOutput: String,
        val evidence: Map<String, Any?> = emptyMap()
    ) {
        fun toJson(): String {
            val obj = JSONObject()
            obj.put("stepIndex", stepIndex)
            obj.put("tokensConsumed", tokensConsumed)
            obj.put("accumulatedOutput", accumulatedOutput)
            val ev = JSONObject()
            evidence.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is Number, is Boolean -> ev.put(k, v)
                    is String -> ev.put(k, v)
                    is Collection<*> -> ev.put(k, JSONArray(v.map { it?.toString() ?: "" }))
                    else -> ev.put(k, v.toString())
                }
            }
            obj.put("evidence", ev)
            return obj.toString()
        }

        companion object {
            fun fromJson(json: String?): TaskCheckpoint? {
                if (json.isNullOrBlank()) return null
                return try {
                    val obj = JSONObject(json)
                    val ev = mutableMapOf<String, Any?>()
                    obj.optJSONObject("evidence")?.let { e ->
                        for (k in e.keys()) {
                            // Convert JSONArrays back to Kotlin Lists — downstream
                            // evidence injection checks `is List<*>`; a raw
                            // org.json.JSONArray would silently break restored
                            // search/memory evidence (caught by GoldenPathTest).
                            val v = e.get(k)
                            ev[k] = if (v is org.json.JSONArray) {
                                (0 until v.length()).map { i ->
                                    val item = v.get(i)
                                    if (item == JSONObject.NULL) null else item.toString()
                                }
                            } else v
                        }
                    }
                    TaskCheckpoint(
                        stepIndex = obj.optInt("stepIndex", 0),
                        tokensConsumed = obj.optInt("tokensConsumed", 0),
                        accumulatedOutput = obj.optString("accumulatedOutput", ""),
                        evidence = ev
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Executes an agent task via the autonomous closed loop governed by CBR-MDP Decision Intelligence.
     *
     * Implemented as a [channelFlow] (gap-closure): the loop body runs inside
     * a PINNED ExecutionScope (`withContext`) and emits from that scope — a
     * plain `flow {}` forbids cross-context emission (Flow invariant), so the
     * channel-based builder is the correct primitive for scoped emission.
     *
     * Audit 2026 fixes in this loop:
     *  - every event is mirrored to [executionEventPublisher] for persistent tracing;
     *  - the loop checkpoint (step, evidence, output, tokens) is persisted after
     *    every step so process death RESUMES instead of re-running;
     *  - a cancelled task is persisted as CANCELLED (previously the row stayed
     *    RUNNING forever, producing zombie resumable tasks);
     *  - delegation depth flows through the task parameters for the depth guard.
     *
     * Gap-closure:
     *  - [restoredContext] carries the ORIGINAL execution identity when
     *    resuming (stable executionId, pinned workspace/agent/model);
     *  - [pinnedWorkspaceId] lets the WorkflowEngine pin all step executions
     *    to the workspace the workflow started in.
     */
    fun executeTaskStream(
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage> = emptyList(),
        preferredProviderId: String? = null,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable: Boolean = true,
        includeWebSearch: Boolean = false,
        restoredCheckpoint: TaskCheckpoint? = null,
        restoredContext: CanonicalExecutionContext? = null,
        pinnedWorkspaceId: String? = null
    ): Flow<ExecutionEvent> = channelFlow<ExecutionEvent> {
        // ProducerScope (1.10+) is a SendChannel, NOT a FlowCollector — bridge
        // the loop's emit() semantics onto the channel's send() (the channel
        // allows emission from the pinned ExecutionScope coroutine).
        val collector = kotlinx.coroutines.flow.FlowCollector<ExecutionEvent> { value -> send(value) }
        try {
            executeTaskLoop(
                collector = collector,
                agent = agent,
                task = task,
                conversationHistory = conversationHistory,
                preferredProviderId = preferredProviderId,
                networkPolicy = networkPolicy,
                isNetworkAvailable = isNetworkAvailable,
                includeWebSearch = includeWebSearch,
                restoredCheckpoint = restoredCheckpoint,
                restoredContext = restoredContext,
                pinnedWorkspaceId = pinnedWorkspaceId
            )
        } catch (e: CancellationException) {
            // Truthful cancellation (audit 2026 fix): persist CANCELLED so the
            // task does not linger as a zombie RUNNING row that auto-resume
            // would re-launch on the next startup.
            val dao = taskDao
            if (dao != null) {
                runCatching {
                    dao.updateTaskStatus(
                        id = task.id.value,
                        state = "CANCELLED",
                        summary = "تم إلغاء المهمة بواسطة المستخدم أو النظام.",
                        tokens = restoredCheckpoint?.tokensConsumed ?: 0,
                        duration = 0L,
                        isDegraded = false,
                        degradedReason = null,
                        errorMsg = "CANCELLED",
                        now = System.currentTimeMillis()
                    )
                }
            }
            runCatching {
                send(
                    ExecutionEvent.Cancelled(
                        executionId = restoredContext?.executionId ?: task.id.value,
                        reason = "أُلغيت المهمة بواسطة المستخدم أو بسبب انقطاع البيئة."
                    )
                )
            }
            throw e
        }
        // channelFlow closes its channel when this block returns — no
        // awaitClose needed (events are produced sequentially here).
    }.onEach { event ->
        // Mirror every event to the observability bus. P1-10: SUSPENDING
        // emit — backpressure instead of silent event loss (see the
        // publisher declaration above).
        _executionEventPublisher.emit(event)
    }

    /** The actual closed-loop body, extracted for cancellation-safe wrapping. */
    private suspend fun executeTaskLoop(
        collector: kotlinx.coroutines.flow.FlowCollector<in ExecutionEvent>,
        agent: AgentDefinition,
        task: TaskDefinition,
        conversationHistory: List<LlmMessage>,
        preferredProviderId: String?,
        networkPolicy: NetworkPolicy,
        isNetworkAvailable: Boolean,
        includeWebSearch: Boolean,
        restoredCheckpoint: TaskCheckpoint?,
        restoredContext: CanonicalExecutionContext?,
        pinnedWorkspaceId: String?
    ) {
        // ------------------------------------------------------------
        // CANONICAL CONTEXT BINDING (P0-02 / P0-03 / P1-01 / P1-03):
        // the workspace is captured ONCE — before the loop — and pinned for
        // the whole execution. A configured provider that cannot supply a
        // workspace fails CLOSED: no data is written into an implicit
        // "default" scope anymore.
        // ------------------------------------------------------------
        val resolvedWorkspaceId = pinnedWorkspaceId
            ?: restoredContext?.workspaceId
            ?: workspaceIdProvider?.invoke()
        if (workspaceIdProvider != null && pinnedWorkspaceId == null &&
            restoredContext == null && resolvedWorkspaceId == null
        ) {
            val reason = "WORKSPACE_CONTEXT_REQUIRED: لا توجد مساحة عمل نشطة لربط التنفيذ — أُوقف التنفيذ بأمان بدلاً من الكتابة في نطاق افتراضي."
            collector.emit(ExecutionEvent.Error(task.id.value, "WORKSPACE_CONTEXT_REQUIRED", reason, isFatal = true))
            // Persist the FAILED row (insert-or-update — the task may have no
            // row yet at this early failure point).
            runCatching {
                taskDao?.insertOrUpdateTask(
                    TaskEntity(
                        id = task.id.value,
                        assignedAgentId = agent.identity.id.value,
                        rawPrompt = task.input.rawPrompt,
                        lifecycleState = "FAILED",
                        autonomyPolicy = task.constraints.autonomyPolicy.name,
                        resultSummary = null,
                        totalTokensConsumed = 0,
                        durationMs = 0L,
                        isDegraded = false,
                        degradedReason = null,
                        errorMessage = reason,
                        createdAtEpochMs = System.currentTimeMillis(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                        goal = task.goal,
                        currentStepIndex = task.currentStepIndex,
                        tokenLimit = task.budget.tokenLimit,
                        maxRetries = task.constraints.maxRetries,
                        allowDegradedExecution = task.constraints.allowDegradedExecution,
                        requireHumanConsentForSensitiveTools = task.constraints.requireHumanConsentForSensitiveTools,
                        timeoutMs = task.constraints.timeoutMs,
                        minOutputLengthChars = task.successCriteria.minOutputLengthChars,
                        verificationStrategy = task.successCriteria.verificationStrategy.name,
                        assignedModelId = task.assignedModelId,
                        parentTaskId = task.input.parameters["parentTaskId"]?.toString(),
                        delegationDepth = task.input.parameters["delegationDepth"]?.toString()?.toIntOrNull() ?: 0
                    )
                )
            }
            return
        }

        // STABLE execution identity: a resumed execution keeps its original
        // executionId and increments the attempt counter (P1-03).
        //
        // Provider-less mode (pure JVM tests / legacy wiring) is labeled
        // HONESTLY as "unattributed" — never a fabricated "default" scope
        // (the fail-CLOSED path above already handles the configured-but-
        // missing case).
        val context = restoredContext?.nextAttempt() ?: CanonicalExecutionContext(
            executionId = "exec_" + UUID.randomUUID().toString(),
            taskId = task.id,
            workspaceId = resolvedWorkspaceId ?: "unattributed",
            projectId = projectIdProvider?.invoke()?.takeIf { it > 0 },
            agentId = agent.identity.id,
            agentRole = agent.identity.role,
            modelId = task.assignedModelId,
            parentTaskId = task.input.parameters["parentTaskId"]?.toString(),
            delegationDepth = task.input.parameters["delegationDepth"]?.toString()?.toIntOrNull() ?: 0,
            attempt = 1
        )
        val executionId = context.executionId
        val workspaceId = context.workspaceId.takeIf { it.isNotBlank() && it != UNATTRIBUTED_WORKSPACE }

        // 0. Persist Initial Task State in Room DB
        // GOVERNANCE PHASE: stamp the pinned workspace into the task parameters
        // so the decision context, economic gate, and radar evidence scope
        // correctly.
        var currentTask = if (workspaceId != null && task.input.parameters["workspaceId"] == null) {
            task.copy(
                state = TaskLifecycleState.RUNNING,
                input = task.input.copy(
                    parameters = task.input.parameters + ("workspaceId" to workspaceId)
                )
            )
        } else {
            task.copy(state = TaskLifecycleState.RUNNING)
        }
        persistTaskInitial(currentTask, agent, context)

        // ------------------------------------------------------------
        // PINNED WORKSPACE SCOPE (P0-02): every suspending call below this
        // point (memory writes, RAG retrieval, resource scoping) resolves the
        // workspace from THIS scope, not from the active-workspace StateFlow.
        // ------------------------------------------------------------
        withContext(ExecutionScope(executionId = executionId, workspaceId = context.workspaceId, projectId = context.projectId)) {
            executeClosedLoop(
                collector = collector,
                agent = agent,
                context = context,
                initialTask = currentTask,
                conversationHistory = conversationHistory,
                networkPolicy = networkPolicy,
                isNetworkAvailable = isNetworkAvailable,
                restoredCheckpoint = restoredCheckpoint
            )
        }
    }

    /** The closed-loop body — executed INSIDE the pinned ExecutionScope. */
    private suspend fun executeClosedLoop(
        collector: kotlinx.coroutines.flow.FlowCollector<in ExecutionEvent>,
        agent: AgentDefinition,
        context: CanonicalExecutionContext,
        initialTask: TaskDefinition,
        conversationHistory: List<LlmMessage>,
        networkPolicy: NetworkPolicy,
        isNetworkAvailable: Boolean,
        restoredCheckpoint: TaskCheckpoint?
    ) {
        val executionId = context.executionId
        val workspaceId = context.workspaceId.takeIf { it.isNotBlank() && it != UNATTRIBUTED_WORKSPACE }
        val startTime = System.currentTimeMillis()
        var currentTask = initialTask

        val maxSteps = (initialTask.constraints.maxRetries + 4).coerceIn(3, 8)

        // Restore durable state from the checkpoint when resuming after
        // process death — otherwise start a fresh loop.
        var stepIndex = restoredCheckpoint?.stepIndex ?: 0
        var consecutiveFailures = 0
        var accumulatedTokens = restoredCheckpoint?.tokensConsumed ?: 0
        val accumulatedOutputText = StringBuilder(restoredCheckpoint?.accumulatedOutput.orEmpty())
        val accumulatedEvidence = mutableMapOf<String, Any?>()
        restoredCheckpoint?.evidence?.let { restored -> accumulatedEvidence.putAll(restored) }
        val decisionHistory = mutableListOf<DecisionResult>()
        val observationHistory = mutableListOf<EnvironmentObservation>()
        var replayedActions = 0

        var currentDecisionState = decisionService.buildDecisionContext(
            task = currentTask,
            networkPolicy = networkPolicy,
            isNetworkAvailable = isNetworkAvailable,
            remainingTokens = currentTask.budget.tokenLimit - accumulatedTokens,
            consecutiveFailures = 0,
            uncertaintyScore = 0.2f
        ).toDecisionState()

        var isTerminal = false
        var isDegraded = false
        var degradedReason: DegradedReason? = null
        var finalResultText = ""

        collector.emit(
            ExecutionEvent.Started(
                executionId = executionId,
                agentId = agent.identity.id,
                modelId = "cbr_mdp_orchestrator",
                workspaceId = workspaceId
            )
        )

        while (!isTerminal && stepIndex < maxSteps) {
            // 1. Rebuild DecisionContext dynamically before every decision
            val decisionContext = decisionService.buildDecisionContext(
                task = currentTask.copy(currentStepIndex = stepIndex),
                networkPolicy = networkPolicy,
                isNetworkAvailable = isNetworkAvailable,
                remainingTokens = (currentTask.budget.tokenLimit - accumulatedTokens).coerceAtLeast(0),
                consecutiveFailures = consecutiveFailures,
                uncertaintyScore = currentDecisionState.uncertaintyScore,
                historyCount = conversationHistory.size,
                memoriesCount = (accumulatedEvidence["memorySnippets"] as? List<*>)?.size ?: 0,
                complexity = when {
                    currentTask.requirements.requiredCapabilities.size > 2 -> 0.8f
                    currentTask.requirements.requiredCapabilities.isNotEmpty() -> 0.6f
                    currentTask.goal.length > 200 -> 0.6f
                    else -> 0.5f
                },
                accumulatedEvidence = accumulatedEvidence,
                lastAction = decisionHistory.lastOrNull()?.chosenAction,
                lastObservation = observationHistory.lastOrNull(),
                decisionHistory = decisionHistory
            )

            // 2. CBR-MDP Evaluation
            val decisionResult = decisionService.evaluate(decisionContext)
            decisionHistory.add(decisionResult)
            val chosenAction = decisionResult.chosenAction

            collector.emit(
                ExecutionEvent.DecisionMade(
                    executionId = executionId,
                    decision = decisionResult
                )
            )

            // Handle early pause / termination actions
            if (chosenAction.type == DecisionActionType.COMPLETE || chosenAction.type == DecisionActionType.STOP) {
                val verification = outcomeService.verifyTaskCompletion(
                    task = currentTask,
                    accumulatedEvidence = accumulatedEvidence,
                    finalOutputText = accumulatedOutputText.toString(),
                    lastAction = chosenAction
                )
                // ------------------------------------------------------------
                // FIX D-2 (audit c03919d): the verification result is the REAL
                // terminal reward signal. Previously the COMPLETE action never
                // received an observation on the satisfied path (the loop broke
                // before recording anything), so the CBR-MDP engine could not
                // learn from acceptance-criteria outcomes at all.
                // ------------------------------------------------------------
                val quality = if (verification.isSatisfied) verification.confidence else -verification.confidence
                val terminalResult = ExecutionResult(
                    isSuccess = verification.isSatisfied,
                    outputText = accumulatedOutputText.toString().ifBlank {
                        chosenAction.payload["summary"] ?: "تم تقييم اكتمال المهمة."
                    },
                    outputData = mapOf(
                        "verificationStatus" to verification.status.name,
                        "satisfiedCriteria" to verification.satisfiedCriteria,
                        "missingCriteria" to verification.missingCriteria
                    )
                )
                val terminalObservation = observationService.createObservation(
                    action = chosenAction,
                    result = terminalResult,
                    stepIndex = stepIndex,
                    actionOutcome = outcomeService.evaluateActionOutcome(chosenAction, terminalResult),
                    taskVerificationQuality = quality
                )
                observationHistory.add(terminalObservation)
                currentDecisionState = decisionService.recordObservation(currentDecisionState, terminalObservation)
                collector.emit(
                    ExecutionEvent.ObservationRecorded(
                        executionId = executionId,
                        observation = terminalObservation,
                        updatedUncertainty = currentDecisionState.uncertaintyScore
                    )
                )
                if (verification.isSatisfied) {
                    val summaryText = accumulatedOutputText.toString().ifBlank { chosenAction.payload["summary"] ?: "تم إكمال المهمة بنجاح." }
                    finalResultText = summaryText
                    isTerminal = true
                    break
                } else {
                    consecutiveFailures++
                    accumulatedOutputText.append("\n[حوكمة]: لم تُستوفَ معايير الاكتمال: ${verification.missingCriteria.joinToString(", ")}")
                    // COMPLETE/STOP have no side effects to execute — learning
                    // already happened above; continue the loop to re-decide.
                    stepIndex++
                    currentTask = currentTask.copy(currentStepIndex = stepIndex)
                    continue
                }
            } else if (chosenAction.type == DecisionActionType.ASK_USER) {
                val reason = chosenAction.payload["reason"] ?: "مطلوب تأكيد أو مدخلات من المستخدم."
                persistTaskFinal(currentTask.id.value, "WAITING", reason, accumulatedTokens, System.currentTimeMillis() - startTime, isDegraded, degradedReason?.name, null)
                collector.emit(
                    ExecutionEvent.Degraded(
                        executionId = executionId,
                        reason = DegradedReason.UNKNOWN_DEGRADATION,
                        message = reason
                    )
                )
                isTerminal = true
                return
            }

            collector.emit(
                ExecutionEvent.ActionStarted(
                    executionId = executionId,
                    action = chosenAction,
                    stepIndex = stepIndex
                )
            )

            // ------------------------------------------------------------
            // AUTONOMY GOVERNANCE (defect family 4): sensitive actions are
            // evaluated against the EFFECTIVE autonomy policy (the more
            // restrictive of the workspace's authoritative policy and the
            // task's persisted policy) — NOT raw caller-supplied authority.
            // A policy that does not allow the action FAILS CLOSED: the task
            // stops honestly, waiting for explicit user consent/grants.
            // ------------------------------------------------------------
            if (chosenAction.type in SENSITIVE_AUTONOMY_ACTIONS) {
                val governor = autonomyGovernor
                if (governor != null) {
                    val evaluation = governor(
                        agent, chosenAction, true,
                        currentTask.constraints.autonomyPolicy
                    )
                    if (!evaluation.isAllowed || evaluation.requireHumanConsent) {
                        val autonomyMsg = "AUTONOMY_POLICY_BLOCKED: ${evaluation.reason}"
                        collector.emit(
                            ExecutionEvent.BudgetGateDecision(
                                executionId = executionId,
                                decision = com.example.domain.core.budget.EconomicGateDecision.DENIED.name,
                                reason = autonomyMsg
                            )
                        )
                        collector.emit(
                            ExecutionEvent.Degraded(
                                executionId = executionId,
                                reason = DegradedReason.UNKNOWN_DEGRADATION,
                                message = autonomyMsg
                            )
                        )
                        isDegraded = true
                        degradedReason = DegradedReason.UNKNOWN_DEGRADATION
                        accumulatedOutputText.append("\n[حوكمة الاستقلالية]: $autonomyMsg")
                        persistTaskFinal(
                            currentTask.id.value, "WAITING", autonomyMsg,
                            accumulatedTokens, System.currentTimeMillis() - startTime,
                            isDegraded, degradedReason?.name, null
                        )
                        isTerminal = true
                        break
                    }
                }
            }

            // ------------------------------------------------------------
            // AGENT BUDGET GOVERNANCE (defect family 4): the effective budget
            // is the AGENT's durable budget intersected with the task quota
            // (authoritative), and a BLOCK verdict stops further paid actions.
            // ------------------------------------------------------------
            if (chosenAction.type in TOKEN_QUOTA_GATED_ACTIONS) {
                val budgetEvaluation = budgetGovernor?.invoke(agent, currentTask, accumulatedTokens)
                if (budgetEvaluation != null &&
                    budgetEvaluation.recommendedAction ==
                    com.example.domain.core.agent.lifecycle.BudgetRecommendedAction.BLOCK
                ) {
                    val blockMsg = "AGENT_BUDGET_DEPLETED: لا توجد ميزانية متبقية للوكيل " +
                        "(${budgetEvaluation.remainingTokens} توكن متبقٍ) — توقّف الإنفاق."
                    collector.emit(
                        ExecutionEvent.BudgetGateDecision(
                            executionId = executionId,
                            decision = com.example.domain.core.budget.EconomicGateDecision.DENIED.name,
                            reason = blockMsg
                        )
                    )
                    collector.emit(
                        ExecutionEvent.Degraded(
                            executionId = executionId,
                            reason = DegradedReason.BUDGET_APPROACHING_LIMIT,
                            message = blockMsg
                        )
                    )
                    isDegraded = true
                    degradedReason = DegradedReason.BUDGET_APPROACHING_LIMIT
                    accumulatedOutputText.append("\n[حوكمة ميزانية الوكيل]: $blockMsg")
                    break
                }
            }

            // ------------------------------------------------------------
            // GOVERNANCE PHASE — PRE-EXECUTION TOKEN QUOTA GATE (execution
            // limit enforcement). TaskBudget.tokenLimit is the TOKEN QUOTA
            // (migration of the legacy tokenBudget concept): exceeding it now
            // STOPS further paid actions instead of silently continuing.
            // The monetary budget is enforced separately (DecisionService
            // economic gate). Security's own session ceiling is ALSO enforced
            // (validateTokenBudget was previously dead code with zero callers).
            // ------------------------------------------------------------
            if (chosenAction.type in TOKEN_QUOTA_GATED_ACTIONS &&
                accumulatedTokens >= currentTask.budget.tokenLimit
            ) {
                val quotaMsg = "TOKEN_QUOTA_EXCEEDED: استُهلكت حصة التوكنز للمهمة " +
                    "(${accumulatedTokens}/${currentTask.budget.tokenLimit}) — توقّف الإنفاق الإضافي."
                collector.emit(
                    ExecutionEvent.BudgetGateDecision(
                        executionId = executionId,
                        decision = com.example.domain.core.budget.EconomicGateDecision.DENIED.name,
                        reason = quotaMsg
                    )
                )
                collector.emit(
                    ExecutionEvent.Degraded(
                        executionId = executionId,
                        reason = DegradedReason.BUDGET_APPROACHING_LIMIT,
                        message = quotaMsg
                    )
                )
                isDegraded = true
                degradedReason = DegradedReason.BUDGET_APPROACHING_LIMIT
                accumulatedOutputText.append("\n[حوكمة الاقتصاد]: $quotaMsg")
                break
            }
            if (chosenAction.type in TOKEN_QUOTA_GATED_ACTIONS) {
                // Security session ceiling (upper safety bound — ordering:
                // permission/policy BEFORE budget; a budget allow never
                // overrides this deny). requestedTokens is the ESTIMATED
                // magnitude of the NEXT single step (not the whole remaining
                // quota) — the policy compares it against the accumulated
                // session total.
                val estimatedNextStepTokens = (currentTask.budget.tokenLimit - accumulatedTokens)
                    .coerceAtLeast(0)
                    .coerceAtMost(ESTIMATED_STEP_TOKENS)
                val sessionCheck = runCatching {
                    securityGuard.validateTokenBudget(
                        requestedTokens = estimatedNextStepTokens,
                        sessionTotalTokens = accumulatedTokens,
                        policy = defaultSecurityPolicy
                    )
                }.getOrNull()
                if (sessionCheck is Outcome.Error) {
                    val secMsg = "SECURITY_TOKEN_CEILING: ${sessionCheck.diagnosticMessage ?: "رفضت سياسة الأمان استمرار الإنفاق"}"
                    collector.emit(
                        ExecutionEvent.BudgetGateDecision(
                            executionId = executionId,
                            decision = com.example.domain.core.budget.EconomicGateDecision.DENIED.name,
                            reason = secMsg
                        )
                    )
                    collector.emit(
                        ExecutionEvent.Degraded(
                            executionId = executionId,
                            reason = DegradedReason.BUDGET_APPROACHING_LIMIT,
                            message = secMsg
                        )
                    )
                    isDegraded = true
                    degradedReason = DegradedReason.BUDGET_APPROACHING_LIMIT
                    accumulatedOutputText.append("\n[حوكمة الأمان]: $secMsg")
                    break
                }
            }

            // ------------------------------------------------------------
            // GOVERNANCE PHASE — surface budget-gate verdicts from the
            // DecisionService economic gate onto the event bus (telemetry +
            // radar evidence react to them).
            // ------------------------------------------------------------
            val budgetGateReason = chosenAction.payload["reason"]
            if (chosenAction.type == DecisionActionType.REPLAN && budgetGateReason?.startsWith("BUDGET_") == true) {
                collector.emit(
                    ExecutionEvent.BudgetGateDecision(
                        executionId = executionId,
                        decision = com.example.domain.core.budget.EconomicGateDecision.DOWNGRADE.name,
                        reason = "${budgetGateReason}: ${chosenAction.payload["detail"] ?: ""}"
                    )
                )
            }

            // ------------------------------------------------------------
            // ACTION IDEMPOTENCY GATE (P0-05 / P0-06): side-effectful actions
            // are recorded in the durable ledger BEFORE execution. A
            // COMPLETED intent from a previous attempt is REPLAYED (its
            // stored output becomes this step's result) — the side effect is
            // never repeated. Without a ledger (pure JVM tests) the gate is
            // honestly "no exactly-once guarantee".
            // ------------------------------------------------------------
            var execResult: ExecutionResult? = null
            val isGatedAction = chosenAction.type in idempotency.gatedActionTypes
            val gate = if (isGatedAction) {
                idempotency.begin(executionId, stepIndex, chosenAction)
            } else {
                null
            }
            when (gate) {
                is IntentGate.AlreadyCompleted -> {
                    replayedActions++
                    val stored = gate.intent.outputSummary.orEmpty()
                    execResult = ExecutionResult(
                        isSuccess = true,
                        outputText = if (stored.isBlank()) {
                            "[IDEMPOTENT_REPLAY]: نُفّذ هذا الإجراء سابقاً في المحاولة الأولى وأُعيدت نتيجته من سجل النوايا."
                        } else {
                            "[IDEMPOTENT_REPLAY]: $stored"
                        },
                        outputData = mapOf(
                            "replayed" to true,
                            "actionKey" to gate.intent.actionKey,
                            "replayFingerprint" to (gate.intent.outputFingerprint ?: "")
                        ),
                        tokensConsumed = 0
                    )
                    collector.emit(
                        ExecutionEvent.ActionCompleted(
                            executionId = executionId,
                            action = chosenAction.copy(
                                payload = chosenAction.payload + ("__replayed__" to "true")
                            ),
                            outputSummary = "[إعادة تشغيل من سجل الـIdempotency] ${gate.intent.actionKey}",
                            observation = observationService.createObservation(
                                action = chosenAction,
                                result = execResult,
                                stepIndex = stepIndex,
                                actionOutcome = com.example.application.outcome.ActionOutcomeType.SUCCESS
                            )
                        )
                    )
                }
                else -> {
                    // Proceed (fresh intent, ungated action, or honestly
                    // ledger-less mode) — then record the outcome.
                    execResult = executionService.executeAction(
                        action = chosenAction,
                        context = decisionContext,
                        agent = agent,
                        conversationHistory = conversationHistory,
                        executionId = executionId,
                        onEvent = { event -> collector.emit(event) }
                    )
                    if (isGatedAction) {
                        if (execResult.isSuccess) {
                            idempotency.complete(executionId, stepIndex, chosenAction, execResult.outputText)
                        } else {
                            idempotency.fail(
                                executionId, stepIndex, chosenAction,
                                execResult.errorDescription ?: "فشل غير محدد"
                            )
                        }
                    }
                }
            }
            val stepResult = execResult
                ?: ExecutionResult(isSuccess = false, outputText = "", errorDescription = "NO_RESULT")

            // 4. Update Token and Output Tracking
            accumulatedTokens += stepResult.tokensConsumed
            // GOVERNANCE PHASE: keep the LIVE task budget consumption accurate
            // (legacy defect: TaskBudget.consumedTokens was never incremented
            // during a run, so delegation carve-outs and remaining-budget
            // reporting always saw the full 30000).
            currentTask = currentTask.copy(
                budget = currentTask.budget.copy(consumedTokens = accumulatedTokens)
            )

            // ------------------------------------------------------------
            // GOVERNANCE PHASE — POST-EXECUTION USAGE ACCOUNTING:
            // attributed usage -> persistent cost ledger (+ telemetry +
            // RPM/TPM window) -> CostRecorded event on the bus.
            // Accounting uses the PINNED workspace (P0-02).
            // ------------------------------------------------------------
            val attribution = stepResult.usageDetail
            if (attribution != null && attribution.totalTokens > 0) {
                val record = runCatching {
                    economicGovernanceService?.accountUsageSuspend(
                        UsageAccountingInput(
                            executionId = executionId,
                            taskId = currentTask.id.value,
                            workspaceId = workspaceId,
                            agentId = agent.identity.id.value,
                            providerId = attribution.providerId,
                            serviceId = attribution.serviceId,
                            modelId = attribution.modelId,
                            resourceId = attribution.resourceId,
                            usage = com.example.domain.core.budget.TokenUsageRecord(
                                inputTokens = attribution.inputTokens,
                                outputTokens = attribution.outputTokens,
                                cachedTokens = attribution.cachedTokens,
                                providerTotalTokens = attribution.totalTokens,
                                isEstimate = attribution.isEstimated
                            ),
                            isActualProviderReport = !attribution.isEstimated
                        )
                    )
                }.getOrNull()
                if (record != null) {
                    collector.emit(
                        ExecutionEvent.CostRecorded(
                            executionId = executionId,
                            inputTokens = record.usage.inputTokens,
                            outputTokens = record.usage.outputTokens,
                            cachedTokens = record.usage.cachedTokens,
                            totalTokens = record.usage.totalTokens,
                            costAmountMicro = record.cost?.amountMicro,
                            currency = record.cost?.currency ?: "USD",
                            costStatus = record.costStatus.name,
                            billingClass = record.billingClass.name,
                            providerId = record.providerId,
                            modelId = record.modelId
                        )
                    )
                }
            }
            if (stepResult.outputText.isNotBlank()) {
                if (accumulatedOutputText.isNotEmpty() && !accumulatedOutputText.endsWith("\n")) {
                    accumulatedOutputText.append("\n")
                }
                accumulatedOutputText.append(stepResult.outputText)
            }
            if (stepResult.isDegraded) {
                isDegraded = true
                degradedReason = stepResult.degradedReason
            }

            // 5. Merge Evidence into context memory
            accumulatedEvidence.putAll(stepResult.outputData)
            if (stepResult.outputText.isNotBlank()) {
                accumulatedEvidence["step_${stepIndex}_output"] = stepResult.outputText
            }

            // 6. Normalize Observation
            // FIX D-3 (audit c03919d): the previously-dead
            // OutcomeService.evaluateActionOutcome is now wired into the live
            // loop — its classification shapes the CBR-MDP feedback reward.
            val actionOutcomeType = outcomeService.evaluateActionOutcome(chosenAction, stepResult)
            val observation = observationService.createObservation(
                action = chosenAction,
                result = stepResult,
                stepIndex = stepIndex,
                actionOutcome = actionOutcomeType
            )
            observationHistory.add(observation)

            // 7. Feed Observation into CBR-MDP Engine -> updates belief state and retains case
            currentDecisionState = decisionService.recordObservation(currentDecisionState, observation)
            collector.emit(
                ExecutionEvent.ObservationRecorded(
                    executionId = executionId,
                    observation = observation,
                    updatedUncertainty = currentDecisionState.uncertaintyScore
                )
            )

            if (stepResult.isSuccess) {
                consecutiveFailures = 0
                if (gate !is IntentGate.AlreadyCompleted) {
                    collector.emit(
                        ExecutionEvent.ActionCompleted(
                            executionId = executionId,
                            action = chosenAction,
                            outputSummary = observation.outputSummary,
                            observation = observation
                        )
                    )
                }
            } else {
                consecutiveFailures++
                collector.emit(
                    ExecutionEvent.ActionFailed(
                        executionId = executionId,
                        action = chosenAction,
                        errorDescription = stepResult.errorDescription ?: "فشل في تنفيذ الإجراء",
                        observation = observation
                    )
                )
            }

            // 8. Outcome & Objective Verification
            val isObjectiveSatisfied = outcomeService.isTaskObjectiveSatisfied(
                task = currentTask,
                accumulatedEvidence = accumulatedEvidence,
                finalOutputText = accumulatedOutputText.toString(),
                lastAction = chosenAction
            )

            stepIndex++
            currentTask = currentTask.copy(
                currentStepIndex = stepIndex,
                state = if (isObjectiveSatisfied) TaskLifecycleState.COMPLETED else TaskLifecycleState.RUNNING,
                outcomeSummary = accumulatedOutputText.toString().take(200)
            )

            if (!persistTaskUpdate(
                    taskId = currentTask.id.value,
                    stateStr = currentTask.state.name,
                    outcomeSummary = currentTask.outcomeSummary,
                    tokensConsumed = accumulatedTokens,
                    durationMs = System.currentTimeMillis() - startTime,
                    isDegraded = isDegraded,
                    degradedReason = degradedReason?.name,
                    errorMsg = if (!stepResult.isSuccess) stepResult.errorDescription else null
                )
            ) {
                collector.emit(
                    ExecutionEvent.Error(
                        executionId = executionId,
                        failureCode = "TASK_PERSISTENCE_FAILED",
                        message = "تعذّر تحديث حالة المهمة في قاعدة البيانات — الحالة الجارية في الذاكرة فقط.",
                        isFatal = false
                    )
                )
            }

            // ------------------------------------------------------------
            // AUTHORITATIVE CHECKPOINT (P0-05): persist the loop state after
            // EVERY step. A FAILED checkpoint write is FATAL — continuing
            // would create an execution whose recovery semantics are
            // unknowable (a crash later could re-run side effects with no
            // ledger guarantee). Fail closed, honestly.
            // ------------------------------------------------------------
            val checkpointPersisted = persistCheckpoint(
                taskId = currentTask.id.value,
                checkpoint = TaskCheckpoint(
                    stepIndex = stepIndex,
                    tokensConsumed = accumulatedTokens,
                    accumulatedOutput = accumulatedOutputText.toString(),
                    evidence = accumulatedEvidence
                )
            )
            if (!checkpointPersisted) {
                val reason = "CHECKPOINT_PERSISTENCE_FAILED: تعذّر حفظ نقطة الاستئناف بعد الخطوة $stepIndex — أُوقف التنفيذ بأمان لأن استئنافه لاحقاً لم يعد مضموناً (exactly-once)."
                collector.emit(
                    ExecutionEvent.Error(
                        executionId = executionId,
                        failureCode = "CHECKPOINT_PERSISTENCE_FAILED",
                        message = reason,
                        isFatal = true
                    )
                )
                persistTaskFinal(currentTask.id.value, "FAILED", accumulatedOutputText.toString().take(200), accumulatedTokens, System.currentTimeMillis() - startTime, isDegraded, degradedReason?.name, reason)
                return
            }

            val isTerminalCondition = outcomeService.isTerminalConditionReached(
                task = currentTask,
                stepCount = stepIndex,
                maxSteps = maxSteps,
                consecutiveFailures = consecutiveFailures,
                isObjectiveMet = isObjectiveSatisfied
            )

            if (isTerminalCondition) {
                finalResultText = accumulatedOutputText.toString()
                isTerminal = true
                if (!isObjectiveSatisfied && consecutiveFailures > currentTask.constraints.maxRetries) {
                    val failureMsg = "تجاوزت المهمة الحد الأقصى للمحاولات (${currentTask.constraints.maxRetries}) دون الوصول للهدف: ${stepResult.errorDescription}"
                    collector.emit(
                        ExecutionEvent.Error(
                            executionId = executionId,
                            failureCode = "MAX_RETRIES_EXCEEDED",
                            message = failureMsg,
                            isFatal = true
                        )
                    )
                    persistTaskFinal(currentTask.id.value, "FAILED", null, accumulatedTokens, System.currentTimeMillis() - startTime, isDegraded, degradedReason?.name, failureMsg)
                    return
                }
            }
        }

        // 9. Final Objective Verification before emitting Completed Event
        val isFinalObjectiveMet = outcomeService.isTaskObjectiveSatisfied(
            task = currentTask,
            accumulatedEvidence = accumulatedEvidence,
            finalOutputText = accumulatedOutputText.toString(),
            lastAction = decisionHistory.lastOrNull()?.chosenAction ?: DecisionAction(DecisionActionType.STOP)
        )

        val totalDuration = System.currentTimeMillis() - startTime
        val finalOutput = if (finalResultText.isNotBlank()) finalResultText else accumulatedOutputText.toString().ifBlank { "اكتملت معالجة المهمة." }

        if (!isFinalObjectiveMet && !currentTask.constraints.allowDegradedExecution) {
            val failureMsg = "فشلت المهمة في استيفاء معايير القبول المحددة بعد $stepIndex خطوات."
            persistTaskFinal(currentTask.id.value, "FAILED", finalOutput.take(200), accumulatedTokens, totalDuration, isDegraded, degradedReason?.name, failureMsg)
            collector.emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "OBJECTIVE_NOT_SATISFIED",
                    message = failureMsg,
                    isFatal = true
                )
            )
            return
        }

        val stateStr = if (isDegraded || !isFinalObjectiveMet) "DEGRADED" else "COMPLETED"
        val effectiveDegradedReason = if (!isFinalObjectiveMet) DegradedReason.PARTIAL_EVIDENCE else degradedReason

        if (!persistTaskFinal(
                taskId = currentTask.id.value,
                stateStr = stateStr,
                outcomeSummary = finalOutput.take(200),
                tokensConsumed = accumulatedTokens,
                durationMs = totalDuration,
                isDegraded = isDegraded || !isFinalObjectiveMet,
                degradedReason = effectiveDegradedReason?.name,
                errorMsg = null
            )
        ) {
            collector.emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "TASK_PERSISTENCE_FAILED",
                    message = "اكتمل التنفيذ لكن تعذّر تدوين الحالة النهائية في قاعدة البيانات — قد تظهر المهمة RUNNING في الواجهة رغم انتهائها.",
                    isFatal = false
                )
            )
        }

        collector.emit(
            ExecutionEvent.Completed(
                executionId = executionId,
                finalText = finalOutput,
                totalDurationMs = totalDuration,
                isDegraded = isDegraded || !isFinalObjectiveMet,
                degradedReason = effectiveDegradedReason
            )
        )
    }

    /**
     * Honest scope label for provider-less executions (pure JVM tests /
     * legacy wiring): the execution is UNATTRIBUTED — never a fabricated
     * "default" workspace.
     */
    private val UNATTRIBUTED_WORKSPACE = "unattributed"

    /** Actions that consume the task token quota (execution limit). */
    private val TOKEN_QUOTA_GATED_ACTIONS = setOf(
        DecisionActionType.EXECUTE_STEP,
        DecisionActionType.SELECT_MODEL,
        DecisionActionType.SEARCH,
        DecisionActionType.RETRIEVE_KNOWLEDGE,
        DecisionActionType.DELEGATE,
        DecisionActionType.RETRY
    )

    /** Sensitive actions gated by the EFFECTIVE autonomy policy (family 4). */
    private val SENSITIVE_AUTONOMY_ACTIONS = setOf(
        DecisionActionType.EXECUTE_TOOL,
        DecisionActionType.SELECT_TOOL,
        DecisionActionType.EXECUTE_MCP,
        DecisionActionType.EXECUTE_SKILL,
        DecisionActionType.USE_INTEGRATION
    )

    /**
     * Honest per-step token magnitude estimate for the security session
     * ceiling check (GenerationConfig.maxOutputTokens = 2048 + context —
     * 4096 is the documented heuristic bound, labeled as an estimate).
     */
    private val ESTIMATED_STEP_TOKENS = 4096

    /**
     * Resumes execution of a previously persisted task from Room database.
     *
     * FIX APP-P0-07: Previously this method reconstructed TaskDefinition with ONLY 4 fields
     * (id, agentId, rawPrompt, state), dropping specification/requirements/constraints/budget/
     * successCriteria/currentStepIndex/outcomeSummary. The resumed task started fresh from
     * step 0 with default constraints — effectively a re-execution, not a resume. Now we
     * reconstruct the full TaskDefinition from the extended TaskEntity.
     *
     * Gap-closure P1-03 / P1-04: the ORIGINAL canonical context (executionId,
     * pinned workspace, pinned agent identity) is restored from
     * `tasks.executionContextJson`. The ORIGINAL agent must exist and match —
     * a resume NO LONGER silently migrates the task to a different agent
     * (different prompt/capabilities/permissions/model). When the original
     * agent is unavailable the resume fails honestly (AGENT_UNAVAILABLE).
     */
    fun resumeTask(taskId: String): Flow<ExecutionEvent> = flow {
        val dao = taskDao
        if (dao == null) {
            emit(ExecutionEvent.Error("resume_err", "NO_PERSISTENCE", "قاعدة البيانات غير متاحة لاستئناف المهمة."))
            return@flow
        }
        val taskEntity = dao.getTaskById(taskId)
        if (taskEntity == null) {
            emit(ExecutionEvent.Error("resume_err", "TASK_NOT_FOUND", "المهمة ذات المعرف $taskId غير موجودة."))
            return@flow
        }

        // Restore the ORIGINAL canonical execution context (P1-03): the
        // resumed execution keeps its stable executionId and pinned scope.
        val restoredContext = ExecutionContextCodec.decode(taskEntity.executionContextJson)

        val restoredState = try {
            TaskLifecycleState.valueOf(taskEntity.lifecycleState)
        } catch (_: IllegalArgumentException) {
            // Legacy state strings that aren't valid enum values — default to CREATED
            // so the orchestrator can re-run the task instead of crashing.
            TaskLifecycleState.CREATED
        }

        // Reconstruct full TaskDefinition from the extended TaskEntity.
        val taskDef = TaskDefinition(
            id = TaskId(taskEntity.id),
            assignedAgentId = AgentId(taskEntity.assignedAgentId),
            goal = taskEntity.goal.ifBlank { taskEntity.rawPrompt },
            input = TaskInput(rawPrompt = taskEntity.rawPrompt),
            state = restoredState,
            budget = TaskBudget(
                tokenLimit = taskEntity.tokenLimit,
                consumedTokens = taskEntity.totalTokensConsumed
            ),
            constraints = TaskConstraints(
                timeoutMs = taskEntity.timeoutMs,
                maxRetries = taskEntity.maxRetries,
                allowDegradedExecution = taskEntity.allowDegradedExecution,
                autonomyPolicy = try {
                    AutonomyPolicy.valueOf(taskEntity.autonomyPolicy)
                } catch (_: IllegalArgumentException) {
                    AutonomyPolicy.SUPERVISED
                },
                requireHumanConsentForSensitiveTools = taskEntity.requireHumanConsentForSensitiveTools
            ),
            successCriteria = TaskSuccessCriteria(
                minOutputLengthChars = taskEntity.minOutputLengthChars,
                verificationStrategy = try {
                    VerificationStrategy.valueOf(taskEntity.verificationStrategy)
                } catch (_: IllegalArgumentException) {
                    VerificationStrategy.STRICT
                },
                requiredOutputKeys = parseJsonStringArray(taskEntity.requiredOutputKeysJson),
                requiredEvidenceKeys = parseJsonStringArray(taskEntity.requiredEvidenceKeysJson)
            ),
            assignedModelId = taskEntity.assignedModelId,
            activeTools = parseJsonStringArray(taskEntity.activeToolsJson),
            currentStepIndex = taskEntity.currentStepIndex,
            executionLog = parseJsonStringArray(taskEntity.executionLogJson),
            outcomeSummary = taskEntity.resultSummary
        )

        // ------------------------------------------------------------
        // AGENT IDENTITY VERIFICATION (P1-04): a resume must run the SAME
        // agent the execution started with. Silent agent migration (with a
        // different prompt, capabilities, permissions or model) is exactly
        // the "Resume became Migration" defect — it now fails honestly.
        // ------------------------------------------------------------
        val assignedAgent = registry.getAgent(taskEntity.assignedAgentId)
        if (assignedAgent == null) {
            emit(
                ExecutionEvent.Error(
                    "resume_err",
                    "AGENT_UNAVAILABLE",
                    "الوكيل الأصلي '${taskEntity.assignedAgentId}' غير مسجّل — يُرفض الاستئناف بدلاً من الترحيل الصامت إلى وكيل آخر بصلاحيات مختلفة. أعد تسجيل الوكيل أو شغّل المهمة من جديد."
                )
            )
            runCatching {
                dao.updateTaskStatus(
                    id = taskId,
                    state = "BLOCKED",
                    summary = "الوكيل الأصلي غير متوفر — الاستئناف مرفوض (منع الترحيل الصامت).",
                    tokens = taskEntity.totalTokensConsumed,
                    duration = taskEntity.durationMs,
                    isDegraded = taskEntity.isDegraded,
                    degradedReason = taskEntity.degradedReason,
                    errorMsg = "AGENT_UNAVAILABLE",
                    now = System.currentTimeMillis()
                )
            }
            return@flow
        }
        if (restoredContext != null && restoredContext.agentRole != assignedAgent.identity.role) {
            emit(
                ExecutionEvent.Error(
                    "resume_err",
                    "AGENT_IDENTITY_MISMATCH",
                    "دور الوكيل المسجّل (${assignedAgent.identity.role.name}) لا يطابق الدور المثبّت في سياق التنفيذ (${restoredContext.agentRole.name}) — يُرفض الاستئناف حفاظاً على هوية التنفيذ."
                )
            )
            return@flow
        }

        // Durable resume (audit 2026 fix): restore the persisted closed-loop
        // checkpoint (step, evidence, output, tokens) instead of silently
        // re-running the task from step 0.
        val restoredCheckpoint = TaskCheckpoint.fromJson(taskEntity.checkpointJson)
        executeTaskStream(
            agent = assignedAgent,
            task = taskDef,
            restoredCheckpoint = restoredCheckpoint,
            restoredContext = restoredContext
        ).collect { emit(it) }
    }

    /**
     * Startup recovery sweep (audit 2026 fix): resumes tasks left RUNNING by
     * process death. Called once from AppContainer.bootstrapRuntime(). Only
     * top-level (non-delegated) tasks are auto-resumed, bounded by [maxTasks]
     * to avoid resume storms; a sweep never runs concurrently with itself.
     *
     * Returns the ids of the tasks that were re-launched.
     */
    fun resumeInterruptedTasks(maxTasks: Int = 3): List<String> {
        if (!resumeSweepRunning.compareAndSet(false, true)) return emptyList()
        val resumedIds = mutableListOf<String>()
        coroutineScope.launch {
            try {
                val dao = taskDao ?: return@launch
                val allTasks = dao.getAllTasks()
                val interrupted = allTasks
                    .filter {
                        it.lifecycleState == "RUNNING" &&
                            it.parentTaskId == null &&
                            it.delegationDepth == 0
                    }
                    .take(maxTasks)
                for (entity in interrupted) {
                    resumedIds.add(entity.id)
                    launch {
                        resumeTask(entity.id).collect { /* events flow through telemetry bus */ }
                    }
                }
                // ------------------------------------------------------------
                // P1-9 FIX (audit 2026 §19 — recovery does not cover the
                // delegated execution graph): ORPHANED delegated children
                // left RUNNING by process death are RECONCILED instead of
                // lingering as zombie rows forever:
                //  - child whose parent is also RUNNING → left untouched
                //    (the resumed parent re-drives its delegation subtree);
                //  - child whose parent is TERMINAL (COMPLETED / FAILED /
                //    CANCELLED / DEGRADED) or MISSING → marked CANCELLED
                //    with an explicit reason. No re-execution of an orphan
                //    (that could duplicate side effects); no zombie rows.
                // ------------------------------------------------------------
                val runningParents = allTasks
                    .filter { it.lifecycleState == "RUNNING" }
                    .map { it.id }
                    .toSet()
                val orphanedChildren = allTasks.filter { entity ->
                    entity.lifecycleState == "RUNNING" &&
                        (entity.parentTaskId != null || entity.delegationDepth > 0) &&
                        entity.parentTaskId !in runningParents
                }
                for (orphan in orphanedChildren) {
                    runCatching {
                        dao.updateTaskStatus(
                            id = orphan.id,
                            state = "CANCELLED",
                            summary = "أُلغيت المهمة المفوَّضة اليتيمة أثناء استرداد الإقلاع: لم يعد الأب قابلاً للاستئناف " +
                                "(حالته: ${allTasks.firstOrNull { it.id == orphan.parentTaskId }?.lifecycleState ?: "غير موجودة"}).",
                            tokens = orphan.totalTokensConsumed,
                            duration = orphan.durationMs,
                            isDegraded = false,
                            degradedReason = null,
                            errorMsg = "ORPHANED_DELEGATED_CHILD_RECONCILED",
                            now = System.currentTimeMillis()
                        )
                    }
                }
            } finally {
                resumeSweepRunning.set(false)
            }
        }
        return resumedIds
    }

    /**
     * Persists the durable loop checkpoint (P0-05: AUTHORITATIVE — the
     * caller treats a `false` result as fatal for the execution).
     */
    private suspend fun persistCheckpoint(taskId: String, checkpoint: TaskCheckpoint): Boolean {
        val dao = taskDao ?: return true // no persistence wired — nothing to guarantee
        return try {
            dao.updateCheckpoint(
                id = taskId,
                stepIndex = checkpoint.stepIndex,
                checkpointJson = checkpoint.toJson(),
                tokens = checkpoint.tokensConsumed,
                now = System.currentTimeMillis()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parses a JSON-encoded string array back to List<String>. Returns emptyList on any failure
     * so resume is resilient to legacy / null / malformed rows.
     */
    private fun parseJsonStringArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                out.add(arr.getString(i))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Encodes a List<String> as a JSON array string for Room storage. */
    private fun encodeStringArray(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private suspend fun persistTaskInitial(
        task: TaskDefinition,
        agent: AgentDefinition,
        context: CanonicalExecutionContext
    ) {
        val dao = taskDao ?: return
        try {
            dao.insertOrUpdateTask(
                TaskEntity(
                    id = task.id.value,
                    assignedAgentId = agent.identity.id.value,
                    rawPrompt = task.input.rawPrompt,
                    lifecycleState = "INITIALIZED",
                    autonomyPolicy = task.constraints.autonomyPolicy.name,
                    resultSummary = null,
                    totalTokensConsumed = 0,
                    durationMs = 0L,
                    isDegraded = false,
                    degradedReason = null,
                    errorMessage = null,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    // FIX APP-P0-07: persist full-fidelity fields for resume round-trip
                    goal = task.goal,
                    currentStepIndex = task.currentStepIndex,
                    tokenLimit = task.budget.tokenLimit,
                    maxRetries = task.constraints.maxRetries,
                    allowDegradedExecution = task.constraints.allowDegradedExecution,
                    requireHumanConsentForSensitiveTools = task.constraints.requireHumanConsentForSensitiveTools,
                    timeoutMs = task.constraints.timeoutMs,
                    minOutputLengthChars = task.successCriteria.minOutputLengthChars,
                    verificationStrategy = task.successCriteria.verificationStrategy.name,
                    assignedModelId = task.assignedModelId,
                    activeToolsJson = encodeStringArray(task.activeTools),
                    requiredCapabilitiesJson = null, // Set<CapabilityType> not serializable here; deferred to Phase 2
                    requiredEvidenceKeysJson = encodeStringArray(task.successCriteria.requiredEvidenceKeys),
                    requiredOutputKeysJson = encodeStringArray(task.successCriteria.requiredOutputKeys),
                    executionLogJson = encodeStringArray(task.executionLog),
                    // Delegation lineage (audit 2026 fix) — child tasks carry
                    // their parent id and nesting depth for tracing + guards.
                    parentTaskId = task.input.parameters["parentTaskId"]?.toString(),
                    delegationDepth = task.input.parameters["delegationDepth"]?.toString()?.toIntOrNull() ?: 0,
                    // Canonical execution context (gap-closure): stable
                    // identity + pinned scope, restored on resume.
                    executionContextJson = ExecutionContextCodec.encode(context),
                    // P1-7: explicit owning-workspace identity on the task row
                    // (from the pinned canonical context — never implicit).
                    workspaceId = context.workspaceId
                )
            )
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    /** @return true when the status row was written; false = honest failure. */
    private suspend fun persistTaskUpdate(
        taskId: String,
        stateStr: String,
        outcomeSummary: String?,
        tokensConsumed: Int,
        durationMs: Long,
        isDegraded: Boolean,
        degradedReason: String?,
        errorMsg: String?
    ): Boolean {
        val dao = taskDao ?: return true
        return try {
            dao.updateTaskStatus(
                id = taskId,
                state = stateStr,
                summary = outcomeSummary,
                tokens = tokensConsumed,
                duration = durationMs,
                isDegraded = isDegraded,
                degradedReason = degradedReason,
                errorMsg = errorMsg,
                now = System.currentTimeMillis()
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** @return true when the status row was written; false = honest failure. */
    private suspend fun persistTaskFinal(
        taskId: String,
        stateStr: String,
        outcomeSummary: String?,
        tokensConsumed: Int,
        durationMs: Long,
        isDegraded: Boolean,
        degradedReason: String?,
        errorMsg: String?
    ): Boolean {
        return persistTaskUpdate(
            taskId, stateStr, outcomeSummary, tokensConsumed, durationMs, isDegraded, degradedReason, errorMsg
        )
    }
}
