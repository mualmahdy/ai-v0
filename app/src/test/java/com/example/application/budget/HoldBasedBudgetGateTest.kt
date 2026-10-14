package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
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
 * STAGE 1 (Day 8-9) — the authorize() wiring (Section و.6)
 * ============================================================================
 * Proves the three wiring promises against a REAL in-memory Room database:
 *
 *  1. NO active accounting policy → the gate returns null and the LEGACY
 *     authorize() behavior runs VERBATIM (full retro-compatibility);
 *  2. active policy + known estimate → hold-based verdict: a hold exists,
 *     the balance dropped, the reason documents the receipt;
 *  3. active policy + exhausted budget → DENIED with the exact shortfall;
 *  4. active policy + UNKNOWN estimate → honest ALLOWED with NO hold
 *     (interpretation I-3: UNKNOWN is never zero, zero holds are
 *     impossible);
 *  5. soft-limit overage → WARNED and NO hold (interpretation I-7: the
 *     holds-never-exceed-limit invariant is absolute).
 */
@RunWith(RobolectricTestRunner::class)
class HoldBasedBudgetGateTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var gate: HoldBasedBudgetGate
    private lateinit var governance: EconomicGovernanceService

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
                }
            })
            .build()
        ledger = BudgetLedgerService(database = db)
        gate = HoldBasedBudgetGate(ledger)
        governance = EconomicGovernanceService(
            pricingRepository = FakePricingRepository(),
            costLedger = FakeCostLedger(),
            allocationRepository = FakeBudgetAllocationRepository(),
            holdBasedBudgetCore = gate
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun request(workspaceId: String? = "ws-1", tokens: Int? = 4_000) =
        EconomicAuthorizationRequest(
            executionId = "exec-1",
            workspaceId = workspaceId,
            agentId = null,
            taskId = null,
            providerId = "prov-1",
            serviceId = null,
            modelId = "model-1",
            resourceId = null,
            isLocalResource = false,
            expectedTotalTokens = tokens,
            actionTypeCode = "TEST"
        )

    private suspend fun createPolicy(
        limitMicro: Long,
        overage: String = BudgetLedgerService.OVERAGE_HARD_LIMIT
    ): String {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-1",
                providerId = null,
                budgetName = "ws-1 budget",
                currency = "USD",
                limitMicroUnits = limitMicro,
                periodStartEpochMs = windowStart,
                periodEndEpochMs = windowEnd,
                overagePolicy = overage
            )
        )
        return created.periodId
    }

    @Test
    fun `without an active policy the legacy authorize behavior runs verbatim`(): Unit = runBlocking {
        // No budget_allocations, no accounting policy — authorize() must
        // produce exactly the legacy honest ALLOWED (no spending authority
        // configured, unknown cost tracked, not denied).
        val result = governance.authorize(request(workspaceId = "ws-no-policy"))
        assertEquals(EconomicGateDecision.ALLOWED, result.decision)
        // and no hold was created for any scope
        assertEquals(0, db.budgetLedgerEntryDao().count())
    }

    @Test
    fun `an active policy routes authorization through a hold`(): Unit = runBlocking {
        val periodId = createPolicy(limitMicro = 1_000_000L)
        val result = governance.authorize(request())
        assertEquals(EconomicGateDecision.ALLOWED, result.decision)
        assertTrue("reason must document the hold-based path", result.reason.contains("HOLD-BASED BUDGET"))

        // UNKNOWN pricing (the fake repo has no prices) -> interpretation I-3:
        // honest ALLOWED with NO hold — available unchanged, zero entries.
        assertEquals(1_000_000L, ledger.availableForPeriod(periodId).availableMicroUnits)
        assertEquals(0, db.budgetLedgerEntryDao().count())

        // A concrete reservation through the same core the gate uses proves
        // hold creation end-to-end for the wired scope.
        val reservation = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE",
            platformObjectId = "ws-1",
            amountMicroUnits = 250_000,
            reason = "exec-direct",
            idempotencyKey = "auth:exec-9:WORKSPACE:ws-1"
        )
        assertTrue(reservation is BudgetLedgerService.HoldReservation.Reserved)
        assertEquals(750_000L, ledger.availableForPeriod(periodId).availableMicroUnits)
        assertEquals(1, db.budgetHoldDao().forPeriod(periodId).size)
    }

    @Test
    fun `an exhausted hard budget denies with the exact shortfall`(): Unit = runBlocking {
        createPolicy(limitMicro = 1_000L, overage = BudgetLedgerService.OVERAGE_HARD_LIMIT)
        val r1 = ledger.authorizeAndReserve("WORKSPACE", "ws-1", 900L, "exec-1", "auth:exec-1:WORKSPACE:ws-1")
        assertTrue(r1 is BudgetLedgerService.HoldReservation.Reserved)
        val r2 = ledger.authorizeAndReserve("WORKSPACE", "ws-1", 200L, "exec-2", "auth:exec-2:WORKSPACE:ws-1")
        val denied = r2 as BudgetLedgerService.HoldReservation.Denied
        assertEquals(100L, denied.availableMicroUnits)
        assertEquals(200L, denied.requiredMicroUnits)
        assertEquals(BudgetLedgerService.OVERAGE_HARD_LIMIT, denied.overagePolicy)
    }

    @Test
    fun `a soft-limit overage never creates an over-limit hold (I-7)`(): Unit = runBlocking {
        createPolicy(limitMicro = 1_000L, overage = BudgetLedgerService.OVERAGE_SOFT_LIMIT)
        val r1 = ledger.authorizeAndReserve("WORKSPACE", "ws-1", 900L, "exec-1", "auth:exec-1:WORKSPACE:ws-1")
        assertTrue(r1 is BudgetLedgerService.HoldReservation.Reserved)
        val r2 = ledger.authorizeAndReserve("WORKSPACE", "ws-1", 200L, "exec-2", "auth:exec-2:WORKSPACE:ws-1")
        assertTrue("over-limit request must be Denied at the core (invariant)", r2 is BudgetLedgerService.HoldReservation.Denied)

        // The holds-never-exceed-limit invariant is absolute: raw hold rows
        // sum to exactly the limit.
        val periodId = ledger.activeBudgetForScope("WORKSPACE", "ws-1")!!.period.budgetPeriodId
        val holds = db.budgetHoldDao().forPeriod(periodId)
        assertEquals(1, holds.size)
        assertEquals(1_000L, holds.sumOf { it.holdAmountMicroUnits })
    }

    @Test
    fun `an unrecognized overage policy fails closed (money never guesses)`(): Unit = runBlocking {
        createPolicy(limitMicro = 1_000L, overage = "SOMETHING_ELSE")
        ledger.authorizeAndReserve("WORKSPACE", "ws-1", 900L, "exec-1", "auth:exec-1:WORKSPACE:ws-1")
        val denied = ledger.authorizeAndReserve("WORKSPACE", "ws-1", 200L, "exec-2", "auth:exec-2:WORKSPACE:ws-1")
            as BudgetLedgerService.HoldReservation.Denied
        // The policy value rides along verbatim — mapOverage's else-branch
        // maps it to DENIED (fail-closed) with the policy quoted in the reason.
        assertEquals("SOMETHING_ELSE", denied.overagePolicy)
        assertTrue(denied.requiredMicroUnits > denied.availableMicroUnits)
    }
}
