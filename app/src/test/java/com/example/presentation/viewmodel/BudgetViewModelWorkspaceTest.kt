package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.example.application.budget.BudgetDashboardService
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.BudgetPolicyManagementService
import com.example.application.budget.HoldKeys
import com.example.application.budget.MoneyInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 2B — Workstream E + F: workspace-aware budget state, dashboard
 * freshness and monetary input integrity (BudgetViewModel, real Room)
 * ============================================================================
 * Order §7 required scenarios:
 *  - Switch workspace A → B while the Budget screen is "open" (a live
 *    collector): A's authority never appears as B's; A's selected period
 *    is not retained for B; the period feed is workspace-scoped.
 *  - Advance the injected clock past a hold's TTL WITHOUT touching the
 *    database → the dashboard's derived balance updates (refresh-signal
 *    re-binding; no mutation, no ViewModel recreation).
 *  - Rapid switching records no workspace inversions (the delayed-read
 *    guard + flatMapLatest cancellation keep a previous workspace's late
 *    projection from overwriting the new one).
 * Order §8 (WS-F): zero / negative / excessive precision / NaN / infinity /
 * maximum amounts — all validated with EXACT decimal arithmetic and a
 * visible error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BudgetViewModelWorkspaceTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var policyManagement: BudgetPolicyManagementService
    private lateinit var economicStore: RoomEconomicStore

    /** Injectable "now" for the dashboard service (TTL freshness tests). */
    private var testNow = 1_700_000_000_000L

    /** Manual refresh signal — deterministic TTL re-binding control. The
     *  replayed initial emission runs the first query on collection. */
    private val refreshSignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

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
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun dashboard() = BudgetDashboardService(
        database = db,
        clock = { testNow },
        refreshSignal = refreshSignal
    )

    private suspend fun configureWorkspace(workspaceId: String, limitMicro: Long = 5_000_000L): String {
        val created = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = workspaceId,
                limitMicroUnits = limitMicro,
                currency = "USD",
                periodStartEpochMs = testNow,
                periodEndEpochMs = testNow + 30L * 86_400_000L
            )
        )
        return (created as BudgetPolicyManagementService.ConfigureOutcome.Created).periodId
    }

    // ------------------------------------------------------------------
    // WS-E 1+2+3+8: workspace switch re-binds authority, clears the stale
    // selected period and scopes the period feed to the new workspace.
    // ------------------------------------------------------------------

    @Test
    fun `switching workspaces rebinds authority and never crosses workspaces`() = runBlockingCompat {
        val periodA = configureWorkspace("ws-a", limitMicro = 1_000_000L)
        val periodB = configureWorkspace("ws-b", limitMicro = 2_000_000L)

        val workspaceStream = MutableStateFlow("ws-a")
        val viewModel = BudgetViewModel(
            dashboardService = dashboard(),
            nowEpochMs = { testNow },
            policyManagement = policyManagement,
            activeWorkspace = workspaceStream
        )
        val job = launch { viewModel.periodBalances.collect {} } // the screen is open

        // On A: A's authority and ONLY A's period.
        withTimeout(5_000) {
            while (viewModel.state.value.authorityWorkspaceId != "ws-a") yield()
        }
        assertEquals("ws-a", viewModel.state.value.authorityWorkspaceId)
        assertEquals(1_000_000L, viewModel.state.value.authority!!.modernBalance!!.limitMicroUnits)
        // Wait for the scoped FEED (value-based: under full-suite load the
        // Room emission can lag the authority projection).
        withTimeout(5_000) {
            while (viewModel.periodBalances.value.map { it.periodId } != listOf(periodA)) yield()
        }
        assertEquals(listOf(periodA), viewModel.periodBalances.value.map { it.periodId })
        // Auto-select landed on A's period.
        withTimeout(5_000) { while (viewModel.state.value.selectedPeriodId != periodA) yield() }
        assertEquals(periodA, viewModel.state.value.selectedPeriodId)

        // SWITCH to B while the screen is open.
        workspaceStream.value = "ws-b"
        withTimeout(5_000) {
            while (viewModel.state.value.authorityWorkspaceId != "ws-b") yield()
        }
        assertEquals("ws-b", viewModel.state.value.authorityWorkspaceId)
        assertEquals(2_000_000L, viewModel.state.value.authority!!.modernBalance!!.limitMicroUnits)
        // B's feed shows ONLY B's period (never A's).
        withTimeout(5_000) {
            while (viewModel.periodBalances.value.map { it.periodId } != listOf(periodB)) yield()
        }
        assertEquals(listOf(periodB), viewModel.periodBalances.value.map { it.periodId })
        // A's selected period was NOT retained for B; B's own period
        // auto-selected instead.
        assertTrue(viewModel.state.value.selectedPeriodId == null || viewModel.state.value.selectedPeriodId == periodB)
        withTimeout(5_000) { while (viewModel.state.value.selectedPeriodId == null) yield() }
        assertEquals(periodB, viewModel.state.value.selectedPeriodId)

        job.cancel()
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------------
    // WS-E 4/6: in STREAM mode with NO workspace the feed is empty and
    // honestly so (never other workspaces' periods).
    // ------------------------------------------------------------------

    @Test
    fun `a null workspace renders an empty scoped feed - never another workspace's periods`() = runBlockingCompat {
        configureWorkspace("ws-a")

        val workspaceStream = MutableStateFlow<String?>(null)
        val viewModel = BudgetViewModel(
            dashboardService = dashboard(),
            nowEpochMs = { testNow },
            policyManagement = policyManagement,
            activeWorkspace = workspaceStream
        )
        val job = launch { viewModel.periodBalances.collect {} }

        withTimeout(5_000) { while (!viewModel.periodBalances.value.isEmpty()) yield() }
        assertTrue(viewModel.periodBalances.value.isEmpty())
        assertNull(viewModel.state.value.authority)

        job.cancel()
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------------
    // WS-E 7: TTL freshness — advance the injected clock past a hold's TTL
    // WITHOUT any database write; the derived balance updates via the
    // refresh signal.
    // ------------------------------------------------------------------

    @Test
    fun `expired reservations stop counting without database mutation or viewmodel recreation`() = runBlockingCompat {
        val periodId = configureWorkspace("ws-a")

        // A live reservation under the 24h TTL.
        ledger.authorizeAndReserve(
            scopeType = "WORKSPACE",
            platformObjectId = "ws-a",
            amountMicroUnits = 1_500L,
            reason = "exec:ttl-test",
            idempotencyKey = "auth:ttl-test",
            expiresAtEpochMs = testNow + HoldKeys.DEFAULT_HOLD_TTL_MS,
            nowEpochMs = testNow
        )
        assertEquals(5_000_000L - 1_500L, ledger.availableForPeriod(periodId, testNow).availableMicroUnits)

        val workspaceStream = MutableStateFlow("ws-a")
        val viewModel = BudgetViewModel(
            dashboardService = dashboard(),
            nowEpochMs = { testNow },
            policyManagement = policyManagement,
            activeWorkspace = workspaceStream
        )
        val job = launch { viewModel.periodBalances.collect {} }
        withTimeout(5_000) {
            while (viewModel.periodBalances.value.isEmpty() ||
                viewModel.periodBalances.value.single().remainingHoldMicroUnits != 1_500L
            ) yield()
        }
        assertEquals(1_500L, viewModel.periodBalances.value.single().remainingHoldMicroUnits)

        // ADVANCE THE CLOCK past the TTL — NO database write, NO recreation.
        testNow += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        val holdStatusBefore = db.openHelper.writableDatabase
            .query("SELECT status FROM budget_hold").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("ACTIVE", holdStatusBefore) // the stored status is untouched

        refreshSignal.tryEmit(Unit) // the dashboard's refresh cadence tick

        withTimeout(5_000) {
            while (viewModel.periodBalances.value.single().remainingHoldMicroUnits != 0L) yield()
        }
        assertEquals(0L, viewModel.periodBalances.value.single().remainingHoldMicroUnits)
        assertEquals(5_000_000L, viewModel.periodBalances.value.single().availableMicroUnits())
        // Still no database mutation.
        val holdStatusAfter = db.openHelper.writableDatabase
            .query("SELECT status FROM budget_hold").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("ACTIVE", holdStatusAfter)

        job.cancel()
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------------
    // WS-E 3 (delayed read): rapid A→B switching records no inversions —
    // a previous workspace's late projection never overwrites the new one.
    // ------------------------------------------------------------------

    @Test
    fun `rapid workspace switching never records an authority inversion`() = runBlockingCompat {
        configureWorkspace("ws-a", limitMicro = 1_000_000L)
        configureWorkspace("ws-b", limitMicro = 2_000_000L)
        configureWorkspace("ws-c", limitMicro = 3_000_000L)

        val workspaceStream = MutableStateFlow("ws-a")
        val viewModel = BudgetViewModel(
            dashboardService = dashboard(),
            nowEpochMs = { testNow },
            policyManagement = policyManagement,
            activeWorkspace = workspaceStream
        )
        val job = launch { viewModel.periodBalances.collect {} }

        val recorded = mutableListOf<String?>()
        val recorder = launch { viewModel.state.collect { recorded.add(it.authorityWorkspaceId) } }

        // Rapid-fire switching while loads are in flight.
        listOf("ws-b", "ws-c", "ws-a", "ws-b", "ws-c").forEach { ws ->
            workspaceStream.value = ws
            yield()
        }
        // Wait for the RECORDER (not state.value — the collector must
        // actually deliver) to observe C's authority.
        withTimeout(5_000) {
            while (viewModel.state.value.authorityWorkspaceId != "ws-c") yield()
            while (recorded.lastOrNull() != "ws-c") yield()
        }

        // Once C's authority is current, NO earlier workspace may reappear.
        assertTrue(
            "no late A/B projection overwrote C: $recorded",
            recorded.indexOfLast { it == "ws-a" } < recorded.indexOfLast { it == "ws-c" } &&
                recorded.indexOfLast { it == "ws-b" } < recorded.indexOfLast { it == "ws-c" }
        )
        // The scoped feed + selection follow the FINAL workspace.
        assertEquals(3_000_000L, viewModel.state.value.authority!!.modernBalance!!.limitMicroUnits)
        assertEquals("ws-c", viewModel.state.value.periodsWorkspaceId)

        recorder.cancel()
        job.cancel()
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------------
    // WS-F: monetary input integrity through the real authoring path.
    // ------------------------------------------------------------------

    @Test
    fun `invalid monetary inputs are rejected with a visible error and write nothing`() = runBlockingCompat {
        val viewModel = BudgetViewModel(
            dashboardService = dashboard(),
            nowEpochMs = { testNow },
            policyManagement = policyManagement,
            activeWorkspaceId = { "ws-1" }
        )
        val job = launch { viewModel.periodBalances.collect {} }

        val invalid = listOf(
            "NaN", "Infinity", "-Infinity", "-5", "5,00", "1.1234567", "abc", "",
            "1000000000001" // above the supported range
        )
        for (text in invalid) {
            viewModel.configureWorkspaceBudget(text, "USD")
            assertNotNull("'$text' must produce a visible validation error", viewModel.state.value.inputError)
            viewModel.dismissInputError()
        }
        // Nothing was persisted by any invalid submission.
        val policyCount = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_policy")
            .use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, policyCount)

        // Invalid currency and period length are visible errors too.
        viewModel.configureWorkspaceBudget("5.00", "US")
        assertNotNull(viewModel.state.value.inputError)
        viewModel.dismissInputError()
        viewModel.configureWorkspaceBudget("5.00", "USD", periodDaysText = "0")
        assertNotNull(viewModel.state.value.inputError)
        viewModel.dismissInputError()
        viewModel.configureWorkspaceBudget("5.00", "USD", periodDaysText = "3660")
        assertNotNull(viewModel.state.value.inputError)
        viewModel.dismissInputError()
        assertEquals(0, policyCount)

        job.cancel()
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    @Test
    fun `valid monetary inputs convert exactly - zero is a real ceiling and max is accepted`() = runBlockingCompat {
        val viewModel = BudgetViewModel(
            dashboardService = dashboard(),
            nowEpochMs = { testNow },
            policyManagement = policyManagement,
            activeWorkspaceId = { "ws-1" }
        )
        val job = launch { viewModel.periodBalances.collect {} }

        // EXACT decimal arithmetic: 5.999999 → 5,999,999 micro (a binary
        // double multiply would have truncated it).
        viewModel.configureWorkspaceBudget("5.999999", "USD")
        // Value-based wait: the init-time NONE projection (modernPolicy
        // null) can land before the write does — wait for the POLICY.
        withTimeout(5_000) {
            while (viewModel.state.value.authority?.modernPolicy?.defaultLimitMicroUnits != 5_999_999L) yield()
        }
        assertEquals(5_999_999L, viewModel.state.value.authority!!.modernPolicy!!.defaultLimitMicroUnits)
        assertNull(viewModel.state.value.inputError)

        // Zero is a REAL ceiling (monitoring), never "unlimited"/"no budget".
        viewModel.configureWorkspaceBudget("0", "usd")
        // Null-safe value wait: a transiently failed load writes a null
        // authority projection; the VALUE is what we wait for.
        withTimeout(5_000) {
            while (viewModel.state.value.authority?.modernBalance?.limitMicroUnits != 0L) yield()
        }
        assertEquals(0L, viewModel.state.value.authority!!.modernBalance!!.limitMicroUnits)

        // The maximum supported amount is accepted exactly (10^12 units).
        viewModel.configureWorkspaceBudget("1000000000000", "EUR")
        withTimeout(5_000) {
            while (viewModel.state.value.authority?.modernPolicy?.defaultLimitMicroUnits != 1_000_000_000_000_000_000L) yield()
        }
        assertEquals(1_000_000_000_000_000_000L, viewModel.state.value.authority!!.modernPolicy!!.defaultLimitMicroUnits)
        assertEquals("EUR", viewModel.state.value.authority!!.modernPolicy!!.currency)

        // Longest period accepted; period math exact.
        viewModel.configureWorkspaceBudget("1", "USD", periodDaysText = "3650")
        withTimeout(5_000) {
            while (viewModel.state.value.authority?.modernPeriod == null ||
                viewModel.state.value.authority!!.modernPeriod!!.periodEndEpochMs -
                viewModel.state.value.authority!!.modernPeriod!!.periodStartEpochMs != 3650L * 86_400_000L
            ) yield()
        }

        job.cancel()
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    // ------------------------------------------------------------------
    // MoneyInput — the pure parser contract (WS-F regression set).
    // ------------------------------------------------------------------

    @Test
    fun `money input parser rejects non-finite out-of-range and over-precise values`() {
        listOf("NaN", "Infinity", "-Infinity", "+5", "-0.1", "0x10", "5,5", "abc")
            .forEach { text ->
                assertTrue("'$text' must be rejected", MoneyInput.parseMicroUnits(text) is MoneyInput.Parsed.Invalid)
            }
        // Scientific notation IS exact decimal (BigDecimal) — accepted
        // precisely, never float-approximated.
        assertEquals(1_000_000_000L, (MoneyInput.parseMicroUnits("1e3") as MoneyInput.Parsed.Micro).microUnits)
        assertTrue(MoneyInput.parseMicroUnits("1.1234567") is MoneyInput.Parsed.Invalid)
        assertTrue(MoneyInput.parseMicroUnits("1000000000001") is MoneyInput.Parsed.Invalid)

        // Valid forms.
        assertEquals(0L, (MoneyInput.parseMicroUnits("0") as MoneyInput.Parsed.Micro).microUnits)
        assertEquals(5_000_000L, (MoneyInput.parseMicroUnits("5") as MoneyInput.Parsed.Micro).microUnits)
        assertEquals(5_999_999L, (MoneyInput.parseMicroUnits("5.999999") as MoneyInput.Parsed.Micro).microUnits)
        assertEquals(1L, (MoneyInput.parseMicroUnits("0.000001") as MoneyInput.Parsed.Micro).microUnits)
        assertEquals(1_000_000_000_000_000_000L, (MoneyInput.parseMicroUnits("1000000000000") as MoneyInput.Parsed.Micro).microUnits)
        // Whitespace tolerated; leading zeros fine.
        assertEquals(1_234_500L, (MoneyInput.parseMicroUnits(" 1.2345 ") as MoneyInput.Parsed.Micro).microUnits)

        // Currency: exactly 3 letters, case-normalized.
        assertEquals("USD", MoneyInput.parseCurrency("usd"))
        assertEquals("EUR", MoneyInput.parseCurrency(" EUR "))
        assertNull(MoneyInput.parseCurrency("US"))
        assertNull(MoneyInput.parseCurrency("USDD"))
        assertNull(MoneyInput.parseCurrency("U1D"))
        assertNull(MoneyInput.parseCurrency(""))
    }

    /** runBlocking with a CoroutineScope receiver for launch/async in bodies. */
    private fun runBlockingCompat(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
