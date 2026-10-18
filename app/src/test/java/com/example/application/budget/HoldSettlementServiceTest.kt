package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 2 — HoldSettlementServiceTest (I-5, the registered FIRST Stage-2
 * item)
 * ============================================================================
 * The production settlement hooks: every path the accountUsage→settlement
 * cycle can take, with the balance equation recomputed from raw rows at
 * every step (the Stage-1 ح.2 discipline):
 *
 *   actual < hold   → settle + release remainder (G06: no double positive)
 *   actual = hold   → settle to completion
 *   actual > hold   → settle + post the excess (I-11: never truncate)
 *   actual UNKNOWN  → hold kept until lazy expiry (I-10)
 *   hold expired    → actual posted directly
 *   no hold at all  → actual posted under the first active policy (I-3)
 *   no policy       → honest no-op (legacy path untouched)
 *   currency clash  → no settlement (I-18: no silent FX)
 *   replay          → idempotent
 */
@RunWith(RobolectricTestRunner::class)
class HoldSettlementServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var settlement: HoldSettlementService

    private var tick = 1_000L
    private val windowStart = 0L
    private val windowEnd = 10_000_000L

    @Before
    fun setup() {
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
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${tick++}" },
            clock = { tick }
        )
        // Stage 3 (I-18 closure): the FX converter is wired — with no rule
        // registered it stays fail-closed, so the mismatch test below is
        // unaffected (no rule -> the same honest no-op).
        val fx = CurrencyConversionService(database = db, clock = { tick })
        settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, fx = fx, clock = { tick })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createBudget(
        limitMicro: Long = 1_000_000L,
        currency: String = "USD"
    ): BudgetLedgerService.CreatedBudget =
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = "ws-1",
                providerId = null, budgetName = "ws", currency = currency,
                limitMicroUnits = limitMicro,
                periodStartEpochMs = windowStart, periodEndEpochMs = windowEnd,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT
            )
        )

    private fun usage(executionId: String, workspace: String? = "ws-1") = UsageAccountingInput(
        executionId = executionId, taskId = null, workspaceId = workspace, agentId = null,
        providerId = "prov", serviceId = null, modelId = "m1", resourceId = null,
        usage = TokenUsageRecord(inputTokens = 1000, outputTokens = 1000, cachedTokens = 0),
        isActualProviderReport = true, timestampEpochMs = tick
    )

    /** authorize() equivalent: a hold with the gate's exact reason format. */
    private suspend fun holdFor(
        executionId: String,
        amountMicro: Long,
        expiresAtEpochMs: Long? = null
    ) =
        ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = amountMicro,
            reason = "exec:$executionId",
            idempotencyKey = "auth:$executionId:WORKSPACE:ws-1",
            expiresAtEpochMs = expiresAtEpochMs
        ) as BudgetLedgerService.HoldReservation.Reserved

    @Test
    fun `actual below hold - settle then release, equation exact at every step`() = runBlocking {
        val budget = createBudget()
        val hold = holdFor("exec-1", 300_000)
        assertEquals(700_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)

        val summary = settlement.settleExecutionUsage(
            usage("exec-1"), actualCostMicroUnits = 120_000, costCurrency = "USD"
        )

        assertTrue(summary is HoldSettlementService.SettlementSummary.SettledAndReleased)
        assertEquals(120_000L, (summary as HoldSettlementService.SettlementSummary.SettledAndReleased).settledMicroUnits)
        assertEquals(180_000L, summary.releasedMicroUnits)
        // 1_000_000 - 120_000 posted - 0 held = 880_000
        assertEquals(880_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(hold.holdId)!!.status)
    }

    @Test
    fun `actual equals hold - settled to completion`() = runBlocking {
        val budget = createBudget()
        val hold = holdFor("exec-2", 300_000)

        val summary = settlement.settleExecutionUsage(
            usage("exec-2"), actualCostMicroUnits = 300_000, costCurrency = "USD"
        )

        assertTrue(summary is HoldSettlementService.SettlementSummary.Settled)
        assertEquals(BudgetLedgerService.HOLD_SETTLED, db.budgetHoldDao().byId(hold.holdId)!!.status)
        assertEquals(700_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `actual above hold - full settle plus overage posting, never truncated`() = runBlocking {
        val budget = createBudget()
        holdFor("exec-3", 300_000)

        val summary = settlement.settleExecutionUsage(
            usage("exec-3"), actualCostMicroUnits = 450_000, costCurrency = "USD"
        )

        assertTrue(summary is HoldSettlementService.SettlementSummary.SettledWithOverage)
        assertEquals(300_000L, (summary as HoldSettlementService.SettlementSummary.SettledWithOverage).settledMicroUnits)
        assertEquals(150_000L, summary.overagePostedMicroUnits)
        // the FULL actual reached the ledger: 1_000_000 - 450_000 = 550_000
        assertEquals(550_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `UNKNOWN actual keeps the hold until lazy expiry - nothing fabricated`() = runBlocking {
        val budget = createBudget()
        holdFor("exec-4", 300_000)

        val summary = settlement.settleExecutionUsage(
            usage("exec-4"), actualCostMicroUnits = null, costCurrency = "USD"
        )

        assertTrue(summary is HoldSettlementService.SettlementSummary.NoAction)
        assertEquals("UNKNOWN_ACTUAL_COST", (summary as HoldSettlementService.SettlementSummary.NoAction).reason)
        // the hard budget stays protected: 1_000_000 - 300_000 held
        assertEquals(700_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `no hold under an active policy - the actual lands directly (the I-3 promise)`() = runBlocking {
        val budget = createBudget()

        val summary = settlement.settleExecutionUsage(
            usage("exec-5"), actualCostMicroUnits = 80_000, costCurrency = "USD"
        )

        assertTrue(summary is HoldSettlementService.SettlementSummary.PostedWithoutHold)
        assertEquals(920_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `no hold and no policy - honest no-op (legacy path verbatim)`() = runBlocking {
        val summary = settlement.settleExecutionUsage(
            usage("exec-6", workspace = "ws-none"), actualCostMicroUnits = 80_000, costCurrency = "USD"
        )
        assertTrue(summary is HoldSettlementService.SettlementSummary.NoAction)
    }

    @Test
    fun `expired hold - actual posted without the hold`() = runBlocking {
        val budget = createBudget(limitMicro = 1_000_000)
        // BF-17: markExpired only expires holds that CARRY an expiry — a
        // hold without expires_at_epoch_ms is open until settled/released.
        // The original test forgot to give the hold its expiry window.
        val hold = holdFor("exec-7", 300_000, expiresAtEpochMs = windowEnd)
        // force expiry behind the service's back (the lazy-expiry sweep)
        db.budgetHoldDao().markExpired(windowEnd + 1)
        assertEquals(BudgetLedgerService.HOLD_EXPIRED, db.budgetHoldDao().byId(hold.holdId)!!.status)

        val summary = settlement.settleExecutionUsage(
            usage("exec-7"), actualCostMicroUnits = 120_000, costCurrency = "USD"
        )
        assertTrue(summary is HoldSettlementService.SettlementSummary.PostedWithoutHold)
        assertEquals(880_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `currency mismatch - no settlement, no silent FX, hold survives (I-18)`() = runBlocking {
        val budget = createBudget(currency = "EUR")
        holdFor("exec-8", 300_000)

        val summary = settlement.settleExecutionUsage(
            usage("exec-8"), actualCostMicroUnits = 120_000, costCurrency = "USD"
        )

        assertTrue(summary is HoldSettlementService.SettlementSummary.NoAction)
        assertTrue(
            (summary as HoldSettlementService.SettlementSummary.NoAction).reason.startsWith("CURRENCY_MISMATCH")
        )
        assertEquals("the hold keeps protecting the budget", 700_000L,
            ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `currency mismatch WITH a covering rule - I-18 closed with an evidenced conversion (Stage 3)`() = runBlocking {
        val budget = createBudget(currency = "EUR")
        holdFor("exec-fx", 300_000)

        // the treasury rule covering the settlement instant
        CurrencyConversionService(database = db, clock = { tick }).apply {
            registerRule("USD", "EUR", "0.9", 0L, null, "treasury")
        }

        val summary = settlement.settleExecutionUsage(
            usage("exec-fx"), actualCostMicroUnits = 120_000, costCurrency = "USD"
        )

        // 120_000 USD × 0.9 = 108_000 EUR: settled against the 300_000 EUR
        // hold, the remainder released, the ledger in the policy currency.
        assertTrue(
            "expected SettledAndReleased, got $summary",
            summary is HoldSettlementService.SettlementSummary.SettledAndReleased
        )
        summary as HoldSettlementService.SettlementSummary.SettledAndReleased
        assertEquals(108_000L, summary.settledMicroUnits)
        assertEquals(192_000L, summary.releasedMicroUnits)
        assertEquals(892_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }

    @Test
    fun `replaying the same execution is idempotent`() = runBlocking {
        val budget = createBudget()
        holdFor("exec-9", 300_000)

        val first = settlement.settleExecutionUsage(usage("exec-9"), 120_000, "USD")
        val second = settlement.settleExecutionUsage(usage("exec-9"), 120_000, "USD")

        assertTrue(first is HoldSettlementService.SettlementSummary.SettledAndReleased)
        // the replay detects the settle settlement by the execution's key
        // and is a strict no-op — exactly one consumption entry exists.
        assertTrue(second is HoldSettlementService.SettlementSummary.NoAction)
        assertEquals(
            "ALREADY_SETTLED",
            (second as HoldSettlementService.SettlementSummary.NoAction).reason
        )
        val entries = db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM budget_ledger_entry WHERE entry_type='CONSUMPTION'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, entries)
        assertEquals(880_000L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }
}
