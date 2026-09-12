package com.example.application

import com.example.application.budget.EconomicGovernanceService
import com.example.application.budget.RateLimitGovernor
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.UsageAccountingInput
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.Outcome
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * GAP-05 (Design Closure 2026, ADR-5) — LlmStepEconomicGateTest
 * ============================================================================
 *
 * The audit finding: the economic HARD_LIMIT announced to the user was
 * enforced ONLY at decide-time (DecisionService); the execution layer called
 * providers with NO pre-call gate, so a budget/rate DENY was invisible to
 * execution. ADR-5 prescribes a tryAcquire/blockedUntil-budget gate in
 * ExecutionService.executeLlmStep.
 *
 * This test pins the REAL gate on the REAL execution path:
 *   1. HARD_LIMIT allocation + priced provider + ledger consumption above
 *      the cap → the LLM step FAILS FAST with BUDGET_GATE_DENIED and the
 *      provider is NEVER CALLED;
 *   2. a rate-limited resource scope (RPM window closed) → the step fails
 *      with RATE_LIMITED before the provider call;
 *   3. enforcement failure inside the gate → fail-closed with
 *      ECONOMIC_GATE_UNAVAILABLE (never a silent ALLOW);
 *   4. within-budget execution proceeds normally (no over-blocking).
 */
class LlmStepEconomicGateTest {

    private lateinit var pricing: FakePricingRepository
    private lateinit var ledger: FakeCostLedger
    private lateinit var allocations: FakeBudgetAllocationRepository
    private lateinit var governor: RateLimitGovernor
    private lateinit var economics: EconomicGovernanceService

    @Before
    fun setup() {
        pricing = FakePricingRepository()
        ledger = FakeCostLedger()
        allocations = FakeBudgetAllocationRepository()
        governor = RateLimitGovernor(windowLengthMs = 60_000L)
        economics = EconomicGovernanceService(
            pricingRepository = pricing,
            costLedger = ledger,
            allocationRepository = allocations,
            rateLimitGovernor = governor,
            telemetry = null
        )
    }

    /** Counting fake LLM provider — proves whether the gate fired pre-call. */
    private class CountingLlmProvider : LlmProviderPort {
        var callCount = 0
        override val providerId: String = "gate_llm"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "gate_llm", name = "Gate", providerType = "FAKE",
            defaultModel = "gate-1", isConfigured = true, isOnline = true, isLocal = false
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            callCount++
            return Outcome.Success(
                LlmResponse(text = "ok", usage = TokenUsage(10, 10), finishReason = "STOP", modelId = "gate-1")
            )
        }

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            callCount++
            emit(ExecutionEvent.ContentChunk(executionId = executionId, deltaText = "ok", sequenceIndex = 0))
            emit(ExecutionEvent.Completed(executionId = executionId, finalText = "ok", totalDurationMs = 1))
        }
    }

    private fun buildExecutionService(provider: CountingLlmProvider): ExecutionService {
        val registry = ComponentRegistry()
        TestResourceRegistration.registerLlmProvider(registry, provider)
        return ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        )
    }

    private fun generateAction(): DecisionAction = DecisionAction(
        type = DecisionActionType.SELECT_MODEL,
        targetId = "gate_llm",
        payload = emptyMap(),
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId("gate_llm"),
            providerId = "gate_llm",
            serviceId = "gate_llm",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            rationale = "test",
            confidence = 0.9f
        )
    )

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent-gate"),
            name = "Gate Agent",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "gate test agent",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
        budget = AgentBudget(maxTokens = 30000)
    )

    private fun newTask(): TaskDefinition = TaskDefinition(
        id = TaskId("task-gate"),
        assignedAgentId = testAgent.identity.id,
        input = TaskInput(rawPrompt = "hello"),
        budget = TaskBudget(tokenLimit = 30000)
    )

    private suspend fun seedPricing() {
        economics.upsertPricing(
            PricingEntry(
                id = "p-gate",
                scope = PricingScope.PROVIDER,
                providerId = "gate_llm",
                inputPricePerMillion = MoneyAmount.of(3_000_000L, "USD"),
                outputPricePerMillion = MoneyAmount.of(15_000_000L, "USD"),
                billingClass = BillingClass.PAID,
                pricingVersion = "v1",
                effectiveFromEpochMs = 0L,
                provenance = "TEST"
            )
        )
    }

    @Test
    fun `hard limit denies the LLM step before the provider is called`() = runBlocking {
        seedPricing()
        economics.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-gate"),
            allocated = MoneyAmount.of(100_000L, "USD"), // 0.10 USD
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.HARD_LIMIT))
        )
        // Warm the ledger ABOVE the allocation: 100k tokens * 3 USD/1M = 0.30 USD.
        economics.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-warmup",
                taskId = null, workspaceId = "ws-gate", agentId = null,
                providerId = "gate_llm", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 100_000, outputTokens = 0),
                isActualProviderReport = true
            )
        )

        val provider = CountingLlmProvider()
        val es = buildExecutionService(provider)
        es.economicGovernance = economics

        val events = mutableListOf<ExecutionEvent>()
        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-gate", "ws-gate")) {
            es.executeAction(
                action = generateAction(),
                context = com.example.application.decision.DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-gate",
                onEvent = { events.add(it) }
            )
        }

        assertFalse("GAP-05: the step must FAIL under a hard limit", result.isSuccess)
        assertTrue(
            "GAP-05: failure must carry the honest code, was: ${result.errorDescription}",
            result.errorDescription?.startsWith("BUDGET_GATE_DENIED") == true
        )
        assertEquals("GAP-05: the provider must NOT be called after a budget deny", 0, provider.callCount)
        assertTrue(
            "GAP-05: an Error event with BUDGET_GATE_DENIED must be emitted",
            events.any { it is ExecutionEvent.Error && it.failureCode == "BUDGET_GATE_DENIED" }
        )
    }

    @Test
    fun `rate-limited resource scope denies the step before the provider is called`() = runBlocking {
        // Configure RPM=1 on the resource scope the gate consults
        // (RESOURCE:<resourceId>) and consume the single slot.
        governor.configureLimits("RESOURCE:gate_llm", 1, null)
        governor.tryAcquire("RESOURCE:gate_llm")

        val provider = CountingLlmProvider()
        val es = buildExecutionService(provider)
        es.economicGovernance = economics

        val events = mutableListOf<ExecutionEvent>()
        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-rate", "ws-gate")) {
            es.executeAction(
                action = generateAction(),
                context = com.example.application.decision.DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-rate",
                onEvent = { events.add(it) }
            )
        }

        assertFalse(result.isSuccess)
        assertTrue(
            "GAP-05: failure must carry RATE_LIMITED, was: ${result.errorDescription}",
            result.errorDescription?.startsWith("RATE_LIMITED") == true
        )
        assertEquals(0, provider.callCount)
        assertTrue(events.any { it is ExecutionEvent.Error && it.failureCode == "RATE_LIMITED" })
    }

    @Test
    fun `enforcement failure inside the gate fails closed`() = runBlocking {
        val provider = CountingLlmProvider()
        val es = buildExecutionService(provider)
        // A gate that THROWS — enforcement infrastructure broken.
        val broken = EconomicGovernanceService(
            pricingRepository = pricing,
            costLedger = ledger,
            allocationRepository = object : com.example.domain.ports.budget.BudgetAllocationPort {
                override suspend fun allocationFor(scope: BudgetScope): com.example.domain.core.budget.BudgetAllocation? =
                    throw IllegalStateException("allocation store offline")
                override suspend fun upsertAllocation(allocation: com.example.domain.core.budget.BudgetAllocation) = Unit
                override suspend fun allocationsForType(scopeType: BudgetScopeType): List<com.example.domain.core.budget.BudgetAllocation> = emptyList()
                override suspend fun allAllocations(): List<com.example.domain.core.budget.BudgetAllocation> = emptyList()
                override suspend fun deactivateAllocation(scope: BudgetScope) = Unit
                override suspend fun allocatedTotal(scopeType: BudgetScopeType, currency: String): MoneyAmount =
                    MoneyAmount.of(0L, currency)
                override fun observeAllocations(): Flow<List<com.example.domain.core.budget.BudgetAllocation>> = kotlinx.coroutines.flow.flowOf(emptyList())
            },
            rateLimitGovernor = governor,
            telemetry = null
        )
        es.economicGovernance = broken

        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-broken", "ws-gate")) {
            es.executeAction(
                action = generateAction(),
                context = com.example.application.decision.DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-broken",
                onEvent = { }
            )
        }

        assertFalse("GAP-05: enforcement error must FAIL CLOSED", result.isSuccess)
        assertTrue(
            "GAP-05: honest code ECONOMIC_GATE_UNAVAILABLE, was: ${result.errorDescription}",
            result.errorDescription?.startsWith("ECONOMIC_GATE_UNAVAILABLE") == true
        )
        assertEquals(0, provider.callCount)
    }

    @Test
    fun `within-budget execution proceeds normally`() = runBlocking {
        seedPricing()
        economics.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-gate"),
            allocated = MoneyAmount.of(1_000_000_000L, "USD"), // plenty
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.HARD_LIMIT))
        )

        val provider = CountingLlmProvider()
        val es = buildExecutionService(provider)
        es.economicGovernance = economics

        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-ok", "ws-gate")) {
            es.executeAction(
                action = generateAction(),
                context = com.example.application.decision.DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-ok",
                onEvent = { }
            )
        }

        assertTrue("GAP-05: within-budget step must proceed", result.isSuccess)
        assertEquals(1, provider.callCount)
    }
}
