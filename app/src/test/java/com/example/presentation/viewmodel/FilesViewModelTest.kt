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
    // ------------------------------------------------------------------
    // STAGE 7 (user round 4) — the real file manager navigation
    // ------------------------------------------------------------------

    @Test
    fun `openDirectory switches the listing to the directory direct children and navigateUp pops`() {
        val navigable = com.example.application.governed.FakeNavigableWorkspaceStorage()
        navigable.seed(7L, "attachments/folder_1/a.md", "أ")
        navigable.seed(7L, "attachments/folder_1/sub/b.md", "ب")
        navigable.seed(7L, "generated/result.md", "ج")
        navigable.seed(7L, "notes.md", "د")
        val navViewModel = FilesViewModel(
            manageWorkspaceFilesUseCase = ManageWorkspaceFilesUseCase(navigable),
            activeWorkspace = activeWorkspace,
            bootstrapState = bootstrapState,
            refreshOnProjectChange = false
        )
        bindProject(7L)
        navViewModel.refreshFiles()

        // Root listing: the two sandbox directories + the one root file.
        val root = navViewModel.state.value
        assertNull(root.currentDirectory)
        assertEquals(
            listOf("attachments", "generated", "notes.md"),
            root.files.map { it.relativePath }
        )
        assertEquals(listOf(true, true, false), root.files.map { it.isDirectory })

        // ENTER the imported folder (the user's report: this was impossible).
        navViewModel.openDirectory("attachments/folder_1")
        val inside = navViewModel.state.value
        assertEquals("attachments/folder_1", inside.currentDirectory)
        assertEquals(
            listOf("attachments/folder_1/a.md", "attachments/folder_1/sub"),
            inside.files.map { it.relativePath }
        )
        assertEquals(listOf(false, true), inside.files.map { it.isDirectory })
        // Entering a directory NEVER opens the file editor.
        assertNull(inside.selectedFilePath)

        // UP one level: the parent listing (attachments' children).
        navViewModel.navigateUp()
        val parent = navViewModel.state.value
        assertEquals("attachments", parent.currentDirectory)
        assertEquals(listOf("attachments/folder_1"), parent.files.map { it.relativePath })
        assertEquals(listOf(true), parent.files.map { it.isDirectory })

        // UP again: back at the sandbox root.
        navViewModel.navigateUp()
        val rootAgain = navViewModel.state.value
        assertNull(rootAgain.currentDirectory)
        assertEquals(
            listOf("attachments", "generated", "notes.md"),
            rootAgain.files.map { it.relativePath }
        )
    }

    @Test
    fun `refreshFiles lists the CURRENT directory not the sandbox root`() {
        val navigable = com.example.application.governed.FakeNavigableWorkspaceStorage()
        navigable.seed(7L, "attachments/folder_1/a.md", "أ")
        navigable.seed(7L, "notes.md", "د")
        val navViewModel = FilesViewModel(
            manageWorkspaceFilesUseCase = ManageWorkspaceFilesUseCase(navigable),
            activeWorkspace = activeWorkspace,
            bootstrapState = bootstrapState,
            refreshOnProjectChange = false
        )
        bindProject(7L)
        navViewModel.openDirectory("attachments/folder_1")
        navViewModel.refreshFiles()

        assertEquals("attachments/folder_1", navViewModel.state.value.currentDirectory)
        assertEquals(
            listOf("attachments/folder_1/a.md"),
            navViewModel.state.value.files.map { it.relativePath }
        )
    }

    @Test
    fun `a project switch resets the explorer to the NEW sandbox root`() {
        val navigable = com.example.application.governed.FakeNavigableWorkspaceStorage()
        navigable.seed(7L, "attachments/folder_1/a.md", "أ")
        navigable.seed(9L, "other/only.md", "آخر مشروع")
        val navViewModel = FilesViewModel(
            manageWorkspaceFilesUseCase = ManageWorkspaceFilesUseCase(navigable),
            activeWorkspace = activeWorkspace,
            bootstrapState = bootstrapState,
            refreshOnProjectChange = true
        )
        bindProject(7L)
        navViewModel.openDirectory("attachments/folder_1")
        assertEquals("attachments/folder_1", navViewModel.state.value.currentDirectory)

        // Switch the workspace to ANOTHER project (a different sandbox tree).
        activeWorkspace.value = Workspace(
            id = "ws_test_2",
            name = "المساحة الثانية",
            description = "",
            activeProjectId = 9L
        )

        // The explorer is at the NEW project's ROOT — never silently inside
        // a same-named directory of the new sandbox.
        val switched = navViewModel.state.value
        assertNull(switched.currentDirectory)
        assertEquals(listOf("other"), switched.files.map { it.relativePath })
        assertEquals(listOf(true), switched.files.map { it.isDirectory })
    }

    @Test
    fun `deleting a directory removes the whole tree and the listing refreshes`() {
        val navigable = com.example.application.governed.FakeNavigableWorkspaceStorage()
        navigable.seed(7L, "attachments/folder_1/a.md", "أ")
        navigable.seed(7L, "attachments/folder_1/sub/b.md", "ب")
        navigable.seed(7L, "notes.md", "د")
        val navViewModel = FilesViewModel(
            manageWorkspaceFilesUseCase = ManageWorkspaceFilesUseCase(navigable),
            activeWorkspace = activeWorkspace,
            bootstrapState = bootstrapState,
            refreshOnProjectChange = false
        )
        bindProject(7L)
        navViewModel.refreshFiles()

        navViewModel.deleteWorkspaceFile("attachments/folder_1")

        val after = navViewModel.state.value
        // The navigable fake derives directories from FILE paths (an EMPTY
        // directory does not exist in its model — the REAL adapter lists
        // empty dirs too), so with folder_1 gone the emptied "attachments"
        // parent vanishes from the fake's listing: only the root file is
        // left. The deleted tree is gone — that is the pin.
        assertEquals(
            listOf("notes.md"),
            after.files.map { it.relativePath }
        )
        // The nested files are GONE (the recursive delete contract).
        val storageAfterDelete = kotlinx.coroutines.runBlocking {
            val readBack = navigable.readFile(7L, "attachments/folder_1/a.md")
            val listed = navigable.listFiles(7L, null)
            readBack to listed
        }
        assertTrue(
            "the deleted file must read back as an honest error, got $storageAfterDelete",
            storageAfterDelete.first is com.example.domain.core.Outcome.Error
        )
        val listedOutcome = storageAfterDelete.second
        assertTrue(
            "the listing must be a Success outcome, got $listedOutcome",
            listedOutcome is com.example.domain.core.Outcome.Success
        )
        assertTrue(
            "the deleted tree's files must be gone from storage",
            (listedOutcome as com.example.domain.core.Outcome.Success)
                .value.none { it.relativePath.startsWith("attachments/folder_1") }
        )
    }
}
