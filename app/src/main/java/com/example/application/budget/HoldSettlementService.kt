package com.example.application.budget

import com.example.domain.core.budget.UsageAccountingInput
import com.example.infrastructure.persistence.AppDatabase

/**
 * ============================================================================
 * HoldSettlementService — the I-5 production settlement hooks (Stage 2)
 * ============================================================================
 * Interpretation I-5 (Stage-1 register): "production settlement hooks
 * (accountUsage -> settleHold) are the FIRST Stage-2 item; the approved
 * Day 8-9 scope wires authorize() only." Before this service, a hold
 * created at authorization NEVER met its actual cost: the actual was
 * written to the legacy cost ledger only, and the hold kept suppressing
 * `available` until lazy expiry. This closes the cycle:
 *
 *   authorize()  → authorizeAndReserve (Stage 1, reason="exec:{id}")
 *   accountUsage → THIS service settles/releases/posts (Stage 2)
 *
 * Correlation key: the hold's `reason` field, written verbatim by
 * HoldBasedBudgetGate as "exec:{executionId}".
 *
 * Policy decisions recorded in the stage register:
 *   I-10  actual cost UNKNOWN → the hold is KEPT until lazy expiry
 *         (conservative hard-budget semantics; UNKNOWN is never zero and
 *         nothing is fabricated into the ledger).
 *   I-11  actual > hold → settle the full hold, post the excess as an
 *         ACTUAL consumption entry — actuals are never truncated.
 *   I-18  hold currency ≠ cost currency → NO SILENT CONVERSION (an
 *         assumed 1:1 rate is a fabricated fact); Stage 3 closes this
 *         honestly: when a [CurrencyConversionService] is wired AND a
 *         windowed rule covers the settlement instant, the actual is
 *         converted with the recorded rate (evidence lives on the rule +
 *         the reconciliation layer); without a rule the honest no-op
 *         stands — UNKNOWN is never one.
 *
 * STAGE 2B (WS-A) closes the I-18 remaining gap: without a covering rule
 * the actual is no longer DROPPED from the accounting record — a DRAFT
 * ledger entry (zero balance effect, append-only) preserves the original
 * amount + currency, the hold is KEPT under its bounded TTL (the
 * conservative I-10 family), and a later settlement retry after a rule
 * is registered converts and settles normally (the DRAFT row stays as
 * the historical mismatch record). The posted/settled entries carry the
 * FX attribution columns (original amount, original currency, rule id)
 * so historical conversions stay reproducible.
 */
class HoldSettlementService(
    private val database: AppDatabase,
    private val ledger: BudgetLedgerService,
    private val telemetry: com.example.application.observability.TelemetryService? = null,
    private val fx: CurrencyConversionService? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    sealed class SettlementSummary {
        /** Full cycle closed: actual settled, remainder released. */
        data class SettledAndReleased(
            val holdId: String,
            val settledMicroUnits: Long,
            val releasedMicroUnits: Long
        ) : SettlementSummary()

        /** Actual exactly consumed the hold (or a partial settle to zero). */
        data class Settled(
            val holdId: String,
            val settledMicroUnits: Long,
            val replayed: Boolean
        ) : SettlementSummary()

        /** Actual exceeded the hold: hold settled + excess posted (I-11). */
        data class SettledWithOverage(
            val holdId: String,
            val settledMicroUnits: Long,
            val overagePostedMicroUnits: Long
        ) : SettlementSummary()

        /** No hold survived; actual posted directly under the active policy. */
        data class PostedWithoutHold(val entryId: String) : SettlementSummary()

        /**
         * Stage 1A — the step's reservation was released WITHOUT settlement:
         * the operation was proven never dispatched (D), or was conclusively
         * known to cost zero (B). A RELEASE settlement exists, NO ledger
         * entry is ever created (G06 — releasing is not a positive effect).
         */
        data class ReleasedAsUnused(
            val holdId: String?,
            val releasedMicroUnits: Long,
            /** ZERO_ACTUAL_COST | UNDISPATCHED */
            val cause: String,
            val replayed: Boolean
        ) : SettlementSummary()

        /** Nothing to do — honest no-op with the reason recorded. */
        data class NoAction(val reason: String) : SettlementSummary()
    }

    /**
     * The post-execution hook — the Stage 1A hold-lifecycle decision table.
     * [actualCostMicroUnits] is the true cost of the billable operation
     * (null = UNKNOWN); [usageRecordId] links the posted entries to the
     * Stage-2 usage facts when available. The input carries the canonical
     * (executionId, stepIndex, attempt) identity and the PROVEN dispatch
     * state of the operation.
     *
     * Decision table (in order — the first matching arm wins):
     *
     *  A. actual > 0 (known positive cost):
     *       settle against the matching reservation, release the unused
     *       remainder, post any excess as actual consumption (I-11 — never
     *       truncated). [Settled]/[SettledAndReleased]/[SettledWithOverage]
     *
     *  B. actual == 0 (CONCLUSIVELY known zero — a usage report that shows
     *       zero consumption with pricing resolved): release the matching
     *       reservation idempotently. A positive reservation must not stay
     *       active merely because the real cost equals zero.
     *       [ReleasedAsUnused(ZERO_ACTUAL_COST)]
     *
     *  C. actual == null (UNKNOWN — missing usage report, unresolvable
     *       pricing, failed response): NOT evidence of zero. The hold is
     *       KEPT under the bounded-expiry policy (I-10); nothing is
     *       fabricated. [NoAction(UNKNOWN_ACTUAL_COST)]
     *
     *  D. dispatchState == NOT_DISPATCHED (the provider call definitely
     *       never started — proven from execution state, not a generic
     *       failure label): release the matching reservation idempotently.
     *       [ReleasedAsUnused(UNDISPATCHED)]
     *
     *  E. dispatched but no usable usage report: identical to C — the
     *       reservation is retained under the bounded TTL (UNKNOWN is never
     *       zero and a dispatched request with unknown cost is never
     *       "safely unsent").
     *
     * A-arm ordering note: a known POSITIVE cost always wins over the
     * dispatch state — if real cost evidence exists it is recorded, even
     * when the dispatch state disagrees (defensive; the two never contradict
     * in practice). Every arm is idempotent under retries and concurrent
     * operations via the unique (hold, idempotency_key) / (period,
     * idempotency_key) indexes, and safe after the hold expired (the actual
     * is posted without the hold — real known consumption is never
     * discarded merely because its reservation expired).
     */
    suspend fun settleExecutionUsage(
        input: UsageAccountingInput,
        actualCostMicroUnits: Long?,
        costCurrency: String,
        usageRecordId: String? = null
    ): SettlementSummary {
        val now = clock()
        val reason = HoldKeys.holdReason(input.executionId, input.stepIndex, input.attempt)

        // ---- A. known positive actual cost: the full settle machinery ----
        if (actualCostMicroUnits != null && actualCostMicroUnits > 0) {
            return settlePositiveActual(
                input = input,
                reason = reason,
                actualCostMicroUnits = actualCostMicroUnits,
                costCurrency = costCurrency,
                usageRecordId = usageRecordId,
                now = now
            )
        }

        // ---- D. proven never dispatched: release, never fabricate ----
        if (input.dispatchState == com.example.domain.core.budget.UsageDispatchState.NOT_DISPATCHED) {
            return releaseStepHolds(
                reason = reason,
                input = input,
                now = now,
                cause = "UNDISPATCHED"
            )
        }

        // ---- C. UNKNOWN actual cost: conservative keep (I-10) ----
        if (actualCostMicroUnits == null) {
            return SettlementSummary.NoAction("UNKNOWN_ACTUAL_COST")
        }

        // ---- B. conclusively known zero cost: release the reservation ----
        return releaseStepHolds(
            reason = reason,
            input = input,
            now = now,
            cause = "ZERO_ACTUAL_COST"
        )
    }

    /**
     * Stage 1A arm A — a known POSITIVE actual cost. This is the Stage-2/6
     * machinery verbatim (settle against the oldest matching hold, I-11
     * overage posting, F-1 multi-hold release, F-2 replay honesty, F-3
     * stale-snapshot replay detection), keyed by the canonical per-step
     * identity from [HoldKeys].
     */
    private suspend fun settlePositiveActual(
        input: UsageAccountingInput,
        reason: String,
        actualCostMicroUnits: Long,
        costCurrency: String,
        usageRecordId: String?,
        now: Long
    ): SettlementSummary {
        val holds = database.budgetHoldDao().mutableHoldsForReason(reason)

        if (holds.isEmpty()) {
            // replay detection: this step WAS hold-authorized and the hook
            // already ran (a SETTLE settlement with the step's settle key
            // exists) — replaying is a no-op, never a second posting. The
            // first settlement is authoritative; revised provider reports
            // supersede via Stage-3 reconciliation, not by double-posting.
            // F-3 (Stage 6): the key is checked on EVERY hold of the step —
            // with the multi-hold release (F-1) the settle may live on the
            // oldest while firstOrNull returned another.
            if (settleKeyAlreadyUsed(reason, input)) {
                return SettlementSummary.NoAction("ALREADY_SETTLED")
            }
            return postActualWithoutHold(input, actualCostMicroUnits, costCurrency, usageRecordId, now)
        }

        // The gate creates exactly one hold per billable operation; if more
        // ever exist (e.g. the two gates resolved different scopes before
        // Stage 1A unified them), settle against the oldest and release the
        // rest (documented deterministic choice).
        val hold = holds.first()

        // F-3 (Stage 6, G09): a stale hold snapshot is NOT proof the settle
        // never ran. A slow replay whose snapshot predates the first
        // worker's settle+release reaches this point with
        // totalSettledMicro == the full hold (SETTLE + RELEASE both count)
        // and would "post without hold" — a SECOND consumption entry for
        // one actual. The step's settle key is the identity: if it exists
        // on ANY hold of this step, this call is a replay. The release
        // re-attempt (same key) completes a cycle whose original call died
        // between settle and release — idempotent, never a fresh posting.
        if (settleKeyAlreadyUsed(reason, input)) {
            ledger.releaseHold(
                holdId = hold.holdId,
                idempotencyKey = HoldKeys.releaseKey(input.executionId, input.stepIndex, input.attempt),
                nowEpochMs = now
            )
            return SettlementSummary.NoAction("ALREADY_SETTLED")
        }

        var effectiveActualMicro = actualCostMicroUnits
        var effectiveActualCurrency = costCurrency
        var fxAttribution: BudgetLedgerService.FxAttribution? = null
        if (hold.currency != costCurrency) {
            // I-18 (Stage-3 closure): convert the actual into the hold's
            // currency ONLY with a recorded windowed rule; never assume
            // 1:1. STAGE 2B: without a rule the actual is PRESERVED on a
            // DRAFT ledger entry (zero balance effect) instead of being
            // silently discarded, and the hold is KEPT — a settlement
            // retry after a rule is registered completes the cycle.
            val converted = fx?.convert(
                amountMicroUnits = actualCostMicroUnits,
                fromCurrency = costCurrency,
                toCurrency = hold.currency,
                atEpochMs = now
            )
            if (converted is CurrencyConversionService.ConversionResult.Converted) {
                effectiveActualMicro = converted.targetMicroUnits
                effectiveActualCurrency = hold.currency
                fxAttribution = BudgetLedgerService.FxAttribution(
                    originalAmountPrecise = converted.preRoundedPrecise,
                    originalCurrency = costCurrency,
                    conversionRuleId = converted.ruleId.ifEmpty { null }
                )
            } else {
                telemetry?.incrementCounter(
                    action = "BUDGET_SETTLEMENT_CURRENCY_MISMATCH",
                    dimensions = com.example.domain.core.observability.MetricDimensions(
                        executionId = input.executionId,
                        workspaceId = input.workspaceId,
                        providerId = input.providerId,
                        agentId = input.agentId
                    )
                )
                // The real cost is recorded as DRAFT evidence under the
                // hold's period (never discarded, never 1:1, zero balance
                // effect) — distinct idempotency key so the eventual
                // converted settlement (the standard settle key) can still
                // run after a rule is registered.
                runCatching {
                    ledger.recordUnconvertibleActual(
                        periodId = hold.budgetPeriodId,
                        originalAmountMicroUnits = actualCostMicroUnits,
                        originalCurrency = costCurrency,
                        reason = reason,
                        idempotencyKey = HoldKeys.settleKey(input.executionId, input.stepIndex, input.attempt) +
                            ":FX-UNCONVERTIBLE",
                        holdId = hold.holdId,
                        usageRecordId = usageRecordId,
                        nowEpochMs = now
                    )
                }
                return SettlementSummary.NoAction(
                    "CURRENCY_MISMATCH_NO_RULE:${hold.currency}!=$costCurrency"
                )
            }
        }

        val settledSoFar = database.budgetHoldSettlementDao().totalSettledMicro(hold.holdId)
        val remaining = hold.holdAmountMicroUnits - settledSoFar
        if (remaining <= 0) {
            return postActualWithoutHold(input, effectiveActualMicro, effectiveActualCurrency, usageRecordId, now)
        }

        val settleable = minOf(effectiveActualMicro, remaining)

        val summary = when (
            val outcome = ledger.settleHold(
                holdId = hold.holdId,
                actualAmountMicroUnits = settleable,
                idempotencyKey = HoldKeys.settleKey(input.executionId, input.stepIndex, input.attempt),
                nowEpochMs = now,
                fxAttribution = fxAttribution
            )
        ) {
            is BudgetLedgerService.SettlementOutcome.Settled -> {
                if (outcome.replayed) {
                    // F-2 (Stage 6, G06/G09): a REPLAYED settle means this
                    // step's settlement already ran to its settle — the
                    // overage decision belonged to the original call, and a
                    // fresh overage computed from THIS call's (possibly
                    // stale) `settleable` would fabricate a ledger entry.
                    // Re-attempt the release with the standard key only:
                    // idempotent by the unique index, and it completes the
                    // release if the original call died between settle and
                    // release. NEVER a fresh overage on a replay.
                    ledger.releaseHold(
                        holdId = hold.holdId,
                        idempotencyKey = HoldKeys.releaseKey(input.executionId, input.stepIndex, input.attempt),
                        nowEpochMs = now
                    )
                    SettlementSummary.Settled(hold.holdId, outcome.amountMicroUnits, replayed = true)
                } else {
                    val overage = effectiveActualMicro - settleable
                    if (overage > 0) {
                        // I-11: the excess is a real cost — post it, never truncate.
                        // The overage is denominated in the HOLD's currency
                        // (the converted actual) — postUsageActual re-checks
                        // it against the ACTIVE policy's currency.
                        val scope = holdScope(hold.holdId)
                        val posted = ledger.postUsageActual(
                            scopeType = scope?.first ?: GLOBAL_APPLICATION,
                            platformObjectId = scope?.second,
                            amountMicroUnits = overage,
                            reason = "overage:" + HoldKeys.holdReason(input.executionId, input.stepIndex, input.attempt),
                            idempotencyKey = HoldKeys.overageKey(input.executionId, input.stepIndex, input.attempt),
                            usageRecordId = usageRecordId,
                            nowEpochMs = now,
                            amountCurrency = hold.currency
                        )
                        if (posted is BudgetLedgerService.UsagePostingOutcome.Posted) {
                            SettlementSummary.SettledWithOverage(hold.holdId, settleable, overage)
                        } else {
                            // policy raced away — the hold-side settle stands, the
                            // overage has nowhere honest to land; report it.
                            telemetry?.incrementCounter(
                                action = "BUDGET_OVERAGE_UNPOSTED",
                                dimensions = com.example.domain.core.observability.MetricDimensions(
                                    executionId = input.executionId,
                                    workspaceId = input.workspaceId,
                                    providerId = input.providerId,
                                    agentId = input.agentId
                                )
                            )
                            SettlementSummary.SettledWithOverage(hold.holdId, settleable, 0)
                        }
                    } else {
                        val release = remaining - settleable
                        if (release > 0) {
                            // G06: release records a RELEASE settlement only —
                            // never a positive ledger effect.
                            ledger.releaseHold(
                                holdId = hold.holdId,
                                idempotencyKey = HoldKeys.releaseKey(input.executionId, input.stepIndex, input.attempt),
                                nowEpochMs = now
                            )
                            SettlementSummary.SettledAndReleased(hold.holdId, settleable, release)
                        } else {
                            SettlementSummary.Settled(hold.holdId, settleable, outcome.replayed)
                        }
                    }
                }
            }

            is BudgetLedgerService.SettlementOutcome.HoldNotFound,
            is BudgetLedgerService.SettlementOutcome.InvalidHoldState ->
                // expired/settled between the read and the settle — the
                // actual still belongs in the ledger of the active policy
                // (in the hold's currency when FX applied, else as reported).
                // Stage 1A: real known consumption is NEVER discarded merely
                // because its original reservation expired.
                postActualWithoutHold(input, effectiveActualMicro, effectiveActualCurrency, usageRecordId, now)

            is BudgetLedgerService.SettlementOutcome.OverSettlement ->
                // cannot happen (settleable ≤ remaining by construction);
                // fail honest rather than guess.
                SettlementSummary.NoAction("OVER_SETTLEMENT:${outcome.requestedMicroUnits}")
        }

        // F-1 (Stage 6, G06): the documented deterministic choice — the
        // oldest hold takes the settlement; every OTHER mutable hold for
        // the same step is RELEASED so availability recovers
        // deterministically instead of waiting for the bounded expiry. A
        // release records a RELEASE settlement only and never a positive
        // ledger effect, exactly like the primary release path above.
        for (other in holds.drop(1)) {
            ledger.releaseHold(
                holdId = other.holdId,
                idempotencyKey = HoldKeys.releaseKey(input.executionId, input.stepIndex, input.attempt) +
                    ":${other.holdId}",
                nowEpochMs = now
            )
        }
        return summary
    }

    /**
     * Stage 1A arms B and D — release the step's reservations WITHOUT
     * settlement: the operation was conclusively zero-cost or proven never
     * dispatched. Idempotent (unique release keys), never fabricates a
     * ledger entry (G06), and honest when nothing is there to release
     * (legacy workspaces / no active policy → no hold ever existed).
     */
    private suspend fun releaseStepHolds(
        reason: String,
        input: UsageAccountingInput,
        now: Long,
        cause: String
    ): SettlementSummary {
        val releaseKey = HoldKeys.releaseKey(input.executionId, input.stepIndex, input.attempt)
        val mutable = database.budgetHoldDao().mutableHoldsForReason(reason)

        if (mutable.isEmpty()) {
            // Either no hold ever existed for this step (legacy path —
            // honest no-op) or this call is a REPLAY of an earlier
            // release whose hold is already terminal. Distinguish by the
            // release key on any hold of this step's reason.
            val alreadyReleased = database.budgetHoldDao().allForReason(reason).any { h ->
                database.budgetHoldSettlementDao().byIdempotencyKey(h.holdId, releaseKey) != null ||
                    database.budgetHoldSettlementDao()
                        .byIdempotencyKey(h.holdId, "$releaseKey:${h.holdId}") != null
            }
            return if (alreadyReleased) {
                SettlementSummary.ReleasedAsUnused(
                    holdId = database.budgetHoldDao().allForReason(reason).firstOrNull()?.holdId,
                    releasedMicroUnits = 0L,
                    cause = cause,
                    replayed = true
                )
            } else {
                SettlementSummary.NoAction("NO_ACTIVE_HOLD")
            }
        }

        // Release the FIRST mutable hold under the plain key; every other
        // mutable hold (defensive multi-hold case) under a hold-qualified
        // key — both idempotent by the unique (hold, idempotency_key) index.
        var totalReleased = 0L
        for ((index, hold) in mutable.withIndex()) {
            val key = if (index == 0) releaseKey else "$releaseKey:${hold.holdId}"
            when (val outcome = ledger.releaseHold(holdId = hold.holdId, idempotencyKey = key, nowEpochMs = now)) {
                is BudgetLedgerService.ReleaseOutcome.Released -> totalReleased += outcome.releasedMicroUnits
                else -> Unit // HoldNotFound/InvalidHoldState/NothingToRelease — raced terminal; honest
            }
        }
        return SettlementSummary.ReleasedAsUnused(
            holdId = mutable.first().holdId,
            releasedMicroUnits = totalReleased,
            cause = cause,
            replayed = false
        )
    }

    /**
     * No surviving hold: the actual lands directly under the FIRST active
     * accounting policy covering the request's scopes (the gate's own
     * order — workspace → agent → task → provider → APPLICATION global),
     * honoring the I-3 promise that "the actual cost is posted at
     * settlement". STAGE 2B (WS-A): the cost's currency is declared to
     * the ledger — a policy in a different currency converts it with the
     * recorded rule (attribution on the entry) or records it as DRAFT
     * evidence when no rule covers the instant (never 1:1, never
     * discarded). No active policy → honest no-op (legacy path).
     */
    private suspend fun postActualWithoutHold(
        input: UsageAccountingInput,
        actualCostMicroUnits: Long,
        costCurrency: String,
        usageRecordId: String?,
        now: Long
    ): SettlementSummary {
        for ((scopeType, scopeId) in candidateScopes(input)) {
            when (
                val posted = ledger.postUsageActual(
                    scopeType = scopeType,
                    platformObjectId = scopeId,
                    amountMicroUnits = actualCostMicroUnits,
                    reason = HoldKeys.actualKey(input.executionId, input.stepIndex, input.attempt),
                    idempotencyKey = HoldKeys.actualKey(input.executionId, input.stepIndex, input.attempt),
                    usageRecordId = usageRecordId,
                    nowEpochMs = now,
                    amountCurrency = costCurrency
                )
            ) {
                is BudgetLedgerService.UsagePostingOutcome.Posted ->
                    return SettlementSummary.PostedWithoutHold(posted.entryId)
                is BudgetLedgerService.UsagePostingOutcome.RecordedUnconverted ->
                    // The cost is preserved as DRAFT evidence (original
                    // amount + currency) — recorded, not posted, never
                    // silently dropped, never converted 1:1.
                    return SettlementSummary.NoAction(
                        "CURRENCY_MISMATCH_NO_RULE_RECORDED_AS_DRAFT:${posted.originalCurrency}"
                    )
                is BudgetLedgerService.UsagePostingOutcome.NoActivePolicy ->
                    continue // try the next, less specific scope
            }
        }
        return SettlementSummary.NoAction("NO_ACTIVE_POLICY")
    }

    /** The gate's documented scope order, request-derived. */
    private fun candidateScopes(input: UsageAccountingInput): List<Pair<String, String?>> = buildList {
        input.workspaceId?.let { add("WORKSPACE" to it) }
        input.agentId?.let { add("AGENT" to it) }
        input.taskId?.let { add("TASK" to it) }
        input.providerId?.let { add("PROVIDER" to it) }
        add(GLOBAL_APPLICATION to null)
    }

    /** (scopeType, platformObjectId) of the budget a hold belongs to. */
    private suspend fun holdScope(holdId: String): Pair<String, String?>? {
        val hold = database.budgetHoldDao().byId(holdId) ?: return null
        val period = database.budgetPeriodDao().byId(hold.budgetPeriodId) ?: return null
        val policy = database.budgetPolicyDao().byId(period.budgetPolicyId) ?: return null
        val descriptor = database.scopeDescriptorDao().byId(policy.scopeId)
        return policy.budgetScopeType to descriptor?.platformObjectId
    }

    /**
     * F-3 (Stage 6, rekeyed Stage 1A): does ANY hold of this billable
     * operation already carry its settle key? The key embeds the canonical
     * (executionId, stepIndex, attempt) identity, so a hit means the
     * settlement already ran — whatever a stale mutable-holds snapshot
     * said.
     */
    private suspend fun settleKeyAlreadyUsed(reason: String, input: UsageAccountingInput): Boolean {
        val key = HoldKeys.settleKey(input.executionId, input.stepIndex, input.attempt)
        return database.budgetHoldDao().allForReason(reason).any { h ->
            database.budgetHoldSettlementDao().byIdempotencyKey(h.holdId, key) != null
        }
    }

    companion object {
        /** G01 documented equivalence: SYSTEM ≡ APPLICATION (global catch-all). */
        const val GLOBAL_APPLICATION = "APPLICATION"
    }
}
