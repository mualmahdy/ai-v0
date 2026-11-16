package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * STAGE 1A — Economic hold-lifecycle acceptance (production-path identities)
 * ============================================================================
 * Root causes this suite locks closed (all proven at baseline 2eec12d):
 *
 *  R1 IDENTITY MISMATCH — the decide-time gate reserved under
 *     `exec:pre_{taskId}_{step}` while settlement looked holds up by
 *     `exec:{executionId}`: two production grammars that never matched, so
 *     the reservation was NEVER settled, released or expired.
 *  R2 UNBOUNDED RESERVATION — the gate wrote expires_at = NULL and the
 *     expiry sweep had zero production callers: availability stayed pinned
 *     forever.
 *  R3 LIFECYCLE BYPASS — the orchestrator only invoked accounting when
 *     `attribution != null && attribution.totalTokens > 0`, silently
 *     skipping the lifecycle decision for unknown and zero outcomes.
 *  R4 ZERO = UNKNOWN CONFLATION — settlement treated a KNOWN zero actual
 *     cost as "keep the hold" (NoAction) — a positive reservation stayed
 *     active although the operation conclusively cost nothing.
 *
 * The suite drives the REAL production stack on a REAL (in-memory) Room
 * database with the production DB triggers: EconomicGovernanceService →
 * HoldBasedBudgetGate → BudgetLedgerService for authorization, and
 * EconomicGovernanceService.accountUsageSuspend → HoldSettlementService for
 * the post-execution lifecycle — with the canonical
 * (executionId, stepIndex, attempt) identity the orchestrator now plumbs.
 * Assertions are on STORED STATE (hold rows, settlement rows, ledger
 * entries, idempotency keys, computed availability), never on returned
 * message strings alone.
 */
@RunWith(RobolectricTestRunner::class)
class Stage1AHoldLifecycleAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var gate: HoldBasedBudgetGate
    private lateinit var settlement: HoldSettlementService
    private lateinit var governance: EconomicGovernanceService
    private lateinit var pricing: FakePricingRepository

    /** Fake monotonic clock — every service sees the SAME tick. */
    private var tick = 1_000L
    /** Id sequence — deliberately DECOUPLED from the clock so expiry math is exact. */
    private var idSeq = 0L
    private val windowStart = 0L
    /** Extends far beyond the TTL horizon so expiry tests stay in-period. */
    private val windowEnd = 400_000_000L
    private val limitMicro = 1_000_000L

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
        pricing = FakePricingRepository()
        governance = EconomicGovernanceService(
            pricingRepository = pricing,
            costLedger = FakeCostLedger(),
            allocationRepository = FakeBudgetAllocationRepository(),
            rateLimitGovernor = RateLimitGovernor(),
            telemetry = null,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            holdBasedBudgetCore = gate,
            holdSettlement = settlement
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Fixture helpers — the production request/input constructions
    // ------------------------------------------------------------------

    private suspend fun createActiveBudget(
        limitMicroUnits: Long = limitMicro,
        overagePolicy: String = BudgetLedgerService.OVERAGE_HARD_LIMIT,
        workspaceId: String = "ws-1"
    ): BudgetLedgerService.CreatedBudget =
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = workspaceId,
                providerId = null, budgetName = "ws budget", currency = "USD",
                limitMicroUnits = limitMicroUnits,
                periodStartEpochMs = windowStart, periodEndEpochMs = windowEnd,
                overagePolicy = overagePolicy
            )
        )

    /** MODEL-scope pricing: 1500 in / 3000 out micro per million tokens. */
    private suspend fun seedPricing() {
        pricing.upsertPricing(
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
     * The DECIDE-GATE request construction (DecisionService.applyEconomicGate):
     * expectedTotalTokens = 1,000,000 → 3:1 split → estimate
     * 750k×1500/1M + 250k×3000/1M = 1,875 micro.
     */
    private fun decideGateRequest(
        executionId: String,
        stepIndex: Int?,
        attempt: Int?,
        workspaceId: String? = "ws-1",
        providerId: String? = "prov",
        serviceId: String? = "svc-1",
        modelId: String? = "m1",
        actionTypeCode: String = "EXECUTE_STEP"
    ) = EconomicAuthorizationRequest(
        executionId = executionId,
        workspaceId = workspaceId,
        agentId = "agent-1",
        taskId = "task-1",
        providerId = providerId,
        serviceId = serviceId,
        modelId = modelId,
        resourceId = "res-1",
        isLocalResource = false,
        expectedTotalTokens = 1_000_000,
        actionTypeCode = actionTypeCode,
        stepIndex = stepIndex,
        attempt = attempt
    )

    /**
     * The PRE-DISPATCH LLM-GATE request construction
     * (ExecutionService.executeLlmStep): same identity, real provider
     * attribution, actionTypeCode "LLM_STEP".
     */
    private fun llmGateRequest(executionId: String, stepIndex: Int?, attempt: Int?) =
        decideGateRequest(executionId, stepIndex, attempt, actionTypeCode = "LLM_STEP")

    /** The orchestrator's post-step accounting input (Stage 1A fields). */
    private fun stepAccounting(
        executionId: String,
        stepIndex: Int?,
        attempt: Int?,
        inputTokens: Int,
        outputTokens: Int,
        dispatchState: UsageDispatchState,
        hasUsageReport: Boolean,
        workspaceId: String? = "ws-1"
    ) = UsageAccountingInput(
        executionId = executionId,
        taskId = "task-1",
        workspaceId = workspaceId,
        agentId = "agent-1",
        providerId = if (hasUsageReport) "prov" else null,
        serviceId = if (hasUsageReport) "svc-1" else null,
        modelId = if (hasUsageReport) "m1" else null,
        resourceId = if (hasUsageReport) "res-1" else null,
        usage = TokenUsageRecord(inputTokens = inputTokens, outputTokens = outputTokens, cachedTokens = 0),
        isActualProviderReport = hasUsageReport,
        stepIndex = stepIndex,
        attempt = attempt,
        dispatchState = dispatchState,
        hasUsageReport = hasUsageReport,
        timestampEpochMs = tick
    )

    private fun stepReason(executionId: String, stepIndex: Int?, attempt: Int?) =
        HoldKeys.holdReason(executionId, stepIndex, attempt)

    private suspend fun holdsFor(executionId: String, stepIndex: Int? = null, attempt: Int? = null) =
        db.budgetHoldDao().allForReason(stepReason(executionId, stepIndex, attempt))

    private suspend fun consumptionEntryCount(): Int =
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM budget_ledger_entry WHERE entry_type='CONSUMPTION'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    private suspend fun holdRowCount(): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM budget_hold")
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private suspend fun available(periodId: String) =
        ledger.availableForPeriod(periodId).availableMicroUnits

    // ------------------------------------------------------------------
    // 1. The full known-price lifecycle: authorize → reserve → dispatch →
    //    account actual usage → settle → release the remainder.
    // ------------------------------------------------------------------

    @Test
    fun `known-price step lifecycle - bounded reservation, settle, release, exact balance`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()

        // ---- authorize (the decide gate, canonical identity) ----
        val verdict = governance.authorize(decideGateRequest("exec-lc", stepIndex = 0, attempt = 1))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)

        val holds = holdsFor("exec-lc", 0, 1)
        assertEquals("exactly one reservation for the step", 1, holds.size)
        val hold = holds.first()
        // The stored identity is the canonical per-step grammar.
        assertEquals("exec:exec-lc:step:0:attempt:1", hold.reason)
        assertEquals("auth:exec-lc:step:0:attempt:1:WORKSPACE:ws-1", hold.idempotencyKey)
        assertEquals(BudgetLedgerService.HOLD_ACTIVE, hold.status)
        // Stage 1A: the reservation is BOUNDED — expires at now + TTL.
        assertEquals(tick + HoldKeys.DEFAULT_HOLD_TTL_MS, hold.expiresAtEpochMs)
        // Estimate: 1,875 micro reserved → availability suppressed by exactly it.
        assertEquals(1_875L, hold.holdAmountMicroUnits)
        assertEquals(limitMicro - 1_875L, available(budget.periodId))

        // ---- dispatch + account the actual (400k in × 1500 + 100k out × 3000 = 900) ----
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-lc", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        assertNotNull("the usage report is recorded in the cost ledger", record)
        assertEquals(900L, record!!.cost!!.amountMicro)

        // ---- stored state: settled consumption + released remainder ----
        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_RELEASED, holdAfter.status)
        val settlements = db.budgetHoldSettlementDao().forHold(hold.holdId)
        assertEquals(2, settlements.size) // one SETTLE + one RELEASE
        val settle = settlements.first { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }
        assertEquals(900L, settle.amountMicroUnits)
        assertEquals(HoldKeys.settleKey("exec-lc", 0, 1), settle.idempotencyKey)
        val release = settlements.first { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        assertEquals(1_875L - 900L, release.amountMicroUnits)
        assertEquals(HoldKeys.releaseKey("exec-lc", 0, 1), release.idempotencyKey)

        // Exactly one consumption entry of the ACTUAL cost; availability = limit − actual.
        assertEquals(1, consumptionEntryCount())
        assertEquals(limitMicro - 900L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 2. The identity-mismatch regression lock (R1): the decide gate and
    //    the pre-dispatch LLM gate derive the SAME reservation; accounting
    //    handles the reservation authorization created.
    // ------------------------------------------------------------------

    @Test
    fun `identity parity - decide gate and LLM gate share ONE reservation, accounting settles it (C5 lock)`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()

        // The decide-time gate runs first (inside DecisionService.evaluate).
        val decideVerdict = governance.authorize(decideGateRequest("exec-par", stepIndex = 2, attempt = 1))
        assertEquals(EconomicGateDecision.ALLOWED, decideVerdict.decision)
        assertFalse(decideVerdict.reason.contains("[replay]"))

        // The pre-dispatch LLM gate authorizes the SAME billable operation.
        val llmVerdict = governance.authorize(llmGateRequest("exec-par", stepIndex = 2, attempt = 1))
        assertEquals(EconomicGateDecision.ALLOWED, llmVerdict.decision)
        assertTrue("the second gate must REPLAY, never duplicate", llmVerdict.reason.contains("[replay]"))

        // ONE hold — the reservation the decide gate created is the one that exists.
        val holds = holdsFor("exec-par", 2, 1)
        assertEquals(1, holds.size)
        assertEquals(1, holdRowCount())
        val holdId = holds.first().holdId

        // The orchestrator's accounting for that step settles EXACTLY that hold.
        governance.accountUsageSuspend(
            stepAccounting("exec-par", 2, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        val holdAfter = db.budgetHoldDao().byId(holdId)!!
        assertTrue(
            "the authorization-created reservation reached a terminal state",
            holdAfter.status == BudgetLedgerService.HOLD_SETTLED ||
                holdAfter.status == BudgetLedgerService.HOLD_RELEASED
        )
        assertEquals(1, consumptionEntryCount())
        assertEquals(limitMicro - 900L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 3. Multiple billable steps in one execution — no reservation-key
    //    collisions; every step's actual lands in the ledger.
    // ------------------------------------------------------------------

    @Test
    fun `multi-step execution - per-step reservations never collide, every actual lands`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()

        for (step in 0..2) {
            val verdict = governance.authorize(decideGateRequest("exec-multi", stepIndex = step, attempt = 1))
            assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
            // Three DISTINCT reservations (no key collisions).
            assertEquals(step + 1, holdRowCount())
            val record = governance.accountUsageSuspend(
                stepAccounting("exec-multi", step, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
            )
            assertNotNull(record)
        }

        // Three consumption entries; availability reflects the SUM of actuals.
        assertEquals(3, consumptionEntryCount())
        assertEquals(limitMicro - 3 * 900L, available(budget.periodId))
        // Every step's hold is terminal and carries its own identity.
        for (step in 0..2) {
            val hold = holdsFor("exec-multi", step, 1).single()
            assertEquals(BudgetLedgerService.HOLD_RELEASED, hold.status)
            assertEquals(stepReason("exec-multi", step, 1), hold.reason)
        }
    }

    // ------------------------------------------------------------------
    // 4. Replaying the SAME operation: one hold, one posting — idempotent.
    // ------------------------------------------------------------------

    @Test
    fun `replay of the same operation - no duplicate holds, no double posting`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()

        val first = governance.authorize(decideGateRequest("exec-replay", stepIndex = 0, attempt = 1))
        val second = governance.authorize(decideGateRequest("exec-replay", stepIndex = 0, attempt = 1))
        assertEquals(EconomicGateDecision.ALLOWED, first.decision)
        assertEquals(EconomicGateDecision.ALLOWED, second.decision)
        assertTrue(second.reason.contains("[replay]"))
        assertEquals(1, holdRowCount())

        // Account the step twice (retry / late duplicate accounting call).
        governance.accountUsageSuspend(
            stepAccounting("exec-replay", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        governance.accountUsageSuspend(
            stepAccounting("exec-replay", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )

        assertEquals("exactly one consumption entry for one actual", 1, consumptionEntryCount())
        assertEquals(limitMicro - 900L, available(budget.periodId))
        val hold = holdsFor("exec-replay", 0, 1).single()
        assertEquals(BudgetLedgerService.HOLD_RELEASED, hold.status)
    }

    // ------------------------------------------------------------------
    // 5. A DISTINCT retry attempt (can incur a separate charge) receives
    //    its own reservation — never mistaken for a replay.
    // ------------------------------------------------------------------

    @Test
    fun `distinct retry attempt gets a distinct reservation and settles independently`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()

        // Attempt 1 (pre-crash) authorizes + dispatches + is accounted.
        governance.authorize(decideGateRequest("exec-retry", stepIndex = 3, attempt = 1))
        governance.accountUsageSuspend(
            stepAccounting("exec-retry", 3, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        // The resumed execution (attempt + 1) re-authorizes the same step.
        val resumeVerdict = governance.authorize(decideGateRequest("exec-retry", stepIndex = 3, attempt = 2))
        assertEquals(EconomicGateDecision.ALLOWED, resumeVerdict.decision)
        assertFalse("a fresh attempt is a NEW billable attempt, not a replay", resumeVerdict.reason.contains("[replay]"))

        // TWO holds under different identities; the attempt-2 hold is fresh.
        assertEquals(1, holdsFor("exec-retry", 3, 1).size)
        assertEquals(1, holdsFor("exec-retry", 3, 2).size)
        val secondHold = holdsFor("exec-retry", 3, 2).single()
        assertEquals(BudgetLedgerService.HOLD_ACTIVE, secondHold.status)

        // The re-dispatch incurs its own charge — settles independently.
        governance.accountUsageSuspend(
            stepAccounting("exec-retry", 3, 2, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        assertEquals("both attempts' actuals reached the ledger", 2, consumptionEntryCount())
        assertEquals(limitMicro - 2 * 900L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 6. Cancellation PROVEN before dispatch: the reservation is released
    //    idempotently; nothing is fabricated.
    // ------------------------------------------------------------------

    @Test
    fun `cancel proven before dispatch - reservation released, no ledger fabrication`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        governance.authorize(decideGateRequest("exec-cancel", stepIndex = 0, attempt = 1))
        val hold = holdsFor("exec-cancel", 0, 1).single()
        assertEquals(BudgetLedgerService.HOLD_ACTIVE, hold.status)
        assertEquals(limitMicro - 1_875L, available(budget.periodId))

        // The cancellation path: NOT_DISPATCHED, no usage report.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-cancel", 0, 1, 0, 0, UsageDispatchState.NOT_DISPATCHED, hasUsageReport = false)
        )
        assertNull("no cost-ledger record is fabricated for an unsent step", record)

        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_RELEASED, holdAfter.status)
        val releases = db.budgetHoldSettlementDao().forHold(hold.holdId)
            .filter { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        assertEquals(1, releases.size)
        assertEquals(HoldKeys.releaseKey("exec-cancel", 0, 1), releases.first().idempotencyKey)
        // Availability fully recovered; ZERO ledger entries exist.
        assertEquals(0, consumptionEntryCount())
        assertEquals(limitMicro, available(budget.periodId))

        // Replaying the release is a no-op (idempotent) — re-query to prove
        // no second RELEASE settlement and no entry appeared.
        governance.accountUsageSuspend(
            stepAccounting("exec-cancel", 0, 1, 0, 0, UsageDispatchState.NOT_DISPATCHED, hasUsageReport = false)
        )
        assertEquals(
            1,
            db.budgetHoldSettlementDao().forHold(hold.holdId)
                .count { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
        assertEquals(0, consumptionEntryCount())
        assertEquals(limitMicro, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 7. Cancellation/failure AFTER dispatch with UNKNOWN actual cost:
    //    the reservation is KEPT (I-10) — UNKNOWN is never zero.
    // ------------------------------------------------------------------

    @Test
    fun `cancel or failure after dispatch with UNKNOWN cost - reservation kept, nothing fabricated`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        governance.authorize(decideGateRequest("exec-unknown", stepIndex = 0, attempt = 1))
        val hold = holdsFor("exec-unknown", 0, 1).single()

        // Dispatched (the provider call was entered) but NO usable usage
        // report arrived — a missing report is NOT evidence of zero cost.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-unknown", 0, 1, 0, 0, UsageDispatchState.DISPATCHED, hasUsageReport = false)
        )
        assertNull(record)

        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals("conservative semantics — the reservation survives", BudgetLedgerService.HOLD_ACTIVE, holdAfter.status)
        assertEquals(0, consumptionEntryCount())
        assertEquals("availability stays protected until the bounded expiry", limitMicro - 1_875L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 8. KNOWN zero actual cost: a completed call that consumed nothing
    //    releases the reservation (B) — never left active.
    // ------------------------------------------------------------------

    @Test
    fun `conclusively known zero actual cost - reservation released`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        governance.authorize(decideGateRequest("exec-zero", stepIndex = 0, attempt = 1))
        val hold = holdsFor("exec-zero", 0, 1).single()

        // The provider call completed and its usage report shows ZERO tokens
        // with pricing resolved → cost is CONCLUSIVELY zero.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-zero", 0, 1, 0, 0, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        assertNotNull("the zero usage report IS recorded (honest evidence)", record)
        assertEquals(0L, record!!.cost!!.amountMicro)

        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_RELEASED, holdAfter.status)
        assertEquals(0, consumptionEntryCount())
        assertEquals(limitMicro, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 9. Expiry enforcement WITHOUT a restart or cleanup run: the hold
    //    stops suppressing availability through the lazy balance query.
    // ------------------------------------------------------------------

    @Test
    fun `expired reservation stops suppressing availability lazily - no restart, no sweep`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        governance.authorize(decideGateRequest("exec-ttl", stepIndex = 0, attempt = 1))
        val hold = holdsFor("exec-ttl", 0, 1).single()
        assertEquals(limitMicro - 1_875L, available(budget.periodId))

        // Time passes beyond the reservation's bounded lifetime.
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1

        // LAZY enforcement: availability recovers purely via the balance
        // query's expiry filter — no sweep ran (the hold is still ACTIVE).
        assertEquals(limitMicro, available(budget.periodId))
        assertEquals(
            "no sweep/bookkeeping ran — this is the lazy filter alone",
            BudgetLedgerService.HOLD_ACTIVE,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )

        // A subsequent billable dispatch authorizes FRESH (new step identity)
        // and sees the recovered availability.
        val next = governance.authorize(decideGateRequest("exec-ttl", stepIndex = 1, attempt = 1))
        assertEquals(EconomicGateDecision.ALLOWED, next.decision)
        assertEquals(1, holdsFor("exec-ttl", 1, 1).size)
        assertEquals(limitMicro - 1_875L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 10. A hold that expired while active + a LATE actual-cost report:
    //     real known consumption is recorded exactly once — never
    //     discarded because the reservation expired.
    // ------------------------------------------------------------------

    @Test
    fun `late actual-cost report after expiry is recorded exactly once, idempotently`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        governance.authorize(decideGateRequest("exec-late", stepIndex = 0, attempt = 1))
        val hold = holdsFor("exec-late", 0, 1).single()

        // The reservation expires while the operation is in flight; the
        // bookkeeping sweep marks it EXPIRED.
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        ledger.expireHolds()
        assertEquals(BudgetLedgerService.HOLD_EXPIRED, db.budgetHoldDao().byId(hold.holdId)!!.status)

        // The provider's usage report finally arrives.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-late", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        assertNotNull(record)

        // The actual is POSTED (without the expired hold) — recorded once.
        assertEquals(1, consumptionEntryCount())
        assertEquals(limitMicro - 900L, available(budget.periodId))

        // A retried/duplicated late report never double-posts.
        governance.accountUsageSuspend(
            stepAccounting("exec-late", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )
        assertEquals(1, consumptionEntryCount())
        assertEquals(limitMicro - 900L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 11. Concurrent / replayed settlements, releases and reservations:
    //     exactly-once posting under parallelism.
    // ------------------------------------------------------------------

    @Test
    fun `concurrent settlements releases and reservations post exactly once`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()

        // (a) Concurrent authorize of the SAME operation → one hold.
        val verdicts = (1..8).map {
            async(Dispatchers.IO) { governance.authorize(decideGateRequest("exec-conc", stepIndex = 0, attempt = 1)) }
        }.awaitAll()
        assertTrue(verdicts.all { it.decision == EconomicGateDecision.ALLOWED })
        assertEquals("parallel authorization of one operation yields ONE hold", 1, holdRowCount())

        // (b) Concurrent settlement of the SAME actual → one posting.
        (1..8).map {
            async(Dispatchers.IO) {
                governance.accountUsageSuspend(
                    stepAccounting("exec-conc", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
                )
            }
        }.awaitAll()
        assertEquals(1, consumptionEntryCount())
        val hold = holdsFor("exec-conc", 0, 1).single()
        assertTrue(
            hold.status == BudgetLedgerService.HOLD_SETTLED || hold.status == BudgetLedgerService.HOLD_RELEASED
        )
        assertEquals(limitMicro - 900L, available(budget.periodId))

        // (c) Concurrent undispatched releases of a fresh step → one release.
        governance.authorize(decideGateRequest("exec-conc", stepIndex = 1, attempt = 1))
        (1..8).map {
            async(Dispatchers.IO) { governance.releaseUndispatchedStepHold(stepAccounting("exec-conc", 1, 1, 0, 0, UsageDispatchState.UNKNOWN, hasUsageReport = false)) }
        }.awaitAll()
        val hold1 = holdsFor("exec-conc", 1, 1).single()
        assertEquals(BudgetLedgerService.HOLD_RELEASED, hold1.status)
        assertEquals(
            "the parallel release replays — exactly one RELEASE settlement",
            1,
            db.budgetHoldSettlementDao().forHold(hold1.holdId)
                .count { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
        assertEquals(1, consumptionEntryCount()) // still one posting overall
        assertEquals(limitMicro - 900L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 12. Actual cost EXCEEDING the reserved estimate: full hold settled +
    //     excess posted (I-11) — actuals never truncated.
    // ------------------------------------------------------------------

    @Test
    fun `actual above hold - full settle plus overage posting, never truncated`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        governance.authorize(decideGateRequest("exec-over", stepIndex = 0, attempt = 1))
        val hold = holdsFor("exec-over", 0, 1).single()
        assertEquals(1_875L, hold.holdAmountMicroUnits)

        // 900k in × 1500 + 900k out × 3000 = 4,050 micro — far above the hold.
        governance.accountUsageSuspend(
            stepAccounting("exec-over", 0, 1, 900_000, 900_000, UsageDispatchState.DISPATCHED, hasUsageReport = true)
        )

        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(BudgetLedgerService.HOLD_SETTLED, holdAfter.status)
        val entries = db.budgetLedgerEntryDao().forPeriod(budget.periodId)
            .filter { it.entryType == BudgetLedgerService.ENTRY_CONSUMPTION }
        // The FULL actual reached the ledger: 1,875 via the hold + 2,175 overage.
        assertEquals(4_050L, -entries.sumOf { it.availableEffectMicroUnits })
        assertEquals(limitMicro - 4_050L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 13. Legacy workspace (NO active accounting policy): the legacy path
    //     runs verbatim — no holds, no budget-core entries, honest no-op.
    // ------------------------------------------------------------------

    @Test
    fun `legacy workspace without an active policy - no holds, legacy verdict, honest no-op`() = runBlocking {
        seedPricing() // pricing exists; no budget/policy rows do

        val verdict = governance.authorize(decideGateRequest("exec-legacy", stepIndex = 0, attempt = 1, workspaceId = "ws-none"))
        assertEquals(EconomicGateDecision.ALLOWED, verdict.decision)
        assertFalse("the legacy path ran, not the hold gate", verdict.reason.contains("HOLD-BASED BUDGET"))
        assertEquals("no reservation is ever created without an active policy", 0, holdRowCount())

        // Accounting: the usage report still lands in the legacy cost ledger,
        // but the budget core records NOTHING.
        val record = governance.accountUsageSuspend(
            stepAccounting("exec-legacy", 0, 1, 400_000, 100_000, UsageDispatchState.DISPATCHED, hasUsageReport = true, workspaceId = "ws-none")
        )
        assertNotNull(record)
        assertEquals("no budget-core consumption entries", 0, consumptionEntryCount())
        assertEquals(0, holdRowCount())

        // The undispatched release on a policy-less workspace is an honest no-op.
        governance.releaseUndispatchedStepHold(
            stepAccounting("exec-legacy", 1, 1, 0, 0, UsageDispatchState.UNKNOWN, hasUsageReport = false, workspaceId = "ws-none")
        )
        assertEquals(0, holdRowCount())
    }

    // ------------------------------------------------------------------
    // 14. Pre-existing persisted holds with the LEGACY per-execution
    //     grammar remain reachable by legacy-identity settlement.
    // ------------------------------------------------------------------

    @Test
    fun `pre-existing legacy-grammar holds still settle through the legacy identity`() = runBlocking {
        val budget = createActiveBudget()
        seedPricing()
        // A hold exactly as the PRE-fix gate wrote it: reason "exec:{id}",
        // per-execution auth key, NO expiry (pre-fix writes NULL).
        val legacy = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1", amountMicroUnits = 300_000L,
            reason = "exec:legacy-exec-1", idempotencyKey = "auth:legacy-exec-1:WORKSPACE:ws-1",
            expiresAtEpochMs = null
        ) as BudgetLedgerService.HoldReservation.Reserved
        assertEquals(limitMicro - 300_000L, available(budget.periodId))

        // A legacy caller (no step identity) settles through the SAME grammar.
        val summary = settlement.settleExecutionUsage(
            UsageAccountingInput(
                executionId = "legacy-exec-1", taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov", serviceId = "svc-1", modelId = "m1", resourceId = null,
                usage = TokenUsageRecord(inputTokens = 400_000, outputTokens = 100_000, cachedTokens = 0),
                isActualProviderReport = true,
                timestampEpochMs = tick
            ),
            actualCostMicroUnits = 120_000L,
            costCurrency = "USD"
        )
        assertTrue("legacy-grammar settlement still works: $summary", summary is HoldSettlementService.SettlementSummary.SettledAndReleased)
        assertEquals(BudgetLedgerService.HOLD_RELEASED, db.budgetHoldDao().byId(legacy.holdId)!!.status)
        assertEquals(limitMicro - 120_000L, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 15. The bootstrap sweep bounds pre-fix UNBOUNDED holds — the
    //     lifetime stamp lets the lazy filter reclaim them; nothing is
    //     deleted and the sweep is idempotent.
    // ------------------------------------------------------------------

    @Test
    fun `bootstrap sweep bounds pre-fix unbounded holds without deleting anything`() = runBlocking {
        val budget = createActiveBudget()
        // A pre-fix hold: ACTIVE, NULL expiry — pins availability forever.
        val unbounded = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1", amountMicroUnits = 250_000L,
            reason = "exec:pre-crash-exec", idempotencyKey = "auth:pre-crash-exec:WORKSPACE:ws-1",
            expiresAtEpochMs = null
        ) as BudgetLedgerService.HoldReservation.Reserved
        val created_at = tick // the ledger clock at insert

        // Even far in the future, a NULL-expiry hold never drops out lazily.
        tick = created_at + HoldKeys.DEFAULT_HOLD_TTL_MS * 3
        assertEquals("unbounded hold pins availability past any horizon", limitMicro - 250_000L, available(budget.periodId))

        // The Stage 1A bootstrap sweep: bound + expire.
        val stamped = ledger.boundLegacyHolds()
        assertEquals(1, stamped)
        assertEquals("the lazy filter reclaims it WITHOUT the sweep running", limitMicro, available(budget.periodId))
        assertEquals(1, ledger.expireHolds())
        assertEquals(BudgetLedgerService.HOLD_EXPIRED, db.budgetHoldDao().byId(unbounded.holdId)!!.status)
        assertEquals("the row still exists — nothing was deleted", 1, holdRowCount())

        // Idempotent: a second sweep stamps/marks nothing.
        assertEquals(0, ledger.boundLegacyHolds())
        assertEquals(0, ledger.expireHolds())
        assertEquals(limitMicro, available(budget.periodId))
    }

    // ------------------------------------------------------------------
    // 16. Preserved governance semantics: UNKNOWN estimate (I-3) allows
    //     WITHOUT a hold; soft limit (I-7) warns WITHOUT a hold.
    // ------------------------------------------------------------------

    @Test
    fun `unknown estimate allows without hold and soft limit warns without hold - I-3 and I-7 preserved`() = runBlocking {
        // I-3: an active policy but NO pricing for the provider → UNKNOWN
        // estimate → honest ALLOWED, NO reservation (unknown is never zero).
        val budget = createActiveBudget()
        val unknownEstimate = governance.authorize(
            decideGateRequest("exec-i3", stepIndex = 0, attempt = 1, providerId = "prov-unpriced", serviceId = null, modelId = null)
        )
        assertEquals(EconomicGateDecision.ALLOWED, unknownEstimate.decision)
        assertEquals(0, holdRowCount())

        // I-7: a soft-limit policy that cannot cover the estimate → WARNED,
        // still NO hold (the holds-never-exceed-limit invariant is absolute).
        // (Its OWN workspace scope — createBudget mints a NEW policy family,
        // so two ACTIVE policies on one scope would be ambiguous.)
        val soft = createActiveBudget(
            limitMicroUnits = 100L,
            overagePolicy = BudgetLedgerService.OVERAGE_SOFT_LIMIT,
            workspaceId = "ws-soft"
        )
        seedPricing()
        val warned = governance.authorize(decideGateRequest("exec-i7", stepIndex = 0, attempt = 1, workspaceId = "ws-soft"))
        assertEquals(EconomicGateDecision.WARNED, warned.decision)
        assertEquals("a soft-limit breach proceeds WITHOUT a reservation", 0, holdRowCount())
        assertEquals(100L, available(soft.periodId))
    }
}

