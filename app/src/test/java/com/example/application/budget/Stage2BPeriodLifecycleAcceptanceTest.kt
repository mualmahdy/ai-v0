package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.domain.core.budget.UsageDispatchState
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import kotlinx.coroutines.flow.first
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
 * STAGE 2B — Workstream B: period lifecycle & accounting-state consistency
 * (acceptance; Stage 1A DEBT-1 + DEBT-6 closure evidence)
 * ============================================================================
 * Pins the documented lifecycle contract (BudgetPolicyManagementService
 * class doc, WS-B):
 *
 *  - DERIVED lifecycle (BudgetDashboardService.PeriodLifecycle): a period
 *    whose window passed never displays as merely OPEN; a superseded
 *    version's periods derive SUPERSEDED; outstanding holds are NAMED,
 *    never silently closed.
 *  - Stored-status convergence: createPolicyVersion and rollover CLOSE the
 *    ended, fully-settled periods of retired/old versions; periods with
 *    outstanding mutable holds stay OPEN.
 *  - DEBT-1 (true-expiry unification): settle/release treat a
 *    timestamp-expired hold as EXPIRED regardless of whether the sweep
 *    re-stamped its stored status — the same late actual lands identically
 *    with and without the sweep.
 *  - DEBT-6 (late-actual attribution contract): a late actual settles
 *    against its SURVIVING hold's period; with no surviving hold it posts
 *    under the CURRENTLY-ACTIVE period of the governing scope — one named
 *    owner, no silent moves between periods (append-only ledger).
 */
@RunWith(RobolectricTestRunner::class)
class Stage2BPeriodLifecycleAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var gate: HoldBasedBudgetGate
    private lateinit var settlement: HoldSettlementService
    private lateinit var governance: EconomicGovernanceService
    private lateinit var economicStore: RoomEconomicStore
    private lateinit var policyManagement: BudgetPolicyManagementService
    private lateinit var dashboard: BudgetDashboardService

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
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${idSeq++}" },
            clock = { tick }
        )
        gate = HoldBasedBudgetGate(ledger, clock = { tick })
        settlement = HoldSettlementService(database = db, ledger = ledger, telemetry = null, clock = { tick })
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
        dashboard = BudgetDashboardService(database = db, clock = { tick })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Fixtures (same shapes as the Stage 2B currency suite)
    // ------------------------------------------------------------------

    private suspend fun configureBudget(
        workspaceId: String = "ws-1",
        limitMicro: Long = 5_000_000L,
        start: Long = tick,
        end: Long = tick + periodMs
    ): String {
        val created = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = workspaceId,
                limitMicroUnits = limitMicro,
                currency = "USD",
                periodStartEpochMs = start,
                periodEndEpochMs = end
            )
        )
        return when (created) {
            is BudgetPolicyManagementService.ConfigureOutcome.Created -> created.periodId
            is BudgetPolicyManagementService.ConfigureOutcome.NewVersion -> created.periodId
            is BudgetPolicyManagementService.ConfigureOutcome.NewPeriod -> created.periodId
            is BudgetPolicyManagementService.ConfigureOutcome.Unchanged ->
                (created as BudgetPolicyManagementService.ConfigureOutcome.Unchanged).periodId
        }
    }

    private suspend fun seedPricing() {
        economicStore.upsertPricing(
            PricingEntry(
                id = "pe-1", scope = PricingScope.MODEL, providerId = "prov", serviceId = "svc-1", modelId = "m1",
                inputPricePerMillion = MoneyAmount.of(1500, "USD"),
                outputPricePerMillion = MoneyAmount.of(3000, "USD"),
                cachedInputPricePerMillion = null,
                billingClass = BillingClass.PAID,
                pricingVersion = "v1",
                effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                provenance = "test"
            )
        )
    }

    private fun decideGateRequest(executionId: String, workspaceId: String = "ws-1") =
        EconomicAuthorizationRequest(
            executionId = executionId,
            workspaceId = workspaceId,
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

    private fun stepAccounting(executionId: String, workspaceId: String = "ws-1") = UsageAccountingInput(
        executionId = executionId,
        taskId = "task-1",
        workspaceId = workspaceId,
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

    private suspend fun rowFor(periodId: String): PeriodBalanceRow =
        dashboard.periodBalances(tick).first().single { it.periodId == periodId }

    private suspend fun singleValue(query: String): String =
        db.openHelper.writableDatabase.query(query).use { c -> c.moveToFirst(); c.getString(0) ?: "NULL" }

    // ------------------------------------------------------------------
    // 1. DERIVED lifecycle: an ended period with no outstanding holds
    //    derives ENDED (never visually OPEN); with an outstanding hold it
    //    derives ENDED_WITH_OUTSTANDING_HOLDS.
    // ------------------------------------------------------------------

    @Test
    fun `an ended period derives ENDED and never displays as merely open`() = runBlocking {
        seedPricing()
        val periodId = configureBudget()

        // In-window: ACTIVE.
        assertEquals(BudgetDashboardService.PeriodLifecycle.ACTIVE, dashboard.lifecycleOf(rowFor(periodId), tick))

        // The window passes with NO holds at all.
        tick += periodMs + 1
        assertEquals(BudgetDashboardService.PeriodLifecycle.ENDED, dashboard.lifecycleOf(rowFor(periodId), tick))
        // The stored status is still OPEN (no bookkeeping ran) — the DERIVED
        // state is honest without it.
        assertEquals("OPEN", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$periodId'"))

        // The bootstrap bookkeeping sweep converges the stored status.
        ledger.closeEligiblePeriods()
        assertEquals("CLOSED", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$periodId'"))
        assertEquals(BudgetDashboardService.PeriodLifecycle.CLOSED, dashboard.lifecycleOf(rowFor(periodId), tick))
    }

    @Test
    fun `an ended period with an outstanding hold derives ENDED_WITH_OUTSTANDING_HOLDS and is never closed`() = runBlocking {
        seedPricing()
        // A SHORT window (2h) so it ends while the hold's 24h TTL still runs.
        val periodId = configureBudget(start = tick, end = tick + 2 * 3_600_000L)
        governance.authorize(decideGateRequest("exec-hold")) // outstanding ACTIVE hold

        tick += 2 * 3_600_000L + 1 // window passed; the hold's TTL (24h) is still running
        assertEquals(
            BudgetDashboardService.PeriodLifecycle.ENDED_WITH_OUTSTANDING_HOLDS,
            dashboard.lifecycleOf(rowFor(periodId), tick)
        )

        // The bookkeeping sweep REFUSES to close it (accounting obligations).
        val closed = ledger.closeEligiblePeriods()
        assertEquals(0, closed)
        assertEquals("OPEN", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$periodId'"))

        // After the hold's TTL passes, the obligation is gone (lazy expiry):
        // derived state is plain ENDED and the sweep may close it.
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        assertEquals(BudgetDashboardService.PeriodLifecycle.ENDED, dashboard.lifecycleOf(rowFor(periodId), tick))
        ledger.closeEligiblePeriods()
        assertEquals("CLOSED", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$periodId'"))
    }

    // ------------------------------------------------------------------
    // 2. Supersession: createPolicyVersion retires the old version AND
    //    closes its ended, settled periods; an in-window old period stays
    //    OPEN but derives SUPERSEDED.
    // ------------------------------------------------------------------

    @Test
    fun `a superseded policy version gets its ended periods closed and its windows derive SUPERSEDED`() = runBlocking {
        seedPricing()
        val firstPeriodId = configureBudget(limitMicro = 5_000_000L)

        // A material edit while the first period is STILL IN-WINDOW.
        val edited = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = "ws-1",
                limitMicroUnits = 6_000_000L,
                currency = "USD",
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )
        assertTrue(edited is BudgetPolicyManagementService.ConfigureOutcome.NewVersion)
        val newPeriodId = (edited as BudgetPolicyManagementService.ConfigureOutcome.NewVersion).periodId

        // The old version's IN-WINDOW period stays stored OPEN (it may still
        // own obligations) but derives SUPERSEDED — never silently ACTIVE.
        assertEquals("OPEN", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$firstPeriodId'"))
        assertEquals(BudgetDashboardService.PeriodLifecycle.SUPERSEDED, dashboard.lifecycleOf(rowFor(firstPeriodId), tick))
        assertEquals(BudgetDashboardService.PeriodLifecycle.ACTIVE, dashboard.lifecycleOf(rowFor(newPeriodId), tick))

        // Once its window ends with no outstanding holds, the stored status
        // converges on the NEXT version edit (or the sweep).
        tick += periodMs + 1
        ledger.closeEligiblePeriods()
        assertEquals("CLOSED", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$firstPeriodId'"))
    }

    // ------------------------------------------------------------------
    // 3. Rollover: configuring again after the window ended opens a new
    //    period under the SAME immutable version and closes the ended one.
    // ------------------------------------------------------------------

    @Test
    fun `period rollover closes the ended settled period and opens a new one under the same version`() = runBlocking {
        seedPricing()
        val firstPeriodId = configureBudget()
        val policyIdBefore = singleValue(
            "SELECT budget_policy_id FROM budget_period WHERE budget_period_id='$firstPeriodId'"
        )

        tick += periodMs + 1
        val rolled = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = "ws-1",
                limitMicroUnits = 5_000_000L, // unchanged material fields
                currency = "USD",
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )
        assertTrue(rolled is BudgetPolicyManagementService.ConfigureOutcome.NewPeriod)
        val newPeriodId = (rolled as BudgetPolicyManagementService.ConfigureOutcome.NewPeriod).periodId
        assertEquals(policyIdBefore, singleValue("SELECT budget_policy_id FROM budget_period WHERE budget_period_id='$newPeriodId'"))
        assertEquals("CLOSED", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$firstPeriodId'"))
        assertEquals("OPEN", singleValue("SELECT period_status FROM budget_period WHERE budget_period_id='$newPeriodId'"))
    }

    // ------------------------------------------------------------------
    // 4. DEBT-1 closure — the SAME late actual lands identically with and
    //    without the expiry sweep (true-expiry validation in settleHold).
    // ------------------------------------------------------------------

    @Test
    fun `late actual lands identically with and without the expiry sweep - DEBT-1`() = runBlocking {
        seedPricing()
        val periodIdA = configureBudget(workspaceId = "ws-a")
        val periodIdB = configureBudget(workspaceId = "ws-b")

        // Two identical scenarios; only the sweep differs.
        governance.authorize(decideGateRequest("exec-swept", workspaceId = "ws-a"))
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        // Scenario A: the sweep marks the hold EXPIRED (stored status).
        ledger.expireHolds()
        assertEquals(
            "EXPIRED",
            singleValue("SELECT status FROM budget_hold WHERE reason LIKE '%exec-swept%'")
        )

        // Scenario B: NO sweep — a hold whose TTL has passed but whose stored
        // status is still ACTIVE (the pre-Stage-2B divergence case). Crafted
        // through the SAME ledger API the gate uses, with a 1ms TTL.
        ledger.authorizeAndReserve(
            scopeType = "WORKSPACE",
            platformObjectId = "ws-b",
            amountMicroUnits = 1_875L,
            reason = HoldKeys.holdReason("exec-unswept", 0, 1),
            idempotencyKey = HoldKeys.authorizeIdempotencyKey("exec-unswept", 0, 1, "WORKSPACE", "ws-b"),
            expiresAtEpochMs = tick + 1,
            nowEpochMs = tick
        )
        tick += 2
        assertEquals(
            "ACTIVE",
            singleValue("SELECT status FROM budget_hold WHERE reason LIKE '%exec-unswept%'")
        )

        // The SAME late actual for both.
        governance.accountUsageSuspend(stepAccounting("exec-swept", workspaceId = "ws-a"))
        governance.accountUsageSuspend(stepAccounting("exec-unswept", workspaceId = "ws-b"))

        // IDENTICAL outcomes: one POSTED consumption entry of the full actual
        // (900 micro), posted WITHOUT the expired hold, under the active
        // period; NO settlement row for the expired hold; availability equal.
        for (ws in listOf("ws-a" to periodIdA, "ws-b" to periodIdB)) {
            val (_, periodId) = ws
            val posted = singleValue(
                "SELECT COUNT(*) FROM budget_ledger_entry WHERE budget_period_id='$periodId' " +
                    "AND posting_state='POSTED' AND entry_type='CONSUMPTION'"
            )
            assertEquals("1", posted)
            val effect = singleValue(
                "SELECT available_effect_micro_units FROM budget_ledger_entry WHERE budget_period_id='$periodId' " +
                    "AND posting_state='POSTED'"
            )
            assertEquals("-900", effect)
            assertEquals(5_000_000L - 900L, ledger.availableForPeriod(periodId).availableMicroUnits)
        }
        // Neither hold was settled (both expired; the actual posted fresh).
        val settleCount = singleValue("SELECT COUNT(*) FROM budget_hold_settlement WHERE settlement_type='SETTLE'")
        assertEquals("0", settleCount)
    }

    // ------------------------------------------------------------------
    // 5. DEBT-6 closure — the late-actual attribution contract:
    //    surviving hold → the HOLD's period; no surviving hold → the
    //    CURRENTLY-ACTIVE period of the governing scope. No silent moves.
    // ------------------------------------------------------------------

    @Test
    fun `late actual with a surviving hold attributes to the hold period - DEBT-6`() = runBlocking {
        seedPricing()
        val periodId = configureBudget()
        governance.authorize(decideGateRequest("exec-attr"))
        val hold = db.budgetHoldDao().allForReason(HoldKeys.holdReason("exec-attr", 0, 1)).single()

        // The actual arrives LATE but WITHIN the hold's TTL: the hold
        // survives and the settlement lands under the HOLD's period.
        tick += 1_000
        governance.accountUsageSuspend(stepAccounting("exec-attr"))

        val entryPeriod = singleValue("SELECT budget_period_id FROM budget_ledger_entry WHERE hold_id='${hold.holdId}'")
        assertEquals(periodId, entryPeriod)
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(hold.holdId)!!.status)
    }

    @Test
    fun `late actual with a rolled-over period attributes per the documented owner contract - DEBT-6`() = runBlocking {
        seedPricing()
        val firstPeriodId = configureBudget()
        governance.authorize(decideGateRequest("exec-late2"))
        val hold = db.budgetHoldDao().allForReason(HoldKeys.holdReason("exec-late2", 0, 1)).single()

        // The period rolls over (new OPEN period under the SAME policy),
        // and the hold's TTL passes before the actual arrives.
        tick += periodMs + 1
        val rolled = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = "ws-1",
                limitMicroUnits = 5_000_000L,
                currency = "USD",
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )
        val newPeriodId = (rolled as BudgetPolicyManagementService.ConfigureOutcome.NewPeriod).periodId
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1

        // No surviving hold (expired) → the actual posts under the
        // CURRENTLY-ACTIVE period of the governing scope: the NEW period.
        governance.accountUsageSuspend(stepAccounting("exec-late2"))
        val postedPeriod = singleValue(
            "SELECT budget_period_id FROM budget_ledger_entry WHERE posting_state='POSTED' AND entry_type='CONSUMPTION'"
        )
        assertEquals(newPeriodId, postedPeriod)
        assertEquals(5_000_000L - 900L, ledger.availableForPeriod(newPeriodId).availableMicroUnits)
        assertEquals(5_000_000L, ledger.availableForPeriod(firstPeriodId).availableMicroUnits)
        // The old hold was never settled; the ledger was never moved.
        assertEquals("0", singleValue("SELECT COUNT(*) FROM budget_hold_settlement"))
    }

    // ------------------------------------------------------------------
    // 6. Financial immutability of the policy fields across versioning.
    // ------------------------------------------------------------------

    @Test
    fun `policy financial fields stay immutable across versions`() = runBlocking {
        seedPricing()
        configureBudget(limitMicro = 5_000_000L)
        val familyId = singleValue("SELECT policy_family_id FROM budget_policy")
        val v1 = db.budgetPolicyDao().familyHistory(familyId).single()
        assertEquals(5_000_000L, v1.defaultLimitMicroUnits)
        assertEquals("USD", v1.currency)

        val edited = policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = "ws-1",
                limitMicroUnits = 7_000_000L,
                currency = "EUR",
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )
        assertTrue(edited is BudgetPolicyManagementService.ConfigureOutcome.NewVersion)

        // v1 is RETIRED and its fields are untouched (frozen history).
        val v1After = db.budgetPolicyDao().byId(v1.budgetPolicyId)!!
        assertEquals("RETIRED", v1After.policyStatus)
        assertEquals(5_000_000L, v1After.defaultLimitMicroUnits)
        assertEquals("USD", v1After.currency)
        // v2 owns the new fields.
        val v2 = db.budgetPolicyDao().familyHistory(v1.policyFamilyId).maxByOrNull { it.policyVersion }!!
        assertEquals(7_000_000L, v2.defaultLimitMicroUnits)
        assertEquals("EUR", v2.currency)
        assertEquals("ACTIVE", v2.policyStatus)
        assertNotNull(v2)
    }
}
