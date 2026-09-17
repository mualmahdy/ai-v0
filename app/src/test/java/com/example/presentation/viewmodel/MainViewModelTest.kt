package com.example.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.bootstrap.BootstrapFailure
import com.example.application.bootstrap.BootstrapPhase
import com.example.application.bootstrap.BootstrapState
import com.example.application.bootstrap.WorkspaceBootstrapOrchestrator
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.task.AutonomyPolicy
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ============================================================================
 * MainViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage for
 * the APP SHELL at its ADR-6 terminal state (slice 8 — the track's closing
 * suite)
 * ============================================================================
 *
 * Slice 8 removed the shell's writerless display state (diagnosticBanner /
 * isDegraded / degradedReason — D-13); this suite pins what the shell ACTUALLY
 * owns at its terminal state, over the REAL stack (the BootstrapStateMachineTest
 * wiring precedent — REAL in-memory Room + REAL WorkspaceBootstrapOrchestrator
 * + REAL WorkspaceRuntimeService; the VM is wired exactly like AppContainer
 * wires production: bootstrapStateProvider = service.bootstrapState):
 *
 *  - the REPAIR ORDER §3A gate data: the bootstrap phase label + the explicit
 *    failure message mirror, the gate clearing through the SAME flow when the
 *    state machine recovers, and retryBootstrap idempotence on the REAL
 *    orchestrator;
 *  - the workspace-scoped DISPLAY mirrors: activeProject (the default
 *    workspace + its transactionally-bound sandbox project — P0-04: a real
 *    owned id, never a silent shared one) and autonomyPolicy following the
 *    persisted column across a workspace re-scope;
 *  - the shell's honest error channel (the R-5 regression: an init-path
 *    collector failure SURFACES instead of crashing the app) and its
 *    dismissal.
 *
 * (The failure-mirror cases inject the bootstrapStateProvider StateFlow —
 * that IS the production seam AppContainer passes; the VM's contract is to
 * mirror whatever the single startup truth emits.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var viewModel: MainViewModel

    // TEST-side determinism (the slice-8 CI follow-up): every service the
    // suite constructs gets its scope tracked here so tearDown can cancel
    // the service's STANDING collectors before the Room pool closes (the
    // 'connection pool has been closed' uncaught-exception family).
    private var serviceScope: CoroutineScope? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // TEST-side determinism (the slice-7 CI failure family, documented):
        // cancel the shell VM's standing collectors BEFORE closing the DB —
        // and the injected SERVICE scope as well: the service holds its own
        // standing Room collectors, and closing the DB under them is the
        // slice-7 matrix failure's 'connection pool has been closed' family.
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        serviceScope?.cancel()
        if (this::db.isInitialized) db.close()
        if (this::baseDir.isInitialized) baseDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the orchestrator
     * and the Room-backed service hop to Dispatchers.IO internally, so the
     * mirrors settle asynchronously even under the Unconfined Main
     * dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 15_000L,
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

    /** The REAL shell stack, wired exactly like AppContainer wires production. */
    private fun newRealStack(): WorkspaceRuntimeService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_workspaces_mainvm").apply { deleteRecursively(); mkdirs() }
        val orchestrator = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        val service = WorkspaceRuntimeService(
            workspaceDao = db.workspaceDao(),
            projectDao = db.projectDao(),
            bootstrapOrchestrator = orchestrator,
            coroutineScope = scope
        )
        viewModel = MainViewModel(
            workspaceRuntimeService = service,
            bootstrapStateProvider = service.bootstrapState
        )
        return service
    }

    // ------------------------------------------------------------------
    // The REPAIR ORDER §3A gate data — the bootstrap mirrors
    // ------------------------------------------------------------------

    @Test
    fun `the REAL bootstrap state machine mirrors to READY with no failure message`() {
        newRealStack()

        awaitUntil { viewModel.uiState.value.bootstrapPhase == "READY" }
        assertNull(viewModel.uiState.value.bootstrapFailureMessage)
    }

    @Test
    fun `a FAILED bootstrap mirrors the honest phase and message for the full-screen gate`() {
        // Legacy wiring (no orchestrator): the service itself is inert here;
        // the VM's contract is to mirror the single startup truth it is
        // given — the AppContainer seam, injected as in production.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        val service = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = scope
        )
        val provider = MutableStateFlow(BootstrapState.BOOTSTRAPPING)
        viewModel = MainViewModel(
            workspaceRuntimeService = service,
            bootstrapStateProvider = provider
        )

        provider.value = BootstrapState(
            BootstrapPhase.Failed(
                failure = BootstrapFailure.BOOTSTRAP_FAILED,
                message = "فشل صادق في تجهيز مساحة العمل"
            )
        )

        awaitUntil { viewModel.uiState.value.bootstrapPhase == "FAILED" }
        assertEquals("فشل صادق في تجهيز مساحة العمل", viewModel.uiState.value.bootstrapFailureMessage)
    }

    @Test
    fun `the gate clears through the SAME flow when the state machine recovers`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        val service = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = scope
        )
        val provider = MutableStateFlow(BootstrapState.BOOTSTRAPPING)
        viewModel = MainViewModel(
            workspaceRuntimeService = service,
            bootstrapStateProvider = provider
        )

        provider.value = BootstrapState(
            BootstrapPhase.Failed(BootstrapFailure.BOOTSTRAP_FAILED, "فشل قابل للإصلاح")
        )
        awaitUntil { viewModel.uiState.value.bootstrapFailureMessage != null }

        // The recovery lands on the SAME flow the gate collects — a
        // successful retry dismisses the gate without any UI-side special
        // case (the retryBootstrap contract).
        provider.value = BootstrapState(BootstrapPhase.Ready(workspaceId = "default", projectId = 1L))

        awaitUntil { viewModel.uiState.value.bootstrapPhase == "READY" }
        assertNull(viewModel.uiState.value.bootstrapFailureMessage)
    }

    @Test
    fun `retryBootstrap on the REAL state machine is idempotent - READY stays READY`() {
        newRealStack()
        awaitUntil { viewModel.uiState.value.bootstrapPhase == "READY" }

        viewModel.retryBootstrap()

        // The idempotent re-run re-verifies and repairs drift; it must not
        // regress the gate to a transient phase or surface a failure.
        awaitUntil { viewModel.uiState.value.bootstrapPhase == "READY" }
        assertNull(viewModel.uiState.value.bootstrapFailureMessage)
    }

    // ------------------------------------------------------------------
    // The workspace-scoped DISPLAY mirrors
    // ------------------------------------------------------------------

    @Test
    fun `the workspace mirror lands the default workspace with its owned sandbox project`() {
        newRealStack()
        awaitUntil { viewModel.uiState.value.bootstrapPhase == "READY" }
        awaitUntil { viewModel.uiState.value.activeProject != null }

        val meta = viewModel.uiState.value.activeProject!!
        assertEquals("مساحة العمل الافتراضية", meta.name)
        // P0-04: the transactionally-bound SANDBOX project — a real owned
        // id, never the legacy silent shared "project 1" fallback.
        assertTrue("expected an owned sandbox project id > 0, got ${meta.id}", meta.id > 0L)
        assertTrue(meta.isDefault)
    }

    @Test
    fun `the autonomy display mirror follows the persisted column across a re-scope`() {
        val service = newRealStack()
        awaitUntil { viewModel.uiState.value.bootstrapPhase == "READY" }
        // TEST-side determinism (the slice-8 CI failure — c164c36's android.yml
        // 'Unit tests' step, reproduced locally under the exact CI flags): the
        // gate's READY phase and the service's active-workspace landing are
        // TWO SEPARATE landings — the bootstrap state machine completes
        // independently of the service's Room read that populates
        // activeWorkspace, and nothing orders one before the other on the real
        // dispatchers. Awaiting only the ADJACENT gate phase can read the
        // service flow inside that gap (the CI NullPointerException on this
        // exact line; green in isolation, red under full-suite load). Await
        // the ACTUAL prerequisite — the documented
        // await-the-SPECIFIC-terminal-signal family.
        awaitUntil { service.activeWorkspace.value != null }
        val firstId = service.activeWorkspace.value!!.id

        // Mutate the persisted column out-of-band (the Settings surface's
        // real path routes through the authoritative service; the DAO write
        // here is the same durable column the mirror reads).
        runBlocking {
            db.workspaceDao().updateAutonomyPolicy(firstId, AutonomyPolicy.AUTONOMOUS.name, System.currentTimeMillis())
        }

        // A re-scope cycle (create + switch away + switch back) makes the
        // collector re-read the column for the first workspace.
        val second = runBlocking { service.createWorkspace("مساحة ثانية للقشرة", "اختبار") }
        runBlocking { assertTrue(service.switchWorkspace(second.id)) }
        awaitUntil { viewModel.uiState.value.activeProject?.name == "مساحة ثانية للقشرة" }
        runBlocking { assertTrue(service.switchWorkspace(firstId)) }

        awaitUntil { viewModel.uiState.value.autonomyPolicy == AutonomyPolicy.AUTONOMOUS }
    }

    // ------------------------------------------------------------------
    // The shell's honest error channel (R-5 regression)
    // ------------------------------------------------------------------

    @Test
    fun `an init-path collector failure surfaces in the error channel and clears (R-5)`() {
        // FIX R-5 (audit c03919d): an unhandled exception in this init-path
        // collector previously CRASHED the app. The honest contract: the
        // failure lands in the shell's error channel (the global snackbar).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        val service = WorkspaceRuntimeService(
            workspaceDao = ExplodingAutonomyDaoForShell(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = scope
        )
        Thread.sleep(100) // let the legacy default-workspace bootstrap settle
        viewModel = MainViewModel(workspaceRuntimeService = service)

        awaitUntil { viewModel.uiState.value.errorMessage != null }
        assertTrue(
            "expected the honest workspace-load failure, got: ${viewModel.uiState.value.errorMessage}",
            viewModel.uiState.value.errorMessage!!.contains("تعذر تحميل مساحة العمل النشطة")
        )

        viewModel.clearErrorMessage()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    /** The autonomy read explodes AFTER a workspace lands (the R-5 path). */
    private class ExplodingAutonomyDaoForShell : FakeWorkspaceDaoForVm() {
        override suspend fun autonomyPolicyFor(workspaceId: String): String? =
            throw IllegalStateException("انفجار مقصود في قراءة سياسة الاستقلالية")
    }
}
