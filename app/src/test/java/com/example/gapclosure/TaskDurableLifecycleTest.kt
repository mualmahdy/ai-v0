package com.example.gapclosure

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionContextCodec
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.execution.CanonicalExecutionContext
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.Outcome
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskSuccessCriteria
import com.example.domain.ports.llm.LlmProviderPort
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ============================================================================
 * GAP-10 + GAP-11 (Design Closure 2026) — TaskDurableLifecycleTest
 * ============================================================================
 *
 * GAP-10 — cancellation previously wrote `tokens = restoredCheckpoint?.tokensConsumed ?: 0`,
 * RESETTING the task row's total to the start-of-run value (or zero) and
 * erasing every token the cancelled run had actually consumed (per-step
 * persistence had already written the honest value). The row's CURRENT
 * total is now authoritative.
 *
 * GAP-11 — timeout previously persisted the SAME "WAITING" state as
 * ASK_USER/consent pauses (indistinguishable, never resumable from the
 * tasks surface) and multiplied the timeout budget by the attempt counter
 * (a repeatedly-resumed task ran effectively unbounded). Timeout now
 * persists the DISTINCT resumable "TIMED_OUT" state with the task's OWN
 * cap, and manual resume is guarded to resumable states only.
 */
class TaskDurableLifecycleTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var taskDao: RecordingTaskDao

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent_durable"),
            name = "Durable Agent",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "durable",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
        budget = AgentBudget(maxTokens = 30000)
    )

    /** Steps gate: completes steps 1..allowance, then blocks step N+1. */
    private class GatedStepProvider(
        val tokensPerStep: Int = 20,
        val textPerStep: String = "Generated answer with enough substance for the loop to keep working toward the goal."
    ) : LlmProviderPort {
        override val providerId: String = "gated_provider"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "gated_provider", name = "Gated", providerType = "FAKE",
            defaultModel = "gated-1", isConfigured = true, isOnline = true, isLocal = false
        )
        @Volatile
        var generateCalls = 0
        var allowance = Int.MAX_VALUE
        val blockGate = CompletableDeferred<Unit>()

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            generateCalls++
            if (generateCalls > allowance) blockGate.await() // hold this step open
            return Outcome.Success(
                LlmResponse(
                    text = textPerStep,
                    usage = TokenUsage(tokensPerStep, tokensPerStep),
                    finishReason = "STOP",
                    modelId = "gated-1"
                )
            )
        }

        /**
         * The loop's REAL provider path is stream() (generate() is only the
         * no-text fallback) — the gate must hold HERE, or the producer never
         * actually blocks mid-run and the cancellation test races a loop
         * that has already run to its terminal state.
         */
        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            generateCalls++
            if (generateCalls > allowance) blockGate.await() // hold this step open
            emit(ExecutionEvent.Completed(executionId, textPerStep, totalDurationMs = 5))
        }
    }

    /** In-memory TaskDao that RECORDS every status write (the assertions read it). */
    private class RecordingTaskDao : TaskDao {
        val rows = java.util.concurrent.ConcurrentHashMap<String, TaskEntity>()
        val statusWrites = ConcurrentLinkedQueue<Pair<String, Int>>() // state to tokens

        override fun getAllTasksFlow(): Flow<List<TaskEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun getAllTasks(): List<TaskEntity> = rows.values.toList()
        override fun getTasksForWorkspaceFlow(workspaceId: String): Flow<List<TaskEntity>> =
            MutableStateFlow(rows.values.filter { it.workspaceId == workspaceId })
        override suspend fun getTasksForWorkspace(workspaceId: String): List<TaskEntity> =
            rows.values.filter { it.workspaceId == workspaceId }
        override suspend fun getTasksForWorkspaceAndProject(workspaceId: String, projectId: Long?): List<TaskEntity> =
            rows.values.filter { it.workspaceId == workspaceId && it.projectId == projectId }
        override suspend fun reassignProject(ids: List<String>, projectId: Long?) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(projectId = projectId) } }
        }
        override suspend fun getTasksForProject(projectId: Long): List<TaskEntity> =
            rows.values.filter { it.projectId == projectId }
        override suspend fun getTaskById(id: String): TaskEntity? = rows[id]
        override suspend fun insertOrUpdateTask(task: TaskEntity) { rows[task.id] = task }
        override suspend fun updateTaskStatus(
            id: String, state: String, summary: String?, tokens: Int, duration: Long,
            isDegraded: Boolean, degradedReason: String?, errorMsg: String?, now: Long
        ) {
            statusWrites.add(state to tokens)
            rows[id]?.let { rows[id] = it.copy(lifecycleState = state, totalTokensConsumed = tokens, isDegraded = isDegraded, degradedReason = degradedReason, resultSummary = summary, errorMessage = errorMsg) }
        }
        override suspend fun updateCheckpoint(id: String, stepIndex: Int, checkpointJson: String, tokens: Int, now: Long) {
            rows[id]?.let { rows[id] = it.copy(currentStepIndex = stepIndex, checkpointJson = checkpointJson, totalTokensConsumed = tokens) }
        }

        fun seed(entity: TaskEntity) { rows[entity.id] = entity }
    }

    @Before
    fun setup() {
        registry = ComponentRegistry()
        registry.registerAgent(testAgent)
        taskDao = RecordingTaskDao()
        val decisionService = DecisionService(
            com.example.domain.core.decision.CbrMdpEngine(),
            registry,
            SecurityGuardService()
        )
        orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = SecurityGuardService(),
            decisionService = decisionService,
            taskDao = taskDao
        )
        orchestrator.workspaceIdProvider = { "ws_durable" }
    }

    /**
     * The objective can NEVER be satisfied (minOutputLengthChars huge), so the
     * closed loop keeps executing LLM steps until maxSteps — giving the test
     * multiple completed (and persisted) steps before a mid-flight event.
     */
    private fun unsatisfiableTask(id: String, timeoutMs: Long = 600_000L): TaskDefinition =
        TaskDefinition(
            id = TaskId(id),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput(rawPrompt = "keep working"),
            constraints = TaskConstraints(timeoutMs = timeoutMs, maxRetries = 4),
            successCriteria = TaskSuccessCriteria(minOutputLengthChars = 1_000_000)
        )

    private fun register(provider: GatedStepProvider) {
        com.example.application.testing.TestResourceRegistration.registerLlmProvider(registry, provider)
    }

    // ------------------------------------------------------------------
    // GAP-10 — cancellation preserves the ACTUAL consumed tokens
    // ------------------------------------------------------------------

    @Test
    fun `cancellation preserves tokens consumed by the cancelled run`() = runBlocking {
        val provider = GatedStepProvider(tokensPerStep = 20)
        provider.allowance = 1 // call #1 completes (row gains tokens); call #2 PARKS the run
        register(provider)

        // A resumed run: the durable checkpoint carries 7 tokens from a
        // previous attempt; per-step persistence during THIS run pushes the
        // row's total past that. The OLD code wrote
        // `restoredCheckpoint?.tokensConsumed ?: 0` on cancellation —
        // RESETTING the row's honest total to the stale start-of-run value.
        val restored = AgentOrchestrator.TaskCheckpoint(
            stepIndex = 1, tokensConsumed = 7, accumulatedOutput = "prior work"
        )
        // A required output key no step ever produces: the objective can
        // NEVER be satisfied, so the closed loop keeps issuing steps (the
        // verifier cannot terminate the task early through the length
        // criterion once evidence exists).
        val task = unsatisfiableTask("task-cancel").let {
            it.copy(
                successCriteria = it.successCriteria.copy(
                    requiredOutputKeys = listOf("final_deliverable_hash")
                )
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job: Job = scope.launch {
            orchestrator.executeTaskStream(testAgent, task, restoredCheckpoint = restored).collect { }
        }

        // DETERMINISTIC park: wait until the provider is ON its 2nd call —
        // the gate holds that call open, so the run is guaranteed
        // mid-execution from this point on.
        val parked = withTimeout(30_000L) {
            while (provider.generateCalls < 2) {
                kotlinx.coroutines.delay(50)
            }
            true
        }
        assertTrue("the run must reach the parked provider call (calls=${provider.generateCalls})", parked)
        assertTrue(
            "a completed step must have pushed the row's tokens past the checkpoint baseline",
            (taskDao.rows[task.id.value]?.totalTokensConsumed ?: 0) > 7
        )

        job.cancelAndJoin()

        // The final status write must be CANCELLED with the row's real total.
        val cancelledWrite = taskDao.statusWrites.lastOrNull { it.first == "CANCELLED" }
        assertNotNull(
            "a CANCELLED status write must exist; writes=${taskDao.statusWrites}",
            cancelledWrite
        )
        val rowTotal = taskDao.rows[task.id.value]?.totalTokensConsumed ?: 0
        assertTrue(
            "GAP-10: cancelled row must keep consumed tokens > 0 (row total = $rowTotal), " +
                    "the old code reset them to the start-of-run checkpoint (7)",
            cancelledWrite!!.second > 7
        )
        assertEquals(
            "GAP-10: the CANCELLED write must carry the row's CURRENT total, " +
                    "not the stale restored checkpoint value",
            rowTotal,
            cancelledWrite.second
        )
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // GAP-11 — timeout persists the DISTINCT resumable TIMED_OUT state
    // ------------------------------------------------------------------

    @Test
    fun `wall-clock timeout persists TIMED_OUT - not the user-input WAITING`() = runBlocking {
        val provider = GatedStepProvider(tokensPerStep = 20)
        provider.allowance = Int.MAX_VALUE
        register(provider)

        val task = unsatisfiableTask("task-timeout", timeoutMs = 60_000L)
        // Jump the clock beyond the 60s floor AFTER launch: the first step
        // runs on real time, then the boundary check trips on the next step.
        orchestrator.nowProvider = { System.currentTimeMillis() + 120_000L }

        val events = orchestrator.executeTaskStream(testAgent, task).toList()

        assertTrue(
            "GAP-11: a Degraded TASK_TIMEOUT event must be emitted",
            events.any { it is ExecutionEvent.Degraded }
        )
        val timedOutWrite = taskDao.statusWrites.lastOrNull { it.first == "TIMED_OUT" }
        assertNotNull(
            "GAP-11: the row must be persisted with the DISTINCT TIMED_OUT state " +
                    "(was the indistinguishable WAITING); writes: ${taskDao.statusWrites}",
            timedOutWrite
        )
        assertEquals(
            "GAP-11: timed-out row must be marked degraded",
            true,
            taskDao.rows[task.id.value]?.isDegraded
        )
    }

    @Test
    fun `timeout budget is NOT multiplied by the resume attempt counter`() = runBlocking {
        val provider = GatedStepProvider(tokensPerStep = 20)
        provider.allowance = Int.MAX_VALUE
        register(provider)

        val task = unsatisfiableTask("task-timeout-attempt", timeoutMs = 60_000L)
        // A RESUMED context (attempt = 5). The old code multiplied the cap by
        // the attempt (60s * 5 = 300s) — the task would run unbounded instead
        // of timing out; the fix keeps the task's OWN 60s cap.
        val resumedContext = CanonicalExecutionContext(
            executionId = "exec_attempt5",
            taskId = task.id,
            workspaceId = "ws_durable",
            agentId = testAgent.identity.id,
            agentRole = testAgent.identity.role,
            attempt = 5
        )
        orchestrator.nowProvider = { System.currentTimeMillis() + 90_000L } // < 300s, > 60s

        orchestrator.executeTaskStream(
            testAgent, task,
            restoredContext = resumedContext
        ).toList()

        assertNotNull(
            "GAP-11: attempt=5 must NOT inflate the 60s cap — the timeout must still trip",
            taskDao.statusWrites.firstOrNull { it.first == "TIMED_OUT" }
        )
    }

    // ------------------------------------------------------------------
    // GAP-11 — resume is guarded to resumable states
    // ------------------------------------------------------------------

    private fun seedTaskRow(id: String, state: String, checkpoint: String? = null, executionContext: String? = null) {
        taskDao.seed(
            TaskEntity(
                id = id,
                assignedAgentId = testAgent.identity.id.value,
                rawPrompt = "keep working",
                goal = "keep working",
                lifecycleState = state,
                autonomyPolicy = "SUPERVISED",
                resultSummary = null,
                totalTokensConsumed = 40,
                durationMs = 1000L,
                isDegraded = false,
                degradedReason = null,
                errorMessage = null,
                createdAtEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis(),
                currentStepIndex = 1,
                tokenLimit = 30000,
                maxRetries = 4,
                allowDegradedExecution = true,
                requireHumanConsentForSensitiveTools = false,
                timeoutMs = 600_000L,
                minOutputLengthChars = 1_000_000,
                verificationStrategy = "STRICT",
                checkpointJson = checkpoint,
                executionContextJson = executionContext
            )
        )
    }

    @Test
    fun `resume rejects a COMPLETED task with an honest error`() = runBlocking {
        seedTaskRow("task-completed", "COMPLETED")
        val events = orchestrator.resumeTask("task-completed").toList()
        val error = events.firstOrNull { it is ExecutionEvent.Error } as? ExecutionEvent.Error
        assertNotNull("a resume of COMPLETED must fail honestly", error)
        assertEquals("TASK_NOT_RESUMABLE", error!!.failureCode)
    }

    @Test
    fun `resume rejects a CANCELLED task - deliberate stops stay stopped`() = runBlocking {
        seedTaskRow("task-cancelled", "CANCELLED")
        val events = orchestrator.resumeTask("task-cancelled").toList()
        val error = events.firstOrNull { it is ExecutionEvent.Error } as? ExecutionEvent.Error
        assertNotNull(error)
        assertEquals("TASK_NOT_RESUMABLE", error!!.failureCode)
    }

    @Test
    fun `a TIMED_OUT task is resumable and continues from its durable checkpoint`() = runBlocking {
        val provider = GatedStepProvider(tokensPerStep = 20)
        provider.allowance = Int.MAX_VALUE
        register(provider)

        // Checkpoint: 2 steps already done, 40 tokens consumed. The resume
        // must CONTINUE from there (loop starts at step 2) — the checkpoint
        // (not step 0) is the restart point.
        val checkpoint = AgentOrchestrator.TaskCheckpoint(
            stepIndex = 2,
            tokensConsumed = 40,
            accumulatedOutput = "prior work"
        ).toJson()
        val context = ExecutionContextCodec.encode(
            CanonicalExecutionContext(
                executionId = "exec_resumable",
                taskId = TaskId("task-timedout"),
                workspaceId = "ws_durable",
                agentId = testAgent.identity.id,
                agentRole = testAgent.identity.role,
                attempt = 1
            )
        )
        seedTaskRow("task-timedout", "TIMED_OUT", checkpoint, context)

        val events = orchestrator.resumeTask("task-timedout").toList()

        // No TASK_NOT_RESUMABLE rejection; the execution STARTS.
        assertTrue(
            "GAP-11: a TIMED_OUT task must be resumable (no rejection), errors: " +
                    events.filterIsInstance<ExecutionEvent.Error>().map { it.failureCode },
            events.none { it is ExecutionEvent.Error && it.failureCode == "TASK_NOT_RESUMABLE" }
        )
        assertTrue(
            "GAP-11: the resumed execution must emit Started",
            events.any { it is ExecutionEvent.Started }
        )
    }
}
