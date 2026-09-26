package com.example.gapclosure

import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * ImmutableInvocationScopeTest — CLOSURE PHASE P0 (spec §3)
 * ============================================================================
 *
 * The adversarial acceptance test for END-TO-END IMMUTABLE SCOPE PINNING:
 *
 *   1. Start in "workspace A / project A1 / session S".
 *   2. Accept the invocation (the acceptance-time capture).
 *   3. Stop the coroutine BEFORE actual execution begins.
 *   4. Switch to "workspace B / project B1".
 *   5. Resume the execution.
 *   6. EVERY operation must still target "A / A1 / S" — no layer may read
 *      the live active-workspace/active-project providers after the
 *      acceptance moment.
 *
 * Enforcement strategy:
 *  - The live providers are wired to THROW once "switched" — any post-
 *    acceptance live read fails the test loudly (no silent fallback can
 *    hide it).
 *  - The mock LLM CAPTURES the coroutine-context ExecutionScope at the
 *    moment of generation, proving the complete tuple
 *    (executionId/workspaceId/projectId/sessionId) reached the innermost
 *    execution layer without any live-provider consultation.
 */
class ImmutableInvocationScopeTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var taskDao: FakeTaskDao

    /** The acceptance-time scope: A / A1 / S. */
    private val workspaceA = "ws_alpha"
    private val projectA1 = 101L
    private val sessionS = "sess_origin"

    /** The post-switch live state: B / B1. */
    private val workspaceB = "ws_beta"
    private val projectB1 = 202L

    /** TRUE once the simulated switch has happened (post-acceptance). */
    @Volatile
    private var switched = false

    /**
     * The EXPLODING live providers: before the switch they answer honestly;
     * after it they THROW. Any execution layer that consults the live
     * provider AFTER the acceptance moment crashes the test — proving the
     * invariant by construction rather than by absence-of-evidence.
     */
    private val explodingWorkspaceProvider: () -> String? = {
        if (switched) throw IllegalStateException(
            "LIVE_WORKSPACE_READ_AFTER_ACCEPTANCE: an execution layer read the live workspace provider after invocation acceptance"
        )
        workspaceA
    }
    private val explodingProjectProvider: () -> Long? = {
        if (switched) throw IllegalStateException(
            "LIVE_PROJECT_READ_AFTER_ACCEPTANCE: an execution layer read the live project provider after invocation acceptance"
        )
        projectA1
    }

    /** Scopes captured by the innermost execution layer (the LLM call). */
    private val capturedScopes = mutableListOf<ExecutionScope>()

    private val scopeCapturingLlm = object : LlmProviderPort {
        override val providerId: String = "mock_provider"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "mock_provider",
            name = "Mock",
            providerType = "MOCK",
            defaultModel = "mock-v1",
            isConfigured = true,
            isOnline = true,
            isLocal = false,
            supportedCapabilities = listOf("mock-v1")
        )

        override suspend fun generate(request: LlmRequest): com.example.domain.core.Outcome<LlmResponse, LlmFailure> {
            // CLOSURE P0 probe: the INNERMOST execution layer reads its
            // coroutine-context scope — this is what every execution layer
            // must resolve instead of any live provider.
            capturedScopes += kotlin.coroutines.coroutineContext[ExecutionScope.Key]
                ?: throw IllegalStateException("LLM_GENERATION_WITHOUT_EXECUTION_SCOPE")
            return com.example.domain.core.Outcome.Success(
                LlmResponse(
                    text = "تم التنفيذ في النطاق المثبت وقت القبول.",
                    toolCalls = emptyList(),
                    usage = TokenUsage(10, 10),
                    finishReason = "STOP",
                    modelId = "mock-v1"
                )
            )
        }

        override fun stream(request: LlmRequest, executionId: String) =
            kotlinx.coroutines.flow.emptyFlow<ExecutionEvent>()
    }

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("scope_agent"),
            name = "Scope Test Agent",
            role = AgentRole.GENERAL_ASSISTANT,
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
        override fun getTasksForWorkspaceFlow(workspaceId: String): Flow<List<TaskEntity>> =
            MutableStateFlow(stored.values.filter { it.workspaceId == workspaceId })
        override suspend fun getTasksForWorkspace(workspaceId: String): List<TaskEntity> =
            stored.values.filter { it.workspaceId == workspaceId }
        override suspend fun getTaskById(id: String): TaskEntity? = stored[id]
        override suspend fun getTasksForWorkspaceAndProject(workspaceId: String, projectId: Long?): List<TaskEntity> =
            stored.values.filter { it.workspaceId == workspaceId && it.projectId == projectId }
        override suspend fun reassignProject(ids: List<String>, projectId: Long?) {}
        override suspend fun getTasksForProject(projectId: Long): List<TaskEntity> =
            stored.values.filter { it.projectId == projectId }
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
            override suspend fun deleteTasksForProject(projectId: Long) {}
        override suspend fun rebindTasksWorkspace(projectId: Long, targetWorkspaceId: String) {}
}

    @Before
    fun setup() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        taskDao = FakeTaskDao()
        val decisionService = com.example.application.decision.DecisionService(
            com.example.domain.core.decision.CbrMdpEngine(), registry, securityGuard
        )
        orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = securityGuard,
            decisionService = decisionService,
            taskDao = taskDao
        )
        // The LIVE providers — pre-acceptance they answer A/A1; post-switch
        // they THROW (see the exploding wrappers above).
        orchestrator.workspaceIdProvider = explodingWorkspaceProvider
        orchestrator.projectIdProvider = explodingProjectProvider
        registry.registerAgent(testAgent)
        TestResourceRegistration.registerLlmProvider(registry, scopeCapturingLlm)
    }

    private fun task(id: String) = TaskDefinition(
        id = TaskId(id),
        assignedAgentId = testAgent.identity.id,
        input = TaskInput(rawPrompt = "نفّذ المهمة في نطاقها المثبت")
    )

    // ------------------------------------------------------------------
    // THE adversarial acceptance test (spec §3):
    // accept in A/A1/S → switch to B/B1 → execute → everything stays A/A1/S
    // and NO live provider is consulted after acceptance.
    // ------------------------------------------------------------------

    @Test
    fun `acceptance-pinned tuple survives a post-acceptance workspace and project switch`() = runBlocking {
        // Step 3-4 of the adversarial script: the switch happens BEFORE the
        // kernel starts (the acceptance→kernel-start race window).
        switched = true

        // Step 5: resume/execute — carrying ONLY the acceptance-time capture.
        val events = orchestrator.executeTaskStream(
            agent = testAgent,
            task = task("t_adv_switch"),
            pinnedWorkspaceId = workspaceA,
            pinnedProjectId = projectA1,
            pinnedSessionId = sessionS
        ).toList()

        // Step 6a: the execution COMPLETED — no exploding-provider crash, no
        // WORKSPACE_CONTEXT_REQUIRED, no silent fallback.
        val completed = events.filterIsInstance<ExecutionEvent.Completed>().firstOrNull()
        assertNotNull(
            "The execution must complete against the pinned scope even after the live switch",
            completed
        )

        // Step 6b: the INNERMOST execution layer saw the COMPLETE pinned
        // tuple — A/A1/S with a real executionId — via the coroutine scope.
        assertTrue("The LLM must have been invoked inside the execution scope", capturedScopes.isNotEmpty())
        capturedScopes.forEach { scope ->
            assertEquals("workspace stays A after the switch", workspaceA, scope.workspaceId)
            assertEquals("project stays A1 after the switch", projectA1, scope.projectId)
            assertEquals("session stays S after the switch", sessionS, scope.sessionId)
            assertTrue("executionId is a real kernel identity", scope.executionId.startsWith("exec_"))
        }

        // Step 6c: the DURABLE canonical context pins the same tuple.
        val persisted = taskDao.stored["t_adv_switch"]
        assertNotNull(persisted)
        val contextJson = persisted!!.executionContextJson
        assertTrue(contextJson!!.contains("\"workspaceId\":\"$workspaceA\""))
        assertTrue(contextJson.contains("\"projectId\":$projectA1"))
        assertTrue(contextJson.contains("\"sessionId\":\"$sessionS\""))
        assertTrue(!contextJson.contains(workspaceB))
        assertTrue(!contextJson.contains(projectB1.toString()))
        assertEquals("The task row is attributed to workspace A", workspaceA, persisted.workspaceId)
    }

    // ------------------------------------------------------------------
    // The session rides the canonical context and SURVIVES resume.
    // ------------------------------------------------------------------

    @Test
    fun `pinned session rides the canonical context and survives a durable resume`() = runBlocking {
        val firstEvents = orchestrator.executeTaskStream(
            agent = testAgent,
            task = task("t_resume_scope"),
            pinnedWorkspaceId = workspaceA,
            pinnedProjectId = projectA1,
            pinnedSessionId = sessionS
        ).toList()

        assertTrue(firstEvents.filterIsInstance<ExecutionEvent.Completed>().isNotEmpty())
        val firstContextJson = taskDao.stored["t_resume_scope"]!!.executionContextJson!!
        assertTrue(firstContextJson.contains("\"sessionId\":\"$sessionS\""))

        // Durable resume: the RESTORED context (decoded from JSON) keeps
        // the same complete tuple and increments the attempt.
        val restored = com.example.application.execution.ExecutionContextCodec.decode(firstContextJson)
        assertNotNull(restored)
        assertEquals(sessionS, restored!!.sessionId)
        assertEquals(workspaceA, restored.workspaceId)
        assertEquals(projectA1, restored.projectId)

        switched = true // the resume itself must not consult live providers
        val resumedEvents = orchestrator.executeTaskStream(
            agent = testAgent,
            task = task("t_resume_scope"),
            restoredCheckpoint = null,
            restoredContext = restored, // the loop applies nextAttempt() itself
            pinnedWorkspaceId = workspaceA,
            pinnedProjectId = projectA1,
            pinnedSessionId = sessionS
        ).toList()
        assertTrue(resumedEvents.filterIsInstance<ExecutionEvent.Completed>().isNotEmpty())
        val resumedContextJson = taskDao.stored["t_resume_scope"]!!.executionContextJson!!
        assertTrue(resumedContextJson.contains("\"attempt\":2"))
        assertTrue(resumedContextJson.contains("\"sessionId\":\"$sessionS\""))
    }

    // ------------------------------------------------------------------
    // A NULL pinned project stays null — never the live project, never 1L.
    // ------------------------------------------------------------------

    @Test
    fun `null pinned project is preserved as null even when the live provider answers`() = runBlocking {
        switched = false // live provider WOULD answer A1 — it must not be asked
        val events = orchestrator.executeTaskStream(
            agent = testAgent,
            task = task("t_null_project"),
            pinnedWorkspaceId = workspaceA,
            pinnedProjectId = null,
            pinnedSessionId = sessionS
        ).toList()

        assertTrue(events.filterIsInstance<ExecutionEvent.Completed>().isNotEmpty())
        val contextJson = taskDao.stored["t_null_project"]!!.executionContextJson!!
        assertTrue(
            "An explicitly-null pinned project must NOT fall through to the live provider",
            !contextJson.contains("\"projectId\"")
        )
        capturedScopes.forEach { assertNull(it.projectId) }
    }

    // ------------------------------------------------------------------
    // Legacy seam (documented): callers that predate acceptance-time
    // pinning still resolve the live provider ONCE at kernel start. The
    // chat path never uses this seam — it always passes the pinned tuple.
    // ------------------------------------------------------------------

    @Test
    fun `legacy callers without pins still resolve the live provider once at kernel start`() = runBlocking {
        switched = false
        val events = orchestrator.executeTaskStream(agent = testAgent, task = task("t_legacy")).toList()
        assertTrue(events.filterIsInstance<ExecutionEvent.Completed>().isNotEmpty())
        val contextJson = taskDao.stored["t_legacy"]!!.executionContextJson!!
        assertTrue(contextJson.contains("\"workspaceId\":\"$workspaceA\""))
        assertTrue(contextJson.contains("\"projectId\":$projectA1"))
    }

    // ------------------------------------------------------------------
    // Codec round-trip: the sessionId is additive — legacy payloads decode
    // to null (the honest "not a chat execution" value).
    // ------------------------------------------------------------------

    @Test
    fun `codec round-trips the complete tuple and decodes legacy payloads to null session`() {
        val context = com.example.domain.core.execution.CanonicalExecutionContext(
            executionId = "exec_codec_1",
            taskId = TaskId("t_codec"),
            workspaceId = workspaceA,
            projectId = projectA1,
            sessionId = sessionS,
            agentId = testAgent.identity.id,
            agentRole = testAgent.identity.role
        )
        val decoded = com.example.application.execution.ExecutionContextCodec.decode(
            com.example.application.execution.ExecutionContextCodec.encode(context)
        )
        assertEquals(workspaceA, decoded!!.workspaceId)
        assertEquals(projectA1, decoded.projectId)
        assertEquals(sessionS, decoded.sessionId)

        // A legacy payload (pre-CLOSURE) has no sessionId field — null.
        val legacyJson = """{"executionId":"exec_legacy","taskId":"t_legacy","workspaceId":"ws_x",
            "agentId":"agent_general","agentRole":"GENERAL_ASSISTANT","attempt":1}"""
            .replace("\n", "")
        val legacyDecoded = com.example.application.execution.ExecutionContextCodec.decode(legacyJson)
        assertNull(legacyDecoded!!.sessionId)
    }
}
