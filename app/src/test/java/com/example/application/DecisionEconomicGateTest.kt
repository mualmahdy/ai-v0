package com.example.application

import com.example.application.budget.EconomicGovernanceService
import com.example.application.decision.DecisionService
import com.example.application.radar.CapabilityRadarService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
import com.example.application.testing.FakeRadarPersistence
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * DecisionEconomicGateTest — Decision Engine budget/capability integration
 * ============================================================================
 *
 * GOVERNANCE PHASE: proves the decision flow order
 *   Permission/Policy -> Capability -> Budget -> Rate -> Risk
 * is enforced INSIDE DecisionService.evaluate: a budget denial REPLANs or
 * pauses with ASK_USER BEFORE any provider call happens, and a capability
 * BLOCKED state produces an explicit REPLAN rather than silent execution.
 */
class DecisionEconomicGateTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var economics: EconomicGovernanceService
    private lateinit var radar: CapabilityRadarService
    private lateinit var decisionService: DecisionService

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("gate_agent"),
            name = "Gate Agent",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "Test",
            systemPrompt = "Test"
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
        budget = AgentBudget()
    )

    private var providerCallCount = 0

    private val pricedProvider = object : LlmProviderPort {
        override val providerId: String = "priced_provider"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "priced_provider", name = "Priced", providerType = "MOCK",
            defaultModel = "priced-model", isConfigured = true, isOnline = true, isLocal = false
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            providerCallCount++
            return Outcome.Success(
                LlmResponse(text = "ok", usage = TokenUsage(10, 10), modelId = "priced-model")
            )
        }

        override fun stream(request: LlmRequest, executionId: String): Flow<com.example.domain.core.events.ExecutionEvent> =
            flow {
                providerCallCount++
                emit(
                    com.example.domain.core.events.ExecutionEvent.UsageBudgetUpdate(
                        executionId = executionId,
                        promptTokens = 10,
                        completionTokens = 10,
                        totalSessionTokens = 20,
                        remainingBudgetTokens = com.example.domain.core.events.ExecutionEvent.UsageBudgetUpdate.REMAINING_UNKNOWN,
                        providerId = providerId,
                        modelId = "priced-model"
                    )
                )
            }
    }

    @Before
    fun setup() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        providerCallCount = 0

        economics = EconomicGovernanceService(
            pricingRepository = FakePricingRepository(),
            costLedger = FakeCostLedger(),
            allocationRepository = FakeBudgetAllocationRepository(),
            rateLimitGovernor = com.example.application.budget.RateLimitGovernor(),
            telemetry = null
        )
        radar = CapabilityRadarService(
            persistence = FakeRadarPersistence(),
            resourceSnapshotProvider = { emptyList() },
            embeddingSemanticProvisioned = { null },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )

        val engine = CbrMdpEngine()
        decisionService = DecisionService(
            cbrMdpEngine = engine,
            resourceCapabilityGraph = registry.resourceCapabilityGraph,
            securityGuard = securityGuard,
            agentCatalog = { listOf(testAgent) },
            economicGovernance = economics,
            capabilityRadar = radar
        )

        // Register the priced LLM resource as usable.
        TestResourceRegistration.registerLlmProvider(registry, pricedProvider)
        // Publish pricing: 3.0 USD / 1M input, 15.0 / 1M output.
        runBlocking {
            economics.upsertPricing(
                PricingEntry(
                    id = "p-priced",
                    scope = PricingScope.PROVIDER,
                    providerId = "priced_provider",
                    inputPricePerMillion = MoneyAmount.of(3_000_000L, "USD"),
                    outputPricePerMillion = MoneyAmount.of(15_000_000L, "USD"),
                    billingClass = com.example.domain.core.budget.BillingClass.PAID,
                    pricingVersion = "v1",
                    effectiveFromEpochMs = 0L,
                    provenance = "TEST"
                )
            )
        }
    }

    private fun task(prompt: String = "analyze this") = TaskDefinition(
        id = TaskId("gate-task"),
        assignedAgentId = testAgent.identity.id,
        // Step 2: skips the step-0-only SELECT_AGENT candidate so the engine
        // selects a resource-backed (gated) action.
        currentStepIndex = 2,
        input = TaskInput(
            rawPrompt = prompt,
            parameters = mapOf("workspaceId" to "ws-gate")
        ),
        requirements = com.example.domain.core.task.TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION)
        )
    )

    @Test
    fun `budget hard limit REPLANs the chosen action instead of executing`() = runBlocking {
        // Exhaust the workspace budget: allocate 0.05 USD and consume 0.09.
        economics.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-gate"),
            allocated = MoneyAmount.of(50_000L, "USD"),
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.HARD_LIMIT))
        )
        economics.accountUsageSuspend(
            com.example.domain.core.budget.UsageAccountingInput(
                executionId = "warmup",
                taskId = null, workspaceId = "ws-gate", agentId = null,
                providerId = "priced_provider", serviceId = null, modelId = null, resourceId = null,
                usage = com.example.domain.core.budget.TokenUsageRecord(
                    inputTokens = 20_000, outputTokens = 10_000
                ),
                isActualProviderReport = true
            )
        )
        val callsBefore = providerCallCount

        val context = decisionService.buildDecisionContext(task = task())
        val result = decisionService.evaluate(context)

        assertTrue(
            "budget denial must alter the action (REPLAN/ASK_USER), got ${result.chosenAction.type}",
            result.chosenAction.type == DecisionActionType.REPLAN ||
                result.chosenAction.type == DecisionActionType.ASK_USER
        )
        if (result.chosenAction.type == DecisionActionType.REPLAN) {
            assertEquals("BUDGET_DENIED", result.chosenAction.payload["reason"])
        }
        // THE critical assertion: no paid provider call happened after denial.
        assertEquals(callsBefore, providerCallCount)
    }

    @Test
    fun `require approval policy pauses with ASK_USER before spending`() = runBlocking {
        economics.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-gate"),
            allocated = MoneyAmount.of(10_000L, "USD"),
            policy = BudgetPolicy(
                actions = listOf(BudgetPolicyAction.REQUIRE_APPROVAL),
                warnThresholdRatio = 0.01f
            )
        )
        economics.accountUsageSuspend(
            com.example.domain.core.budget.UsageAccountingInput(
                executionId = "warmup2",
                taskId = null, workspaceId = "ws-gate", agentId = null,
                providerId = "priced_provider", serviceId = null, modelId = null, resourceId = null,
                usage = com.example.domain.core.budget.TokenUsageRecord(
                    inputTokens = 10_000, outputTokens = 10_000
                ),
                isActualProviderReport = true
            )
        )
        val context = decisionService.buildDecisionContext(task = task())
        val result = decisionService.evaluate(context)
        assertEquals(DecisionActionType.ASK_USER, result.chosenAction.type)
        assertTrue(result.chosenAction.payload["reason"]?.contains("BUDGET_APPROVAL_REQUIRED") == true)
        assertEquals(0, providerCallCount)
    }

    @Test
    fun `capability radar FAILED state REPLANs instead of executing`() = runBlocking {
        // Push the radar into FAILED for LLM_GENERATION via failure evidence
        // with no prior success in this workspace.
        val persistence = FakeRadarPersistence()
        persistence.insertEvidenceAll(
            listOf(
                com.example.domain.core.radar.CapabilityEvidence(
                    id = "ev-f1",
                    capabilityKey = CapabilityType.LLM_GENERATION.code,
                    source = com.example.domain.core.radar.EvidenceSource.ACTION_EXECUTION,
                    outcome = com.example.domain.core.radar.EvidenceOutcome.FAILURE,
                    timestampEpochMs = System.currentTimeMillis(),
                    workspaceId = "ws-gate"
                ),
                com.example.domain.core.radar.CapabilityEvidence(
                    id = "ev-f2",
                    capabilityKey = CapabilityType.LLM_GENERATION.code,
                    source = com.example.domain.core.radar.EvidenceSource.ACTION_EXECUTION,
                    outcome = com.example.domain.core.radar.EvidenceOutcome.FAILURE,
                    timestampEpochMs = System.currentTimeMillis(),
                    workspaceId = "ws-gate"
                )
            )
        )
        // Derive with the registry resource marked usable so FAILED comes
        // from evidence, not from resource absence.
        radar = CapabilityRadarService(
            persistence = persistence,
            resourceSnapshotProvider = { registry.resourceRegistry.listResources() },
            embeddingSemanticProvisioned = { null },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        val engine = CbrMdpEngine()
        decisionService = DecisionService(
            cbrMdpEngine = engine,
            resourceCapabilityGraph = registry.resourceCapabilityGraph,
            securityGuard = securityGuard,
            agentCatalog = { listOf(testAgent) },
            economicGovernance = economics,
            capabilityRadar = radar
        )

        val context = decisionService.buildDecisionContext(task = task())
        val result = decisionService.evaluate(context)
        assertEquals(DecisionActionType.REPLAN, result.chosenAction.type)
        assertTrue(result.chosenAction.payload["reason"]?.startsWith("CAPABILITY_") == true)
        assertEquals(0, providerCallCount)
    }

    @Test
    fun `offline security policy still outranks budget allow - no silent bypass`() = runBlocking {
        // OFFLINE policy + non-local provider: enforceGovernance must reject
        // before any budget consideration, and execution must not happen.
        val context = decisionService.buildDecisionContext(
            task = task(),
            networkPolicy = com.example.domain.core.network.NetworkPolicy.OFFLINE,
            isNetworkAvailable = false
        )
        val result = decisionService.evaluate(context)
        assertEquals(0, providerCallCount)
        assertTrue(result.chosenAction.type != DecisionActionType.SELECT_MODEL)
    }

    @Test
    fun `allowed budget proceeds to a normal executable action`() = runBlocking {
        // No allocation at all → allowed; the chosen action should be a real
        // resource-backed action (not a budget REPLAN).
        val context = decisionService.buildDecisionContext(task = task())
        val result = decisionService.evaluate(context)
        assertTrue(
            result.chosenAction.payload["reason"] != "BUDGET_DENIED" &&
                result.chosenAction.type != DecisionActionType.ASK_USER
        )
    }
}
