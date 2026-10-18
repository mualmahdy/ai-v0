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
 * STAGE 6 — G06 acceptance: comprehensive hold-cycle tests
 * ============================================================================
 * PDM-G06: "تحرير الحجز دون أثر موجب مزدوج في الدفتر — يحتاج اختبارات دورات
 * حجز شاملة". The mechanism shipped with Stage 1 (a RELEASE settlement with
 * ledger_entry_id = NULL — never a ledger entry) and Stage 2 wired every
 * settlement path; this suite is the comprehensive cycle evidence the study
 * demands before the gate can close:
 *
 *   1. A 25-cycle churn with the balance equation recomputed from raw rows
 *      at every step (the Stage-1 ح.2 discipline).
 *   2. The aggregate invariant across the whole run: the sum of posted
 *      consumption effects equals the settled micro exactly — releases
 *      contribute NOTHING to the ledger, ever (no double positive effect).
 *   3. Every terminal-state arm: double release rejected, settle after
 *      release rejected, settle after SETTLED rejected.
 *   4. The multi-hold execution (F-1, fixed this stage): the oldest hold
 *      takes the settlement, every other mutable hold is RELEASED, and
 *      availability recovers deterministically.
 *   5. The replay-after-partial-settle arm (F-2, fixed this stage): a
 *      replayed settle NEVER fabricates an overage entry.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetHoldCycleAcceptanceTest {

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
        val fx = CurrencyConversionService(database = db, clock = { tick })
        settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, fx = fx, clock = { tick })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createBudget(
        limitMicro: Long = 1_000_000L
    ): BudgetLedgerService.CreatedBudget =
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = "ws-1",
                providerId = null, budgetName = "ws", currency = "USD",
                limitMicroUnits = limitMicro,
                periodStartEpochMs = windowStart, periodEndEpochMs = windowEnd,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT
            )
        )

    private fun usage(executionId: String) = UsageAccountingInput(
        executionId = executionId, taskId = null, workspaceId = "ws-1", agentId = null,
        providerId = "prov", serviceId = null, modelId = "m1", resourceId = null,
        usage = TokenUsageRecord(inputTokens = 1000, outputTokens = 1000, cachedTokens = 0),
        isActualProviderReport = true, timestampEpochMs = tick
    )

    /** authorize() equivalent: a hold with the gate's exact reason format. */
    private suspend fun holdFor(
        executionId: String,
        amountMicro: Long,
        idempotencySuffix: String = "WORKSPACE:ws-1"
    ) =
        ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = amountMicro,
            reason = "exec:$executionId",
            idempotencyKey = "auth:$executionId:$idempotencySuffix",
            expiresAtEpochMs = null
        ) as BudgetLedgerService.HoldReservation.Reserved

    /** The G06 aggregate invariant, recomputed from raw rows only. */
    private suspend fun assertG06Aggregate(periodId: String, expectedSettledMicro: Long) {
        val entries = db.budgetLedgerEntryDao().forPeriod(periodId)
        // Every posted entry is a consumption (a release adds none).
        entries.forEach {
            assertEquals("only CONSUMPTION entries may exist", BudgetLedgerService.ENTRY_CONSUMPTION, it.entryType)
            assertEquals("only POSTED state may exist", BudgetLedgerService.POSTING_POSTED, it.postingState)
        }
        // The double-positive invariant: posted effects == settled micro exactly.
        val postedNegative = entries.sumOf { it.availableEffectMicroUnits }
        assertEquals("posted effects must equal the settled micro (negated)", -expectedSettledMicro, postedNegative)
        // Every RELEASE settlement is entry-less.
        val holds = db.budgetHoldDao().forPeriod(periodId)
        holds.forEach { h ->
            db.budgetHoldSettlementDao().forHold(h.holdId)
                .filter { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
                .forEach { rs ->
                    assertEquals("a RELEASE settlement never carries a ledger entry", null, rs.ledgerEntryId)
                }
        }
    }

    // ------------------------------------------------------------------
    // 1. The churn: 25 full authorize→settle→release cycles
    // ------------------------------------------------------------------

    @Test
    fun `G06 churn - 25 settle-and-release cycles keep the equation exact at every step`() = runBlocking {
        val limit = 1_000_000L
        val budget = createBudget(limit)
        val periodId = budget.periodId
        val holdMicro = 40_000L
        val actualMicro = 30_000L

        var expectedAvailable = limit
        var totalSettled = 0L

        for (cycle in 1..25) {
            val exec = "churn-$cycle"
            holdFor(exec, holdMicro)
            expectedAvailable -= holdMicro
            assertEquals(
                "step $cycle after authorize",
                expectedAvailable,
                ledger.availableForPeriod(periodId).availableMicroUnits
            )

            val outcome = settlement.settleExecutionUsage(usage(exec), actualMicro, "USD")
            assertTrue("cycle $cycle must settle-and-release", outcome is HoldSettlementService.SettlementSummary.SettledAndReleased)
            // 40k hold: 30k settled (posted) + 10k released (never posted)
            expectedAvailable += holdMicro - actualMicro
            totalSettled += actualMicro
            assertEquals(
                "step $cycle after settle+release",
                expectedAvailable,
                ledger.availableForPeriod(periodId).availableMicroUnits
            )
        }

        // Exactly one consumption entry per cycle — releases added none.
        assertEquals(25, db.budgetLedgerEntryDao().forPeriod(periodId).size)
        assertG06Aggregate(periodId, totalSettled)
    }

    // ------------------------------------------------------------------
    // 2. Replaying every cycle changes nothing (the idempotent hammer)
    // ------------------------------------------------------------------

    @Test
    fun `G06 replaying all 25 cycles is a strict no-op - no new entries, no new settlements`() = runBlocking {
        val limit = 1_000_000L
        val budget = createBudget(limit)
        val periodId = budget.periodId

        for (cycle in 1..25) {
            val exec = "churn-$cycle"
            holdFor(exec, 40_000L)
            settlement.settleExecutionUsage(usage(exec), 30_000L, "USD")
        }
        val entriesBefore = db.budgetLedgerEntryDao().forPeriod(periodId).size

        for (cycle in 1..25) {
            val replay = settlement.settleExecutionUsage(usage("churn-$cycle"), 30_000L, "USD")
            assertTrue(
                "replay of churn-$cycle must be a no-action",
                replay is HoldSettlementService.SettlementSummary.NoAction
            )
        }

        assertEquals("no new entries after 25 replays", entriesBefore, db.budgetLedgerEntryDao().forPeriod(periodId).size)
        assertG06Aggregate(periodId, 25 * 30_000L)
    }

    // ------------------------------------------------------------------
    // 3. Release after a partial settle — exact, and silent in the ledger
    // ------------------------------------------------------------------

    @Test
    fun `G06 release after partial settle keeps the equation exact and posts nothing`() = runBlocking {
        val budget = createBudget(500_000L)
        val periodId = budget.periodId

        val hold = holdFor("partial-1", 200_000L)
        // A direct partial settle (the ledger-level arm): 80k of 200k.
        ledger.settleHold(hold.holdId, 80_000L, "settle-partial-1")
        assertEquals(300_000L, ledger.availableForPeriod(periodId).availableMicroUnits)

        val release = ledger.releaseHold(hold.holdId, "release-partial-1")
            as BudgetLedgerService.ReleaseOutcome.Released
        assertEquals(120_000L, release.releasedMicroUnits)
        // 500k - 80k posted - 0 hold remaining = 420k
        assertEquals(420_000L, ledger.availableForPeriod(periodId).availableMicroUnits)
        // THE G06 assertion: the release added no ledger entry at all.
        assertEquals(1, db.budgetLedgerEntryDao().forPeriod(periodId).size)
        assertG06Aggregate(periodId, 80_000L)
    }

    // ------------------------------------------------------------------
    // 4. Terminal-state rejections — a released/settled hold never moves again
    // ------------------------------------------------------------------

    @Test
    fun `G06 double release with a different key is rejected - InvalidHoldState`() = runBlocking {
        val budget = createBudget()
        val hold = holdFor("rel-1", 100_000L)

        val first = ledger.releaseHold(hold.holdId, "release-key-1")
            as BudgetLedgerService.ReleaseOutcome.Released
        assertEquals(100_000L, first.releasedMicroUnits)

        val second = ledger.releaseHold(hold.holdId, "release-key-DIFFERENT")
        assertTrue(
            "a second release under a different key must be rejected",
            second is BudgetLedgerService.ReleaseOutcome.InvalidHoldState
        )
        assertEquals(
            BudgetLedgerService.HOLD_RELEASED,
            (second as BudgetLedgerService.ReleaseOutcome.InvalidHoldState).status
        )
        assertEquals(0, db.budgetLedgerEntryDao().forPeriod(budget.periodId).size)
    }

    @Test
    fun `G06 settle after a full release is rejected - a released hold never resurrects`() = runBlocking {
        val budget = createBudget()
        val hold = holdFor("res-1", 100_000L)
        ledger.releaseHold(hold.holdId, "release-res-1")

        val settle = ledger.settleHold(hold.holdId, 40_000L, "settle-res-1")
        assertTrue(settle is BudgetLedgerService.SettlementOutcome.InvalidHoldState)
        assertEquals(
            BudgetLedgerService.HOLD_RELEASED,
            (settle as BudgetLedgerService.SettlementOutcome.InvalidHoldState).status
        )
        assertEquals(0, db.budgetLedgerEntryDao().forPeriod(budget.periodId).size)
    }

    @Test
    fun `G06 settle after a terminal SETTLED hold is rejected with the exact numbers`() = runBlocking {
        val budget = createBudget()
        val hold = holdFor("term-1", 100_000L)
        val settled = ledger.settleHold(hold.holdId, 100_000L, "settle-term-1")
            as BudgetLedgerService.SettlementOutcome.Settled
        assertEquals(BudgetLedgerService.HOLD_SETTLED, settled.holdStatusAfter)

        val more = ledger.settleHold(hold.holdId, 1L, "settle-term-1-extra")
        assertTrue("a fully settled hold must reject further settlement", more is BudgetLedgerService.SettlementOutcome.InvalidHoldState)
        assertEquals(1, db.budgetLedgerEntryDao().forPeriod(budget.periodId).size)
    }

    // ------------------------------------------------------------------
    // 5. The multi-hold execution (F-1, fixed this stage)
    // ------------------------------------------------------------------

    @Test
    fun `G06 multi-hold execution - oldest settled, the rest released, availability recovers (F-1)`() = runBlocking {
        val budget = createBudget(1_000_000L)
        val periodId = budget.periodId

        // Two holds for ONE execution (defensive reality the Stage-2 comment
        // already promised to handle deterministically).
        val oldest = holdFor("multi-1", 40_000L, "WORKSPACE:ws-1")
        val second = holdFor("multi-1", 50_000L, "WORKSPACE:ws-1:extra")
        assertEquals(910_000L, ledger.availableForPeriod(periodId).availableMicroUnits)

        val outcome = settlement.settleExecutionUsage(usage("multi-1"), 30_000L, "USD")
        assertTrue(outcome is HoldSettlementService.SettlementSummary.SettledAndReleased)

        // The oldest hold: 30k settled + 10k released.
        val oldestSettlements = db.budgetHoldSettlementDao().forHold(oldest.holdId)
        assertEquals(2, oldestSettlements.size)
        assertEquals(30_000L, oldestSettlements.first { it.settlementType == "SETTLE" }.amountMicroUnits)
        assertEquals(10_000L, oldestSettlements.first { it.settlementType == "RELEASE" }.amountMicroUnits)

        // F-1: the second hold is RELEASED — not left blocking until lazy expiry.
        val secondSettlements = db.budgetHoldSettlementDao().forHold(second.holdId)
        assertEquals(
            "the remaining hold must be released exactly once",
            1,
            secondSettlements.count { it.settlementType == "RELEASE" }
        )
        assertEquals(50_000L, secondSettlements.first { it.settlementType == "RELEASE" }.amountMicroUnits)
        assertEquals(
            BudgetLedgerService.HOLD_RELEASED,
            db.budgetHoldDao().byId(second.holdId)!!.status
        )

        // Availability recovered: only the 30k actual is spent.
        assertEquals(970_000L, ledger.availableForPeriod(periodId).availableMicroUnits)
        // And the release of the second hold posted NOTHING (G06).
        assertG06Aggregate(periodId, 30_000L)

        // A replay is the honest no-op — never a second settle on the rest.
        val replay = settlement.settleExecutionUsage(usage("multi-1"), 30_000L, "USD")
        assertTrue(replay is HoldSettlementService.SettlementSummary.NoAction)
        assertEquals("ALREADY_SETTLED", (replay as HoldSettlementService.SettlementSummary.NoAction).reason)
        assertG06Aggregate(periodId, 30_000L)
    }

    // ------------------------------------------------------------------
    // 6. The replay-after-partial-settle arm (F-2, fixed this stage)
    // ------------------------------------------------------------------

    @Test
    fun `G06 replay after a partial settle NEVER fabricates an overage entry (F-2 + F-3)`() = runBlocking {
        val budget = createBudget(1_000_000L)
        val periodId = budget.periodId

        // Reproduce the race windows deterministically: the execution's settle
        // key already exists with a PARTIAL amount, and the hold is still
        // mutable — exactly the state a concurrent/late replay reads when
        // its snapshots trail the first writer. Pre-fix, two corruptions
        // were reachable from here: a fabricated OVERAGE entry (F-2) and a
        // second DIRECT posting through postActualWithoutHold (F-3).
        val hold = holdFor("race-1", 300_000L)
        ledger.settleHold(hold.holdId, 100_000L, "settle:exec:race-1")

        // actual 250k > remaining 200k → the stale-read replay would compute
        // a 50k overage and POST it (F-2), or see remaining == 0 after the
        // release and post the whole actual again (F-3). Both fixed.
        val outcome = settlement.settleExecutionUsage(usage("race-1"), 250_000L, "USD")
        assertTrue(
            "the replay must be the honest ALREADY_SETTLED no-op",
            outcome is HoldSettlementService.SettlementSummary.NoAction
        )
        assertEquals(
            "ALREADY_SETTLED",
            (outcome as HoldSettlementService.SettlementSummary.NoAction).reason
        )

        val entries = db.budgetLedgerEntryDao().forPeriod(periodId)
        assertEquals("exactly ONE consumption entry — no fabricated overage, no double posting", 1, entries.size)
        assertEquals(-100_000L, entries.single().availableEffectMicroUnits)
        // The F-3 release re-attempt completed the cycle honestly (idempotent key).
        val settlements = db.budgetHoldSettlementDao().forHold(hold.holdId)
        assertEquals(1, settlements.count { it.settlementType == "SETTLE" })
        assertEquals(1, settlements.count { it.settlementType == "RELEASE" })
        assertEquals(200_000L, settlements.first { it.settlementType == "RELEASE" }.amountMicroUnits)
        assertEquals(
            BudgetLedgerService.HOLD_RELEASED,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )
        // Equation exact: 1M - 100k posted - 0 held = 900k.
        assertEquals(900_000L, ledger.availableForPeriod(periodId).availableMicroUnits)
        assertG06Aggregate(periodId, 100_000L)
    }

    // ------------------------------------------------------------------
    // 7. The overage cycle — the excess posts exactly once, under its own key
    // ------------------------------------------------------------------

    @Test
    fun `G06 overage cycle - the excess posts once under the execution overage key`() = runBlocking {
        val budget = createBudget(1_000_000L)
        val periodId = budget.periodId

        holdFor("over-1", 40_000L)
        val outcome = settlement.settleExecutionUsage(usage("over-1"), 60_000L, "USD")
        assertTrue(outcome is HoldSettlementService.SettlementSummary.SettledWithOverage)
        assertEquals(20_000L, (outcome as HoldSettlementService.SettlementSummary.SettledWithOverage).overagePostedMicroUnits)

        // Two entries: the 40k settle + the 20k overage — both real costs.
        val entries = db.budgetLedgerEntryDao().forPeriod(periodId)
        assertEquals(2, entries.size)
        assertEquals(-60_000L, entries.sumOf { it.availableEffectMicroUnits })
        assertEquals(940_000L, ledger.availableForPeriod(periodId).availableMicroUnits)

        // A replay posts nothing more (same settle key AND same overage key).
        val replay = settlement.settleExecutionUsage(usage("over-1"), 60_000L, "USD")
        assertTrue(replay is HoldSettlementService.SettlementSummary.NoAction)
        assertEquals(2, db.budgetLedgerEntryDao().forPeriod(periodId).size)
        assertG06Aggregate(periodId, 60_000L)
    }
}
