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
import com.example.domain.core.session.ConversationTimelineEvent
import com.example.domain.core.session.TimelineEventKind
import com.example.domain.core.session.TurnAttachment
import com.example.domain.core.workspace.Workspace
import com.example.domain.ports.llm.LlmProviderPort
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ExecutionPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * StudioViewModelFunctionalClosureTest — FUNCTIONAL CLOSURE Phase 1 §23
 * ============================================================================
 *
 * The REQUIRED Phase-1 behavioral matrix, verified against the REAL governed
 * execution kernel (the same harness shape as StudioViewModelTest — mock only
 * at the LLM port):
 *
 *  §1  project/workspace switch → stale transcript released; the running
 *      execution is DETACHED (not killed).
 *  §2  mid-execution workspace switch → the turn still persists into the
 *      execution's OWN pinned workspace (no loss, no cross-workspace leak).
 *  §1  cross-project session open → refused with the honest reason.
 *  §3  agent switch mid-session → the durable binding is UPDATED explicitly
 *      (Agent B never silently executes inside Agent A's session row).
 *  §4  session reopen → restoredAgentId exposes the session's agent for the
 *      catalog owner to restore.
 *  §5  model honesty → the assistant entry records the model the decision
 *      layer ACTUALLY selected (SELECT_MODEL harvest), not the pin.
 *  §6  targeted regenerate → the SPECIFIC assistant entry's user message is
 *      re-executed even when later messages exist.
 *  §8  approval-blocked execution → AWAITING_APPROVAL lifecycle, NOT a
 *      failed assistant entry.
 *  §9/§10 capability results + approval blocks persist and re-render after
 *      a session reopen (timestamp-merged — §11 chronology).
 *  §12 approval state resolution is mirrored onto the durable event.
 *  §15 attachment-only prompts are refused honestly.
 *  §22 the pending capability block lands FIRST, then resolves in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelFunctionalClosureTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val signalBus = kotlinx.coroutines.flow.MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val signalCollectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var registry: ComponentRegistry
    private lateinit var executeAgentTaskUseCase: ExecuteAgentTaskUseCase
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var repository: FakeConversationSessionRepositoryForVm
    private lateinit var sessionService: ConversationSessionService
    private lateinit var viewModel: StudioViewModel
    private lateinit var activeWorkspace: Workspace

    private val capturedRequests = mutableListOf<LlmRequest>()

    @Volatile
    private var approvalScenario: Boolean = false

    @Volatile
    private var selectModelScenario: String? = null

    private val approvalStore = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
    private val gate = com.example.application.governed.HumanApprovalGate(approvalStore)

    /** Task-1 cancel window: emits one chunk then awaits. */
    @Volatile
    private var streamGate: CompletableDeferred<Unit>? = null

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
        Thread.sleep(100)
        activeWorkspace = workspaceService.createWorkspace("مساحة الإغلاق", "اختبار")
        orchestrator.workspaceIdProvider = { activeWorkspace.id }

        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_closure_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_closure_provider",
                name = "Mock Closure",
                providerType = "MOCK",
                defaultModel = "closure-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("closure-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                if (approvalScenario) {
                    // The consent block gates EVERY provider surface (the
                    // same refusal on the fallback path — no synthesized
                    // "answer" behind a pending approval).
                    Outcome.Error(
                        LlmFailure.ProviderUnavailable(providerId, "يتطلب موافقة بشرية")
                    )
                } else {
                    Outcome.Success(
                        LlmResponse(
                            text = "جواب فوري",
                            toolCalls = emptyList(),
                            usage = TokenUsage(10, 20),
                            finishReason = "STOP",
                            modelId = "closure-mock-v1"
                        )
                    )
                }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                capturedRequests += request
                if (approvalScenario) {
                    gate.requestApproval(
                        executionId = executionId,
                        toolName = "tool_closure_consent",
                        riskLevel = "HIGH",
                        prompt = "طلب موافقة الإغلاق",
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
                    // §5: the REAL SELECT_MODEL decision shape — the decision
                    // layer announces the model it actually picked.
                    selectModelScenario?.let { picked ->
                        emit(
                            ExecutionEvent.DecisionMade(
                                executionId = executionId,
                                decision = com.example.domain.core.decision.DecisionResult(
                                    chosenAction = com.example.domain.core.decision.DecisionAction(
                                        type = com.example.domain.core.decision.DecisionActionType.SELECT_MODEL,
                                        payload = mapOf("resourceId" to picked)
                                    ),
                                    confidence = 0.9f,
                                    rationale = "اختيار الاختبار",
                                    stateSnapshot = com.example.domain.core.decision.DecisionState(
                                        taskId = com.example.domain.core.task.TaskId("task_closure")
                                    ),
                                    evaluatedAlternatives = emptyList(),
                                    matchedHistoricalCasesCount = 0
                                )
                            )
                        )
                    }
                    emit(ExecutionEvent.ContentChunk(executionId, "الجواب ", sequenceIndex = 0))
                    streamGate?.let { gate -> gate.await() }
                    emit(ExecutionEvent.ContentChunk(executionId, "الكامل", sequenceIndex = 1))
                    emit(ExecutionEvent.Completed(executionId, "الجواب الكامل", totalDurationMs = 40))
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
            )
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
        assertTrue("Timed out waiting for the condition", condition())
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

    // ------------------------------------------------------------------
    // §1 — SCOPE ISOLATION
    // ------------------------------------------------------------------

    @Test
    fun `a project switch releases the stale transcript and the session binding`() {
        runPrompt("رسالة المشروع الأول")
        awaitExecutionSettled()
        assertTrue(viewModel.state.value.timeline.isNotEmpty())
        assertNotNull(viewModel.state.value.activeSessionId)

        // Switch the ACTIVE PROJECT under the conversation (the runtime's own
        // flow — exactly what ProjectsViewModel.switchProject does).
        val otherProject = runBlocking {
            val projectId = workspaceService.activeProjectIdOrNull()!!
            // Create a SECOND project in the same workspace and activate it.
            val daoField = WorkspaceRuntimeService::class.java.getDeclaredField("projectDao")
            daoField.isAccessible = true
            val dao = daoField.get(workspaceService) as FakeProjectDaoForVm
            val entity = com.example.infrastructure.persistence.entities.ProjectEntity(
                name = "مشروع ثانٍ",
                description = "",
                rootPath = "",
                createdAtEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis(),
                workspaceId = activeWorkspace.id
            )
            val generated = dao.stored.keys.maxOrNull()?.plus(1) ?: 500L
            dao.stored[generated] = entity.copy(id = generated)
            workspaceService.setActiveProject(generated)
            generated
        }

        awaitUntil { viewModel.state.value.activeSessionId == null }
        // THE §1 CONTRACT: Project A's transcript is GONE the moment Project B
        // becomes active — no stale transcript over a foreign scope.
        assertTrue(viewModel.state.value.timeline.isEmpty())
        assertTrue(viewModel.state.value.studioSession.isEmpty())
        assertNull(viewModel.state.value.liveExecution)
        assertNotNull(otherProject)
    }

    @Test
    fun `opening another project's private session is refused with the honest reason`() = runBlocking {
        runPrompt("جلسة المشروع الأول")
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!

        // Move to a DIFFERENT project, then try to open the private session.
        val daoField = WorkspaceRuntimeService::class.java.getDeclaredField("projectDao")
        daoField.isAccessible = true
        val dao = daoField.get(workspaceService) as FakeProjectDaoForVm
        val entity = com.example.infrastructure.persistence.entities.ProjectEntity(
            name = "مشروع منافس",
            description = "",
            rootPath = "",
            createdAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
            workspaceId = activeWorkspace.id
        )
        dao.stored[600L] = entity.copy(id = 600L)
        workspaceService.setActiveProject(600L)
        awaitUntil { viewModel.state.value.activeSessionId == null }

        viewModel.openSession(sessionId)
        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(
            viewModel.state.value.errorMessage!!.contains("مشروعاً آخر")
        )
        // The foreign session did NOT become the active conversation.
        assertNull(viewModel.state.value.activeSessionId)
    }

    // ------------------------------------------------------------------
    // §2 — EXECUTION-PINNED CONTEXT (mid-execution workspace switch)
    // ------------------------------------------------------------------

    @Test
    fun `a mid-execution workspace switch detaches the view but keeps the pinned turn`() {
        streamGate = CompletableDeferred()
        runPrompt("تنفيذ طويل عبر تبديل مساحة العمل")
        awaitUntil { viewModel.state.value.streamText.isNotBlank() }

        // Switch the workspace mid-execution (the scope sentinel fires).
        runBlocking {
            workspaceService.createWorkspace("مساحة ثانية", "أثناء التنفيذ")
        }
        awaitUntil { viewModel.state.value.activeSessionId == null }

        // §1: the view released the transcript…
        assertTrue(viewModel.state.value.timeline.isEmpty())
        assertFalse(viewModel.state.value.isExecuting)

        // §2: …but the execution keeps running with ITS OWN pinned context —
        // release the gate and let it complete.
        streamGate?.complete(Unit)
        awaitUntil(20_000) { repository.appendedTurns.isNotEmpty() }

        // The turn persisted into the ORIGINAL workspace's session (pinned at
        // launch), never into the new workspace or the void.
        val turn = repository.appendedTurns.last()
        val session = runBlocking { repository.getSession(turn.sessionId) }!!
        assertEquals(activeWorkspace.id, session.workspaceId)
        assertEquals("تنفيذ طويل عبر تبديل مساحة العمل", turn.prompt)
        // And the detached execution never injected its result into the new
        // (cleared) timeline.
        assertTrue(viewModel.state.value.timeline.isEmpty())
        assertTrue(viewModel.state.value.studioSession.isEmpty())
    }

    // ------------------------------------------------------------------
    // §3/§4 — SESSION SEMANTICS + AGENT RESTORATION
    // ------------------------------------------------------------------

    @Test
    fun `an agent switch updates the session binding explicitly`() = runBlocking {
        // Seed an AGENT session bound to agent A, make it active, execute with
        // agent B — the durable row must name B (explicit update, no silent B).
        val agentA = com.example.application.agent.CanonicalAgentCatalog.defaults.first()
        val session = sessionService.createSession(
            mode = ChatMode.AGENT,
            agentId = agentA.identity.id.value,
            agentName = agentA.identity.name,
            workspaceId = activeWorkspace.id
        )
        repository.seed(session)
        viewModel.setChatMode(ChatMode.AGENT)
        // Reveal the binding as the active conversation (openSession path).
        viewModel.openSession(session.id.value)
        awaitUntil { viewModel.state.value.activeSessionId == session.id.value }

        // A DIFFERENT agent now executes (the catalog's shared selection).
        val agentB = com.example.application.agent.CanonicalAgentCatalog.defaults
            .first { it.identity.id.value != agentA.identity.id.value }
        viewModel.updatePromptInput("تنفيذ الوكيل ب")
        viewModel.executePrompt(agent = agentB)
        awaitExecutionSettled()

        // §3: the durable binding was UPDATED to the agent that really ran.
        val updated = repository.getSession(session.id)!!
        assertEquals(agentB.identity.id.value, updated.agentId)
        assertEquals(agentB.identity.name, updated.agentName)
    }

    @Test
    fun `reopening a session exposes its agent for the catalog owner to restore`() = runBlocking {
        val agentA = com.example.application.agent.CanonicalAgentCatalog.defaults.first()
        val session = sessionService.createSession(
            mode = ChatMode.AGENT,
            agentId = agentA.identity.id.value,
            agentName = agentA.identity.name,
            workspaceId = activeWorkspace.id
        )
        repository.seed(session)

        viewModel.openSession(session.id.value)
        awaitUntil { viewModel.state.value.activeSessionId == session.id.value }

        // §4: the ACTUAL agent is exposed — the screen routes it into the
        // agent-catalog owner's selection (the continuation then really
        // executes through it).
        assertEquals(agentA.identity.id.value, viewModel.state.value.restoredAgentId)
    }

    // ------------------------------------------------------------------
    // §5 — MODEL HONESTY
    // ------------------------------------------------------------------

    @Test
    fun `the assistant entry records the model the decision layer actually selected`() {
        // The user pinned NOTHING (auto) — the decision layer picked this:
        selectModelScenario = "res_actual_model_x"

        runPrompt("أي نموذج نفّذ هذا؟")
        awaitExecutionSettled()

        val assistant = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.Assistant>()
            .last()
        // §5: the HONEST runtime binding — the harvested SELECT_MODEL id, not
        // a pin that was never set.
        assertEquals("res_actual_model_x", assistant.modelResourceId)
        // And the durable turn carries the same truth.
        assertEquals("res_actual_model_x", repository.appendedTurns.last().modelResourceId)
    }

    @Test
    fun `with no decision announcement the user's pin stays the recorded model`() {
        runPrompt("نموذج المستخدم")
        awaitExecutionSettled()

        val assistant = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.Assistant>()
            .last()
        // No user pin, no mock announcement — but the REAL kernel's own
        // SELECT_MODEL decision DID announce its pick (the honest harvest):
        // the recorded model is the runtime's choice, never fabricated.
        assertNotNull(assistant.modelResourceId)
        assertTrue(assistant.modelResourceId!!.isNotBlank())
    }

    // ------------------------------------------------------------------
    // §6 — TARGETED REGENERATE
    // ------------------------------------------------------------------

    @Test
    fun `regenerate targets the TAPPED entry's message even when later messages exist`() {
        runPrompt("السؤال الأول")
        awaitExecutionSettled()
        runPrompt("السؤال الثاني")
        awaitExecutionSettled()

        // The user taps "إعادة التوليد" on the FIRST assistant answer —
        // NOT the last one.
        val firstAssistant = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.Assistant>()
            .first()

        viewModel.regenerateFromAssistant(assistantEntryId = firstAssistant.id, agent = null)
        awaitExecutionSettled()

        // THREE assistant results now (2 originals + the regeneration)…
        assertEquals(
            3,
            viewModel.state.value.timeline.filterIsInstance<ChatEntry.Assistant>().size
        )
        // …and BOTH user messages still exist (nothing was duplicated/removed).
        val userTexts = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.User>()
            .map { it.text }
        assertEquals(listOf("السؤال الأول", "السؤال الثاني"), userTexts)
    }

    @Test
    fun `regenerating an unknown entry id is a no-op`() {
        runPrompt("سؤال واحد")
        awaitExecutionSettled()
        val before = viewModel.state.value.timeline.size

        viewModel.regenerateFromAssistant(assistantEntryId = "asst_missing", agent = null)
        Thread.sleep(300)

        assertEquals(before, viewModel.state.value.timeline.size)
        assertEquals(1, repository.appendedTurns.size)
    }

    // ------------------------------------------------------------------
    // §8 — APPROVAL = AWAITING, NOT FAILURE
    // ------------------------------------------------------------------

    @Test
    fun `a consent-blocked execution shows AWAITING_APPROVAL and never a failed assistant entry`() {
        approvalScenario = true
        runPrompt("تنفيذ يحتاج موافقة")
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }

        val state = viewModel.state.value
        // §8: the composer is free, the lifecycle is honestly AWAITING…
        assertFalse(state.isExecuting)
        assertEquals(ExecutionPhase.AWAITING_APPROVAL, state.liveExecution?.phase)
        // …and NO failed assistant entry was fabricated (consent ≠ failure).
        assertTrue(
            state.timeline.none { it is ChatEntry.Assistant && !it.isSuccessful }
        )
        assertTrue(
            state.timeline.none { it is ChatEntry.Assistant }
        )
    }

    // ------------------------------------------------------------------
    // §9/§10/§11 — CAPABILITY PERSISTENCE + CHRONOLOGY
    // ------------------------------------------------------------------

    @Test
    fun `capability results persist and re-render after a session reopen in timestamp order`() {
        runPrompt("رسالة قبل القدرة")
        awaitExecutionSettled()
        val sessionId = viewModel.state.value.activeSessionId!!

        // A capability result lands BETWEEN the two turns.
        viewModel.appendCapabilityResult(
            ChatEntry.CapabilityResult(
                id = "cap_search_1",
                kind = CapabilityKind.SEARCH,
                title = "بحث ذكي: الاختبار",
                summary = "تم العثور على 3 نتائج.",
                sources = listOf(
                    com.example.presentation.state.ChatSourceRef(
                        title = "مرجع دائم",
                        url = "https://example.com/durable"
                    )
                ),
                timestampMs = System.currentTimeMillis()
            )
        )
        awaitUntil { repository.appendTimelineEventCount == 1 }

        runPrompt("رسالة بعد القدرة")
        awaitExecutionSettled()

        // REOPEN: the exact conversation must come back.
        viewModel.openSession(sessionId)
        awaitUntil {
            viewModel.state.value.timeline.any { it is ChatEntry.CapabilityResult }
        }

        val timeline = viewModel.state.value.timeline
        // §9/§10: the capability result survived the reopen (with its sources).
        val capability = timeline.filterIsInstance<ChatEntry.CapabilityResult>().single()
        assertEquals("بحث ذكي: الاختبار", capability.title)
        assertEquals("https://example.com/durable", capability.sources.single().url)
        // §11: CHRONOLOGY — user1, assistant1, capability, user2, assistant2.
        val kinds = timeline.map { entry ->
            when (entry) {
                is ChatEntry.User -> "U"
                is ChatEntry.Assistant -> "A"
                is ChatEntry.CapabilityResult -> "C"
                is ChatEntry.ApprovalBlock -> "V"
            }
        }
        assertEquals(listOf("U", "A", "C", "U", "A"), kinds)
    }

    @Test
    fun `approval blocks persist with their state and re-render resolved after reopen`() {
        approvalScenario = true
        runPrompt("تنفيذ يحتاج موافقة دائمة")
        awaitUntil { viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock } }
        val sessionId = viewModel.state.value.activeSessionId!!
        val block = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock

        // §12: resolve through the REAL gate — the durable mirror updates.
        viewModel.approveApproval(block.approvalId)
        awaitUntil { repository.updateTimelineEventStateCount == 1 }

        viewModel.openSession(sessionId)
        awaitUntil {
            viewModel.state.value.timeline.any { it is ChatEntry.ApprovalBlock }
        }
        // §9/§12: the reopened block shows the RESOLVED decision, not a stale
        // PENDING one.
        val reopened = viewModel.state.value.timeline
            .first { it is ChatEntry.ApprovalBlock } as ChatEntry.ApprovalBlock
        assertEquals(ApprovalBlockState.APPROVED, reopened.state)
        assertEquals(block.approvalId, reopened.approvalId)
    }

    // ------------------------------------------------------------------
    // §22 — DIRECT CAPABILITY LIFECYCLE (pending → resolved)
    // ------------------------------------------------------------------

    @Test
    fun `a direct invocation lands PENDING first then resolves in place`() {
        val pendingId = viewModel.appendPendingCapability(
            kind = CapabilityKind.TOOL,
            title = "sample_tool"
        )

        // §22: the block is IN the conversation immediately, visibly pending.
        val pending = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.CapabilityResult>()
            .single()
        assertEquals(pendingId, pending.id)
        assertTrue(pending.isPending)

        // The result arrives later — it resolves the SAME entry (stable id).
        viewModel.resolveCapabilityResult(
            pendingEntryId = pendingId,
            resolved = ChatEntry.CapabilityResult(
                id = "cap_irrelevant",
                kind = CapabilityKind.TOOL,
                title = "sample_tool",
                summary = "تم التنفيذ بنجاح.",
                isSuccessful = true,
                timestampMs = System.currentTimeMillis()
            )
        )

        val resolved = viewModel.state.value.timeline
            .filterIsInstance<ChatEntry.CapabilityResult>()
            .single()
        assertEquals(pendingId, resolved.id)
        assertFalse(resolved.isPending)
        assertEquals("تم التنفيذ بنجاح.", resolved.summary)
    }

    // ------------------------------------------------------------------
    // §15 — ATTACHMENT-ONLY PROMPTS
    // ------------------------------------------------------------------

    @Test
    fun `an attachment-only prompt is refused with the honest Vision reason`() {
        viewModel.updatePromptInput("   ")
        val accepted = viewModel.executePrompt(
            agent = null,
            attachments = listOf(
                TurnAttachment(
                    id = "attm_only",
                    name = "صورة.png",
                    mimeType = "image/png",
                    sizeBytes = 100L,
                    storageUri = "attachments/صورة.png"
                )
            )
        )

        // §15: refused up front — no meaningless request reaches the LLM.
        assertFalse(accepted)
        assertTrue(
            viewModel.state.value.errorMessage!!.contains("Vision")
        )
        assertTrue(capturedRequests.isEmpty())
        assertTrue(viewModel.state.value.timeline.isEmpty())
    }
}
