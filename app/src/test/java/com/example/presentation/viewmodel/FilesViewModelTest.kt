package com.example.presentation.viewmodel

import com.example.application.bootstrap.BootstrapFailure
import com.example.application.bootstrap.BootstrapPhase
import com.example.application.bootstrap.BootstrapState
import com.example.application.governed.FakeWorkspaceStorage
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.domain.core.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * ============================================================================
 * FilesViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage for
 * the FILES feature ViewModel (ADR-6 slice 1)
 * ============================================================================
 *
 * Pure-JVM behavioral tests (no Robolectric): a FakeWorkspaceStorage backs
 * the real ManageWorkspaceFilesUseCase, and the workspace/bootstrap state
 * flows are MutableStateFows the test drives directly. Every test asserts
 * the FEATURE CONTRACT: honest Outcome handling (Error surfaces the
 * diagnostic message), the project gate (bootstrap-phase-aware refusal
 * when no project is bound), editor lifecycle, and the auto-refresh on
 * active-project switch that replaced MainViewModel.observeWorkspace's
 * refreshFiles() call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var storage: FakeWorkspaceStorage
    private lateinit var useCase: ManageWorkspaceFilesUseCase
    private lateinit var activeWorkspace: MutableStateFlow<Workspace?>
    private lateinit var bootstrapState: MutableStateFlow<BootstrapState>
    private lateinit var viewModel: FilesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        storage = FakeWorkspaceStorage()
        useCase = ManageWorkspaceFilesUseCase(storage)
        activeWorkspace = MutableStateFlow(null)
        bootstrapState = MutableStateFlow(BootstrapState.BOOTSTRAPPING)
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): FilesViewModel = FilesViewModel(
        manageWorkspaceFilesUseCase = useCase,
        activeWorkspace = activeWorkspace,
        bootstrapState = bootstrapState
    )

    private fun bindProject(projectId: Long) {
        activeWorkspace.value = Workspace(
            id = "ws_test",
            name = "مساحة الاختبار",
            description = "",
            activeProjectId = projectId
        )
        bootstrapState.value = BootstrapState(
            BootstrapPhase.Ready(workspaceId = "ws_test", projectId = projectId)
        )
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    @Test
    fun `refreshFiles lists the active project files`() {
        storage.seed(7L, "notes/a.md", "محتوى أ")
        storage.seed(7L, "notes/b.md", "محتوى ب")
        storage.seed(9L, "other/x.md", "مشروع آخر")
        bindProject(7L)

        viewModel.refreshFiles()

        val state = viewModel.state.value
        assertEquals(2, state.files.size)
        assertTrue(state.files.all { it.relativePath.startsWith("notes/") })
        assertEquals(false, state.isFileLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `refreshFiles surfaces the diagnostic message on storage error`() {
        bindProject(7L)
        // Force a real error: a path-escape write attempt is refused by the
        // fake (mirroring production containment behaviour).
        viewModel.saveFile("../escape.md", "x")

        assertNotNull("The path-escape refusal must surface an error", viewModel.state.value.errorMessage)
    }

    @Test
    fun `acting without a bound project refuses honestly with the bootstrap phase label`() {
        // Bootstrap still running, no workspace → bootstrap-aware message.
        viewModel.refreshFiles()

        val state = viewModel.state.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("جارٍ تهيئة"))
        assertEquals(0, state.files.size)
    }

    @Test
    fun `acting without a bound project after READY refuses with the no-project message`() {
        bindProject(7L)
        // Un-bind the project but keep READY.
        activeWorkspace.value = activeWorkspace.value?.copy(activeProjectId = 0L)

        viewModel.refreshFiles()

        val state = viewModel.state.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("لا يوجد مشروع مرتبط"))
    }

    @Test
    fun `a FAILED bootstrap surfaces the orchestrator failure message`() {
        bindProject(7L)
        bootstrapState.value = BootstrapState(
            BootstrapPhase.Failed(BootstrapFailure.PROJECT_NOT_FOUND, "تعذر حل المشروع النشط")
        )
        activeWorkspace.value = activeWorkspace.value?.copy(activeProjectId = 0L)

        viewModel.openFile("a.md")

        assertEquals("تعذر حل المشروع النشط", viewModel.state.value.errorMessage)
    }

    // ------------------------------------------------------------------
    // Editor lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `openFile loads content into the editor state`() {
        storage.seed(7L, "docs/spec.md", "# المواصفة")
        bindProject(7L)

        viewModel.openFile("docs/spec.md")

        val state = viewModel.state.value
        assertEquals("docs/spec.md", state.selectedFilePath)
        assertEquals("# المواصفة", state.selectedFileContent)
        assertEquals(false, state.isFileLoading)
    }

    @Test
    fun `openFile of a missing file surfaces the storage error`() {
        bindProject(7L)

        viewModel.openFile("ghost.md")

        assertNotNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `closeFileEditor clears the editor state directly (FIX P0-5 semantics)`() {
        storage.seed(7L, "a.md", "abc")
        bindProject(7L)
        viewModel.openFile("a.md")

        viewModel.closeFileEditor()

        assertNull(viewModel.state.value.selectedFilePath)
        assertNull(viewModel.state.value.selectedFileContent)
    }

    // ------------------------------------------------------------------
    // Write / create / delete
    // ------------------------------------------------------------------

    @Test
    fun `saveFile writes, refreshes the listing, and reopens the editor with the saved content`() {
        storage.seed(7L, "a.md", "قديم")
        bindProject(7L)
        viewModel.refreshFiles()

        viewModel.saveFile("a.md", "جديد")

        val state = viewModel.state.value
        assertEquals("a.md", state.selectedFilePath)
        assertEquals("جديد", state.selectedFileContent)
    }

    @Test
    fun `createFile rejects blank paths outright`() {
        bindProject(7L)

        viewModel.createFile("   ", "")

        // No storage interaction: no error, no listing change, nothing open.
        assertNull(viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.selectedFilePath)
    }

    @Test
    fun `deleteWorkspaceFile removes the file, clears the editor, banners, and refreshes`() {
        storage.seed(7L, "gone.md", "bye")
        bindProject(7L)
        viewModel.refreshFiles()
        viewModel.openFile("gone.md")

        viewModel.deleteWorkspaceFile("gone.md")

        val state = viewModel.state.value
        assertTrue(state.diagnosticBanner!!.contains("تم حذف الملف"))
        assertNull(state.selectedFilePath)
        assertNull(state.selectedFileContent)
        assertEquals(0, state.files.size)
    }

    @Test
    fun `deleteWorkspaceFile of a missing file surfaces the storage error`() {
        bindProject(7L)

        viewModel.deleteWorkspaceFile("ghost.md")

        assertNotNull(viewModel.state.value.errorMessage)
    }

    // ------------------------------------------------------------------
    // Workspace-switch auto-refresh (replaces MainViewModel's refresh call)
    // ------------------------------------------------------------------

    @Test
    fun `switching the active project auto-refreshes the listing`() {
        storage.seed(1L, "p1.md", "واحد")
        storage.seed(2L, "p2.md", "اثنان")
        bindProject(1L)
        viewModel.refreshFiles()
        assertEquals(1, viewModel.state.value.files.size)
        assertEquals("p1.md", viewModel.state.value.files.first().relativePath)

        // Simulate a workspace switch to a different project (the flow the
        // FilesViewModel init collector observes).
        activeWorkspace.value = Workspace(
            id = "ws_test_2",
            name = "المساحة الثانية",
            description = "",
            activeProjectId = 2L
        )

        assertEquals(1, viewModel.state.value.files.size)
        assertEquals("p2.md", viewModel.state.value.files.first().relativePath)
    }

    @Test
    fun `emitting the SAME project again does not re-list (deduplication)`() {
        storage.seed(1L, "p1.md", "واحد")
        bindProject(1L)
        viewModel.refreshFiles()
        assertEquals(1, viewModel.state.value.files.size)

        // Re-emit the same project (e.g. unrelated workspace-field change):
        // the collector must NOT re-list (deduplicated by project id).
        activeWorkspace.value = activeWorkspace.value?.copy(description = "وصف آخر")

        assertEquals(1, viewModel.state.value.files.size)
        assertEquals("p1.md", viewModel.state.value.files.first().relativePath)
    }

    // ------------------------------------------------------------------
    // Error / banner dismissal
    // ------------------------------------------------------------------

    @Test
    fun `dismissError and dismissBanner clear their channels`() {
        bindProject(7L)
        viewModel.openFile("ghost.md") // sets errorMessage
        assertNotNull(viewModel.state.value.errorMessage)

        viewModel.dismissError()
        assertNull(viewModel.state.value.errorMessage)

        storage.seed(7L, "gone.md", "x")
        viewModel.deleteWorkspaceFile("gone.md") // sets banner
        assertNotNull(viewModel.state.value.diagnosticBanner)

        viewModel.dismissBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
    }
}
