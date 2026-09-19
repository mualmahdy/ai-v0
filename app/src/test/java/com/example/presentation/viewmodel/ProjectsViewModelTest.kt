package com.example.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.bootstrap.WorkspaceBootstrapOrchestrator
import com.example.application.project.ProjectRuntimeService
import com.example.application.session.ConversationSessionService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.session.ChatMode
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.repository.RoomConversationSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.annotation.Config
import java.io.File

/**
 * ============================================================================
 * ProjectsViewModelTest — the projects feature's behavioral suite (UI
 * Design Closure, phase B — GAP-21-style coverage over the REAL stack)
 * ============================================================================
 *
 * The projects surface is NEW (defect D-01: the PROJECTS destination used
 * to open the Tasks board while the entire project lifecycle sat unexposed
 * in the backend). This suite pins the feature VM over the REAL wiring —
 * REAL in-memory Room + REAL ProjectRuntimeService + REAL
 * WorkspaceRuntimeService + REAL ConversationSessionService — exactly like
 * AppContainer wires production:
 *
 *  - the honest CURRENT-PROJECT mirror: the default workspace's OWNED
 *    sandbox project lands with a real id > 0 and its REAL name (the
 *    P0-04 pin transferred here from MainViewModelTest when the mislabeled
 *    shell mirror was removed — D-02);
 *  - the ACTIVE-projects list lands and follows the real scope;
 *  - create → list grows, the new project becomes the current one AND the
 *    durable lastActiveProjectId column moves (scope saved);
 *  - openProject(switch) → the binding + mirror move to the other project
 *    (scope saved, re-scope without bleed);
 *  - rename → reflected through the authoritative service;
 *  - archive of the ACTIVE project → it leaves the picker list AND the
 *    binding is honestly cleared (§27);
 *  - duplicate-name creation → the service's honest rejection channel
 *    surfaces in the feature's own error channel;
 *  - evidence: the current project's session count reflects a REAL session
 *    row created through the REAL session service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var viewModel: ProjectsViewModel
    private lateinit var service: WorkspaceRuntimeService
    private lateinit var projectRuntime: ProjectRuntimeService
    private lateinit var sessionService: ConversationSessionService

    // TEST-side determinism (the slice-7/8 family): cancel every standing
    // collector BEFORE the Room pool closes.
    private var serviceScope: CoroutineScope? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        serviceScope?.cancel()
        if (this::db.isInitialized) db.close()
        if (this::baseDir.isInitialized) baseDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** The REAL projects stack, wired exactly like AppContainer wires it. */
    private fun newRealStack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_projects_feature").apply { deleteRecursively(); mkdirs() }
        val orchestrator = WorkspaceBootstrapOrchestrator(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { serviceScope = it }
        service = WorkspaceRuntimeService(
            workspaceDao = db.workspaceDao(),
            projectDao = db.projectDao(),
            bootstrapOrchestrator = orchestrator,
            coroutineScope = scope
        )
        projectRuntime = ProjectRuntimeService(
            database = db,
            projectRootResolver = { id -> File(baseDir, "proj_$id") }
        )
        sessionService = ConversationSessionService(
            repository = RoomConversationSessionRepository(
                database = db,
                sessionDao = db.conversationSessionDao(),
                turnDao = db.conversationTurnDao()
            ),
            workspaceIdProvider = { service.activeWorkspace.value?.id ?: "default" }
        )
        viewModel = ProjectsViewModel(
            projectRuntimeService = projectRuntime,
            workspaceRuntimeService = service,
            conversationSessionService = sessionService
        )
    }

    /** The documented await-the-SPECIFIC-terminal-signal helper. */
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

    // ------------------------------------------------------------------
    // The honest current-project mirror + the owned sandbox project (P0-04)
    // ------------------------------------------------------------------

    @Test
    fun `the current-project mirror lands the default workspace's OWNED sandbox project`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }

        awaitUntil { viewModel.state.value.isLoading.not() && viewModel.state.value.currentProject != null }
        val current = viewModel.state.value.currentProject!!
        // P0-04 (transferred from MainViewModelTest with the ownership):
        // a REAL owned project id — never a silent shared "project 1".
        assertTrue("expected an owned sandbox project id > 0, got ${current.id}", current.id > 0L)
        assertEquals("مشروع مساحة العمل الافتراضية", current.name)
        // The mirror is the REAL project row, never workspace data
        // relabeled as a project (the D-02 defect this replaces).
        assertEquals(service.activeWorkspace.value!!.id, current.workspaceId)
    }

    @Test
    fun `the ACTIVE-projects list lands with the sandbox project`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }

        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        assertTrue(viewModel.state.value.projects.any { it.id == viewModel.state.value.currentProject?.id })
    }

    // ------------------------------------------------------------------
    // Create → activate → scope saved
    // ------------------------------------------------------------------

    @Test
    fun `create lands in the list, becomes the current project and moves the durable binding`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }
        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        val before = viewModel.state.value.projects.size

        viewModel.createProject("مشروع الاختبار الأول", "وصف تجريبي")
        awaitUntil { viewModel.state.value.projects.size == before + 1 }
        awaitUntil { viewModel.state.value.currentProject?.name == "مشروع الاختبار الأول" }

        // SCOPE SAVED (the package's explicit requirement): the durable
        // lastActiveProjectId column — read through the authoritative DAO,
        // exactly what a process restart would read — points at the new
        // project.
        val wsId = service.activeWorkspace.value!!.id
        val bound = runBlocking {
            db.workspaceDao().getWorkspaceById(wsId)?.lastActiveProjectId
        }
        assertEquals(viewModel.state.value.currentProject!!.id, bound)
        // And the honest success channel confirmed it to the user.
        assertNotNull(viewModel.state.value.successMessage)
    }

    // ------------------------------------------------------------------
    // Switch → binding + mirror move (re-scope, no bleed)
    // ------------------------------------------------------------------

    @Test
    fun `openProject switches the binding and the mirror to the other project`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }
        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        val sandbox = viewModel.state.value.currentProject!!
        viewModel.createProject("مشروع الاختبار الثاني", "")
        awaitUntil { viewModel.state.value.currentProject?.name == "مشروع الاختبار الثاني" }
        val second = viewModel.state.value.currentProject!!

        viewModel.openProject(sandbox.id)
        awaitUntil { viewModel.state.value.currentProject?.id == sandbox.id }
        val wsId = service.activeWorkspace.value!!.id
        val bound = runBlocking {
            db.workspaceDao().getWorkspaceById(wsId)?.lastActiveProjectId
        }
        assertEquals(sandbox.id, bound)
        // The workspace mirror (every feature's re-scope trigger) moved too.
        assertEquals(sandbox.id, service.activeWorkspace.value?.activeProjectId)
        // No bleed: the other project is still in the list.
        assertTrue(viewModel.state.value.projects.any { it.id == second.id })
    }

    // ------------------------------------------------------------------
    // Rename → reflected through the authoritative service
    // ------------------------------------------------------------------

    @Test
    fun `rename is reflected in the list and the current-project mirror`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }
        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        val sandbox = viewModel.state.value.currentProject!!

        viewModel.renameProject(sandbox.id, "المشروع المعاد تسميته", "وصف جديد")
        awaitUntil { viewModel.state.value.currentProject?.name == "المشروع المعاد تسميته" }
        assertEquals(
            "المشروع المعاد تسميته",
            viewModel.state.value.projects.first { it.id == sandbox.id }.name
        )
    }

    // ------------------------------------------------------------------
    // Archive of the ACTIVE project → hidden from the picker, binding cleared (§27)
    // ------------------------------------------------------------------

    @Test
    fun `archiving the ACTIVE project clears the binding honestly`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }
        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        val sandbox = viewModel.state.value.currentProject!!

        viewModel.archiveProject(sandbox.id)
        // §27: archived projects leave the ONLY picker list.
        awaitUntil { viewModel.state.value.projects.none { it.id == sandbox.id } }
        // §27 honesty: the active binding is cleared — the mirror does not
        // keep pointing at a hidden project.
        awaitUntil { viewModel.state.value.currentProject == null }
        assertEquals(0L, service.activeWorkspace.value?.activeProjectId)
    }

    // ------------------------------------------------------------------
    // Honest rejection channel
    // ------------------------------------------------------------------

    @Test
    fun `a duplicate name surfaces the service's honest rejection`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }
        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        viewModel.dismissError()
        val sizeBefore = viewModel.state.value.projects.size

        viewModel.createProject("مشروع مساحة العمل الافتراضية", "")
        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(
            "expected the duplicate-name rejection, got: ${viewModel.state.value.errorMessage}",
            viewModel.state.value.errorMessage!!.contains("بهذا الاسم")
        )
        // Nothing was created.
        awaitUntil { viewModel.state.value.projects.size == sizeBefore }
        assertNull(viewModel.state.value.successMessage)
    }

    // ------------------------------------------------------------------
    // Evidence: the current project's session count is REAL
    // ------------------------------------------------------------------

    @Test
    fun `the current project's session count reflects a REAL session row`() {
        newRealStack()
        awaitUntil { service.activeWorkspace.value != null }
        awaitUntil { viewModel.state.value.projects.isNotEmpty() }
        val sandbox = viewModel.state.value.currentProject!!
        awaitUntil { viewModel.state.value.currentProjectSessionCount == 0 }

        runBlocking {
            sessionService.createSession(
                mode = ChatMode.QUICK_CHAT,
                title = "جلسة مشروع",
                projectId = sandbox.id
            )
        }
        awaitUntil { viewModel.state.value.currentProjectSessionCount == 1 }
    }
}
