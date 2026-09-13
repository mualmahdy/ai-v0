package com.example.presentation.viewmodel

import com.example.application.session.ConversationSessionService
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * SessionsViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the SESSIONS feature ViewModel (ADR-6 slice 2)
 * ============================================================================
 *
 * Drives the REAL ConversationSessionService over an in-memory repository
 * port (Unconfined dispatch) and asserts the registry contract extracted
 * from MainViewModel:
 *
 *  - the browser list follows the ACTIVE workspace (continuity) AND its
 *    active project (GAP-14 isolation — sibling projects' sessions are
 *    invisible);
 *  - the flatMapLatest re-scope: a workspace/project switch REPLACES the
 *    query collector (the old stacked-collector last-writer race is gone);
 *  - deletion goes through the service and fires the callback AFTER the
 *    service call settles (deterministic studio-side effect ordering);
 *  - the browser sheet flag is pure feature state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: FakeConversationSessionRepositoryForVm
    private lateinit var service: ConversationSessionService
    private lateinit var activeWorkspace: MutableStateFlow<Workspace?>
    private lateinit var viewModel: SessionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeConversationSessionRepositoryForVm()
        service = ConversationSessionService(
            repository = repository,
            workspaceIdProvider = { repository.activeWorkspaceId }
        )
        activeWorkspace = MutableStateFlow(null)
        viewModel = SessionsViewModel(
            conversationSessionService = service,
            activeWorkspace = activeWorkspace
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session(
        id: String,
        workspaceId: String,
        projectId: Long? = null,
        mode: ChatMode = ChatMode.QUICK_CHAT
    ): ConversationSession = ConversationSession(
        id = ConversationSessionId(id),
        workspaceId = workspaceId,
        title = "جلسة $id",
        mode = mode,
        projectId = projectId
    )

    private fun activateWorkspace(id: String, projectId: Long = 0L) {
        activeWorkspace.value = Workspace(
            id = id,
            name = "مساحة $id",
            description = "",
            activeProjectId = projectId
        )
        repository.activeWorkspaceId = id
    }

    // ------------------------------------------------------------------
    // Registry observation (GAP-14 + workspace continuity)
    // ------------------------------------------------------------------

    @Test
    fun `the browser lists only the ACTIVE workspace's sessions`() {
        repository.seed(session("s_a1", "ws_a"))
        repository.seed(session("s_a2", "ws_a"))
        repository.seed(session("s_b1", "ws_b"))

        activateWorkspace("ws_a")

        val ids = viewModel.state.value.sessions.map { it.id.value }
        assertEquals(listOf("s_a1", "s_a2"), ids)
    }

    @Test
    fun `GAP-14 - a project-bound workspace sees ONLY that project's private sessions`() {
        repository.seed(session("s_p7", "ws_a", projectId = 7L))
        repository.seed(session("s_p9", "ws_a", projectId = 9L))
        repository.seed(session("s_shared", "ws_a", projectId = null))

        activateWorkspace("ws_a", projectId = 7L)

        val ids = viewModel.state.value.sessions.map { it.id.value }
        assertEquals("Sibling projects' sessions are invisible", listOf("s_p7"), ids)
    }

    @Test
    fun `a project-less workspace sees ONLY the workspace-scoped shared sessions`() {
        repository.seed(session("s_p7", "ws_a", projectId = 7L))
        repository.seed(session("s_shared", "ws_a", projectId = null))

        activateWorkspace("ws_a", projectId = 0L)

        val ids = viewModel.state.value.sessions.map { it.id.value }
        assertEquals(listOf("s_shared"), ids)
    }

    @Test
    fun `a workspace switch re-scopes the list (collector replaced, not stacked)`() {
        repository.seed(session("s_a1", "ws_a"))
        repository.seed(session("s_b1", "ws_b"))

        activateWorkspace("ws_a")
        assertEquals(listOf("s_a1"), viewModel.state.value.sessions.map { it.id.value })

        activateWorkspace("ws_b")
        assertEquals(
            "flatMapLatest: the new scope REPLACES the previous collector",
            listOf("s_b1"),
            viewModel.state.value.sessions.map { it.id.value }
        )
    }

    @Test
    fun `a project switch re-scopes the list within the same workspace`() {
        repository.seed(session("s_p7", "ws_a", projectId = 7L))
        repository.seed(session("s_p8", "ws_a", projectId = 8L))

        activateWorkspace("ws_a", projectId = 7L)
        assertEquals(listOf("s_p7"), viewModel.state.value.sessions.map { it.id.value })

        activateWorkspace("ws_a", projectId = 8L)
        assertEquals(listOf("s_p8"), viewModel.state.value.sessions.map { it.id.value })
    }

    // ------------------------------------------------------------------
    // Browser sheet + deletion
    // ------------------------------------------------------------------

    @Test
    fun `the browser sheet flag opens and closes as feature state`() {
        assertFalse(viewModel.state.value.isSessionBrowserOpen)

        viewModel.setSessionBrowserOpen(true)
        assertTrue(viewModel.state.value.isSessionBrowserOpen)

        viewModel.setSessionBrowserOpen(false)
        assertFalse(viewModel.state.value.isSessionBrowserOpen)
    }

    @Test
    fun `deleteSession goes through the service and fires the callback AFTER the deletion`() {
        activateWorkspace("ws_a")
        repository.seed(session("s_a1", "ws_a"))
        val deleted = mutableListOf<String>()

        viewModel.deleteSession("s_a1") { deleted += it }

        assertEquals(1, repository.deleteCount)
        assertEquals(listOf("s_a1"), deleted)
        assertTrue(
            "The registry row is gone after the callback fires",
            viewModel.state.value.sessions.none { it.id.value == "s_a1" }
        )
    }

    @Test
    fun `the deletion callback fires even when the session does not exist (honest no-op)`() {
        activateWorkspace("ws_a")
        val deleted = mutableListOf<String>()

        viewModel.deleteSession("s_missing") { deleted += it }

        assertEquals(0, repository.deleteCount)
        assertEquals(
            "The callback contract is unconditional — the studio-side effect checks its own binding",
            listOf("s_missing"),
            deleted
        )
    }
}
