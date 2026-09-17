package com.example.presentation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.usecases.DecisionSimulationUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.workflow.WorkflowLibraryService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.presentation.state.WorkflowBuilderState
import com.example.presentation.state.WorkflowBuilderStep
import com.example.presentation.ui.screens.decision.DecisionScreen
import com.example.presentation.ui.screens.radar.RadarScreen
import com.example.presentation.ui.screens.tasks.TasksScreen
import com.example.presentation.viewmodel.DecisionViewModel
import com.example.presentation.viewmodel.RadarViewModel
import com.example.presentation.viewmodel.TasksViewModel
import com.example.presentation.viewmodel.WorkflowsViewModel
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ============================================================================
 * Slice6ScreensRoborazziMatrixTest — GAP-21 (Design Closure 2026) screenshot
 * matrix for the ADR-6 SLICE-6 redesigned screens (Radar / Decision / Tasks)
 * ============================================================================
 *
 * The track table's slice-6 deliverable: a Roborazzi matrix over the screens
 * that left the shared UiState, composed on their FEATURE ViewModels (the
 * owners), over REAL services — the same no-service-stubbing contract as
 * the behavioral suites:
 *
 *  - RadarScreen: the loaded observatory (the real pipeline's bootstrap
 *    feed + evolution candidates; zero radar sources = no network);
 *  - DecisionScreen: the HONEST EMPTY preview (legacy construction without
 *    the use-case — the fallback the cockpit renders instead of a
 *    fabricated verdict) AND the loaded cockpit (real engine evaluation
 *    through the REAL DecisionSimulationUseCase);
 *  - TasksScreen: the builder console on its DEFAULT template with the
 *    task board + a REAL saved library definition + a REAL durable
 *    resumable execution (the richest honest state).
 *
 * The screens render Arabic-first: the matrix pins RTL exactly like
 * MainActivity (LocalLayoutDirection provides LayoutDirection.Rtl) so the
 * captures are representative of production rendering.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class Slice6ScreensRoborazziMatrixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val screenshotDir = "src/test/screenshots/slice6"

    private fun rtl(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    content()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Radar — the loaded observatory
    // ------------------------------------------------------------------

    @Test
    fun radar_screen_loaded_observatory() {
        val pipeline = IntelligenceRadarPipeline(
            // Zero radar sources: NO network — the bootstrap feed renders.
            radarSources = emptyList(),
            radarItemDao = null,
            evolutionCandidateDao = null,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        val viewModel = RadarViewModel(pipeline)

        rtl { RadarScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize()) }

        composeTestRule.waitUntil(5_000) { viewModel.state.value.radarItems.isNotEmpty() }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/radar_loaded.png")
    }

    // ------------------------------------------------------------------
    // Decision — honest empty (the no-use-case fallback) + loaded cockpit
    // ------------------------------------------------------------------

    @Test
    fun decision_screen_honest_empty_preview() {
        val engine = CbrMdpEngine()
        // Legacy construction WITHOUT the use-case: the cockpit renders the
        // honest empty preview (no fabricated verdict, no spinner stuck).
        val viewModel = DecisionViewModel(cbrMdpEngine = engine, decisionSimulationUseCase = null)

        rtl { DecisionScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize()) }

        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/decision_empty.png")
    }

    @Test
    fun decision_screen_loaded_cockpit() {
        val engine = CbrMdpEngine()
        val viewModel = DecisionViewModel(
            cbrMdpEngine = engine,
            decisionSimulationUseCase = DecisionSimulationUseCase(engine)
        )

        rtl { DecisionScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize()) }

        composeTestRule.waitUntil(5_000) { viewModel.state.value.latestDecision != null }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/decision_loaded.png")
    }

    // ------------------------------------------------------------------
    // Tasks — the builder console on the richest honest state
    // ------------------------------------------------------------------

    @Test
    fun tasks_screen_builder_console_with_library_and_resumable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // --- Light REAL stack (the same shapes the behavioral suites use) ---
            val registry = ComponentRegistry()
            val securityGuard = SecurityGuardService()
            val decisionService = DecisionService(CbrMdpEngine(), registry, securityGuard)
            val orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
            val workspaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val workspaceService = WorkspaceRuntimeService(
                workspaceDao = com.example.presentation.viewmodel.FakeWorkspaceDaoForVm(),
                projectDao = com.example.presentation.viewmodel.FakeProjectDaoForVm(),
                coroutineScope = workspaceScope
            )
            Thread.sleep(100) // let the default bootstrap settle
            val workspace = workspaceService.createWorkspace("مساحة اللقطة", "roborazzi")
            orchestrator.workspaceIdProvider = { workspace.id }

            val persistence = WorkflowPersistenceService(
                workflowExecutionDao = db.workflowExecutionDao(),
                workflowStepStateDao = db.workflowStepStateDao()
            )
            val library = WorkflowLibraryService(
                workflowDefinitionDao = db.workflowDefinitionDao(),
                planSerializer = persistence,
                workspaceIdProvider = { workspace.id }
            )

            // ADR-6 slice 7: the agent catalog composes on the agents
            // feature VM (its owner) — same light REAL registry.
            val agentsViewModel = com.example.presentation.viewmodel.AgentsViewModel(
                agentRegistryService = null,
                componentRegistry = registry
            )
            val tasksViewModel = TasksViewModel(
                taskBoardService = com.example.application.orchestration.TaskBoardService(
                    // Light but REAL board service over the same Room DB.
                    taskDao = db.taskDao()
                ),
                agentOrchestrator = orchestrator,
                workspaceRuntimeService = workspaceService
            )
            val workflowsViewModel = WorkflowsViewModel(
                executeWorkflowUseCase = ExecuteWorkflowUseCase(WorkflowEngine(orchestrator)),
                workflowLibraryService = library,
                workflowPersistenceService = persistence,
                workspaceRuntimeService = workspaceService
            )

            // Seed the richest honest state: an authored definition saved to
            // the REAL library + a durable RUNNING execution (resumable row).
            library.saveDefinition(
                existingId = null,
                name = "خطة الإصدار التالي",
                plan = WorkflowPlan(
                    id = WorkflowId("wf_shot"),
                    goal = "إصدار الميزة الجديدة",
                    executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
                    steps = listOf(
                        StepNode("s1", TaskId("t1"), AgentRole.PLANNER, "تحليل", dependencies = emptySet()),
                        StepNode("s2", TaskId("t2"), AgentRole.CODER, "تنفيذ", dependencies = setOf("s1"))
                    )
                )
            )
            persistence.start(
                WorkflowId("wf_shot"),
                workspace.id,
                WorkflowPlan(
                    id = WorkflowId("wf_shot"),
                    goal = "إصدار الميزة الجديدة",
                    steps = listOf(StepNode("s1", TaskId("t1"), AgentRole.PLANNER, "تحليل", dependencies = emptySet()))
                )
            )
            workflowsViewModel.applyWorkflowTemplate(
                WorkflowBuilderState(
                    name = "خطة الإصدار التالي",
                    goal = "إصدار الميزة الجديدة بنهاية الأسبوع",
                    executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
                    steps = listOf(
                        WorkflowBuilderStep("step_1_plan", "تحليل المتطلبات والتخطيط", AgentRole.PLANNER, emptySet()),
                        WorkflowBuilderStep("step_2_code", "كتابة الشيفرات والنماذج", AgentRole.CODER, setOf("step_1_plan")),
                        WorkflowBuilderStep("step_3_security", "التدقيق الأمني والسياسات", AgentRole.SECURITY_GUARD, setOf("step_2_code"))
                    )
                )
            )
            workflowsViewModel.loadResumableWorkflows()

            rtl {
                TasksScreen(
                    agentsViewModel = agentsViewModel,
                    tasksViewModel = tasksViewModel,
                    workflowsViewModel = workflowsViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            composeTestRule.waitUntil(5_000) { workflowsViewModel.state.value.resumableWorkflows.isNotEmpty() }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/tasks_builder_console.png")
            // Deterministic teardown (the slice-7 CI failure family): stop
            // the feature VMs' standing collectors (the workflows library
            // relay is a STANDING Room flow) and the service scope BEFORE
            // the pool closes — a pending Room query step under a closed
            // pool throws an uncaught "connection pool has been closed".
            workflowsViewModel.viewModelScope.cancel()
            tasksViewModel.viewModelScope.cancel()
            agentsViewModel.viewModelScope.cancel()
            workspaceScope.cancel()
        } finally {
            db.close()
        }
    }
}
