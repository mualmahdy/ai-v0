package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.entities.BudgetPeriodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 * STAGE 6 — G09 acceptance: real concurrency + temporal overlap
 * ============================================================================
 * PDM-G09: "اختبارات التداخل الزمني والتزامن والمطابقة — تُبنى تراكميًا مع كل
 * مرحلة", and the study's closing condition: "اختبارات تزامن حقيقية لكل مسار
 * حجز/تسوية/قيد قبل اعتماد كل مرحلة، لا اختبارات وحدات متسلسلة فقط".
 *
 * Accumulated so far (each cited in the register): z-1 (32 workers racing
 * reservations, Stage 1), the FX window races (Stage 3), the reconciliation
 * matching arms (Stage 3, BudgetReconciliationE2ETest — the المطابقة leg).
 * This suite closes the two remaining legs with REAL multi-worker races on
 * Dispatchers.IO (never sequential loops pretending to be concurrent):
 *
 *   1. Concurrent SETTLES with distinct keys on one hold — the settled
 *      total can never exceed the hold.
 *   2. Concurrent SETTLE vs RELEASE on one hold — every interleaving ends
 *      in a coherent state (settled + released ≤ hold, releases post
 *      nothing, the equation recomputes exact from raw rows).
 *   3. Concurrent REPLAYS of one execution's settlement — exactly ONE
 *      posting wins (the F-2 regression hammer).
 *   4. Temporal OVERLAP: two covering periods on one policy resolve
 *      deterministically to the latest start, holds land on it, the other
 *      period stays untouched — SUPERVISORY_OVERLAP made executable.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetConcurrencyAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var settlement: HoldSettlementService

    private val idSeq = AtomicInteger(1_000)
    private val now = AtomicLong(7_000_000L) // inside the overlap window
    private val windowStart = 0L
    private val windowEnd = 10_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${idSeq.incrementAndGet()}" },
            clock = { now.get() }
        )
        val fx = CurrencyConversionService(database = db, clock = { now.get() })
        settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, fx = fx, clock = { now.get() })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createBudget(
        limitMicro: Long = 1_000_000L,
        start: Long = windowStart,
        end: Long = windowEnd
    ): BudgetLedgerService.CreatedBudget =
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = "ws-1",
                providerId = null, budgetName = "ws", currency = "USD",
                limitMicroUnits = limitMicro,
                periodStartEpochMs = start, periodEndEpochMs = end,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT
            )
        )

    private fun usage(executionId: String) = UsageAccountingInput(
        executionId = executionId, taskId = null, workspaceId = "ws-1", agentId = null,
        providerId = "prov", serviceId = null, modelId = "m1", resourceId = null,
        usage = TokenUsageRecord(inputTokens = 1000, outputTokens = 1000, cachedTokens = 0),
        isActualProviderReport = true, timestampEpochMs = now.get()
    )

    // ------------------------------------------------------------------
    // 1. Concurrent settles, distinct keys, one hold
    // ------------------------------------------------------------------

    @Test
    fun `G09 concurrent settles with distinct keys on one hold - the total never exceeds the hold`() = runBlocking {
        val budget = createBudget(1_000_000L)
        val periodId = budget.periodId
        val hold = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = 100_000L, reason = "exec:conc-1", idempotencyKey = "auth:conc-1"
        ) as BudgetLedgerService.HoldReservation.Reserved

        // 16 workers each try to settle 25k — only 4 can ever win.
        val outcomes = (1..16).map { i ->
            async(Dispatchers.IO) {
                ledger.settleHold(hold.holdId, 25_000L, "settle:conc-1:$i")
            }
        }.awaitAll()

        val wins = outcomes.count { it is BudgetLedgerService.SettlementOutcome.Settled && !it.replayed }
        val settledTotal = db.budgetHoldSettlementDao().totalSettledMicro(hold.holdId)

        assertEquals("exactly 4 of 16 win the 100k hold", 4, wins)
        assertEquals("the settled total equals the hold exactly", 100_000L, settledTotal)
        assertEquals(
            BudgetLedgerService.HOLD_SETTLED,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )

        // Every win posted exactly its amount; nothing else posted.
        val entries = db.budgetLedgerEntryDao().forPeriod(periodId)
        assertEquals(4, entries.size)
        assertEquals(-100_000L, entries.sumOf { it.availableEffectMicroUnits })
        // Equation exact from raw rows: 1M - 100k posted - 0 held.
        assertEquals(900_000L, ledger.availableForPeriod(periodId, now.get()).availableMicroUnits)
    }

    // ------------------------------------------------------------------
    // 2. Concurrent settle vs release on the same hold — coherence
    // ------------------------------------------------------------------

    @Test
    fun `G09 concurrent settle versus release on one hold - every interleaving ends coherent`() = runBlocking {
        val budget = createBudget(1_000_000L)
        val periodId = budget.periodId
        val holdAmount = 100_000L
        val hold = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = holdAmount, reason = "exec:conc-2", idempotencyKey = "auth:conc-2"
        ) as BudgetLedgerService.HoldReservation.Reserved

        // 8 settlers (50k each, distinct keys) + 8 releasers (distinct keys).
        val outcomes = ((1..8).map { i ->
            async(Dispatchers.IO) { ledger.settleHold(hold.holdId, 50_000L, "settle:conc-2:$i") }
        } + (1..8).map { i ->
            async(Dispatchers.IO) { ledger.releaseHold(hold.holdId, "release:conc-2:$i") }
        }).awaitAll()

        // Whatever the interleaving, the invariants hold:
        val settlements = db.budgetHoldSettlementDao().forHold(hold.holdId)
        val settles = settlements.filter { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }
        val releases = settlements.filter { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        val settledTotal = settles.sumOf { it.amountMicroUnits }
        val releasedTotal = releases.sumOf { it.amountMicroUnits }

        assertTrue("settled can never exceed the hold ($settledTotal)", settledTotal <= holdAmount)
        assertTrue("settled+released can never exceed the hold ($settledTotal+$releasedTotal)", settledTotal + releasedTotal <= holdAmount)
        assertTrue("at most two settles fit (2×50k)", settles.size <= 2)
        assertTrue("at most one release can win", releases.size <= 1)

        // Releases never post; settles post exactly their amount.
        val entries = db.budgetLedgerEntryDao().forPeriod(periodId)
        releases.forEach { assertEquals("a RELEASE settlement is entry-less", null, it.ledgerEntryId) }
        assertEquals("posted effects equal the settled total exactly", -settledTotal, entries.sumOf { it.availableEffectMicroUnits })

        // The equation recomputes exact from raw rows for the final state.
        val status = db.budgetHoldDao().byId(hold.holdId)!!.status
        val holdRemaining = if (status in BudgetLedgerService.MUTABLE_HOLD_STATUSES) holdAmount - settledTotal else 0L
        assertEquals(
            1_000_000L - settledTotal - holdRemaining,
            ledger.availableForPeriod(periodId, now.get()).availableMicroUnits
        )
        // And the outcomes themselves were never lost: every worker got a verdict.
        assertEquals(16, outcomes.size)
    }

    // ------------------------------------------------------------------
    // 3. Concurrent replays of ONE execution — exactly one posting (F-2)
    // ------------------------------------------------------------------

    @Test
    fun `G09 concurrent replays of one execution settle exactly once - no fabricated overage (F-2)`() = runBlocking {
        val budget = createBudget(1_000_000L)
        val periodId = budget.periodId
        // 300k hold; the reported actual is 200k (< hold → settle + release).
        ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = 300_000L, reason = "exec:conc-3", idempotencyKey = "auth:conc-3"
        )

        val summaries = (1..12).map {
            async(Dispatchers.IO) { settlement.settleExecutionUsage(usage("conc-3"), 200_000L, "USD") }
        }.awaitAll()

        // Whatever the interleaving, the ledger holds EXACTLY one posting.
        val entries = db.budgetLedgerEntryDao().forPeriod(periodId)
        assertEquals("exactly ONE consumption entry may exist", 1, entries.size)
        assertEquals(-200_000L, entries.single().availableEffectMicroUnits)

        // The settle/release pair is coherent: 200k settled + 100k released.
        val holds = db.budgetHoldDao().forPeriod(periodId)
        assertEquals(1, holds.size)
        val settlements = db.budgetHoldSettlementDao().forHold(holds.single().holdId)
        assertEquals(200_000L, settlements.filter { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }.sumOf { it.amountMicroUnits })
        assertEquals(100_000L, settlements.filter { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }.sumOf { it.amountMicroUnits })

        // Equation exact: 1M - 200k posted - 0 held.
        assertEquals(800_000L, ledger.availableForPeriod(periodId, now.get()).availableMicroUnits)

        // And every worker received an honest verdict — no exceptions escaped.
        assertEquals(12, summaries.size)
    }

    // ------------------------------------------------------------------
    // 4. Temporal overlap — two covering periods, deterministic resolution
    // ------------------------------------------------------------------

    @Test
    fun `G09 temporal overlap - the latest-starting covering period wins deterministically and the other stays untouched`() = runBlocking {
        // The policy's effective_from is stamped at creation time — create
        // it at t=0 so BOTH probe instants (3M and 7M) are after it.
        now.set(0L)
        // Period A: [0, 10M) limit 1M (created with the budget).
        val budget = createBudget(1_000_000L, start = 0L, end = 10_000_000L)
        // Period B on the SAME policy: [5M, 15M) limit 500k — overlaps A.
        db.budgetPeriodDao().insert(
            BudgetPeriodEntity(
                budgetPeriodId = "period-overlap-b",
                budgetPolicyId = budget.policyId,
                periodStartEpochMs = 5_000_000L,
                periodEndEpochMs = 15_000_000L,
                limitMicroUnits = 500_000L,
                periodStatus = BudgetLedgerService.PERIOD_OPEN,
                createdAtEpochMs = now.get(),
                updatedAtEpochMs = now.get()
            )
        )

        // Before the overlap window (t=3M): only A covers → A.
        now.set(3_000_000L)
        val early = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = 100_000L, reason = "exec:ov-early", idempotencyKey = "auth:ov-early"
        ) as BudgetLedgerService.HoldReservation.Reserved
        assertEquals("before the overlap, period A covers alone", budget.periodId, early.periodId)

        // Inside the overlap window (t=7M): BOTH cover — the LATEST START
        // wins deterministically (activePeriodForPolicy's ORDER BY).
        now.set(7_000_000L)
        val late = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = 100_000L, reason = "exec:ov-late", idempotencyKey = "auth:ov-late"
        ) as BudgetLedgerService.HoldReservation.Reserved
        assertEquals("inside the overlap, period B (latest start) must win", "period-overlap-b", late.periodId)

        // A carries only its early hold; B carries only its late hold.
        assertEquals(900_000L, ledger.availableForPeriod(budget.periodId, now.get()).availableMicroUnits)
        assertEquals(400_000L, ledger.availableForPeriod("period-overlap-b", now.get()).availableMicroUnits)
    }

    @Test
    fun `G09 concurrent burst inside the overlap window all lands on the winning period (SUPERVISORY_OVERLAP)`() = runBlocking {
        val budget = createBudget(1_000_000L, start = 0L, end = 10_000_000L)
        db.budgetPeriodDao().insert(
            BudgetPeriodEntity(
                budgetPeriodId = "period-overlap-b",
                budgetPolicyId = budget.policyId,
                periodStartEpochMs = 5_000_000L,
                periodEndEpochMs = 15_000_000L,
                limitMicroUnits = 500_000L,
                periodStatus = BudgetLedgerService.PERIOD_OPEN,
                createdAtEpochMs = now.get(),
                updatedAtEpochMs = now.get()
            )
        )

        // 8 workers × 100k inside the window: B (500k) takes them all —
        // exactly 5 win, 3 denied; A never sees a single hold.
        val outcomes = (1..8).map { i ->
            async(Dispatchers.IO) {
                ledger.authorizeAndReserve(
                    scopeType = "WORKSPACE", platformObjectId = "ws-1",
                    amountMicroUnits = 100_000L, reason = "exec:ov-burst-$i",
                    idempotencyKey = "auth:ov-burst-$i"
                )
            }
        }.awaitAll()

        assertEquals("exactly 5 of 8 fit B's 500k", 5, outcomes.count { it is BudgetLedgerService.HoldReservation.Reserved })
        assertEquals(3, outcomes.count { it is BudgetLedgerService.HoldReservation.Denied })

        val bHolds = db.budgetHoldDao().forPeriod("period-overlap-b")
        assertEquals(5, bHolds.size)
        assertEquals(500_000L, bHolds.sumOf { it.holdAmountMicroUnits })
        assertEquals(0L, ledger.availableForPeriod("period-overlap-b", now.get()).availableMicroUnits)

        // Period A: untouched by the burst — SUPERVISORY_OVERLAP means each
        // covering period supervises its own limit; no shared accounting.
        assertEquals(0, db.budgetHoldDao().forPeriod(budget.periodId).size)
        assertEquals(1_000_000L, ledger.availableForPeriod(budget.periodId, now.get()).availableMicroUnits)
    }
}
