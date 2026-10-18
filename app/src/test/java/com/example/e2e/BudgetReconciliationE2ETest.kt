package com.example.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.CostBreakdownService
import com.example.application.budget.CurrencyConversionService
import com.example.application.budget.EconomicGovernanceService
import com.example.application.budget.EntitlementService
import com.example.application.budget.HoldBasedBudgetGate
import com.example.application.budget.HoldSettlementService
import com.example.application.budget.PricingCatalogService
import com.example.application.budget.ProviderBillingService
import com.example.application.budget.RateLimitGovernor
import com.example.application.budget.ReconciliationService
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
import com.example.infrastructure.persistence.Stage3MigrationSql
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
 * STAGE 3 — the reconciliation E2E: the roadmap exit criterion, verbatim
 * ============================================================================
 * "معيار الخروج: مصالحة نهائية لفترة تجريبية كاملة مع فرق مفسَّر بالكامل
 * (ضرائب/ائتمانات/رسوم/تحويل) دون فروق غير مفسرة."
 *
 * One real Room database, the full production loop:
 *
 *   1. authorize()    — hold-based budget gate (v23), USD hard budget
 *   2. accountUsage() — settlement + itemized breakdown hooks (v25)
 *   3. entitlement    — the accrual layer consumes the same usage record
 *   4. FX rule        — USD -> EUR, the G03 windowed directional rate
 *   5. invoice import — provider bills in EUR with a tax breakdown
 *   6. reconciliation — G03 comparison in the provider's currency,
 *                       difference fully classified (tax + conversion),
 *                       residual ZERO
 *   7. finalize       — G04 sign-off, then frozen at the DB level
 *
 * The balance equation is recomputed from raw rows at every step.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetReconciliationE2ETest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var governance: EconomicGovernanceService
    private lateinit var fx: CurrencyConversionService
    private lateinit var reconciliation: ReconciliationService
    private lateinit var billing: ProviderBillingService
    private lateinit var entitlements: EntitlementService

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
        ledger = BudgetLedgerService(database = db, idGenerator = { "id-${tick++}" }, clock = { tick })
        val catalog = PricingCatalogService(database = db, clock = { tick })
        val breakdown = CostBreakdownService(database = db, pricingCatalog = catalog, clock = { tick })
        fx = CurrencyConversionService(database = db, clock = { tick })
        val settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, fx = fx, clock = { tick })
        billing = ProviderBillingService(database = db, clock = { tick })
        reconciliation = ReconciliationService(database = db, fx = fx, clock = { tick })
        entitlements = EntitlementService(database = db, clock = { tick })
        val pricing = FakePricingRepository()
        kotlinx.coroutines.runBlocking {
            pricing.upsertPricing(
                PricingEntry(
                    id = "pe-e2e", scope = PricingScope.MODEL, providerId = "prov",
                    serviceId = "svc-1", modelId = "m1",
                    inputPricePerMillion = MoneyAmount.of(1500, "USD"),
                    outputPricePerMillion = MoneyAmount.of(3000, "USD"),
                    cachedInputPricePerMillion = null,
                    billingClass = com.example.domain.core.budget.BillingClass.PAID,
                    pricingVersion = "v1",
                    effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                    provenance = "e2e"
                )
            )
        }
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
        // the catalog mirror reads the ROOM pricing rows — seed the same
        // entry there (what RoomEconomicStore.upsertPricing would write).
        runBlocking {
            db.pricingEntryDao().upsert(
                com.example.infrastructure.persistence.entities.PricingEntryEntity(
                    id = "pe-e2e", scopeType = "MODEL", providerId = "prov", serviceId = "svc-1", modelId = "m1",
                    inputPriceMicroPerMillion = 1500L, outputPriceMicroPerMillion = 3000L,
                    cachedInputPriceMicroPerMillion = null,
                    currency = "USD", billingClass = "PAID", pricingVersion = "v1",
                    effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                    provenance = "e2e", createdAtEpochMs = 0
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun scopeIdFor(workspaceId: String): String =
        db.openHelper.writableDatabase.query(
            "SELECT scope_id FROM scope_descriptor WHERE platform_object_id='$workspaceId'"
        ).use { c -> c.moveToFirst(); c.getString(0) }

    @Test
    fun `full trial period reconciles with a fully explained difference and zero residual`() = runBlocking {
        // ---- 1) the budget: a hard USD workspace budget ----
        val budget = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = "ws-e2e",
                providerId = null, budgetName = "e2e budget", currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = windowStart, periodEndEpochMs = windowEnd,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT
            )
        )

        // ---- 2) authorize: hold 1875 micro (750k in × 1500 + 250k out × 3000) ----
        val decision = governance.authorize(
            EconomicAuthorizationRequest(
                executionId = "exec-e2e-1", workspaceId = "ws-e2e", agentId = null, taskId = null,
                providerId = "prov", serviceId = "svc-1", modelId = "m1", resourceId = null,
                isLocalResource = false, expectedTotalTokens = 1_000_000, actionTypeCode = "LLM_STEP"
            )
        )
        assertEquals(EconomicGateDecision.ALLOWED, decision.decision)
        val hold = db.budgetHoldDao().allForReason("exec:exec-e2e-1").single()
        assertEquals(1875L, hold.holdAmountMicroUnits)
        assertEquals(998_125L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)

        // ---- 3) accountUsage: the actual (1M in + 1M out) lands everywhere ----
        governance.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-e2e-1", taskId = null, workspaceId = "ws-e2e", agentId = null,
                providerId = "prov", serviceId = "svc-1", modelId = "m1", resourceId = null,
                usage = TokenUsageRecord(inputTokens = 1_000_000, outputTokens = 1_000_000, cachedTokens = 0),
                isActualProviderReport = true,
                timestampEpochMs = tick
            )
        )
        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_SETTLED, holdAfter.status)
        val calc = db.costCalculationDao().forAttempt("exec-e2e-1").single()
        assertEquals(CostBreakdownService.STATUS_CALCULATED, calc.calculationStatus)
        assertEquals(4500L, calc.totalAmountMicroUnits)
        assertEquals(995_500L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)

        // ---- 4) the accrual layer: the same usage record consumes an entitlement ----
        val entitlement = entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov", applicabilityScopeId = scopeIdFor("ws-e2e"),
                entitlementType = "FREE_QUOTA",
                valueKind = EntitlementService.VALUE_QUANTITY_LIMIT,
                quantityLimitPrecise = "5000000", unitId = "UNIT_TOKEN",
                startsAtEpochMs = windowStart, expiresAtEpochMs = windowEnd,
                consumptionOrder = 1
            )
        ) as EntitlementService.RecordOutcome.Recorded
        val usageRecordId = db.usageRecordDao()
            .byAttemptSourceVersion("exec-e2e-1", "PROVIDER", "1")!!.usageRecordId
        entitlements.consume(
            entitlementId = entitlement.entitlementId,
            consumptionGranularity = EntitlementService.GRANULARITY_USAGE_RECORD,
            idempotencyKey = "ur:$usageRecordId",
            usageRecordId = usageRecordId,
            quantityPrecise = "2000000",
            unitId = "UNIT_TOKEN"
        )
        val remaining = entitlements.remaining(entitlement.entitlementId)
                as EntitlementService.Remaining.RemainingValue
        assertEquals(3_000_000L, remaining.value.toLong())

        // ---- 5) the FX rule: our recorded USD -> EUR rate (G03 directional) ----
        fx.registerRule("USD", "EUR", "0.90012", 0L, null, "e2e-treasury")

        // ---- 6) the provider bills in EUR for the SAME trial period ----
        // Provider math: exact 0.9 on the net + VAT. Our rule rounds HALF_UP.
        // internal 4500 USD × 0.90012 = 4050.54 -> 4051 EUR (recorded evidence)
        // provider: net 4050 + tax 514 = gross 4564 EUR
        val invoice = billing.import(
            ProviderBillingService.ImportSpec(
                providerId = "prov",
                externalBillingId = "INV-E2E-2026-10",
                billingRecordType = ProviderBillingService.TYPE_INVOICE,
                recordGranularity = ProviderBillingService.GRANULARITY_MONTHLY,
                periodStartEpochMs = windowStart,
                periodEndEpochMs = windowEnd,
                primaryAmountType = ProviderBillingService.PRIMARY_GROSS_PAYABLE,
                primaryAmountMicroUnits = 4564L,
                grossPayableMicroUnits = 4564L,
                netBeforeTaxMicroUnits = 4050L,
                taxAmountMicroUnits = 514L,
                creditAmountMicroUnits = 0L,
                adjustmentAmountMicroUnits = 0L,
                currency = "EUR",
                sourceAuthority = "provider-portal",
                reportedAtEpochMs = windowEnd - 1
            )
        ) as ProviderBillingService.ImportResult.Imported

        // ---- 7) reconciliation: G03 comparison in the provider's currency ----
        val scopeId = scopeIdFor("ws-e2e")
        val outcome = reconciliation.buildReconciliation(
            billingRecordId = invoice.recordId,
            scopeId = scopeId,
            periodStartEpochMs = windowStart,
            periodEndEpochMs = windowEnd,
            toleranceMicroUnits = 0L
        ) as ReconciliationService.BuildOutcome.Recorded

        assertEquals(ReconciliationService.COVERAGE_COMPLETE, outcome.coverageStatus)
        assertEquals("EUR", db.reconciliationRecordDao().byId(outcome.reconciliationId)!!.comparisonCurrency)
        assertEquals(4500L, outcome.localAmountMicroUnits)          // internal, USD (local side)
        assertEquals(4051L, outcome.comparedInternalMicroUnits)     // 4500 × 0.90012 = 4050.54 -> HALF_UP
        assertEquals(4564L, outcome.providerAmountMicroUnits)       // provider gross, EUR
        assertEquals(513L, outcome.differenceMicroUnits)            // 4564 - 4051
        assertEquals(ReconciliationService.STATUS_CONFLICT, outcome.reconciliationStatus)
        assertEquals("fx_USD_EUR_0", outcome.conversionRuleId)

        // the inclusions carry the itemized lines — the audit trail
        assertEquals(2, reconciliation.inclusions(outcome.reconciliationId).size)

        // ---- 8) classify the difference until NOTHING is unexplained ----
        reconciliation.addAdjustment(
            outcome.reconciliationId, ReconciliationService.ADJUSTMENT_PROVIDER_ONLY_TAX,
            514L, "VAT-20pct"
        )
        reconciliation.addAdjustment(
            outcome.reconciliationId, ReconciliationService.ADJUSTMENT_CONVERSION_DIFFERENCE,
            -1L, "HALF_UP rounding: exact 4050.54 recorded as 4051"
        )
        assertEquals(
            "the Stage-3 exit criterion: zero unexplained difference",
            0L,
            reconciliation.explainedResidual(outcome.reconciliationId)
        )

        // ---- 9) the finance sign-off, then the freeze (G04) ----
        val finalized = reconciliation.finalize(outcome.reconciliationId)
        assertTrue(finalized is ReconciliationService.FinalizeOutcome.Finalized)
        val record = reconciliation.byId(outcome.reconciliationId)!!
        assertEquals(ReconciliationService.FINALITY_FINALIZED, record.finalityStatus)
        assertNotNull(record.reconciledAtEpochMs)

        val sql = db.openHelper.writableDatabase
        try {
            sql.execSQL("UPDATE reconciliation_record SET reason_code='tamper' WHERE reconciliation_id='${record.reconciliationId}'")
            assertTrue("FINALIZED reconciliation is frozen", false)
        } catch (expected: android.database.SQLException) {
            // G04 frozen trigger — correct
        }
        try {
            sql.execSQL("DELETE FROM reconciliation_record")
            assertTrue("the reconciliation trail is append-only", false)
        } catch (expected: android.database.SQLException) {
            // G04 no-delete trigger — correct
        }
        try {
            sql.execSQL("UPDATE currency_conversion_rule SET rate_precise='9.9'")
            assertTrue("a used FX rule is frozen (reproducible history)", false)
        } catch (expected: android.database.SQLException) {
            // trg_currency_rule_immutable_when_used — correct
        }

        // ---- 10) the balance equation still closes from raw rows ----
        assertEquals(995_500L, ledger.availableForPeriod(budget.periodId).availableMicroUnits)
    }
}
