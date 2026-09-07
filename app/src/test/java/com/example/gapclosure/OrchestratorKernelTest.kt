package com.example.gapclosure

import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.capability.CapabilityType
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * OrchestratorKernelTest — gap-closure P0-02 / P0-03 / P1-03 / P1-04
 * ============================================================================
 *
 * Proves the Canonical Execution Kernel contracts:
 *  - a configured workspace provider that yields NO workspace fails the
 *    execution CLOSED (WORKSPACE_CONTEXT_REQUIRED) — never a "default" scope;
 *  - the executionId is STABLE across durable resume (attempt increments);
 *  - a resume that cannot find the ORIGINAL agent fails honestly
 *    (AGENT_UNAVAILABLE) — no silent migration to a different agent.
 */
class OrchestratorKernelTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var taskDao: FakeTaskDao

    private val testMetadata = SafeProviderMetadata(
        id = "mock_provider",
        name = "Mock",
        providerType = "MOCK",
        defaultModel = "mock-v1",
        isConfigured = true,
        isOnline = true,
        isLocal = false,
        supportedCapabilities = listOf("mock-v1")
    )

    private val mockLlm = object : com.example.domain.ports.llm.LlmProviderPort {
        override val providerId: String = "mock_provider"
        override val metadata: SafeProviderMetadata = testMetadata

        override suspend fun generate(request: LlmRequest): com.example.domain.core.Outcome<LlmResponse, LlmFailure> =
            com.example.domain.core.Outcome.Success(
                LlmResponse(
                    text = "تم تنفيذ المهمة بنجاح بمخرجات وافية ومفصلة.",
                    toolCalls = emptyList(),
                    usage = TokenUsage(10, 10),
                    finishReason = "STOP",
                    modelId = "mock-v1"
                )
            )

        override fun stream(request: LlmRequest, executionId: String) =
            kotlinx.coroutines.flow.emptyFlow<ExecutionEvent>()
    }

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("kernel_agent"),
            name = "Kernel Test Agent",
            role = AgentRole.CODER,
            description = "test",
            systemPrompt = "test"
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
        budget = AgentBudget(maxTokens = 30000)
    )

    /** In-memory TaskDao capturing persisted rows. */
    private class FakeTaskDao : TaskDao {
        val stored = linkedMapOf<String, TaskEntity>()
        override fun getAllTasksFlow(): Flow<List<TaskEntity>> = MutableStateFlow(stored.values.toList())
        override suspend fun getAllTasks(): List<TaskEntity> = stored.values.toList()
        override suspend fun getTaskById(id: String): TaskEntity? = stored[id]
        override suspend fun insertOrUpdateTask(task: TaskEntity) { stored[task.id] = task }
        override suspend fun updateTaskStatus(
            id: String, state: String, summary: String?, tokens: Int, duration: Long,
            isDegraded: Boolean, degradedReason: String?, errorMsg: String?, now: Long
        ) {
            stored[id]?.let { stored[id] = it.copy(lifecycleState = state, resultSummary = summary, totalTokensConsumed = tokens) }
        }
        override suspend fun updateCheckpoint(id: String, stepIndex: Int, checkpointJson: String, tokens: Int, now: Long) {
            stored[id]?.let { stored[id] = it.copy(currentStepIndex = stepIndex, checkpointJson = checkpointJson, totalTokensConsumed = tokens) }
        }
    }

    @Before
    fun setup() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        taskDao = FakeTaskDao()
        val cbrMdpEngine = com.example.domain.core.decision.CbrMdpEngine()
        val decisionService = com.example.application.decision.DecisionService(cbrMdpEngine, registry, securityGuard)
        orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = securityGuard,
            decisionService = decisionService,
            taskDao = taskDao
        )
        registry.registerAgent(testAgent)
        TestResourceRegistration.registerLlmProvider(registry, mockLlm)
    }

    private fun task(id: String) = com.example.domain.core.task.TaskDefinition(
        id = com.example.domain.core.task.TaskId(id),
        assignedAgentId = testAgent.identity.id,
        input = com.example.domain.core.task.TaskInput(rawPrompt = "نفّذ المهمة")
    )

    // ------------------------------------------------------------------
    // P0-03 — fail-closed workspace binding
    // ------------------------------------------------------------------

    @Test
    fun `missing workspace fails the execution CLOSED with WORKSPACE_CONTEXT_REQUIRED`() = runBlocking {
        // Provider is CONFIGURED but yields no workspace (bootstrap failed).
        orchestrator.workspaceIdProvider = { null }

        val events = orchestrator.executeTaskStream(agent = testAgent, task = task("t_ws_missing")).toList()

        val fatal = events.filterIsInstance<ExecutionEvent.Error>().firstOrNull { it.isFatal }
        assertNotNull("A fatal error must be emitted when the workspace cannot be bound", fatal)
        assertEquals("WORKSPACE_CONTEXT_REQUIRED", fatal!!.failureCode)
        assertEquals("FAILED", taskDao.stored["t_ws_missing"]?.lifecycleState)
    }

    @Test
    fun `pinned workspace stamps the task and the Started event`() = runBlocking {
        orchestrator.workspaceIdProvider = { "ws_pinned" }

        val events = orchestrator.executeTaskStream(agent = testAgent, task = task("t_ws_pinned")).toList()

        val started = events.filterIsInstance<ExecutionEvent.Started>().firstOrNull()
        assertNotNull(started)
        assertEquals("P0-02: Started carries the pinned workspace id", "ws_pinned", started!!.workspaceId)

        // The pinned workspace is part of the persisted canonical context.
        val persisted = taskDao.stored["t_ws_pinned"]
        assertNotNull(persisted)
        assertTrue(
            "Canonical context JSON must contain the pinned workspace",
            persisted!!.executionContextJson?.contains("ws_pinned") == true
        )
    }

    // ------------------------------------------------------------------
    // P1-03 — stable execution identity across resume
    // ------------------------------------------------------------------

    @Test
    fun `resume keeps the SAME executionId and increments the attempt`() = runBlocking {
        orchestrator.workspaceIdProvider = { "ws_resume" }

        // First run: capture the executionId from the canonical context.
        val firstEvents = orchestrator.executeTaskStream(agent = testAgent, task = task("t_resume")).toList()
        val firstStarted = firstEvents.filterIsInstance<ExecutionEvent.Started>().first()
        val firstContextJson = taskDao.stored["t_resume"]?.executionContextJson
        assertTrue(firstContextJson!!.contains("\"attempt\":1"))

        // Simulate process death: task left RUNNING with its context + checkpoint.
        val runningEntity = taskDao.stored["t_resume"]!!.copy(
            lifecycleState = "RUNNING",
            checkpointJson = null
        )
        taskDao.stored["t_resume"] = runningEntity

        // Resume: the SAME executionId must be reused (attempt = 2).
        val resumeEvents = orchestrator.resumeTask("t_resume").toList()
        val resumedStarted = resumeEvents.filterIsInstance<ExecutionEvent.Started>().firstOrNull()

        assertNotNull("Resume must start an execution", resumedStarted)
        assertEquals(
            "P1-03: executionId is STABLE across resume (no new execution identity)",
            firstStarted.executionId,
            resumedStarted!!.executionId
        )
        assertTrue(
            "Attempt counter must increment on resume",
            taskDao.stored["t_resume"]?.executionContextJson?.contains("\"attempt\":2") == true
        )
    }

    // ------------------------------------------------------------------
    // P1-04 — no silent agent migration on resume
    // ------------------------------------------------------------------

    @Test
    fun `resume refuses when the original agent is unavailable (no silent migration)`() = runBlocking {
        orchestrator.workspaceIdProvider = { "ws_agent" }

        // Run once with the registered agent, then UNREGISTER it (simulating
        // the original agent being removed before a resume).
        orchestrator.executeTaskStream(agent = testAgent, task = task("t_agent_gone")).toList()
        registry.registerAgent(testAgent) // ensure present during the run
        // Remove by overwriting the registry's entry with a DIFFERENT agent id:
        val ghostDao = FakeTaskDao().also { dao ->
            dao.stored["t_agent_gone"] = taskDao.stored["t_agent_gone"]!!
                .copy(assignedAgentId = "ghost_agent")
        }
        val ghostOrchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = securityGuard,
            decisionService = com.example.application.decision.DecisionService(
                com.example.domain.core.decision.CbrMdpEngine(), registry, securityGuard
            ),
            taskDao = ghostDao
        )

        val events = ghostOrchestrator.resumeTask("t_agent_gone").toList()
        val error = events.filterIsInstance<ExecutionEvent.Error>().firstOrNull()

        assertNotNull("Resume must fail honestly when the original agent is gone", error)
        assertEquals("AGENT_UNAVAILABLE", error!!.failureCode)
        assertTrue(error.message.contains("الترحيل الصامت"))
    }
}
