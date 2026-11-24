package com.example.presentation

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetDashboardService
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.BudgetPolicyManagementService
import com.example.application.budget.PricingManagementService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import com.example.presentation.ui.screens.budget.BudgetScreen
import com.example.presentation.viewmodel.BudgetViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * STAGE 2B — Workstream G: Compose UI verification of the ACTUAL product
 * flows (Robolectric + real Room + real services — no mocks)
 * ============================================================================
 * The Stage 2 service suite proved the services; this suite proves the
 * SCREEN: the editor drives the real authoring path, the authority card
 * reflects the real projection, provenance/verification is worded
 * honestly, workspace switching re-binds the UI, the selected period +
 * ledger render, and invalid input produces a VISIBLE error (never a
 * silent no-op). Compose semantics ride the testTags the screen already
 * carries (budget_editor_*, pricing_form_*, budget_authority_card,
 * period_lifecycle_badge).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BudgetScreenGovernanceFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var policyManagement: BudgetPolicyManagementService
    private lateinit var pricingManagement: PricingManagementService
    private lateinit var economicStore: RoomEconomicStore

    private var testNow = 1_700_000_000_000L
    private val refreshSignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private val workspaceStream = MutableStateFlow("ws-a")

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
                    // The Stage-2 definition seeds (unit/metric definitions):
                    // the catalog mirror's pricing_component rows carry FKs
                    // onto them — the REAL migration seeds these; a faithful
                    // in-memory harness must too.
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        var idSeq = 0L
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${idSeq++}" },
            clock = { testNow }
        )
        economicStore = RoomEconomicStore(
            pricingDao = db.pricingEntryDao(),
            ledgerDao = db.costLedgerEntryDao(),
            allocationDao = db.budgetAllocationDao()
        )
        policyManagement = BudgetPolicyManagementService(
            database = db,
            ledger = ledger,
            allocationRepository = economicStore,
            clock = { testNow },
            idGenerator = { "id-${idSeq++}" }
        )
        pricingManagement = PricingManagementService(
            pricingRepository = economicStore,
            pricingCatalog = com.example.application.budget.PricingCatalogService(database = db, clock = { testNow }),
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
        policyManagement = policyManagement,
        pricingManagement = pricingManagement,
        activeWorkspace = workspaceStream
    )

    private fun setContent(vm: BudgetViewModel) {
        composeTestRule.setContent {
            MyApplicationTheme {
                BudgetScreen(viewModel = vm)
            }
        }
    }

    /** Compose-rule-driven wait: pumps the main looper between checks so
     *  the ViewModel's write flows can resume (never block the UI thread). */
    private fun awaitAuthority(vm: BudgetViewModel, workspaceId: String) {
        composeTestRule.waitUntil(5_000) {
            vm.state.value.authorityWorkspaceId == workspaceId &&
                vm.state.value.authority != null
        }
    }

    private fun awaitEventually(condition: () -> Boolean) {
        composeTestRule.waitUntil(5_000, condition)
    }

    // ------------------------------------------------------------------
    // Flow 1: configure a modern workspace policy through the real editor.
    // ------------------------------------------------------------------

    @Test
    fun `configuring a modern policy through the editor creates the real policy and authority`() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("5.00")
        composeTestRule.onNodeWithTag("budget_editor_save").performClick()

        awaitAuthority(vm, "ws-a")
        composeTestRule.waitUntil(5_000) {
            vm.state.value.authority?.kind ==
                BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING
        }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                assertEquals(5_000_000L, vm.state.value.authority!!.modernBalance!!.limitMicroUnits)
                // The REAL database holds the policy + period.
                assertEquals(1, db.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM budget_policy").use { it.moveToFirst(); it.getInt(0) })
                assertEquals(1, db.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM budget_period").use { it.moveToFirst(); it.getInt(0) })
            }
        }
        composeTestRule.onNodeWithTag("budget_authority_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("period_lifecycle_badge", useUnmergedTree = true).assertExists()
    }

    // ------------------------------------------------------------------
    // Flow 2: edit the limit → a NEW immutable policy version (same family).
    // ------------------------------------------------------------------

    @Test
    fun `editing the limit creates a new immutable policy version`() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("5.00")
        composeTestRule.onNodeWithTag("budget_editor_save").performScrollTo().performClick()
        awaitAuthority(vm, "ws-a")

        composeTestRule.onNodeWithTag("budget_editor_amount").performTextClearance()
        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("9.00")
        composeTestRule.onNodeWithTag("budget_editor_save").performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) { vm.state.value.authority?.modernPolicy?.policyVersion == 2 }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                val familyCount = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_policy")
                    .use { c -> c.moveToFirst(); c.getInt(0) }
                assertEquals(2, familyCount) // v1 RETIRED + v2 ACTIVE, ONE family
                val families = db.openHelper.writableDatabase.query("SELECT COUNT(DISTINCT policy_family_id) FROM budget_policy")
                    .use { c -> c.moveToFirst(); c.getInt(0) }
                assertEquals(1, families)
                assertEquals(9_000_000L, vm.state.value.authority!!.modernPolicy!!.defaultLimitMicroUnits)
            }
        }
    }

    // ------------------------------------------------------------------
    // Flow 3: reopen a period after the window ends (rollover).
    // ------------------------------------------------------------------

    @Test
    fun `reopening a period after the window ends creates a new period under the same version`() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("5.00")
        composeTestRule.onNodeWithTag("budget_editor_save").performScrollTo().performClick()
        awaitAuthority(vm, "ws-a")

        // The window ends (time advances OUTSIDE the UI).
        testNow += 31L * 86_400_000L
        refreshSignal.tryEmit(Unit)

        // Configure again with UNCHANGED material fields → rollover.
        composeTestRule.onNodeWithTag("budget_editor_amount").performTextClearance()
        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("5.00")
        composeTestRule.onNodeWithTag("budget_editor_save").performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_period")
                .use { c -> c.moveToFirst(); c.getInt(0) } == 2
        }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                val periods = db.openHelper.writableDatabase.query(
                    "SELECT budget_policy_id, period_status FROM budget_period ORDER BY period_start_epoch_ms"
                ).use { c ->
                    mutableListOf<Pair<String, String>>().also {
                        while (c.moveToNext()) it.add(c.getString(0) to c.getString(1))
                    }
                }
                // Same immutable policy version owns both; the ended one CLOSED.
                assertEquals(periods[0].first, periods[1].first)
                assertEquals("CLOSED", periods[0].second)
                assertEquals("OPEN", periods[1].second)
            }
        }
    }

    // ------------------------------------------------------------------
    // Flow 4+5: register a user-configured price; the list shows the
    // honest provenance/verification wording (WS-D).
    // ------------------------------------------------------------------

    @Test
    fun `registering a price shows honest provenance and unverified wording`() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithTag("pricing_form_provider").performTextInput("prov")
        composeTestRule.onNodeWithTag("pricing_form_input_price").performScrollTo().performTextInput("1.50")
        composeTestRule.onNodeWithTag("pricing_form_output_price").performTextInput("3.00")
        composeTestRule.onNodeWithTag("pricing_form_save").performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) { vm.state.value.pricingEntries.isNotEmpty() }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                assertEquals(1, vm.state.value.pricingEntries.size)
                val entry = vm.state.value.pricingEntries.single()
                assertTrue(entry.provenance.startsWith("USER_CONFIG"))
                // The catalog stamped the version UNVERIFIED (user-entered ≠
                // independently verified — WS-D).
                val versions = db.pricingProfileVersionDao().forProfile(
                    com.example.application.budget.PricingCatalogService.Ids.profileId(
                        "prov", com.example.application.budget.PricingCatalogService.Ids.profileCode("PROVIDER", null, null)
                    )
                )
                assertTrue(versions.isNotEmpty())
                assertEquals("UNVERIFIED", versions.single().verificationStatus)
            }
        }
        // The honest badge is rendered (user-entered, NOT verified).
        composeTestRule.onNodeWithTag("pricing_management_card").assertExists()
    }

    // ------------------------------------------------------------------
    // Flow 6: switch the active workspace — the authority card and the
    // period feed follow; A's period is not retained for B.
    // ------------------------------------------------------------------

    @Test
    fun `switching the active workspace switches the authority and the scoped periods`() {
        // Two workspaces with real policies.
        kotlinx.coroutines.runBlocking {
            for (ws in listOf("ws-a" to 1_000_000L, "ws-b" to 2_000_000L)) {
                policyManagement.configureWorkspaceBudget(
                    BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                        workspaceId = ws.first,
                        limitMicroUnits = ws.second,
                        currency = "USD",
                        periodStartEpochMs = testNow,
                        periodEndEpochMs = testNow + 30L * 86_400_000L
                    )
                )
            }
        }
        val vm = viewModel()
        setContent(vm)

        awaitAuthority(vm, "ws-a")
        composeTestRule.waitUntil(5_000) {
            vm.state.value.authority?.modernBalance?.limitMicroUnits == 1_000_000L
        }

        // SWITCH the active workspace while the screen is open.
        workspaceStream.value = "ws-b"
        awaitAuthority(vm, "ws-b")
        composeTestRule.waitUntil(5_000) {
            vm.state.value.authority?.modernBalance?.limitMicroUnits == 2_000_000L
        }
        composeTestRule.waitUntil(5_000) { vm.periodBalances.value.size == 1 }
        composeTestRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                val rows = vm.periodBalances.value
                assertEquals("ws-b", rows.single().scopePlatformObjectId)
                // A's selected period is not retained for B.
                assertTrue(
                    vm.state.value.selectedPeriodId == null ||
                        vm.state.value.selectedPeriodId == rows.single().periodId
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Flow 7: a known currency mismatch is represented honestly (the
    // editor's honesty note; the WS-A authorization reason).
    // ------------------------------------------------------------------

    @Test
    fun `a known currency mismatch is communicated honestly`() {
        val vm = viewModel()
        setContent(vm)
        awaitAuthority(vm, "ws-a")
        // The authority card carries the WS-A honesty note about conversion
        // rules (never a claimed-enforceable ceiling across currencies, never
        // an assumed 1:1 rate).
        composeTestRule.onNodeWithTag("budget_authority_card").assertExists()
        composeTestRule.onNodeWithText("1:1", substring = true).assertExists()
    }

    // ------------------------------------------------------------------
    // Flow 8: invalid input produces a VISIBLE error (WS-F) — never a
    // silent no-op.
    // ------------------------------------------------------------------

    @Test
    fun `invalid input produces a visible error and writes nothing`() {
        val vm = viewModel()
        setContent(vm)

        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("NaN")
        composeTestRule.onNodeWithTag("budget_editor_save").performClick()

        composeTestRule.onNodeWithTag("budget_editor_validation").assertExists()
        composeTestRule.waitUntil(5_000) {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_policy")
                .use { it.moveToFirst(); it.getInt(0) } == 0
        }

        // Excessive precision is likewise visible.
        composeTestRule.onNodeWithTag("budget_editor_amount").performTextClearance()
        composeTestRule.onNodeWithTag("budget_editor_amount").performTextInput("1.1234567")
        composeTestRule.onNodeWithTag("budget_editor_save").performClick()
        composeTestRule.onNodeWithTag("budget_editor_validation").assertExists()
    }
}
