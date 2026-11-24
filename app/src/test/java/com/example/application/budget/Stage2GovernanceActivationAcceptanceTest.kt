package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 2 — Economic governance activation acceptance (production paths)
 * ============================================================================
 * Stage 1A closed the reservation-identity/hold-lifecycle defects and is
 * PARTIALLY CLOSED / accepted for this iteration. Stage 2 connects the
 * accounting core to the real management and user-configuration paths:
 *
 *  - BudgetPolicyManagementService — the modern budget policy authoring
 *    surface (first policy + period, immutable policy versions on edit,
 *    period rollover, verified no-ops on repeated identical writes).
 *  - PricingManagementService — the verified pricing path (USER_CONFIG
 *    evidence through the existing pricing domain + versioned catalog,
 *    UNKNOWN preserved).
 *  - HoldBasedBudgetGate Stage-2 transition semantics — an expired modern
 *    accounting period NEVER silently falls back to the stale legacy
 *    allocation.
 *
 * The suite drives the REAL production stack on a REAL (in-memory) Room
 * database with the production DB triggers — the same fixture family as
 * Stage1AHoldLifecycleAcceptanceTest: EconomicGovernanceService →
 * HoldBasedBudgetGate → BudgetLedgerService for authorization;
 * accountUsageSuspend → HoldSettlementService for settlement; and the NEW
 * management services over the SAME database. Assertions are on STORED
 * STATE (policy rows, period rows, holds, settlements, ledger entries,
 * pricing/catalog rows, computed availability) — never on returned message
 * strings alone.
 */
@RunWith(RobolectricTestRunner::class)
class Stage2GovernanceActivationAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var gate: HoldBasedBudgetGate
    private lateinit var settlement: HoldSettlementService
    private lateinit var governance: EconomicGovernanceService
    /** The PRODUCTION Room-backed economic store — one repository instance
     *  shared by the governance facade and the management services, exactly
     *  as AppContainer wires it (pricing_entries / cost ledger / budget_
     *  allocations are the REAL tables the catalog mirror reads). */
    private lateinit var economicStore: RoomEconomicStore
    private lateinit var policyManagement: BudgetPolicyManagementService
    private lateinit var pricingManagement: PricingManagementService
    private lateinit var dashboard: BudgetDashboardService

    /** Fake monotonic clock — every service sees the SAME tick. */
    private var tick = 10_000L
    /** Id sequence — deliberately DECOUPLED from the clock. */
    private var idSeq = 0L
    private val periodDays = 30L
    private val periodMs = periodDays * 86_400_000L

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
        pricingManagement = PricingManagementService(
            pricingRepository = economicStore,
            pricingCatalog = PricingCatalogService(database = db, clock = { tick }),
            clock = { tick }
        )
        dashboard = BudgetDashboardService(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Fixture helpers — the production request/input constructions
    // (same shapes as Stage1AHoldLifecycleAcceptanceTest)
    // ------------------------------------------------------------------

    private fun configureRequest(
        workspaceId: String = "ws-1",
        limitMicroUnits: Long = 5_000_000L,
        overagePolicy: String = BudgetLedgerService.OVERAGE_HARD_LIMIT,
        start: Long = tick,
        end: Long = tick + periodMs
    ) = BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
        workspaceId = workspaceId,
        limitMicroUnits = limitMicroUnits,
        currency = "USD",
        overagePolicy = overagePolicy,
        periodStartEpochMs = start,
        periodEndEpochMs = end
    )

    /** MODEL-scope pricing: 1500 in / 3000 out micro per million tokens. */
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

    /**
     * The DECIDE-GATE request construction: expectedTotalTokens = 1,000,000
     * → 3:1 split → estimate 750k×1500/1M + 250k×3000/1M = 1,875 micro.
     */
    private fun decideGateRequest(
        executionId: String,
        workspaceId: String? = "ws-1",
        providerId: String? = "prov"
    ) = EconomicAuthorizationRequest(
        executionId = executionId,
        workspaceId = workspaceId,
        agentId = "agent-1",
        taskId = "task-1",
        providerId = providerId,
        serviceId = "svc-1",
        modelId = "m1",
        resourceId = "res-1",
        isLocalResource = false,
        expectedTotalTokens = 1_000_000,
        actionTypeCode = "EXECUTE_STEP",
        stepIndex = 0,
        attempt = 1
    )

    /** The orchestrator's post-step accounting input. */
    private fun stepAccounting(
        executionId: String,
        inputTokens: Int,
        outputTokens: Int,
        hasUsageReport: Boolean,
        workspaceId: String? = "ws-1",
        providerId: String? = if (hasUsageReport) "prov" else null
    ) = UsageAccountingInput(
        executionId = executionId,
        taskId = "task-1",
        workspaceId = workspaceId,
        agentId = "agent-1",
        providerId = providerId,
        serviceId = if (hasUsageReport) "svc-1" else null,
        modelId = if (hasUsageReport) "m1" else null,
        resourceId = if (hasUsageReport) "res-1" else null,
        usage = TokenUsageRecord(inputTokens = inputTokens, outputTokens = outputTokens, cachedTokens = 0),
        isActualProviderReport = hasUsageReport,
        stepIndex = 0,
        attempt = 1,
        dispatchState = if (hasUsageReport) UsageDispatchState.DISPATCHED else UsageDispatchState.NOT_DISPATCHED,
        hasUsageReport = hasUsageReport,
        timestampEpochMs = tick
    )

    private suspend fun holdRowCount(): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_hold")
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private suspend fun policyRowCount(): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_policy")
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private suspend fun consumptionEntryCount(): Int =
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM budget_ledger_entry WHERE entry_type='CONSUMPTION'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    // ------------------------------------------------------------------
    // 1. Creating a workspace budget through the actual management path
    //    creates the expected modern policy and period.
    // ------------------------------------------------------------------

    @Test
    fun `create workspace budget through the management path creates policy period and authority`() = runBlocking {
        val outcome = policyManagement.configureWorkspaceBudget(configureRequest())

        assertTrue(outcome is BudgetPolicyManagementService.ConfigureOutcome.Created)
        outcome as BudgetPolicyManagementService.ConfigureOutcome.Created

        // The policy row: v1, ACTIVE, workspace-scoped, correct material fields.
        val policy = db.budgetPolicyDao().byId(outcome.policyId)
        assertNotNull(policy)
        assertEquals(1, policy!!.policyVersion)
        assertEquals(BudgetLedgerService.STATUS_ACTIVE, policy.policyStatus)
        assertEquals("WORKSPACE", policy.budgetScopeType)
        assertEquals(5_000_000L, policy.defaultLimitMicroUnits)
        assertEquals("USD", policy.currency)
        assertEquals(BudgetLedgerService.OVERAGE_HARD_LIMIT, policy.overagePolicy)
        assertEquals(outcome.policyFamilyId, policy.policyFamilyId)

        // The scope descriptor: canonical WORKSPACE:ws-1 identity.
        val descriptor = db.scopeDescriptorDao().byId(outcome.scopeId)
        assertNotNull(descriptor)
        assertEquals("WORKSPACE", descriptor!!.scopeKind)
        assertEquals("ws-1", descriptor.platformObjectId)

        // The first period: OPEN, governed by v1, covering now.
        val period = db.budgetPeriodDao().byId(outcome.periodId)
        assertNotNull(period)
        assertEquals(BudgetLedgerService.PERIOD_OPEN, period!!.periodStatus)
        assertEquals(outcome.policyId, period.budgetPolicyId)
        assertEquals(5_000_000L, period.limitMicroUnits)
        assertTrue(period.periodStartEpochMs <= tick && period.periodEndEpochMs > tick)

        // The authority projection reports the modern accounting policy.
        val authority = policyManagement.workspaceAuthority("ws-1")
        assertEquals(
            BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING,
            authority.kind
        )
        assertEquals(outcome.policyId, authority.modernPolicy?.budgetPolicyId)
        assertEquals(outcome.periodId, authority.modernPeriod?.budgetPeriodId)
        assertEquals(5_000_000L, authority.modernBalance?.limitMicroUnits)
    }

    // ------------------------------------------------------------------
    // 2. Editing a budget produces the expected immutable policy version.
    // ------------------------------------------------------------------

    @Test
    fun `editing the budget creates an immutable new policy version in the same family`() = runBlocking {
        val first = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 5_000_000L))
        val second = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 8_000_000L))

        assertTrue(second is BudgetPolicyManagementService.ConfigureOutcome.NewVersion)
        second as BudgetPolicyManagementService.ConfigureOutcome.NewVersion
        assertEquals((first as BudgetPolicyManagementService.ConfigureOutcome.Created).policyFamilyId, second.policyFamilyId)
        assertEquals(first.policyId, second.previousPolicyId)

        // The family history: exactly two versions, v1 RETIRED with its
        // financial fields UNCHANGED (immutability), v2 ACTIVE.
        val history = db.budgetPolicyDao().familyHistory(first.policyFamilyId)
        assertEquals(2, history.size)
        val v1 = history.single { it.policyVersion == 1 }
        val v2 = history.single { it.policyVersion == 2 }
        assertEquals(BudgetLedgerService.STATUS_RETIRED, v1.policyStatus)
        assertEquals(5_000_000L, v1.defaultLimitMicroUnits) // frozen — never mutated in place
        assertEquals(BudgetLedgerService.STATUS_ACTIVE, v2.policyStatus)
        assertEquals(8_000_000L, v2.defaultLimitMicroUnits)

        // v2 owns a NEW open period; v1's period still exists (history kept).
        val v2Periods = db.budgetPeriodDao().forPolicy(v2.budgetPolicyId)
        assertEquals(1, v2Periods.size)
        assertEquals(BudgetLedgerService.PERIOD_OPEN, v2Periods.single().periodStatus)
        val v1Periods = db.budgetPeriodDao().forPolicy(v1.budgetPolicyId)
        assertEquals(1, v1Periods.size)

        // The ACTIVE authority is now v2.
        val authority = policyManagement.workspaceAuthority("ws-1")
        assertEquals(v2.budgetPolicyId, authority.modernPolicy?.budgetPolicyId)
        assertEquals(8_000_000L, authority.modernBalance?.limitMicroUnits)
    }

    // ------------------------------------------------------------------
    // 3. The modern budget is visible through the real dashboard projection.
    // ------------------------------------------------------------------

    @Test
    fun `the modern budget is visible through the real dashboard projection`() = runBlocking {
        val created = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 5_000_000L))
            as BudgetPolicyManagementService.ConfigureOutcome.Created

        // The REAL Room-backed dashboard Flow (the Budget screen's feed).
        val rows = dashboard.periodBalances(tick).first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(created.periodId, row.periodId)
        assertEquals(5_000_000L, row.limitMicroUnits)
        assertEquals(0L, row.postedEffectsMicroUnits)
        assertEquals(0L, row.remainingHoldMicroUnits)
        // The balance equation over raw rows: available = limit + posted − holds.
        assertEquals(5_000_000L, row.availableMicroUnits())

        // The authority projection's equation agrees with the dashboard row.
        val authority = policyManagement.workspaceAuthority("ws-1")
        assertEquals(row.availableMicroUnits(), authority.modernBalance?.availableMicroUnits)
    }

    // ------------------------------------------------------------------
    // 4. An explicitly configured modern policy is selected by the
    //    production authorization path.
    // ------------------------------------------------------------------

    @Test
    fun `an explicitly configured modern policy is selected by the production authorization path`() = runBlocking {
        seedPricing()
        val created = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 10_000L))
            as BudgetPolicyManagementService.ConfigureOutcome.Created

        val verdict = governance.authorize(decideGateRequest("exec-1"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)

        // The reservation landed under the CONFIGURED policy's period — the
        // production gate selected the explicitly configured modern policy.
        val holds = db.budgetHoldDao().forPeriod(created.periodId)
        assertEquals(1, holds.size)
        val hold = holds.single()
        assertEquals(1_875L, hold.holdAmountMicroUnits)
        assertEquals("USD", hold.currency)
        assertEquals(
            HoldKeys.authorizeIdempotencyKey("exec-1", 0, 1, "WORKSPACE", "ws-1"),
            hold.idempotencyKey
        )
        // Availability after the hold, from the balance equation.
        assertEquals(10_000L - 1_875L, ledger.availableForPeriod(created.periodId).availableMicroUnits)
    }

    // ------------------------------------------------------------------
    // 5. A priced provider/model interaction creates a reservation and
    //    settles against its actual usage.
    // ------------------------------------------------------------------

    @Test
    fun `a priced interaction reserves and settles against its actual usage`() = runBlocking {
        seedPricing()
        val created = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 10_000L))
            as BudgetPolicyManagementService.ConfigureOutcome.Created
        governance.authorize(decideGateRequest("exec-2"))

        // Actual usage 400k in / 100k out → 600 + 300 = 900 micro ACTUAL.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-2", inputTokens = 400_000, outputTokens = 100_000, hasUsageReport = true)
        )
        assertNotNull(record)
        assertEquals(900L, record!!.cost?.amountMicro)

        val hold = db.budgetHoldDao().forPeriod(created.periodId).single()
        // 900 settled + 975 remainder released → terminal, nothing leaked.
        assertEquals(BudgetLedgerService.HOLD_RELEASED, hold.status)
        val settlements = db.budgetHoldSettlementDao().forHold(hold.holdId)
        assertEquals(2, settlements.size)
        assertTrue(settlements.any { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE && it.amountMicroUnits == 900L })
        assertTrue(settlements.any { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE && it.amountMicroUnits == 975L })

        // The ACTUAL consumption entry, the exact amount, never truncated.
        assertEquals(1, consumptionEntryCount())
        val entry = db.budgetLedgerEntryDao().byIdempotencyKey(
            created.periodId, HoldKeys.settleKey("exec-2", 0, 1)
        )
        assertNotNull(entry)
        assertEquals(-900L, entry!!.availableEffectMicroUnits)
        assertEquals(BudgetLedgerService.BASIS_ACTUAL, entry.entryBasis)

        // The balance equation after the full cycle.
        assertEquals(10_000L - 900L, ledger.availableForPeriod(created.periodId).availableMicroUnits)
    }

    // ------------------------------------------------------------------
    // 6. A provider/model with UNKNOWN pricing is never assigned a
    //    fabricated price or fabricated monetary cost.
    // ------------------------------------------------------------------

    @Test
    fun `unknown pricing is never assigned a fabricated price or monetary cost`() = runBlocking {
        // NO pricing evidence for provider "ghost".
        policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 10_000L))

        // Resolution: honestly UNKNOWN.
        assertTrue(governance.resolvePricing("ghost", "svc-1", "m1").isUnknown)

        // Authorization under the active policy: I-3 — ALLOWED with no hold
        // (UNKNOWN is never zero; no fabricated reservation).
        val verdict = governance.authorize(decideGateRequest("exec-3", providerId = "ghost"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        assertNull(verdict.estimate.expectedCost)
        assertEquals(0, holdRowCount())

        // Accounting with a real usage report but UNKNOWN pricing: the cost
        // stays UNKNOWN — no fabricated monetary amount anywhere.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-3", inputTokens = 400_000, outputTokens = 100_000, hasUsageReport = true, providerId = "ghost")
        )
        assertNotNull(record)
        assertNull(record!!.cost?.amountMicro) // UNKNOWN, not zero
        assertEquals(
            com.example.domain.core.budget.CostStatus.UNKNOWN,
            record.costStatus
        )
        // The modern ledger received NO fabricated consumption entry.
        assertEquals(0, consumptionEntryCount())
        // And no hold exists to release or settle.
        assertEquals(0, holdRowCount())
    }

    // ------------------------------------------------------------------
    // 7. Legacy workspaces without modern policies preserve the existing
    //    legacy authorization behavior.
    // ------------------------------------------------------------------

    @Test
    fun `legacy workspaces without modern policies keep the legacy authorization behavior`() = runBlocking {
        seedPricing()
        // The legacy allocation the Governance editor writes (same policy
        // shape as ManageWorkspaceBudgetUseCase).
        governance.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(1_000L, "USD"),
            policy = BudgetPolicy(
                actions = listOf(BudgetPolicyAction.HARD_LIMIT, BudgetPolicyAction.AUTO_LOCAL_FALLBACK),
                warnThresholdRatio = 0.8f
            )
        )

        // Authority projection: LEGACY (transition not started).
        val authority = policyManagement.workspaceAuthority("ws-1")
        assertEquals(
            BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.LEGACY_ALLOCATION,
            authority.kind
        )

        // Estimate 1,875 > 1,000 legacy allocation → the legacy HARD_LIMIT
        // verdict, exactly as before the modern path existed.
        val denied = governance.authorize(decideGateRequest("exec-4"))
        assertEquals(EconomicGateDecision.DENIED, denied.decision)
        assertTrue(denied.reason.startsWith("BUDGET"))
        assertEquals(0, holdRowCount()) // legacy path never creates holds

        // Below the allocation → ALLOWED through the legacy comparison.
        governance.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(10_000L, "USD"),
            policy = BudgetPolicy(
                actions = listOf(BudgetPolicyAction.HARD_LIMIT, BudgetPolicyAction.AUTO_LOCAL_FALLBACK),
                warnThresholdRatio = 0.8f
            )
        )
        val allowed = governance.authorize(decideGateRequest("exec-5"))
        assertEquals(EconomicGateDecision.ALLOWED, allowed.decision)
        assertEquals(0, holdRowCount())
        assertEquals(0, policyRowCount()) // no modern policy was created by observing
    }

    // ------------------------------------------------------------------
    // 8. A workspace with BOTH historical legacy data and an explicitly
    //    configured modern policy follows the documented transition
    //    semantics.
    // ------------------------------------------------------------------

    @Test
    fun `legacy data plus modern policy follows the documented transition semantics`() = runBlocking {
        seedPricing()
        // Historical legacy data (stale after the transition begins).
        governance.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(50_000L, "USD"),
            policy = BudgetPolicy(
                actions = listOf(BudgetPolicyAction.HARD_LIMIT, BudgetPolicyAction.AUTO_LOCAL_FALLBACK),
                warnThresholdRatio = 0.8f
            )
        )

        // Explicit transition: configure the modern policy (3,000 micro —
        // STRICTER than the legacy 50,000).
        val created = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 3_000L))
            as BudgetPolicyManagementService.ConfigureOutcome.Created

        // While the modern period covers `now`, the MODERN policy governs:
        // the hold lands under the modern period, availability is computed
        // from the modern equation only (legacy consumed amounts are NOT
        // silently converted into modern balances).
        val verdict = governance.authorize(decideGateRequest("exec-6"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        val hold = db.budgetHoldDao().forPeriod(created.periodId).single()
        assertEquals(1_875L, hold.holdAmountMicroUnits)
        assertEquals(3_000L - 1_875L, ledger.availableForPeriod(created.periodId).availableMicroUnits)

        // The period ends and no new one is configured: spending is DENIED
        // honestly — NEVER a silent fallback to the stale legacy 50,000.
        tick += periodMs + 1
        val expired = governance.authorize(decideGateRequest("exec-7"))
        assertEquals(EconomicGateDecision.DENIED, expired.decision)
        assertTrue(expired.reason.contains("انتهت فترتها المحاسبية"))
        assertEquals(1, holdRowCount()) // no new hold was created

        // The authority projection reports the ended period honestly.
        val authority = policyManagement.workspaceAuthority("ws-1")
        assertEquals(
            BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_PERIOD_ENDED,
            authority.kind
        )
        assertTrue(authority.modernPeriodEnded)
        // The legacy allocation is still THERE (compatibility view) but does
        // not govern.
        assertNotNull(authority.legacyAllocation)

        // Renewal: configuring again opens a new period under the same
        // immutable policy version — spending authority returns.
        val renewed = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 3_000L))
        assertTrue(renewed is BudgetPolicyManagementService.ConfigureOutcome.NewPeriod)
        val allowedAgain = governance.authorize(decideGateRequest("exec-8"))
        assertEquals(EconomicGateDecision.ALLOWED, allowedAgain.decision)
        val newPeriodHolds = db.budgetHoldDao().forPeriod(
            (renewed as BudgetPolicyManagementService.ConfigureOutcome.NewPeriod).periodId
        )
        assertEquals(1, newPeriodHolds.size)
    }

    // ------------------------------------------------------------------
    // 9. Historical pricing resolution remains stable when a new price
    //    version is added.
    // ------------------------------------------------------------------

    @Test
    fun `historical pricing resolution stays stable when a new price version is added`() = runBlocking {
        // v1: input 1,000 micro/million from t=2,000.
        pricingManagement.registerUserConfiguredPricing(
            PricingManagementService.UserPricingRequest(
                providerId = "p9", serviceId = "s9", modelId = "m9",
                inputPriceMicroPerMillion = 1_000L, outputPriceMicroPerMillion = 2_000L,
                currency = "USD", effectiveFromEpochMs = 2_000L
            )
        )

        // Historical resolution inside v1's window: exactly v1's prices.
        val before = pricingManagement.resolveAt("p9", "s9", "m9", atEpochMs = 2_500L)
        assertNotNull(before)
        val inputBefore = before!!.components.single { it.componentCode == "INPUT_TOKENS" }
        assertEquals("1000", inputBefore.rateAmountPrecise)

        // v2: input 2,500 micro/million from t=5,000 (a materially changed,
        // verified price — appended, v1 untouched).
        pricingManagement.registerUserConfiguredPricing(
            PricingManagementService.UserPricingRequest(
                providerId = "p9", serviceId = "s9", modelId = "m9",
                inputPriceMicroPerMillion = 2_500L, outputPriceMicroPerMillion = 4_000L,
                currency = "USD", effectiveFromEpochMs = 5_000L
            )
        )

        // The HISTORICAL moment still resolves to v1 — already-recorded
        // transactions are never reinterpreted.
        val stillOld = pricingManagement.resolveAt("p9", "s9", "m9", atEpochMs = 2_500L)
        assertEquals("1000", stillOld!!.components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)
        // The NEW moment resolves to v2.
        val nowNew = pricingManagement.resolveAt("p9", "s9", "m9", atEpochMs = 6_000L)
        assertEquals("2500", nowNew!!.components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)

        // The engine's own resolution (MODEL > SERVICE > PROVIDER precedence,
        // max effectiveFrom within window) also sees v2 at the current tick.
        val engineResolution = governance.resolvePricing("p9", "s9", "m9")
        assertFalse(engineResolution.isUnknown)
        assertEquals(2_500L, engineResolution.resolvedEntry?.inputPricePerMillion?.amountMicro)
        assertEquals(PricingManagementService.PROVENANCE_USER_CONFIG, engineResolution.resolvedEntry?.provenance)
    }

    // ------------------------------------------------------------------
    // 10. Currency and effective-time-window behavior remain correct.
    // ------------------------------------------------------------------

    @Test
    fun `currency and effective window behavior remain correct`() = runBlocking {
        // EUR pricing with an explicit effective window [2,000, 8,000).
        pricingManagement.registerUserConfiguredPricing(
            PricingManagementService.UserPricingRequest(
                providerId = "p10", serviceId = "s10", modelId = "m10",
                inputPriceMicroPerMillion = 3_000L,
                currency = "EUR",
                effectiveFromEpochMs = 2_000L,
                effectiveUntilEpochMs = 8_000L
            )
        )

        // The entry is recorded in EUR — currency is explicit evidence,
        // never silently converted or assumed USD.
        val entries = pricingManagement.listPricing().filter { it.providerId == "p10" }
        assertEquals(1, entries.size)
        assertEquals("EUR", entries.single().inputPricePerMillion?.currency)

        // Inside the window: the catalog resolves the EUR price.
        val inside = pricingManagement.resolveAt("p10", "s10", "m10", atEpochMs = 3_000L)
        assertNotNull(inside)
        assertEquals("EUR", inside!!.components.single().currency)

        // After the window: honestly UNKNOWN — no price survives its
        // effective window, and none is fabricated.
        val after = pricingManagement.resolveAt("p10", "s10", "m10", atEpochMs = 9_000L)
        assertNull(after)

        // The ENGINE's resolution at real now (far past the window) is
        // UNKNOWN too — no cross-window leakage, no silent 1:1 conversion.
        assertTrue(governance.resolvePricing("p10", "s10", "m10").isUnknown)
    }

    // ------------------------------------------------------------------
    // 11. Repeated UI writes, retries and policy refreshes do not create
    //     duplicate policies, periods or pricing versions.
    // ------------------------------------------------------------------

    @Test
    fun `repeated writes and refreshes never duplicate policies periods or pricing versions`() = runBlocking {
        // Three identical configurations + three authority refreshes.
        val first = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 5_000_000L))
        assertTrue(first is BudgetPolicyManagementService.ConfigureOutcome.Created)
        repeat(3) {
            val repeated = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 5_000_000L))
            assertTrue(repeated is BudgetPolicyManagementService.ConfigureOutcome.Unchanged)
        }
        repeat(3) { policyManagement.workspaceAuthority("ws-1") }

        val created = first as BudgetPolicyManagementService.ConfigureOutcome.Created
        assertEquals(1, policyRowCount()) // ONE policy — refreshes/retries create nothing
        assertEquals(1, db.budgetPolicyDao().familyHistory(created.policyFamilyId).size)
        assertEquals(1, db.budgetPeriodDao().forPolicy(created.policyId).size)

        // An edit creates exactly ONE new version; repeating the same edit
        // is a verified no-op (no v3).
        val edited = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 6_000_000L))
        assertTrue(edited is BudgetPolicyManagementService.ConfigureOutcome.NewVersion)
        val reedited = policyManagement.configureWorkspaceBudget(configureRequest(limitMicroUnits = 6_000_000L))
        assertTrue(reedited is BudgetPolicyManagementService.ConfigureOutcome.Unchanged)
        assertEquals(2, db.budgetPolicyDao().familyHistory(created.policyFamilyId).size)

        // Pricing: three identical saves → ONE entry, ONE catalog version.
        repeat(3) {
            pricingManagement.registerUserConfiguredPricing(
                PricingManagementService.UserPricingRequest(
                    providerId = "p11", serviceId = "s11", modelId = "m11",
                    inputPriceMicroPerMillion = 1_500L, outputPriceMicroPerMillion = 3_000L,
                    currency = "USD", effectiveFromEpochMs = tick
                )
            )
        }
        assertEquals(1, economicStore.allPricing().count { it.providerId == "p11" })
        val profileId = PricingCatalogService.Ids.profileId("p11", PricingCatalogService.Ids.profileCode("MODEL", "m11", "s11"))
        assertEquals(1, db.pricingProfileVersionDao().forProfile(profileId).size)
    }

    // ------------------------------------------------------------------
    // 12. An empty modern ledger is represented honestly and is not
    //     confused with an unlimited or zero-cost budget.
    // ------------------------------------------------------------------

    @Test
    fun `an empty modern ledger is honest - never unlimited and never zero-cost`() = runBlocking {
        // Fresh workspace: NO authority at all — an honest absence, not an
        // implicit budget of any size.
        val empty = policyManagement.workspaceAuthority("ws-fresh")
        assertEquals(
            BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.NONE,
            empty.kind
        )
        assertNull(empty.modernPolicy)
        assertNull(empty.legacyAllocation)

        // The dashboard projection is honestly empty (no fabricated zeros).
        assertTrue(dashboard.periodBalances(tick).first().isEmpty())

        // No policy + no allocation → authorization ALLOWED with honest
        // no-authority semantics (cost tracked, nothing denied, no holds).
        seedPricing()
        val verdict = governance.authorize(decideGateRequest("exec-9", workspaceId = "ws-fresh"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        assertEquals(0, holdRowCount())
        assertEquals(0, consumptionEntryCount())

        // A ZERO-limit policy is a REAL ceiling (monitoring), NOT "unlimited"
        // and NOT "no authority": the kinds stay distinct and enforcement
        // treats available=0 as a hard stop.
        policyManagement.configureWorkspaceBudget(
            configureRequest(workspaceId = "ws-fresh", limitMicroUnits = 0L)
        )
        val zero = policyManagement.workspaceAuthority("ws-fresh")
        assertEquals(
            BudgetPolicyManagementService.WorkspaceBudgetAuthority.AuthorityKind.MODERN_ACCOUNTING,
            zero.kind
        )
        assertEquals(0L, zero.modernBalance?.availableMicroUnits)
        assertEquals(0L, zero.modernBalance?.limitMicroUnits)
        val deniedAtZero = governance.authorize(decideGateRequest("exec-10", workspaceId = "ws-fresh"))
        assertEquals(EconomicGateDecision.DENIED, deniedAtZero.decision)
    }
}
