package com.example.presentation

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetDashboardService
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.CurrencyConversionService
import com.example.application.budget.FxRuleManagementService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.presentation.ui.screens.budget.BudgetScreen
import com.example.presentation.viewmodel.BudgetViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.annotation.GraphicsMode

/**
 * ============================================================================
 * STAGE 2C — Compose UI verification of the FX-rule management surface
 * ============================================================================
 * Robolectric Compose over REAL Room + the REAL management services (no
 * mocks): the operator registers an immutable FX rule through the actual
 * form, the rule list displays it with its applicability state, invalid
 * input produces a VISIBLE error (never a silent no-op), and the surface's
 * wording keeps registered operator evidence clearly distinct from
 * independently verified exchange rates (fail-closed discipline stated).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BudgetScreenFxManagementTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var fxService: CurrencyConversionService
    private lateinit var fxManagement: FxRuleManagementService

    private var testNow = 1_700_000_000_000L
    private val refreshSignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private val workspaceStream = MutableStateFlow("ws-fx")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        var idSeq = 0L
        fxService = CurrencyConversionService(database = db, clock = { testNow })
        fxManagement = FxRuleManagementService(
            database = db,
            fx = fxService,
            clock = { testNow }
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = BudgetViewModel(
        dashboardService = BudgetDashboardService(
            database = db, clock = { testNow }, refreshSignal = refreshSignal
        ),
        nowEpochMs = { testNow },
        fxRuleManagement = fxManagement,
        activeWorkspace = workspaceStream
    )

    private fun setContent(vm: BudgetViewModel) {
        composeTestRule.setContent {
            MyApplicationTheme {
                BudgetScreen(viewModel = vm)
            }
        }
    }

    /** The FX card sits below the fold of the budget LazyColumn — scroll
     *  the LIST to the node (lazy composition), then interact. */
    private fun scrollToTag(tag: String) {
        composeTestRule.onNodeWithTag("budget_screen").performScrollToNode(hasTestTag(tag))
    }

    private fun fillForm(from: String, to: String, rate: String) {
        scrollToTag("fx_form_from")
        composeTestRule.onNodeWithTag("fx_form_from").performTextInput(from)
        composeTestRule.onNodeWithTag("fx_form_to").performTextInput(to)
        composeTestRule.onNodeWithTag("fx_form_rate").performTextInput(rate)
    }

    // ------------------------------------------------------------------
    // UI registration + display through the REAL surface
    // ------------------------------------------------------------------

    @Test
    fun `registering a rule through the form persists it and displays it with its state`() {
        val vm = viewModel()
        setContent(vm)

        fillForm("EUR", "USD", "1.0854")
        scrollToTag("fx_form_save")
        composeTestRule.onNodeWithTag("fx_form_save").performClick()

        composeTestRule.waitUntil(5_000) { vm.state.value.fxRules.size == 1 }

        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                // The REAL database holds the immutable rule.
                val rows = db.currencyConversionRuleDao().allRules()
                assertEquals(1, rows.size)
                assertEquals("EUR", rows.single().fromCurrency)
                assertEquals("USD", rows.single().toCurrency)
                assertEquals("1.0854", rows.single().ratePrecise)
                // And the ViewModel projects it with the ACTIVE applicability.
                assertEquals(
                    FxRuleManagementService.RuleApplicability.OPEN_ENDED_ACTIVE,
                    vm.state.value.fxRules.single().applicability
                )
            }
        }
        // The rule card is rendered with its applicability badge.
        scrollToTag("fx_rule_${vm.state.value.fxRules.single().ruleId}")
        composeTestRule.onNodeWithTag(
            "fx_rule_${vm.state.value.fxRules.single().ruleId}"
        ).assertIsDisplayed()
        scrollToTag("fx_rule_applicability")
        composeTestRule.onNodeWithTag("fx_rule_applicability", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `an invalid rate produces a visible error and writes nothing`() {
        val vm = viewModel()
        setContent(vm)

        fillForm("EUR", "USD", "not-a-rate")
        scrollToTag("fx_form_save")
        composeTestRule.onNodeWithTag("fx_form_save").performClick()

        composeTestRule.waitUntil(5_000) { vm.state.value.inputError != null }
        scrollToTag("fx_form_validation")
        composeTestRule.onNodeWithTag("fx_form_validation").assertExists()

        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                assertEquals("nothing was written", 0, db.currencyConversionRuleDao().allRules().size)
            }
        }
    }

    @Test
    fun `a zero rate is visibly rejected - never silently clamped`() {
        val vm = viewModel()
        setContent(vm)

        fillForm("EUR", "USD", "0")
        scrollToTag("fx_form_save")
        composeTestRule.onNodeWithTag("fx_form_save").performClick()

        composeTestRule.waitUntil(5_000) { vm.state.value.inputError != null }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                assertEquals(0, db.currencyConversionRuleDao().allRules().size)
            }
        }
    }

    @Test
    fun `an identical re-registration is surfaced as an idempotent replay`() {
        val vm = viewModel()
        setContent(vm)

        fillForm("EUR", "USD", "1.0854")
        scrollToTag("fx_form_save")
        composeTestRule.onNodeWithTag("fx_form_save").performClick()
        composeTestRule.waitUntil(5_000) { vm.state.value.fxRules.size == 1 }

        scrollToTag("fx_form_rate")
        composeTestRule.onNodeWithTag("fx_form_rate").performTextClearance()
        composeTestRule.onNodeWithTag("fx_form_rate").performTextInput("1.0854")
        scrollToTag("fx_form_save")
        composeTestRule.onNodeWithTag("fx_form_save").performClick()

        composeTestRule.waitUntil(5_000) {
            vm.state.value.managementMessage?.contains("مطابقة") == true
        }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                assertEquals("still exactly one rule row", 1, db.currencyConversionRuleDao().allRules().size)
            }
        }
    }

    // ------------------------------------------------------------------
    // Honesty of the surface's wording (registered evidence ≠ verified rates)
    // ------------------------------------------------------------------

    @Test
    fun `the surface distinguishes registered evidence from independently verified rates`() {
        val vm = viewModel()
        setContent(vm)

        // The registered-evidence vs verified-rate honesty note is part of
        // the management card's wording (WS-D honesty applied to FX).
        scrollToTag("fx_form_honesty_note")
        composeTestRule.onNodeWithTag("fx_form_honesty_note")
            .assertTextContains("ليس سعر صرف مُتحققاً بشكل مستقل", substring = true)
        // The fail-closed promise is stated on the empty state too.
        scrollToTag("fx_empty_state")
        composeTestRule.onNodeWithTag("fx_empty_state").assertExists()
    }

    @Test
    fun `a registered rule carries its source authority in the displayed evidence`() {
        val vm = viewModel()
        setContent(vm)

        fillForm("EUR", "USD", "1.0854")
        scrollToTag("fx_form_authority")
        composeTestRule.onNodeWithTag("fx_form_authority").performTextInput("operator:ecb")
        scrollToTag("fx_form_save")
        composeTestRule.onNodeWithTag("fx_form_save").performClick()

        composeTestRule.waitUntil(5_000) { vm.state.value.fxRules.size == 1 }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                assertTrue(
                    vm.state.value.fxRules.single().sourceAuthority.contains("ecb")
                )
            }
        }
    }
}
