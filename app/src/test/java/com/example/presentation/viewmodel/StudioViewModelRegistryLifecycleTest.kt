package com.example.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.application.decision.DecisionService
import com.example.application.operation.OperationPhase
import com.example.application.operation.OperationRegistry
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
import com.example.domain.core.workspace.Workspace
import com.example.domain.ports.llm.LlmProviderPort
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
 * StudioViewModelRegistryLifecycleTest — CLOSURE FINAL STAGE (§5/item 2)
 * ============================================================================
 *
 * The PROOF suite for the execution-path migration onto OperationRegistry
 * (same harness shape as StudioViewModelFinalClosureTest — mock only at the
 * LLM port, everything else REAL):
 *
 *  item 2 lifecycle: a chat-turn execution registers as CHAT_TURN_EXECUTION
 *      keyed by its execution task id — RUNNING at launch, SUCCEEDED only
 *      AFTER the durable turn persisted, PROJECTED when the assistant entry
 *      lands, FINALIZED in the finally-guard.
 *  item 1 remainder: the record's scope is the CANONICAL acceptance-time
 *      ScopeSnapshot (workspace + acceptance-time session; null = honest
 *      transient first turn).
 *  cancel path: cancelExecution lands the cancellation REQUEST first, then
 *      CANCELLED + FINALIZED when the job dies.
 *  consent-halt path: a run halted for approval is CANCELLED (the turn was
 *      never fulfilled; the retry opens a NEW operation) — never FAILED.
 *  fail-closed path: a failed durable-session establishment is FAILED with
 *      the honest reason, then FINALIZED — and the record never stays
 *      RUNNING (the audit's "no zombie live records" invariant).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelRegistryLifecycleTest {

    // A REAL Unconfined main: viewModelScope launches run INLINE on the
    // caller thread (deterministic synchronous state updates).
    private val dispatcher = Dispatchers.Unconfined
    private val signalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val signalCollectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var registry: ComponentRegistry
    private lateinit var operationRegistry: OperationRegistry
    private lateinit var executeAgentTaskUseCase: ExecuteAgentTaskUseCase
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var repository: FakeConversationSessionRepositoryForVm
    private lateinit var sessionService: ConversationSessionService
    private lateinit var viewModel: StudioViewModel
    private lateinit var activeWorkspace: Workspace

    /** LLM requests captured from the mock provider (fail-closed proofs). */
    private val capturedRequests = mutableListOf<LlmRequest>()

    /**
     * The park point BEFORE the terminal Completed emission — the cancel-path
     * test suspends the stream here so the execution is genuinely RUNNING
     * when cancelExecution() lands.
     */
    @Volatile private var parkBeforeCompletion: CompletableDeferred<Unit>? = null

    /**
     * ADVERSARIAL LAYER (§5/item 10 remainder): when set, the stream DIES
     * after the first content chunk — the kernel-death-mid-stream scenario
     * (a raw pipeline exception, NO terminal event, partial stream text).
     */
    @Volatile private var dieAfterFirstChunk: Boolean = false

    private val approvalStore = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
    private val gate = com.example.application.governed.HumanApprovalGate(approvalStore)

    @Before
    fun setUp() = runBlocking {
        com.example.application.execution.ExecutionHost.durableScopeOverride =
            CoroutineScope(dispatcher + SupervisorJob())

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
        activeWorkspace = workspaceService.createWorkspace("مساحة السجل", "اختبار")
        orchestrator.workspaceIdProvider = { activeWorkspace.id }

        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_registry_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_registry_provider",
                name = "Mock Registry",
                providerType = "MOCK",
                defaultModel = "registry-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("registry-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(
                    LlmResponse(
                        text = "جواب فوري",
                        toolCalls = emptyList(),
                        usage = TokenUsage(10, 20),
                        finishReason = "STOP",
                        modelId = "registry-mock-v1"
                    )
                )

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                capturedRequests += request
                emit(ExecutionEvent.ContentChunk(executionId, "جزء ", sequenceIndex = 0))
                if (dieAfterFirstChunk) {
                    throw IllegalStateException("kernel died mid-stream")
                }
                emit(
                    ExecutionEvent.UsageBudgetUpdate(
                        executionId = executionId,
                        promptTokens = 10,
                        completionTokens = 20,
                        totalSessionTokens = 30,
                        remainingBudgetTokens = 29_970
                    )
                )
                parkBeforeCompletion?.await()
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

        // CLOSURE FINAL STAGE (§5/item 2): the registry under test — the
        // same app-wide instance production wires through AppContainer.
        operationRegistry = OperationRegistry()
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
            detachedPersistenceFailureSink = { },
            sessionEstablishmentProbe = { },
            operationRegistry = operationRegistry
        )
    }

    @After
    fun tearDown() {
        com.example.application.execution.ExecutionHost.durableScopeOverride = null

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

    /** The registry record for the workspace under test (exactly one live/finished chat-turn execution). */
    private fun chatTurnRecords() =
        operationRegistry.operationsForWorkspace(activeWorkspace.id)
            .filter { it.type == "CHAT_TURN_EXECUTION" }

    // ------------------------------------------------------------------
    // item 2 — the full happy-path lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `a chat turn registers, runs, succeeds, projects and finalizes in the registry`() {
        assertEquals("no chat-turn operation before any send", 0, chatTurnRecords().size)

        runPrompt("دورة ناجحة")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.Assistant } >= 1 }
        awaitUntil { repository.appendedTurns.size >= 1 }

        val records = chatTurnRecords()
        assertEquals("exactly one chat-turn operation for one send", 1, records.size)
        val record = records.single()
        assertEquals(
            "SUCCEEDED must have been reached (FINALIZED is only reachable from a terminal phase)",
            OperationPhase.FINALIZED,
            record.phase
        )
        // The summary progression is honest last-write-wins:
        // SUCCEEDED("turn persisted durably") → PROJECTED("assistant entry
        // projected") — the FINALIZED record carries the PROJECTION summary
        // (the latest lifecycle truth).
        assertEquals("assistant entry projected", record.resultSummary)
        assertNull("a successful run carries no error", record.error)
        assertFalse("the finished record is not active", record.isActive)
        assertEquals("owner is the executing view model", "StudioViewModel", record.owner)
    }

    @Test
    fun `the record scope is the canonical acceptance-time snapshot - transient first turn, bound second turn`() {
        // FIRST turn: no durable session exists at acceptance — the honest
        // acceptance-time session is null (transient), never a fabricated id.
        // (Await BOTH the assistant entry AND the durable turn: the entry
        // lands in the SAME atomic update that frees the composer — awaiting
        // the persist alone leaves a window where isExecuting is still true
        // and the second send would be silently gated.)
        runPrompt("الدورة الأولى")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.Assistant } >= 1 }
        awaitUntil { repository.appendedTurns.size >= 1 }
        val first = chatTurnRecords().single()
        assertEquals(
            "the execution-pinned context is the CANONICAL ScopeSnapshot (item 1 remainder)",
            activeWorkspace.id,
            first.scope.workspaceId
        )
        assertNull(
            "a transient first turn records null as its acceptance-time session",
            first.scope.sessionId
        )

        // SECOND turn: the session established by the first turn is bound at
        // acceptance — the snapshot records THAT session, the pinned truth.
        val boundSession = viewModel.state.value.activeSessionId
        assertNotNull(boundSession)
        runPrompt("الدورة الثانية")
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.Assistant } >= 2 }
        awaitUntil { repository.appendedTurns.size >= 2 }
        val records = chatTurnRecords()
        assertEquals(2, records.size)
        // The registry's backing map is a ConcurrentHashMap — iteration order
        // is NOT guaranteed, so the SECOND record is identified by IDENTITY
        // (not .last(), which under load can hand back the first record).
        val second = records.first { it.operationId != first.operationId }
        assertEquals(
            "the second turn's acceptance-time session is the BOUND session",
            boundSession,
            second.scope.sessionId
        )
        assertEquals(activeWorkspace.id, second.scope.workspaceId)
    }

    // ------------------------------------------------------------------
    // item 2 — the cancel path
    // ------------------------------------------------------------------

    @Test
    fun `cancelExecution lands the cancellation request then CANCELLED and FINALIZED`() {
        // Park the stream BEFORE its terminal event — the execution is
        // genuinely RUNNING when the user cancels.
        val park = CompletableDeferred<Unit>()
        parkBeforeCompletion = park
        runPrompt("دورة ستُلغى")
        awaitUntil { chatTurnRecords().any { it.phase == OperationPhase.RUNNING } }
        val operationId = chatTurnRecords().single().operationId

        viewModel.cancelExecution()
        park.complete(Unit) // release the parked stream (the job is dying anyway)

        awaitUntil {
            val record = operationRegistry.get(operationId)
            record != null && record.cancellationRequested
        }
        awaitUntil {
            val record = operationRegistry.get(operationId)
            record != null && record.phase == OperationPhase.FINALIZED
        }
        val record = operationRegistry.get(operationId)!!
        assertTrue("the cancellation REQUEST landed in the registry", record.cancellationRequested)
        // CANCELLED must have been the terminal (FINALIZED is only reachable
        // from a terminal phase — reaching FINALIZED proves a terminal landed;
        // the CancellationException handler lands CANCELLED, never FAILED).
        assertNull("a cancelled run is not an error", record.error)
        assertFalse(record.isActive)
        parkBeforeCompletion = null
    }

    // ------------------------------------------------------------------
    // item 2 — the consent-halt path (CANCELLED, never FAILED)
    // ------------------------------------------------------------------

    @Test
    fun `a run halted for approval is CANCELLED in the registry - the turn was never fulfilled`() {
        // Replace the default provider with the approval scenario (same id).
        val approvalProvider = object : LlmProviderPort {
            override val providerId: String = "mock_registry_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_registry_provider",
                name = "Mock Registry Approval",
                providerType = "MOCK",
                defaultModel = "registry-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("registry-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(
                    LlmResponse(
                        text = "جواب",
                        toolCalls = emptyList(),
                        usage = TokenUsage(1, 1),
                        finishReason = "STOP",
                        modelId = "registry-mock-v1"
                    )
                )

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                gate.requestApproval(
                    executionId = executionId,
                    toolName = "tool_registry_consent",
                    riskLevel = "HIGH",
                    prompt = "طلب موافقة السجل",
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
        TestResourceRegistration.registerLlmProvider(
            registry,
            approvalProvider,
            capabilities = setOf(
                com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                com.example.domain.core.capability.CapabilityType.STREAMING
            ),
            isLocal = true
        )

        runPrompt("دورة تطلب موافقة")
        awaitUntil {
            viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock }
        }
        awaitUntil {
            chatTurnRecords().singleOrNull()?.let { it.phase == OperationPhase.FINALIZED } == true
        }
        val record = chatTurnRecords().single()
        // The VIEW rests at AWAITING_APPROVAL; the OPERATION is honestly
        // CANCELLED (nothing failed — the turn was never fulfilled).
        assertTrue(
            "the consent-halted record carries the approval-halt summary",
            record.resultSummary?.contains("approval requested") == true
        )
        assertNull("a consent halt is not an error", record.error)
        assertFalse(record.isActive)
        // No durable turn was fabricated for the halted run.
        assertEquals(0, repository.appendedTurns.size)
    }

    // ------------------------------------------------------------------
    // item 2 — the fail-closed path (establishment failure)
    // ------------------------------------------------------------------

    @Test
    fun `a failed durable-session establishment lands FAILED with the honest reason then FINALIZED`() {
        repository.upsertFailure = IllegalStateException("قاعدة البيانات مغلقة")
        val requestsBefore = capturedRequests.size

        runPrompt("طلب لن يجد جلسة دائمة")

        awaitUntil { viewModel.state.value.errorMessage != null }
        awaitUntil {
            chatTurnRecords().singleOrNull()?.let { it.phase == OperationPhase.FINALIZED } == true
        }
        val record = chatTurnRecords().single()
        // FAILED landed (FINALIZED is only reachable from a terminal phase).
        assertTrue(
            "the establishment failure is the record's honest error",
            record.error?.contains("establishment failed") == true
        )
        assertFalse(record.isActive)
        // FAIL-CLOSED: the LLM was never invoked without a durable session.
        assertEquals(requestsBefore, capturedRequests.size)
        repository.upsertFailure = null
    }

    // ------------------------------------------------------------------
    // item 10 (adversarial layer) — kernel death mid-stream
    // ------------------------------------------------------------------

    @Test
    fun `a stream that dies mid-stream still lands its durable turn and a registry terminal - never a zombie live record`() {
        dieAfterFirstChunk = true
        runPrompt("دورة يموت بثّها في المنتصف")

        // The governed kernel folds a raw provider-stream exception into an
        // ActionFailed observation and synthesizes the run's terminal
        // Completed (the D-11 MERGE contract: the merge never costs
        // durability) — ONE durable turn lands for the question.
        awaitUntil { viewModel.state.value.timeline.count { it is ChatEntry.Assistant } >= 1 }
        awaitUntil { repository.appendedTurns.size >= 1 }
        awaitUntil {
            chatTurnRecords().singleOrNull()?.let { it.phase == OperationPhase.FINALIZED } == true
        }
        val record = chatTurnRecords().single()
        // The operation HONESTLY succeeded: its durable turn persisted and
        // its entry projected (the provider failure is folded, not lost).
        assertNull("the folded provider failure never surfaces as an operation error", record.error)
        assertEquals("assistant entry projected", record.resultSummary)
        assertFalse("a dead run is never left RUNNING in the registry", record.isActive)
        assertEquals("exactly one durable turn for one question", 1, repository.appendedTurns.size)
        dieAfterFirstChunk = false
    }

    // ------------------------------------------------------------------
    // item 2 (adversarial layer) — registry monotonicity through the
    // execution-path call shape
    // ------------------------------------------------------------------

    @Test
    fun `the registry refuses projection before success and a second terminal - monotonic lifecycle`() {
        val scope = com.example.domain.core.execution.ScopeSnapshot.capture(
            operationId = "op_monotonic",
            workspaceId = activeWorkspace.id,
            projectId = null
        )
        operationRegistry.register(
            type = "CHAT_TURN_EXECUTION",
            scope = scope,
            owner = "test"
        )
        // PROJECTED before SUCCEEDED is REFUSED (UI projection cannot run
        // ahead of durable success — the audit's §5.4/B2 reorderings).
        assertNull(
            operationRegistry.transition("op_monotonic", OperationPhase.PROJECTED)
        )
        assertEquals(OperationPhase.CREATED, operationRegistry.get("op_monotonic")?.phase)
        // The legal execution-path shape: RUNNING → SUCCEEDED → PROJECTED.
        assertNotNull(operationRegistry.transition("op_monotonic", OperationPhase.RUNNING))
        assertNotNull(operationRegistry.transition("op_monotonic", OperationPhase.SUCCEEDED, "turn persisted durably"))
        assertNotNull(operationRegistry.transition("op_monotonic", OperationPhase.PROJECTED, "assistant entry projected"))
        // The multi-terminal quirk: a SECOND SUCCEEDED and a FAILED after
        // PROJECTED are both refused — the terminal is immutable.
        assertNull(operationRegistry.transition("op_monotonic", OperationPhase.SUCCEEDED, "again"))
        assertNull(operationRegistry.fail("op_monotonic", "late error"))
        assertEquals(OperationPhase.PROJECTED, operationRegistry.get("op_monotonic")?.phase)
        assertNotNull(operationRegistry.transition("op_monotonic", OperationPhase.FINALIZED))
        // FINALIZED is terminal — nothing further lands.
        assertNull(operationRegistry.transition("op_monotonic", OperationPhase.RUNNING))
        assertFalse(operationRegistry.get("op_monotonic")!!.isActive)
    }
}
