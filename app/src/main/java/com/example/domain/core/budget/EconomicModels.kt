package com.example.domain.core.budget

/**
 * ============================================================================
 * Token / Economic Budget & Cost Governance — Domain Models
 * ============================================================================
 *
 * CONCEPT SEPARATION CONTRACT (Governance Phase, Section 3.1):
 *
 *   A. Token Usage      -> [TokenUsageRecord]      (what was consumed, measured)
 *   B. Token Quota      -> [TokenQuota]            (execution token constraints)
 *   C. Monetary Budget  -> [BudgetAllocation]      (spending authority)
 *   D. Pricing          -> [PricingEntry]          (unit prices)
 *   E. Billing Class    -> [BillingClass]          (FREE/PAID/TRIAL/CREDIT/LOCAL/UNKNOWN)
 *   F. Rate Limits      -> [RateLimitStatus]       (RPM/TPM capacity)
 *   G. Cost Ledger      -> [UsageCostRecord]       (persistent accounting)
 *   H. Cost Estimation  -> [CostEstimate]          (pre-execution forecast)
 *   I. Budget Policy    -> [BudgetPolicy]          (enforceable semantics)
 *
 * These concepts are NEVER merged:
 *   - Token limit       != Budget
 *   - Token usage       != Monetary cost
 *   - Free tier         != Local execution
 *   - $0 API cost       != Zero computational cost
 *   - Provider pricing  != Model pricing
 *   - Estimated cost    != Actual cost
 *
 * UNKNOWN is a valid, honest value everywhere fabrication would otherwise
 * be required.
 */

/**
 * A. TOKEN USAGE — measured consumption for one provider interaction.
 * `isEstimate` is true when the provider did NOT report usage and a
 * heuristic (e.g. chars/4) substituted for it. Heuristic usage is never
 * silently presented as measured usage.
 */
data class TokenUsageRecord(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** Provider-reported total (input+output+cached+reasoning) when the
     *  provider publishes its own total; otherwise computed. */
    val providerTotalTokens: Int? = null,
    val isEstimate: Boolean = false
) {
    val totalTokens: Int
        get() = providerTotalTokens ?: (inputTokens + outputTokens + cachedTokens)
}

/**
 * B. TOKEN QUOTA — execution/token constraints. These are NOT monetary
 * budgets. Enforced by the execution layer (task loop / delegation).
 */
data class TokenQuota(
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val maxTotalTokens: Int? = null
)

/** E. BILLING CLASS — classification of the economic nature of a resource. */
enum class BillingClass {
    /** Costs no money per call under a provider's free tier. NOT local. */
    FREE,
    /** Costs real money per usage. */
    PAID,
    /** Trial / promotional credit — time- or usage-bounded free usage. */
    TRIAL,
    /** Prepaid credit balance. */
    CREDIT,
    /** Executes on-device. NOT necessarily economically free (compute,
     *  battery, thermal cost) — but no provider charge. */
    LOCAL,
    /** Pricing information is not available. NEVER guessed. */
    UNKNOWN
}

/**
 * Money amount with EXPLICIT currency. The platform does NOT assume USD
 * internally; normalization happens only at display/telemetry boundaries
 * and only when the currency is known.
 *
 * amount is expressed in micro units of the currency (1_000_000 micro = 1
 * unit) as a Long, so all arithmetic is exact integer math. null amount
 * with a non-null currency means "cost exists but is unknown".
 */
data class MoneyAmount(
    val amountMicro: Long?,
    val currency: String
) {
    val isUnknown: Boolean get() = amountMicro == null

    companion object {
        fun unknown(currency: String = "USD") = MoneyAmount(null, currency)
        fun of(amountMicro: Long, currency: String = "USD") = MoneyAmount(amountMicro, currency)
        fun zero(currency: String = "USD") = MoneyAmount(0L, currency)
    }
}

/** The scope at which a pricing entry is declared. */
enum class PricingScope { PROVIDER, SERVICE, MODEL }

/**
 * D. PRICING — one versioned unit-price entry. Model-specific entries
 * override service-level, which override provider-level. Entries carry
 * effective-time windows and provenance so the system can answer "which
 * price did we use and where did it come from".
 */
data class PricingEntry(
    val id: String,
    val scope: PricingScope,
    val providerId: String,
    val serviceId: String? = null,
    val modelId: String? = null,
    /** Price per MILLION input tokens. null = not published at this scope. */
    val inputPricePerMillion: MoneyAmount? = null,
    /** Price per MILLION output tokens. null = not published at this scope. */
    val outputPricePerMillion: MoneyAmount? = null,
    /** Price per MILLION cached input tokens (where the provider bills them). */
    val cachedInputPricePerMillion: MoneyAmount? = null,
    val billingClass: BillingClass = BillingClass.UNKNOWN,
    /** Monotonic/labelled pricing revision (e.g. "2026-09", "discovery-42"). */
    val pricingVersion: String,
    val effectiveFromEpochMs: Long,
    val effectiveToEpochMs: Long? = null,
    /** Where this entry came from: MODEL_DISCOVERY, USER_CONFIG, PROVIDER_PUBLISH… */
    val provenance: String
) {
    init {
        require(scope == PricingScope.PROVIDER || !serviceId.isNullOrBlank()) {
            "SERVICE/MODEL pricing requires serviceId"
        }
        require(scope != PricingScope.MODEL || !modelId.isNullOrBlank()) {
            "MODEL pricing requires modelId"
        }
    }
}

/** Result of a price lookup: what entry won, at which scope, or UNKNOWN. */
data class PriceResolution(
    val resolvedEntry: PricingEntry?,
    val matchedScope: PricingScope?,
    val isUnknown: Boolean
) {
    companion object {
        val UNKNOWN = PriceResolution(null, null, isUnknown = true)
    }
}

/** Whether a ledger cost is an estimate or the provider's actual figure. */
enum class CostStatus { ESTIMATED, ACTUAL, UNKNOWN }

/**
 * G. COST LEDGER — one persistent usage+cost accounting record. Every
 * billable interaction lands here with full attribution (execution,
 * workspace, agent, provider, service, model, resource).
 */
data class UsageCostRecord(
    val id: String,
    val executionId: String,
    val taskId: String?,
    val workspaceId: String?,
    val agentId: String?,
    val providerId: String?,
    val serviceId: String?,
    val modelId: String?,
    val resourceId: String?,
    val usage: TokenUsageRecord,
    /** Snapshot of the unit pricing actually applied (null = unknown). */
    val appliedPricing: PricingEntry?,
    val cost: MoneyAmount?,
    val costStatus: CostStatus,
    val billingClass: BillingClass,
    val timestampEpochMs: Long
)

/** Hierarchy of budget scopes the engine can evaluate. */
enum class BudgetScopeType { SYSTEM, PROVIDER, SERVICE, MODEL, AGENT, WORKSPACE, TASK, EXECUTION, STEP }

/** A concrete budget scope identity. */
data class BudgetScope(
    val scopeType: BudgetScopeType,
    val scopeId: String
) {
    fun key(): String = "${scopeType.name}:${scopeId}"
}

/** Enforceable policy actions, ordered by precedence when composing. */
enum class BudgetPolicyAction {
    /** Deny execution when the budget would be exceeded. */
    HARD_LIMIT,
    /** Warn but allow (soft ceiling). */
    SOFT_LIMIT,
    /** Substitute a cheaper model/resource when the budget is tight. */
    AUTO_DOWNGRADE,
    /** Substitute a local resource when the budget is tight. */
    AUTO_LOCAL_FALLBACK,
    /** Pause and require human approval for the spend. */
    REQUIRE_APPROVAL
}

/**
 * I. BUDGET POLICY — the enforceable semantics attached to a scope.
 * Policies are consulted by the decision engine BEFORE execution; they are
 * not decorative.
 */
data class BudgetPolicy(
    val actions: List<BudgetPolicyAction>,
    /** Warn when consumed/allocated crosses this ratio (0..1). */
    val warnThresholdRatio: Float = 0.8f,
    val note: String? = null
) {
    fun primaryAction(): BudgetPolicyAction = actions.firstOrNull() ?: BudgetPolicyAction.SOFT_LIMIT
}

/**
 * C. MONETARY BUDGET — allocation of spending authority at a scope.
 */
data class BudgetAllocation(
    val scope: BudgetScope,
    val allocated: MoneyAmount,
    val policy: BudgetPolicy,
    val isActive: Boolean = true,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/** Live budget status for a scope, derived from allocation + ledger sums. */
data class BudgetStatus(
    val scope: BudgetScope,
    val allocation: BudgetAllocation?,
    val consumed: MoneyAmount,
    val remaining: MoneyAmount?,
    val utilizationRatio: Float?,
    val currency: String,
    /** True when no allocation exists (unlimited, cost still tracked). */
    val hasNoAllocation: Boolean
)

/**
 * F. RATE LIMITS — RPM/TPM capacity, tracked independently from budgets.
 * A "remaining token budget" is NOT a "remaining TPM capacity".
 */
enum class RateLimitScopeType { PROVIDER, MODEL, RESOURCE }

data class RateLimitStatus(
    val scopeKey: String,
    val scopeType: RateLimitScopeType,
    val rpmLimit: Int?,
    val tpmLimit: Int?,
    val windowStartEpochMs: Long,
    val windowLengthMs: Long,
    val rpmUsed: Int,
    val tpmUsed: Long,
    /** Set when the provider returned 429 / Retry-After; gates stay closed
     *  until it elapses. */
    val blockedUntilEpochMs: Long? = null
) {
    val isRateLimited: Boolean
        get() = (rpmLimit != null && rpmUsed >= rpmLimit) ||
            (tpmLimit != null && tpmUsed >= tpmLimit) ||
            (blockedUntilEpochMs != null && System.currentTimeMillis() < blockedUntilEpochMs)

    val remainingRpm: Int? get() = rpmLimit?.let { (it - rpmUsed).coerceAtLeast(0) }
    val remainingTpm: Long? get() = tpmLimit?.let { (it.toLong() - tpmUsed).coerceAtLeast(0) }
}

/**
 * H. COST ESTIMATION — pre-execution forecast. UNKNOWN rather than invented
 * when insufficient history/pricing exists.
 */
data class CostEstimate(
    val expectedInputTokens: Int?,
    val expectedOutputTokens: Int?,
    val expectedTotalTokens: Int?,
    val expectedCost: MoneyAmount?,
    /** HISTORY_AVERAGE | CONFIGURED_QUOTA | REQUEST_DECLARED | NONE */
    val basis: String,
    /** 0..1 — 0 means estimation was impossible (fields then null). */
    val confidence: Float,
    val pricingVersionApplied: String?
) {
    val isUnknown: Boolean
        get() = expectedCost == null && expectedTotalTokens == null

    companion object {
        fun unknown(basis: String = "NONE") = CostEstimate(
            expectedInputTokens = null,
            expectedOutputTokens = null,
            expectedTotalTokens = null,
            expectedCost = null,
            basis = basis,
            confidence = 0.0f,
            pricingVersionApplied = null
        )
    }
}

/**
 * The verdict of the pre-execution economic authorization gate.
 */
enum class EconomicGateDecision {
    /** Budget/rate fine (or no budget configured) — proceed. */
    ALLOWED,
    /** Soft ceiling crossed — proceed but emit warning. */
    WARNED,
    /** Hard limit — deny the action as chosen. */
    DENIED,
    /** Policy substitutes a cheaper candidate. */
    DOWNGRADE,
    /** Policy substitutes a local candidate. */
    LOCAL_FALLBACK,
    /** Policy requires human approval before spending. */
    APPROVAL_REQUIRED
}

/** Input for the pre-execution economic gate. */
data class EconomicAuthorizationRequest(
    val executionId: String,
    val workspaceId: String?,
    val agentId: String?,
    val taskId: String?,
    /** Resource identity the action intends to use (null for non-resource actions). */
    val providerId: String?,
    val serviceId: String?,
    val modelId: String?,
    val resourceId: String?,
    val isLocalResource: Boolean,
    /** Declared/estimated expected token magnitude, when known. */
    val expectedTotalTokens: Int?,
    val actionTypeCode: String
)

/** The gate's verdict with the reasoning the decision engine needs. */
data class EconomicAuthorizationResult(
    val decision: EconomicGateDecision,
    val reason: String,
    /** Budget statuses that were evaluated (for telemetry/audit). */
    val evaluatedScopes: List<BudgetStatus>,
    /** Rate limit status of the target scope, when rate limits are known. */
    val rateLimitStatus: RateLimitStatus?,
    /** The applied estimate (never fabricated — UNKNOWN basis documented). */
    val estimate: CostEstimate,
    val appliedPolicy: BudgetPolicyAction?
)

/**
 * Post-execution accounting input: what was actually consumed.
 */
data class UsageAccountingInput(
    val executionId: String,
    val taskId: String?,
    val workspaceId: String?,
    val agentId: String?,
    val providerId: String?,
    val serviceId: String?,
    val modelId: String?,
    val resourceId: String?,
    val usage: TokenUsageRecord,
    val isActualProviderReport: Boolean,
    val timestampEpochMs: Long = System.currentTimeMillis()
)
