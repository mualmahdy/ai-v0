package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.domain.core.budget.UsageDispatchState
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import kotlinx.coroutines.runBlocking
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
 * STAGE 2B — Workstream A: currency consistency across authorization and
 * settlement (acceptance, production paths)
 * ============================================================================
 * The ONE budget-currency rule this stage establishes and pins:
 *
 *  "The budget currency is the ACTIVE POLICY's currency. An amount enters
 *   hold reservation or ledger posting only in the budget currency —
 *   converted with the recorded, effective-time-valid FX rule
 *   (CurrencyConversionService) at the transaction instant. Identity
 *   conversion needs no rule. No covering rule → NO hold / NO POSTED
 *   entry, NEVER an assumed 1:1, and the original amount+currency is
 *   preserved on a DRAFT ledger entry (zero balance effect) instead of
 *   being silently discarded."
 *
 * Required coverage (order §3): USD/USD, EUR/EUR, USD budget + EUR pricing
 * with a rule, without a rule, rule not covering the transaction time,
 * estimate/actual currency mismatch, and late actual under a changed rule.
 * Assertions are on STORED holds, settlements, ledger entries (including
 * the FX attribution columns) and computed balances — the same real
 * Room + production-trigger harness as the Stage 1A/2 suites.
 */
@RunWith(RobolectricTestRunner::class)
class Stage2BCurrencyConsistencyAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var gate: HoldBasedBudgetGate
    private lateinit var settlement: HoldSettlementService
    private lateinit var governance: EconomicGovernanceService
    private lateinit var economicStore: RoomEconomicStore
    private lateinit var policyManagement: BudgetPolicyManagementService
    private lateinit var fx: CurrencyConversionService

    /** Fake monotonic clock — every service sees the SAME tick. */
    private var tick = 10_000L
    private var idSeq = 0L
    private val periodMs = 30L * 86_400_000L

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
        fx = CurrencyConversionService(database = db, clock = { tick })
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${idSeq++}" },
            clock = { tick },
            fx = fx
        )
        gate = HoldBasedBudgetGate(ledger, clock = { tick }, fx = fx)
        settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, fx = fx, clock = { tick })
        economicStore = RoomEconomicStore(
            pricingDao = db.pricingEntryDao(),
            ledgerDao = db.costLedgerEntryDao(),
            allocationDao = db.budgetAllocationDao()
        )
        governance = EconomicGovernanceService(
            pricingRepository = economicStore,
            costLedger = economicStore,
            allocationRepository = economicStore,
            rateLimitGovernor = RateLimitGovernor(),
            telemetry = null,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            holdBasedBudgetCore = gate,
            holdSettlement = settlement
        )
        policyManagement = BudgetPolicyManagementService(
            database = db,
            ledger = ledger,
            allocationRepository = economicStore,
            clock = { tick },
            idGenerator = { "id-${idSeq++}" }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun configureBudget(currency: String, limitMicro: Long = 5_000_000L) = runBlocking {
        val created = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = "ws-1",
                limitMicroUnits = limitMicro,
                currency = currency,
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )
        (created as BudgetPolicyManagementService.ConfigureOutcome.Created).periodId
    }

    /** MODEL-scope pricing in [currency]: 1500 in / 3000 out micro/M tokens. */
    private suspend fun seedPricing(currency: String) {
        economicStore.upsertPricing(
            PricingEntry(
                id = "pe-$currency", scope = PricingScope.MODEL, providerId = "prov",
                serviceId = "svc-1", modelId = "m1",
                inputPricePerMillion = MoneyAmount.of(1500, currency),
                outputPricePerMillion = MoneyAmount.of(3000, currency),
                cachedInputPricePerMillion = null,
                billingClass = BillingClass.PAID,
                pricingVersion = "v1",
                effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                provenance = "test"
            )
        )
    }

    /** estimate = 750k×1500/1M + 250k×3000/1M = 1,875 micro [currency]. */
    private fun decideGateRequest(executionId: String) = EconomicAuthorizationRequest(
        executionId = executionId,
        workspaceId = "ws-1",
        agentId = "agent-1",
        taskId = "task-1",
        providerId = "prov",
        serviceId = "svc-1",
        modelId = "m1",
        resourceId = "res-1",
        isLocalResource = false,
        expectedTotalTokens = 1_000_000,
        actionTypeCode = "EXECUTE_STEP",
        stepIndex = 0,
        attempt = 1
    )

    /** actual = 400k×1500/1M + 100k×3000/1M = 900 micro [currency]. */
    private fun stepAccounting(executionId: String) = UsageAccountingInput(
        executionId = executionId,
        taskId = "task-1",
        workspaceId = "ws-1",
        agentId = "agent-1",
        providerId = "prov",
        serviceId = "svc-1",
        modelId = "m1",
        resourceId = "res-1",
        usage = TokenUsageRecord(inputTokens = 400_000, outputTokens = 100_000, cachedTokens = 0),
        isActualProviderReport = true,
        stepIndex = 0,
        attempt = 1,
        dispatchState = UsageDispatchState.DISPATCHED,
        hasUsageReport = true,
        timestampEpochMs = tick
    )

    private suspend fun holdsFor(executionId: String) =
        db.budgetHoldDao().allForReason(HoldKeys.holdReason(executionId, 0, 1))

    private suspend fun available(periodId: String) =
        ledger.availableForPeriod(periodId).availableMicroUnits

    private suspend fun singleSql(query: String): Array<String> =
        db.openHelper.writableDatabase.query(query).use { c ->
            c.moveToFirst()
            Array(c.columnCount) { c.getString(it) ?: "NULL" }
        }

    // ------------------------------------------------------------------
    // 1. USD budget + USD pricing — the same-currency baseline.
    // ------------------------------------------------------------------

    @Test
    fun `usd budget with usd pricing reserves and settles in usd`() = runBlocking {
        seedPricing("USD")
        val periodId = configureBudget("USD")

        val verdict = governance.authorize(decideGateRequest("exec-1"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        val hold = holdsFor("exec-1").single()
        assertEquals(1_875L, hold.holdAmountMicroUnits)
        assertEquals("USD", hold.currency)
        assertEquals(5_000_000L - 1_875L, available(periodId))

        governance.accountUsageSuspend(stepAccounting("exec-1"))
        val entry = singleSql(
            "SELECT available_effect_micro_units, currency, original_currency, conversion_rule_id, posting_state " +
                "FROM budget_ledger_entry WHERE hold_id = '${hold.holdId}'"
        )
        assertEquals("-900", entry[0])
        assertEquals("USD", entry[1])
        assertEquals("NULL", entry[2]) // same currency: no FX attribution
        assertEquals("NULL", entry[3])
        assertEquals("POSTED", entry[4])
        assertEquals(5_000_000L - 900L, available(periodId))
        // actual (900) < hold (1,875): settle + release-the-remainder → RELEASED.
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(hold.holdId)!!.status)
    }

    // ------------------------------------------------------------------
    // 2. EUR budget + EUR pricing — same-currency in EUR.
    // ------------------------------------------------------------------

    @Test
    fun `eur budget with eur pricing reserves and settles in eur`() = runBlocking {
        seedPricing("EUR")
        val periodId = configureBudget("EUR")

        val verdict = governance.authorize(decideGateRequest("exec-2"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        val hold = holdsFor("exec-2").single()
        assertEquals(1_875L, hold.holdAmountMicroUnits)
        assertEquals("EUR", hold.currency)

        governance.accountUsageSuspend(stepAccounting("exec-2"))
        assertEquals(5_000_000L - 900L, available(periodId))
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(hold.holdId)!!.status)
    }

    // ------------------------------------------------------------------
    // 3. USD budget + EUR pricing WITH a valid conversion rule — the
    //    reservation and the settlement are CONVERTED into the budget
    //    currency with recorded, reproducible FX evidence.
    // ------------------------------------------------------------------

    @Test
    fun `usd budget with eur pricing and a rule converts both reservation and settlement`() = runBlocking {
        seedPricing("EUR")
        val periodId = configureBudget("USD")
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.1", effectiveFromEpochMs = 0, effectiveUntilEpochMs = null,
            sourceAuthority = "test-ecb"
        )

        val verdict = governance.authorize(decideGateRequest("exec-3"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        val hold = holdsFor("exec-3").single()
        // estimate 1,875 micro-EUR × 1.1 = 2,062.5 → HALF_UP → 2,063 micro-USD
        assertEquals(2_063L, hold.holdAmountMicroUnits)
        assertEquals("USD", hold.currency) // the hold is in the BUDGET currency
        assertEquals(5_000_000L - 2_063L, available(periodId))

        governance.accountUsageSuspend(stepAccounting("exec-3"))
        // actual 900 micro-EUR × 1.1 = 990 micro-USD
        val entry = singleSql(
            "SELECT available_effect_micro_units, currency, original_amount_precise, original_currency, conversion_rule_id " +
                "FROM budget_ledger_entry WHERE hold_id = '${hold.holdId}'"
        )
        assertEquals("-990", entry[0])
        assertEquals("USD", entry[1])
        assertEquals("990", entry[2]) // the pre-rounded converted amount
        assertEquals("EUR", entry[3])
        assertEquals("fx_EUR_USD_0", entry[4]) // reproducible rule attribution
        assertEquals(5_000_000L - 990L, available(periodId))
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(hold.holdId)!!.status)
    }

    // ------------------------------------------------------------------
    // 4. USD budget + EUR pricing WITHOUT a rule — no hold (never 1:1),
    //    and the actual is preserved as DRAFT evidence, never discarded.
    // ------------------------------------------------------------------

    @Test
    fun `usd budget with eur pricing without a rule creates no hold and records the actual as draft evidence`() = runBlocking {
        seedPricing("EUR")
        val periodId = configureBudget("USD") // NO FX rule registered

        val verdict = governance.authorize(decideGateRequest("exec-4"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        assertTrue(
            "the reason states the ceiling is not enforceable for this interaction",
            verdict.reason.contains("لا يُنشأ حجز") && verdict.reason.contains("1:1")
        )
        assertEquals(0, holdsFor("exec-4").size) // NEVER a hold in mismatched units

        // The actual (900 micro-EUR) arrives at settlement with no hold.
        val summary = settlement.settleExecutionUsage(stepAccounting("exec-4"), 900L, "EUR")
        assertTrue(summary is HoldSettlementService.SettlementSummary.NoAction)
        assertTrue(
            (summary as HoldSettlementService.SettlementSummary.NoAction).reason.contains("CURRENCY_MISMATCH_NO_RULE")
        )

        // The real cost is PRESERVED on a DRAFT entry (zero balance effect,
        // original amount + currency recorded) — not silently discarded.
        val draft = singleSql(
            "SELECT posting_state, available_effect_micro_units, currency, original_amount_precise, original_currency, conversion_rule_id " +
                "FROM budget_ledger_entry WHERE budget_period_id = '$periodId' AND entry_type='CONSUMPTION'"
        )
        assertEquals("DRAFT", draft[0])
        assertEquals("0", draft[1])
        assertEquals("USD", draft[2]) // the row's bookkeeping currency = policy currency (trigger)
        assertEquals("900", draft[3])
        assertEquals("EUR", draft[4])
        assertEquals("NULL", draft[5])
        assertEquals(5_000_000L, available(periodId)) // NO balance effect

        // A retried report never duplicates the DRAFT row (idempotent).
        settlement.settleExecutionUsage(stepAccounting("exec-4"), 900L, "EUR")
        val draftCount = db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM budget_ledger_entry WHERE budget_period_id = '$periodId' AND posting_state='DRAFT'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, draftCount)
    }

    // ------------------------------------------------------------------
    // 5. A rule that does not cover the transaction time behaves exactly
    //    like no rule at that instant (window discipline).
    // ------------------------------------------------------------------

    @Test
    fun `a rule that does not cover the transaction time is honestly unusable`() = runBlocking {
        seedPricing("EUR")
        configureBudget("USD")
        // The rule's window [0, 5,000) ended BEFORE the current tick (10,000).
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.1", effectiveFromEpochMs = 0, effectiveUntilEpochMs = 5_000,
            sourceAuthority = "test-ecb"
        )

        val verdict = governance.authorize(decideGateRequest("exec-5"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        assertEquals(0, holdsFor("exec-5").size) // no conversion → no hold
        assertEquals("EUR", fx.resolveRule("EUR", "USD", 4_000L)!!.fromCurrency) // window itself still resolves
        assertNull(fx.resolveRule("EUR", "USD", tick)) // but not at the transaction instant
    }

    // ------------------------------------------------------------------
    // 6. Currency mismatch between the ESTIMATE and the ACTUAL: the
    //    estimate reserved in the budget currency; the actual arrives in
    //    another currency and converts with the recorded rule.
    // ------------------------------------------------------------------

    @Test
    fun `estimate and actual in different currencies convert independently`() = runBlocking {
        seedPricing("USD") // the estimate is computed in USD
        val periodId = configureBudget("USD")
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.2", effectiveFromEpochMs = 0, effectiveUntilEpochMs = null,
            sourceAuthority = "test-ecb"
        )

        governance.authorize(decideGateRequest("exec-6"))
        val hold = holdsFor("exec-6").single()
        assertEquals(1_875L, hold.holdAmountMicroUnits) // USD estimate, USD budget — no conversion

        // The provider's invoice reports the actual in EUR (900 micro-EUR
        // × 1.2 = 1,080 micro-USD) — the settlement converts with the rule.
        val summary = settlement.settleExecutionUsage(stepAccounting("exec-6"), 900L, "EUR")
        assertTrue(summary is HoldSettlementService.SettlementSummary.SettledAndReleased)
        assertEquals(1_080L, (summary as HoldSettlementService.SettlementSummary.SettledAndReleased).settledMicroUnits)
        val entry = singleSql(
            "SELECT available_effect_micro_units, original_currency, conversion_rule_id " +
                "FROM budget_ledger_entry WHERE hold_id = '${hold.holdId}'"
        )
        assertEquals("-1080", entry[0])
        assertEquals("EUR", entry[1])
        assertEquals("fx_EUR_USD_0", entry[2])
        assertEquals(5_000_000L - 1_080L, available(periodId))
    }

    // ------------------------------------------------------------------
    // 7. Late actual under a CHANGED rule: the settlement uses the rule
    //    covering ITS instant (the new window); historical resolution of
    //    the old window stays reproducible; attribution names the new rule.
    // ------------------------------------------------------------------

    @Test
    fun `late actual converts with the rule covering the settlement instant`() = runBlocking {
        seedPricing("EUR")
        val periodId = configureBudget("USD")
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.1", effectiveFromEpochMs = 0, effectiveUntilEpochMs = 20_000,
            sourceAuthority = "test-ecb-window1"
        )

        governance.authorize(decideGateRequest("exec-7"))
        val hold = holdsFor("exec-7").single()
        assertEquals(2_063L, hold.holdAmountMicroUnits) // reserved under rule v1 (1.1)

        // Time passes; a NEW rule window (1.25) becomes effective before the
        // late actual arrives — corrections ship as new windows, never
        // rewrites of the old rule.
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.25", effectiveFromEpochMs = 20_000, effectiveUntilEpochMs = null,
            sourceAuthority = "test-ecb-window2"
        )
        tick = 25_000

        // 900 micro-EUR × 1.25 = 1,125 micro-USD — the settlement instant's rule.
        val summary = settlement.settleExecutionUsage(stepAccounting("exec-7"), 900L, "EUR")
        assertTrue(summary is HoldSettlementService.SettlementSummary.SettledAndReleased)
        val entry = singleSql(
            "SELECT available_effect_micro_units, original_currency, conversion_rule_id " +
                "FROM budget_ledger_entry WHERE hold_id = '${hold.holdId}'"
        )
        assertEquals("-1125", entry[0])
        assertEquals("EUR", entry[1])
        assertEquals("fx_EUR_USD_20000", entry[2]) // the NEW window's rule
        assertEquals(5_000_000L - 1_125L, available(periodId))

        // Historical reproducibility: each window still resolves as recorded.
        assertEquals("1.1", fx.resolveRule("EUR", "USD", 15_000L)!!.ratePrecise)
        assertEquals("1.25", fx.resolveRule("EUR", "USD", 21_000L)!!.ratePrecise)
    }

    // ------------------------------------------------------------------
    // 8. RECOVERY: a hold-authorized step whose FX rule EXPIRED before the
    //    actual arrives keeps its hold (conservative), records the actual
    //    as DRAFT evidence; a retried settlement after a new rule window
    //    is registered converts, settles and releases — the DRAFT row
    //    remains as the historical mismatch record.
    // ------------------------------------------------------------------

    @Test
    fun `hold kept when actual unconvertible - retry after rule registration settles`() = runBlocking {
        seedPricing("EUR")
        val periodId = configureBudget("USD")
        // Rule covers AUTHORIZATION only; it ends before settlement.
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.1", effectiveFromEpochMs = 0, effectiveUntilEpochMs = 20_000,
            sourceAuthority = "test-ecb"
        )

        governance.authorize(decideGateRequest("exec-8b"))
        val hold = holdsFor("exec-8b").single()
        assertEquals(2_063L, hold.holdAmountMicroUnits) // converted estimate under rule v1

        // The actual arrives AFTER the rule window ended: unconvertible →
        // DRAFT record + the hold is KEPT (conservative).
        tick = 25_000
        val first = settlement.settleExecutionUsage(stepAccounting("exec-8b"), 900L, "EUR")
        assertTrue((first as HoldSettlementService.SettlementSummary.NoAction).reason.contains("CURRENCY_MISMATCH_NO_RULE"))
        assertEquals(BudgetLedgerService.HOLD_ACTIVE, db.budgetHoldDao().byId(hold.holdId)!!.status)
        val draft = singleSql(
            "SELECT posting_state, original_amount_precise, original_currency FROM budget_ledger_entry " +
                "WHERE hold_id = '${hold.holdId}' AND posting_state='DRAFT'"
        )
        assertEquals("DRAFT", draft[0])
        assertEquals("900", draft[1])
        assertEquals("EUR", draft[2])

        // A new rule window is registered; the RETRIED settlement converts,
        // settles and releases — the DRAFT row remains as history.
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.25", effectiveFromEpochMs = 20_000, effectiveUntilEpochMs = null,
            sourceAuthority = "test-ecb-2"
        )
        val retried = settlement.settleExecutionUsage(stepAccounting("exec-8b"), 900L, "EUR")
        assertTrue(retried is HoldSettlementService.SettlementSummary.SettledAndReleased)
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(hold.holdId)!!.status)
        assertEquals(5_000_000L - 1_125L, available(periodId))
        val draftStillThere = db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM budget_ledger_entry WHERE hold_id='${hold.holdId}' AND posting_state='DRAFT'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, draftStillThere)
        val postedCount = db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM budget_ledger_entry WHERE hold_id='${hold.holdId}' AND posting_state='POSTED'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, postedCount) // exactly ONE posted consumption
    }

    // ------------------------------------------------------------------
    // 9. Overages convert too: actual above the converted reservation in
    //    a cross-currency policy — the excess is posted (I-11) in the
    //    budget currency, never truncated.
    // ------------------------------------------------------------------

    @Test
    fun `overage above a converted hold posts the excess in the budget currency`() = runBlocking {
        seedPricing("EUR")
        val periodId = configureBudget("USD", limitMicro = 2_500L) // tight USD ceiling
        fx.registerRule(
            fromCurrency = "EUR", toCurrency = "USD",
            ratePrecise = "1.1", effectiveFromEpochMs = 0, effectiveUntilEpochMs = null,
            sourceAuthority = "test-ecb"
        )

        governance.authorize(decideGateRequest("exec-9"))
        val hold = holdsFor("exec-9").single()
        assertEquals(2_063L, hold.holdAmountMicroUnits)

        // actual 3,000 micro-EUR × 1.1 = 3,300 micro-USD > hold 2,063.
        val summary = settlement.settleExecutionUsage(stepAccounting("exec-9"), 3_000L, "EUR")
        assertTrue(summary is HoldSettlementService.SettlementSummary.SettledWithOverage)
        val s = summary as HoldSettlementService.SettlementSummary.SettledWithOverage
        assertEquals(2_063L, s.settledMicroUnits)
        assertEquals(1_237L, s.overagePostedMicroUnits) // 3,300 − 2,063
        assertEquals(2_500L - 3_300L, available(periodId)) // limit − full actual (I-11)
        assertNotNull(db.budgetHoldDao().byId(hold.holdId))
        // the full hold settled (no remainder to release)
        assertEquals(BudgetLedgerService.HOLD_SETTLED, db.budgetHoldDao().byId(hold.holdId)!!.status)
    }
}
