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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.presentation.di.AppContainer
import com.example.presentation.di.MainViewModelFactory
import com.example.presentation.di.ProvidersViewModelFactory
import com.example.presentation.ui.screens.dashboard.DashboardScreen
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.ProvidersViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * MoreGroupsTest — the three-group capability center (UI Design Closure,
 * phase C — D-06)
 * ============================================================================
 *
 * The flat eight-card grid became the package's taxonomy — Workspace /
 * Intelligence / Governance & System. This suite composes the dashboard
 * over the PRODUCTION wiring (AppContainer factories — the same no-stub
 * contract as NavigationShellTest) and pins:
 *
 *  - the THREE group headers render (with the ten real entries inside
 *    them — no dead cards);
 *  - every entry click routes to its advertised destination (the
 *    reachability contract at the screen's own seam);
 *  - the live stats row is evidence-derived (renders from the providers
 *    feature's real state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class MoreGroupsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appContainer: AppContainer
    private val createdViewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appContainer = AppContainer(ApplicationProvider.getApplicationContext<Context>())
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

    @Test
    fun `the three groups render with their real entries`() {
        val main = MainViewModelFactory(appContainer).create(MainViewModel::class.java)
            .also { createdViewModels.add(it) }
        val providers = ProvidersViewModelFactory(appContainer).create(ProvidersViewModel::class.java)
            .also { createdViewModels.add(it) }

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    DashboardScreen(
                        viewModel = main,
                        providersViewModel = providers,
                        onNavigate = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // The verification is split into the two deterministic viewport
        // bands of the lazy grid (searching for out-of-order tags mid-fling
        // is inherently racy: lazy composition/disposal tracks the fling):
        //
        // TOP band (the initial viewport, no scrolling needed): the stats
        // row + the Workspace group with all four of its entries.
        composeRule.onNodeWithTag("more_stat_providers").assertIsDisplayed()
        listOf(
            "more_group_workspace", "more_tab_explorer", "more_tab_knowledge",
            "more_tab_files", "more_tab_tasks"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }

        // BOTTOM band: scroll deep once, settle, then existence-check the
        // Intelligence and Governance groups (all composed at the bottom).
        repeat(8) {
            composeRule.onNodeWithTag("dashboard_screen").performTouchInput { swipeUp() }
        }
        composeRule.waitForIdle()
        listOf(
            "more_group_intelligence", "more_group_governance",
            "more_tab_providers", "more_tab_decision", "more_tab_radar",
            "more_tab_governance", "more_tab_extensions", "more_tab_settings"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists()
        }
        // The evidence-derived stats row.
        composeRule.onNodeWithTag("more_stat_providers").assertIsDisplayed()
    }

    @Test
    fun `every entry click routes to its advertised destination`() {
        val main = MainViewModelFactory(appContainer).create(MainViewModel::class.java)
            .also { createdViewModels.add(it) }
        val providers = ProvidersViewModelFactory(appContainer).create(ProvidersViewModel::class.java)
            .also { createdViewModels.add(it) }

        val routed = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    DashboardScreen(
                        viewModel = main,
                        providersViewModel = providers,
                        onNavigate = { routed.add(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val entries = listOf(
            "more_tab_explorer" to "explorer",
            "more_tab_knowledge" to "knowledge",
            "more_tab_files" to "files",
            "more_tab_tasks" to "tasks",
            "more_tab_providers" to "providers",
            "more_tab_decision" to "decision",
            "more_tab_radar" to "radar",
            "more_tab_governance" to "governance",
            "more_tab_extensions" to "extensions",
            "more_tab_settings" to "settings"
        )
        entries.forEach { (entryTag, route) ->
            swipeGridUntilComposed(entryTag)
            composeRule.onNodeWithTag(entryTag).performClick()
            composeRule.waitForIdle()
        }
        assertEquals(entries.map { it.second }, routed)
        // Discoverability is preserved (the package's standing rule).
        assertTrue(routed.contains("settings"))
    }
    /**
     * Lazy grids only compose VISIBLE cards — below-the-fold cards need a
     * REAL scroll gesture to enter composition (performScrollTo is
     * unreliable in grids with full-row span-2 header items).
     */
    private fun swipeGridUntilComposed(tag: String, maxSwipes: Int = 14) {
        // Reset to the grid TOP first: tags are searched in top-to-bottom
        // order, but a previous search may have left the grid scrolled deep
        // (earlier cards would then sit ABOVE the viewport — out of reach of
        // any further upward swipes).
        repeat(6) {
            composeRule.onNodeWithTag("dashboard_screen").performTouchInput { swipeDown() }
        }
        var swipes = 0
        while (swipes < maxSwipes) {
            if (composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            composeRule.onNodeWithTag("dashboard_screen").performTouchInput {
                swipeUp()
            }
            swipes++
        }
        composeRule.onNodeWithTag(tag).assertExists()
    }
}
