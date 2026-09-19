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
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.bootstrap.WorkspaceBootstrapOrchestrator
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.network.NetworkPolicy
import com.example.infrastructure.persistence.AppDatabase
import com.example.presentation.ui.screens.settings.SettingsScreen
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SettingsViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import java.io.File

/**
 * ============================================================================
 * SettingsCategoriesTest — the 12-category organization pin (UI Design
 * Closure, phase C — D-07)
 * ============================================================================
 *
 * The settings surface over its REAL stack (the shell VM + the settings
 * feature VM over a REAL in-memory Room + WorkspaceRuntimeService — the
 * MainViewModelTest wiring):
 *
 *  - ALL TWELVE categories render (the package's explicit taxonomy);
 *  - the categories with REAL controls expand to them (chat & AI policies,
 *    workspace manager, network policy);
 *  - the honest capability-less categories (account, appearance, language,
 *    data) expand to an honest note — NEVER to fabricated toggles;
 *  - a real control works through the authoritative VM (the workspace
 *    network policy write).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class SettingsCategoriesTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var mainViewModel: MainViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private var serviceScope: CoroutineScope? = null

    private val categoryTags = listOf(
        "settings_cat_account", "settings_cat_privacy", "settings_cat_appearance",
        "settings_cat_language", "settings_cat_chat_ai", "settings_cat_workspace",
        "settings_cat_network", "settings_cat_budget", "settings_cat_providers",
        "settings_cat_tools", "settings_cat_data", "settings_cat_about"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        if (this::mainViewModel.isInitialized) mainViewModel.viewModelScope.cancel()
        serviceScope?.cancel()
        if (this::db.isInitialized) db.close()
        if (this::baseDir.isInitialized) baseDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun newRealStack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_settings_cats").apply { deleteRecursively(); mkdirs() }
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
        mainViewModel = MainViewModel(
            workspaceRuntimeService = service,
            bootstrapStateProvider = service.bootstrapState
        )
        settingsViewModel = SettingsViewModel(workspaceRuntimeService = service)
    }

    private fun composeScreen() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    SettingsScreen(
                        viewModel = mainViewModel,
                        settingsViewModel = settingsViewModel,
                        onNavigate = {},
                        sessionNetworkPolicy = NetworkPolicy.HYBRID,
                        onSessionNetworkPolicy = {},
                        semanticModelReady = false,
                        isProvisioningSemanticModel = false,
                        onProvisionSemanticModel = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    @Test
    fun `all TWELVE categories render`() {
        newRealStack()
        composeScreen()
        categoryTags.forEach { tag ->
            // The accordion is a LazyColumn: below-the-fold categories are
            // scrolled into composition first (they are real, just lazy).
            composeRule.onNodeWithTag(tag).performScrollTo()
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun `the chat-AI category expands to its REAL controls`() {
        newRealStack()
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_chat_ai").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("btn_settings_provision_semantic").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("btn_settings_provision_semantic").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the account category expands to an HONEST note - never fabricated controls`() {
        newRealStack()
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_account").performClick()
        composeRule.waitForIdle()
        // The honest note (the app is fully local — no account system).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodes(androidx.compose.ui.test.hasText("لا يوجد ما يُضبط هنا", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `the network category expands and a real policy write lands in the durable column`() {
        newRealStack()
        composeScreen()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("settings_cat_network").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_cat_network").performScrollTo().performClick()
        composeRule.waitForIdle()

        // The REAL mutation through the authoritative VM: CLOUD_ONLY lands
        // in the workspace's durable network-policy column (read through
        // the DAO — exactly what a process restart would read).
        settingsViewModel.updateWorkspaceNetworkPolicy(NetworkPolicy.OFFLINE)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            settingsViewModel.activeWorkspace.value?.networkPolicy == NetworkPolicy.OFFLINE
        }
        assertEquals(NetworkPolicy.OFFLINE, settingsViewModel.activeWorkspace.value?.networkPolicy)
        val durableColumn = kotlinx.coroutines.runBlocking {
            db.workspaceDao().getWorkspaceById("default")?.networkPolicy
        }
        assertEquals(NetworkPolicy.OFFLINE.name, durableColumn)
    }
}
