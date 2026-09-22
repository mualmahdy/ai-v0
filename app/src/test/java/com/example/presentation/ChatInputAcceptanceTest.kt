package com.example.presentation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
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
import com.example.presentation.ui.navigation.WorkspaceRoutes
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * ChatInputAcceptanceTest — DIAGNOSTIC + regression suite for the reported
 * device defect: "شاشة المحادثة لا تقبل الإدخال" (the chat screen does not
 * accept input).
 * ============================================================================
 *
 * The gap that let the defect slip: every prior Studio suite tested the
 * ViewModel layer; NO test ever composed the real screen and TYPED into the
 * composer. This suite composes the FULL production shell (real AppContainer
 * + real factories — the repo's standing test contract), navigates to the
 * chat destination exactly as a user does (the bottom tab), and exercises
 * the composer end-to-end: field VISIBLE, input ACCEPTED (state round-trip),
 * and the send affordance reachable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ChatInputAcceptanceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appContainer: AppContainer
    private val studioSignalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val createdViewModels = mutableListOf<ViewModel>()
    private var studioVm: StudioViewModel? = null

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

    /** Composes the full shell, then navigates to the chat tab like a user. */
    private fun openChatScreen() {
        val main = vm<MainViewModel>(MainViewModelFactory(appContainer))
        val tasks = vm<TasksViewModel>(TasksViewModelFactory(appContainer))
        val files = vm<FilesViewModel>(FilesViewModelFactory(appContainer))
        val settings = vm<SettingsViewModel>(SettingsViewModelFactory(appContainer))
        val studio = vm<StudioViewModel>(StudioViewModelFactory(appContainer, studioSignalBus))
            .also { studioVm = it }
        val chatCaps = vm<ChatCapabilitiesViewModel>(ChatCapabilitiesViewModelFactory(appContainer))
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
        // Await the REAL bootstrap (the failure gate must NOT be showing).
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithTag("bootstrap_failure_gate").fetchSemanticsNodes().isEmpty()
        }
        // Navigate exactly as the user does.
        composeRule.onNodeWithTag("nav_tab_chat").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("agent_studio_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------------
    // The reported defect, pinned as regressions
    // ------------------------------------------------------------------

    @Test
    fun `the composer text field is VISIBLE on the chat screen`() {
        openChatScreen()
        composeRule.onNodeWithTag("chat_composer").assertIsDisplayed()
        composeRule.onNodeWithTag("prompt_text_field").assertIsDisplayed()
    }

    @Test
    fun `typing into the composer updates the field text (input accepted)`() {
        openChatScreen()
        val field = composeRule.onNodeWithTag("prompt_text_field")
        field.assertIsDisplayed()
        field.performTextInput("مرحبا")
        composeRule.runOnIdle {
            // The controlled round-trip: onValueChange → ViewModel → state →
            // recomposition. If the loop is broken anywhere, the text never
            // lands in the state (the device symptom: typing does nothing).
            val draft = studioVm?.state?.value?.promptInput.orEmpty()
            org.junit.Assert.assertEquals("مرحبا", draft)
        }
        // The field itself renders the accepted text (display round-trip).
        composeRule.onNodeWithTag("prompt_text_field")
            .assertTextsContain("مرحبا")
    }

    @Test
    fun `the send button exists and is clickable once text is entered`() {
        openChatScreen()
        composeRule.onNodeWithTag("prompt_text_field").performTextInput("اختبار الإدخال")
        composeRule.onNodeWithTag("execute_prompt_button").assertHasClickAction()
        composeRule.onNodeWithTag("execute_prompt_button").performClick()
        // The atomic send: the draft is cleared and the USER message appears
        // immediately (P0-B/P0-C) — proving input was truly accepted.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            studioVm?.state?.value?.timeline?.any {
                it is com.example.presentation.state.ChatEntry.User &&
                    it.text == "اختبار الإدخال"
            } == true
        }
    }

    @Test
    fun `the capability plus button is reachable and clickable`() {
        openChatScreen()
        composeRule.onNodeWithTag("btn_open_capabilities").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_open_capabilities").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("capability_menu_sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------------
    // UI POLISH §7 — the composer as a COMMAND SURFACE
    // ------------------------------------------------------------------

    @Test
    fun `the composer context strip carries the hub and agent-model chips`() {
        openChatScreen()
        composeRule.onNodeWithTag("composer_context_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_open_capabilities").assertIsDisplayed()
        composeRule.onNodeWithTag("chip_composer_agent_model").assertIsDisplayed()
    }

    @Test
    fun `the voice placeholder is VISIBLE with the honest unavailable reason`() {
        openChatScreen()
        // §3 UNAVAILABLE ≠ HIDDEN: the mic is composed (never hidden),
        // carries NO click action, and its accessibility label states the
        // real reason — the verified absence of a speech execution path.
        composeRule.onNodeWithTag("voice_input_placeholder").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "الإدخال الصوتي — غير متاح في هذا الإصدار (لا يوجد مسار تنفيذ للصوت)"
        ).assertExists()
    }

    @Test
    fun `the quick attach button sits beside the field`() {
        openChatScreen()
        composeRule.onNodeWithTag("btn_quick_attach").assertIsDisplayed()
        composeRule.onNodeWithTag("prompt_text_field").assertIsDisplayed()
    }

    @Test
    fun `the capability hub renders the four professional groups`() {
        openChatScreen()
        composeRule.onNodeWithTag("btn_open_capabilities").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("capability_menu_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        listOf(
            "capability_group_FILES_AND_CONTEXT",
            "capability_group_SEARCH_AND_INTELLIGENCE",
            "capability_group_MEDIA",
            "capability_group_CREATION"
        ).forEach { groupTag ->
            composeRule.onNodeWithTag(groupTag).assertExists()
        }
    }

    @Test
    fun `UNAVAILABLE is not HIDDEN - media and creation rows stay visible in the hub`() {
        openChatScreen()
        composeRule.onNodeWithTag("btn_open_capabilities").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("capability_menu_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        // The version-level no-execution-path capabilities are PRESENT on
        // the surface (disabled rows with their real reasons) — removing
        // them from the hub is the regression this test pins.
        listOf(
            "capability_VISION_ANALYSIS",
            "capability_IMAGE_GENERATION",
            "capability_SPEECH",
            "capability_CAMERA",
            "capability_SCREEN_SHARE",
            "capability_RESULT_TO_ARTIFACT",
            "capability_DOCUMENT_CREATION",
            "capability_CODE_CREATION",
            "capability_AGENT",
            "capability_WORKFLOW"
        ).forEach { rowTag ->
            composeRule.onNodeWithTag(rowTag).assertExists()
        }
    }

    // ------------------------------------------------------------------
    // The ADAPTIVE layouts share the same Column — the composer must be
    // on-screen at EVERY width class (§19), not only compact.
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = [36], qualifiers = "w700dp-h1000dp")
    fun `MEDIUM layout - sessions pane and composer both visible`() {
        openChatScreen()
        composeRule.onNodeWithTag("sessions_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_composer").assertIsDisplayed()
        composeRule.onNodeWithTag("prompt_text_field").assertIsDisplayed()
        composeRule.onNodeWithTag("prompt_text_field").performTextInput("مرحبا")
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(
                "مرحبا",
                studioVm?.state?.value?.promptInput.orEmpty()
            )
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w900dp-h1000dp")
    fun `EXPANDED layout - context pane and composer both visible`() {
        openChatScreen()
        composeRule.onNodeWithTag("context_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_composer").assertIsDisplayed()
        composeRule.onNodeWithTag("prompt_text_field").assertIsDisplayed()
        composeRule.onNodeWithTag("prompt_text_field").performTextInput("اختبار")
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(
                "اختبار",
                studioVm?.state?.value?.promptInput.orEmpty()
            )
        }
    }
}

/** Asserts the node's editable/text value contains [expected]. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextsContain(expected: String) {
    val texts = fetchSemanticsNode().config[
        androidx.compose.ui.semantics.SemanticsProperties.EditableText
    ]?.text ?: fetchSemanticsNode().config[
        androidx.compose.ui.semantics.SemanticsProperties.Text
    ]?.joinToString("\n") ?: ""
    org.junit.Assert.assertTrue(
        "expected text '$expected' not in '$texts'",
        texts.contains(expected)
    )
}
