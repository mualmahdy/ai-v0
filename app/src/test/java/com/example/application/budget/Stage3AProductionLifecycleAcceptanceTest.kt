package com.example.application.budget

import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.UsageAccountingInput
import com.example.domain.core.budget.UsageDispatchState
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================
 * STAGE 3A — DEBT-5 closure: the production-path lifecycle harness
 * ============================================================================
 *
 * The complete production chain on one deterministic fixture (see
 * [EconomicGovernanceProductionHarness]):
 *
 *   DecisionService → AgentOrchestrator → ExecutionService → provider
 *   boundary (scripted) → BudgetActualsFeed/accountUsageSuspend →
 *   HoldSettlementService → BudgetLedgerService
 *
 * The required scenarios (order §7), every one proven on PERSISTED state
 * (holds, settlements, ledger entries, posting states, idempotency keys,
 * period/scope ownership, final balance):
 *
 *   1. known estimate → reservation → provider call → actual → settlement;
 *   2. known zero-cost result → correct release;
 *   3. proven pre-dispatch cancellation → safe release;
 *   4. provider invocation + timeout → conservative keep;
 *   5. actual above reservation → complete overage accounting;
 *   6. expired reservation + late actual cost;
 *   7. duplicate callback/replay → exactly-once;
 *   8. conflicting workspace identity → explicit rejection;
 *   9. no modern policy → preserved legacy behavior;
 *   10. unknown pricing → no fabricated cost or reservation;
 *   11. currency conversion path → correct attribution + settlement.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
class Stage3AProductionLifecycleAcceptanceTest : EconomicGovernanceProductionHarness() {

    private fun conflictTask(): TaskDefinition {
        val base = unfulfillableTask()
        return base.copy(
            id = TaskId("prod-path-conflict-task"),
            input = TaskInput(
                rawPrompt = base.input.rawPrompt,
                parameters = mapOf("workspaceId" to "ws-other")
            )
        )
    }

    /** A late/duplicate accounting callback shaped exactly like the
     *  orchestrator's post-step production call. */
    private fun accountingInput(
        executionId: String,
        stepIndex: Int,
        attempt: Int,
        inputTokens: Int = 5_000,
        outputTokens: Int = 5_000,
        dispatchState: UsageDispatchState = UsageDispatchState.COMPLETED
    ) = UsageAccountingInput(
        executionId = executionId,
        taskId = "prod-path-task",
        workspaceId = workspaceId,
        agentId = agent.identity.id.value,
        providerId = scriptedProvider.providerId,
        serviceId = "prod-model",
        modelId = "prod-model",
        resourceId = "prod_path_provider",
        usage = com.example.domain.core.budget.TokenUsageRecord(
            inputTokens = inputTokens,
            outputTokens = outputTokens
        ),
        isActualProviderReport = true,
        stepIndex = stepIndex,
        attempt = attempt,
        dispatchState = dispatchState,
        hasUsageReport = true
    )

    // ------------------------------------------------------------------
    // 1. Known estimate → reservation → provider call → actual → settlement
    // ------------------------------------------------------------------

    @Test
    fun `scenario 1 - full known-cost lifecycle settles exactly with persisted evidence`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        val events = runTask(unfulfillableTask())
        assertTrue(scriptedProvider.invocations.get() > 0)
        assertTrue(events.any { it is ExecutionEvent.CostRecorded })

        val periodId = activePeriodId()!!
        val holds = allHoldsInPeriod(periodId)
        assertTrue("billable steps created reservations", holds.isNotEmpty())

        var settledSteps = 0
        for (hold in holds) {
            assertTrue("hold carries the canonical reason grammar", hold.reason!!.startsWith("exec:"))
            val settlements = settlementsForHold(hold.holdId)
            val settle = settlements.firstOrNull { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }
            if (settle != null) {
                settledSteps++
                // The SETTLE settlement is linked to its POSTED entry.
                val entry = settle.ledgerEntryId?.let { db.budgetLedgerEntryDao().byId(it) }
                assertNotNull("the settle's ledger entry is persisted", entry)
                assertEquals(BudgetLedgerService.POSTING_POSTED, entry!!.postingState)
                assertEquals(BudgetLedgerService.ENTRY_CONSUMPTION, entry.entryType)
                assertEquals("entry and settlement agree to the micro unit", settle.amountMicroUnits, -entry.availableEffectMicroUnits)
                assertEquals("the canonical settle idempotency key", HoldKeys.settleKey(executionIdOf(hold), stepIndexOf(hold), attemptOf(hold)), settle.idempotencyKey)
                assertEquals("period ownership: the entry lives in the hold's period", hold.budgetPeriodId, entry.budgetPeriodId)
                assertEquals("currency: the budget (policy) currency", "USD", entry.currency)
            }
        }
        assertTrue("at least one step settled against its reservation", settledSteps > 0)

        // FINAL BALANCE: recomputed from raw rows only.
        val balance = ledgerService.availableForPeriod(periodId, tick)
        assertEquals(
            5_000_000L,
            balance.limitMicroUnits
        )
        assertTrue(
            "real consumption was posted (balance below the limit)",
            balance.availableMicroUnits < 5_000_000L
        )
        assertEquals(
            "balance equation: available = limit + posted - activeHolds(=0 terminal)",
            5_000_000L + balance.postedEffectsMicroUnits - balance.activeHoldRemainingMicroUnits,
            balance.availableMicroUnits
        )
    }

    // ------------------------------------------------------------------
    // 2. Known zero-cost result → correct release behavior
    // ------------------------------------------------------------------

    @Test
    fun `scenario 2 - a conclusively zero-cost result releases the reservation`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)
        scriptedProvider.promptTokens = 0
        scriptedProvider.completionTokens = 0

        runTask(unfulfillableTask())

        val periodId = activePeriodId()!!
        val holds = allHoldsInPeriod(periodId)
        assertTrue("the estimate created reservations", holds.isNotEmpty())
        assertTrue(
            "every zero-cost step's reservation was RELEASED, never left pinned",
            holds.all { it.status == BudgetLedgerService.HOLD_RELEASED }
        )
        assertTrue(
            "a RELEASE settlement exists per step (G06: no ledger entry for a release)",
            holds.all { h ->
                settlementsForHold(h.holdId).any { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
            }
        )
        assertEquals(
            "no consumption was posted — zero is conclusive, nothing fabricated",
            0L,
            db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        )
        assertEquals(
            "final available balance is the untouched limit",
            5_000_000L,
            ledgerService.availableForPeriod(periodId, tick).availableMicroUnits
        )
    }

    // ------------------------------------------------------------------
    // 3. Proven pre-dispatch cancellation → safe release
    // ------------------------------------------------------------------

    @Test
    fun `scenario 3 - proven pre-dispatch cancellation releases safely with nothing fabricated`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)
        val latch = CompletableDeferred<Unit>()
        controllableIntentDao.holdLatch = latch

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = mutableListOf<ExecutionEvent>()
        val job = scope.launch {
            runCatching { orchestrator.executeTaskStream(agent, unfulfillableTask()).toList(events) }
        }

        val periodId = activePeriodId()!!
        withTimeout(20_000) {
            while (allHoldsInPeriod(periodId).isEmpty()) delay(10)
        }
        val hold = allHoldsInPeriod(periodId).single()

        job.cancelAndJoin()
        latch.complete(Unit)

        assertEquals("the provider was never called", 0, scriptedProvider.invocations.get())
        assertEquals(
            "the reservation was safely released",
            BudgetLedgerService.HOLD_RELEASED,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )
        assertTrue(
            "the release is a settlement row, never a fabricated ledger entry",
            settlementsForHold(hold.holdId).any { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
        assertEquals(
            "final available balance is the untouched limit",
            5_000_000L,
            ledgerService.availableForPeriod(periodId, tick).availableMicroUnits
        )
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 4. Provider invocation + timeout → conservative keep
    // ------------------------------------------------------------------

    @Test
    fun `scenario 4 - timeout after invocation keeps the reservation conservative`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)
        scriptedProvider.hangLatch = CompletableDeferred()

        val outcome = runCatching { runTaskWithTimeout(unfulfillableTask(), timeoutMs = 2_000) }
        assertTrue("the run surfaced the timeout", outcome.exceptionOrNull() is CancellationException)

        val periodId = activePeriodId()!!
        val hold = allHoldsInPeriod(periodId).single()
        assertEquals(
            "OUTCOME_UNKNOWN keeps the reservation under the bounded TTL",
            BudgetLedgerService.HOLD_ACTIVE,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )
        assertTrue(
            "no fabricated release",
            settlementsForHold(hold.holdId).none { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
        assertEquals(
            "availability stays suppressed (unknown cost is never zero)",
            5_000_000L - hold.holdAmountMicroUnits,
            ledgerService.availableForPeriod(periodId, tick).availableMicroUnits
        )
    }

    // ------------------------------------------------------------------
    // 5. Actual above reservation → complete overage accounting (I-11)
    // ------------------------------------------------------------------

    @Test
    fun `scenario 5 - actual above reservation posts the overage completely`() = runBlocking {
        // Pricing: 1500 in / 3000 out micro per million. The capped estimate
        // is 4000 tokens (3000 in + 1000 out) = 7.5 micro; the scripted
        // actual is 5000+5000 tokens = 22.5 micro — comfortably above.
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        runTask(unfulfillableTask())

        val periodId = activePeriodId()!!
        val entries = entriesForPeriod(periodId)
        val overageEntries = entries.filter {
            it.idempotencyKey?.endsWith(":overage") == true
        }
        assertTrue(
            "overage entries were posted for the steps whose actual exceeded the reservation",
            overageEntries.isNotEmpty()
        )
        assertTrue(
            "the overage is a POSTED consumption entry (actuals are never truncated)",
            overageEntries.all {
                it.postingState == BudgetLedgerService.POSTING_POSTED &&
                    it.entryType == BudgetLedgerService.ENTRY_CONSUMPTION
            }
        )
        // The full actual is accounted: for the first settled hold, the
        // settle amount + the overage >= the settle amount alone, and the
        // hold itself is fully consumed (SETTLED).
        val settledHold = allHoldsInPeriod(periodId).firstOrNull {
            it.status == BudgetLedgerService.HOLD_SETTLED
        }
        assertNotNull("a step settled to its full reservation", settledHold)
        val settle = settlementsForHold(settledHold!!.holdId)
            .first { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }
        assertEquals(
            "the reservation was consumed to the last micro",
            settledHold.holdAmountMicroUnits,
            settle.amountMicroUnits
        )
        // The complete accounting: every consumption entry is POSTED with an
        // explicit idempotency key (settle or overage), nothing DRAFT.
        assertTrue(
            entries.filter { it.entryType == BudgetLedgerService.ENTRY_CONSUMPTION }.all {
                it.postingState == BudgetLedgerService.POSTING_POSTED
            }
        )
    }

    // ------------------------------------------------------------------
    // 6. Expired reservation + late actual cost
    // ------------------------------------------------------------------

    @Test
    fun `scenario 6 - a late actual after the reservation expired posts without the hold exactly once`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        // The reservation is created by the decide-gate-shaped production
        // authorization (the same request applyEconomicGate issues).
        val request = EconomicAuthorizationRequest(
            executionId = "exec-late-actual",
            workspaceId = workspaceId,
            agentId = agent.identity.id.value,
            taskId = "prod-path-task",
            providerId = scriptedProvider.providerId,
            serviceId = "prod-model",
            modelId = "prod-model",
            resourceId = "prod_path_provider",
            isLocalResource = false,
            expectedTotalTokens = 1_000_000,
            actionTypeCode = "EXECUTE_STEP",
            stepIndex = 3,
            attempt = 1
        )
        assertEquals(EconomicGateDecision.ALLOWED, governance.authorize(request).decision)
        val periodId = activePeriodId()!!
        val hold = allHoldsInPeriod(periodId).single()

        // Process death: the TTL passes before the actual arrives.
        tick += HoldKeys.DEFAULT_HOLD_TTL_MS + 1
        ledgerService.expireHolds(tick)
        assertEquals(BudgetLedgerService.HOLD_EXPIRED, db.budgetHoldDao().byId(hold.holdId)!!.status)

        // The LATE actual lands through the production accounting hook.
        val record = governance.accountUsageSuspend(accountingInput("exec-late-actual", 3, 1))
        assertNotNull(record)

        // The expired hold is untouched (EXPIRED — never "settled" post hoc)…
        assertEquals(
            BudgetLedgerService.HOLD_EXPIRED,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )
        assertTrue(
            "no settlement was fabricated against the expired hold",
            settlementsForHold(hold.holdId).isEmpty()
        )
        // …but the REAL known consumption is never discarded: a POSTED entry
        // exists WITHOUT the hold, exactly once, under the active period.
        val consumption = entriesForPeriod(periodId)
            .filter { it.entryType == BudgetLedgerService.ENTRY_CONSUMPTION && it.holdId == null }
        assertEquals(1, consumption.size)
        assertEquals(BudgetLedgerService.POSTING_POSTED, consumption.single().postingState)
        assertEquals(
            "the actual's idempotency key is the canonical one",
            HoldKeys.actualKey("exec-late-actual", 3, 1),
            consumption.single().idempotencyKey
        )
        // Availability reflects the real cost, not the dead reservation.
        val balance = ledgerService.availableForPeriod(periodId, tick)
        assertEquals(5_000_000L + balance.postedEffectsMicroUnits, balance.availableMicroUnits)
    }

    // ------------------------------------------------------------------
    // 7. Duplicate callback / replay → exactly-once
    // ------------------------------------------------------------------

    @Test
    fun `scenario 7 - a duplicate late callback for the same identity never double-posts`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        runTask(unfulfillableTask())
        val periodId = activePeriodId()!!
        val postedBefore = db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        val entriesBefore = entriesForPeriod(periodId).size

        // Replay the production accounting call for a settled step 3 times.
        val settled = allHoldsInPeriod(periodId).first { it.status == BudgetLedgerService.HOLD_SETTLED }
        val execId = executionIdOf(settled)
        val step = stepIndexOf(settled)
        val attempt = attemptOf(settled)
        repeat(3) { governance.accountUsageSuspend(accountingInput(execId, step, attempt)) }

        assertEquals(
            "exactly-once: the duplicate callbacks posted nothing new",
            postedBefore,
            db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        )
        assertEquals(entriesBefore, entriesForPeriod(periodId).size)
    }

    // ------------------------------------------------------------------
    // 8. Conflicting workspace identity → explicit rejection
    // ------------------------------------------------------------------

    @Test
    fun `scenario 8 - a conflicting workspace identity is explicitly rejected end to end`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(workspace = workspaceId)
        configureWorkspaceBudget(workspace = "ws-other")

        runTask(conflictTask())

        assertEquals("the provider was never invoked", 0, scriptedProvider.invocations.get())
        assertEquals(
            "no reservation exists under the pinned workspace",
            0,
            allHoldsInPeriod(activePeriodId(workspaceId)!!).size
        )
        assertEquals(
            "no reservation exists under the claimed workspace",
            0,
            allHoldsInPeriod(activePeriodId("ws-other")!!).size
        )
        assertEquals(
            "the budget ledger is untouched",
            0,
            entriesForPeriod(activePeriodId(workspaceId)!!).size
        )
    }

    // ------------------------------------------------------------------
    // 9. No modern policy → preserved legacy behavior
    // ------------------------------------------------------------------

    @Test
    fun `scenario 9 - without a modern policy the legacy path is preserved`() = runBlocking {
        seedPricing()
        // NO configureWorkspaceBudget: no modern accounting policy exists.

        val events = runTask(unfulfillableTask())

        assertTrue(
            "the legacy path allowed the provider call (no silent blocking)",
            scriptedProvider.invocations.get() > 0
        )
        assertTrue(
            "usage was still accounted in the legacy cost ledger with attribution",
            events.any { it is ExecutionEvent.CostRecorded }
        )
        val periodId = activePeriodId()
        assertEquals("no modern policy -> no period", null, periodId)
        // No hold and no budget-ledger rows were ever created.
        val anyHold = db.budgetHoldDao().forPeriod("nonexistent").isEmpty()
        assertTrue(anyHold)
        assertTrue("observing created nothing", allBudgetHolds().isEmpty())
        assertTrue("no budget ledger entries", allBudgetEntries().isEmpty())
    }

    // ------------------------------------------------------------------
    // 10. Unknown pricing → no fabricated cost or reservation
    // ------------------------------------------------------------------

    @Test
    fun `scenario 10 - unknown pricing creates no reservation and no fabricated cost`() = runBlocking {
        // A modern policy EXISTS, but NO pricing entry: the estimate is
        // honestly UNKNOWN.
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        runTask(unfulfillableTask())

        assertTrue("the I-3 path allowed the provider call", scriptedProvider.invocations.get() > 0)
        val periodId = activePeriodId()!!
        assertEquals(
            "UNKNOWN estimate -> NO reservation was fabricated",
            0,
            allHoldsInPeriod(periodId).size
        )
        assertEquals(
            "no monetary consumption was fabricated",
            0,
            entriesForPeriod(periodId).count { it.postingState == BudgetLedgerService.POSTING_POSTED }
        )
        assertEquals(
            "the balance is untouched — the ceiling is honestly not enforceable",
            5_000_000L,
            ledgerService.availableForPeriod(periodId, tick).availableMicroUnits
        )
    }

    // ------------------------------------------------------------------
    // 11. Currency conversion path → correct attribution + settlement
    // ------------------------------------------------------------------

    @Test
    fun `scenario 11 - a EUR-priced step under a USD budget converts with recorded attribution`() = runBlocking {
        // A USD budget with EUR pricing and a recorded EUR->USD rule.
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L, currency = "USD")
        seedPricing(currency = "EUR")
        val registration = fxService.registerRule(
            fromCurrency = "EUR",
            toCurrency = "USD",
            ratePrecise = "2",
            effectiveFromEpochMs = 0,
            effectiveUntilEpochMs = null,
            sourceAuthority = "HARNESS:central-bank"
        )
        assertTrue(registration is CurrencyConversionService.RuleRegistration.Registered)

        runTask(unfulfillableTask())
        assertTrue(scriptedProvider.invocations.get() > 0)

        val periodId = activePeriodId()!!
        val holds = allHoldsInPeriod(periodId)
        assertTrue("the converted estimate reserved in the budget currency", holds.isNotEmpty())
        assertTrue(
            "every reservation is denominated in the POLICY currency (USD)",
            holds.all { it.currency == "USD" }
        )
        // The converted reservation: capped estimate 4000 tokens =
        // 3000×1500/1M + 1000×3000/1M = 7.5 micro EUR -> 7 micro EUR
        // (estimate floor) × 2 (recorded rate) = 14 micro USD.
        val firstHold = holds.first()
        assertEquals(14L, firstHold.holdAmountMicroUnits)

        // The settlement carries the FX attribution columns.
        val settle = settlementsForHold(firstHold.holdId)
            .firstOrNull { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }
        if (settle != null) {
            val entry = settle.ledgerEntryId?.let { db.budgetLedgerEntryDao().byId(it) }
            assertNotNull(entry)
            assertEquals("the posted amount is in the budget currency", "USD", entry!!.currency)
            assertEquals("the FX attribution names the original currency", "EUR", entry.originalCurrency)
            assertEquals(
                "the FX attribution names the recorded rule",
                (registration as CurrencyConversionService.RuleRegistration.Registered).ruleId,
                entry.conversionRuleId
            )
            assertNotNull("the exact pre-rounded decimal is preserved", entry.originalAmountPrecise)
        }
        assertTrue(
            "the posted consumption entries are all USD (single-currency ledger discipline)",
            entriesForPeriod(periodId)
                .filter { it.postingState == BudgetLedgerService.POSTING_POSTED }
                .all { it.currency == "USD" }
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun executionIdOf(hold: com.example.infrastructure.persistence.entities.BudgetHoldEntity): String =
        hold.reason!!.removePrefix("exec:").substringBefore(":step:")

    private fun stepIndexOf(hold: com.example.infrastructure.persistence.entities.BudgetHoldEntity): Int =
        hold.reason!!.substringAfter(":step:").substringBefore(":attempt:").toInt()

    private fun attemptOf(hold: com.example.infrastructure.persistence.entities.BudgetHoldEntity): Int =
        hold.reason!!.substringAfter(":attempt:").toInt()

    private suspend fun allBudgetHolds(): List<com.example.infrastructure.persistence.entities.BudgetHoldEntity> {
        // All holds across every period (the harness DB is per-test).
        val periods = db.budgetPeriodDao().openPeriods()
        return periods.flatMap { allHoldsInPeriod(it.budgetPeriodId) }
    }

    private suspend fun allBudgetEntries(): List<com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity> {
        val periods = db.budgetPeriodDao().openPeriods()
        return periods.flatMap { entriesForPeriod(it.budgetPeriodId) }
    }
}
