package com.example.application.budget

import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.UsageDispatchState
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================
 * STAGE 3A — DEBT-4 closure: workspace identity parity
 * ============================================================================
 *
 * ONE canonical workspace identity — the PINNED ExecutionScope's — feeds
 * BOTH economic gates of a billable step (the decide-time gate in
 * DecisionService.applyEconomicGate and the pre-dispatch LLM gate in
 * ExecutionService.executeLlmStep). Before Stage 3A the decide gate derived
 * it from the task's input parameter while the pre-dispatch gate read the
 * pinned scope: a disagreement could evaluate ONE billable operation
 * against two different workspace budgets.
 *
 * Required scenarios (order §6):
 *   1. matching task and pinned identities;
 *   2. missing optional task value with a valid pinned scope;
 *   3. CONFLICTING task and pinned identities → explicit integrity error
 *      (never a silent alternative authority);
 *   4. workspace switch between separate executions;
 *   5. both authorization gates resolve the SAME policy and period;
 *   6. no reservation is ever written against the wrong workspace.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
class Stage3AWorkspaceIdentityParityTest : EconomicGovernanceProductionHarness() {

    private val otherWorkspaceId = "ws-other"

    private fun taskWithParameter(param: Pair<String, String>?): TaskDefinition {
        val base = unfulfillableTask()
        return base.copy(
            id = TaskId("ws-parity-task"),
            input = TaskInput(
                rawPrompt = base.input.rawPrompt,
                parameters = param?.let { mapOf(it) } ?: emptyMap()
            )
        )
    }

    // ------------------------------------------------------------------
    // 1 + 2 + 5 + 6. The pinned scope is the ONE canonical identity
    // ------------------------------------------------------------------

    @Test
    fun `matching or absent task workspace resolves the pinned scope - one hold per step under the pinned period`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L, workspace = workspaceId)
        // A SECOND workspace with a different budget — a reservation landing
        // there would prove the identity diverged.
        configureWorkspaceBudget(limitMicroUnits = 999L, workspace = otherWorkspaceId)

        // (2) The task carries NO workspaceId parameter — the optional value
        // is absent and the pinned scope governs alone.
        runTask(taskWithParameter(param = null))

        val pinnedPeriod = activePeriodId(workspaceId)!!
        val otherPeriod = activePeriodId(otherWorkspaceId)!!

        val holdsPinned = allHoldsInPeriod(pinnedPeriod)
        val holdsOther = allHoldsInPeriod(otherPeriod)
        assertTrue(
            "the billable steps reserved under the PINNED workspace's period",
            holdsPinned.isNotEmpty()
        )
        assertEquals(
            "NOTHING was reserved against the other workspace (scenario 6)",
            0,
            holdsOther.size
        )
        assertTrue(
            "both gates agreed on identity + period: exactly ONE hold per step (a divergence would double-reserve)",
            holdsPinned.size == holdsPinned.map { it.reason }.toSet().size
        )
    }

    @Test
    fun `an explicitly matching task parameter resolves the same pinned period`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(workspace = workspaceId)
        configureWorkspaceBudget(workspace = otherWorkspaceId)

        // (1) The task parameter MATCHES the pinned scope — harmless.
        runTask(taskWithParameter("workspaceId" to workspaceId))

        val pinnedPeriod = activePeriodId(workspaceId)!!
        assertTrue(allHoldsInPeriod(pinnedPeriod).isNotEmpty())
        assertEquals(0, allHoldsInPeriod(activePeriodId(otherWorkspaceId)!!).size)
    }

    // ------------------------------------------------------------------
    // 3. CONFLICTING identities → explicit integrity error
    // ------------------------------------------------------------------

    @Test
    fun `a conflicting task workspace parameter rejects the step explicitly - no hold, no provider call`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(workspace = workspaceId)
        configureWorkspaceBudget(workspace = otherWorkspaceId)

        // (3) The task claims the OTHER workspace while the execution is
        // pinned to workspaceId — an integrity error, never an alternative
        // authority, never a silent fallback.
        val events = runTask(taskWithParameter("workspaceId" to otherWorkspaceId))

        assertEquals("the provider was NEVER invoked", 0, scriptedProvider.invocations.get())
        assertEquals(
            "no reservation was created under the pinned workspace",
            0,
            allHoldsInPeriod(activePeriodId(workspaceId)!!).size
        )
        assertEquals(
            "no reservation was created under the CLAIMED workspace either",
            0,
            allHoldsInPeriod(activePeriodId(otherWorkspaceId)!!).size
        )
        assertEquals("no consumption was posted anywhere", 0, events.count { it is ExecutionEvent.CostRecorded })
    }

    @Test
    fun `the pre-dispatch gate rejects a conflicting task workspace before the provider`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(workspace = workspaceId)

        // Direct production-path proof: executeLlmStep's pre-dispatch gate
        // (inside the pinned ExecutionScope) refuses the conflicting task
        // parameter BEFORE the provider invocation, with the explicit
        // WORKSPACE_IDENTITY_CONFLICT code and a NOT_DISPATCHED state.
        val result = kotlinx.coroutines.withContext(
            com.example.domain.core.execution.ExecutionScope(
                executionId = "exec-conflict",
                workspaceId = workspaceId
            )
        ) {
            executionService.executeAction(
                action = com.example.domain.core.decision.DecisionAction(
                    type = com.example.domain.core.decision.DecisionActionType.EXECUTE_STEP,
                    targetId = "prod-model",
                    decisionRecord = com.example.domain.core.decision.DecisionRecord(
                        selectedResourceId = com.example.domain.core.resource.ResourceId("prod_path_provider"),
                        providerId = scriptedProvider.providerId,
                        serviceId = "prod-model",
                        configurationVersion = 1L,
                        requiredCapabilities = setOf(
                            com.example.domain.core.capability.CapabilityType.LLM_GENERATION
                        ),
                        rationale = "harness",
                        confidence = 0.9f
                    )
                ),
                context = com.example.application.decision.DecisionContext(
                    task = taskWithParameter("workspaceId" to otherWorkspaceId),
                    networkPolicy = com.example.domain.core.network.NetworkPolicy.HYBRID
                ),
                agent = agent,
                executionId = "exec-conflict",
                stepIndex = 0,
                attempt = 1
            )
        }
        assertTrue("the conflicting step failed", !result.isSuccess)
        assertTrue(
            "the failure names the integrity error explicitly",
            result.errorDescription.orEmpty().contains("WORKSPACE_IDENTITY_CONFLICT")
        )
        assertEquals(
            "the rejection is provably pre-boundary",
            UsageDispatchState.NOT_DISPATCHED,
            result.dispatchState
        )
        assertEquals(0, scriptedProvider.invocations.get())
    }

    // ------------------------------------------------------------------
    // 4. Workspace switch between separate executions
    // ------------------------------------------------------------------

    @Test
    fun `workspace switch between separate executions attributes each run to its own workspace`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L, workspace = workspaceId)
        configureWorkspaceBudget(limitMicroUnits = 7_000_000L, workspace = otherWorkspaceId)

        // Run 1 pinned to the harness workspace.
        orchestrator.workspaceIdProvider = { workspaceId }
        runTask(taskWithParameter(param = null))
        val firstPeriod = activePeriodId(workspaceId)!!
        val holdsFirst = allHoldsInPeriod(firstPeriod)
        val entriesFirst = entriesForPeriod(firstPeriod)
        assertTrue("run 1 reserved and posted under its workspace", holdsFirst.isNotEmpty() && entriesFirst.isNotEmpty())

        // Run 2 pinned to the OTHER workspace (a fresh execution).
        orchestrator.workspaceIdProvider = { otherWorkspaceId }
        runTask(taskWithParameter(param = null))
        val secondPeriod = activePeriodId(otherWorkspaceId)!!
        val holdsSecond = allHoldsInPeriod(secondPeriod)
        val entriesSecond = entriesForPeriod(secondPeriod)
        assertTrue("run 2 reserved and posted under ITS workspace", holdsSecond.isNotEmpty() && entriesSecond.isNotEmpty())

        assertNotEquals("the two runs used different periods", firstPeriod, secondPeriod)
        assertEquals(
            "run 1's rows are untouched by run 2 (no leakage across the switch)",
            holdsFirst.size,
            allHoldsInPeriod(firstPeriod).size
        )
        assertEquals(
            "run 1's posted effects are untouched",
            entriesFirst.size,
            entriesForPeriod(firstPeriod).size
        )
        // Scope ownership: every hold of run 1 sits in run 1's period only.
        assertTrue(
            allHoldsInPeriod(firstPeriod).all { it.budgetPeriodId == firstPeriod }
        )
        assertTrue(
            allHoldsInPeriod(secondPeriod).all { it.budgetPeriodId == secondPeriod }
        )
    }

    // ------------------------------------------------------------------
    // 5 (direct). Both gates resolve the same policy + period for one step
    // ------------------------------------------------------------------

    @Test
    fun `decide and pre-dispatch gates resolve one policy and period for the same identity`() = runBlocking {
        seedPricing()
        configureWorkspaceBudget(limitMicroUnits = 5_000_000L, workspace = workspaceId)

        // DECIDE-gate-shaped authorization (what applyEconomicGate issues).
        val decide = governance.authorize(
            EconomicAuthorizationRequest(
                executionId = "exec-parity",
                workspaceId = workspaceId,
                agentId = agent.identity.id.value,
                taskId = "ws-parity-task",
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
        assertEquals(EconomicGateDecision.ALLOWED, decide.decision)

        // PRE-DISPATCH-gate-shaped authorization (what executeLlmStep issues
        // for the SAME identity — currentWorkspaceId() from the pinned scope).
        val preDispatch = kotlinx.coroutines.withContext(
            com.example.domain.core.execution.ExecutionScope(
                executionId = "exec-parity",
                workspaceId = workspaceId
            )
        ) {
            governance.authorize(
                EconomicAuthorizationRequest(
                    executionId = "exec-parity",
                    workspaceId = com.example.domain.core.execution.WorkspaceIdentity
                        .pinnedWorkspaceIdOrNull(
                            kotlinx.coroutines.currentCoroutineContext()[
                                com.example.domain.core.execution.ExecutionScope.Key
                            ]
                        ),
                    agentId = agent.identity.id.value,
                    taskId = "ws-parity-task",
                    providerId = scriptedProvider.providerId,
                    serviceId = "prod-model",
                    modelId = "prod-model",
                    resourceId = "prod_path_provider",
                    isLocalResource = false,
                    expectedTotalTokens = 1_000_000,
                    actionTypeCode = "LLM_STEP",
                    stepIndex = 0,
                    attempt = 1
                )
            )
        }
        assertEquals(
            "the pre-dispatch gate REPLAYED the decide-time reservation (same policy, same period)",
            EconomicGateDecision.ALLOWED,
            preDispatch.decision
        )
        assertTrue("the replay is marked as such", preDispatch.reason.contains("[replay]"))

        // ONE reservation for the step — never two under different periods.
        val period = activePeriodId(workspaceId)!!
        assertEquals(1, allHoldsInPeriod(period).size)
    }
}
