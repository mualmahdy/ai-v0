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
 * STAGE 2 TRANSITION SEMANTICS — expired accounting period: a scope whose
 * modern policy EXISTS but whose accounting period has ENDED owns that
 * scope: the verdict is an honest DENY ("configure a new period"), NEVER a
 * silent fallback to the stale legacy budget_allocations row. A workspace
 * that was explicitly moved to the modern accounting path cannot
 * accidentally resurrect its legacy spending authority by letting its
 * period lapse (accepted transition behavior, BudgetPolicyManagementService).
 *
 * STAGE 2B (WS-A) — BUDGET-CURRENCY DISCIPLINE AT AUTHORIZATION: the
 * estimate's currency is checked against the ACTIVE policy's currency
 * BEFORE any reservation. Same currency → reserve as before. Different
 * currency → convert with the recorded, effective-time-valid FX rule
 * (the same CurrencyConversionService the settlement path uses — one
 * consistent rule for budget-currency conversion); the hold reserves the
 * CONVERTED amount in the policy's currency. No covering rule → NO hold
 * and NO silent 1:1: the verdict is an honest ALLOWED without reservation
 * (the I-3 shape) stating plainly that the monetary ceiling is not
 * enforceable for an interaction whose estimate cannot be converted into
 * the budget currency; the actual lands at settlement (where the same
 * discipline records it as DRAFT evidence when it cannot convert).
 *
 * Overage mapping is FAIL-CLOSED: an unrecognized overage policy DENIES.
 * SOFT_LIMIT warns and proceeds WITHOUT a hold (the "holds never exceed
 * the limit" invariant is absolute — reservation protection belongs to
 * hard budgets only). Recorded as interpretation I-7.
 */
class HoldBasedBudgetGate(
    private val ledger: BudgetLedgerService,
    private val clock: () -> Long = System::currentTimeMillis,
    /** STAGE 2B (WS-A) — the FX evidence authority for cross-currency
     *  estimates; null = cross-currency estimates are honestly
     *  unconvertible (no hold, explicit reason — never 1:1). */
    private val fx: CurrencyConversionService? = null
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
        // Stage 2 transition semantics: a scope whose modern policy EXISTS
        // but whose accounting period has ENDED still OWNS that scope — the
        // request must NOT silently fall back to a stale legacy allocation
        // (the workspace was explicitly moved to the modern path). Remember
        // the first such scope; if no other scope produces a modern verdict,
        // return an honest DENY telling the operator to configure a new
        // period. Never a legacy fallback after an explicit transition.
        var expiredPeriodScope: Pair<String, String?>? = null
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
            val policy = ledger.activePolicyForScopeIgnoringPeriod(pdmScopeType, scopeId, now)
                ?: continue
            val period = ledger.activePeriodForPolicy(policy.budgetPolicyId, now)
            if (period == null) {
                // Modern policy exists, no period covers `now` — record and
                // keep probing the remaining (more specific) scopes.
                if (expiredPeriodScope == null) expiredPeriodScope = pdmScopeType to scopeId
                continue
            }
            val active = BudgetLedgerService.ActiveBudget(policy, period)

            val estimateMoney = estimate.expectedCost
            val estimatedMicro = estimateMoney?.amountMicro
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

            // ---- STAGE 2B (WS-A): convert the estimate into the BUDGET
            //      currency before reserving. Micro-units of different
            //      currencies are never compared or reserved as if
            //      equivalent, and a missing conversion rule is never an
            //      assumed 1:1 rate.
            val estimateCurrency = estimateMoney!!.currency
            val policyCurrency = active.policy.currency
            var reserveMicro = estimatedMicro
            var conversionNote = ""
            if (estimateCurrency != policyCurrency) {
                val converted = fx?.convert(
                    amountMicroUnits = estimatedMicro,
                    fromCurrency = estimateCurrency,
                    toCurrency = policyCurrency,
                    atEpochMs = now
                )
                if (converted is CurrencyConversionService.ConversionResult.Converted) {
                    reserveMicro = converted.targetMicroUnits
                    conversionNote = " (مُحوّل من $estimatedMicro micro-$estimateCurrency " +
                        "بقاعدة ${converted.ruleId} بمعدل ${converted.ratePrecise})"
                } else {
                    // No recorded, effective-time-valid conversion rule: the
                    // ceiling is honestly NOT enforceable for this
                    // interaction. No hold is fabricated in mismatched
                    // units; the actual lands at settlement under the same
                    // discipline (DRAFT record when unconvertible).
                    return EconomicAuthorizationResult(
                        decision = EconomicGateDecision.ALLOWED,
                        reason = "HOLD-BASED BUDGET (WS-A): تقدير التكلفة بعملة $estimateCurrency " +
                            "ولا توجد قاعدة تحويل سارية إلى عملة السياسة $policyCurrency — " +
                            "لا يُنشأ حجز (لا يُفترض سعر صرف 1:1 أبدًا)؛ " +
                            "السقف النقدي غير قابل للفرض لهذا التفاعل، والتكلفة الفعلية تُسجّل بأمانة عند التسوية.",
                        evaluatedScopes = evaluatedScopes,
                        rateLimitStatus = rateLimitStatus,
                        estimate = estimate,
                        appliedPolicy = null
                    )
                }
            }

            return when (
                val reservation = ledger.authorizeAndReserve(
                    scopeType = pdmScopeType,
                    platformObjectId = scopeId,
                    amountMicroUnits = reserveMicro,
                    // Stage 1A — the reservation identity is the canonical
                    // billable-operation triple (executionId, stepIndex,
                    // attempt) carried by EXPLICIT request fields (never
                    // reason-string parsing), centralized in HoldKeys so the
                    // decide gate, the pre-dispatch LLM gate and settlement
                    // can never diverge again. Legacy callers (stepIndex ==
                    // null) keep the exact pre-Stage-1A key formats.
                    reason = HoldKeys.holdReason(request.executionId, request.stepIndex, request.attempt),
                    idempotencyKey = HoldKeys.authorizeIdempotencyKey(
                        request.executionId, request.stepIndex, request.attempt, pdmScopeType, scopeId
                    ),
                    // Stage 1A — bounded reservation lifetime: an abandoned
                    // hold stops suppressing availability after the TTL via
                    // the EXISTING lazy balance filter (no sweep/restart
                    // needed). A subsequent billable dispatch always
                    // authorizes fresh (new step/attempt identity).
                    expiresAtEpochMs = now + HoldKeys.DEFAULT_HOLD_TTL_MS,
                    nowEpochMs = now
                )
            ) {
                is BudgetLedgerService.HoldReservation.Reserved ->
                    EconomicAuthorizationResult(
                        decision = EconomicGateDecision.ALLOWED,
                        reason = "HOLD-BASED BUDGET: حجز ${reservation.holdAmountMicroUnits} micro-" +
                            "${active.policy.currency} للنطاق ${pdmScopeType}:$scopeId " +
                            "(hold=${reservation.holdId})؛ المتاح بعده ${reservation.availableAfterMicroUnits}" +
                            conversionNote +
                            (
                                if (reservation.replayed) " [replay]"
                                else " (ينتهي بعد ${HoldKeys.DEFAULT_HOLD_TTL_MS / 3_600_000L} ساعة)"
                                ),
                        evaluatedScopes = evaluatedScopes,
                        rateLimitStatus = rateLimitStatus,
                        estimate = estimate,
                        appliedPolicy = null
                    )

                is BudgetLedgerService.HoldReservation.ExpiredReplay ->
                    // STAGE 2C (DEBT-2): the replay of an authorization whose
                    // original hold EXPIRED carries NO spending authority —
                    // availability was lazily reclaimed and the reservation is
                    // NOT renewed, NOT re-created under the same idempotency
                    // identity. The honest verdict is an explicit DENY naming
                    // the attempt-increment contract: a genuine redispatch
                    // MUST carry a new attempt identity (a durable resume
                    // already increments it), which authorizes fresh.
                    EconomicAuthorizationResult(
                        decision = EconomicGateDecision.DENIED,
                        reason = "HOLD-BASED BUDGET (DEBT-2): الحجز الأصلي (hold=${reservation.holdId}) " +
                            "للمحاولة نفسها (${request.executionId}, step=${request.stepIndex}, " +
                            "attempt=${request.attempt}) انتهت مدته — لا سلطة إنفاق متبقية ولا يُجدَّد الحجز " +
                            "تحت الهوية نفسها. أعد الإرسال بهوية محاولة جديدة (attempt+1) للحجز من جديد.",
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
        // No scope produced a modern verdict. If a modern policy exists whose
        // period has ended, that expired authority OWNS the request: an
        // honest DENY, never a silent fallback to the stale legacy
        // allocation (Stage 2 transition semantics — see the class doc).
        expiredPeriodScope?.let { (type, id) ->
            return EconomicAuthorizationResult(
                decision = EconomicGateDecision.DENIED,
                reason = "HOLD-BASED BUDGET: سياسة محاسبية نشطة للنطاق $type:$id انتهت فترتها المحاسبية — " +
                    "لا سلطة إنفاق سارية. راجع شاشة الميزانية لفتح فترة محاسبية جديدة؛ " +
                    "لا يُسمح بارتداد صامت إلى التخصيص القديم (legacy) بعد الانتقال للمسار المحاسبي.",
                evaluatedScopes = evaluatedScopes,
                rateLimitStatus = rateLimitStatus,
                estimate = estimate,
                appliedPolicy = null
            )
        }
        return null
    }

    private fun mapOverage(
        denial: BudgetLedgerService.HoldReservation.Denied,
        active: BudgetLedgerService.ActiveBudget,
        pdmScopeType: String,
        // Build-fix 2026-10-16: String? — the global APPLICATION catch-all
        // carries the canonical platform-null scopeId (ScopeEntry.fixedScopeId),
        // matching activeBudgetForScope's nullable platformObjectId.
        scopeId: String?,
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
