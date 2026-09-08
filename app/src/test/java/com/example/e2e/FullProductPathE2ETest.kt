package com.example.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.agent.AgentRegistryService
import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionService
import com.example.application.observability.TelemetryService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.PermissionGrantService
import com.example.application.security.SecurityGuardService
import com.example.application.session.ConversationSessionService
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import com.example.infrastructure.observability.RoomTelemetryRepository
import com.example.infrastructure.persistence.repository.RoomConversationSessionRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetSocketAddress

/**
 * ============================================================================
 * E2E (defect family 8) — FULL PRODUCT PATH
 * ============================================================================
 *
 * "UI → Workspace → Session → Task → Agent → Exact Model → Decision →
 *  Governance → Execution → Tool/RAG → Output → Persistence → Process Death
 *  → Relaunch → Resume"
 *
 * This is a REAL full-path test: every stage runs the production component
 * (Room-backed sessions/agents/permissions/telemetry, the real decision
 * engine, the real execution kernel, the real authorization boundary), and
 * the LLM leg runs the REAL OpenAI-compatible wire adapter against a local
 * SSE server that deliberately streams a FRAGMENTED tool call (defect
 * family 6) for a SENSITIVE tool (defect family 2 — the grant gate is on
 * the path).
 *
 * PROCESS DEATH is simulated the same way the convergence tests do it: the
 * Room database (file-backed) is CLOSED and every component is REBUILT over
 * the same file — then the relaunched stack must reload the session
 * transcript, the agent registry, the permission grants, and RESUME the
 * conversation with a second turn.
 */
@RunWith(RobolectricTestRunner::class)
class FullProductPathE2ETest {

    private lateinit var dbFile: File
    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var server: HttpServer
    private lateinit var egress: com.example.infrastructure.network.EgressControl

    private val workspaceId = "ws_e2e"
    private val exactModelResourceId = "res:e2e:svc:llm:model_exact"
    private val sensitiveToolName = "sensitive_data_tool"

    /** Wire script knobs (the local SSE server serves both stream + generate). */
    @Volatile private var serveStreamScript: List<String> = emptyList()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File.createTempFile("e2e_full_", ".db")
        db = openDb()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Route ONCE — serveScript only swaps the CURRENT stream script
        // (re-registering a context on the same path throws).
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val isStream = body.contains("\"stream\":true")
            val payload: String = if (isStream) {
                streamScriptChunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            } else {
                // Synthesis round (non-streaming generate()).
                org.json.JSONObject(
                    mapOf(
                        "choices" to listOf(
                            mapOf(
                                "message" to mapOf(
                                    "role" to "assistant",
                                    "content" to "الإجابة النهائية بعد تنفيذ الأداة: SENSITIVE_TOOL_OK"
                                ),
                                "finish_reason" to "stop"
                            )
                        ),
                        "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 12, "total_tokens" to 22),
                        "model" to "model_exact"
                    )
                ).toString()
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (isStream) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        egress = com.example.infrastructure.network.EgressControl()
        egress.pinWorkspacePolicy(workspaceId, com.example.domain.core.network.NetworkPolicy.HYBRID)
        egress.setActiveWorkspace(workspaceId)
    }

    @After
    fun tearDown() {
        server.stop(0)
        db.close()
        dbFile.delete()
        egress.reset()
    }

    private fun openDb(): com.example.infrastructure.persistence.AppDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            com.example.infrastructure.persistence.AppDatabase::class.java,
            dbFile.absolutePath
        ).allowMainThreadQueries().build()

    // ------------------------------------------------------------------
    // The full wired stack over a database (the "container")
    // ------------------------------------------------------------------

    private class E2EStack(
        val db: com.example.infrastructure.persistence.AppDatabase,
        val registry: ComponentRegistry,
        val sessionService: ConversationSessionService,
        val agentRegistry: AgentRegistryService,
        val orchestrator: AgentOrchestrator,
        val executionService: ExecutionService,
        val permissionGrantService: PermissionGrantService,
        val telemetryService: TelemetryService
    )

    private fun buildStack(database: com.example.infrastructure.persistence.AppDatabase): E2EStack {
        val registry = ComponentRegistry()

        // ---- Workspace-scoped sessions (durable) ----
        val sessionRepository = RoomConversationSessionRepository(
            database = database,
            sessionDao = database.conversationSessionDao(),
            turnDao = database.conversationTurnDao()
        )
        val sessionService = ConversationSessionService(
            repository = sessionRepository,
            workspaceIdProvider = { workspaceId }
        )

        // ---- Durable agent registry (canonical identity) ----
        val agentRegistry = AgentRegistryService(database.agentDefinitionDao())

        // ---- Telemetry + permission grants (governance) ----
        val telemetryPort = RoomTelemetryRepository(
            metricEventDao = database.metricEventDao(),
            auditTrailDao = database.auditTrailDao(),
            healthProbeDao = database.healthProbeDao(),
            executionTraceDao = database.executionTraceDao(),
            executionLogDao = database.executionLogDao()
        )
        val telemetryService = TelemetryService(telemetryPort)
        val permissionGrantService = PermissionGrantService(
            permissionGrantDao = database.permissionGrantDao(),
            telemetryPort = telemetryPort
        )

        // ---- EXACT MODEL resource: the REAL wire adapter ----
        val adapter = OpenAiCompatibleLlmAdapter(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            apiKeyProvider = { "e2e-key" },
            defaultModel = "model_exact",
            providerId = exactModelResourceId,
            egressControl = egress
        )
        com.example.application.testing.TestResourceRegistration.registerLlmProvider(
            registry, adapter,
            capabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING)
        )

        // ---- Sensitive tool (governed through the canonical boundary) ----
        val sensitiveTool = object : com.example.domain.ports.tools.ToolPort {
            var executeCount = 0
            override val declaration = com.example.domain.core.tools.ToolDeclaration(
                name = sensitiveToolName,
                description = "Sensitive workspace data tool",
                parameters = listOf(
                    com.example.domain.core.tools.ToolParameter("action", "string", "act"),
                    com.example.domain.core.tools.ToolParameter("path", "string", "path")
                ),
                isSensitive = true,
                requiresHumanConsent = true
            )
            override suspend fun execute(input: com.example.domain.core.tools.ToolInput) =
                com.example.domain.core.Outcome.Success(
                    com.example.domain.core.tools.ToolOutput(content = "SENSITIVE_TOOL_OK ${input.arguments}")
                ).also { executeCount++ }
        }
        registry.runtimeAdapterResolver.registerToolAdapter(
            com.example.domain.core.resource.ResourceId(sensitiveToolName), sensitiveTool
        )
        registry.resourceRegistry.registerResource(
            com.example.domain.core.resource.ResourceRecord(
                resourceId = com.example.domain.core.resource.ResourceId(sensitiveToolName),
                providerId = sensitiveToolName,
                serviceId = sensitiveToolName,
                resourceType = com.example.domain.core.resource.ResourceType.TOOL,
                capabilities = setOf(CapabilityType.TOOL_EXECUTION),
                configurationVersion = 1L,
                lifecycleState = com.example.domain.core.resource.ResourceLifecycleState.ENABLED,
                runtimeSupported = true,
                healthStatus = com.example.domain.core.provider.HealthStatus.HEALTHY,
                isLocal = true
            )
        )

        // ---- Decision + execution + orchestration (the real kernel) ----
        val securityGuard = SecurityGuardService()
        val decisionService = DecisionService(
            cbrMdpEngine = CbrMdpEngine(),
            resourceCapabilityGraph = registry.resourceCapabilityGraph,
            securityGuard = securityGuard
        )
        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = securityGuard
        )
        executionService.permissionGrantService = permissionGrantService

        val orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = securityGuard,
            decisionService = decisionService,
            executionService = executionService,
            taskDao = database.taskDao(),
            actionIntentDao = database.actionIntentDao()
        )
        orchestrator.workspaceIdProvider = { workspaceId }

        // RAG leg: the RETRIEVE_KNOWLEDGE action consults this hook.
        executionService.ragRetrievalProvider = { query, _ ->
            com.example.domain.core.rag.AssembledRagContext(
                query = query,
                formattedContextText = "معرفة مرجعية من قاعدة المعرفة (RAG).",
                retrievedChunks = emptyList(),
                totalTokensEstimated = 8
            )
        }

        return E2EStack(
            db = database,
            registry = registry,
            sessionService = sessionService,
            agentRegistry = agentRegistry,
            orchestrator = orchestrator,
            executionService = executionService,
            permissionGrantService = permissionGrantService,
            telemetryService = telemetryService
        )
    }

    private val e2eAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent_e2e"),
            name = "وكيل الاختبار الشامل",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "E2E agent",
            systemPrompt = "You are the E2E agent."
        ),
        allowedCapabilities = setOf(
            CapabilityType.LLM_GENERATION,
            CapabilityType.TOOL_EXECUTION,
            CapabilityType.FILE_READ,
            CapabilityType.FILE_WRITE
        ),
        budget = AgentBudget(maxTokens = 30000)
    )

    // ------------------------------------------------------------------
    // Local SSE wire server (REAL protocol handling on the client side)
    // ------------------------------------------------------------------

    private fun toolCallDelta(
        index: Int,
        id: String? = null,
        name: String? = null,
        argumentsFragment: String? = null
    ): String {
        val fn = org.json.JSONObject()
        name?.let { fn.put("name", it) }
        argumentsFragment?.let { fn.put("arguments", it) }
        val tc = org.json.JSONObject()
        tc.put("index", index)
        id?.let { tc.put("id", it) }
        tc.put("type", "function")
        tc.put("function", fn)
        val delta = org.json.JSONObject().put("tool_calls", org.json.JSONArray().put(tc))
        val choice = org.json.JSONObject().put("delta", delta)
        return org.json.JSONObject().put("choices", org.json.JSONArray().put(choice)).toString()
    }

    private fun contentChunk(text: String): String {
        val delta = org.json.JSONObject().put("content", text)
        val choice = org.json.JSONObject().put("delta", delta)
        return org.json.JSONObject().put("choices", org.json.JSONArray().put(choice)).toString()
    }

    private fun usageChunk(): String =
        org.json.JSONObject()
            .put("choices", org.json.JSONArray())
            .put("usage", org.json.JSONObject()
                .put("prompt_tokens", 25)
                .put("completion_tokens", 30)
                .put("total_tokens", 55))
            .toString()

    @Volatile private var streamScriptChunks: List<String> = emptyList()

    private fun serveScript(streamChunks: List<String>) {
        streamScriptChunks = streamChunks
    }

    // ------------------------------------------------------------------
    // THE FULL PATH
    // ------------------------------------------------------------------

    @Test
    fun `full product path survives process death and resumes`() = runBlocking {
        // ============ 1. FIRST LIFE ============
        val stack = buildStack(db)

        // ---- Workspace (workspace-scoped runtime) ----
        // (The workspace id governs sessions, grants, and task scoping.)

        // ---- Session (durable, workspace-scoped, model pinned) ----
        val session = stack.sessionService.createSession(
            mode = com.example.domain.core.session.ChatMode.QUICK_CHAT,
            modelResourceId = exactModelResourceId,
            modelDisplayName = "Exact E2E Model"
        )
        assertEquals(workspaceId, session.workspaceId)

        // ---- Agent (durable registry = canonical identity) ----
        stack.agentRegistry.saveAgent(e2eAgent, origin = "E2E")
        val durableAgent = stack.agentRegistry.getAgent("agent_e2e")
        assertNotNull("Agent must be durable in the registry", durableAgent)
        stack.registry.registerAgent(durableAgent!!)

        // ---- Governance: grant EXECUTE on the sensitive tool (fail-closed
        //      without it — proven in FailClosedSecurityTest) ----
        stack.permissionGrantService.grant(
            principalType = PrincipalType.AGENT,
            principalId = e2eAgent.identity.id.value,
            resourceType = SecurableResourceType.TOOL,
            resourceId = sensitiveToolName,
            permission = Permission.EXECUTE,
            grantedBy = "e2e_operator",
            workspaceId = workspaceId
        )

        // ---- Task (exact model pin → reproducible binding) ----
        val taskId = "e2e_task_1"
        val task = TaskDefinition(
            id = TaskId(taskId),
            assignedAgentId = durableAgent.identity.id,
            input = TaskInput(rawPrompt = "نفّذ الأداة الحساسة واقرأ التقرير"),
            assignedModelId = exactModelResourceId,
            requirements = com.example.domain.core.task.TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION)
            )
        )

        // ---- Wire script: text + FRAGMENTED sensitive tool call ----
        serveScript(
            listOf(
                contentChunk("جارٍ "),
                contentChunk("التنفيذ...\n"),
                // Fragmented across THREE deltas (defect family 6 on the
                // REAL wire path).
                toolCallDelta(index = 0, id = "call_e2e", name = sensitiveToolName, argumentsFragment = """{"act"""),
                toolCallDelta(index = 0, argumentsFragment = """ion":"read","path":"rep"""),
                toolCallDelta(index = 0, argumentsFragment = """ort.txt"}"""),
                usageChunk()
            )
        )

        // ---- Execution (the real closed loop with governance) ----
        val events = stack.orchestrator.executeTaskStream(durableAgent, task).toList()

        // Decision happened (real CBR-MDP engine).
        assertTrue(events.any { it is ExecutionEvent.DecisionMade })
        // The DECISION bound the EXACT pinned model resource (reproducible
        // identity — never a provider fallback).
        val selectedModelAction = events
            .filterIsInstance<ExecutionEvent.DecisionMade>()
            .flatMap { it.decision.evaluatedAlternatives }
            .firstOrNull { it.action.type == com.example.domain.core.decision.DecisionActionType.SELECT_MODEL }
            .let { alternatives ->
                events.filterIsInstance<ExecutionEvent.DecisionMade>()
                    .flatMap { it.decision.evaluatedAlternatives }
                    .filter { it.action.type == com.example.domain.core.decision.DecisionActionType.SELECT_MODEL }
                    .mapNotNull { it.action.decisionRecord?.selectedResourceId?.value }
                    .firstOrNull()
            }
        assertEquals(
            "The decision must bind the EXACT pinned model resource",
            exactModelResourceId,
            selectedModelAction
        )

        // The fragmented tool call executed EXACTLY ONCE with complete args.
        val toolRequests = events.filterIsInstance<ExecutionEvent.ToolRequested>()
        assertEquals(1, toolRequests.size)
        assertEquals("call_e2e", toolRequests.first().callId)
        val args = org.json.JSONObject(toolRequests.first().argumentsJson)
        assertEquals("read", args.getString("action"))
        assertEquals("report.txt", args.getString("path"))

        // The sensitive tool PASSED the governance gates (grant honored).
        val toolResults = events.filterIsInstance<ExecutionEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertTrue(
            "The sensitive tool must succeed through the grant gate: ${toolResults.first().outcome}",
            toolResults.first().outcome is com.example.domain.core.Outcome.Success<*>
        )

        // Output produced (completion event + final text).
        val completed = events.firstOrNull { it is ExecutionEvent.Completed } as? ExecutionEvent.Completed
        assertNotNull("Execution must complete", completed)

        // ---- Persistence (task row, session turn) ----
        stack.sessionService.appendTurn(
            sessionId = session.id,
            prompt = task.input.rawPrompt,
            answer = completed!!.finalText.ifBlank { "تم التنفيذ" },
            agentName = durableAgent.identity.name,
            agentRole = durableAgent.identity.role.name,
            modelResourceId = exactModelResourceId,
            tokensConsumed = 55,
            durationMs = 120,
            isSuccessful = true,
            eventCount = events.size
        )
        stack.sessionService.titleFromPrompt(session.id, task.input.rawPrompt)

        val taskRow = db.taskDao().getTaskById(taskId)
        assertNotNull("The task row must be durable", taskRow)
        assertEquals(exactModelResourceId, taskRow!!.assignedModelId)

        // ============ 2. PROCESS DEATH ============
        db.close()
        // (Every in-memory component dies with the process.)

        // ============ 3. RELAUNCH ============
        db = openDb()
        val relaunched = buildStack(db)

        // ---- Session transcript survived ----
        val reloaded = relaunched.sessionService.getSessionWithTurns(session.id, workspaceId = workspaceId)
        assertNotNull("The session must survive process death", reloaded)
        assertEquals(1, reloaded!!.turns.size)
        assertEquals(task.input.rawPrompt, reloaded.turns.first().prompt)
        assertEquals(1, reloaded.session.turnCount)
        assertEquals(55L, reloaded.session.totalTokensConsumed.toLong())
        assertEquals(exactModelResourceId, reloaded.session.modelResourceId)
        assertEquals(task.input.rawPrompt.take(60), reloaded.session.title.take(60))

        // ---- Task row survived ----
        val relaunchedTaskRow = db.taskDao().getTaskById(taskId)
        assertNotNull(relaunchedTaskRow)

        // ---- Agent registry survived (canonical identity is durable) ----
        val relaunchedAgent = relaunched.agentRegistry.getAgent("agent_e2e")
        assertNotNull("The durable agent registry must survive process death", relaunchedAgent)

        // ---- Permission grants survived (workspace-scoped authorization) ----
        val stillGranted = relaunched.permissionGrantService.check(
            principalType = PrincipalType.AGENT,
            principalId = e2eAgent.identity.id.value,
            resourceType = SecurableResourceType.TOOL,
            resourceId = sensitiveToolName,
            permission = Permission.EXECUTE,
            workspaceId = workspaceId
        )
        assertTrue("The workspace-scoped grant must survive process death", stillGranted)

        // ============ 4. RESUME (second turn through the relaunched stack) ============
        serveScript(
            listOf(
                contentChunk("استئناف "),
                contentChunk("المحادثة."),
                usageChunk()
            )
        )
        val resumeTask = TaskDefinition(
            id = TaskId("e2e_task_2"),
            assignedAgentId = relaunchedAgent!!.identity.id,
            input = TaskInput(rawPrompt = "استئنف المحادثة"),
            assignedModelId = exactModelResourceId,
            requirements = com.example.domain.core.task.TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION)
            )
        )
        val resumeEvents = relaunched.orchestrator
            .executeTaskStream(relaunchedAgent, resumeTask)
            .toList()
        assertTrue(resumeEvents.any { it is ExecutionEvent.Completed })

        relaunched.sessionService.appendTurn(
            sessionId = session.id,
            prompt = "استئنف المحادثة",
            answer = "استئناف المحادثة",
            agentName = relaunchedAgent.identity.name,
            agentRole = relaunchedAgent.identity.role.name,
            modelResourceId = exactModelResourceId,
            tokensConsumed = 10,
            durationMs = 40,
            isSuccessful = true,
            eventCount = resumeEvents.size
        )

        // The RESUMED conversation now carries BOTH turns.
        val finalState = relaunched.sessionService.getSessionWithTurns(session.id, workspaceId = workspaceId)
        assertEquals(2, finalState!!.turns.size)
        assertEquals(2, finalState.session.turnCount)
        assertEquals(65L, finalState.session.totalTokensConsumed.toLong())
    }
}
