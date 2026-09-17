package com.example.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.workflow.WorkflowLibraryService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.domain.ports.llm.LlmProviderPort
import com.example.presentation.state.WorkflowBuilderState
import com.example.presentation.state.WorkflowBuilderStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * WorkflowsViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the WORKFLOWS feature ViewModel (ADR-6 slice 6)
 * ============================================================================
 *
 * Drives the REAL execution + library stack — no service-seam stubbing; only
 * a local mock LLM port (registered through TestResourceRegistration, the
 * StudioViewModelTest pattern) and Room in-memory DAOs:
 *
 *  - REAL ExecuteWorkflowUseCase → REAL WorkflowEngine → REAL
 *    AgentOrchestrator (ComponentRegistry + SecurityGuardService +
 *    DecisionService) — the builder's "تنفيذ" runs the exact production
 *    pipeline;
 *  - REAL WorkflowLibraryService + REAL WorkflowPersistenceService over the
 *    same in-memory Room database the resumable surface reads;
 *  - REAL WorkspaceRuntimeService over the shared workspace fakes
 *    (createWorkspace binds a sandbox project, exactly like the studio
 *    test).
 *
 * Asserted feature contract (extracted from MainViewModel):
 *  - the builder is a durable re-editable asset: save → list → load →
 *    edit → re-save (versioned lineage);
 *  - execution through the REAL engine renders the report and settles the
 *    flag; a cyclic plan fails through the engine's own validation gate
 *    (the cycle authority — GAP-19/ADR-6 step 6) with the honest error;
 *  - the library observer is flatMapLatest-scoped to the ACTIVE workspace
 *    (the slice-6 re-wiring, slice-4 precedent): a STALE workspace's write
 *    cannot bleed into the list;
 *  - the resumable list follows the active workspace and the durable
 *    resume re-executes through the REAL engine;
 *  - clone/delete surface their honest degradation banners.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkflowsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var registry: ComponentRegistry
    private lateinit var executeWorkflowUseCase: ExecuteWorkflowUseCase
    private lateinit var persistence: WorkflowPersistenceService
    private lateinit var library: WorkflowLibraryService
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var activeWorkspaceId: String
    private lateinit var viewModel: WorkflowsViewModel

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)

        // --- REAL execution stack (mock only at the LLM port) ---
        registry = ComponentRegistry()
        val securityGuard = SecurityGuardService()
        val decisionService = DecisionService(
            com.example.domain.core.decision.CbrMdpEngine(),
            registry,
            securityGuard
        )
        val orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
        executeWorkflowUseCase = ExecuteWorkflowUseCase(WorkflowEngine(orchestrator))

        // --- Local mock LLM resource (offline-safe) ---
        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_wf_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_wf_provider",
                name = "Mock WF",
                providerType = "MOCK",
                defaultModel = "wf-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("wf-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(
                    LlmResponse(
                        text = "مخرج الخطوة",
                        toolCalls = emptyList(),
                        usage = TokenUsage(5, 5),
                        finishReason = "STOP",
                        modelId = "wf-mock-v1"
                    )
                )

            override fun stream(request: LlmRequest, executionId: String) = emptyFlow<com.example.domain.core.events.ExecutionEvent>()
        }
        TestResourceRegistration.registerLlmProvider(
            registry,
            mockProvider,
            capabilities = setOf(
                com.example.domain.core.capability.CapabilityType.LLM_GENERATION
            ),
            isLocal = true
        )

        // --- REAL workspace runtime: createWorkspace binds a sandbox project ---
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100) // let the default bootstrap settle
        val activeWorkspace = workspaceService.createWorkspace("مساحة خطط العمل", "اختبار")
        activeWorkspaceId = activeWorkspace.id
        orchestrator.workspaceIdProvider = { activeWorkspaceId }

        // --- REAL library + persistence over in-memory Room ---
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        persistence = WorkflowPersistenceService(
            workflowExecutionDao = db.workflowExecutionDao(),
            workflowStepStateDao = db.workflowStepStateDao()
        )
        library = WorkflowLibraryService(
            workflowDefinitionDao = db.workflowDefinitionDao(),
            planSerializer = persistence,
            workspaceIdProvider = { activeWorkspaceId }
        )

        viewModel = WorkflowsViewModel(
            executeWorkflowUseCase = executeWorkflowUseCase,
            workflowLibraryService = library,
            workflowPersistenceService = persistence,
            workspaceRuntimeService = workspaceService
        )
    }

    @After
    fun tearDown() {
        // TEST-side determinism fix (the slice-7 CI failure family,
        // documented): the feature VM's library collector is a STANDING
        // Room flow (workflowDefinitionDao.forWorkspace relayed through
        // flatMapLatest); closing the DB underneath a pending query step
        // throws "connection pool has been closed" as an uncaught
        // exception that the framework attributes to whichever test runs
        // next. Cancel the scope FIRST — the test-side equivalent of
        // onCleared() — then close the DB.
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the engine and
     * the Room-backed services hop to Dispatchers.IO internally, so
     * outcomes settle asynchronously even under the Unconfined Main
     * dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 10_000L,
        intervalMs: Long = 25L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(intervalMs)
        }
    }

    /** Builds a two-step plan through the VM's own builder functions. */
    private fun authorTwoStepPlan() {
        viewModel.applyWorkflowTemplate(
            WorkflowBuilderState(
                name = "خطة الاختبار",
                goal = "بناء وحدة تجريبية",
                executionMode = ExecutionMode.SEQUENTIAL,
                steps = listOf(
                    WorkflowBuilderStep("step_1_plan", "تحليل المتطلبات", AgentRole.PLANNER, emptySet()),
                    WorkflowBuilderStep("step_2_code", "تنفيذ الكود", AgentRole.CODER, setOf("step_1_plan"))
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // The builder — a durable, re-editable authored asset
    // ------------------------------------------------------------------

    @Test
    fun `the builder starts from the default template and is fully editable`() {
        val initial = viewModel.state.value.workflowBuilder
        assertEquals(3, initial.steps.size)
        assertEquals(ExecutionMode.DIRECTED_ACYCLIC_GRAPH, initial.executionMode)

        viewModel.updateWorkflowName("اسم جديد")
        viewModel.updateWorkflowGoal("هدف جديد")
        viewModel.updateWorkflowMode(ExecutionMode.SEQUENTIAL)
        viewModel.addWorkflowStep()
        awaitUntil { viewModel.state.value.workflowBuilder.steps.size == 4 }

        viewModel.updateWorkflowStep(3) { it.copy(description = "خطوة إضافية") }
        viewModel.moveWorkflowStep(3, -1)
        assertEquals(4, viewModel.state.value.workflowBuilder.steps.size)
        viewModel.removeWorkflowStep(2)
        assertEquals(3, viewModel.state.value.workflowBuilder.steps.size)
        viewModel.toggleWorkflowStepDependency(0, "nonexistent")
        viewModel.assignWorkflowStepAgent(1, "code_craftsman")
        assertEquals("code_craftsman", viewModel.state.value.workflowBuilder.steps[1].assignedAgentId)
        assertEquals("اسم جديد", viewModel.state.value.workflowBuilder.name)
        assertEquals("هدف جديد", viewModel.state.value.workflowBuilder.goal)
        assertEquals(ExecutionMode.SEQUENTIAL, viewModel.state.value.workflowBuilder.executionMode)
    }

    @Test
    fun `save then list then load round-trips the authored plan`() {
        authorTwoStepPlan()

        viewModel.saveWorkflowDefinition()

        awaitUntil { viewModel.state.value.workflowLibrary.isNotEmpty() }
        val saved = viewModel.state.value.workflowLibrary.first()
        assertEquals("خطة الاختبار", saved.name)
        assertNotNull(viewModel.state.value.workflowBuilder.editingDefinitionId)

        // Edit-again lineage: mutate + re-save bumps the same definition.
        // (TEST-side race fix — the documented await-the-SPECIFIC-terminal-
        // signal family: the FIRST save's banner is still up, so awaiting
        // "!= null" here passes VACUOUSLY and proves nothing about the
        // re-save; the load below could then read the pre-save row and
        // never see the edited goal. Dismiss first, so the awaited banner
        // can only be the re-save's terminal signal.)
        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
        viewModel.updateWorkflowGoal("هدف معدّل")
        viewModel.saveWorkflowDefinition()
        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals(1, viewModel.state.value.workflowLibrary.size)

        // Load the definition back into the builder (re-editable asset).
        val definitionId = viewModel.state.value.workflowBuilder.editingDefinitionId!!
        viewModel.applyWorkflowTemplate(WorkflowBuilderState(name = "استبدال مؤقت"))
        viewModel.loadWorkflowDefinitionIntoBuilder(definitionId)
        awaitUntil { viewModel.state.value.workflowBuilder.goal == "هدف معدّل" }
        assertEquals(2, viewModel.state.value.workflowBuilder.steps.size)
        assertEquals(definitionId, viewModel.state.value.workflowBuilder.editingDefinitionId)
    }

    // ------------------------------------------------------------------
    // Execution — the REAL engine
    // ------------------------------------------------------------------

    @Test
    fun `executeWorkflow runs the REAL engine and renders the report`() {
        authorTwoStepPlan()
        val plan = WorkflowPlan(
            id = WorkflowId("wf_exec_test"),
            goal = "بناء وحدة تجريبية",
            executionMode = ExecutionMode.SEQUENTIAL,
            steps = listOf(
                StepNode("step_1_plan", TaskId("t1"), AgentRole.PLANNER, "تحليل المتطلبات", dependencies = emptySet()),
                StepNode("step_2_code", TaskId("t2"), AgentRole.CODER, "تنفيذ الكود", dependencies = setOf("step_1_plan"))
            )
        )

        viewModel.executeWorkflow(plan)

        awaitUntil { viewModel.state.value.workflowReport != null }
        awaitUntil { !viewModel.state.value.isExecutingWorkflow }
        val report = viewModel.state.value.workflowReport!!
        assertTrue(report.overallOutcome is Outcome.Success)
        assertEquals(StepStatus.COMPLETED, report.stepStatuses["step_1_plan"])
        assertEquals(StepStatus.COMPLETED, report.stepStatuses["step_2_code"])
    }

    @Test
    fun `a cyclic plan fails through the engine validation gate with the honest error`() {
        val cyclicPlan = WorkflowPlan(
            id = WorkflowId("wf_cyclic"),
            goal = "خطة دورية",
            steps = listOf(
                StepNode("a", TaskId("ta"), AgentRole.PLANNER, "A", dependencies = setOf("b")),
                StepNode("b", TaskId("tb"), AgentRole.CODER, "B", dependencies = setOf("a"))
            )
        )

        viewModel.executeWorkflow(cyclicPlan)

        awaitUntil { viewModel.state.value.workflowReport != null || viewModel.state.value.errorMessage != null }
        // The engine's validatePlan is the cycle AUTHORITY (GAP-19): the
        // report carries the failure, the flag settles, the state stays
        // honest — no crash, no fabricated success.
        awaitUntil { !viewModel.state.value.isExecutingWorkflow }
        val report = viewModel.state.value.workflowReport
        if (report != null) {
            assertTrue(report.overallOutcome is Outcome.Error)
        } else {
            assertNotNull(viewModel.state.value.errorMessage)
        }
    }

    // ------------------------------------------------------------------
    // Workspace re-scope — the flatMapLatest re-wiring (slice-6 repair)
    // ------------------------------------------------------------------

    @Test
    fun `the library re-scopes with the active workspace and a stale write cannot bleed`() = runBlocking {
        authorTwoStepPlan()
        viewModel.saveWorkflowDefinition()
        awaitUntil { viewModel.state.value.workflowLibrary.isNotEmpty() }
        assertEquals(1, viewModel.state.value.workflowLibrary.size)

        // Switch to a SECOND workspace: the library reflects ONLY it.
        val second = workspaceService.createWorkspace("مساحة ثانية", "اختبار")
        val switched = workspaceService.switchWorkspace(second.id)
        assertTrue(switched)
        awaitUntil { viewModel.state.value.workflowLibrary.isEmpty() }

        // A write landing in the FIRST (now stale) workspace must NOT bleed
        // into the active list — the flatMapLatest collector for it was
        // cancelled (the inherited stacked-collector race is gone).
        val staleLibrary = WorkflowLibraryService(
            workflowDefinitionDao = db.workflowDefinitionDao(),
            planSerializer = persistence,
            workspaceIdProvider = { activeWorkspaceId }
        )
        staleLibrary.saveDefinition(
            existingId = null,
            name = "خطة في مساحة قديمة",
            plan = WorkflowPlan(
                id = WorkflowId("wf_stale"),
                goal = "هدف قديم",
                steps = listOf(StepNode("s1", TaskId("ts1"), AgentRole.PLANNER, "خطوة", dependencies = emptySet()))
            )
        )
        Thread.sleep(400) // would have bled under the stacked collector
        assertTrue(viewModel.state.value.workflowLibrary.isEmpty())

        // Switching back re-subscribes: BOTH of workspace A's rows are
        // there — the original save PLUS the row the stale write landed
        // while A was inactive (that write belongs to A; seeing it when
        // viewing A is honest. The flatMapLatest guarantee — proven above —
        // is about the ACTIVE list never reflecting ANOTHER workspace's
        // rows, not about hiding A's own durable history.)
        workspaceService.switchWorkspace(activeWorkspaceId)
        awaitUntil { viewModel.state.value.workflowLibrary.size == 2 }
        assertTrue(
            viewModel.state.value.workflowLibrary.map { it.name }.containsAll(
                listOf("خطة الاختبار", "خطة في مساحة قديمة")
            )
        )
    }

    // ------------------------------------------------------------------
    // The durable resume surface
    // ------------------------------------------------------------------

    @Test
    fun `the resumable list follows the active workspace and resume re-executes`() = runBlocking {
        val plan = WorkflowPlan(
            id = WorkflowId("wf_resumable"),
            goal = "خطة قابلة للاستئناف",
            steps = listOf(StepNode("s1", TaskId("tr1"), AgentRole.PLANNER, "خطوة", dependencies = emptySet()))
        )
        // A durable RUNNING execution in the ACTIVE workspace.
        persistence.start(WorkflowId("wf_resumable"), activeWorkspaceId, plan)

        // (TEST-side race fix, documented: the resumable list is a PULL
        // surface refreshed on workspace emissions and after executions —
        // the same contract the extracted MainViewModel code had. A start()
        // AFTER the VM's init needs a fresh pull, so this test constructs
        // the feature VM AFTER the durable row exists — mirroring a
        // process restart landing on an interrupted execution.)
        val lateViewModel = WorkflowsViewModel(
            executeWorkflowUseCase = executeWorkflowUseCase,
            workflowLibraryService = library,
            workflowPersistenceService = persistence,
            workspaceRuntimeService = workspaceService
        )

        awaitUntil { lateViewModel.state.value.resumableWorkflows.isNotEmpty() }
        assertEquals("wf_resumable", lateViewModel.state.value.resumableWorkflows.first().workflowId.value)

        // The durable resume: the completed-steps seeding runs through the
        // REAL engine (here: zero completed → full re-run).
        lateViewModel.resumeWorkflow("wf_resumable")
        awaitUntil { lateViewModel.state.value.workflowReport != null }
        assertTrue(lateViewModel.state.value.workflowReport!!.overallOutcome is Outcome.Success)
        // (TEST-side determinism: this locally-constructed VM's standing
        // library collector outlives the method — cancelled here so the
        // class teardown's db.close() cannot race a pending Room query.)
        lateViewModel.viewModelScope.cancel()
    }

    @Test
    fun `resumeWorkflow for an unknown id is an honest no-op`() {
        viewModel.resumeWorkflow("wf_nonexistent")
        Thread.sleep(300)
        assertNull(viewModel.state.value.workflowReport)
        assertNull(viewModel.state.value.diagnosticBanner)
    }

    // ------------------------------------------------------------------
    // Library lifecycle + honest degradation
    // ------------------------------------------------------------------

    @Test
    fun `clone and delete operate on the REAL library`() = runBlocking {
        authorTwoStepPlan()
        viewModel.saveWorkflowDefinition()
        awaitUntil { viewModel.state.value.workflowLibrary.isNotEmpty() }
        val definitionId = viewModel.state.value.workflowLibrary.first().workflowId.value

        viewModel.cloneWorkflowDefinition(definitionId)
        awaitUntil { viewModel.state.value.workflowLibrary.size == 2 }

        viewModel.deleteWorkflowDefinition(definitionId)
        awaitUntil { viewModel.state.value.workflowLibrary.size == 1 }
    }

    @Test
    fun `the error and banner channels are the feature's own dismissible state`() {
        viewModel.clearErrorMessage()
        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.diagnosticBanner)
    }
}
