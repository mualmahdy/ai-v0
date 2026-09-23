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
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ChatMode
import com.example.domain.core.workspace.Workspace
import com.example.domain.ports.llm.LlmProviderPort
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ChatEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ============================================================================
 * StudioViewModelResidualClosureTest — CHAT FUNCTIONAL RESIDUAL CLOSURE
 * ============================================================================
 *
 * The PROOF-OF-DEFECT suite for the residual problems found by the independent
 * review after FUNCTIONAL CLOSURE Phase 1 (same harness shape as
 * StudioViewModelFunctionalClosureTest — mock only at the LLM port, everything
 * else REAL):
 *
 *  P1  detached execution events after a scope/workspace switch must not
 *      mutate the new scope's chat state (stream text, log, gauges, degraded
 *      state, isExecuting, approval blocks) — and a NEW execution in the new
 *      scope must keep working while the detached one finishes.
 *  P2  a capability result resolves into its ORIGINATING session (captured at
 *      invocation start) — never into the new scope's session, never lost.
 *  P3  a capability invocation BEFORE the first message still persists its
 *      result durably (a session is created and pinned for it) — without
 *      creating empty sessions for invocations that never resolve.
 *  P4  retrying APPROVAL A re-executes exactly A's message — even when a
 *      later approval B is also approved (or rejected).
 *  P6  a detached execution's first turn still titles its OWN pinned session.
 *
 * (P5 — execution-pinned grounding — needs the attachment coordinator stack;
 *  it lives in StudioGroundingScopePinningTest.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelResidualClosureTest {

    // A REAL Unconfined main: viewModelScope launches run INLINE on the
    // caller thread (deterministic synchronous state updates) and StateFlow
    // collector resumptions execute inline on the updater's thread — the
    // anchor recorder can never miss an intermediate live block.
    private val dispatcher = Dispatchers.Unconfined
    private val signalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val signalCollectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var registry: ComponentRegistry
    private lateinit var executeAgentTaskUseCase: ExecuteAgentTaskUseCase
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var repository: FakeConversationSessionRepositoryForVm
    private lateinit var sessionService: ConversationSessionService
    private lateinit var viewModel: StudioViewModel
    private lateinit var activeWorkspace: Workspace

    /** LLM requests captured from the mock provider (prompt identity proofs). */
    private val capturedRequests = mutableListOf<LlmRequest>()

    /** Kernel execution ids the mock stream saw (log-purity proofs). */
    private val capturedExecutionIds = mutableListOf<String>()

    /** One mid-stream gate per execution, in launch order (no sleeps). */
    private val streamGates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()

    /** Per-execution stream/final text (distinguishes A's answer from B's). */
    @Volatile
    private var scriptedChunkPrefix: String = "الجواب "

    @Volatile
    private var scriptedFinalText: String = "الجواب الكامل"

    /** Per-execution usage numbers (gauge-purity proofs). */
    @Volatile
    private var scriptedUsage: Pair<Int, Int> = 10 to 20

    /** Emit Completed with a BLANK final text (forces the stream-fallback path). */
    @Volatile
    private var blankFinalText: Boolean = false

    /** Immediate consent-block scenario (Task-2 §13 shape). */
    @Volatile
    private var approvalScenario: Boolean = false

    /** GATED consent scenario: one chunk, then the gate, then the consent error. */
    @Volatile
    private var gatedApprovalScenario: Boolean = false

    private val approvalStore = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
    private val gate = com.example.application.governed.HumanApprovalGate(approvalStore)

    /**
     * RESIDUAL CLOSURE (persistence-failure leak tests): the non-UI sink the
     * ViewModel records DETACHED persistence failures through (production
     * default is stderr — the AuditTrailService write-failure convention).
     */
    private val recordedDetachedFailures =
        java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)

        registry = ComponentRegistry()
        val securityGuard = SecurityGuardService()
        val decisionService = DecisionService(
            com.example.domain.core.decision.CbrMdpEngine(),
            registry,
            securityGuard
        )
        val orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
        executeAgentTaskUseCase = ExecuteAgentTaskUseCase(orchestrator)

        val workspaceDao = FakeWorkspaceDaoForVm()
        val projectDao = FakeProjectDaoForVm()
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = workspaceDao,
            projectDao = projectDao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100) // let the default bootstrap settle
        activeWorkspace = workspaceService.createWorkspace("مساحة الإغلاق المتبقي", "اختبار")
        orchestrator.workspaceIdProvider = { activeWorkspace.id }

        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_residual_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_residual_provider",
                name = "Mock Residual",
                providerType = "MOCK",
                defaultModel = "residual-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("residual-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(
                    LlmResponse(
                        text = "جواب فوري",
                        toolCalls = emptyList(),
                        usage = TokenUsage(10, 20),
                        finishReason = "STOP",
                        modelId = "residual-mock-v1"
                    )
                )

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                capturedRequests += request
                capturedExecutionIds += executionId
                if (approvalScenario) {
                    gate.requestApproval(
                        executionId = executionId,
                        toolName = "tool_residual_consent",
                        riskLevel = "HIGH",
                        prompt = "طلب موافقة الإغلاق المتبقي",
                        justification = "سياسة الحوكمة"
                    )
                    emit(
                        ExecutionEvent.Error(
                            executionId,
                            "HUMAN_APPROVAL_REQUIRED",
                            "يتطلب موافقة بشرية"
                        )
                    )
                } else if (gatedApprovalScenario) {
                    emit(ExecutionEvent.ContentChunk(executionId, "قبل الموافقة ", sequenceIndex = 0))
                    streamGates.poll()?.await()
                    gate.requestApproval(
                        executionId = executionId,
                        toolName = "tool_residual_consent",
                        riskLevel = "HIGH",
                        prompt = "طلب موافقة مؤجل",
                        justification = "سياسة الحوكمة"
                    )
                    emit(
                        ExecutionEvent.Error(
                            executionId,
                            "HUMAN_APPROVAL_REQUIRED",
                            "يتطلب موافقة بشرية"
                        )
                    )
                } else {
                    // Snapshot BOTH the text and the usage numbers at stream
                    // START (the volatiles may legitimately change while a
                    // gated execution is suspended mid-stream).
                    val prefix = scriptedChunkPrefix
                    val (promptTokens, completionTokens) = scriptedUsage
                    emit(ExecutionEvent.ContentChunk(executionId, prefix, sequenceIndex = 0))
                    streamGates.poll()?.await()
                    emit(ExecutionEvent.ContentChunk(executionId, "الكامل", sequenceIndex = 1))
                    emit(
                        ExecutionEvent.UsageBudgetUpdate(
                            executionId = executionId,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalSessionTokens = promptTokens + completionTokens,
                            remainingBudgetTokens = 30_000 - (promptTokens + completionTokens)
                        )
                    )
                    emit(
                        ExecutionEvent.Completed(
                            executionId,
                            if (blankFinalText) "" else scriptedFinalText,
                            totalDurationMs = 40
                        )
                    )
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

        repository = FakeConversationSessionRepositoryForVm()
        repository.activeWorkspaceId = activeWorkspace.id
        sessionService = ConversationSessionService(
            repository = repository,
            workspaceIdProvider = { repository.activeWorkspaceId }
        )

        viewModel = StudioViewModel(
            executeAgentTaskUseCase = executeAgentTaskUseCase,
            componentRegistry = registry,
            workspaceRuntimeService = workspaceService,
            conversationSessionService = sessionService,
            networkMonitorProvider = null,
            appContext = null,
            signalBus = signalBus,
            humanApprovalGate = gate,
            permissionGrantService = com.example.application.security.PermissionGrantService(
                permissionGrantDao = FakePermissionGrantDaoForVm(),
                telemetryPort = NoopTelemetryForVm
            ),
            detachedPersistenceFailureSink = { message -> recordedDetachedFailures.add(message) }
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
        assertTrue("Timed out waiting for the condition (execution pipeline stalled?)", condition())
    }

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
                now - lastChangeAt > 500
            }
        }
    }

    private fun runPrompt(prompt: String) {
        viewModel.updatePromptInput(prompt)
        viewModel.executePrompt(agent = null)
    }

    /** Creates a SECOND workspace and returns it (the mid-execution switch). */
    private suspend fun switchToNewWorkspace(name: String = "مساحة ثانية"): Workspace =
        workspaceService.createWorkspace(name, "أثناء التنفيذ")

    /**
     * Records every liveExecution's (executionId → anchored user entry) the
     * ViewModel EVER enters (an UNCONFINED collector observes each emission
     * ON THE EMITTER'S THREAD — StateFlow conflation can never hide an
     * intermediate live block). Callers filter out the pre-existing live
     * execution to isolate the RETRY's own anchor.
     */
    private fun recordLiveExecutionAnchors(): Pair<MutableMap<String, String>, CoroutineScope> {
        val seen: MutableMap<String, String> =
            java.util.Collections.synchronizedMap(HashMap<String, String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch {
            viewModel.state.collect { st ->
                st.liveExecution?.let { live ->
                    seen[live.executionId] = live.originUserEntryId ?: ""
                }
            }
        }
        return seen to scope
    }

    // ------------------------------------------------------------------
    // P1 — detached execution events must not mutate the new scope's state
    // ------------------------------------------------------------------

    @Test
    fun `P1a a detached execution's post-switch events never touch the new scope's chat state`() {
        val executionGate = CompletableDeferred<Unit>()
        streamGates += executionGate
        runPrompt("تنفيذ يُفصل أثناء البث")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }
        // The scope change cleared the conversation state.
        assertEquals("", viewModel.state.value.streamText)
        assertTrue(viewModel.state.value.executionLog.isEmpty())
        assertEquals(0, viewModel.state.value.currentTokensConsumed)

        // The detached execution completes now — every event it emits from
        // here on belongs to the OLD scope and must NOT re-enter this view.
        executionGate.complete(Unit)
        awaitUntil(20_000) { repository.appendedTurns.isNotEmpty() }
        awaitExecutionSettled()

        val state = viewModel.state.value
        assertEquals(
            "stale ContentChunk must not write stream text into the new scope",
            "",
            state.streamText
        )
        assertEquals(
            "stale UsageBudgetUpdate must not move the new scope's token gauges",
            0,
            state.currentTokensConsumed
        )
        assertEquals(
            "stale events must not enter the new scope's execution log",
            0,
            state.executionLog.size
        )
        assertEquals(
            "stale terminal event must not re-flag the new scope's composer",
            false,
            state.isExecuting
        )
        // §2 stays honored: the turn itself DID persist into the pinned
        // workspace's session.
        assertEquals(1, repository.appendedTurns.size)
        val session = runBlocking { repository.getSession(repository.appendedTurns.single().sessionId) }!!
        assertEquals(activeWorkspace.id, session.workspaceId)
    }

    @Test
    fun `P1b a NEW execution in the new scope keeps its live state while the detached one finishes`() {
        // E1 in workspace A, held mid-stream.
        val e1Gate = CompletableDeferred<Unit>()
        streamGates += e1Gate
        runPrompt("تنفيذ أ عبر التبديل")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        // Switch to workspace B mid-execution (E1 detaches).
        runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }

        // A NEW conversation + execution starts in B — E2 must work normally.
        scriptedChunkPrefix = "جواب_ب "
        scriptedUsage = 111 to 222
        val e2Gate = CompletableDeferred<Unit>()
        streamGates += e2Gate
        runPrompt("تنفيذ ب بعد التبديل")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }
        assertTrue(viewModel.state.value.isExecuting)
        val beforeStreamText = viewModel.state.value.streamText

        // E1 (detached) now completes — first with its own stale chunk.
        blankFinalText = true
        e1Gate.complete(Unit)
        awaitUntil(20_000) { repository.appendedTurns.size == 1 }

        // E2 is STILL the view's execution: its composer stays locked…
        assertEquals(
            "a detached terminal event must not unlock the new execution's composer",
            true,
            viewModel.state.value.isExecuting
        )
        // …its live stream shows ONLY its own chunk…
        assertEquals(
            "a detached ContentChunk must not splice into the live execution's stream",
            beforeStreamText,
            viewModel.state.value.streamText
        )

        // E2 completes normally (with a real final text).
        blankFinalText = false
        e2Gate.complete(Unit)
        awaitExecutionSettled()

        val state = viewModel.state.value
        // B's conversation contains exactly E2's exchange.
        val users = state.timeline.filterIsInstance<ChatEntry.User>()
        assertEquals(listOf("تنفيذ ب بعد التبديل"), users.map { it.text })
        assertEquals(1, state.timeline.filterIsInstance<ChatEntry.Assistant>().size)
        // B's gauges reflect E2's OWN usage event — not E1's stale one.
        assertEquals(333, state.currentTokensConsumed)
        // B's execution log contains only E2's kernel events.
        val e1KernelId = capturedExecutionIds.first()
        assertTrue(
            "detached events must never enter the new execution's log",
            state.executionLog.none { it.executionId == e1KernelId }
        )
        // E1's turn persisted into ITS pinned workspace/session, with ITS OWN
        // stream text (never the new scope's) and its own usage numbers.
        assertEquals(2, repository.appendedTurns.size)
        val e1Turn = repository.appendedTurns.first { it.prompt == "تنفيذ أ عبر التبديل" }
        val e1Session = runBlocking { repository.getSession(e1Turn.sessionId) }!!
        assertEquals(activeWorkspace.id, e1Session.workspaceId)
        assertEquals("الجواب الكامل", e1Turn.answer)
        assertEquals(30, e1Turn.tokensConsumed)
    }

    @Test
    fun `P1c a user cancel is an honest cancellation, never an unexpected error`() {
        streamGates += CompletableDeferred() // held: the cancel happens mid-stream
        runPrompt("تنفيذ سيُلغى")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        viewModel.cancelExecution()

        // The honest cancelled trace stays (Task 1 contract)…
        awaitUntil { viewModel.state.value.liveExecution?.phase ==
            com.example.presentation.state.ExecutionPhase.CANCELLED }
        assertEquals("تم إلغاء العملية بواسطة المستخدم.", viewModel.state.value.diagnosticBanner)
        // …and the cancellation is NOT surfaced as an unexpected pipeline error.
        Thread.sleep(400) // bounded settle window for the job's death path
        assertNull(
            "a user-initiated cancel must not surface errorMessage (CancellationException is not an error)",
            viewModel.state.value.errorMessage
        )
        assertEquals(0, repository.appendedTurns.size)
    }

    @Test
    fun `P1d resetting the view mid-execution detaches the running execution from the fresh conversation`() {
        val executionGate = CompletableDeferred<Unit>()
        streamGates += executionGate
        runPrompt("تنفيذ قبل إعادة تعيين العرض")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        viewModel.resetTranscriptView()

        // The fresh view is FREE (the execution keeps running detached).
        assertEquals(
            "resetting the view must detach the running execution (honest composer release)",
            false,
            viewModel.state.value.isExecuting
        )

        executionGate.complete(Unit)
        awaitUntil(20_000) { repository.appendedTurns.isNotEmpty() }
        awaitExecutionSettled()

        // The detached execution's result NEVER lands in the fresh view…
        assertTrue(
            "a detached execution must not append its result to the reset view",
            viewModel.state.value.timeline.isEmpty()
        )
        // …but its turn persisted into its own session (§2 honored).
        assertEquals(1, repository.appendedTurns.size)
    }

    @Test
    fun `P1e a detached consent request never lands in the new scope and stays durable in its own session`() {
        gatedApprovalScenario = true
        val executionGate = CompletableDeferred<Unit>()
        streamGates += executionGate
        runPrompt("طلب موافقة سيفصل")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        // Switch the workspace BEFORE the consent request is emitted.
        runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }

        // Release the gate: the consent request + HUMAN_APPROVAL_REQUIRED
        // error now arrive from the DETACHED execution.
        executionGate.complete(Unit)
        awaitUntil { viewModel.state.value.isExecuting == false }
        Thread.sleep(400) // bounded settle for the approval-block coroutine

        // The new scope's timeline stays EMPTY — no foreign approval block.
        assertTrue(
            "a detached consent request must not surface in the new scope's timeline",
            viewModel.state.value.timeline.none { it is ChatEntry.ApprovalBlock }
        )
        // The approval block is DURABLE in the execution's OWN session.
        val e1SessionId = repository.appendedTurnsSessionIds().firstOrNull()
            ?: repository.sessionsForWorkspace(activeWorkspace.id)
                .map { it.id }.firstOrNull()
        assertNotNull(
            "the detached execution's session must exist (ensureActiveSession ran at launch)",
            e1SessionId
        )
        assertTrue(
            "the consent request must be durable in its own session's timeline events",
            runBlocking {
                sessionService.timelineEventsForSession(e1SessionId!!)
            }.any { it.kind == com.example.domain.core.session.TimelineEventKind.APPROVAL_BLOCK }
        )
    }

    // ------------------------------------------------------------------
    // P2 — capability result scope pinning (save-to-originating-session)
    // ------------------------------------------------------------------

    @Test
    fun `P2a a capability result resolving after a workspace switch persists into its ORIGINATING session`() {
        runPrompt("سؤال قبل تبديل القدرة")
        awaitExecutionSettled()
        val originalSessionId = viewModel.state.value.activeSessionId!!
        assertNotNull(originalSessionId)

        // The invocation starts HERE (scope captured at invocation start)…
        val pendingId = viewModel.appendPendingCapability(
            kind = CapabilityKind.SEARCH,
            title = "بحث ذكي: اختبار التبديل"
        )

        // …and the scope switches while it runs.
        runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }

        viewModel.resolveCapabilityResult(
            pendingId,
            ChatEntry.CapabilityResult(
                id = pendingId,
                kind = CapabilityKind.SEARCH,
                title = "بحث ذكي: اختبار التبديل",
                summary = "تم العثور على نتيجة.",
                isSuccessful = true,
                timestampMs = System.currentTimeMillis()
            )
        )
        awaitUntil { repository.appendTimelineEventCount > 0 }
        Thread.sleep(200)

        // The result persisted into the ORIGINATING session — never lost.
        val events = runBlocking {
            sessionService.timelineEventsForSession(ConversationSessionId(originalSessionId))
        }
        assertTrue(
            "the capability result must persist into the session the invocation started in",
            events.any { it.id == pendingId && it.kind == com.example.domain.core.session.TimelineEventKind.CAPABILITY_RESULT }
        )
        // The new scope's view stays untouched (no foreign block).
        assertTrue(
            viewModel.state.value.timeline.none { it.id == pendingId }
        )
    }

    @Test
    fun `P2b a capability result never contaminates a sibling project's session`() {
        runPrompt("سؤال المشروع الأول للقدرة")
        awaitExecutionSettled()
        val originalSessionId = viewModel.state.value.activeSessionId!!
        val pendingId = viewModel.appendPendingCapability(
            kind = CapabilityKind.TOOL,
            title = "tool_scope_probe"
        )

        // Sibling project (SAME workspace) + a NEW session there.
        val otherProjectId = runBlocking { createSiblingProject() }
        runBlocking { workspaceService.setActiveProject(otherProjectId) }
        awaitUntil { viewModel.state.value.activeSessionId == null }
        runPrompt("سؤال المشروع الثاني للقدرة")
        awaitExecutionSettled()
        val siblingSessionId = viewModel.state.value.activeSessionId!!
        assertTrue(originalSessionId != siblingSessionId)

        viewModel.resolveCapabilityResult(
            pendingId,
            ChatEntry.CapabilityResult(
                id = pendingId,
                kind = CapabilityKind.TOOL,
                title = "tool_scope_probe",
                summary = "تم.",
                isSuccessful = true,
                timestampMs = System.currentTimeMillis()
            )
        )
        awaitUntil { repository.appendTimelineEventCount > 0 }
        Thread.sleep(200)

        // The result landed in the ORIGINATING project's session…
        val originalEvents = runBlocking {
            sessionService.timelineEventsForSession(ConversationSessionId(originalSessionId))
        }
        assertTrue(originalEvents.any { it.id == pendingId })
        // …NOT in the sibling project's session (no cross-project contamination).
        val siblingEvents = runBlocking {
            sessionService.timelineEventsForSession(ConversationSessionId(siblingSessionId))
        }
        assertTrue(
            "the capability result must never persist into another project's session",
            siblingEvents.none { it.id == pendingId }
        )
    }

    // ------------------------------------------------------------------
    // P3 — capability invocation BEFORE the first session
    // ------------------------------------------------------------------

    @Test
    fun `P3 a capability result before the first message persists into a pinned durable session`() {
        assertNull(viewModel.state.value.activeSessionId)

        val pendingId = viewModel.appendPendingCapability(
            kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
            title = "استرجاع المعرفة: بدون جلسة"
        )
        // A PENDING block alone creates NO session (no needless empty rows).
        Thread.sleep(250)
        assertEquals(
            "a pending invocation must not create an empty session",
            0,
            repository.sessionsForWorkspace(activeWorkspace.id).size
        )

        viewModel.resolveCapabilityResult(
            pendingId,
            ChatEntry.CapabilityResult(
                id = pendingId,
                kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
                title = "استرجاع المعرفة: بدون جلسة",
                summary = "تم استرجاع مقطعين.",
                isSuccessful = true,
                timestampMs = System.currentTimeMillis()
            )
        )
        awaitUntil { repository.appendTimelineEventCount > 0 }
        Thread.sleep(200)

        // A durable session was created and pinned for the result…
        val sessions = repository.sessionsForWorkspace(activeWorkspace.id)
        assertEquals(1, sessions.size)
        val created = sessions.single()
        assertEquals(activeWorkspace.activeProjectId.takeIf { it > 0L }, created.projectId)
        assertEquals(ChatMode.QUICK_CHAT, created.mode)
        // …the view's conversation is bound to it (the block is IN the visible conversation)…
        assertEquals(created.id.value, viewModel.state.value.activeSessionId)
        // …and the result survives a reopen.
        viewModel.resetTranscriptView()
        viewModel.openSession(created.id.value)
        awaitUntil { viewModel.state.value.timeline.isNotEmpty() }
        val reopened = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.CapabilityResult>()
            .single()
        assertEquals("تم استرجاع مقطعين.", reopened.summary)
    }

    // ------------------------------------------------------------------
    // P4 — targeted approval retry (approval A vs approval B)
    // ------------------------------------------------------------------

    @Test
    fun `P4 retrying approval A targets exactly A's message even when approval B is also approved`() {
        // Block A: the FIRST consent-blocked exchange.
        approvalScenario = true
        runPrompt("رسالة الموافقة أ")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.ApprovalBlock } == 1 }
        val blockA = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.ApprovalBlock>().single()
        approvalScenario = false
        viewModel.approveApproval(blockA.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.approvalId == blockA.approvalId && it.state == ApprovalBlockState.APPROVED }
        }

        // Block B: a SECOND consent-blocked exchange, later in the SAME conversation.
        approvalScenario = true
        runPrompt("رسالة الموافقة ب")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.ApprovalBlock } == 2 }
        val blockB = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.ApprovalBlock>()
            .first { it.approvalId != blockA.approvalId }
        approvalScenario = false
        viewModel.approveApproval(blockB.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.approvalId == blockB.approvalId && it.state == ApprovalBlockState.APPROVED }
        }
        // The block can land in the timeline a frame BEFORE the same event's
        // isExecuting=false update — settle fully so the retry never races
        // the pipeline's terminal bookkeeping.
        awaitExecutionSettled()

        // The user taps RETRY on block A (its own affordance — §6 identity).
        val userAId = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.User>().first().id
        val userBId = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.User>().last().id
        capturedRequests.clear()
        val preRetryLiveId = viewModel.state.value.liveExecution?.executionId
        val seenAnchors = recordLiveExecutionAnchors()
        // CHAT FINAL CLOSURE: the deterministic targeting witness — the
        // retry RETURNS the retargeted message id (the transitory live-block
        // emission can conflate under fast kernel completions; the returned
        // id is exact).
        val retryTarget = retryApprovalFor(blockA.approvalId)
        awaitExecutionSettled()
        seenAnchors.second.cancel()

        // TARGETING PROOF (kernel-independent): the retry targets A's OWN
        // user message — never to B's (the last approved block's message).
        assertEquals(
            "retry on A must target A's user message (deterministic witness)",
            userAId,
            retryTarget
        )
        val retryAnchors = seenAnchors.first.entries
            .filter { it.key != preRetryLiveId }
        assertFalse(
            "retry on A must never anchor to B's user message (the last approved block's message)",
            retryAnchors.any { it.value == userBId }
        )
        // Whatever the kernel's admissibility decides for the re-run (its
        // honest Degraded refusal after this case history is a KERNEL
        // decision, orthogonal to targeting), B's message was NEVER the
        // retry's target — the OLD defect re-executed B (the last approved).
        assertTrue(
            "the retry must never send B's message (the last approved block's)",
            capturedRequests.none { it.messages.last().content.contains("رسالة الموافقة ب") }
        )
        // When the kernel admits the re-run, the request carries exactly A's
        // words (the message the tapped approval block belongs to).
        capturedRequests.lastOrNull()?.let { request ->
            val content = request.messages.last().content
            assertTrue(
                "an admitted retry request must carry A's message (got: $content)",
                content.contains("رسالة الموافقة أ")
            )
        }
    }

    /**
     * The UI affordance for a specific approval block's retry (P4 seam):
     * the tapped block's approvalId — returns the RETARGETED user message id
     * (CHAT FINAL CLOSURE: the deterministic targeting witness — a transitory
     * live-block emission can conflate under fast kernel completions, so the
     * anchor map alone is not a reliable witness).
     */
    private fun retryApprovalFor(approvalId: String): String? =
        viewModel.retryAfterApproval(approvalId = approvalId, agent = null)

    @Test
    fun `P4b retrying approval A works even when approval B was REJECTED`() {
        approvalScenario = true
        runPrompt("رسالة الرفض أ")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.ApprovalBlock } == 1 }
        val blockA = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.ApprovalBlock>().single()
        approvalScenario = false
        viewModel.approveApproval(blockA.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.approvalId == blockA.approvalId && it.state == ApprovalBlockState.APPROVED }
        }

        approvalScenario = true
        runPrompt("رسالة الرفض ب")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.ApprovalBlock } == 2 }
        val blockB = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.ApprovalBlock>()
            .first { it.approvalId != blockA.approvalId }
        approvalScenario = false
        viewModel.rejectApproval(blockB.approvalId)
        awaitUntil {
            viewModel.state.value.timeline
                .filterIsInstance<ChatEntry.ApprovalBlock>()
                .any { it.approvalId == blockB.approvalId && it.state == ApprovalBlockState.REJECTED }
        }
        // Settle fully (see P4) — the retry must never race the terminal
        // bookkeeping of the rejected conversation's execution.
        awaitExecutionSettled()

        val userAId = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.User>().first().id
        val userBId = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.User>().last().id
        capturedRequests.clear()
        val preRetryLiveId = viewModel.state.value.liveExecution?.executionId
        val seenAnchors = recordLiveExecutionAnchors()
        // CHAT FINAL CLOSURE: the deterministic targeting witness (see P4).
        val retryTarget = retryApprovalFor(blockA.approvalId)
        awaitExecutionSettled()
        seenAnchors.second.cancel()

        // TARGETING PROOF (kernel-independent): the retry targets A's OWN
        // user message even when B was REJECTED — the approved-block lookup
        // never falls back to "whatever is approved".
        assertEquals(
            "retry on A must target A's user message even when B was rejected (deterministic witness)",
            userAId,
            retryTarget
        )
        val retryAnchors = seenAnchors.first.entries
            .filter { it.key != preRetryLiveId }
        assertFalse(
            "retry on A must never anchor to B's user message when B was rejected",
            retryAnchors.any { it.value == userBId }
        )
        // Whatever the kernel's admissibility decides for the re-run, B's
        // message was NEVER the retry's target.
        assertTrue(
            capturedRequests.none { it.messages.last().content.contains("رسالة الرفض ب") }
        )
    }

    // ------------------------------------------------------------------
    // P6 — the detached execution's first turn titles its OWN session
    // ------------------------------------------------------------------

    @Test
    fun `P6 a detached execution's first turn still titles its pinned session`() {
        val executionGate = CompletableDeferred<Unit>()
        streamGates += executionGate
        runPrompt("عنوان الجلسة المفصولة عن العرض")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        val secondWorkspace = runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }
        // The session service's workspace provider now tracks the REAL
        // active workspace (production wiring: requireActiveWorkspaceId()).
        repository.activeWorkspaceId = secondWorkspace.id

        executionGate.complete(Unit)
        awaitUntil(20_000) { repository.appendedTurns.isNotEmpty() }
        awaitExecutionSettled()

        val turn = repository.appendedTurns.single()
        val session = runBlocking { repository.getSession(turn.sessionId) }!!
        assertEquals(activeWorkspace.id, session.workspaceId)
        assertTrue(
            "the pinned session must be titled from its own first turn (got: ${session.title})",
            session.title.startsWith("عنوان الجلسة المفصولة")
        )
    }


    // ------------------------------------------------------------------
    // RESIDUAL CLOSURE — persistence-failure UI leaks + approval integrity
    // (Test A / Test B / Test C / Test D per the closure task contract)
    // ------------------------------------------------------------------

    @Test
    fun `Test A - a detached execution's durable turn persistence failure never touches the new scope's banner`() {
        // The execution starts in workspace A and is held mid-stream.
        val executionGate = CompletableDeferred<Unit>()
        streamGates += executionGate
        runPrompt("تنفيذ يفشل حفظه بعد الفصل")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        // The user switches to workspace B — the execution DETACHES (the
        // switch's own banner is the honest scope-change notice).
        runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }
        val bannerBeforeFailure = viewModel.state.value.diagnosticBanner

        // From now on every durable append FAILS (a disk-full-class failure).
        repository.appendTurnFailure = IllegalStateException("disk full (simulated)")

        // The detached execution completes and tries to persist its turn
        // into ITS OWN pinned session under workspace A.
        executionGate.complete(Unit)
        awaitUntil(20_000) { repository.failedAppendTurnAttempts >= 1 }
        awaitExecutionSettled()

        // The failure path RAN and was handled: it is recorded honestly in
        // the NON-UI sink (the failure is real — "nowhere" is not honest)…
        assertTrue(
            "the detached persistence failure must be recorded non-visibly",
            recordedDetachedFailures.any { it.contains("detached execution") && it.contains("disk full") }
        )
        // …but the CURRENT scope B's diagnostic state was NEVER mutated by
        // the old execution's failure (the banner is still exactly the
        // scope-change notice — no persistence-failure text leaked in).
        assertEquals(
            "scope B's diagnostic banner must stay untouched by a detached persistence failure",
            bannerBeforeFailure,
            viewModel.state.value.diagnosticBanner
        )
        assertFalse(
            "no persistence-failure text may leak into scope B's banner",
            viewModel.state.value.diagnosticBanner?.contains("تعذر حفظ دورة المحادثة") == true
        )
        // And the view state of B is still the clean, fresh conversation.
        assertTrue(viewModel.state.value.timeline.isEmpty())
        assertEquals("", viewModel.state.value.streamText)
    }

    @Test
    fun `Test B - a capability persistence failure in a detached scope never touches the new scope's UI`() {
        // The capability invocation starts in workspace A, before any
        // session exists (the P3 shape — the resolved result creates one).
        val pendingId = viewModel.appendPendingCapability(
            com.example.presentation.state.CapabilityKind.SEARCH,
            "بحث الإغلاق المتبقي"
        )

        // The user switches to workspace B before the result resolves.
        runBlocking { switchToNewWorkspace() }
        awaitUntil { viewModel.state.value.activeSessionId == null }
        val bannerBeforeFailure = viewModel.state.value.diagnosticBanner

        // Capability persistence fails from now on.
        repository.appendTimelineEventFailure =
            IllegalStateException("timeline write failed (simulated)")

        // The result resolves under the ORIGINATING scope A.
        viewModel.resolveCapabilityResult(
            pendingId,
            ChatEntry.CapabilityResult(
                id = pendingId,
                kind = com.example.presentation.state.CapabilityKind.SEARCH,
                title = "بحث الإغلاق المتبقي",
                summary = "النتيجة الجاهزة",
                isSuccessful = true
            )
        )
        awaitUntil(20_000) { repository.failedAppendTimelineEventAttempts >= 1 }
        awaitExecutionSettled()

        // The result stayed bound to A: the failed write targeted the
        // session created under the CAPTURED scope (A) — never B's view.
        assertNotNull(
            "the capability persistence attempt must have run",
            repository.lastFailedTimelineEventSessionId
        )
        val failedSession = runBlocking {
            repository.getSession(ConversationSessionId(repository.lastFailedTimelineEventSessionId!!))
        }
        assertEquals(
            "the failed capability write must target the ORIGINATING workspace's session",
            activeWorkspace.id,
            failedSession?.workspaceId
        )
        // The failure is recorded non-visibly (honest — it really happened)…
        assertTrue(
            "the detached capability persistence failure must be recorded non-visibly",
            recordedDetachedFailures.any { it.contains("detached invocation") }
        )
        // …but B's UI state was never mutated: no banner change, no timeline
        // pollution, no foreign session binding.
        assertEquals(
            "scope B's diagnostic banner must stay untouched by A's capability persistence failure",
            bannerBeforeFailure,
            viewModel.state.value.diagnosticBanner
        )
        assertFalse(
            "no capability persistence failure text may leak into scope B",
            viewModel.state.value.diagnosticBanner?.contains("تعذر حفظ نتيجة القدرة") == true
        )
        assertTrue(viewModel.state.value.timeline.isEmpty())
        assertNull("no foreign session binding may leak into B's view", viewModel.state.value.activeSessionId)
    }

    @Test
    fun `Test C - an approval update with session B and approvalId A changes nothing`() {
        // Two sessions in the same workspace, each carrying its OWN approval
        // event (A's event lives ONLY in session A).
        val sessionA = com.example.domain.core.session.ConversationSession(
            id = ConversationSessionId("sess_integrity_a"),
            workspaceId = activeWorkspace.id,
            title = "جلسة السلامة أ"
        )
        val sessionB = com.example.domain.core.session.ConversationSession(
            id = ConversationSessionId("sess_integrity_b"),
            workspaceId = activeWorkspace.id,
            title = "جلسة السلامة ب"
        )
        repository.seed(sessionA)
        repository.seed(sessionB)
        runBlocking {
            repository.appendTimelineEventForWorkspace(
                com.example.domain.core.session.ConversationTimelineEvent(
                    id = "apv_evt_a",
                    sessionId = sessionA.id,
                    kind = com.example.domain.core.session.TimelineEventKind.APPROVAL_BLOCK,
                    title = "موافقة أ",
                    summary = "طلب أ",
                    approvalId = "approval_a",
                    approvalState = "PENDING"
                ),
                workspaceId = activeWorkspace.id
            )
            repository.appendTimelineEventForWorkspace(
                com.example.domain.core.session.ConversationTimelineEvent(
                    id = "apv_evt_b",
                    sessionId = sessionB.id,
                    kind = com.example.domain.core.session.TimelineEventKind.APPROVAL_BLOCK,
                    title = "موافقة ب",
                    summary = "طلب ب",
                    approvalId = "approval_b",
                    approvalState = "PENDING"
                ),
                workspaceId = activeWorkspace.id
            )
        }

        // A WRONG pairing: session B + approvalId A (a stray id reaching the
        // mirror path) must update NOTHING — not A's event (the old SQL would
        // have flipped it), not B's event.
        val applied = runBlocking {
            sessionService.updateTimelineEventApprovalState(
                sessionId = sessionB.id,
                approvalId = "approval_a",
                state = "APPROVED"
            )
        }
        assertFalse("a (session B, approvalId A) update must report no row changed", applied)
        val events = runBlocking { repository.timelineEventsForSession(sessionA.id) }
        assertEquals(
            "approval A's event in session A must stay PENDING (integrity: session+approvalId)",
            "PENDING",
            events.single { it.approvalId == "approval_a" }.approvalState
        )
        val eventsB = runBlocking { repository.timelineEventsForSession(sessionB.id) }
        assertEquals(
            "approval B's event in session B must stay PENDING too",
            "PENDING",
            eventsB.single { it.approvalId == "approval_b" }.approvalState
        )
    }

    @Test
    fun `Test D - an approval update with session A and approvalId A applies correctly`() {
        val sessionA = com.example.domain.core.session.ConversationSession(
            id = ConversationSessionId("sess_integrity_d"),
            workspaceId = activeWorkspace.id,
            title = "جلسة السلامة د"
        )
        repository.seed(sessionA)
        runBlocking {
            repository.appendTimelineEventForWorkspace(
                com.example.domain.core.session.ConversationTimelineEvent(
                    id = "apv_evt_d",
                    sessionId = sessionA.id,
                    kind = com.example.domain.core.session.TimelineEventKind.APPROVAL_BLOCK,
                    title = "موافقة صحيحة",
                    summary = "طلب صحيح",
                    approvalId = "approval_d",
                    approvalState = "PENDING"
                ),
                workspaceId = activeWorkspace.id
            )
        }

        val applied = runBlocking {
            sessionService.updateTimelineEventApprovalState(
                sessionId = sessionA.id,
                approvalId = "approval_d",
                state = "APPROVED"
            )
        }
        assertTrue("the CORRECT (session, approvalId) pairing must apply", applied)
        val events = runBlocking { repository.timelineEventsForSession(sessionA.id) }
        val event = events.single { it.approvalId == "approval_d" }
        assertEquals("APPROVED", event.approvalState)
        assertTrue(event.isSuccessful)
    }

    // ------------------------------------------------------------------
    // Harness helpers
    // ------------------------------------------------------------------

    private suspend fun createSiblingProject(): Long {
        val daoField = WorkspaceRuntimeService::class.java.getDeclaredField("projectDao")
        daoField.isAccessible = true
        val dao = daoField.get(workspaceService) as FakeProjectDaoForVm
        val entity = com.example.infrastructure.persistence.entities.ProjectEntity(
            name = "مشروع شقيق",
            description = "",
            rootPath = "",
            createdAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
            workspaceId = activeWorkspace.id
        )
        val generated = (dao.stored.keys.maxOrNull() ?: 500L) + 1
        dao.stored[generated] = entity.copy(id = generated)
        return generated
    }
}

/** Test-only additions over the shared fake (read-only views for proofs). */
private fun FakeConversationSessionRepositoryForVm.sessionsForWorkspace(
    workspaceId: String
): List<com.example.domain.core.session.ConversationSession> =
    runBlocking {
        withTimeoutOrNull(500) { observeSessions(workspaceId).first() }
    } ?: emptyList()

private fun FakeConversationSessionRepositoryForVm.appendedTurnsSessionIds(): List<ConversationSessionId> =
    appendedTurns.map { it.sessionId }
