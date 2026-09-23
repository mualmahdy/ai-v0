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
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.workspace.Workspace
import com.example.domain.ports.llm.LlmProviderPort
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.ChatEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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

/**
 * ============================================================================
 * StudioViewModelFinalClosureTest — CHAT FINAL CLOSURE regression proofs
 * ============================================================================
 *
 * The PROOF-OF-DEFECT suite for the final-closure P1 items (same harness
 * shape as StudioViewModelResidualClosureTest — mock only at the LLM port,
 * everything else REAL):
 *
 *  P1-1 session binding race: an execution accepted while session A is the
 *      candidate can NEVER slide into session B the user opened mid-execution
 *      (the reuse candidate is captured at acceptance, not read live).
 *  P1-2 fail-closed: when the durable session cannot be established, the LLM
 *      is NEVER invoked and the user sees an explicit failure state.
 *  P1-3 appendTurn result: a null appendTurn (authorization no-op) surfaces
 *      the honest persistence-failure banner — never a silent "saved".
 *  P1-4 approval persistence: a PENDING approval block is shown ONLY when
 *      its durable event was written; a failed write surfaces the explicit
 *      error; a later decision mirrors onto the approval's OWN session (even
 *      after the conversation moved on).
 *  P1-5 grantAlways false-success: with NO pending approval, the operation
 *      can NEVER end in APPROVED + "تم السماح دائماً…".
 *  §7   model persistence failure: the UI keeps the PREVIOUS selection and
 *      surfaces the error — the choice is never shown as saved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelFinalClosureTest {

    // A REAL Unconfined main: viewModelScope launches run INLINE on the
    // caller thread (deterministic synchronous state updates).
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

    /** LLM requests captured from the mock provider (fail-closed proofs). */
    private val capturedRequests = mutableListOf<LlmRequest>()

    private val approvalStore = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
    private val gate = com.example.application.governed.HumanApprovalGate(approvalStore)

    private val recordedDetachedFailures =
        java.util.Collections.synchronizedList(mutableListOf<String>())

    /**
     * CHAT FINAL CLOSURE (P1-1 determinism): the session-establishment probe
     * parks the kernel coroutine's durable-session establishment BEFORE any
     * session read — the test switches the live active session while the
     * execution is parked, then releases it.
     */
    private val establishmentGates =
        java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    private val establishedCount = java.util.concurrent.atomic.AtomicInteger(0)

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
        activeWorkspace = workspaceService.createWorkspace("مساحة الإغلاق النهائي", "اختبار")
        orchestrator.workspaceIdProvider = { activeWorkspace.id }

        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_final_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_final_provider",
                name = "Mock Final",
                providerType = "MOCK",
                defaultModel = "final-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("final-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(
                    LlmResponse(
                        text = "جواب فوري",
                        toolCalls = emptyList(),
                        usage = TokenUsage(10, 20),
                        finishReason = "STOP",
                        modelId = "final-mock-v1"
                    )
                )

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                capturedRequests += request
                emit(ExecutionEvent.ContentChunk(executionId, "جزء ", sequenceIndex = 0))
                emit(
                    ExecutionEvent.UsageBudgetUpdate(
                        executionId = executionId,
                        promptTokens = 10,
                        completionTokens = 20,
                        totalSessionTokens = 30,
                        remainingBudgetTokens = 29_970
                    )
                )
                emit(
                    ExecutionEvent.Completed(
                        executionId,
                        "الجواب الكامل",
                        totalDurationMs = 40
                    )
                )
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
            detachedPersistenceFailureSink = { message -> recordedDetachedFailures.add(message) },
            sessionEstablishmentProbe = {
                establishedCount.incrementAndGet()
                establishmentGates.poll()?.await()
            }
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

    private fun runPrompt(prompt: String) {
        viewModel.updatePromptInput(prompt)
        viewModel.executePrompt(agent = null)
    }

    /**
     * The approval-scenario stream — registered under the SAME provider id
     * as the default mock so it REPLACES the default registration (one LLM
     * resource stays visible to the decision layer; the consent path is
     * deterministic).
     */
    private val approvalStreamProvider = object : LlmProviderPort {
        override val providerId: String = "mock_final_provider"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "mock_final_provider",
            name = "Mock Final Approval",
            providerType = "MOCK",
            defaultModel = "final-mock-v1",
            isConfigured = true,
            isOnline = true,
            isLocal = true,
            supportedCapabilities = listOf("final-mock-v1")
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
            Outcome.Success(
                LlmResponse(
                    text = "جواب",
                    toolCalls = emptyList(),
                    usage = TokenUsage(1, 1),
                    finishReason = "STOP",
                    modelId = "final-mock-v1"
                )
            )

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            capturedRequests += request
            gate.requestApproval(
                executionId = executionId,
                toolName = "tool_final_consent",
                riskLevel = "HIGH",
                prompt = "طلب موافقة الإغلاق النهائي",
                justification = "سياسة الحوكمة"
            )
            emit(
                ExecutionEvent.Error(
                    executionId,
                    "HUMAN_APPROVAL_REQUIRED",
                    "يتطلب موافقة بشرية"
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // P1-1 — session binding race
    // ------------------------------------------------------------------

    @Test
    fun `P1-1 an execution accepted on session A never persists into session B opened mid-execution`() {
        // Establish session A: one COMPLETED prompt creates and binds it.
        runPrompt("الرسالة الأولى")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.Assistant } >= 1 }
        awaitUntil { repository.appendedTurns.size >= 1 }
        val sessionA = viewModel.state.value.activeSessionId
        assertNotNull("session A must be bound after the first turn", sessionA)
        val turnsInA_before = repository.appendedTurns.count { it.sessionId.value == sessionA }
        val establishmentsBefore = establishedCount.get()

        // Session B: another durable session in the SAME workspace the user
        // will open while execution A's establishment is parked.
        val sessionB = runBlocking {
            sessionService.createSession(mode = ChatMode.QUICK_CHAT, workspaceId = activeWorkspace.id)
        }
        assertTrue(sessionB.id.value != sessionA)

        // Park execution A's durable-session establishment BEFORE any session
        // read (the old defect read the LIVE activeSessionId at this point).
        val establishmentGate = CompletableDeferred<Unit>()
        establishmentGates += establishmentGate
        runPrompt("الرسالة الثانية")
        awaitUntil(5_000) { establishedCount.get() > establishmentsBefore }

        // While execution A is parked, the user OPENS session B — the live
        // activeSessionId becomes B (the old defect's trigger).
        viewModel.openSession(sessionB.id.value)
        awaitUntil { viewModel.state.value.activeSessionId == sessionB.id.value }

        // Release the establishment: execution A continues under its
        // ACCEPTANCE candidate (session A) — never the now-live session B.
        establishmentGate.complete(Unit)
        awaitUntil { repository.appendedTurns.size >= 2 }

        // The second turn landed in SESSION A (the acceptance candidate) —
        // and NOTHING from execution A leaked into session B.
        assertEquals(
            "execution A's turn must persist into its acceptance-candidate session A",
            turnsInA_before + 1,
            repository.appendedTurns.count { it.sessionId.value == sessionA }
        )
        assertEquals(
            "execution A must never persist into session B (session binding race)",
            0,
            repository.appendedTurns.count { it.sessionId.value == sessionB.id.value }
        )
    }

    // ------------------------------------------------------------------
    // P1-2 — fail closed when the durable session cannot be established
    // ------------------------------------------------------------------

    @Test
    fun `P1-2 a failed durable-session establishment refuses the execution with an explicit failure`() {
        repository.upsertFailure = IllegalStateException("قاعدة البيانات مغلقة")
        val requestsBefore = capturedRequests.size

        runPrompt("طلب لن يجد جلسة دائمة")

        // FAIL-CLOSED: no LLM request was ever made (no un-persisted run).
        awaitUntil { viewModel.state.value.errorMessage != null }
        Thread.sleep(300)
        assertEquals(
            "the LLM must never be invoked without a durable session",
            requestsBefore,
            capturedRequests.size
        )
        // The user-visible failure state is explicit.
        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.errorMessage!!.contains("الجلسة الدائمة"))
        assertFalse("the composer is freed", viewModel.state.value.isExecuting)
        // No turn was fabricated anywhere.
        assertTrue(repository.appendedTurns.isEmpty())
        // The user message keeps its honest FAILED lifecycle block.
        assertEquals(
            com.example.presentation.state.ExecutionPhase.FAILED,
            viewModel.state.value.liveExecution?.phase
        )
        // Cleanup: the injected failure must not leak into other assertions.
        repository.upsertFailure = null
    }

    // ------------------------------------------------------------------
    // P1-3 — appendTurn result is part of the state machine
    // ------------------------------------------------------------------

    @Test
    fun `P1-3 a rejected appendTurn surfaces the honest persistence-failure banner`() {
        // First turn succeeds (session created + bound).
        runPrompt("دورة تُحفظ")
        awaitUntil { repository.appendedTurns.size >= 1 }

        // Second turn: the repository refuses the append (authorization-style
        // no-op — returns false WITHOUT writing and WITHOUT throwing).
        repository.appendTurnReject = true
        runPrompt("دورة تُرفض")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.Assistant } >= 2 }
        awaitUntil { viewModel.state.value.diagnosticBanner?.contains("تعذر حفظ دورة المحادثة") == true }

        // Nothing was written for the rejected turn (the banner is not a lie).
        assertEquals(1, repository.appendedTurns.size)
        repository.appendTurnReject = false
    }

    // ------------------------------------------------------------------
    // P1-4 — approval persistence + the durable mirror's own session
    // ------------------------------------------------------------------

    @Test
    fun `P1-4a a failed approval-event write shows NO pending block and surfaces the error`() {
        // Swap the LLM provider for the consent scenario.
        registerApprovalProvider()
        repository.appendTimelineEventReject = true

        runPrompt("نفّذ الأداة الحساسة")
        awaitUntil { viewModel.state.value.errorMessage != null }
        Thread.sleep(400)

        // The durable write failed → NO inline PENDING block may appear.
        assertEquals(
            "a PENDING approval block must never masquerade as durable state",
            0,
            viewModel.state.value.timeline.count { it is ChatEntry.ApprovalBlock }
        )
        assertTrue(viewModel.state.value.errorMessage!!.contains("طلب الموافقة"))
        assertEquals(0, repository.timelineEvents.size)
        repository.appendTimelineEventReject = false
    }

    @Test
    fun `P1-4b a decision from a REOPENED conversation mirrors onto that approval's OWN session`() {
        registerApprovalProvider()
        runPrompt("نفّذ الأداة الحساسة")
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock
        val owningSessionId = viewModel.state.value.activeSessionId
        assertNotNull(owningSessionId)

        // The view is RELEASED (binding + transcript + mirror registrations)
        // and the conversation REOPENED from its durable events — the PENDING
        // block re-renders and re-registers its OWN mirror location.
        viewModel.resetTranscriptView()
        awaitUntil { viewModel.state.value.activeSessionId == null }
        viewModel.openSession(owningSessionId!!)
        awaitUntil {
            viewModel.state.value.activeSessionId == owningSessionId &&
                viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock }
        }
        val reopenedBlock = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.ApprovalBlock>()
            .first { it.approvalId == block.approvalId }
        assertEquals(ApprovalBlockState.PENDING, reopenedBlock.state)

        // The decision is made from the REOPENED conversation — the durable
        // mirror must land in the approval's OWN session (tracked scope),
        // and the block itself reflects the gate's real decision.
        viewModel.approveApproval(block.approvalId)
        awaitUntil {
            repository.timelineEvents.any {
                it.approvalId == block.approvalId && it.approvalState == ApprovalBlockState.APPROVED.name
            }
        }
        val owningEvent = repository.timelineEvents.first { it.approvalId == block.approvalId }
        assertEquals(
            "the mirror must land in the approval's OWN session",
            owningSessionId,
            owningEvent.sessionId.value
        )
        assertEquals(ApprovalBlockState.APPROVED.name, owningEvent.approvalState)
    }

    @Test
    fun `P1-4c a failed mirror update surfaces the honest stale-state error`() {
        registerApprovalProvider()
        runPrompt("نفّذ الأداة الحساسة")
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        // The durable mirror refuses to change (row-level no-op).
        repository.updateTimelineEventStateReject = true
        viewModel.approveApproval(block.approvalId)
        awaitUntil { viewModel.state.value.errorMessage?.contains("تعذر تحديث السجل الدائم") == true }

        // The GATE decision stands (the authority), the durable row did NOT
        // move, and the user was told exactly that.
        assertEquals(
            com.example.domain.core.security.governance.ApprovalResolution.APPROVED,
            runBlocking { approvalStore.find(block.approvalId) }!!.resolution
        )
        assertEquals(
            ApprovalBlockState.PENDING.name,
            repository.timelineEvents.first { it.approvalId == block.approvalId }.approvalState
        )
        repository.updateTimelineEventStateReject = false
    }

    // ------------------------------------------------------------------
    // P1-5 — grantAlways without a pending approval never claims success
    // ------------------------------------------------------------------

    @Test
    fun `P1-5 grantAlways without a pending approval never produces a false success`() {
        registerApprovalProvider()
        runPrompt("نفّذ الأداة الحساسة")
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        // Resolve the request FIRST — grantAlways will find NO pending approval.
        runBlocking { gate.approve(block.approvalId, "local-device-user") }
        awaitUntil { runBlocking { approvalStore.find(block.approvalId) }!!.resolution != com.example.domain.core.security.governance.ApprovalResolution.PENDING }

        viewModel.grantAlwaysForApproval(block.approvalId)
        awaitUntil { viewModel.state.value.errorMessage != null }
        Thread.sleep(300)

        // NO false success: no banner, an explicit error, and the block was
        // NOT flipped by the no-op grant (the gate was resolved directly —
        // outside the UI — so the view's block honestly keeps its own last
        // known state; only real UI decisions move it).
        assertNull(
            "the grant-always success banner must never appear without a real grant",
            viewModel.state.value.diagnosticBanner
        )
        assertTrue(viewModel.state.value.errorMessage!!.contains("لا يوجد طلب موافقة قائم"))
        val blockAfter = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.ApprovalBlock>()
            .first { it.approvalId == block.approvalId }
        assertEquals(
            "the no-op grant must not flip the block to a granted-always claim",
            ApprovalBlockState.PENDING,
            blockAfter.state
        )
    }

    // ------------------------------------------------------------------
    // §7 — model persistence failure
    // ------------------------------------------------------------------

    @Test
    fun `model persistence failure keeps the previous selection and surfaces the error`() {
        // Establish a durable session with a persisted model pin.
        runPrompt("رسالة لتأسيس الجلسة")
        awaitUntil { repository.appendedTurns.size >= 1 }
        val sessionId = viewModel.state.value.activeSessionId
        assertNotNull(sessionId)
        val persisted = runBlocking {
            sessionService.setSessionModel(
                sessionId = ConversationSessionId(sessionId!!),
                modelResourceId = "model_original",
                modelDisplayName = "النموذج الأصلي",
                workspaceId = activeWorkspace.id
            )
        }
        assertTrue(persisted)
        runBlocking {
            viewModel.selectModel("model_original", "النموذج الأصلي")
        }
        awaitUntil { viewModel.state.value.selectedModelResourceId == "model_original" }

        // The durable pin now FAILS (repository no-op) — the UI must keep the
        // ORIGINAL selection instead of showing the new choice as saved.
        repository.rejectModelUpdate = true
        viewModel.selectModel("model_new", "نموذج جديد")
        awaitUntil { viewModel.state.value.errorMessage != null }

        assertEquals(
            "the displayed selection must not move when persistence fails",
            "model_original",
            viewModel.state.value.selectedModelResourceId
        )
        assertEquals("النموذج الأصلي", viewModel.state.value.selectedModelDisplayName)
        assertTrue(viewModel.state.value.errorMessage!!.contains("تعذر حفظ اختيار النموذج"))
        // The durable row really kept the original pin.
        val durableSession = runBlocking {
            sessionService.getSession(ConversationSessionId(sessionId!!), activeWorkspace.id)
        }
        assertEquals("model_original", durableSession?.modelResourceId)
        repository.rejectModelUpdate = false
    }

    // ------------------------------------------------------------------
    // §6 — startNewSession scope snapshot
    // ------------------------------------------------------------------

    @Test
    fun `startNewSession binds the session to the scope captured at acceptance`() {
        // The conversation's shape at acceptance…
        viewModel.updatePromptInput("مسودة")
        runPrompt("رسالة لتثبيت الجلسة الأولى")
        awaitUntil { repository.appendedTurns.size >= 1 }
        val sessionId = viewModel.state.value.activeSessionId
        assertNotNull(sessionId)

        // …and a NEW session started in the CURRENT workspace: the created
        // row must carry THIS workspace (the acceptance capture), whatever
        // the live scope does afterwards.
        viewModel.startNewSession(agent = null)
        awaitUntil { viewModel.state.value.activeSessionId != null && viewModel.state.value.activeSessionId != sessionId }
        val newSessionId = viewModel.state.value.activeSessionId!!
        val created = runBlocking {
            sessionService.getSession(ConversationSessionId(newSessionId), activeWorkspace.id)
        }
        assertNotNull("the new session must be workspace-authorized under the acceptance workspace", created)
        assertEquals(ChatMode.QUICK_CHAT, created?.mode)
        // The fresh conversation is BOUND to it (not just the durable row).
        assertEquals(newSessionId, viewModel.state.value.activeSessionId)
        assertEquals(0, viewModel.state.value.timeline.size)
    }

    // ------------------------------------------------------------------
    // Harness helpers
    // ------------------------------------------------------------------

    /**
     * Replaces the registered LLM provider with the approval-scenario
     * stream (same provider id — the registry's REPLACE semantics make the
     * registration swap atomically).
     */
    private fun registerApprovalProvider() {
        TestResourceRegistration.registerLlmProvider(
            registry,
            approvalStreamProvider,
            capabilities = setOf(
                com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                com.example.domain.core.capability.CapabilityType.STREAMING
            ),
            isLocal = true
        )
    }
}
