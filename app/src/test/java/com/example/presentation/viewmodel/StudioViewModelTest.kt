package com.example.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.application.decision.DecisionService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.session.ConversationSessionService
import com.example.application.testing.TestResourceRegistration
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.workspace.Workspace
import com.example.presentation.state.ChatEntry
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * StudioViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the STUDIO conversation runtime ViewModel (ADR-6 slice 2)
 * ============================================================================
 *
 * The heaviest extraction of the ADR-6 track, verified against the REAL
 * governed execution kernel (no execution-path mocks):
 *
 *  - [ExecuteAgentTaskUseCase] → a REAL [AgentOrchestrator] (ComponentRegistry
 *    + SecurityGuardService + DecisionService) with a local mock LLM port
 *    registered through [TestResourceRegistration] — Started / ContentChunk /
 *    UsageBudgetUpdate / Completed / Error events come from the REAL pipeline,
 *    exactly as in production;
 *  - the durable-session contract runs against the REAL
 *    [ConversationSessionService] over an in-memory repository port;
 *  - the workspace/project binding (GAP-14) runs against the REAL
 *    [WorkspaceRuntimeService] (createWorkspace binds a sandbox project).
 *
 * Asserted feature contract (extracted verbatim from MainViewModel):
 * prompt gating, AGENT-mode honest error, Quick Chat resolution through the
 * canonical agent, session ensure/persist (survives process death),
 * first-turn titling, conversation-history replay, model pinning, the
 * session network policy + its signal-bus publication, transcript restore
 * (openSession), deletion notification, and honest cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var registry: ComponentRegistry
    private lateinit var executeAgentTaskUseCase: ExecuteAgentTaskUseCase
    private lateinit var repository: FakeConversationSessionRepositoryForVm
    private lateinit var sessionService: ConversationSessionService
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var activeWorkspace: Workspace
    private lateinit var viewModel: StudioViewModel

    private val signalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val receivedSignals = mutableListOf<StudioSignal>()
    private val signalCollectorScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    /** Requests captured from the (local) mock LLM port. */
    private val capturedRequests = mutableListOf<LlmRequest>()

    /** The execution ids the mock provider's stream() saw (Task 2 §13). */
    private val capturedExecutionIds = mutableListOf<String>()

    /** Scripted failure switch for the mock provider's stream. */
    @Volatile
    private var streamFails: Boolean = false

    /**
     * CHAT CAPABILITIES (Task 2 §13): when true, the mock stream persists a
     * REAL pending approval for THIS executionId in the shared gate, then
     * emits the honest HUMAN_APPROVAL_REQUIRED error — exactly the shape the
     * governed kernel produces for a consent-blocked tool.
     */
    @Volatile
    private var approvalScenario: Boolean = false

    /**
     * CHAT CAPABILITIES (Task 2 §11): when true, the mock stream emits a
     * REAL ActionCompleted carrying search citation chains before the
     * answer — the shape the search intelligence pipeline produces.
     */
    @Volatile
    private var citationsScenario: Boolean = false

    /** The REAL human-approval store + gate shared by the mock and the ViewModel. */
    private val approvalStore = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
    private val gate = com.example.application.governed.HumanApprovalGate(approvalStore)

    /** The in-memory permission-grant store for the standing-consent path. */
    private val grantDao = FakePermissionGrantDaoForVm()

    private val approvalToolName = "tool_test_consent"

    /**
     * CHAT WORKSPACE (Task 1): when non-null, the mock stream emits ONE
     * chunk then AWAITS the gate before the terminal events — a
     * controllable mid-stream window for the cancel test.
     */
    @Volatile
    private var streamGate: CompletableDeferred<Unit>? = null

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)

        // --- REAL governed execution kernel (mock only at the LLM port) ---
        registry = ComponentRegistry()
        val securityGuard = SecurityGuardService()
        val decisionService = DecisionService(
            com.example.domain.core.decision.CbrMdpEngine(),
            registry,
            securityGuard
        )
        val orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
        executeAgentTaskUseCase = ExecuteAgentTaskUseCase(orchestrator)

        // --- REAL workspace runtime: createWorkspace binds a sandbox project ---
        val workspaceDao = FakeWorkspaceDaoForVm()
        val projectDao = FakeProjectDaoForVm()
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = workspaceDao,
            projectDao = projectDao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100) // let the default bootstrap settle
        activeWorkspace = workspaceService.createWorkspace("مساحة الاستوديو", "اختبار")
        orchestrator.workspaceIdProvider = { activeWorkspace.id }

        // --- Local mock LLM resource (offline-safe: isLocal = true) ---
        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_studio_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_studio_provider",
                name = "Mock Studio",
                providerType = "MOCK",
                defaultModel = "studio-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("studio-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                if (streamFails) {
                    // The retry path (ExecutionService's generate() fallback /
                    // synthesis rounds) must fail TOO — a scripted failure is
                    // a failure on every provider surface, or the test would
                    // silently assert a transient-failure-then-retry scenario
                    // instead of the all-failed contract.
                    Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "فشل مزود الاختبار"))
                } else {
                    Outcome.Success(
                        LlmResponse(
                            text = "جواب فوري",
                            toolCalls = emptyList(),
                            usage = TokenUsage(10, 20),
                            finishReason = "STOP",
                            modelId = "studio-mock-v1"
                        )
                    )
                }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                capturedRequests += request
                capturedExecutionIds += executionId
                if (streamFails) {
                    emit(ExecutionEvent.Error(executionId, "MOCK_FAILURE", "فشل مزود الاختبار"))
                } else if (approvalScenario) {
                    // Persist the REAL pending request for THIS execution
                    // (the exact AdmissionControlService stage-9 shape).
                    gate.requestApproval(
                        executionId = executionId,
                        toolName = approvalToolName,
                        riskLevel = "HIGH",
                        prompt = "طلب موافقة اختباري",
                        justification = "سياسة الحوكمة تتطلب موافقة صريحة"
                    )
                    emit(
                        ExecutionEvent.Error(
                            executionId,
                            "HUMAN_APPROVAL_REQUIRED",
                            "تنفيذ '$approvalToolName' يتطلب موافقة بشرية (طلب موافقة: apr_x)."
                        )
                    )
                } else {
                    if (citationsScenario) {
                        emit(
                            ExecutionEvent.ActionCompleted(
                                executionId = executionId,
                                action = com.example.domain.core.decision.DecisionAction(
                                    type = com.example.domain.core.decision.DecisionActionType.SEARCH
                                ),
                                outputSummary = "تم البحث",
                                observation = com.example.domain.core.decision.EnvironmentObservation(
                                    action = com.example.domain.core.decision.DecisionAction(
                                        type = com.example.domain.core.decision.DecisionActionType.SEARCH
                                    ),
                                    isSuccess = true,
                                    actualLatencyMs = 10,
                                    outputData = mapOf(
                                        "searchCitations" to listOf(
                                            com.example.domain.core.search.intelligence.CitationChain(
                                                originalQuery = "ابحث واشرح",
                                                subQueryText = "ابحث واشرح",
                                                providerId = "mock_studio_provider",
                                                itemUrl = "https://example.com/citation",
                                                itemTitle = "مرجع الاختبار",
                                                confidenceScore = 0.9f
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    }
                    emit(ExecutionEvent.ContentChunk(executionId, "الجواب ", sequenceIndex = 0))
                    // Direct await: a cancel during the window propagates as
                    // CancellationException (the honest cancellation path).
                    streamGate?.let { gate -> gate.await() }
                    emit(ExecutionEvent.ContentChunk(executionId, "الكامل", sequenceIndex = 1))
                    emit(
                        ExecutionEvent.UsageBudgetUpdate(
                            executionId = executionId,
                            promptTokens = 10,
                            completionTokens = 20,
                            totalSessionTokens = 30,
                            remainingBudgetTokens = 29970
                        )
                    )
                    emit(ExecutionEvent.Completed(executionId, "الجواب الكامل", totalDurationMs = 50))
                }
            }
        }
        TestResourceRegistration.registerLlmProvider(
            registry,
            mockProvider,
            capabilities = setOf(
                com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                com.example.domain.core.capability.CapabilityType.STREAMING
            ),
            isLocal = true
        )

        // --- REAL session service over the in-memory repository ---
        repository = FakeConversationSessionRepositoryForVm()
        repository.activeWorkspaceId = activeWorkspace.id
        sessionService = ConversationSessionService(
            repository = repository,
            workspaceIdProvider = { repository.activeWorkspaceId }
        )

        // --- Signal bus subscriber (the MainViewModel stand-in) ---
        signalCollectorScope.launch {
            signalBus.collect { receivedSignals += it }
        }

        viewModel = StudioViewModel(
            executeAgentTaskUseCase = executeAgentTaskUseCase,
            componentRegistry = registry,
            workspaceRuntimeService = workspaceService,
            conversationSessionService = sessionService,
            networkMonitorProvider = null, // fail-closed offline — local mock resource still routes
            appContext = null,
            signalBus = signalBus,
            // CHAT CAPABILITIES (Task 2 §13): the REAL gate (the same
            // authority the governance surface resolves consent through) +
            // the REAL standing-grant service over the in-memory fake DAO.
            humanApprovalGate = gate,
            permissionGrantService = com.example.application.security.PermissionGrantService(
                permissionGrantDao = grantDao,
                telemetryPort = NoopTelemetryForVm
            )
        )
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        signalCollectorScope.cancel()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun awaitUntil(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean
    ) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(20)
        }
        assertTrue(
            "Timed out waiting for the condition (execution pipeline stalled?)",
            condition()
        )
    }

    /**
     * Waits until the execution pipeline fully SETTLES: first isExecuting
     * must clear (the provider Error resets it BEFORE the loop's terminal
     * event arrives), then the transcript must stop growing for a stable
     * window — a naive single wait would race the second terminal event.
     */
    private fun awaitExecutionSettled() {
        awaitUntil(20_000) { !viewModel.state.value.isExecuting }
        var lastSize = -1
        var lastChangeAt = System.currentTimeMillis()
        awaitUntil(20_000) {
            val size = viewModel.state.value.studioSession.size
            val now = System.currentTimeMillis()
            if (size != lastSize) {
                lastSize = size
                lastChangeAt = now
                false
            } else {
                now - lastChangeAt > 700
            }
        }
    }

    private fun runPrompt(prompt: String) {
        viewModel.updatePromptInput(prompt)
        viewModel.executePrompt(agent = null) // QUICK_CHAT default mode
    }

    // ------------------------------------------------------------------
    // Input surfaces
    // ------------------------------------------------------------------

    @Test
    fun `prompt input and chat mode are feature state`() {
        viewModel.updatePromptInput("مرحبا")
        assertEquals("مرحبا", viewModel.state.value.promptInput)

        viewModel.setChatMode(ChatMode.AGENT)
        assertEquals(ChatMode.AGENT, viewModel.state.value.chatMode)
    }

    @Test
    fun `setNetworkPolicy updates the feature state AND publishes the signal`() {
        receivedSignals.clear()

        viewModel.setNetworkPolicy(NetworkPolicy.OFFLINE)

        assertEquals(NetworkPolicy.OFFLINE, viewModel.state.value.networkPolicy)
        val policySignals = receivedSignals.filterIsInstance<StudioSignal.NetworkPolicyChanged>()
        assertEquals(1, policySignals.size)
        assertEquals(NetworkPolicy.OFFLINE, policySignals.single().policy)
    }

    // ------------------------------------------------------------------
    // Execution gating (honest, never a silent no-op)
    // ------------------------------------------------------------------

    @Test
    fun `an empty prompt is a no-op (no execution, no session, no turn)`() {
        runPrompt("   ")
        Thread.sleep(300) // brief settle window — nothing must have happened

        assertEquals(0, repository.upsertCount)
        assertTrue(viewModel.state.value.studioSession.isEmpty())
        assertNull(viewModel.state.value.activeSessionId)
    }

    @Test
    fun `AGENT mode without a selected agent fails honestly with the actionable message`() {
        viewModel.setChatMode(ChatMode.AGENT)

        viewModel.updatePromptInput("نفّذ المهمة")
        viewModel.executePrompt(agent = null)

        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.errorMessage!!.contains("وضع الوكيل"))
        assertTrue(viewModel.state.value.studioSession.isEmpty())
        assertFalse(viewModel.state.value.isExecuting)
    }

    // ------------------------------------------------------------------
    // Quick Chat execution: the full governed path
    // ------------------------------------------------------------------

    @Test
    fun `QUICK_CHAT executes through the canonical agent and appends a successful turn`() {
        runPrompt("ما الفرق بين الذاكرة والمعرفة؟")
        awaitExecutionSettled()

        val state = viewModel.state.value
        assertFalse(state.isExecuting)
        assertEquals(1, state.studioSession.size)
        val turn = state.studioSession.single()
        assertTrue(turn.isSuccessful)
        assertEquals("ما الفرق بين الذاكرة والمعرفة؟", turn.prompt)
        assertEquals("الجواب الكامل", turn.answer)

        // The token gauges reflect the REAL usage event from the pipeline.
        assertEquals(30, state.currentTokensConsumed)
        assertEquals(29970, state.remainingBudget)
    }

    @Test
    fun `a QUICK_CHAT execution ensures a workspace-scoped session and persists the turn`() {
        runPrompt("سؤال الاختبار")
        awaitExecutionSettled()

        val sessionId = viewModel.state.value.activeSessionId
        assertNotNull("The conversation must be bound to a durable session", sessionId)

        // GAP-14: the session is bound to the ACTIVE workspace's project.
        val session = runBlocking { repository.getSession(ConversationSessionId(sessionId!!)) }
        assertNotNull(session)
        assertEquals(activeWorkspace.id, session!!.workspaceId)
        assertEquals(
            "The session is project-scoped from creation (bound to the workspace's project)",
            activeWorkspace.activeProjectId.takeIf { it > 0L },
            session.projectId
        )

        // The turn is DURABLE (survives process death).
        assertEquals(1, repository.appendedTurns.size)
        assertEquals("سؤال الاختبار", repository.appendedTurns.single().prompt)
        assertTrue(repository.appendedTurns.single().isSuccessful)
    }

    @Test
    fun `first-turn titling renames the default title to the prompt`() {
        runPrompt("عنوان جلسة الاختبار الطويل جداً")
        awaitExecutionSettled()

        val sessionId = viewModel.state.value.activeSessionId!!
        val session = runBlocking { repository.getSession(ConversationSessionId(sessionId)) }!!
        assertTrue(
            "The default title must be replaced by the (bounded) prompt",
            session.title.startsWith("عنوان جلسة الاختبار")
        )
    }

    @Test
    fun `a second execution REUSES the active session (no duplicate rows)`() {
        runPrompt("الدورة الأولى")
        awaitExecutionSettled()
        val firstSessionId = viewModel.state.value.activeSessionId
        val sessionsAfterFirst = repositorySessions().size

        runPrompt("الدورة الثانية")
        awaitExecutionSettled()

        assertEquals(firstSessionId, viewModel.state.value.activeSessionId)
        assertEquals(
            "ensureActiveSession reuses the binding — one session, two durable turns",
            sessionsAfterFirst,
            repositorySessions().size
        )
        assertEquals(2, repository.appendedTurns.size)
    }

    @Test
    fun `the recent transcript is replayed as LLM conversation history`() {
        runPrompt("الدورة الأولى")
        awaitExecutionSettled()

        capturedRequests.clear()
        runPrompt("الدورة الثانية")
        awaitExecutionSettled()

        assertTrue(
            "The second execution must reach the LLM provider",
            capturedRequests.isNotEmpty()
        )
        val request = capturedRequests.last()
        val contents = request.messages.map { it.content }
        assertTrue(
            "Prior turns are replayed as USER history: ${contents.joinToString("|")}",
            contents.any { it.contains("الدورة الأولى") }
        )
        assertTrue(
            "The finished answer is replayed as ASSISTANT history",
            request.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("الجواب الكامل") }
        )
    }

    @Test
    fun `terminal events append turns and persist them honestly (failed provider)`() {
        streamFails = true
        receivedSignals.clear()
        runPrompt("سؤال سيفشل")
        awaitExecutionSettled()
        streamFails = false

        val state = viewModel.state.value
        assertFalse(state.isExecuting)
        // The provider error surfaces honestly as the feature error channel.
        assertNotNull(state.errorMessage)

        // REAL pipeline event topology (pre-slice behaviour, preserved):
        // the provider's Error passes THROUGH ExecutionService, then the
        // loop emits its own terminal event (Completed with the fallback
        // text "اكتملت معالجة المهمة."). One transcript turn per terminal
        // event — the failure turn FIRST (honest ordering), then the loop's
        // terminal turn. Documented as a pre-existing execution-contract
        // quirk in UI-REDESIGN-TRACK.md (NOT changed by this slice).
        val terminalEvents = receivedSignals.filterIsInstance<StudioSignal.ExecutionEvent>()
            .map { it.event }
            .filter { it is ExecutionEvent.Error || it is ExecutionEvent.Completed }
        assertEquals(terminalEvents.size, state.studioSession.size)
        assertEquals("Every transcript turn is durable (honest history)", terminalEvents.size, repository.appendedTurns.size)

        val firstEvent = terminalEvents.first()
        assertTrue("The provider failure arrives first", firstEvent is ExecutionEvent.Error)
        val firstTurn = state.studioSession.first()
        assertFalse("The provider-failure turn records the failure", firstTurn.isSuccessful)
        assertEquals(
            "The failed turn is persisted with the failure flag",
            false,
            repository.appendedTurns.first().isSuccessful
        )
    }

    @Test
    fun `execution events the activity feed needs are published on the signal bus`() {
        receivedSignals.clear()
        runPrompt("نشر الإشارات")
        awaitExecutionSettled()

        val events = receivedSignals.filterIsInstance<StudioSignal.ExecutionEvent>()
            .map { it.event }
        assertTrue(
            "Started must be published (the activity trace binding)",
            events.any { it is ExecutionEvent.Started }
        )
        assertTrue(
            "Completed must be published (the decision case-base mirror)",
            events.any { it is ExecutionEvent.Completed }
        )
    }

    // ------------------------------------------------------------------
    // Model pinning
    // ------------------------------------------------------------------

    @Test
    fun `selectModel pins the model onto the ACTIVE durable session`() {
        runPrompt("جلسة للتثبيت")
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!

        viewModel.selectModel(resourceId = "mock_studio_provider", displayName = "النموذج المختار")

        assertEquals("mock_studio_provider", viewModel.state.value.selectedModelResourceId)
        val session = runBlocking { repository.getSession(ConversationSessionId(sessionId)) }!!
        assertEquals("mock_studio_provider", session.modelResourceId)
        assertEquals("النموذج المختار", session.modelDisplayName)
    }

    @Test
    fun `selectModel without an active session updates the display only (no repository write)`() {
        val upsertsBefore = repository.upsertCount

        viewModel.selectModel(resourceId = "mock_studio_provider", displayName = "النموذج")

        assertEquals("mock_studio_provider", viewModel.state.value.selectedModelResourceId)
        assertEquals(upsertsBefore, repository.upsertCount)
    }

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `startNewSession binds a NEW project-scoped session and resets the transcript`() {
        runPrompt("دورة قديمة")
        awaitExecutionSettled()
        assertEquals(1, viewModel.state.value.studioSession.size)

        viewModel.startNewSession(agent = null)

        awaitUntil { viewModel.state.value.activeSessionId != null }
        val state = viewModel.state.value
        assertTrue(state.studioSession.isEmpty())
        assertTrue(state.executionLog.isEmpty())

        val session = runBlocking { repository.getSession(ConversationSessionId(state.activeSessionId!!)) }!!
        assertEquals(
            "GAP-14: new sessions are project-scoped from creation",
            activeWorkspace.activeProjectId.takeIf { it > 0L },
            session.projectId
        )
    }

    @Test
    fun `openSession restores the transcript, mode and model binding from durable storage`() {
        // Build a durable session with one turn through a REAL execution.
        runPrompt("دورة قابلة للاستئناف")
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!

        // Simulate process death: a FRESH view model over the same repository
        // (same real use-case, same signal bus).
        val fresh = StudioViewModel(
            executeAgentTaskUseCase = executeAgentTaskUseCase,
            componentRegistry = registry,
            workspaceRuntimeService = workspaceService,
            conversationSessionService = sessionService,
            signalBus = signalBus
        )
        assertNull(fresh.state.value.activeSessionId)

        fresh.openSession(sessionId)
        awaitUntil { fresh.state.value.studioSession.isNotEmpty() }

        val restored = fresh.state.value
        assertEquals(sessionId, restored.activeSessionId)
        assertEquals(1, restored.studioSession.size)
        assertEquals("دورة قابلة للاستئناف", restored.studioSession.single().prompt)
        assertEquals("الجواب الكامل", restored.studioSession.single().answer)
    }

    @Test
    fun `openSession for a MISSING session is an honest no-op (no state change, no error)`() {
        val before = viewModel.state.value

        viewModel.openSession("sess_does_not_exist")

        Thread.sleep(200)
        val after = viewModel.state.value
        assertNull(after.activeSessionId)
        assertTrue(after.studioSession.isEmpty())
        assertNull(after.errorMessage)
        assertEquals(before.errorMessage, after.errorMessage)
    }

    @Test
    fun `onSessionDeleted clears the binding and transcript ONLY for the active session`() {
        runPrompt("جلسة ستُحذف")
        awaitExecutionSettled()
        val activeId = viewModel.state.value.activeSessionId!!
        assertEquals(1, viewModel.state.value.studioSession.size)

        // A different session's deletion must NOT touch this conversation.
        viewModel.onSessionDeleted("sess_other")
        assertEquals(activeId, viewModel.state.value.activeSessionId)
        assertEquals(1, viewModel.state.value.studioSession.size)

        // Deleting the ACTIVE session clears the binding + transcript.
        viewModel.onSessionDeleted(activeId)
        assertNull(viewModel.state.value.activeSessionId)
        assertTrue(viewModel.state.value.studioSession.isEmpty())
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    fun `cancelExecution resets the execution state with the honest user banner`() {
        viewModel.cancelExecution()

        val state = viewModel.state.value
        assertFalse(state.isExecuting)
        assertEquals("تم إلغاء العملية بواسطة المستخدم.", state.diagnosticBanner)
    }

    // ------------------------------------------------------------------
    // CHAT WORKSPACE (Task 1) — the conversation-first contract
    // ------------------------------------------------------------------

    @Test
    fun `send clears the prompt input IMMEDIATELY and keeps it cleared during execution`() {
        val gate = CompletableDeferred<Unit>()
        streamGate = gate

        runPrompt("سؤال أثناء البث")
        // P0-B: the draft is cleared in the SAME update that launches — no
        // intermediate frame can keep the old text, and nothing writes it back.
        assertEquals("", viewModel.state.value.promptInput)

        awaitUntil { viewModel.state.value.streamText.isNotBlank() }
        // Still cleared while the execution streams (no race).
        assertEquals("", viewModel.state.value.promptInput)

        gate.complete(Unit)
        awaitExecutionSettled()
        assertEquals("", viewModel.state.value.promptInput)
        streamGate = null
    }

    @Test
    fun `the user message becomes visible IMMEDIATELY (before any result)`() {
        val gate = CompletableDeferred<Unit>()
        streamGate = gate

        runPrompt("سؤالي الفوري")
        // P0-C: the user entry is in the timeline at send time — no waiting
        // for the terminal event.
        val timelineNow = viewModel.state.value.timeline
        assertEquals(1, timelineNow.size)
        val userEntry = timelineNow.single() as com.example.presentation.state.ChatEntry.User
        assertEquals("سؤالي الفوري", userEntry.text)
        // The live execution block is attached to it.
        assertNotNull(viewModel.state.value.liveExecution)

        gate.complete(Unit)
        awaitExecutionSettled()
        streamGate = null
    }

    @Test
    fun `the final streamed result appears exactly ONCE (no duplicate live card)`() {
        runPrompt("سؤال عنصر واحد")
        awaitExecutionSettled()

        val state = viewModel.state.value
        // The assistant entry is the SINGLE display path of the result text.
        val matching = state.timeline.filterIsInstance<com.example.presentation.state.ChatEntry.Assistant>()
            .filter { it.text.contains("الجواب الكامل") }
        assertEquals(1, matching.size)
        // P0-D: the stream text and the live block are collapsed.
        assertEquals("", state.streamText)
        assertNull(state.liveExecution)
        // The timeline and the durable transcript stay 1:1.
        assertEquals(
            "Every durable turn is exactly one user entry + one assistant entry",
            state.studioSession.size * 2,
            state.timeline.size
        )
    }

    @Test
    fun `no stale stream text remains after a FAILED execution either`() {
        streamFails = true
        runPrompt("سؤال فاشل")
        awaitExecutionSettled()
        streamFails = false

        val state = viewModel.state.value
        assertEquals("", state.streamText)
        assertNull(state.liveExecution)
        assertTrue(
            state.timeline.filterIsInstance<com.example.presentation.state.ChatEntry.Assistant>()
                .isNotEmpty()
        )
    }

    @Test
    fun `the execution lifecycle reaches a terminal state and collapses`() {
        receivedSignals.clear()
        runPrompt("دورة كاملة")
        awaitExecutionSettled()

        val state = viewModel.state.value
        assertFalse(state.isExecuting)
        assertNull(state.liveExecution)
        assertEquals("", state.streamText)
    }

    @Test
    fun `regenerate re-executes the last prompt WITHOUT duplicating the user message`() {
        runPrompt("السؤال الأصلي")
        awaitExecutionSettled()
        val userMessagesAfterFirst = viewModel.state.value.timeline
            .filterIsInstance<com.example.presentation.state.ChatEntry.User>()

        // FUNCTIONAL CLOSURE (§6): the action targets the SPECIFIC assistant
        // entry being regenerated (by id), never "the last user message".
        val assistantEntryId = viewModel.state.value.timeline
            .filterIsInstance<com.example.presentation.state.ChatEntry.Assistant>()
            .last().id
        viewModel.regenerateFromAssistant(assistantEntryId = assistantEntryId, agent = null)
        awaitExecutionSettled()

        val state = viewModel.state.value
        val userMessages = state.timeline.filterIsInstance<com.example.presentation.state.ChatEntry.User>()
        assertEquals("The user message is NOT duplicated by regenerate", userMessagesAfterFirst.size, userMessages.size)
        assertEquals("السؤال الأصلي", userMessages.single().text)
        // TWO assistant results exist (the original + the regeneration).
        assertEquals(2, state.timeline.filterIsInstance<com.example.presentation.state.ChatEntry.Assistant>().size)
        assertEquals(2, state.studioSession.size)
    }

    @Test
    fun `editing a user message loads its text into the composer draft`() {
        runPrompt("رسالة قابلة للتحرير")
        awaitExecutionSettled()

        viewModel.editUserMessage("رسالة قابلة للتحرير")

        assertEquals("رسالة قابلة للتحرير", viewModel.state.value.promptInput)
    }

    @Test
    fun `cancel during streaming keeps the partial text in the CANCELLED lifecycle block`() {
        val gate = CompletableDeferred<Unit>()
        streamGate = gate

        runPrompt("سؤال طويل البث")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        viewModel.cancelExecution()

        val state = viewModel.state.value
        assertFalse(state.isExecuting)
        val live = state.liveExecution
        assertNotNull("The cancelled execution stays visible as its lifecycle block", live)
        assertEquals(com.example.presentation.state.ExecutionPhase.CANCELLED, live!!.phase)
        assertTrue("The partial stream text is retained inside the block", state.streamText.isNotBlank())
        // No durable turn was fabricated for the cancelled execution.
        assertEquals(0, repository.appendedTurns.size)

        // The window is released; the cancelled pipeline writes nothing more.
        gate.complete(Unit)
        Thread.sleep(600)
        assertEquals(com.example.presentation.state.ExecutionPhase.CANCELLED, viewModel.state.value.liveExecution?.phase)
        assertEquals(0, repository.appendedTurns.size)
        streamGate = null
    }

    @Test
    fun `a mode change is a SEMANTIC session boundary (no old session with the new mode)`() {
        runPrompt("دورة الوضع السريع")
        awaitExecutionSettled()
        val quickSessionId = viewModel.state.value.activeSessionId
        assertNotNull(quickSessionId)
        assertEquals(1, viewModel.state.value.studioSession.size)

        viewModel.setChatMode(ChatMode.AGENT)

        val state = viewModel.state.value
        assertEquals(ChatMode.AGENT, state.chatMode)
        // The binding is RELEASED: the next send ensures a NEW session.
        assertNull(state.activeSessionId)
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.studioSession.isEmpty())
        // The OLD durable session is untouched and browsable.
        val oldSession = runBlocking { repository.getSession(ConversationSessionId(quickSessionId!!)) }
        assertNotNull(oldSession)
        assertEquals(1, oldSession!!.turnCount)
        assertEquals(ChatMode.QUICK_CHAT, oldSession.mode)
    }

    @Test
    fun `a mode change is REFUSED honestly while an execution is running`() {
        val gate = CompletableDeferred<Unit>()
        streamGate = gate
        runPrompt("تنفيذ جارٍ")
        awaitUntil { viewModel.state.value.liveExecution != null }

        viewModel.setChatMode(ChatMode.AGENT)

        assertEquals(ChatMode.QUICK_CHAT, viewModel.state.value.chatMode)
        assertNotNull(viewModel.state.value.errorMessage)

        gate.complete(Unit)
        awaitExecutionSettled()
        streamGate = null
    }

    @Test
    fun `the selected model binds onto the session CREATED by the next send`() {
        viewModel.selectModel(resourceId = "mock_studio_provider", displayName = "النموذج المختار")
        runPrompt("رسالة مع نموذج مثبت")
        awaitExecutionSettled()

        val sessionId = viewModel.state.value.activeSessionId!!
        val session = runBlocking { repository.getSession(ConversationSessionId(sessionId)) }!!
        assertEquals("mock_studio_provider", session.modelResourceId)
        // The assistant entry carries the binding too (state/UI consistency).
        val assistant = viewModel.state.value.timeline
            .filterIsInstance<com.example.presentation.state.ChatEntry.Assistant>().single()
        assertEquals("mock_studio_provider", assistant.modelResourceId)
    }

    @Test
    fun `openSession restores the MODE and the MODEL binding alongside the transcript`() {
        val agent = com.example.application.agent.CanonicalAgentCatalog.defaults
            .first { it.identity.id.value == "agent_general" }
        viewModel.setChatMode(ChatMode.AGENT)
        viewModel.selectModel(resourceId = "mock_studio_provider", displayName = "النموذج المختار")
        viewModel.updatePromptInput("سؤال وضع الوكيل")
        viewModel.executePrompt(agent = agent)
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!
        assertEquals(ChatMode.AGENT, runBlocking { repository.getSession(ConversationSessionId(sessionId)) }!!.mode)

        // Simulate process death: a FRESH ViewModel over the same repository.
        val fresh = StudioViewModel(
            executeAgentTaskUseCase = executeAgentTaskUseCase,
            componentRegistry = registry,
            workspaceRuntimeService = workspaceService,
            conversationSessionService = sessionService,
            signalBus = signalBus
        )
        fresh.setChatMode(ChatMode.QUICK_CHAT)
        fresh.openSession(sessionId)
        awaitUntil { fresh.state.value.timeline.isNotEmpty() }

        val restored = fresh.state.value
        assertEquals(ChatMode.AGENT, restored.chatMode)
        assertEquals("mock_studio_provider", restored.selectedModelResourceId)
        // The timeline restores BOTH sides of every durable turn.
        assertEquals(restored.studioSession.size * 2, restored.timeline.size)
    }

    @Test
    fun `resetTranscriptView resets the VIEW without touching durable storage`() {
        runPrompt("دورة قبل إعادة التعيين")
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!
        assertEquals(1, viewModel.state.value.studioSession.size)

        viewModel.resetTranscriptView()

        val state = viewModel.state.value
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.studioSession.isEmpty())
        assertNull(state.activeSessionId)
        // The durable session REMAINS (the UI never claimed a deletion).
        val session = runBlocking { repository.getSession(ConversationSessionId(sessionId)) }
        assertNotNull(session)
        assertEquals(1, session!!.turnCount)
    }

    // ------------------------------------------------------------------
    // CHAT CAPABILITIES (Task 2) — attachments, approvals, capability blocks
    // ------------------------------------------------------------------

    @Test
    fun `send with attachments stages them on the user entry and persists them with the turn`() {
        val attachment = com.example.domain.core.session.TurnAttachment(
            id = "attm_test_1",
            name = "ملاحظات.txt",
            mimeType = "text/plain",
            sizeBytes = 12L,
            storageUri = "attachments/ملاحظات.txt",
            artifactId = "art_test_1"
        )
        viewModel.updatePromptInput("لخص هذا الملف")
        val accepted = viewModel.executePrompt(agent = null, attachments = listOf(attachment))

        assertTrue(accepted)
        awaitExecutionSettled()

        val timeline = viewModel.state.value.timeline
        val user = timeline.first { it is ChatEntry.User } as ChatEntry.User
        assertEquals("لخص هذا الملف", user.text)
        // §5: the chips ride the user message.
        assertEquals(listOf("ملاحظات.txt"), user.attachments.map { it.name })

        // §6: the clean user text is what the conversation shows; the LLM
        // request carries the user's text (the digest builder is not wired in
        // this composition — an honest empty digest).
        assertTrue(capturedRequests.isNotEmpty())
        assertTrue(
            "the LLM prompt contains the user's own words",
            capturedRequests.last().messages.last().content.contains("لخص هذا الملف")
        )

        // §16/§7: the durable turn KEEPS the attachment references.
        val persisted = repository.appendedTurns.last()
        assertEquals(listOf(attachment), persisted.attachments)
    }

    @Test
    fun `reopening a session restores the attachment chips from the durable references`() {
        val attachment = com.example.domain.core.session.TurnAttachment(
            id = "attm_test_2",
            name = "تقرير.md",
            mimeType = "text/markdown",
            sizeBytes = 30L,
            storageUri = "attachments/تقرير.md",
            artifactId = "art_test_2"
        )
        viewModel.updatePromptInput("اقرأ التقرير")
        viewModel.executePrompt(agent = null, attachments = listOf(attachment))
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!

        // REOPEN: a fresh load path (same service — the process-death stand-in).
        viewModel.openSession(sessionId)
        awaitUntil {
            viewModel.state.value.timeline
                .any { it is ChatEntry.User && it.attachments.isNotEmpty() }
        }
        val restored = viewModel.state.value.timeline
            .first { it is ChatEntry.User && it.attachments.isNotEmpty() } as ChatEntry.User
        assertEquals("تقرير.md", restored.attachments.single().name)
        assertEquals("art_test_2", restored.attachments.single().artifactId)
    }

    @Test
    fun `a structured capability result lands in the conversation order`() {
        runPrompt("سؤال أول")
        awaitExecutionSettled()

        viewModel.appendCapabilityResult(
            ChatEntry.CapabilityResult(
                id = "cap_test_1",
                kind = com.example.presentation.state.CapabilityKind.TOOL,
                title = "tool_test_echo",
                summary = "تم التنفيذ بنجاح.",
                detail = "نتيجة الأداة",
                isSuccessful = true
            )
        )

        val timeline = viewModel.state.value.timeline
        val block = timeline.last { it is ChatEntry.CapabilityResult } as ChatEntry.CapabilityResult
        assertEquals("نتيجة الأداة", block.detail)
        // The conversation keeps its message entries around the block.
        assertTrue(timeline.count { it is ChatEntry.User } >= 1)
        assertTrue(timeline.count { it is ChatEntry.Assistant } >= 1)
    }

    @Test
    fun `search citations collected during the execution ride the assistant entry as sources`() {
        citationsScenario = true
        viewModel.updatePromptInput("ابحث واشرح")
        viewModel.executePrompt(agent = null)
        awaitExecutionSettled()

        val assistant = viewModel.state.value.timeline
            .last { it is ChatEntry.Assistant } as ChatEntry.Assistant
        assertTrue(assistant.sources.isNotEmpty())
        assertTrue(assistant.sources.any { it.url == "https://example.com/citation" })
    }

    // ------------------------------------------------------------------
    // CHAT CAPABILITIES (Task 2 §13) — the inline human approval loop
    // ------------------------------------------------------------------

    @Test
    fun `an approval-blocked execution surfaces its REAL pending request inline`() {
        runApprovalScenarioPrompt()

        awaitUntil {
            viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock }
        }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock
        assertEquals(approvalToolName, block.toolName)
        assertEquals("HIGH", block.riskLevel)
        assertEquals(
            com.example.presentation.state.ApprovalBlockState.PENDING,
            block.state
        )
        // The REAL gate holds the persisted pending request.
        assertEquals(
            com.example.domain.core.security.governance.ApprovalResolution.PENDING,
            runBlocking { approvalStore.find(block.approvalId) }!!.resolution
        )
    }

    @Test
    fun `approving resolves through the REAL gate and updates the block state`() {
        runApprovalScenarioPrompt()
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        viewModel.approveApproval(block.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.state == com.example.presentation.state.ApprovalBlockState.APPROVED }
        }
        assertEquals(
            com.example.domain.core.security.governance.ApprovalResolution.APPROVED,
            runBlocking { approvalStore.find(block.approvalId) }!!.resolution
        )
    }

    @Test
    fun `rejecting resolves through the REAL gate and updates the block state`() {
        runApprovalScenarioPrompt()
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        viewModel.rejectApproval(block.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.state == com.example.presentation.state.ApprovalBlockState.REJECTED }
        }
        assertEquals(
            com.example.domain.core.security.governance.ApprovalResolution.REJECTED,
            runBlocking { approvalStore.find(block.approvalId) }!!.resolution
        )
    }

    @Test
    fun `approval state transitions are idempotent (already-resolved stays)`() {
        runApprovalScenarioPrompt()
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        viewModel.approveApproval(block.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.state == com.example.presentation.state.ApprovalBlockState.APPROVED }
        }
        // A late reject CANNOT flip an already-approved request.
        viewModel.rejectApproval(block.approvalId)
        Thread.sleep(200)
        assertEquals(
            com.example.domain.core.security.governance.ApprovalResolution.APPROVED,
            runBlocking { approvalStore.find(block.approvalId) }!!.resolution
        )
        assertEquals(
            com.example.presentation.state.ApprovalBlockState.APPROVED,
            (viewModel.state.value.timeline
                .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock).state
        )
    }

    @Test
    fun `retry after approval re-executes the message with append-only history`() {
        runApprovalScenarioPrompt()
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock
        val firstExecutionId = capturedExecutionIds.last()
        assertEquals(block.executionId, firstExecutionId)

        viewModel.approveApproval(block.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.state == com.example.presentation.state.ApprovalBlockState.APPROVED }
        }

        val resultsBefore = viewModel.state.value.timeline.count { it is ChatEntry.Assistant }
        // FUNCTIONAL CLOSURE (§8): the consent was RESOLVED — the retry runs
        // with the approval scenario OFF, exactly like the granted-consent
        // production path (no second fake consent request).
        approvalScenario = false
        // RESIDUAL CLOSURE (P4): the retry targets the block's OWN id.
        viewModel.retryAfterApproval(approvalId = block.approvalId, agent = null)
        awaitUntil { capturedExecutionIds.size >= 2 }
        awaitExecutionSettled()

        // §13: the retry really re-executed (a fresh kernel execution — the
        // kernel mints its own id, so the ids differ; nothing faked).
        assertEquals(firstExecutionId, capturedExecutionIds.first())
        assertTrue(capturedExecutionIds.drop(1).none { it == firstExecutionId })
        // The history stays append-only: NEW result entries landed, nothing removed.
        assertTrue(
            viewModel.state.value.timeline.count { it is ChatEntry.Assistant } > resultsBefore
        )
        assertTrue(
            viewModel.state.value.timeline.count { it is ChatEntry.User } == 1
        )
    }

    @Test
    fun `grant always resolves the approval AND records the standing EXECUTE grant`() {
        runApprovalScenarioPrompt()
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        viewModel.grantAlwaysForApproval(block.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.state == com.example.presentation.state.ApprovalBlockState.APPROVED }
        }

        // The gate resolved the request.
        assertEquals(
            com.example.domain.core.security.governance.ApprovalResolution.APPROVED,
            runBlocking { approvalStore.find(block.approvalId) }!!.resolution
        )
        // The REAL standing grant was recorded (the closeable consent path —
        // future admissions of this tool pass without a new request).
        val grant = runBlocking {
            grantDao.lookupScoped(
                principalType = "USER",
                principalId = "local-device-user",
                resourceType = "TOOL",
                resourceId = approvalToolName,
                permission = "EXECUTE",
                workspaceId = null
            )
        }
        assertNotNull(grant)
        assertEquals(approvalToolName, grant!!.resourceId)
    }

    @Test
    fun `retry without an approval refuses honestly`() {
        runApprovalScenarioPrompt()
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        // P4: a PENDING (not yet approved) block refuses the retry honestly.
        viewModel.retryAfterApproval(approvalId = block.approvalId, agent = null)
        Thread.sleep(200)
        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.errorMessage!!.contains("وافق"))
    }

    /** Drives one approval-blocked execution (the scripted mock scenario). */
    private fun runApprovalScenarioPrompt() {
        approvalScenario = true
        runPrompt("نفّذ الأداة الحساسة")
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun repositorySessions(): List<com.example.domain.core.session.ConversationSession> = runBlocking {
        withTimeoutOrNull(500) {
            sessionService.observeSessions(activeWorkspace.id).first()
        } ?: emptyList()
    }
}

/** In-memory permission-grant DAO (the fake the service contract expects). */
class FakePermissionGrantDaoForVm : com.example.infrastructure.persistence.dao.PermissionGrantDao {
    private val rows = mutableListOf<com.example.infrastructure.persistence.entities.PermissionGrantEntity>()
    private var nextId = 1L

    override suspend fun forPrincipal(principalType: String, principalId: String) =
        rows.filter { it.principalType == principalType && it.principalId == principalId }

    override suspend fun lookupScoped(
        principalType: String,
        principalId: String,
        resourceType: String,
        resourceId: String,
        permission: String,
        workspaceId: String?
    ): com.example.infrastructure.persistence.entities.PermissionGrantEntity? = rows.firstOrNull {
        it.principalType == principalType && it.principalId == principalId &&
            it.resourceType == resourceType && it.resourceId == resourceId &&
            it.permission == permission && (it.workspaceId == null || it.workspaceId == workspaceId)
    }

    override suspend fun lookup(
        principalType: String,
        principalId: String,
        resourceType: String,
        resourceId: String,
        permission: String
    ): com.example.infrastructure.persistence.entities.PermissionGrantEntity? =
        lookupScoped(principalType, principalId, resourceType, resourceId, permission, null)

    override suspend fun upsert(grant: com.example.infrastructure.persistence.entities.PermissionGrantEntity): Long {
        val id = nextId++
        rows += grant.copy(id = id)
        return id
    }

    override suspend fun revoke(id: Long) {
        rows.removeAll { it.id == id }
    }
}

/** No-op telemetry port (audit writes land nowhere — the grants are the assertion). */
object NoopTelemetryForVm : com.example.domain.ports.observability.TelemetryPort {
    override suspend fun record(sample: com.example.domain.core.observability.MetricSample) {}
    override suspend fun recordBatch(samples: List<com.example.domain.core.observability.MetricSample>) {}
    override suspend fun recordAudit(event: com.example.domain.core.observability.AuditEvent): Long = 0L
    override suspend fun recordTraceNode(node: com.example.domain.core.observability.ExecutionTraceNode) {}
    override fun snapshots() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.domain.core.observability.MetricSnapshot>())
    override fun dimensionSummaries() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.domain.core.observability.DimensionSummary>())
    override fun auditEvents(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.domain.core.observability.AuditEvent>())
    override fun traceForExecution(executionId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.domain.core.observability.ExecutionTraceNode>())
    override fun recentTraceNodes(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.domain.core.observability.ExecutionTraceNode>())
    override suspend fun snapshotByType(type: com.example.domain.core.observability.MetricType) = emptyList<com.example.domain.core.observability.MetricSnapshot>()
}
