package com.example.application.budget

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.resource.ResourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
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
 * STAGE 3A — DEBT-3 closure: dispatch-state granularity
 * ============================================================================
 *
 * The AUTHORITATIVE dispatch-state contract (UsageDispatchState) proven on
 * the PRODUCTION chain. Before Stage 3A, the orchestrator stamped
 * `dispatchStarted = true` at executeAction METHOD ENTRY — a cancellation
 * during the pre-provider phases (resource resolution, offline check,
 * circuit-breaker check, pre-dispatch authorization) was conservatively
 * treated as DISPATCHED. Now the boundary is reported by the EXECUTION
 * layer exactly when the provider invocation begins:
 *
 *   NOT_DISPATCHED       → the reservation releases (arm D);
 *   PROVIDER_CALL_STARTED/COMPLETED/OUTCOME_UNKNOWN
 *                        → conservative accounting (settle with a report,
 *                           keep under the bounded TTL without one).
 *
 * Required scenarios (order §5):
 *   1. cancellation during resource resolution (pre-boundary) → RELEASE;
 *   2. offline rejection before provider invocation → RELEASE;
 *   3. circuit-breaker rejection before provider invocation → RELEASE;
 *   4. authorization rejection before provider invocation → RELEASE;
 *   5. cancellation after provider invocation begins → KEEP (conservative);
 *   6. provider timeout after invocation begins → KEEP (conservative);
 *   7. provider response and normal settlement → SETTLE;
 *   8. exactly-once accounting under retries.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
class Stage3ADispatchStateAcceptanceTest : EconomicGovernanceProductionHarness() {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Launches the production loop into [events] under [scope]. */
    private fun launchTask(
        scope: CoroutineScope,
        events: MutableList<ExecutionEvent>,
        task: com.example.domain.core.task.TaskDefinition = unfulfillableTask()
    ): Job = scope.launch {
        runCatching { orchestrator.executeTaskStream(agent, task).toList(events) }
    }

    /** Polls until the period holds at least [count] hold row(s). */
    private suspend fun awaitHold(count: Int = 1): String {
        val periodId = activePeriodId()!!
        withTimeout(20_000) {
            while (db.budgetHoldDao().forPeriod(periodId).size < count) {
                delay(10)
            }
        }
        return periodId
    }

    /** A direct EXECUTE_STEP action carrying an authoritative DecisionRecord
     *  for the scripted provider (the production executeAction entry). */
    private fun llmStepAction() = DecisionAction(
        type = DecisionActionType.EXECUTE_STEP,
        targetId = "prod-model",
        payload = mapOf("modelId" to "prod-model"),
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId("prod_path_provider"),
            providerId = scriptedProvider.providerId,
            serviceId = "prod-model",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            rationale = "harness",
            confidence = 0.9f
        )
    )

    /** The minimal decision context for direct executeAction calls. */
    private fun decisionContext(policy: NetworkPolicy = NetworkPolicy.HYBRID) =
        com.example.application.decision.DecisionContext(
            task = unfulfillableTask(),
            networkPolicy = policy,
            isNetworkAvailable = policy != NetworkPolicy.OFFLINE
        )

    // ------------------------------------------------------------------
    // 1. Cancellation during resource resolution (pre-boundary) → RELEASE
    // ------------------------------------------------------------------

    @Test
    fun `cancellation landing before the provider boundary releases the reservation`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget()
        // Hold the production loop at the action-intent ledger write — a
        // deterministic suspension point BETWEEN the decide-time hold
        // creation and executeAction's provider boundary (the pre-provider
        // phases: resource resolution, offline/breaker checks, pre-dispatch
        // authorization have not been crossed yet).
        val latch = CompletableDeferred<Unit>()
        controllableIntentDao.holdLatch = latch

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = mutableListOf<ExecutionEvent>()
        val job = launchTask(scope, events)

        val periodId = awaitHold(1)
        val hold = db.budgetHoldDao().forPeriod(periodId).single()
        assertEquals("the decide-time hold is ACTIVE pre-boundary", BudgetLedgerService.HOLD_ACTIVE, hold.status)

        // Cancel while the loop is provably BEFORE the provider boundary.
        job.cancelAndJoin()
        latch.complete(Unit)

        assertEquals("the provider was NEVER invoked", 0, scriptedProvider.invocations.get())
        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(
            "pre-boundary cancellation RELEASED the reservation (arm D)",
            BudgetLedgerService.HOLD_RELEASED,
            holdAfter.status
        )
        val settlements = settlementsForHold(hold.holdId)
        assertTrue(
            "a RELEASE settlement exists — never a fabricated ledger entry",
            settlements.any { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
        assertEquals(
            "no consumption entry was ever posted for the undispatched step",
            0L,
            db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        )
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 2. Offline rejection before provider invocation → RELEASE
    // ------------------------------------------------------------------

    @Test
    fun `offline rejection before provider invocation proves NOT_DISPATCHED and releases`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget()

        // The decide-gate-shaped authorization creates the step's hold…
        val decide = governance.authorize(
            com.example.domain.core.budget.EconomicAuthorizationRequest(
                executionId = "exec-offline",
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
                stepIndex = 0,
                attempt = 1
            )
        )
        assertEquals(
            com.example.domain.core.budget.EconomicGateDecision.ALLOWED,
            decide.decision
        )

        // …the execution layer rejects the step OFFLINE, before the provider.
        val result = executionService.executeAction(
            action = llmStepAction(),
            context = decisionContext(policy = NetworkPolicy.OFFLINE),
            agent = agent,
            executionId = "exec-offline",
            stepIndex = 0,
            attempt = 1
        )
        assertTrue("the offline step failed honestly", !result.isSuccess)
        assertEquals(
            "the execution layer PROVED the provider call never started",
            com.example.domain.core.budget.UsageDispatchState.NOT_DISPATCHED,
            result.dispatchState
        )
        assertEquals(0, scriptedProvider.invocations.get())

        // …the accounting hook releases the reservation (arm D).
        governance.accountUsageSuspend(
            com.example.domain.core.budget.UsageAccountingInput(
                executionId = "exec-offline",
                taskId = "prod-path-task",
                workspaceId = workspaceId,
                agentId = agent.identity.id.value,
                providerId = scriptedProvider.providerId,
                serviceId = "prod-model",
                modelId = "prod-model",
                resourceId = "prod_path_provider",
                usage = com.example.domain.core.budget.TokenUsageRecord(),
                isActualProviderReport = false,
                stepIndex = 0,
                attempt = 1,
                dispatchState = result.dispatchState,
                hasUsageReport = false
            )
        )
        val periodId = activePeriodId()!!
        val hold = db.budgetHoldDao().forPeriod(periodId).single()
        assertEquals(
            "the offline-rejected step's reservation was released",
            BudgetLedgerService.HOLD_RELEASED,
            hold.status
        )
        assertEquals(0L, db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId))
    }

    // ------------------------------------------------------------------
    // 3. Circuit-breaker rejection before provider invocation → RELEASE
    // ------------------------------------------------------------------

    @Test
    fun `circuit-breaker rejection before provider invocation releases the reservation`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget()

        // Open the breaker for the authoritative resource BEFORE the run.
        val breaker = com.example.application.resilience.CircuitBreakerService()
        executionService.circuitBreakerGate = breaker
        repeat(10) { breaker.recordFailure("prod_path_provider", "HARNESS_PREOPEN", "warm") }
        assertTrue(
            "the breaker is OPEN for the resource",
            !breaker.allowCall("prod_path_provider")
        )

        val events = runTask(unfulfillableTask())
        assertEquals("the provider was never invoked", 0, scriptedProvider.invocations.get())
        assertTrue(
            "the step failed honestly with CIRCUIT_OPEN",
            events.any { it is ExecutionEvent.Error && it.failureCode == "CIRCUIT_OPEN" } ||
                events.any { it is ExecutionEvent.ActionFailed }
        )

        val periodId = activePeriodId()!!
        val holds = db.budgetHoldDao().forPeriod(periodId)
        assertTrue("the decide gate created hold(s) for the billable steps", holds.isNotEmpty())
        assertTrue(
            "every breaker-rejected step's reservation was RELEASED (proven undispatched)",
            holds.all { it.status == BudgetLedgerService.HOLD_RELEASED }
        )
        assertEquals(
            "no consumption was ever posted for breaker-rejected steps",
            0L,
            db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        )
    }

    // ------------------------------------------------------------------
    // 4. Authorization rejection before provider invocation → RELEASE
    // ------------------------------------------------------------------

    @Test
    fun `pre-dispatch authorization rejection after a mid-flight policy edit releases the reservation`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        // Freeze the loop between the decide-time hold and the dispatch, then
        // EDIT the policy (a REAL production scenario: the operator lowers
        // the budget mid-execution) — the pre-dispatch gate must then DENY
        // under the new version/period, before any provider invocation.
        val latch = CompletableDeferred<Unit>()
        controllableIntentDao.holdLatch = latch
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = mutableListOf<ExecutionEvent>()
        val job = launchTask(scope, events)

        val periodId = awaitHold(1)
        val hold = db.budgetHoldDao().forPeriod(periodId).single()

        // The material edit: an immutable v2 whose limit (5 micro) is BELOW
        // the step estimate (8 micro) + a NEW period — the pre-dispatch gate
        // must DENY under the new authority, before any provider invocation.
        configureWorkspaceBudget(limitMicroUnits = 5L)

        latch.complete(Unit)
        withTimeout(20_000) {
            while (scriptedProvider.invocations.get() == 0 &&
                db.budgetHoldDao().byId(hold.holdId)!!.status == BudgetLedgerService.HOLD_ACTIVE
            ) {
                if (!job.isActive) break
                delay(10)
            }
        }
        runCatching { withTimeout(10_000) { job.join() } }
        scope.cancel()

        assertEquals("the provider was never invoked", 0, scriptedProvider.invocations.get())
        assertTrue(
            "the pre-dispatch gate DENIED honestly (budget gate event present)",
            events.any { it is ExecutionEvent.Error && it.failureCode.startsWith("BUDGET_GATE") }
        )
        assertEquals(
            "the v1-period reservation of the denied step was RELEASED",
            BudgetLedgerService.HOLD_RELEASED,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )
        assertEquals(
            "no consumption was posted for the authorization-rejected step",
            0L,
            db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        )
    }

    // ------------------------------------------------------------------
    // 5. Cancellation after provider invocation begins → KEEP (conservative)
    // ------------------------------------------------------------------

    @Test
    fun `cancellation after the provider boundary keeps the reservation conservative`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget()
        // The provider ENTERS and then hangs — the boundary is crossed.
        scriptedProvider.hangLatch = CompletableDeferred()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = mutableListOf<ExecutionEvent>()
        val job = launchTask(scope, events)

        // Wait until the provider invocation has provably STARTED.
        withTimeout(20_000) { scriptedProvider.entered.await() }
        val periodId = awaitHold(1)
        val hold = db.budgetHoldDao().forPeriod(periodId).single()

        job.cancelAndJoin()

        assertEquals("the provider invocation was entered exactly once", 1, scriptedProvider.invocations.get())
        val holdAfter = db.budgetHoldDao().byId(hold.holdId)!!
        assertEquals(
            "a cancellation AFTER the boundary keeps the reservation (UNKNOWN cost, bounded TTL)",
            BudgetLedgerService.HOLD_ACTIVE,
            holdAfter.status
        )
        assertTrue(
            "no fabricated release settlement exists",
            settlementsForHold(hold.holdId).none { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
        val available = ledgerService.availableForPeriod(periodId, tick)
        assertEquals(
            "availability stays suppressed by the in-flight reservation",
            5_000_000L - hold.holdAmountMicroUnits,
            available.availableMicroUnits
        )
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 6. Provider timeout after invocation begins → KEEP (conservative)
    // ------------------------------------------------------------------

    @Test
    fun `provider timeout after the boundary keeps the reservation conservative`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget()
        scriptedProvider.hangLatch = CompletableDeferred() // the call never resolves

        val timedOut = runCatching {
            runTaskWithTimeout(unfulfillableTask(), timeoutMs = 2_000)
        }
        assertTrue(
            "the hanging provider call surfaced as a timeout cancellation",
            timedOut.exceptionOrNull() is CancellationException
        )

        assertEquals(1, scriptedProvider.invocations.get())
        val periodId = activePeriodId()!!
        val hold = db.budgetHoldDao().forPeriod(periodId).single()
        assertEquals(
            "OUTCOME_UNKNOWN keeps the reservation — never a fabricated release",
            BudgetLedgerService.HOLD_ACTIVE,
            db.budgetHoldDao().byId(hold.holdId)!!.status
        )
        assertTrue(
            "no release settlement exists for the timed-out step",
            settlementsForHold(hold.holdId).none { it.settlementType == BudgetLedgerService.SETTLEMENT_RELEASE }
        )
    }

    // ------------------------------------------------------------------
    // 7. Provider response and normal settlement
    // ------------------------------------------------------------------

    @Test
    fun `a normal provider response settles against the reservation with exact persistence`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        val events = runTask(unfulfillableTask())
        assertTrue(
            "the provider was invoked through the production chain",
            scriptedProvider.invocations.get() > 0
        )
        assertTrue(
            "CostRecorded events reached the bus",
            events.any { it is ExecutionEvent.CostRecorded }
        )

        val periodId = activePeriodId()!!
        val holds = db.budgetHoldDao().forPeriod(periodId)
        assertTrue("billable steps created holds", holds.isNotEmpty())
        assertTrue(
            "every completed step reached a terminal hold state (no zombie ACTIVE reservations)",
            holds.all {
                it.status == BudgetLedgerService.HOLD_SETTLED ||
                    it.status == BudgetLedgerService.HOLD_RELEASED ||
                    it.status == BudgetLedgerService.HOLD_PARTIALLY_SETTLED
            }
        )
        // Every SETTLE settlement has its matching POSTED consumption entry.
        val settlements = holds.flatMap { settlementsForHold(it.holdId) }
        val settles = settlements.filter { it.settlementType == BudgetLedgerService.SETTLEMENT_SETTLE }
        assertTrue("settlements were recorded", settles.isNotEmpty())
        val entries = entriesForPeriod(periodId)
        for (settle in settles) {
            assertNotNull(
                "the settle's ledger entry is persisted",
                settle.ledgerEntryId?.let { db.budgetLedgerEntryDao().byId(it) }
            )
        }
        assertTrue(
            "consumption entries are POSTED (never DRAFT) on the normal path",
            entries.filter { it.entryType == BudgetLedgerService.ENTRY_CONSUMPTION }
                .all { it.postingState == BudgetLedgerService.POSTING_POSTED }
        )
        // The balance equation closes over raw rows: no active holds remain,
        // so available == limit + postedEffects.
        val available = ledgerService.availableForPeriod(periodId, tick)
        val posted = db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        assertTrue("real consumption was posted", posted < 0)
        assertEquals(5_000_000L + posted, available.availableMicroUnits)
    }

    // ------------------------------------------------------------------
    // 8. Exactly-once accounting under retries (duplicate callback)
    // ------------------------------------------------------------------

    @Test
    fun `a duplicate accounting callback for a settled step never double-posts`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L)

        runTask(unfulfillableTask())
        val periodId = activePeriodId()!!
        val postedBefore = db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        val entriesBefore = entriesForPeriod(periodId).size

        // A duplicate/late callback for the SAME (executionId, step, attempt)
        // as one of the settled steps — the canonical replay identity.
        val settledHold = db.budgetHoldDao().forPeriod(periodId)
            .first { it.status == BudgetLedgerService.HOLD_SETTLED }
        val reason = settledHold.reason!! // "exec:{id}:step:{n}:attempt:{a}"
        val executionId = reason.removePrefix("exec:").substringBefore(":step:")
        val stepIndex = reason.substringAfter(":step:").substringBefore(":attempt:").toInt()
        val attempt = reason.substringAfter(":attempt:").toInt()

        repeat(3) {
            governance.accountUsageSuspend(
                com.example.domain.core.budget.UsageAccountingInput(
                    executionId = executionId,
                    taskId = "prod-path-task",
                    workspaceId = workspaceId,
                    agentId = agent.identity.id.value,
                    providerId = scriptedProvider.providerId,
                    serviceId = "prod-model",
                    modelId = "prod-model",
                    resourceId = "prod_path_provider",
                    usage = com.example.domain.core.budget.TokenUsageRecord(
                        inputTokens = 5_000,
                        outputTokens = 5_000
                    ),
                    isActualProviderReport = true,
                    stepIndex = stepIndex,
                    attempt = attempt,
                    dispatchState = com.example.domain.core.budget.UsageDispatchState.COMPLETED,
                    hasUsageReport = true
                )
            )
        }

        assertEquals(
            "the replayed callbacks posted NOTHING new — exactly-once accounting",
            postedBefore,
            db.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        )
        assertEquals(entriesBefore, entriesForPeriod(periodId).size)
    }
}
