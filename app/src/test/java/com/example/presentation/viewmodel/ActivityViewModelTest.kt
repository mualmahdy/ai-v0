package com.example.presentation.viewmodel

import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.infrastructure.observability.RoomTelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * ActivityViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the ACTIVITY feature ViewModel (ADR-6 slice 7)
 * ============================================================================
 *
 * Drives the REAL Room-backed telemetry port (RoomTelemetryRepository over a
 * REAL in-memory Room database — the same DAOs the production AppContainer
 * wires) and the REAL WorkspaceRuntimeService over the pure-JVM DAO fakes —
 * no service-seam stubbing; the studio signal bus is the ONLY injected test
 * double (it IS the production seam: a dumb SharedFlow created per-Activity):
 *
 *  - the honest null-port fallback: EMPTY flows, never fabricated rows;
 *  - the GAP-04 workspace scoping: the recent-trace window + the audit
 *    stream reflect ONLY the active workspace, and re-scoping on a
 *    workspace switch cannot leak the previous workspace's rows;
 *  - the UNIFIED ACTIVITY TRACE WIRING FIX: a Started signal on the bus
 *    binds the feed to THAT execution's trace stream (the real execution
 *    id — never an empty-id query that matches nothing);
 *  - the null-workspace honesty: no active workspace = the honest empty
 *    window (GAP-04 precedent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var telemetry: RoomTelemetryRepository
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var bus: MutableSharedFlow<StudioSignal>
    private lateinit var viewModel: ActivityViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.infrastructure.persistence.AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        telemetry = RoomTelemetryRepository(
            metricEventDao = db.metricEventDao(),
            auditTrailDao = db.auditTrailDao(),
            executionTraceDao = db.executionTraceDao(),
            executionLogDao = db.executionLogDao(),
            auditEventDao = db.auditEventDao(),
            writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        bus = MutableSharedFlow(extraBufferCapacity = 256)
        viewModel = ActivityViewModel(
            telemetryPort = telemetry,
            workspaceRuntimeService = workspaceService,
            studioSignals = bus
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the repository
     * hops to Dispatchers.IO internally (Room queries), so outcomes settle
     * asynchronously even under the Unconfined Main dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 5_000L,
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

    private fun traceNode(
        executionId: String,
        stepIndex: Int,
        workspaceId: String?
    ) = ExecutionTraceNode(
        executionId = executionId,
        stepIndex = stepIndex,
        actionType = "AGENT_STEP",
        targetResourceId = null,
        agentId = null,
        startedAtEpochMs = System.currentTimeMillis() - 1_000,
        completedAtEpochMs = System.currentTimeMillis(),
        durationMs = 1_000,
        outcome = "COMPLETED",
        summary = "خطوة تنفيذ حقيقية #$stepIndex",
        observationSummary = null,
        workspaceId = workspaceId
    )

    private fun auditEvent(workspaceId: String, action: String) = AuditEvent(
        id = "evt_${System.nanoTime()}_$action",
        severity = AuditSeverity.INFO,
        actor = "test",
        action = action,
        resourceType = "WORKSPACE",
        resourceId = workspaceId,
        decision = "ALLOWED",
        reason = "اختبار سلوكي",
        workspaceId = workspaceId
    )

    // ------------------------------------------------------------------
    // The honest fallback — a null port is an EMPTY feed, never fabricated
    // ------------------------------------------------------------------

    @Test
    fun `a null telemetry port renders the honest empty feed`() {
        val bare = ActivityViewModel(
            telemetryPort = null,
            workspaceRuntimeService = workspaceService
        )
        // The flows settle instantly (no IO): empty lists, not errors and
        // not fabricated rows.
        assertTrue(bare.activeExecutionTrace.value.isEmpty())
        assertTrue(bare.recentAuditEvents.value.isEmpty())
    }

    // ------------------------------------------------------------------
    // GAP-04 — workspace-scoped windows
    // ------------------------------------------------------------------

    @Test
    fun `the recent-trace window reflects only the active workspace`() {
        val ws = runBlocking { workspaceService.createWorkspace("مساحة النشاط", "test") }
        runBlocking {
            telemetry.recordTraceNode(traceNode("exec_ws_a", 0, ws.id))
            telemetry.recordTraceNode(traceNode("exec_other", 0, "workspace_غير_النشطة"))
        }

        awaitUntil { viewModel.activeExecutionTrace.value.isNotEmpty() }
        // ONLY the active workspace's rows — the other workspace's trace is
        // invisible (GAP-04: SQL-level scoping).
        assertEquals(1, viewModel.activeExecutionTrace.value.size)
        assertEquals("exec_ws_a", viewModel.activeExecutionTrace.value.first().executionId)
    }

    @Test
    fun `the audit stream reflects only the active workspace`() {
        val ws = runBlocking { workspaceService.createWorkspace("مساحة التدقيق", "test") }
        runBlocking {
            telemetry.recordAudit(auditEvent(ws.id, "POLICY_APPLIED"))
            telemetry.recordAudit(auditEvent("workspace_أخرى", "SOMETHING_ELSE"))
        }

        awaitUntil { viewModel.recentAuditEvents.value.isNotEmpty() }
        assertEquals(1, viewModel.recentAuditEvents.value.size)
        assertEquals("POLICY_APPLIED", viewModel.recentAuditEvents.value.first().action)
    }

    @Test
    fun `a workspace switch re-scopes the windows without bleeding the previous workspace`() {
        val first = runBlocking { workspaceService.createWorkspace("الأولى", "test") }
        runBlocking { telemetry.recordTraceNode(traceNode("exec_first", 0, first.id)) }
        awaitUntil { viewModel.activeExecutionTrace.value.isNotEmpty() }

        // Switch to a SECOND workspace with its own (different) trace row.
        val second = runBlocking { workspaceService.createWorkspace("الثانية", "test") }
        runBlocking { telemetry.recordTraceNode(traceNode("exec_second", 0, second.id)) }

        // The window reflects ONLY the newly active workspace — the first
        // workspace's row cannot bleed in (flatMapLatest re-scope).
        awaitUntil {
            viewModel.activeExecutionTrace.value.isNotEmpty() &&
                viewModel.activeExecutionTrace.value.all { it.executionId == "exec_second" }
        }
        assertEquals(1, viewModel.activeExecutionTrace.value.size)
        assertEquals("exec_second", viewModel.activeExecutionTrace.value.first().executionId)
    }

    // ------------------------------------------------------------------
    // The per-execution trace binding (the wiring fix)
    // ------------------------------------------------------------------

    @Test
    fun `a Started signal binds the feed to that execution's trace stream`() {
        val ws = runBlocking { workspaceService.createWorkspace("مساحة الربط", "test") }
        // Two executions' rows exist; the workspace window would show the
        // per-workspace merge — the Started binding must narrow it to the
        // LIVE execution's stream only.
        runBlocking {
            telemetry.recordTraceNode(traceNode("exec_live", 0, ws.id))
            telemetry.recordTraceNode(traceNode("exec_live", 1, ws.id))
            telemetry.recordTraceNode(traceNode("exec_old", 0, ws.id))
        }
        awaitUntil { viewModel.activeExecutionTrace.value.size >= 3 }

        // The REAL execution id on the bus (what StudioViewModel publishes).
        bus.tryEmit(
            StudioSignal.ExecutionEvent(
                ExecutionEvent.Started(
                    executionId = "exec_live",
                    agentId = com.example.domain.core.agent.AgentId("agent_live"),
                    modelId = "model_test"
                )
            )
        )

        awaitUntil {
            viewModel.activeExecutionTrace.value.isNotEmpty() &&
                viewModel.activeExecutionTrace.value.all { it.executionId == "exec_live" }
        }
        // BOTH live-execution steps are present (ordered by the stream).
        assertEquals(2, viewModel.activeExecutionTrace.value.size)
    }

    @Test
    fun `a blank execution id never queries an empty id - the honest window stays`() {
        val ws = runBlocking { workspaceService.createWorkspace("مساحة الهوية", "test") }
        runBlocking { telemetry.recordTraceNode(traceNode("exec_window", 0, ws.id)) }
        awaitUntil { viewModel.activeExecutionTrace.value.isNotEmpty() }

        // The bus contract only publishes REAL ids on Started; even so, the
        // guard is honest: a null/blank id keeps the workspace window (it
        // never falls back to an empty-id query that matches nothing).
        bus.tryEmit(
            StudioSignal.ExecutionEvent(
                ExecutionEvent.Started(
                    executionId = "",
                    agentId = com.example.domain.core.agent.AgentId("agent_blank"),
                    modelId = "model_test"
                )
            )
        )
        // Still the workspace window.
        awaitUntil { viewModel.activeExecutionTrace.value.size == 1 }
        assertEquals("exec_window", viewModel.activeExecutionTrace.value.first().executionId)
    }
}
