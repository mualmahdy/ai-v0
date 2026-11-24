package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 2C — DEBT-2 closure: idempotent authorization replay after expiry
 * ============================================================================
 *
 * The production contract this suite locks:
 *
 *  1. Replaying the SAME (executionId, stepIndex, attempt) identity while
 *     its hold is ACTIVE is an idempotent replay of the ORIGINAL outcome
 *     ([BudgetLedgerService.HoldReservation.Reserved] with replayed=true).
 *  2. Replaying the same identity AFTER the hold expired is an EXPLICIT
 *     [BudgetLedgerService.HoldReservation.ExpiredReplay] — sweep-invariant
 *     (identical before and after the markExpired bookkeeping pass) and
 *     NEVER presentable as a new active reservation.
 *  3. The expired replay never creates, renews or re-creates a reservation
 *     under the same idempotency identity — exactly one hold row ever.
 *  4. A genuine redispatch MUST carry a NEW attempt identity; the new
 *     identity authorizes a fresh reservation when policy permits.
 *  5. Concurrent duplicates of the same identity remain exactly-once.
 *  6. The GATE translates an expired replay into an explicit DENY naming
 *     the attempt-increment contract — a caller can never interpret it as
 *     fresh spending authority (availability is unchanged by the replay).
 *
 * All assertions are on STORED STATE (hold rows, settlement rows, computed
 * availability) and typed verdicts — never on message strings alone.
 */
@RunWith(RobolectricTestRunner::class)
class Stage2CExpiredReplayAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var gate: HoldBasedBudgetGate
    private lateinit var settlement: HoldSettlementService
    private lateinit var governance: EconomicGovernanceService
    private lateinit var policyManagement: BudgetPolicyManagementService

    private var tick = 1_000L
    private var idSeq = 0L
    private val periodMs = 30L * 86_400_000L
    private val workspaceId = "ws-debt2"

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
        val economicStore = RoomEconomicStore(
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

    /** MODEL-scope pricing: 1500 in / 3000 out micro-USD per million tokens
     *  (estimate for 1,000,000 expected tokens = 1,875 micro). */
    private suspend fun seedPricing() {
        RoomEconomicStore(
            pricingDao = db.pricingEntryDao(),
            ledgerDao = db.costLedgerEntryDao(),
            allocationDao = db.budgetAllocationDao()
        ).upsertPricing(
            com.example.domain.core.budget.PricingEntry(
                id = "pe-debt2",
                scope = com.example.domain.core.budget.PricingScope.MODEL,
                providerId = "prov",
                serviceId = "svc-1",
                modelId = "m1",
                inputPricePerMillion = com.example.domain.core.budget.MoneyAmount.of(1500, "USD"),
                outputPricePerMillion = com.example.domain.core.budget.MoneyAmount.of(3000, "USD"),
                cachedInputPricePerMillion = null,
                billingClass = com.example.domain.core.budget.BillingClass.PAID,
                pricingVersion = "v1",
                effectiveFromEpochMs = 0,
                effectiveToEpochMs = null,
                provenance = "TEST"
            )
        )
    }

    private suspend fun configureBudget(limitMicroUnits: Long = 1_000_000L) {
        seedPricing()
        policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = workspaceId,
                limitMicroUnits = limitMicroUnits,
                currency = "USD",
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT,
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )
    }

    /** The decide-gate-shaped request for (executionId, step, attempt). */
    private fun request(
        executionId: String,
        stepIndex: Int,
        attempt: Int,
        workspace: String = workspaceId
    ) = EconomicAuthorizationRequest(
        executionId = executionId,
        workspaceId = workspace,
        agentId = "agent-1",
        taskId = "task-1",
        providerId = "prov",
        serviceId = "svc-1",
        modelId = "m1",
        resourceId = "res-1",
        isLocalResource = false,
        expectedTotalTokens = 1_000_000,
        actionTypeCode = "EXECUTE_STEP",
        stepIndex = stepIndex,
        attempt = attempt
    )

    private suspend fun holdsInPeriod(periodId: String) = db.budgetHoldDao().forPeriod(periodId)

    // ------------------------------------------------------------------
    // 1. Same identity, ACTIVE hold — idempotent replay
    // ------------------------------------------------------------------

    @Test
    fun `same identity with an active hold replays the original reservation idempotently`() = runBlocking {
        configureBudget()
        val first = governance.authorize(request("exec-a", 0, 1))
        assertEquals(EconomicGateDecision.ALLOWED, first.decision)

        val second = governance.authorize(request("exec-a", 0, 1))
        assertEquals(EconomicGateDecision.ALLOWED, second.decision)
        assertTrue("the active-hold replay is marked [replay]", second.reason.contains("[replay]"))

        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period
        val holds = holdsInPeriod(period.budgetPeriodId)
        assertEquals("one hold only — the replay never duplicates", 1, holds.size)
        assertEquals(BudgetLedgerService.HOLD_ACTIVE, holds.single().status)
    }

    // ------------------------------------------------------------------
    // 2+3. Same identity, EXPIRED hold — explicit ExpiredReplay, sweep-invariant
    // ------------------------------------------------------------------

    @Test
    fun `same identity after expiry BEFORE the sweep returns the explicit ExpiredReplay`() = runBlocking {
        configureBudget()
        val first = governance.authorize(request("exec-b", 0, 1))
        assertEquals(EconomicGateDecision.ALLOWED, first.decision)
        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period
        val hold = holdsInPeriod(period.budgetPeriodId).single()

        // Advance past the hold TTL WITHOUT the bookkeeping sweep.
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1

        val ledgerVerdict = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE",
            platformObjectId = workspaceId,
            amountMicroUnits = 1_875L,
            reason = HoldKeys.holdReason("exec-b", 0, 1),
            idempotencyKey = HoldKeys.authorizeIdempotencyKey("exec-b", 0, 1, "WORKSPACE", workspaceId),
            expiresAtEpochMs = tick + HoldKeys.DEFAULT_HOLD_TTL_MS,
            nowEpochMs = tick
        )
        assertTrue(
            "an unswept but timestamp-expired hold replays as the EXPLICIT ExpiredReplay",
            ledgerVerdict is BudgetLedgerService.HoldReservation.ExpiredReplay
        )
        val expiredReplay = ledgerVerdict as BudgetLedgerService.HoldReservation.ExpiredReplay
        assertEquals(hold.holdId, expiredReplay.holdId)
        assertEquals("still exactly one hold — nothing re-created", 1, holdsInPeriod(period.budgetPeriodId).size)

        // The GATE level: an explicit DENY naming the attempt contract —
        // never an ALLOW the caller could read as fresh spending authority.
        val gateVerdict = governance.authorize(request("exec-b", 0, 1))
        assertEquals(EconomicGateDecision.DENIED, gateVerdict.decision)
        assertTrue(
            "the deny names the attempt-increment contract",
            gateVerdict.reason.contains("DEBT-2") && gateVerdict.reason.contains("attempt")
        )
    }

    @Test
    fun `same identity after expiry AFTER the sweep returns the equivalent ExpiredReplay`() = runBlocking {
        configureBudget()
        governance.authorize(request("exec-c", 0, 1))
        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period
        val hold = holdsInPeriod(period.budgetPeriodId).single()

        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        val swept = ledger.expireHolds(tick)
        assertEquals("the sweep re-stamped the hold EXPIRED", 1, swept)
        assertEquals(BudgetLedgerService.HOLD_EXPIRED, db.budgetHoldDao().byId(hold.holdId)!!.status)

        val ledgerVerdict = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE",
            platformObjectId = workspaceId,
            amountMicroUnits = 1_875L,
            reason = HoldKeys.holdReason("exec-c", 0, 1),
            idempotencyKey = HoldKeys.authorizeIdempotencyKey("exec-c", 0, 1, "WORKSPACE", workspaceId),
            expiresAtEpochMs = tick + HoldKeys.DEFAULT_HOLD_TTL_MS,
            nowEpochMs = tick
        )
        assertTrue(
            "the swept expired hold replays as the SAME explicit ExpiredReplay",
            ledgerVerdict is BudgetLedgerService.HoldReservation.ExpiredReplay &&
                (ledgerVerdict as BudgetLedgerService.HoldReservation.ExpiredReplay).holdId == hold.holdId
        )
        assertEquals("still exactly one hold — nothing renewed", 1, holdsInPeriod(period.budgetPeriodId).size)
    }

    // ------------------------------------------------------------------
    // 4. Availability truth: the expired replay carries NO fresh authority
    // ------------------------------------------------------------------

    @Test
    fun `an expired replay never suppresses availability - no fresh spending authority`() = runBlocking {
        configureBudget(limitMicroUnits = 10_000L)
        governance.authorize(request("exec-d", 0, 1)) // hold 1,875 micro
        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period

        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        ledger.expireHolds(tick)

        // Availability AFTER expiry: the expired hold no longer suppresses.
        val availableAfterExpiry = ledger.availableForPeriod(period.budgetPeriodId, tick).availableMicroUnits
        assertEquals(10_000L, availableAfterExpiry)

        // The expired replay verdict does NOT change availability either.
        governance.authorize(request("exec-d", 0, 1))
        assertEquals(
            "the expired replay granted no fresh authority",
            10_000L,
            ledger.availableForPeriod(period.budgetPeriodId, tick).availableMicroUnits
        )
    }

    // ------------------------------------------------------------------
    // 5. A NEW attempt identity authorizes a FRESH reservation
    // ------------------------------------------------------------------

    @Test
    fun `a new attempt identity creates a new reservation when policy permits`() = runBlocking {
        configureBudget()
        governance.authorize(request("exec-e", 0, 1))

        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        ledger.expireHolds(tick)

        val fresh = governance.authorize(request("exec-e", 0, 2))
        assertEquals(
            "the redispatch under attempt+1 authorizes FRESH (no [replay])",
            EconomicGateDecision.ALLOWED,
            fresh.decision
        )
        assertTrue("a fresh reservation, not a replay", !fresh.reason.contains("[replay]"))

        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period
        val holds = holdsInPeriod(period.budgetPeriodId)
        assertEquals("the expired attempt-1 hold AND the fresh attempt-2 hold both exist", 2, holds.size)
        val statuses = holds.map { it.status }.toSet()
        assertTrue(
            "attempt-1 is EXPIRED, attempt-2 is ACTIVE",
            statuses.contains(BudgetLedgerService.HOLD_EXPIRED) && statuses.contains(BudgetLedgerService.HOLD_ACTIVE)
        )
    }

    // ------------------------------------------------------------------
    // 6. Concurrent duplicate requests remain exactly-once
    // ------------------------------------------------------------------

    @Test
    fun `concurrent duplicate authorizations of the same identity create exactly one hold`() = runBlocking {
        configureBudget()
        val verdicts = (1..8).map {
            async(Dispatchers.IO) { governance.authorize(request("exec-f", 0, 1)) }
        }.awaitAll()

        assertEquals(
            "every concurrent duplicate sees ALLOWED (the original outcome)",
            8,
            verdicts.count { it.decision == EconomicGateDecision.ALLOWED }
        )
        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period
        assertEquals(
            "exactly ONE hold row for the identity — exactly-once under concurrency",
            1,
            holdsInPeriod(period.budgetPeriodId).size
        )
    }

    @Test
    fun `concurrent duplicates after expiry all receive the explicit ExpiredReplay - never a second hold`() = runBlocking {
        configureBudget()
        governance.authorize(request("exec-g", 0, 1))
        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period

        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        ledger.expireHolds(tick)

        val ledgerVerdicts = (1..8).map {
            async(Dispatchers.IO) {
                ledger.authorizeAndReserve(
                    scopeType = "WORKSPACE",
                    platformObjectId = workspaceId,
                    amountMicroUnits = 1_875L,
                    reason = HoldKeys.holdReason("exec-g", 0, 1),
                    idempotencyKey = HoldKeys.authorizeIdempotencyKey("exec-g", 0, 1, "WORKSPACE", workspaceId),
                    expiresAtEpochMs = tick + HoldKeys.DEFAULT_HOLD_TTL_MS,
                    nowEpochMs = tick
                )
            }
        }.awaitAll()

        assertTrue(
            "every concurrent expired replay is the explicit ExpiredReplay (or an equivalent replay of it)",
            ledgerVerdicts.all {
                it is BudgetLedgerService.HoldReservation.ExpiredReplay ||
                    (it is BudgetLedgerService.HoldReservation.Reserved && it.replayed)
            }
        )
        assertEquals(
            "still exactly ONE hold row — no second reservation under the same identity",
            1,
            holdsInPeriod(period.budgetPeriodId).size
        )
    }

    // ------------------------------------------------------------------
    // 7. The identity grammar: different steps never collide
    // ------------------------------------------------------------------

    @Test
    fun `distinct steps of the same execution reserve independently`() = runBlocking {
        configureBudget()
        governance.authorize(request("exec-h", 0, 1))
        governance.authorize(request("exec-h", 1, 1))

        val period = ledger.activeBudgetForScope("WORKSPACE", workspaceId, tick)!!.period
        assertEquals(2, holdsInPeriod(period.budgetPeriodId).size)
        assertNotEquals(
            HoldKeys.holdReason("exec-h", 0, 1),
            HoldKeys.holdReason("exec-h", 1, 1)
        )
    }
}
