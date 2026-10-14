package com.example.application.budget

import com.example.domain.core.budget.CostEstimate
import com.example.domain.core.budget.EconomicAuthorizationRequest
import com.example.domain.core.budget.EconomicAuthorizationResult
import com.example.domain.core.budget.EconomicGateDecision

/**
 * ============================================================================
 * HoldBasedBudgetGate — the v23 bridge inside authorize() (Section و.6)
 * ============================================================================
 * Approved Day 8-9 wiring. EconomicGovernanceService.authorize() consults
 * this adapter AFTER the rate check and BEFORE the legacy allocation
 * comparison:
 *
 *   active accounting policy for one of the request's scopes?
 *     YES → hold-based verdict (authorizeAndReserve / policy decision)
 *     NO  → [tryAuthorize] returns null and the LEGACY path runs verbatim
 *           (budget_allocations keeps working — full retro-compatibility).
 *
 * Scope mapping (PDM-G01, closed 2026-10-14):
 *   WORKSPACE→WORKSPACE, AGENT→AGENT, TASK→TASK, PROVIDER→PROVIDER,
 *   SYSTEM≡APPLICATION (documented equivalence), MODEL→MODEL_OFFERING.
 *   SERVICE has no accounting-scope counterpart → legacy path.
 *
 * UNKNOWN estimate under an active policy: ALLOWED with an explicit honest
 * reason — no hold is created (a hold of 0 is impossible and UNKNOWN is
 * never zero); the actual cost lands at settlement. Recorded as
 * interpretation I-3 in the stage register.
 *
 * Overage mapping is FAIL-CLOSED: an unrecognized overage policy DENIES.
 * SOFT_LIMIT warns and proceeds WITHOUT a hold (the "holds never exceed
 * the limit" invariant is absolute — reservation protection belongs to
 * hard budgets only). Recorded as interpretation I-7.
 */
class HoldBasedBudgetGate(
    private val ledger: BudgetLedgerService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Request-derived scope priority: the four documented authorize()
     * dimensions (و.6), then an APPLICATION-scoped global policy as the
     * lowest-priority catch-all (created with platformObjectId = null,
     * matched by every request).
     */
    private val scopeOrder = listOf(
        ScopeEntry(com.example.domain.core.budget.BudgetScopeType.WORKSPACE),
        ScopeEntry(com.example.domain.core.budget.BudgetScopeType.AGENT),
        ScopeEntry(com.example.domain.core.budget.BudgetScopeType.TASK),
        ScopeEntry(com.example.domain.core.budget.BudgetScopeType.PROVIDER),
        ScopeEntry(com.example.domain.core.budget.BudgetScopeType.SYSTEM, pdmScopeType = "APPLICATION", fixedScopeId = null, isGlobal = true)
    )

    private data class ScopeEntry(
        val platformType: com.example.domain.core.budget.BudgetScopeType,
        val pdmScopeType: String? = null,
        val fixedScopeId: String? = null,
        val isGlobal: Boolean = false
    )

    /**
     * @return null when no active accounting policy covers the request —
     * the caller must then run the legacy path unchanged.
     */
    suspend fun tryAuthorize(
        request: EconomicAuthorizationRequest,
        estimate: CostEstimate,
        evaluatedScopes: List<com.example.domain.core.budget.BudgetStatus>,
        rateLimitStatus: com.example.domain.core.budget.RateLimitStatus?
    ): EconomicAuthorizationResult? {
        val now = clock()
        for (entry in scopeOrder) {
            val scopeId = if (entry.isGlobal) {
                entry.fixedScopeId // APPLICATION catch-all: platform-null canonical
            } else {
                when (entry.platformType) {
                    com.example.domain.core.budget.BudgetScopeType.WORKSPACE -> request.workspaceId ?: continue
                    com.example.domain.core.budget.BudgetScopeType.AGENT -> request.agentId ?: continue
                    com.example.domain.core.budget.BudgetScopeType.TASK -> request.taskId ?: continue
                    com.example.domain.core.budget.BudgetScopeType.PROVIDER -> request.providerId ?: continue
                    else -> continue
                }
            }
            val pdmScopeType = entry.pdmScopeType
                ?: mapToPdmScopeType(entry.platformType)
                ?: continue
            val active = ledger.activeBudgetForScope(pdmScopeType, scopeId, now) ?: continue

            val estimatedMicro = estimate.expectedCost?.amountMicro
            if (estimatedMicro == null || estimatedMicro <= 0) {
                // I-3: UNKNOWN estimate — no hold, honest ALLOWED.
                return EconomicAuthorizationResult(
                    decision = EconomicGateDecision.ALLOWED,
                    reason = "HOLD-BASED BUDGET: سياسة محاسبية نشطة (${active.policy.budgetName}) " +
                        "للنطاق ${pdmScopeType}:$scopeId بلا تقدير تكلفة معروف — لا يُنشأ حجز " +
                        "(المجهول ليس صفرًا)؛ التكلفة الفعلية ستُقيَّد عند التسوية.",
                    evaluatedScopes = evaluatedScopes,
                    rateLimitStatus = rateLimitStatus,
                    estimate = estimate,
                    appliedPolicy = null
                )
            }

            return when (
                val reservation = ledger.authorizeAndReserve(
                    scopeType = pdmScopeType,
                    platformObjectId = scopeId,
                    amountMicroUnits = estimatedMicro,
                    reason = "exec:${request.executionId}",
                    idempotencyKey = "auth:${request.executionId}:$pdmScopeType:$scopeId",
                    expiresAtEpochMs = null,
                    nowEpochMs = now
                )
            ) {
                is BudgetLedgerService.HoldReservation.Reserved ->
                    EconomicAuthorizationResult(
                        decision = EconomicGateDecision.ALLOWED,
                        reason = "HOLD-BASED BUDGET: حجز ${reservation.holdAmountMicroUnits} micro-" +
                            "${active.policy.currency} للنطاق ${pdmScopeType}:$scopeId " +
                            "(hold=${reservation.holdId})؛ المتاح بعده ${reservation.availableAfterMicroUnits}" +
                            if (reservation.replayed) " [replay]" else "",
                        evaluatedScopes = evaluatedScopes,
                        rateLimitStatus = rateLimitStatus,
                        estimate = estimate,
                        appliedPolicy = null
                    )

                is BudgetLedgerService.HoldReservation.Denied ->
                    mapOverage(reservation, active, pdmScopeType, scopeId, estimate, evaluatedScopes, rateLimitStatus)

                BudgetLedgerService.HoldReservation.NoActivePolicy ->
                    null // raced away between peek and reserve — fall back to legacy
            }
        }
        return null
    }

    private fun mapOverage(
        denial: BudgetLedgerService.HoldReservation.Denied,
        active: BudgetLedgerService.ActiveBudget,
        pdmScopeType: String,
        scopeId: String,
        estimate: CostEstimate,
        evaluatedScopes: List<com.example.domain.core.budget.BudgetStatus>,
        rateLimitStatus: com.example.domain.core.budget.RateLimitStatus?
    ): EconomicAuthorizationResult {
        val base = "HOLD-BASED BUDGET: النطاق ${pdmScopeType}:$scopeId — المطلوب " +
            "${denial.requiredMicroUnits} والمتاح ${denial.availableMicroUnits} micro-${active.policy.currency}. " +
            "سياسة التجاوز: ${denial.overagePolicy}."
        val (decision, reason) = when (denial.overagePolicy) {
            BudgetLedgerService.OVERAGE_HARD_LIMIT ->
                EconomicGateDecision.DENIED to base
            BudgetLedgerService.OVERAGE_SOFT_LIMIT, "WARN" ->
                // I-7: soft budgets proceed with an explicit warning and NO
                // hold — the holds-never-exceed-limit invariant is absolute.
                EconomicGateDecision.WARNED to "$base (سقف مرن: متابعة بلا حجز، المحاسبة تبقى دقيقة عند التسوية)."
            BudgetLedgerService.OVERAGE_REQUIRE_APPROVAL ->
                EconomicGateDecision.APPROVAL_REQUIRED to base
            "AUTO_DOWNGRADE" ->
                EconomicGateDecision.DOWNGRADE to base
            "AUTO_LOCAL_FALLBACK", "LOCAL_FALLBACK" ->
                EconomicGateDecision.LOCAL_FALLBACK to base
            else ->
                // FAIL-CLOSED: money gates never guess on unknown policies.
                EconomicGateDecision.DENIED to "$base (سياسة غير معروفة — رفض احترازي)."
        }
        return EconomicAuthorizationResult(
            decision = decision,
            reason = reason,
            evaluatedScopes = evaluatedScopes,
            rateLimitStatus = rateLimitStatus,
            estimate = estimate,
            appliedPolicy = null
        )
    }

    private fun mapToPdmScopeType(
        scopeType: com.example.domain.core.budget.BudgetScopeType
    ): String? = when (scopeType) {
        com.example.domain.core.budget.BudgetScopeType.WORKSPACE -> "WORKSPACE"
        com.example.domain.core.budget.BudgetScopeType.AGENT -> "AGENT"
        com.example.domain.core.budget.BudgetScopeType.TASK -> "TASK"
        com.example.domain.core.budget.BudgetScopeType.EXECUTION -> "EXECUTION"
        com.example.domain.core.budget.BudgetScopeType.STEP -> "STEP"
        com.example.domain.core.budget.BudgetScopeType.PROVIDER -> "PROVIDER"
        // G01 documented equivalence: SYSTEM ≡ APPLICATION.
        com.example.domain.core.budget.BudgetScopeType.SYSTEM -> "APPLICATION"
        // G01: MODEL is bound to the offering dimension.
        com.example.domain.core.budget.BudgetScopeType.MODEL -> "MODEL_OFFERING"
        // SERVICE has no accounting-scope counterpart (yet) — legacy path.
        com.example.domain.core.budget.BudgetScopeType.SERVICE -> null
    }

    companion object {
        /** createBudget(scopeType = "APPLICATION", platformObjectId = null)
         *  creates the global catch-all policy — canonical identity
         *  "v1|APPLICATION|platform=null" is matched by every request. */
        const val GLOBAL_APPLICATION_SCOPE = "APPLICATION"
    }
}
