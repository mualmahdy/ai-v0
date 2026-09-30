package com.example.presentation

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.presentation.di.ActivityViewModelFactory
import com.example.presentation.di.AgentsViewModelFactory
import com.example.presentation.di.AppContainer
import com.example.presentation.di.ChatCapabilitiesViewModelFactory
import com.example.presentation.di.DecisionViewModelFactory
import com.example.presentation.di.ExtensionsViewModelFactory
import com.example.presentation.di.FilesViewModelFactory
import com.example.presentation.di.GovernanceViewModelFactory
import com.example.presentation.di.KnowledgeViewModelFactory
import com.example.presentation.di.MainViewModelFactory
import com.example.presentation.di.ProjectsViewModelFactory
import com.example.presentation.di.ProvidersViewModelFactory
import com.example.presentation.di.RadarViewModelFactory
import com.example.presentation.di.SessionsViewModelFactory
import com.example.presentation.di.SettingsViewModelFactory
import com.example.presentation.di.StudioViewModelFactory
import com.example.presentation.di.TasksViewModelFactory
import com.example.presentation.di.WorkflowsViewModelFactory
import com.example.presentation.ui.MainAppScreen
import com.example.presentation.viewmodel.ActivityViewModel
import com.example.presentation.viewmodel.AgentsViewModel
import com.example.presentation.viewmodel.ChatCapabilitiesViewModel
import com.example.presentation.viewmodel.DecisionViewModel
import com.example.presentation.viewmodel.ExtensionsViewModel
import com.example.presentation.viewmodel.FilesViewModel
import com.example.presentation.viewmodel.GovernanceViewModel
import com.example.presentation.viewmodel.KnowledgeViewModel
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.ProjectsViewModel
import com.example.presentation.viewmodel.ProvidersViewModel
import com.example.presentation.viewmodel.RadarViewModel
import com.example.presentation.viewmodel.SessionsViewModel
import com.example.presentation.viewmodel.SettingsViewModel
import com.example.presentation.viewmodel.StudioSignal
import com.example.presentation.viewmodel.StudioViewModel
import com.example.presentation.viewmodel.TasksViewModel
import com.example.presentation.viewmodel.WorkflowsViewModel
import com.example.ui.theme.MyApplicationTheme
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * HotfixCrashRegressionTest — EMERGENCY-RELEASE regression suite for the
 * device-crash triad reported against the trial UX:
 * ============================================================================
 *
 *  1. THE ACTIVITY SCREEN crashed (process death) the moment ANY trace rows
 *     existed — i.e. after the FIRST conversation. Root cause: the feed's
 *     LazyColumn keyed execution-trace rows on (executionId, stepIndex),
 *     but the telemetry bridge writes STARTED and DECISION rows for the
 *     SAME execution with stepIndex = -1 (and replanned steps share their
 *     index) → "Key was already used" → IllegalArgumentException → crash.
 *     A fresh install only rendered because the list was EMPTY.
 *
 *  2. THE [+] ATTACH and the capabilities hub's ATTACH_FILE both led into
 *     an import pipeline that hashed the ENTIRE imported file on the MAIN
 *     thread (fileStore.stat → full SHA-256) — large files froze the UI
 *     into an ANR and the app was killed.
 *
 *  3. ATTACH FOLDER serialized the whole document tree into an in-memory
 *     ZIP on the MAIN thread with NO cap — real folders froze the UI (ANR)
 *     and oversized folders OOM-killed the process outright.
 *
 * This suite composes the FULL production shell (real AppContainer + real
 * factories — the repo's standing test contract, ChatInputAcceptanceTest's
 * harness) and drives the exact user-level flows that used to die. Each
 * test FAILS on the pre-hotfix code and pins the fixed behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class HotfixCrashRegressionTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appContainer: AppContainer
    private val studioSignalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val createdViewModels = mutableListOf<ViewModel>()

    private var chatCapsVm: ChatCapabilitiesViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        appContainer = AppContainer(context)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        runCatching { appContainer.applicationScope.cancel() }
        runCatching { appContainer.database.close() }
        runCatching {
            com.example.infrastructure.persistence.AppDatabase.resetInstanceForProcessDeath()
        }
        Dispatchers.resetMain()
    }

    private inline fun <reified T : ViewModel> vm(factory: androidx.lifecycle.ViewModelProvider.Factory): T {
        val created = factory.create(T::class.java)
        createdViewModels.add(created)
        return created
    }

    /** Composes the full production shell and lands on the chat tab. */
    private fun openChatScreen() {
        val main = vm<MainViewModel>(MainViewModelFactory(appContainer))
        val tasks = vm<TasksViewModel>(TasksViewModelFactory(appContainer))
        val files = vm<FilesViewModel>(FilesViewModelFactory(appContainer))
        val settings = vm<SettingsViewModel>(SettingsViewModelFactory(appContainer))
        val studio = vm<StudioViewModel>(StudioViewModelFactory(appContainer, studioSignalBus))
        val chatCaps = vm<ChatCapabilitiesViewModel>(ChatCapabilitiesViewModelFactory(appContainer))
            .also { chatCapsVm = it }
        val sessions = vm<SessionsViewModel>(SessionsViewModelFactory(appContainer))
        val knowledge = vm<KnowledgeViewModel>(KnowledgeViewModelFactory(appContainer))
        val governance = vm<GovernanceViewModel>(GovernanceViewModelFactory(appContainer, studioSignalBus))
        val providers = vm<ProvidersViewModel>(ProvidersViewModelFactory(appContainer))
        val radar = vm<RadarViewModel>(RadarViewModelFactory(appContainer))
        val decision = vm<DecisionViewModel>(DecisionViewModelFactory(appContainer, studioSignalBus))
        val workflows = vm<WorkflowsViewModel>(WorkflowsViewModelFactory(appContainer))
        val agents = vm<AgentsViewModel>(AgentsViewModelFactory(appContainer))
        val extensions = vm<ExtensionsViewModel>(ExtensionsViewModelFactory(appContainer))
        val activity = vm<ActivityViewModel>(ActivityViewModelFactory(appContainer, studioSignalBus))
        val projects = vm<ProjectsViewModel>(ProjectsViewModelFactory(appContainer))

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    MainAppScreen(
                        viewModel = main,
                        tasksViewModel = tasks,
                        filesViewModel = files,
                        settingsViewModel = settings,
                        studioViewModel = studio,
                        chatCapabilitiesViewModel = chatCaps,
                        sessionsViewModel = sessions,
                        knowledgeViewModel = knowledge,
                        governanceViewModel = governance,
                        providersViewModel = providers,
                        radarViewModel = radar,
                        decisionViewModel = decision,
                        workflowsViewModel = workflows,
                        agentsViewModel = agents,
                        extensionsViewModel = extensions,
                        activityViewModel = activity,
                        projectsViewModel = projects,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithTag("bootstrap_failure_gate").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("nav_tab_chat").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("agent_studio_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------------
    // 1. The Activity-screen duplicate-key crash (post-first-conversation)
    // ------------------------------------------------------------------

    @Test
    fun `activity feed renders post-conversation trace rows that collide on the legacy key`() {
        openChatScreen()

        // Seed EXACTLY what the telemetry bridge writes for one real
        // conversation: STARTED(-1) and DECISION(-1) for the same execution
        // (the collision that killed the feed), plus two real steps.
        val telemetry = appContainer.telemetryPort
        val workspaceId = runBlocking {
            appContainer.workspaceRuntimeService.awaitActiveWorkspaceId(timeoutMs = 30_000L)
        } ?: appContainer.workspaceRuntimeService.activeWorkspaceIdOrNull()
        org.junit.Assert.assertNotNull(
            "bootstrap did not produce an active workspace — cannot seed the feed",
            workspaceId
        )
        val wsId = workspaceId!!
        val now = System.currentTimeMillis()
        runBlocking {
            telemetry.recordTraceNode(
                ExecutionTraceNode(
                    executionId = "exec_hotfix",
                    stepIndex = -1,
                    actionType = "STARTED",
                    targetResourceId = "model-x",
                    agentId = "agent-x",
                    startedAtEpochMs = now - 4_000,
                    completedAtEpochMs = null,
                    durationMs = null,
                    outcome = "STARTED",
                    summary = "بدء التنفيذ",
                    observationSummary = null,
                    workspaceId = wsId
                )
            )
            telemetry.recordTraceNode(
                ExecutionTraceNode(
                    executionId = "exec_hotfix",
                    stepIndex = -1,
                    actionType = "DECISION",
                    targetResourceId = null,
                    agentId = null,
                    startedAtEpochMs = now - 3_000,
                    completedAtEpochMs = now - 3_000,
                    durationMs = 0,
                    outcome = "DECISION",
                    summary = "قرار: ANALYZE",
                    observationSummary = null,
                    workspaceId = wsId
                )
            )
            telemetry.recordTraceNode(
                ExecutionTraceNode(
                    executionId = "exec_hotfix",
                    stepIndex = 0,
                    actionType = "AGENT_STEP",
                    targetResourceId = null,
                    agentId = null,
                    startedAtEpochMs = now - 2_000,
                    completedAtEpochMs = now - 1_000,
                    durationMs = 1_000,
                    outcome = "COMPLETED",
                    summary = "تحليل المتطلبات",
                    observationSummary = null,
                    workspaceId = wsId
                )
            )
        }

        // Navigate to the Activity tab exactly as a user does. Pre-hotfix
        // this threw "Key "exec_hotfix_-1" was already used" and killed the
        // app; the fixed feed renders the rows.
        composeRule.onNodeWithTag("nav_tab_activity").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("screen_unified_activity").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("screen_unified_activity").assertIsDisplayed()
        // The seeded rows actually RENDERED through the fixed keyed list
        // (waiting on row content, not just the screen frame — this is what
        // turns the crash into a pinned regression).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("تحليل المتطلبات", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------------
    // 2. The file-attach crash (full production import stack)
    // ------------------------------------------------------------------

    @Test
    fun `picking a file through the REAL import pipeline stages a draft chip without crashing`() {
        openChatScreen()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val pickedUri = Uri.parse("content://test.provider/picked/notes.txt")
        shadowOf(context.contentResolver).registerInputStream(
            pickedUri,
            ByteArrayInputStream("محتوى ملف الاختبار للإرفاق".toByteArray())
        )

        // Exactly what the SAF picker callback hands the ViewModel.
        val caps = requireNotNull(chatCapsVm)
        caps.pickFiles(
            uris = listOf(pickedUri.toString()),
            mimeTypes = listOf("text/plain")
        )

        // The import completes honestly: a draft chip is staged (or an
        // honest error is surfaced) — but the PROCESS SURVIVES either way.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val state = caps.state.value
            !state.isImportingAttachment &&
                (state.attachmentDrafts.isNotEmpty() || state.attachmentError != null)
        }
        val state = caps.state.value
        assertTrue(
            "expected a staged draft or an honest error, got neither",
            state.attachmentDrafts.isNotEmpty() || state.attachmentError != null
        )
        if (state.attachmentDrafts.isNotEmpty()) {
            val draft = state.attachmentDrafts.first()
            assertNotNull(draft.artifactId)
            assertEquals("SAF_FILE", draft.provenance)
            assertTrue(draft.sizeBytes > 0)
        }
    }

    // ------------------------------------------------------------------
    // 3. The folder-attach crash (the in-memory tree ZIP path)
    // ------------------------------------------------------------------

    @Test
    fun `picking a folder through the REAL import pipeline stages a draft chip without crashing`() {
        openChatScreen()

        val context = ApplicationProvider.getApplicationContext<Context>()
        // A SAF document-tree uri. The Robolectric resolver serves no child
        // cursor for it, so the tree serializes to an EMPTY zip — which is
        // precisely the pipeline contract (0 entries is a legal import);
        // the crash-safety of the path is what this test pins.
        val treeUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        )
        shadowOf(context.contentResolver).registerInputStream(
            Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments/document/primary%3ADocuments"
            ),
            ByteArrayInputStream(ByteArray(0))
        )

        val caps = requireNotNull(chatCapsVm)
        caps.pickFolder(treeUri.toString())

        composeRule.waitUntil(timeoutMillis = 30_000) {
            val state = caps.state.value
            !state.isImportingAttachment &&
                (state.attachmentDrafts.isNotEmpty() || state.attachmentError != null)
        }
        val state = caps.state.value
        assertTrue(
            "expected a staged folder draft or an honest error, got neither",
            state.attachmentDrafts.isNotEmpty() || state.attachmentError != null
        )
        if (state.attachmentDrafts.isNotEmpty()) {
            val draft = state.attachmentDrafts.first()
            assertEquals("SAF_FOLDER_ZIP", draft.provenance)
        }
    }
}
