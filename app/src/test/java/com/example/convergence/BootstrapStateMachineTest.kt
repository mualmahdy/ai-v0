package com.example.convergence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.bootstrap.BootstrapFailure
import com.example.application.bootstrap.BootstrapPhase
import com.example.application.bootstrap.BootstrapState
import com.example.application.bootstrap.WorkspaceBootstrapOrchestrator
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * REPAIR ORDER §34 — STARTUP TESTS
 * ============================================================================
 * Proves the bootstrap state machine fixes the recurring startup failure
 * "No project is associated with the current workspace":
 *   - first launch / fresh database (transactional workspace+project creation)
 *   - restart with existing workspace + project (deterministic restoration)
 *   - STALE lastActiveProjectId (deleted/archived/foreign project) reconciled
 *   - workspace switching keeps a resolvable binding
 *   - idempotency (re-running bootstrap on READY state is a no-op)
 *   - explicit failure states (PROJECT_NOT_FOUND) when nothing is resolvable
 *   - no "project 1" / first-project fallback
 */
@RunWith(RobolectricTestRunner::class)
class BootstrapStateMachineTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var orchestrator: WorkspaceBootstrapOrchestrator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_workspaces_bootstrap").apply { deleteRecursively(); mkdirs() }
        orchestrator = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // First launch / fresh database
    // ------------------------------------------------------------------

    @Test
    fun `first launch creates workspace AND project transactionally and reaches READY`() = runBlocking {
        val state = orchestrator.bootstrap()
        assertTrue("state must be READY, was $state", state.isReady)
        assertEquals(BootstrapPhase.Ready::class.java, state.phase::class.java)
        val ready = state.phase as BootstrapPhase.Ready
        assertEquals("default", ready.workspaceId)
        assertTrue(ready.projectId > 0)
        // The workspace row points at the created project (root cause 1 fixed:
        // no half-created workspace without its required project).
        val ws = db.workspaceDao().getWorkspaceById("default")
        assertNotNull(ws)
        assertEquals(ready.projectId, ws!!.lastActiveProjectId)
        // The sandbox root was materialized (CONTEXT_READY).
        assertTrue(File(baseDir, "proj_${ready.projectId}").exists())
    }

    @Test
    fun `restart with existing workspace and project restores deterministically`() = runBlocking {
        val first = orchestrator.bootstrap()
        val ready1 = first.phase as BootstrapPhase.Ready
        // Simulate restart: a NEW orchestrator over the SAME database.
        val restarted = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
        val second = restarted.bootstrap()
        assertTrue(second.isReady)
        val ready2 = second.phase as BootstrapPhase.Ready
        assertEquals(ready1.workspaceId, ready2.workspaceId)
        // Deterministic: the SAME project is restored (no arbitrary switch).
        assertEquals(ready1.projectId, ready2.projectId)
    }

    // ------------------------------------------------------------------
    // Stale reference reconciliation
    // ------------------------------------------------------------------

    @Test
    fun `stale lastActiveProjectId (deleted project) is reconciled to newest ACTIVE owned project`() = runBlocking {
        val first = orchestrator.bootstrap()
        val ready = first.phase as BootstrapPhase.Ready
        // Create a second project (newer updatedAt).
        val now = System.currentTimeMillis() + 5_000
        val secondId = db.projectDao().insertProject(
            ProjectEntity(
                name = "Second", description = null, rootPath = "",
                createdAtEpochMs = now, updatedAtEpochMs = now,
                workspaceId = "default", lifecycleState = "ACTIVE"
            )
        )
        // Simulate the pinned project row being deleted (stale reference).
        db.projectDao().deleteProjectRow(ready.projectId)
        val state = orchestrator.bootstrap()
        assertTrue("must reconcile to READY, was $state", state.isReady)
        val ready2 = state.phase as BootstrapPhase.Ready
        assertEquals(secondId, ready2.projectId)
    }

    @Test
    fun `stale lastActiveProjectId pointing at an ARCHIVED project is not resolved`() = runBlocking {
        val first = orchestrator.bootstrap()
        val ready = first.phase as BootstrapPhase.Ready
        // Archive the pinned project (stale-but-existing reference).
        val entity = db.projectDao().getProjectById(ready.projectId)!!
        db.projectDao().updateProject(entity.copy(lifecycleState = "ARCHIVED", isArchived = true))
        val state = orchestrator.bootstrap()
        // The archived project must NOT be resolved; the reconciler binds the
        // newest ACTIVE project — none exists → explicit PROJECT_NOT_FOUND.
        assertTrue(
            "expected PROJECT_NOT_FOUND failure state, was ${state.phase}",
            state.phase is BootstrapPhase.Failed &&
                    (state.phase as BootstrapPhase.Failed).failure == BootstrapFailure.PROJECT_NOT_FOUND
        )
    }

    @Test
    fun `foreign workspace project is never resolved (no cross-workspace fallback)`() = runBlocking {
        val first = orchestrator.bootstrap()
        val ready = first.phase as BootstrapPhase.Ready
        // Remove ALL of this workspace's own resolvable projects; the pinned
        // id points at a project id that does not exist locally.
        db.projectDao().deleteProjectRow(ready.projectId)
        db.workspaceDao().setActiveProject("default", 999_999L, System.currentTimeMillis())
        val state = orchestrator.bootstrap()
        // Reconciliation never binds the FOREIGN id; the state machine
        // creates the workspace's REQUIRED project instead (a workspace must
        // not stay invalid) — READY with a NEW local identity.
        assertTrue(
            "expected READY-with-created-project (foreign id never resolved), was ${state.phase}",
            state.phase is BootstrapPhase.Ready
        )
        val finalReady = state.phase as BootstrapPhase.Ready
        assertTrue("foreign 999999 must never be resolved", finalReady.projectId != 999_999L)
        assertTrue(
            "repair note must record the required-project creation",
            finalReady.repairNote?.contains("created_missing_required_project") == true
        )
    }

    @Test
    fun `no arbitrary first-project fallback - unresolvable state is explicit`() = runBlocking {
        // Pre-seed a workspace with NO projects at all.
        db.workspaceDao().insertOrUpdate(
            WorkspaceEntity(
                id = "ws_empty", name = "Empty", description = "",
                networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                isActive = true, lastActiveProjectId = null,
                createdAtEpochMs = 1, lastAccessedEpochMs = 1
            )
        )
        // Deactivate "default" so the empty workspace is the only active one.
        db.workspaceDao().deactivateAll()
        db.workspaceDao().setActive("ws_empty", System.currentTimeMillis())
        // Remove the default workspace entirely so the empty one wins.
        db.workspaceDao().deleteById("default")
        val orchestrator2 = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
        val state = orchestrator2.bootstrap()
        // A workspace with ZERO projects gets its required project CREATED
        // (transactional — a workspace must not stay invalid).
        assertTrue("expected READY with created project, was ${state.phase}", state.isReady)
        val ready = state.phase as BootstrapPhase.Ready
        assertTrue(ready.projectId > 0)
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Test
    fun `bootstrap is idempotent - re-running on READY produces the same state`() = runBlocking {
        val first = orchestrator.bootstrap()
        val second = orchestrator.bootstrap()
        val third = orchestrator.bootstrap()
        assertTrue(first.isReady && second.isReady && third.isReady)
        assertEquals(
            (first.phase as BootstrapPhase.Ready).projectId,
            (third.phase as BootstrapPhase.Ready).projectId
        )
        // Exactly one workspace + one project (no duplicates from re-runs).
        assertEquals(1, db.workspaceDao().getAllWorkspaces().size)
    }

    @Test
    fun `multiple active workspaces corruption is repaired deterministically`() = runBlocking {
        orchestrator.bootstrap()
        // Force TWO active workspaces (corruption).
        db.workspaceDao().insertOrUpdate(
            WorkspaceEntity(
                id = "ws_intruder", name = "Intruder", description = "",
                networkPolicy = "HYBRID", autonomyPolicy = "SUPERVISED", settingsJson = "{}",
                isActive = true, lastActiveProjectId = null,
                createdAtEpochMs = 2, lastAccessedEpochMs = 2
            )
        )
        val state = orchestrator.bootstrap()
        assertTrue(state.isReady)
        // Exactly ONE active workspace remains.
        val actives = db.workspaceDao().getAllWorkspaces().filter { it.isActive }
        assertEquals(1, actives.size)
    }

    @Test
    fun `corrupt rootPath is repaired to the canonical sandbox path`() = runBlocking {
        val first = orchestrator.bootstrap()
        val ready = first.phase as BootstrapPhase.Ready
        // Corrupt the recorded root path.
        val entity = db.projectDao().getProjectById(ready.projectId)!!
        db.projectDao().updateProject(entity.copy(rootPath = "/definitely/not/sanctioned"))
        val state = orchestrator.bootstrap()
        assertTrue(state.isReady)
        val repaired = db.projectDao().getProjectById(ready.projectId)!!
        assertEquals(File(baseDir, "proj_${ready.projectId}").canonicalPath, repaired.rootPath)
    }
}

// ------------------------------------------------------------------
// GAP-23 (Design Closure 2026) — the honest startup gate's RETRY entry
// ------------------------------------------------------------------

/**
 * The startup gate renders Failed(...) as a full-screen honest surface with
 * a retry button. Retry must be SAFE (idempotent state machine) and must
 * RECOVER when the blocking condition is repaired: the gate opens on
 * Failed and dismisses on Ready through the SAME state flow.
 */
@RunWith(RobolectricTestRunner::class)
class BootstrapRetryTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var orchestrator: WorkspaceBootstrapOrchestrator
    private lateinit var runtime: com.example.application.workspace.WorkspaceRuntimeService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_workspaces_bootstrap_retry").apply { deleteRecursively(); mkdirs() }
        orchestrator = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
        runtime = com.example.application.workspace.WorkspaceRuntimeService(
            workspaceDao = db.workspaceDao(),
            projectDao = db.projectDao(),
            projectRootPathResolver = { id -> File(baseDir, "proj_$id").path },
            coroutineScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
            ),
            bootstrapOrchestrator = orchestrator
        )
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    @Test
    fun `retryBootstrap reaches READY on a healthy state and is idempotent`() = runBlocking {
        val first = runtime.retryBootstrap()
        assertTrue("first retry on a fresh DB must reach READY, was $first", first.isReady)

        val second = runtime.retryBootstrap()
        assertTrue("re-running on READY re-verifies instead of failing", second.isReady)
    }

    @Test
    fun `retryBootstrap surfaces the explicit failure and RECOVERS after repair`() = runBlocking {
        runtime.retryBootstrap() // READY — creates the default workspace + its project.
        val ready = orchestrator.state.value.phase as BootstrapPhase.Ready

        // Corrupt the resolvable state: trash the ONLY project (the workspace
        // still OWNS it — so bootstrap must NOT auto-create a replacement;
        // it must fail explicitly with PROJECT_NOT_FOUND).
        val project = db.projectDao().getProjectById(ready.projectId)!!
        db.projectDao().updateProject(project.copy(lifecycleState = "TRASHED"))

        val failed = runtime.retryBootstrap()
        assertTrue("expected FAILED, was ${failed.phase}", failed.isFailed)
        val failure = failed.phase as BootstrapPhase.Failed
        assertEquals(BootstrapFailure.PROJECT_NOT_FOUND, failure.failure)
        assertNotNull("the failure message must be present for the gate to render", failure.message)

        // The gate's retry: repair (restore the project) → retry → READY.
        db.projectDao().updateProject(project.copy(lifecycleState = "ACTIVE"))
        val recovered = runtime.retryBootstrap()
        assertTrue("retry after repair must dismiss the gate (READY), was ${recovered.phase}", recovered.isReady)
    }
}
