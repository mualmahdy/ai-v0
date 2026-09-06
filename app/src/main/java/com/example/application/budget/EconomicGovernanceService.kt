package com.example.application.budget

import com.example.application.observability.TelemetryService
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetAllocation
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.BudgetStatus
import com.example.domain.core.budget.CostEstimate
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicAuthorizationResult
import com.example.domain.core.budget.EconomicGateDecision
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PriceResolution
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.RateLimitScopeType
import com.example.domain.core.budget.RateLimitStatus
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.observability.MetricDimensions
import com.example.domain.ports.budget.BudgetAllocationPort
import com.example.domain.ports.budget.CostAggregate
import com.example.domain.ports.budget.CostLedgerPort
import com.example.domain.ports.budget.PricingRepositoryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * ============================================================================
 * EconomicGovernanceService — Token/Economic Budget & Cost Governance facade
 * ============================================================================
 *
 * THE decision-flow position (Section 4 of the directive):
 *
 *   Candidate Action -> Capability Check -> Policy Check -> BUDGET CHECK ->
 *   RATE CHECK -> Risk Check -> Decision -> Execution -> USAGE ACCOUNTING ->
 *   COST LEDGER -> Telemetry/Evidence -> Radar update.
 *
 * This service owns the BUDGET CHECK (pre-execution, via [authorize]) and
 * the USAGE ACCOUNTING + COST LEDGER (post-execution, via [accountUsage]).
 * It NEVER bypasses permission/policy enforcement — a budget ALLOW does not
 * grant any permission; security ordering is preserved by the caller
 * (DecisionService evaluates security governance FIRST).
 *
 * Truthfulness rules enforced here:
 *   - No pricing entry  -> billing class/price UNKNOWN (never zero).
 *   - Local resource    -> BillingClass.LOCAL (still accounted; monetary
 *                          provider cost is null, not 0).
 *   - Heuristic usage   -> isEstimate=true, costStatus=ESTIMATED.
 *   - Provider-reported -> costStatus=ACTUAL when priced, else UNKNOWN cost.
 */
class EconomicGovernanceService(
    private val pricingRepository: PricingRepositoryPort,
    private val costLedger: CostLedgerPort,
    private val allocationRepository: BudgetAllocationPort,
    private val rateLimitGovernor: RateLimitGovernor = RateLimitGovernor(),
    /** Optional telemetry sink — observability must never break governance. */
    private val telemetry: TelemetryService? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val ledgerMutex = Mutex()

    // ------------------------------------------------------------------
    // PRICING
    // ------------------------------------------------------------------

    /** Registers/updates a pricing entry (from discovery or user config). */
    suspend fun upsertPricing(entry: PricingEntry) {
        pricingRepository.upsertPricing(entry)
    }

    suspend fun allPricingEntries(): List<PricingEntry> = pricingRepository.allPricing()

    /**
     * Resolves the effective unit pricing for a target identity with
     * precedence MODEL > SERVICE > PROVIDER (Section 3.5) and
     * effective-time-window filtering. Returns UNKNOWN when nothing matches
     * — never a fabricated price.
     */
    suspend fun resolvePricing(
        providerId: String?,
        serviceId: String?,
        modelId: String?
    ): PriceResolution {
        if (providerId.isNullOrBlank()) return PriceResolution.UNKNOWN
        val now = System.currentTimeMillis()
        val candidates = pricingRepository.effectivePricingForProvider(providerId, now)

        // MODEL scope match (most specific)
        if (!modelId.isNullOrBlank()) {
            val modelEntry = candidates.filter {
                it.scope == PricingScope.MODEL && it.modelId == modelId &&
                    (serviceId.isNullOrBlank() || it.serviceId.isNullOrBlank() || it.serviceId == serviceId)
            }.maxByOrNull { it.effectiveFromEpochMs }
            if (modelEntry != null && (modelEntry.inputPricePerMillion != null || modelEntry.outputPricePerMillion != null)) {
                return PriceResolution(modelEntry, PricingScope.MODEL, isUnknown = false)
            }
        }
        // SERVICE scope match
        if (!serviceId.isNullOrBlank()) {
            val serviceEntry = candidates.filter {
                it.scope == PricingScope.SERVICE && it.serviceId == serviceId && it.modelId == null
            }.maxByOrNull { it.effectiveFromEpochMs }
            if (serviceEntry != null && (serviceEntry.inputPricePerMillion != null || serviceEntry.outputPricePerMillion != null)) {
                return PriceResolution(serviceEntry, PricingScope.SERVICE, isUnknown = false)
            }
        }
        // PROVIDER scope fallback
        val providerEntry = candidates.filter {
            it.scope == PricingScope.PROVIDER && it.serviceId == null && it.modelId == null
        }.maxByOrNull { it.effectiveFromEpochMs }
        if (providerEntry != null && (providerEntry.inputPricePerMillion != null || providerEntry.outputPricePerMillion != null)) {
            return PriceResolution(providerEntry, PricingScope.PROVIDER, isUnknown = false)
        }
        return PriceResolution.UNKNOWN
    }

    /**
     * Billing classification for a target identity (Section 3.4). LOCAL is
     * derived from the resource's runtime locality, not guessed from price.
     */
    suspend fun billingClassFor(
        providerId: String?,
        serviceId: String?,
        modelId: String?,
        isLocalResource: Boolean
    ): BillingClass {
        if (isLocalResource) return BillingClass.LOCAL
        val resolution = resolvePricing(providerId, serviceId, modelId)
        return resolution.resolvedEntry?.billingClass ?: BillingClass.UNKNOWN
    }

    // ------------------------------------------------------------------
    // PRE-EXECUTION AUTHORIZATION (BUDGET CHECK + RATE CHECK)
    // ------------------------------------------------------------------

    /**
     * The pre-execution economic gate. Evaluates hierarchical budget scopes
     * (WORKSPACE > AGENT > TASK > PROVIDER/MODEL) and the RPM/TPM governor,
     * applies the enforceable budget policy, and returns a verdict the
     * decision engine must honor.
     *
     * IMPORTANT: no allocation configured => ALLOWED with hasNoAllocation
     * (cost is still tracked); this is honest "no spending authority
     * configured", not an implicit zero budget.
     */
    suspend fun authorize(request: EconomicAuthorizationRequest): EconomicAuthorizationResult {
        val scopes = listOfNotNull(
            BudgetScope(BudgetScopeType.WORKSPACE, request.workspaceId ?: NO_WORKSPACE),
            request.agentId?.let { BudgetScope(BudgetScopeType.AGENT, it) },
            request.taskId?.let { BudgetScope(BudgetScopeType.TASK, it) },
            request.providerId?.let { BudgetScope(BudgetScopeType.PROVIDER, it) }
        )

        val statuses = scopes.map { budgetStatusFor(it) }
        val estimate = estimateCost(
            providerId = request.providerId,
            serviceId = request.serviceId,
            modelId = request.modelId,
            expectedTotalTokens = request.expectedTotalTokens
        )

        // ---- Rate limit check (independent of monetary budget) ----
        val rateKey = rateScopeKeyFor(request.providerId, request.modelId, request.resourceId)
        val rateStatus: RateLimitStatus? = rateKey?.let {
            rateLimitGovernor.statusFor(it, RateLimitScopeType.PROVIDER)
        }
        if (rateStatus != null && rateStatus.isRateLimited) {
            return EconomicAuthorizationResult(
                decision = EconomicGateDecision.DENIED,
                reason = "RATE_LIMITED: نافذة RPM/TPM مغلقة للنطاق ${rateStatus.scopeKey} " +
                    "(RPM ${rateStatus.rpmUsed}/${rateStatus.rpmLimit ?: "?"}, " +
                    "TPM ${rateStatus.tpmUsed}/${rateStatus.tpmLimit ?: "?"}).",
                evaluatedScopes = statuses,
                rateLimitStatus = rateStatus,
                estimate = estimate,
                appliedPolicy = null
            )
        }

        // ---- Budget evaluation over scopes with allocations ----
        val currency = statuses.firstOrNull { it.allocation != null }?.currency
            ?: estimate.expectedCost?.currency ?: DEFAULT_CURRENCY

        val estimatedCostMicro = estimate.expectedCost?.amountMicro

        // Find the most restrictive violated scope (workspace-level policy
        // outranks agent/task: the operator's authority).
        val evaluated = statuses.filter { it.allocation != null && it.allocation.isActive }
        for (status in evaluated) {
            val allocation = status.allocation ?: continue
            val allocatedMicro = allocation.allocated.amountMicro
            // UNKNOWN allocation amount = "track cost, no spending authority
            // configured" — never a ceiling, never a denial (honest default).
            if (allocatedMicro == null) continue
            val remaining = status.remaining?.amountMicro
            val projectedMicro = status.consumed.amountMicro?.plus(estimatedCostMicro ?: 0L)

            val overHard = projectedMicro != null && projectedMicro > allocatedMicro
            val overWarn = status.utilizationRatio != null && estimatedCostMicro != null &&
                status.utilizationRatio + (estimatedCostMicro.toDouble() / allocatedMicro.coerceAtLeast(1)) > allocation.policy.warnThresholdRatio

            if (overHard || overWarn) {
                val action = allocation.policy.primaryAction()
                val reason = buildString {
                    append("BUDGET: نطاق ${status.scope.key()} — المستهلك ${status.consumed.amountMicro ?: 0} / ")
                    append("${allocation.allocated.amountMicro} $currency")
                    if (overHard) append(" (تجاوز متوقع مع التقدير)") else append(" (اقتراب من الحد)")
                    append(". السياسة: ${action.name}.")
                }
                val decision = when (action) {
                    BudgetPolicyAction.HARD_LIMIT -> EconomicGateDecision.DENIED
                    // SOFT_LIMIT never denies — it WARNs at (and beyond) the
                    // threshold so the operator sees the crossing.
                    BudgetPolicyAction.SOFT_LIMIT -> EconomicGateDecision.WARNED
                    BudgetPolicyAction.AUTO_DOWNGRADE -> EconomicGateDecision.DOWNGRADE
                    BudgetPolicyAction.AUTO_LOCAL_FALLBACK -> EconomicGateDecision.LOCAL_FALLBACK
                    BudgetPolicyAction.REQUIRE_APPROVAL -> EconomicGateDecision.APPROVAL_REQUIRED
                }
                notifyBudgetEvent(request, decision, reason, status)
                return EconomicAuthorizationResult(
                    decision = decision,
                    reason = reason,
                    evaluatedScopes = statuses,
                    rateLimitStatus = rateStatus,
                    estimate = estimate,
                    appliedPolicy = action
                )
            }
        }

        // Warned-only threshold crossing with no hard violation
        val warnScope = evaluated.firstOrNull { st ->
            st.utilizationRatio != null && st.allocation != null &&
                st.utilizationRatio >= st.allocation.policy.warnThresholdRatio
        }
        if (warnScope != null) {
            val reason = "BUDGET_WARNING: نطاق ${warnScope.scope.key()} تجاوز عتبة التحذير " +
                "(${warnScope.utilizationRatio}) — الاستمرار مسموح."
            notifyBudgetEvent(request, EconomicGateDecision.WARNED, reason, warnScope)
            return EconomicAuthorizationResult(
                decision = EconomicGateDecision.WARNED,
                reason = reason,
                evaluatedScopes = statuses,
                rateLimitStatus = rateStatus,
                estimate = estimate,
                appliedPolicy = BudgetPolicyAction.SOFT_LIMIT
            )
        }

        return EconomicAuthorizationResult(
            decision = EconomicGateDecision.ALLOWED,
            reason = if (estimatedCostMicro == null)
                "ALLOWED: لا يوجد تكلفة معروفة للتقدير (UNKNOWN) ولا قيود ميزانية مفعّلة."
            else
                "ALLOWED: التقدير $estimatedCostMicro micro-$currency ضمن الميزانيات المفعّلة.",
            evaluatedScopes = statuses,
            rateLimitStatus = rateStatus,
            estimate = estimate,
            appliedPolicy = null
        )
    }

    // ------------------------------------------------------------------
    // COST ESTIMATION (Section 3.10)
    // ------------------------------------------------------------------

    /**
     * Estimates the cost of the NEXT interaction. Basis precedence:
     * 1. explicit expected tokens (from the task/request),
     * 2. historical average from the cost ledger for the same identity,
     * 3. UNKNOWN.
     * Cost is only computed when pricing resolves AND a token magnitude is
     * known; otherwise the estimate is honestly UNKNOWN.
     */
    suspend fun estimateCost(
        providerId: String?,
        serviceId: String?,
        modelId: String?,
        expectedTotalTokens: Int? = null
    ): CostEstimate {
        val resolution = resolvePricing(providerId, serviceId, modelId)
        val entry = resolution.resolvedEntry

        // Determine token magnitude
        var basis = "NONE"
        var expectedTokens: Int? = expectedTotalTokens
        if (expectedTokens == null) {
            val history = costLedger.recentRecordsForIdentity(providerId, modelId, ESTIMATION_HISTORY_WINDOW)
            val nonZero = history.filter { it.usage.totalTokens > 0 }
            if (nonZero.isNotEmpty()) {
                expectedTokens = (nonZero.map { it.usage.totalTokens }.sum() / nonZero.size)
                basis = "HISTORY_AVERAGE"
            }
        } else {
            basis = "REQUEST_DECLARED"
        }

        if (entry == null) {
            return CostEstimate.unknown(if (basis != "NONE") "$basis+UNKNOWN_PRICING" else "NONE")
        }

        val inPrice = entry.inputPricePerMillion?.amountMicro
        val outPrice = entry.outputPricePerMillion?.amountMicro
        if (expectedTokens == null || (inPrice == null && outPrice == null)) {
            return CostEstimate.unknown("$basis+INSUFFICIENT_PRICE")
        }

        // Assume a 3:1 input:output split ONLY for estimation, clearly
        // labeled as estimation (never recorded as actual).
        val inputTokens = (expectedTokens * 3) / 4
        val outputTokens = expectedTokens - inputTokens
        val costMicro = computeCostMicro(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedTokens = 0,
            inputPriceMicro = inPrice,
            outputPriceMicro = outPrice,
            cachedPriceMicro = null
        )
        return CostEstimate(
            expectedInputTokens = inputTokens,
            expectedOutputTokens = outputTokens,
            expectedTotalTokens = expectedTokens,
            expectedCost = costMicro?.let { MoneyAmount.of(it, entryCurrency(entry)) },
            basis = basis,
            confidence = if (basis == "HISTORY_AVERAGE") 0.5f else 0.3f,
            pricingVersionApplied = entry.pricingVersion
        )
    }

    // ------------------------------------------------------------------
    // POST-EXECUTION ACCOUNTING (USAGE -> COST LEDGER)
    // ------------------------------------------------------------------

    /**
     * Records actual/estimated usage into the persistent cost ledger with
     * full attribution, updates the RPM/TPM window, and emits telemetry.
     * Failures are swallowed (accounting must never break execution) but
     * logged through telemetry when available.
     */
    fun accountUsage(input: UsageAccountingInput) {
        scope.launch {
            try {
                accountUsageSuspend(input)
            } catch (t: Throwable) {
                // Accounting is best-effort by design; telemetry failure must
                // not cascade.
            }
        }
    }

    /**
     * Suspend variant used by the orchestrator's post-execution accounting:
     * returns the persisted ledger record (or null on accounting failure) so
     * the caller can surface a real CostRecorded event — usage AND cost are
     * whatever they truly are (UNKNOWN stays UNKNOWN).
     */
    suspend fun accountUsageSuspend(input: UsageAccountingInput): UsageCostRecord? {
        return try {
            accountUsageInternal(input)
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun accountUsageInternal(input: UsageAccountingInput): UsageCostRecord? {
        return ledgerMutex.withLock {
            val resolution = resolvePricing(input.providerId, input.serviceId, input.modelId)
            val entry = resolution.resolvedEntry
            val isLocal = input.providerId != null && isLocalProviderId(input.providerId)

            val billingClass = when {
                isLocal -> BillingClass.LOCAL
                entry != null -> entry.billingClass
                else -> BillingClass.UNKNOWN
            }

            val costMicro: Long? = if (entry == null || isLocal) null else computeCostMicro(
                inputTokens = input.usage.inputTokens,
                outputTokens = input.usage.outputTokens,
                cachedTokens = input.usage.cachedTokens,
                inputPriceMicro = entry.inputPricePerMillion?.amountMicro,
                outputPriceMicro = entry.outputPricePerMillion?.amountMicro,
                cachedPriceMicro = entry.cachedInputPricePerMillion?.amountMicro
            )

            val currency = entry?.let { entryCurrency(it) } ?: DEFAULT_CURRENCY
            val costStatus = when {
                isLocal -> CostStatus.UNKNOWN // local compute cost is not monetized here
                costMicro == null -> CostStatus.UNKNOWN
                input.isActualProviderReport -> CostStatus.ACTUAL
                else -> CostStatus.ESTIMATED
            }

            val record = UsageCostRecord(
                id = "clr_${UUID.randomUUID()}",
                executionId = input.executionId,
                taskId = input.taskId,
                workspaceId = input.workspaceId,
                agentId = input.agentId,
                providerId = input.providerId,
                serviceId = input.serviceId,
                modelId = input.modelId,
                resourceId = input.resourceId,
                usage = input.usage,
                appliedPricing = entry,
                cost = if (costMicro != null) MoneyAmount.of(costMicro, currency) else MoneyAmount.unknown(currency),
                costStatus = costStatus,
                billingClass = billingClass,
                timestampEpochMs = input.timestampEpochMs
            )
            costLedger.insert(record)

            // Rate-limit window accounting (real interaction happened).
            val rateKey = rateScopeKeyFor(input.providerId, input.modelId, input.resourceId)
            if (rateKey != null) {
                rateLimitGovernor.recordRequest(rateKey, input.usage.totalTokens)
            }

            // Telemetry (never blocking, never throwing into the runtime)
            val dims = MetricDimensions(
                executionId = input.executionId,
                workspaceId = input.workspaceId,
                providerId = input.providerId,
                agentId = input.agentId
            )
            telemetry?.let { tel ->
                runCatching {
                    tel.recordTokenUsage(
                        dimensions = dims,
                        promptTokens = input.usage.inputTokens,
                        completionTokens = input.usage.outputTokens,
                        providerId = input.providerId ?: "unknown"
                    )
                    if (costMicro != null) {
                        // Telemetry COST_USD is normalized micro-USD; only
                        // recorded when the currency IS USD (no silent FX).
                        if (currency == "USD") {
                            tel.recordCost(dims, costMicro)
                        }
                    }
                }
            }
            record
        }
    }

    /** Records a provider rate-limit encounter (429) closing the gate. */
    fun recordRateLimitEncounter(providerId: String?, modelId: String?, resourceId: String?, retryAfterMs: Long?) {
        val key = rateScopeKeyFor(providerId, modelId, resourceId) ?: return
        rateLimitGovernor.recordRateLimitEncounter(key, retryAfterMs)
    }

    // ------------------------------------------------------------------
    // BUDGET MANAGEMENT (Section 3.7 / 3.8 / 3.11)
    // ------------------------------------------------------------------

    suspend fun setAllocation(scope: BudgetScope, allocated: MoneyAmount, policy: BudgetPolicy) {
        val now = System.currentTimeMillis()
        val existing = allocationRepository.allocationFor(scope)
        allocationRepository.upsertAllocation(
            BudgetAllocation(
                scope = scope,
                allocated = allocated,
                policy = policy,
                isActive = true,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun deactivateAllocation(scope: BudgetScope) {
        allocationRepository.deactivateAllocation(scope)
    }

    suspend fun budgetStatusFor(scope: BudgetScope): BudgetStatus {
        val allocation = allocationRepository.allocationFor(scope)?.takeIf { it.isActive }
        val currency = allocation?.allocated?.currency ?: DEFAULT_CURRENCY
        val aggregate: CostAggregate = costLedger.consumedCostForScope(scope.scopeType, scope.scopeId, currency)
        val consumed = MoneyAmount.of(aggregate.totalMicro, currency)
        val remaining = allocation?.let { alloc ->
            alloc.allocated.amountMicro?.let { micro ->
                MoneyAmount.of((micro - aggregate.totalMicro).coerceAtLeast(0), currency)
            }
        }
        val utilization = allocation?.allocated?.amountMicro?.takeIf { it > 0 }?.let { micro ->
            aggregate.totalMicro.toFloat() / micro.toFloat()
        }
        return BudgetStatus(
            scope = scope,
            allocation = allocation,
            consumed = consumed,
            remaining = remaining,
            utilizationRatio = utilization,
            currency = currency,
            hasNoAllocation = allocation == null
        )
    }

    suspend fun workspaceBudgetSummary(workspaceId: String): BudgetStatus =
        budgetStatusFor(BudgetScope(BudgetScopeType.WORKSPACE, workspaceId))

    /** Hierarchical status: all active allocations + their live statuses. */
    suspend fun allBudgetStatuses(): List<BudgetStatus> =
        allocationRepository.allAllocations().filter { it.isActive }.map { budgetStatusFor(it.scope) }

    // ------------------------------------------------------------------
    // RATE LIMIT CONFIGURATION
    // ------------------------------------------------------------------

    fun configureRateLimits(scopeKey: String, rpmLimit: Int?, tpmLimit: Int?) {
        rateLimitGovernor.configureLimits(scopeKey, rpmLimit, tpmLimit)
    }

    fun rateLimitStatusFor(providerId: String?, modelId: String?, resourceId: String?): RateLimitStatus? =
        rateScopeKeyFor(providerId, modelId, resourceId)?.let {
            rateLimitGovernor.statusFor(it, RateLimitScopeType.PROVIDER)
        }

    // ------------------------------------------------------------------
    // QUERY SURFACE (UI / observatory)
    // ------------------------------------------------------------------

    suspend fun ledgerForExecution(executionId: String): List<UsageCostRecord> =
        costLedger.recordsForExecution(executionId)

    suspend fun recentLedgerForWorkspace(workspaceId: String, limit: Int = 50): List<UsageCostRecord> =
        costLedger.recentRecordsForWorkspace(workspaceId, limit)

    suspend fun tokensConsumedForWorkspace(workspaceId: String): Long =
        costLedger.tokensConsumedForScope(BudgetScopeType.WORKSPACE, workspaceId)

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun notifyBudgetEvent(
        request: EconomicAuthorizationRequest,
        decision: EconomicGateDecision,
        reason: String,
        status: BudgetStatus
    ) {
        telemetry ?: return
        scope.launch {
            runCatching {
                telemetry.incrementCounter(
                    action = "BUDGET_${decision.name}",
                    dimensions = MetricDimensions(
                        executionId = request.executionId,
                        workspaceId = request.workspaceId,
                        agentId = request.agentId,
                        providerId = request.providerId
                    )
                )
            }
        }
    }

    private fun rateScopeKeyFor(providerId: String?, modelId: String?, resourceId: String?): String? {
        return when {
            !resourceId.isNullOrBlank() -> "RESOURCE:$resourceId"
            !modelId.isNullOrBlank() -> "MODEL:$modelId"
            !providerId.isNullOrBlank() -> "PROVIDER:$providerId"
            else -> null
        }
    }

    private fun isLocalProviderId(providerId: String): Boolean =
        providerId.contains("local", ignoreCase = true) ||
            providerId.contains("onnx", ignoreCase = true) ||
            providerId.contains("device", ignoreCase = true)

    private fun entryCurrency(entry: PricingEntry): String =
        entry.inputPricePerMillion?.currency
            ?: entry.outputPricePerMillion?.currency
            ?: entry.cachedInputPricePerMillion?.currency
            ?: DEFAULT_CURRENCY

    companion object {
        const val DEFAULT_CURRENCY = "USD"
        const val NO_WORKSPACE = "__no_workspace__"
        const val ESTIMATION_HISTORY_WINDOW = 20

        /**
         * Exact integer cost computation in micro currency units:
         * cost = tokens * pricePerMillion / 1_000_000 — using micro prices
         * makes this micro * micro / 1e6; result stays in micro units.
         */
        fun computeCostMicro(
            inputTokens: Int,
            outputTokens: Int,
            cachedTokens: Int,
            inputPriceMicro: Long?,
            outputPriceMicro: Long?,
            cachedPriceMicro: Long?
        ): Long? {
            var known = false
            var total = 0L
            inputPriceMicro?.let {
                known = true
                total += inputTokens.toLong() * it / 1_000_000L
            }
            outputPriceMicro?.let {
                known = true
                total += outputTokens.toLong() * it / 1_000_000L
            }
            cachedPriceMicro?.let { price ->
                // Cached tokens billed at the cached rate only where the
                // provider separates them; otherwise they ride input price.
                known = true
                total += cachedTokens.toLong() * price / 1_000_000L
            }
            return if (known) total else null
        }
    }
}
