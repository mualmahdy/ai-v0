package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 2 — the closed production loop, end to end
 * ============================================================================
 * authorize() (hold-based, v23) → accountUsage() with BOTH Stage-2 hooks
 * wired (I-5 settlement + itemized breakdown) in ONE
 * EconomicGovernanceService — proving the three layers cooperate on a real
 * Room database:
 *
 *   1. authorize() reserves a hold through HoldBasedBudgetGate;
 *   2. accountUsage() writes the legacy cost ledger row (unchanged);
 *   3. the breakdown hook records usage facts + an itemized calculation;
 *   4. the settlement hook settles the hold against the actual cost;
 *   5. the balance equation, recomputed from raw rows, is exact.
 */
@RunWith(RobolectricTestRunner::class)
class EconomicGovernanceStage2IntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var governance: EconomicGovernanceService
    private lateinit var pricing: FakePricingRepository

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
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        ledger = BudgetLedgerService(
            database = db, idGenerator = { "id-${tick++}" }, clock = { tick }
        )
        val catalog = PricingCatalogService(database = db, clock = { tick })
        val breakdown = CostBreakdownService(database = db, pricingCatalog = catalog, clock = { tick })
        val settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, clock = { tick })
        pricing = FakePricingRepository()
        governance = EconomicGovernanceService(
            pricingRepository = pricing,
            costLedger = FakeCostLedger(),
            allocationRepository = FakeBudgetAllocationRepository(),
            rateLimitGovernor = RateLimitGovernor(),
            telemetry = null,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            holdBasedBudgetCore = HoldBasedBudgetGate(ledger, clock = { tick }),
            holdSettlement = settlement,
            costBreakdownRecorder = breakdown
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `authorize then accountUsage closes the hold cycle with an itemized audit trail`() = runBlocking {
        // ---- a hard workspace budget + a priced model ----
        val budget = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = "ws-1",
                providerId = null, budgetName = "ws budget", currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = windowStart, periodEndEpochMs = windowEnd,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT
            )
        )
        pricing.upsertPricing(
            PricingEntry(
                id = "pe-1", scope = PricingScope.MODEL, providerId = "prov", serviceId = null, modelId = "m1",
                inputPricePerMillion = MoneyAmount.of(1500, "USD"),
                outputPricePerMillion = MoneyAmount.of(3000, "USD"),
                cachedInputPricePerMillion = null,
                billingClass = com.example.domain.core.budget.BillingClass.CLOUD,
                pricingVersion = "v1",
                effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                provenance = "test"
            )
        )
        // production reality: pricingRepository IS the Room store — the
        // catalog mirror reads the same rows. The fake replaces the
        // repository view, so the Room row is seeded here too, mirroring
        // what RoomEconomicStore.upsertPricing would have written.
        db.pricingEntryDao().upsert(
            com.example.infrastructure.persistence.entities.PricingEntryEntity(
                id = "pe-1", scopeType = "MODEL", providerId = "prov", serviceId = null, modelId = "m1",
                inputPriceMicroPerMillion = 1500L, outputPriceMicroPerMillion = 3000L,
                cachedInputPriceMicroPerMillion = null,
                currency = "USD", billingClass = "CLOUD", pricingVersion = "v1",
                effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                provenance = "test", createdAtEpochMs = 0
            )
        )

        // ---- authorize: 1,000,000 expected tokens -> estimate 1875 micro
        //      (the 3:1 input:output split: 750k in × 1500 + 250k out × 3000 = 1125 + 750) ----
        val request = EconomicAuthorizationRequest(
            executionId = "exec-int-1", workspaceId = "ws-1", agentId = null, taskId = null,
            providerId = "prov", serviceId = null, modelId = "m1", resourceId = null,
            isLocalResource = false, expectedTotalTokens = 1_000_000, actionTypeCode = "LLM_STEP"
        )
        val decision = governance.authorize(request)
        assertEquals(EconomicGateDecision.ALLOWED, decision.decision)

        val hold = db.budgetHoldDao().allForReason("exec:exec-int-1").singleOrNull()
        assertNotNull("authorize must have reserved a hold", hold)
        assertEquals(1875L, hold!!.holdAmountMicroUnits)
        assertEquals(998_125L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)

        // ---- accountUsage: actuals land in every layer at once ----
        val record = governance.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-int-1", taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov", serviceId = null, modelId = "m1", resourceId = null,
                usage = TokenUsageRecord(
                    inputTokens = 1_000_000, outputTokens = 1_000_000, cachedTokens = 0,
                    isActualProviderReport = true
                ),
                isActualProviderReport = true,
                timestampEpochMs = tick
            )
        )
        assertNotNull(record)
        assertEquals(1500L + 3000L, record!!.cost.amountMicro) // legacy engine result

        // 1) the hold is settled (actual 4500 > hold 1875 -> settle 1875
        //    + post 2625 overage) — the FULL actual is in the ledger
        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_SETTLED, holdAfter.status)

        // 2) the breakdown recorded the itemized calculation: 2 lines
        //    (input 1500 + output 3000), HALF_UP, total 4500
        val calcs = db.costCalculationDao().forAttempt("exec-int-1")
        assertEquals(1, calcs.size)
        assertEquals(CostBreakdownService.STATUS_CALCULATED, calcs[0].calculationStatus)
        assertEquals(4500L, calcs[0].totalAmountMicroUnits)
        val lines = db.costCalculationLineDao().forCalculation(calcs[0].costCalculationId)
        assertEquals(2, lines.size)
        assertEquals(1500L, lines.first { it.lineOrder == 1 }.finalAmountMicroUnits)
        assertEquals(3000L, lines.first { it.lineOrder == 2 }.finalAmountMicroUnits)

        // 3) usage facts: 1 report + 4 measurements
        val usageRecord = db.usageRecordDao().byAttemptSourceVersion("exec-int-1", "PROVIDER", "1")
        assertNotNull(usageRecord)
        assertEquals(4, db.usageMeasurementDao().forRecord(usageRecord!!.usageRecordId).size)

        // 4) the balance equation from raw rows: 1_000_000 - 4_500 = 995_500
        //    (1_875 settled via the hold + 2_625 overage posting)
        assertEquals(995_500L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }
}
