package com.example.application.budget

import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * EconomicGovernanceServiceTest — GOVERNANCE PHASE budget track
 * ============================================================================
 *
 * Covers (mission Section 13 BUDGET requirements):
 *   - token usage accounting (estimated vs actual, cached tokens)
 *   - billing classification (FREE/PAID/LOCAL/UNKNOWN; LOCAL != FREE)
 *   - model/provider pricing resolution + MODEL-over-PROVIDER precedence
 *   - pricing version handling (effective time windows)
 *   - cost calculation (integer micro math)
 *   - unknown pricing -> UNKNOWN cost (never zero)
 *   - hierarchical budget scopes + remaining budget
 *   - HARD_LIMIT / SOFT_LIMIT / AUTO_DOWNGRADE / AUTO_LOCAL_FALLBACK / REQUIRE_APPROVAL
 *   - RPM / TPM rate limits (independent from budget)
 */
class EconomicGovernanceServiceTest {

    private lateinit var pricing: FakePricingRepository
    private lateinit var ledger: FakeCostLedger
    private lateinit var allocations: FakeBudgetAllocationRepository
    private lateinit var governor: RateLimitGovernor
    private lateinit var service: EconomicGovernanceService

    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        pricing = FakePricingRepository()
        ledger = FakeCostLedger()
        allocations = FakeBudgetAllocationRepository()
        governor = RateLimitGovernor(windowLengthMs = 60_000L)
        service = EconomicGovernanceService(
            pricingRepository = pricing,
            costLedger = ledger,
            allocationRepository = allocations,
            rateLimitGovernor = governor,
            telemetry = null
        )
    }

    private fun providerPricing(
        providerId: String = "prov-a",
        inputUsdPerMillion: Double = 3.0,
        outputUsdPerMillion: Double = 15.0,
        billingClass: BillingClass = BillingClass.PAID
    ): PricingEntry = PricingEntry(
        id = "p-$providerId",
        scope = PricingScope.PROVIDER,
        providerId = providerId,
        inputPricePerMillion = MoneyAmount.of((inputUsdPerMillion * 1e6).toLong(), "USD"),
        outputPricePerMillion = MoneyAmount.of((outputUsdPerMillion * 1e6).toLong(), "USD"),
        billingClass = billingClass,
        pricingVersion = "v1",
        effectiveFromEpochMs = 0L,
        provenance = "TEST"
    )

    private fun modelPricing(
        providerId: String = "prov-a",
        modelId: String = "model-flash",
        inputUsdPerMillion: Double = 0.5,
        outputUsdPerMillion: Double = 2.0
    ): PricingEntry = PricingEntry(
        id = "p-$providerId-$modelId",
        scope = PricingScope.MODEL,
        providerId = providerId,
        serviceId = "svc-1",
        modelId = modelId,
        inputPricePerMillion = MoneyAmount.of((inputUsdPerMillion * 1e6).toLong(), "USD"),
        outputPricePerMillion = MoneyAmount.of((outputUsdPerMillion * 1e6).toLong(), "USD"),
        billingClass = BillingClass.PAID,
        pricingVersion = "v1",
        effectiveFromEpochMs = 0L,
        provenance = "TEST"
    )

    private fun authRequest(
        workspaceId: String? = "ws-1",
        taskId: String? = "task-1",
        agentId: String? = "agent-1",
        providerId: String? = "prov-a",
        expectedTokens: Int? = null
    ): EconomicAuthorizationRequest = EconomicAuthorizationRequest(
        executionId = "exec-1",
        workspaceId = workspaceId,
        agentId = agentId,
        taskId = taskId,
        providerId = providerId,
        serviceId = "svc-1",
        modelId = null,
        resourceId = null,
        isLocalResource = false,
        expectedTotalTokens = expectedTokens,
        actionTypeCode = "execute_step"
    )

    // ------------------------------------------------------------------
    // PRICING RESOLUTION
    // ------------------------------------------------------------------

    @Test
    fun `model pricing overrides provider pricing`() = runBlocking {
        service.upsertPricing(providerPricing())          // provider: 3.0 / 15.0
        service.upsertPricing(modelPricing())              // model:     0.5 / 2.0
        val resolution = service.resolvePricing("prov-a", "svc-1", "model-flash")
        assertTrue(!resolution.isUnknown)
        assertEquals(PricingScope.MODEL, resolution.matchedScope)
        assertEquals(0.5e6.toLong(), resolution.resolvedEntry?.inputPricePerMillion?.amountMicro)
    }

    @Test
    fun `provider pricing applies when no model entry matches`() = runBlocking {
        service.upsertPricing(providerPricing())
        val resolution = service.resolvePricing("prov-a", "svc-1", "some-other-model")
        assertTrue(!resolution.isUnknown)
        assertEquals(PricingScope.PROVIDER, resolution.matchedScope)
    }

    @Test
    fun `no pricing entry yields UNKNOWN never fabricated`() = runBlocking {
        val resolution = service.resolvePricing("unknown-provider", null, null)
        assertTrue(resolution.isUnknown)
        assertNull(resolution.resolvedEntry)
    }

    @Test
    fun `expired pricing version is not effective`() = runBlocking {
        val expired = providerPricing().copy(
            effectiveFromEpochMs = now - 10_000,
            effectiveToEpochMs = now - 5_000
        )
        service.upsertPricing(expired)
        val resolution = service.resolvePricing("prov-a", null, null)
        assertTrue(resolution.isUnknown)
    }

    // ------------------------------------------------------------------
    // BILLING CLASSIFICATION
    // ------------------------------------------------------------------

    @Test
    fun `local resource is LOCAL billing class even without pricing`() = runBlocking {
        val cls = service.billingClassFor("anything", null, null, isLocalResource = true)
        assertEquals(BillingClass.LOCAL, cls)
    }

    @Test
    fun `priced remote resource takes entry billing class`() = runBlocking {
        service.upsertPricing(providerPricing(billingClass = BillingClass.PAID))
        val cls = service.billingClassFor("prov-a", "svc-1", null, isLocalResource = false)
        assertEquals(BillingClass.PAID, cls)
    }

    @Test
    fun `unpriced remote resource is UNKNOWN billing class`() = runBlocking {
        val cls = service.billingClassFor("prov-x", null, null, isLocalResource = false)
        assertEquals(BillingClass.UNKNOWN, cls)
    }

    // ------------------------------------------------------------------
    // USAGE ACCOUNTING + COST CALCULATION
    // ------------------------------------------------------------------

    @Test
    fun `accountUsage with pricing records ACTUAL cost with exact micro math`() = runBlocking {
        service.upsertPricing(providerPricing(inputUsdPerMillion = 3.0, outputUsdPerMillion = 15.0))
        val record = service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-1",
                taskId = "task-1",
                workspaceId = "ws-1",
                agentId = "agent-1",
                providerId = "prov-a",
                serviceId = "svc-1",
                modelId = "m-1",
                resourceId = "res-1",
                usage = TokenUsageRecord(
                    inputTokens = 100_000,
                    outputTokens = 50_000,
                    cachedTokens = 0,
                    isEstimate = false
                ),
                isActualProviderReport = true
            )
        )
        assertNotNull(record)
        assertEquals(CostStatus.ACTUAL, record!!.costStatus)
        // 100k input * 3.0/1M + 50k output * 15.0/1M = 0.30 + 0.75 = 1.05 USD
        assertEquals(1_050_000L, record.cost?.amountMicro)
        assertEquals("USD", record.cost?.currency)
        assertEquals(BillingClass.PAID, record.billingClass)
    }

    @Test
    fun `heuristic usage is recorded as ESTIMATED`() = runBlocking {
        service.upsertPricing(providerPricing())
        val record = service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-2",
                taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 1000, outputTokens = 500, isEstimate = true),
                isActualProviderReport = false
            )
        )
        assertEquals(CostStatus.ESTIMATED, record!!.costStatus)
    }

    @Test
    fun `unknown pricing records UNKNOWN cost never zero`() = runBlocking {
        val record = service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-3",
                taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov-unknown", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 1000, outputTokens = 500),
                isActualProviderReport = true
            )
        )
        assertNotNull(record)
        assertEquals(CostStatus.UNKNOWN, record!!.costStatus)
        assertNull(record.cost?.amountMicro) // UNKNOWN, not fabricated 0
        assertEquals(BillingClass.UNKNOWN, record.billingClass)
    }

    @Test
    fun `local resource cost is UNKNOWN and billing LOCAL`() = runBlocking {
        val record = service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-4",
                taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "local_embedding_engine", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 1000, outputTokens = 0),
                isActualProviderReport = true
            )
        )
        assertEquals(BillingClass.LOCAL, record!!.billingClass)
        assertEquals(CostStatus.UNKNOWN, record.costStatus)
    }

    @Test
    fun `cached tokens ride the cached price when provided`() = runBlocking {
        val entry = providerPricing().copy(
            cachedInputPricePerMillion = MoneyAmount.of((0.375 * 1e6).toLong(), "USD")
        )
        service.upsertPricing(entry)
        val record = service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-5",
                taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 10_000, outputTokens = 0, cachedTokens = 10_000),
                isActualProviderReport = true
            )
        )
        // input 10k*3.0/1M = 0.03 ; cached 10k*0.375/1M = 0.00375 → total 33750 micro
        assertEquals(33_750L, record!!.cost?.amountMicro)
    }

    // ------------------------------------------------------------------
    // HIERARCHICAL BUDGETS + POLICIES
    // ------------------------------------------------------------------

    @Test
    fun `no allocation means allowed and cost still tracked`() = runBlocking {
        val result = service.authorize(authRequest())
        assertEquals(EconomicGateDecision.ALLOWED, result.decision)
        assertTrue(result.evaluatedScopes.all { it.hasNoAllocation })
    }

    @Test
    fun `hard limit denies when projected spend exceeds allocation`() = runBlocking {
        service.upsertPricing(providerPricing())
        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(100_000L, "USD"), // 0.10 USD
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.HARD_LIMIT))
        )
        // Each call costs 100k*3/1M = 0.30 USD
        repeat(2) {
            service.accountUsageSuspend(
                UsageAccountingInput(
                    executionId = "exec-warmup",
                    taskId = null, workspaceId = "ws-1", agentId = null,
                    providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                    usage = TokenUsageRecord(inputTokens = 100_000, outputTokens = 0),
                    isActualProviderReport = true
                )
            )
        }
        // consumed 0.6 USD > 0.1 USD allocation
        val result = service.authorize(authRequest(workspaceId = "ws-1"))
        assertEquals(EconomicGateDecision.DENIED, result.decision)
        assertEquals(BudgetPolicyAction.HARD_LIMIT, result.appliedPolicy)
    }

    @Test
    fun `soft limit warns but does not deny`() = runBlocking {
        service.upsertPricing(providerPricing())
        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(1_000_000L, "USD"),
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.SOFT_LIMIT))
        )
        // 300k input * 3.0/1M = 0.9 USD of the 1.0 allocation → 90% ratio.
        service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-x", taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 300_000, outputTokens = 0),
                isActualProviderReport = true
            )
        )
        val status = service.workspaceBudgetSummary("ws-1")
        assertTrue(status.utilizationRatio!! > 0.8f)
        val result = service.authorize(authRequest(workspaceId = "ws-1"))
        assertEquals(EconomicGateDecision.WARNED, result.decision)
    }

    @Test
    fun `require approval policy surfaces APPROVAL_REQUIRED`() = runBlocking {
        service.upsertPricing(providerPricing())
        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(100_000L, "USD"),
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.REQUIRE_APPROVAL), warnThresholdRatio = 0.01f)
        )
        service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-y", taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 100_000, outputTokens = 0),
                isActualProviderReport = true
            )
        )
        val result = service.authorize(authRequest(workspaceId = "ws-1"))
        assertEquals(EconomicGateDecision.APPROVAL_REQUIRED, result.decision)
    }

    @Test
    fun `auto downgrade and local fallback policies map to their decisions`() = runBlocking {
        service.upsertPricing(providerPricing())
        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(100_000L, "USD"),
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.AUTO_DOWNGRADE), warnThresholdRatio = 0.01f)
        )
        service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-z", taskId = null, workspaceId = "ws-1", agentId = null,
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 100_000, outputTokens = 0),
                isActualProviderReport = true
            )
        )
        assertEquals(
            EconomicGateDecision.DOWNGRADE,
            service.authorize(authRequest(workspaceId = "ws-1")).decision
        )

        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.of(100_000L, "USD"),
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.AUTO_LOCAL_FALLBACK), warnThresholdRatio = 0.01f)
        )
        assertEquals(
            EconomicGateDecision.LOCAL_FALLBACK,
            service.authorize(authRequest(workspaceId = "ws-1")).decision
        )
    }

    @Test
    fun `unknown amount allocation is a track-only ceiling that never denies`() = runBlocking {
        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1"),
            allocated = MoneyAmount.unknown("USD"),
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.HARD_LIMIT))
        )
        repeat(5) {
            service.accountUsageSuspend(
                UsageAccountingInput(
                    executionId = "exec-t", taskId = null, workspaceId = "ws-1", agentId = null,
                    providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                    usage = TokenUsageRecord(inputTokens = 1_000_000, outputTokens = 0),
                    isActualProviderReport = true
                )
            )
        }
        val result = service.authorize(authRequest(workspaceId = "ws-1"))
        assertEquals(EconomicGateDecision.ALLOWED, result.decision)
    }

    @Test
    fun `agent scope budget is evaluated hierarchically`() = runBlocking {
        service.upsertPricing(providerPricing())
        service.setAllocation(
            scope = BudgetScope(BudgetScopeType.AGENT, "agent-1"),
            allocated = MoneyAmount.of(200_000L, "USD"), // 0.2 USD
            policy = BudgetPolicy(actions = listOf(BudgetPolicyAction.HARD_LIMIT))
        )
        service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-a", taskId = null, workspaceId = "ws-1", agentId = "agent-1",
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 100_000, outputTokens = 0),
                isActualProviderReport = true
            )
        )
        val result = service.authorize(authRequest(workspaceId = "ws-1", agentId = "agent-1"))
        assertEquals(EconomicGateDecision.DENIED, result.decision)
        assertTrue(result.evaluatedScopes.any { it.scope.scopeType == BudgetScopeType.AGENT })
    }

    // ------------------------------------------------------------------
    // RATE LIMITS (independent from budget)
    // ------------------------------------------------------------------

    @Test
    fun `rate limited provider scope denies regardless of budget state`() = runBlocking {
        governor.configureLimits("PROVIDER:prov-a", rpmLimit = 2, tpmLimit = null)
        governor.recordRequest("PROVIDER:prov-a", 100)
        governor.recordRequest("PROVIDER:prov-a", 100)
        val result = service.authorize(authRequest())
        assertEquals(EconomicGateDecision.DENIED, result.decision)
        assertTrue(result.reason.contains("RATE_LIMITED"))
        assertNotNull(result.rateLimitStatus)
    }

    @Test
    fun `tpm limit counts tokens independent from rpm`() = runBlocking {
        governor.configureLimits("PROVIDER:prov-b", rpmLimit = null, tpmLimit = 1_000)
        governor.recordRequest("PROVIDER:prov-b", 600)
        val status = governor.statusFor("PROVIDER:prov-b")
        assertEquals(600L, status.tpmUsed)
        assertFalse(status.isRateLimited)
        governor.recordRequest("PROVIDER:prov-b", 500) // total 1100 > 1000
        assertTrue(governor.statusFor("PROVIDER:prov-b").isRateLimited)
    }

    @Test
    fun `token budget and tpm capacity remain distinct concepts`() = runBlocking {
        governor.configureLimits("PROVIDER:prov-d", rpmLimit = null, tpmLimit = 10_000)
        val result = service.authorize(authRequest(providerId = "prov-d", expectedTokens = 1_000))
        assertEquals(EconomicGateDecision.ALLOWED, result.decision)
        assertNotNull(result.rateLimitStatus)
        assertEquals(10_000, result.rateLimitStatus?.tpmLimit)
        // Token quota untouched here — no task budget was consulted.
        assertTrue(result.evaluatedScopes.all { it.hasNoAllocation })
    }

    // ------------------------------------------------------------------
    // COST ESTIMATION
    // ------------------------------------------------------------------

    @Test
    fun `estimate is UNKNOWN without pricing`() = runBlocking {
        val estimate = service.estimateCost("prov-unknown", null, null, expectedTotalTokens = 5000)
        assertTrue(estimate.isUnknown)
        assertNull(estimate.expectedCost)
    }

    @Test
    fun `estimate uses history average when available`() = runBlocking {
        service.upsertPricing(providerPricing())
        service.accountUsageSuspend(
            UsageAccountingInput(
                executionId = "exec-h", taskId = null, workspaceId = null, agentId = null,
                providerId = "prov-a", serviceId = null, modelId = null, resourceId = null,
                usage = TokenUsageRecord(inputTokens = 4_000, outputTokens = 4_000),
                isActualProviderReport = true
            )
        )
        val estimate = service.estimateCost("prov-a", null, null, expectedTotalTokens = null)
        assertEquals("HISTORY_AVERAGE", estimate.basis)
        assertEquals(8_000, estimate.expectedTotalTokens)
        // 3:1 split → 6000 input * 3.0/1M + 2000 * 15/1M = 0.018 + 0.03 = 0.048 USD
        assertEquals(48_000L, estimate.expectedCost?.amountMicro)
    }
}
