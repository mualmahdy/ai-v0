package com.example.presentation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.presentation.di.ActivityViewModelFactory
import com.example.presentation.di.AgentsViewModelFactory
import com.example.presentation.di.AppContainer
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
import com.example.presentation.ui.navigation.NavWidthClass
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.ui.navigation.navWidthClassForWidthDp
import com.example.presentation.ui.navigation.topLevelDestinations
import com.example.presentation.viewmodel.ActivityViewModel
import com.example.presentation.viewmodel.AgentsViewModel
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * NavigationShellTest — the navigation semantics suite (UI Design Closure,
 * phases B/C — the package's explicit test requirements)
 * ============================================================================
 *
 * The FIRST compose coverage of the full app shell, over the PRODUCTION
 * wiring: one REAL AppContainer (the exact factories MainActivity uses —
 * no service stubbing, the repo's standing test contract) and every
 * feature ViewModel created through its production factory.
 *
 * Pins (the package's mandated regressions):
 *
 *  1. ICON DEDUPLICATION (D-03/D-04): the ONE taxonomy's five destinations
 *     carry five DISTINCT icons (Home no longer shares Psychology with
 *     Chat; Projects' open-folder is not the Explorer compass).
 *  2. THE PROJECTS ROUTE (D-01): tapping «المشاريع» opens the REAL
 *     projects surface — and the regression this route ever again opening
 *     the Tasks board MUST FAIL HERE (the package: "if PROJECTS →
 *     TasksScreen recurs without an explicit decision, new tests fail").
 *  3. MORE REACHABILITY: every secondary route the capability center
 *     advertises actually opens its real screen (no dead entries).
 *  4. THE ADAPTIVE SHELL (D-10): compact widths get the bottom bar, and
 *     medium/expanded widths get the rail (canonical 600/840dp M3
 *     breakpoints — the width-classifier is pinned too).
 *  5. THE HOME WORK CENTER (D-05): the shell's start destination is the
 *     work center with the honest context header and the new-session CTA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class NavigationShellTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appContainer: AppContainer
    private val studioSignalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)
    private val createdViewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        appContainer = AppContainer(context)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        // CROSS-TEST ISOLATION (the documented process-death pattern):
        // AppDatabase.getInstance is a JVM-level singleton that OUTLIVES each
        // Robolectric test environment. Without closing the shared DB and
        // resetting the singleton, every LATER test in the same JVM inherits
        // a database bound to a DEAD environment — its first query never
        // lands and compose waitUntil calls time out. Production never does
        // this (one open DB per process); the test JVM must.
        runCatching { appContainer.applicationScope.cancel() }
        runCatching { appContainer.database.close() }
        runCatching {
            com.example.infrastructure.persistence.AppDatabase.resetInstanceForProcessDeath()
        }
        Dispatchers.resetMain()
    }

    /** Creates a VM through its PRODUCTION factory (never a stub). */
    private inline fun <reified T : ViewModel> vm(factory: androidx.lifecycle.ViewModelProvider.Factory): T {
        val created = factory.create(T::class.java)
        createdViewModels.add(created)
        return created
    }

    private fun buildAndCompose() {
        val main = vm<MainViewModel>(MainViewModelFactory(appContainer))
        val tasks = vm<TasksViewModel>(TasksViewModelFactory(appContainer))
        val files = vm<FilesViewModel>(FilesViewModelFactory(appContainer))
        val settings = vm<SettingsViewModel>(SettingsViewModelFactory(appContainer))
        val studio = vm<StudioViewModel>(StudioViewModelFactory(appContainer, studioSignalBus))
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
    }

    // ------------------------------------------------------------------
    // 1. Icon deduplication (taxonomy-level pin)
    // ------------------------------------------------------------------

    @Test
    fun `the five primary destinations carry five DISTINCT icons`() {
        val icons = topLevelDestinations.map { it.icon }
        assertEquals(5, topLevelDestinations.size)
        // Material icon singletons: reference equality IS icon identity.
        assertEquals("icon duplication in the bottom-bar taxonomy (D-03)", 5, icons.distinct().size)
        // Home must not be the Chat icon (D-03: both were Psychology), and
        // Projects must not be the plain Folder family glyph (D-04: the
        // quick-links collision) — the two documented collisions.
        val home = topLevelDestinations.first { it.route == WorkspaceRoutes.HOME }
        val chat = topLevelDestinations.first { it.route == WorkspaceRoutes.STUDIO }
        val projects = topLevelDestinations.first { it.route == WorkspaceRoutes.PROJECTS }
        assertNotEquals(home.icon, chat.icon)
        assertNotEquals(projects.icon, Icons.Default.Folder)
        // And the pinned identity is exactly the deduplicated set.
        assertEquals(Icons.Default.Home, home.icon)
        assertEquals(Icons.AutoMirrored.Filled.Chat, chat.icon)
        assertEquals(Icons.Default.FolderOpen, projects.icon)
    }

    @Test
    fun `the width classifier follows the canonical M3 breakpoints`() {
        assertEquals(NavWidthClass.COMPACT, navWidthClassForWidthDp(411))
        assertEquals(NavWidthClass.COMPACT, navWidthClassForWidthDp(599))
        assertEquals(NavWidthClass.MEDIUM, navWidthClassForWidthDp(600))
        assertEquals(NavWidthClass.MEDIUM, navWidthClassForWidthDp(839))
        assertEquals(NavWidthClass.EXPANDED, navWidthClassForWidthDp(840))
        assertEquals(NavWidthClass.EXPANDED, navWidthClassForWidthDp(1280))
    }

    // ------------------------------------------------------------------
    // 2. The PROJECTS regression guard (D-01)
    // ------------------------------------------------------------------

    @Test
    fun `tapping المشاريع opens the REAL projects surface - never the Tasks board`() {
        buildAndCompose()

        composeRule.onNodeWithTag("nav_tab_projects").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("projects_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("projects_screen").assertIsDisplayed()

        // THE REGRESSION GUARD (the package's explicit mandate): if the
        // PROJECTS destination ever opens TasksScreen again — without an
        // explicit product decision — this assertion FAILS the suite.
        assertTrue(
            "PROJECTS must never open the Tasks board again (D-01 regression)",
            composeRule.onAllNodesWithTag("screen_tasks_workflows").fetchSemanticsNodes().isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // 3. The Home work center (D-05)
    // ------------------------------------------------------------------

    @Test
    fun `the shell starts on the work center with the honest context and CTA`() {
        buildAndCompose()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home_context_header").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_home_new_session").assertIsDisplayed()
        composeRule.onNodeWithTag("home_project_card").assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // 4. More reachability — every advertised route opens its real screen
    // ------------------------------------------------------------------

    @Test
    fun `every المزيد route opens its advertised real screen`() {
        buildAndCompose()

        val routes = listOf(
            "more_tab_explorer" to "screen_workspace_explorer",
            "more_tab_knowledge" to "screen_knowledge_rag",
            "more_tab_files" to "files_workspace_screen",
            "more_tab_tasks" to "screen_tasks_workflows",
            "more_tab_providers" to "providers_screen",
            "more_tab_decision" to "screen_decision_intelligence",
            "more_tab_radar" to "screen_radar_evolution",
            "more_tab_governance" to "governance_screen",
            "more_tab_extensions" to "screen_extensions",
            "more_tab_settings" to "screen_settings"
        )
        routes.forEach { (entryTag, screenTag) ->
            composeRule.onNodeWithTag("nav_tab_more").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithTag("dashboard_screen").fetchSemanticsNodes().isNotEmpty()
            }
            // Lazy grids only compose VISIBLE cards; performScrollTo is
            // unreliable in grids with span-2 header rows — swipe for real.
            var swipes = 0
            while (swipes < 12 &&
                composeRule.onAllNodesWithTag(entryTag).fetchSemanticsNodes().isEmpty()
            ) {
                composeRule.onNodeWithTag("dashboard_screen").performTouchInput { swipeUp() }
                swipes++
            }
            composeRule.onNodeWithTag(entryTag).performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithTag(screenTag).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(screenTag).assertIsDisplayed()
        }
    }

    // ------------------------------------------------------------------
    // 5. The adaptive shell (D-10)
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp")
    fun `compact widths get the bottom NavigationBar`() {
        buildAndCompose()
        composeRule.onNodeWithTag("main_bottom_nav").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("main_nav_rail").fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h600dp")
    fun `expanded widths get the side NavigationRail`() {
        buildAndCompose()
        composeRule.onNodeWithTag("main_nav_rail").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("main_bottom_nav").fetchSemanticsNodes().isEmpty())
        // The rail navigates too: the five tabs are reachable from it.
        composeRule.onNodeWithTag("nav_tab_projects").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("projects_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
