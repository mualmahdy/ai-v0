package com.example.presentation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.applock.AppLockAuthAvailability
import com.example.application.applock.AppLockAuthenticator
import com.example.application.applock.AppLockService
import com.example.application.bootstrap.WorkspaceBootstrapOrchestrator
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.security.applock.AppLockMode
import com.example.domain.core.security.applock.AppLockState
import com.example.domain.core.ui.ThemeMode
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.preferences.AppLockSettingsDataStore
import com.example.presentation.ui.screens.settings.SettingsScreen
import com.example.presentation.viewmodel.AppLockSettingsViewModel
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SettingsViewModel
import com.example.ui.theme.MyApplicationTheme
import java.io.File
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * SettingsRealControlsTest — STAGE 7 (the conventional-settings redesign)
 * ============================================================================
 *
 * Pins the two categories STAGE 7 converted from honest notes to REAL
 * controls (over the REAL stack: in-memory Room + WorkspaceRuntimeService +
 * the REAL AppLockService over a REAL isolated DataStore file):
 *
 *  - PRIVACY & SECURITY expands to the app-lock controls: the master switch
 *    ON the row, the honest READY availability line, and the mode radios —
 *    flipping the switch goes through the AUTHORITATIVE service (the state
 *    machine locks immediately — fail-closed — and the policy lands in the
 *    DataStore file);
 *  - APPEARANCE expands to the theme radios and a click writes the policy
 *    through the provided lambda (the shell's persistence seam);
 *  - the 12-category taxonomy pins remain intact (SettingsCategoriesTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class SettingsRealControlsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var mainViewModel: MainViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var appLockService: AppLockService
    private lateinit var appLockSettingsStore: AppLockSettingsDataStore
    private lateinit var appLockViewModel: AppLockSettingsViewModel
    private lateinit var lockDataStoreFile: File
    private var serviceScope: CoroutineScope? = null
    private var capturedThemeMode: ThemeMode? = null
    private var capturedScreenshotProtection: Boolean? = null
    // Live policy holder — the settings surface reads it in composition,
    // so flipping it recomposes the switch exactly like the shell's live
    // policy flow would (one setContent per test rule).
    private var screenshotPolicy by androidx.compose.runtime.mutableStateOf(true)

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        baseDir = File(context.filesDir, "test_settings_real").apply { deleteRecursively(); mkdirs() }
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

        // The REAL app-lock stack over an isolated DataStore file.
        lockDataStoreFile = File(context.filesDir, "test_settings_lock_${System.nanoTime()}.preferences_pb")
        appLockSettingsStore = AppLockSettingsDataStore(lockDataStoreFile)
        appLockService = AppLockService(
            settings = appLockSettingsStore,
            auditTrail = null
        )
        appLockService.initialize()
        appLockViewModel = AppLockSettingsViewModel(
            appLockService = appLockService,
            authenticator = ReadyFakeAuthenticator,
            appContext = context
        )
    }

    @After
    fun tearDown() {
        if (this::mainViewModel.isInitialized) mainViewModel.viewModelScope.cancel()
        if (this::appLockViewModel.isInitialized) appLockViewModel.viewModelScope.cancel()
        serviceScope?.cancel()
        if (this::db.isInitialized) db.close()
        if (this::baseDir.isInitialized) baseDir.deleteRecursively()
        if (this::lockDataStoreFile.isInitialized) lockDataStoreFile.delete()
        Dispatchers.resetMain()
    }

    private object ReadyFakeAuthenticator : AppLockAuthenticator {
        override fun checkAvailability(context: Context) = AppLockAuthAvailability.READY
        override fun launchPrompt(
            activity: FragmentActivity,
            onOutcome: (com.example.domain.core.security.applock.AppLockAuthOutcome) -> Unit
        ) = Unit
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
                        appLockViewModel = appLockViewModel,
                        themeMode = ThemeMode.SYSTEM,
                        onThemeMode = { capturedThemeMode = it },
                        screenshotProtection = screenshotPolicy,
                        onScreenshotProtection = { capturedScreenshotProtection = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    @Test
    fun `the lock switch lives INSIDE the lock section and the honest availability shows`() {
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_privacy").performScrollTo().performClick()
        composeRule.waitForIdle()

        // STAGE 7 (user round 4 — the privacy scoping): the switch is a
        // labeled row INSIDE the lock section — NOT on the category header
        // where it read as a master switch governing the whole privacy tab.
        composeRule.onNodeWithTag("row_app_lock_enabled")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("switch_app_lock_enabled")
            .performScrollTo()
            .assertIsDisplayed()
        // The honest READY availability line.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule
                .onAllNodes(androidx.compose.ui.test.hasText("المصادقة متاحة", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `the lock switch is scoped to the lock - flipping it never touches the screenshot policy`() {
        screenshotPolicy = true
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_privacy").performScrollTo().performClick()
        composeRule.waitForIdle()

        // BOTH controls are visible INSIDE the one expanded privacy tab —
        // each its own scoped setting (the user's report: the lock switch
        // used to read as governing the entire tab).
        composeRule.onNodeWithTag("row_app_lock_enabled").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("switch_screenshot_protection").performScrollTo().assertIsDisplayed()

        // Flipping the LOCK switch flips ONLY the lock policy.
        composeRule.onNodeWithTag("switch_app_lock_enabled").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            appLockService.policyFlow.value.enabled
        }
        // The screenshot policy is untouched by the lock flip.
        assertEquals(java.lang.Boolean.TRUE, screenshotPolicy)
        composeRule.onNodeWithTag("switch_screenshot_protection")
            .performScrollTo()
            .assertIsOn()
    }

    @Test
    fun `flipping the lock switch goes through the authoritative service and persists`() {
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_privacy").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switch_app_lock_enabled").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            appLockService.policyFlow.value.enabled
        }

        // Fail-closed: the un-authenticated process session locked NOW.
        assertEquals(AppLockState.AUTHENTICATION_REQUIRED, appLockService.currentState.value)
        // The mode radios are now enabled and AFTER_TIMEOUT is selected (the
        // sane default the settings surface picks).
        composeRule.onNodeWithTag("radio_lock_timeout").performScrollTo().assertIsDisplayed()
        // DURABILITY: read back through the SAME DataStore instance (a
        // second instance over the same file is refused by DataStore's
        // single-active-instance contract — reading through the live one is
        // exactly what a process restart would read from disk).
        val persisted = runBlocking { appLockSettingsStore.loadPolicy() }
        assertTrue(persisted.enabled)
        assertEquals(AppLockMode.AFTER_TIMEOUT, persisted.mode)
    }

    @Test
    fun `the appearance category expands to the theme radios and a click writes the policy`() {
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_appearance").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("radio_theme_dark").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("radio_theme_dark").performClick()
        assertEquals(ThemeMode.DARK, capturedThemeMode)

        composeRule.onNodeWithTag("radio_theme_system").performClick()
        assertEquals(ThemeMode.SYSTEM, capturedThemeMode)
    }

    /**
     * STAGE 7 user round 3: the screenshot-protection (FLAG_SECURE) toggle
     * is a REAL control inside the privacy category — ON by default (secure
     * by default), flips through the provided lambda (the shell owns the
     * store AND the window flag).
     */
    @Test
    fun `the privacy category carries the screenshot-protection toggle and a flip writes the policy`() {
        screenshotPolicy = true
        composeScreen()
        composeRule.onNodeWithTag("settings_cat_privacy").performScrollTo().performClick()
        composeRule.waitForIdle()

        // The toggle is visible INSIDE the expanded privacy section.
        composeRule.onNodeWithTag("switch_screenshot_protection")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()

        // Flipping it OFF writes the inverted policy through the lambda.
        composeRule.onNodeWithTag("switch_screenshot_protection").performClick()
        assertEquals(java.lang.Boolean.FALSE, capturedScreenshotProtection)

        // The shell persisted and re-projected the new policy — the live
        // holder flips the switch OFF without any re-composition of the
        // test harness (exactly the production flow's behavior).
        screenshotPolicy = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("switch_screenshot_protection")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()

        composeRule.onNodeWithTag("switch_screenshot_protection").performClick()
        assertEquals(java.lang.Boolean.TRUE, capturedScreenshotProtection)
    }
}
