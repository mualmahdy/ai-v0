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
import com.example.application.extension.ExtensionManager
import com.example.application.registry.ComponentRegistry
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.observability.RoomTelemetryRepository
import com.example.presentation.ui.screens.activity.UnifiedActivityFeedScreen
import com.example.presentation.ui.screens.extensions.ExtensionsScreen
import com.example.presentation.viewmodel.ActivityViewModel
import com.example.presentation.viewmodel.ExtensionsViewModel
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ============================================================================
 * Slice7ScreensRoborazziMatrixTest — GAP-21 (Design Closure 2026) screenshot
 * matrix for the ADR-6 SLICE-7 redesigned surfaces (Extensions / Activity)
 * ============================================================================
 *
 * The slice-6 matrix precedent extended to the two screens whose ViewModels
 * left the shared UiState in this slice, composed on their FEATURE
 * ViewModels (the owners), over REAL services — the same no-service-
 * stubbing contract as the behavioral suites:
 *
 *  - ExtensionsScreen: the loaded control room (the REAL manager's two
 *    verified built-in skills + a REGISTERED MCP server with the honest
 *    UNKNOWN health and a toggled-off skill — the richest honest state);
 *  - UnifiedActivityFeedScreen: the loaded feed (a REAL workspace with
 *    seeded trace rows + audit rows through the REAL Room-backed telemetry
 *    port, and the per-execution binding driven by a REAL Started signal
 *    on the bus).
 *
 * The screens render Arabic-first: the matrix pins RTL exactly like
 * MainActivity (LocalLayoutDirection provides LayoutDirection.Rtl) so the
 * captures are representative of production rendering.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class Slice7ScreensRoborazziMatrixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val screenshotDir = "src/test/screenshots/slice7"

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
    // Extensions — the loaded control room
    // ------------------------------------------------------------------

    @Test
    fun extensions_screen_loaded_control_room() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val manager = ExtensionManager(
                componentRegistry = ComponentRegistry(),
                mcpClient = McpClient(),
                integrationGateway = IntegrationGateway(),
                extensionConfigDao = db.extensionConfigDao(),
                coroutineScope = managerScope
            )
            // Seed the richest honest state: a REGISTERED MCP server (the
            // honest UNKNOWN health — no fabricated discovery) and one
            // skill toggled OFF (the honest enable/disable seam).
            manager.registerNewMcpServer("خادم السياق التجريبي", "https://example.com/mcp/sse")
            val viewModel = ExtensionsViewModel(manager)

            rtl { ExtensionsScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize()) }

            composeTestRule.waitUntil(5_000) {
                viewModel.state.value.skills.size >= 2 &&
                    viewModel.state.value.mcpServers.isNotEmpty()
            }
            // A real toggle through the feature's own seam (reflected by
            // the Room-backed collector).
            viewModel.toggleSkill(viewModel.state.value.skills.first().id)
            composeTestRule.waitUntil(5_000) {
                viewModel.state.value.skills.first().state ==
                    com.example.domain.core.extension.SkillState.DISABLED
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/extensions_loaded.png")
            // Deterministic teardown (the slice-7 CI failure family): stop
            // the VM's standing collectors and the manager's scope BEFORE
            // the pool closes — a pending Room query step under a closed
            // pool throws an uncaught "connection pool has been closed"
            // that the compose test environment attributes to a later
            // test (the actual CI failure).
            viewModel.viewModelScope.cancel()
            managerScope.cancel()
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // Activity — the loaded unified feed (trace + audit, per-execution)
    // ------------------------------------------------------------------

    @Test
    fun activity_screen_loaded_feed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val telemetry = RoomTelemetryRepository(
                metricEventDao = db.metricEventDao(),
                auditTrailDao = db.auditTrailDao(),
                executionTraceDao = db.executionTraceDao(),
                executionLogDao = db.executionLogDao(),
                auditEventDao = db.auditEventDao(),
                writeScope = telemetryScope
            )
            val workspaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val workspaceService = WorkspaceRuntimeService(
                workspaceDao = com.example.presentation.viewmodel.FakeWorkspaceDaoForVm(),
                projectDao = com.example.presentation.viewmodel.FakeProjectDaoForVm(),
                coroutineScope = workspaceScope
            )
            Thread.sleep(100) // let the default bootstrap settle
            val bus = MutableSharedFlow<com.example.presentation.viewmodel.StudioSignal>(extraBufferCapacity = 256)
            val viewModel = ActivityViewModel(
                telemetryPort = telemetry,
                workspaceRuntimeService = workspaceService,
                studioSignals = bus
            )

            // Seed a REAL workspace with honest rows through the REAL port.
            val ws = runBlocking { workspaceService.createWorkspace("مساحة اللقطة", "roborazzi") }
            runBlocking {
                telemetry.recordTraceNode(
                    ExecutionTraceNode(
                        executionId = "exec_shot",
                        stepIndex = 0,
                        actionType = "AGENT_STEP",
                        targetResourceId = null,
                        agentId = null,
                        startedAtEpochMs = System.currentTimeMillis() - 2_000,
                        completedAtEpochMs = System.currentTimeMillis() - 1_000,
                        durationMs = 1_000,
                        outcome = "COMPLETED",
                        summary = "تحليل المتطلبات وتخطيط الخطوات",
                        observationSummary = null,
                        workspaceId = ws.id
                    )
                )
                telemetry.recordTraceNode(
                    ExecutionTraceNode(
                        executionId = "exec_shot",
                        stepIndex = 1,
                        actionType = "TOOL_EXECUTION",
                        targetResourceId = null,
                        agentId = null,
                        startedAtEpochMs = System.currentTimeMillis() - 1_000,
                        completedAtEpochMs = System.currentTimeMillis(),
                        durationMs = 1_000,
                        outcome = "COMPLETED",
                        summary = "تنفيذ أداة توليد الهيكل المعماري",
                        observationSummary = "أُنشئت 3 ملفات",
                        workspaceId = ws.id
                    )
                )
                telemetry.recordAudit(
                    AuditEvent(
                        id = "evt_shot_1",
                        severity = AuditSeverity.INFO,
                        actor = "local_principal",
                        action = "POLICY_APPLIED",
                        resourceType = "EXECUTION",
                        resourceId = "exec_shot",
                        decision = "ALLOWED",
                        reason = "سياسة الجلسة تسمح بالتنفيذ المُوجَّه",
                        workspaceId = ws.id
                    )
                )
            }

            rtl {
                UnifiedActivityFeedScreen(
                    viewModel = viewModel,
                    onNavigate = { },
                    modifier = Modifier.fillMaxSize()
                )
            }

            composeTestRule.waitUntil(5_000) {
                viewModel.activeExecutionTrace.value.size >= 2 &&
                    viewModel.recentAuditEvents.value.isNotEmpty()
            }
            // Drive the per-execution binding with a REAL Started signal
            // (what StudioViewModel publishes on the bus).
            bus.tryEmit(
                com.example.presentation.viewmodel.StudioSignal.ExecutionEvent(
                    ExecutionEvent.Started(
                        executionId = "exec_shot",
                        agentId = com.example.domain.core.agent.AgentId("agent_shot"),
                        modelId = "model_shot"
                    )
                )
            )
            composeTestRule.waitUntil(5_000) {
                viewModel.activeExecutionTrace.value.all { it.executionId == "exec_shot" }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/activity_loaded.png")
            // Deterministic teardown (the slice-7 CI failure family, the
            // ACTUAL CI repro): the Started-driven re-subscription leaves
            // forExecution("exec_shot") freshly collecting when the test
            // body ends; closing the pool underneath that pending query
            // step threw the uncaught "connection pool has been closed".
            // Cancel the VM's scope (the test-side onCleared()) and the
            // service scopes FIRST, then close the DB.
            viewModel.viewModelScope.cancel()
            telemetryScope.cancel()
            workspaceScope.cancel()
        } finally {
            db.close()
        }
    }
}
