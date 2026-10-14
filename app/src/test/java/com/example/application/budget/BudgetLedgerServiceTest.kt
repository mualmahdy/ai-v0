package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 1 (Day 5-7) — BudgetLedgerServiceTest
 * ============================================================================
 * The four atomic operations + the balance equation + the risk-matrix
 * detectors (ز-1 concurrency, ز-2 idempotency, ز-9 expiry) + the G06
 * release rule + reversal semantics + DB-level immutability — against a
 * REAL in-memory Room database at v24 (fresh schema + the same ten
 * production triggers installed via the onCreate path).
 *
 * Every available-balance assertion is recomputed from raw rows, matching
 * ح.2 acceptance condition 4 ("مع تطابق معادلة الرصيد عند كل خطوة محسوبةً
 * من الصفوف الخام").
 */
@RunWith(RobolectricTestRunner::class)
class BudgetLedgerServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: BudgetLedgerService

    private val t0 = 1_000L
    private val windowStart = 0L
    private val windowEnd = 10_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            // Fresh installs get the triggers via AppDatabase's onCreate
            // callback in production; the test mirrors that wiring so the
            // DB-level protections are exercised identically.
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                }
            })
            .build()
        var tick = t0
        service = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${tick++}" },
            clock = { tick }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createWorkspaceBudget(
        limitMicro: Long = 1_000_000L,
        overagePolicy: String = BudgetLedgerService.OVERAGE_HARD_LIMIT
    ): BudgetLedgerService.CreatedBudget =
        service.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-1",
                providerId = null,
                budgetName = "Workspace budget",
                currency = "USD",
                limitMicroUnits = limitMicro,
                periodStartEpochMs = windowStart,
                periodEndEpochMs = windowEnd,
                overagePolicy = overagePolicy
            )
        )

    // ------------------------------------------------------------------
    // Full lifecycle: equation at every step, from raw rows (ح.2 cond. 4)
    // ------------------------------------------------------------------

    @Test
    fun `full hold lifecycle keeps the balance equation exact at every step`() = runBlocking {
        val budget = createWorkspaceBudget(limitMicro = 1_000_000L)
        val periodId = budget.periodId

        // step 0: fresh period
        assertEquals(1_000_000L, service.availableForPeriod(periodId).availableMicroUnits)

        // step 1: reserve 300_000
        val r1 = service.authorizeAndReserve("WORKSPACE", "ws-1", 300_000, "exec-1", "op-1")
        assertTrue(r1 is BudgetLedgerService.HoldReservation.Reserved)
        assertEquals(700_000L, service.availableForPeriod(periodId).availableMicroUnits)

        // step 2: settle 120_000 (partial — actual < estimate)
        val s1 = service.settleHold((r1 as BudgetLedgerService.HoldReservation.Reserved).holdId, 120_000, "settle-1")
        assertTrue(s1 is BudgetLedgerService.SettlementOutcome.Settled)
        assertEquals(
            BudgetLedgerService.HOLD_PARTIALLY_SETTLED,
            (s1 as BudgetLedgerService.SettlementOutcome.Settled).holdStatusAfter
        )
        // 1_000_000 - 120_000 (posted) - 180_000 (remaining hold) = 700_000
        assertEquals(700_000L, service.availableForPeriod(periodId).availableMicroUnits)

        // step 3: settle the remaining estimate (180_000)
        val s2 = service.settleHold(r1.holdId, 180_000, "settle-2")
        assertEquals(BudgetLedgerService.HOLD_SETTLED, (s2 as BudgetLedgerService.SettlementOutcome.Settled).holdStatusAfter)
        // 1_000_000 - 300_000 (posted) - 0 (hold complete) = 700_000
        assertEquals(700_000L, service.availableForPeriod(periodId).availableMicroUnits)

        // step 4: a second hold, then release it (G06: no ledger entry)
        val r2 = service.authorizeAndReserve("WORKSPACE", "ws-1", 100_000, "exec-2", "op-2")
        assertEquals(600_000L, service.availableForPeriod(periodId).availableMicroUnits)
        val rel = service.releaseHold((r2 as BudgetLedgerService.HoldReservation.Reserved).holdId, "release-1")
        assertTrue(rel is BudgetLedgerService.ReleaseOutcome.Released)
        assertEquals(100_000L, (rel as BudgetLedgerService.ReleaseOutcome.Released).releasedMicroUnits)
        assertEquals(700_000L, service.availableForPeriod(periodId).availableMicroUnits)
        assertEquals(2, db.budgetLedgerEntryDao().count()) // settle entries only — release added none
    }

    // ------------------------------------------------------------------
    // ز-1: concurrent reservations can never exceed the limit
    // ------------------------------------------------------------------

    @Test
    fun `concurrent reservations never exceed the limit (z-1)`(): Unit = runBlocking {
        val budget = createWorkspaceBudget(limitMicro = 1_000L)
        val periodId = budget.periodId
        val workers = (1..32).map { i ->
            async(Dispatchers.IO) {
                service.authorizeAndReserve("WORKSPACE", "ws-1", 100L, "exec-$i", "op-$i")
            }
        }.awaitAll()
        val reserved = workers.count { it is BudgetLedgerService.HoldReservation.Reserved }
        val denied = workers.count { it is BudgetLedgerService.HoldReservation.Denied }
        assertEquals("exactly 10 of 32 must win", 10, reserved)
        assertEquals(22, denied)
        // The balance from raw rows can never go negative.
        val available = service.availableForPeriod(periodId)
        assertTrue("available must never be negative: $available", available.availableMicroUnits >= 0)
        assertEquals(0L, available.availableMicroUnits)
        // And the raw hold rows sum exactly to the limit.
        val holds = db.budgetHoldDao().forPeriod(periodId)
        assertEquals(10, holds.size)
        assertEquals(1_000L, holds.sumOf { it.holdAmountMicroUnits })
    }

    // ------------------------------------------------------------------
    // ز-2: idempotent replay of reserve / settle / release
    // ------------------------------------------------------------------

    @Test
    fun `replayed operations with the same idempotency key return the original outcome (z-2)`(): Unit = runBlocking {
        val budget = createWorkspaceBudget(limitMicro = 500_000L)
        val periodId = budget.periodId

        val first = service.authorizeAndReserve("WORKSPACE", "ws-1", 200_000, "exec-1", "op-1")
            as BudgetLedgerService.HoldReservation.Reserved
        val replay = service.authorizeAndReserve("WORKSPACE", "ws-1", 200_000, "exec-1", "op-1")
            as BudgetLedgerService.HoldReservation.Reserved
        assertTrue(replay.replayed)
        assertEquals(first.holdId, replay.holdId)
        assertEquals(1, db.budgetHoldDao().forPeriod(periodId).size)

        val settle = service.settleHold(first.holdId, 50_000, "settle-1")
            as BudgetLedgerService.SettlementOutcome.Settled
        val settleReplay = service.settleHold(first.holdId, 50_000, "settle-1")
            as BudgetLedgerService.SettlementOutcome.Settled
        assertTrue(settleReplay.replayed)
        assertEquals(settle.settlementId, settleReplay.settlementId)
        assertEquals(1, db.budgetHoldSettlementDao().forHold(first.holdId).size)

        val release = service.releaseHold(first.holdId, "release-1") as BudgetLedgerService.ReleaseOutcome.Released
        val releaseReplay = service.releaseHold(first.holdId, "release-1") as BudgetLedgerService.ReleaseOutcome.Released
        assertTrue(releaseReplay.replayed)
        assertEquals(release.settlementId, releaseReplay.settlementId)
        assertEquals(2, db.budgetHoldSettlementDao().forHold(first.holdId).size) // SETTLE + RELEASE only
    }

    // ------------------------------------------------------------------
    // ز-9: expiry is evaluated lazily — an expired hold frees availability
    // ------------------------------------------------------------------

    @Test
    fun `an expired hold stops blocking availability immediately (z-9 lazy evaluation)`(): Unit = runBlocking {
        val budget = createWorkspaceBudget(limitMicro = 1_000L)
        val periodId = budget.periodId
        // clock ticks: setup leaves tick at t0; advance manually per call
        var now = t0
        val hold = service.authorizeAndReserve(
            "WORKSPACE", "ws-1", 800L, "exec-1", "op-1",
            expiresAtEpochMs = now + 10, nowEpochMs = now
        ) as BudgetLedgerService.HoldReservation.Reserved
        assertEquals(200L, service.availableForPeriod(periodId, nowEpochMs = now).availableMicroUnits)

        // after expiry: availability ignores the hold even though its row
        // still says ACTIVE (the cleaner has not run yet).
        now += 11
        assertEquals(1_000L, service.availableForPeriod(periodId, nowEpochMs = now).availableMicroUnits)

        // the background cleaner flips the row to EXPIRED (bookkeeping)
        assertEquals(1, service.expireHolds(nowEpochMs = now))
        val row = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_EXPIRED, row.status)
        // and settling an EXPIRED hold is now a domain error
        val outcome = service.settleHold(hold.holdId, 100L, "late-settle", nowEpochMs = now)
        assertTrue(outcome is BudgetLedgerService.SettlementOutcome.InvalidHoldState)
    }

    // ------------------------------------------------------------------
    // Overage policy mapping at the service level
    // ------------------------------------------------------------------

    @Test
    fun `hard limit denies and documents the exact shortfall`(): Unit = runBlocking {
        createWorkspaceBudget(limitMicro = 1_000L, overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT)
        service.authorizeAndReserve("WORKSPACE", "ws-1", 900L, "exec-1", "op-1")
        val denied = service.authorizeAndReserve("WORKSPACE", "ws-1", 200L, "exec-2", "op-2")
        assertTrue(denied is BudgetLedgerService.HoldReservation.Denied)
        val d = denied as BudgetLedgerService.HoldReservation.Denied
        assertEquals(100L, d.availableMicroUnits)
        assertEquals(200L, d.requiredMicroUnits)
        assertEquals(BudgetLedgerService.OVERAGE_HARD_LIMIT, d.overagePolicy)
    }

    @Test
    fun `over-settlement of a hold is rejected with the exact numbers`(): Unit = runBlocking {
        val budget = createWorkspaceBudget()
        val hold = service.authorizeAndReserve("WORKSPACE", "ws-1", 100_000, "exec-1", "op-1")
            as BudgetLedgerService.HoldReservation.Reserved
        val over = service.settleHold(hold.holdId, 100_001, "settle-1")
        assertTrue(over is BudgetLedgerService.SettlementOutcome.OverSettlement)
    }

    // ------------------------------------------------------------------
    // Adjustment & reversal semantics
    // ------------------------------------------------------------------

    @Test
    fun `a reversal is the exact opposite effect and nets to zero`(): Unit = runBlocking {
        val budget = createWorkspaceBudget(limitMicro = 1_000_000L)
        val periodId = budget.periodId
        val hold = service.authorizeAndReserve("WORKSPACE", "ws-1", 400_000, "exec-1", "op-1")
            as BudgetLedgerService.HoldReservation.Reserved
        val settled = service.settleHold(hold.holdId, 400_000, "settle-1")
            as BudgetLedgerService.SettlementOutcome.Settled
        assertEquals(600_000L, service.availableForPeriod(periodId).availableMicroUnits)

        // exact-opposite reversal
        val reversal = service.postAdjustment(
            periodId = periodId,
            effectMicroUnits = 400_000,
            currency = "USD",
            entryBasis = BudgetLedgerService.BASIS_ACTUAL,
            reason = "refund exec-1",
            idempotencyKey = "rev-1",
            reversalOfEntryId = settled.ledgerEntryId
        )
        assertTrue(reversal is BudgetLedgerService.AdjustmentOutcome.Posted)
        assertEquals(1_000_000L, service.availableForPeriod(periodId).availableMicroUnits)

        // a mismatched reversal is rejected (never a partial/opportunistic flip)
        val bad = service.postAdjustment(
            periodId = periodId,
            effectMicroUnits = 399_999,
            currency = "USD",
            entryBasis = BudgetLedgerService.BASIS_ACTUAL,
            reason = "bad",
            idempotencyKey = "rev-2",
            reversalOfEntryId = settled.ledgerEntryId
        )
        assertTrue(bad is BudgetLedgerService.AdjustmentOutcome.InvalidReversal)

        // a reversal without its original is rejected
        val dangling = service.postAdjustment(
            periodId = periodId,
            effectMicroUnits = 1,
            currency = "USD",
            entryBasis = BudgetLedgerService.BASIS_ACTUAL,
            reason = "bad",
            idempotencyKey = "rev-3",
            reversalOfEntryId = "missing-entry"
        )
        assertTrue(dangling is BudgetLedgerService.AdjustmentOutcome.InvalidReversal)
    }

    // ------------------------------------------------------------------
    // DB-level immutability, seen through the Room handle (ADR-B4)
    // ------------------------------------------------------------------

    @Test
    fun `posted entries cannot be updated or deleted through raw SQL (ADR-B4 triggers)`(): Unit = runBlocking {
        val budget = createWorkspaceBudget()
        val periodId = budget.periodId
        val hold = service.authorizeAndReserve("WORKSPACE", "ws-1", 10_000, "exec-1", "op-1")
            as BudgetLedgerService.HoldReservation.Reserved
        val settled = service.settleHold(hold.holdId, 10_000, "settle-1")
            as BudgetLedgerService.SettlementOutcome.Settled

        val raw = db.openHelper.writableDatabase
        try {
            raw.execSQL("UPDATE budget_ledger_entry SET available_effect_micro_units = 0 WHERE entry_id = '${settled.ledgerEntryId}'")
            fail("UPDATE on a POSTED entry must be rejected by the DB trigger")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // trigger fired
        }
        try {
            raw.execSQL("DELETE FROM budget_ledger_entry WHERE entry_id = '${settled.ledgerEntryId}'")
            fail("DELETE on the budget ledger must be rejected by the DB trigger")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // trigger fired
        }
        assertEquals(1, db.budgetLedgerEntryDao().count())
    }

    // ------------------------------------------------------------------
    // Policy versioning (PDM-G05): a new version freezes the old one
    // ------------------------------------------------------------------

    @Test
    fun `policy versioning creates a new row and retires the old active version (G05)`(): Unit = runBlocking {
        val v1 = createWorkspaceBudget(limitMicro = 1_000L)
        // consume through v1
        service.authorizeAndReserve("WORKSPACE", "ws-1", 900L, "exec-1", "op-1")
        // material edit -> new version with a higher limit
        val v2 = service.createPolicyVersion(
            familyId = v1.policyFamilyId,
            updatedRequest = BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-1",
                providerId = null,
                budgetName = "Workspace budget v2",
                currency = "USD",
                limitMicroUnits = 5_000L,
                periodStartEpochMs = windowStart,
                periodEndEpochMs = windowEnd
            )
        )
        // the old ACTIVE v1 is retired; lookups resolve to v2's period
        val history = db.budgetPolicyDao().familyHistory(v1.policyFamilyId)
        assertEquals(2, history.size)
        assertTrue(history.any { it.policyVersion == 1 && it.policyStatus == BudgetLedgerService.STATUS_RETIRED })
        assertTrue(history.any { it.policyVersion == 2 && it.policyStatus == BudgetLedgerService.STATUS_ACTIVE })

        val active = service.activeBudgetForScope("WORKSPACE", "ws-1")
        assertEquals(v2.policyId, active?.policy?.budgetPolicyId)
        // the v1 period's rows are stable and readable (historical truth)
        assertEquals(1_000L, db.budgetPeriodDao().byId(v1.periodId)!!.limitMicroUnits)
    }

    // ------------------------------------------------------------------
    // No active policy -> NoActivePolicy (caller falls back to legacy)
    // ------------------------------------------------------------------

    @Test
    fun `without an active policy the reservation reports NoActivePolicy`(): Unit = runBlocking {
        val outcome = service.authorizeAndReserve("WORKSPACE", "ws-unknown", 100L, "exec-1", "op-1")
        assertTrue(outcome is BudgetLedgerService.HoldReservation.NoActivePolicy)
    }
}
