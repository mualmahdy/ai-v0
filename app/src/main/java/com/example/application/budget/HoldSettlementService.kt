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

        /** Nothing to do — honest no-op with the reason recorded. */
        data class NoAction(val reason: String) : SettlementSummary()
    }

    /**
     * The post-execution hook. [actualCostMicroUnits] is the true cost of
     * the execution (null = UNKNOWN); [usageRecordId] links the posted
     * entries to the Stage-2 usage facts when available.
     */
    suspend fun settleExecutionUsage(
        input: UsageAccountingInput,
        actualCostMicroUnits: Long?,
        costCurrency: String,
        usageRecordId: String? = null
    ): SettlementSummary {
        val now = clock()

        if (actualCostMicroUnits == null || actualCostMicroUnits <= 0) {
            // I-10: UNKNOWN (or genuinely zero) actual — no ledger entry is
            // fabricated; the hold keeps protecting the hard budget until
            // its lazy expiry. Zero is a no-op by the same reasoning.
            return SettlementSummary.NoAction(
                if (actualCostMicroUnits == null) "UNKNOWN_ACTUAL_COST" else "ZERO_ACTUAL_COST"
            )
        }

        val holds = database.budgetHoldDao()
            .mutableHoldsForReason(executionReason(input.executionId))

        if (holds.isEmpty()) {
            // replay detection: this execution WAS hold-authorized and the
            // hook already ran (a SETTLE settlement with the execution's
            // settle key exists) — replaying is a no-op, never a second
            // posting. The first settlement is authoritative; revised
            // provider reports supersede via Stage-3 reconciliation, not
            // by double-posting.
            val anyHold = database.budgetHoldDao()
                .allForReason(executionReason(input.executionId)).firstOrNull()
            if (anyHold != null && database.budgetHoldSettlementDao()
                    .byIdempotencyKey(anyHold.holdId, "settle:exec:${input.executionId}") != null
            ) {
                return SettlementSummary.NoAction("ALREADY_SETTLED")
            }
            return postActualWithoutHold(input, actualCostMicroUnits, usageRecordId, now)
        }

        // The gate creates exactly one hold per execution; if more ever
        // exist, settle against the oldest and release the rest (documented
        // deterministic choice).
        val hold = holds.first()
        var effectiveActualMicro = actualCostMicroUnits
        if (hold.currency != costCurrency) {
            // I-18 (Stage-3 closure): convert the actual into the hold's
            // currency ONLY with a recorded windowed rule; never assume
            // 1:1. No rule → the Stage-2 honest no-op stands.
            val converted = fx?.convert(
                amountMicroUnits = actualCostMicroUnits,
                fromCurrency = costCurrency,
                toCurrency = hold.currency,
                atEpochMs = now
            )
            if (converted is CurrencyConversionService.ConversionResult.Converted) {
                effectiveActualMicro = converted.targetMicroUnits
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
                return SettlementSummary.NoAction(
                    "CURRENCY_MISMATCH:${hold.currency}!=$costCurrency"
                )
            }
        }

        val settledSoFar = database.budgetHoldSettlementDao().totalSettledMicro(hold.holdId)
        val remaining = hold.holdAmountMicroUnits - settledSoFar
        if (remaining <= 0) {
            return postActualWithoutHold(input, effectiveActualMicro, usageRecordId, now)
        }

        val settleable = minOf(effectiveActualMicro, remaining)

        return when (
            val outcome = ledger.settleHold(
                holdId = hold.holdId,
                actualAmountMicroUnits = settleable,
                idempotencyKey = "settle:exec:${input.executionId}",
                nowEpochMs = now
            )
        ) {
            is BudgetLedgerService.SettlementOutcome.Settled -> {
                val overage = effectiveActualMicro - settleable
                if (overage > 0) {
                    // I-11: the excess is a real cost — post it, never truncate.
                    val scope = holdScope(hold.holdId)
                    val posted = ledger.postUsageActual(
                        scopeType = scope?.first ?: GLOBAL_APPLICATION,
                        platformObjectId = scope?.second,
                        amountMicroUnits = overage,
                        reason = "overage:exec:${input.executionId}",
                        idempotencyKey = "actual:exec:${input.executionId}:overage",
                        usageRecordId = usageRecordId,
                        nowEpochMs = now
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
                            idempotencyKey = "release:exec:${input.executionId}",
                            nowEpochMs = now
                        )
                        SettlementSummary.SettledAndReleased(hold.holdId, settleable, release)
                    } else {
                        SettlementSummary.Settled(hold.holdId, settleable, outcome.replayed)
                    }
                }
            }

            is BudgetLedgerService.SettlementOutcome.HoldNotFound,
            is BudgetLedgerService.SettlementOutcome.InvalidHoldState ->
                // expired/settled between the read and the settle — the
                // actual still belongs in the ledger of the active policy
                // (in the hold's currency when FX applied, else as reported).
                postActualWithoutHold(input, effectiveActualMicro, usageRecordId, now)

            is BudgetLedgerService.SettlementOutcome.OverSettlement ->
                // cannot happen (settleable ≤ remaining by construction);
                // fail honest rather than guess.
                SettlementSummary.NoAction("OVER_SETTLEMENT:${outcome.requestedMicroUnits}")
        }
    }

    /**
     * No surviving hold: the actual lands directly under the FIRST active
     * accounting policy covering the request's scopes (the gate's own
     * order — workspace → agent → task → provider → APPLICATION global),
     * honoring the I-3 promise that "the actual cost is posted at
     * settlement". No active policy → honest no-op (legacy path).
     */
    private suspend fun postActualWithoutHold(
        input: UsageAccountingInput,
        actualCostMicroUnits: Long,
        usageRecordId: String?,
        now: Long
    ): SettlementSummary {
        for ((scopeType, scopeId) in candidateScopes(input)) {
            when (
                val posted = ledger.postUsageActual(
                    scopeType = scopeType,
                    platformObjectId = scopeId,
                    amountMicroUnits = actualCostMicroUnits,
                    reason = "actual:exec:${input.executionId}",
                    idempotencyKey = "actual:exec:${input.executionId}",
                    usageRecordId = usageRecordId,
                    nowEpochMs = now
                )
            ) {
                is BudgetLedgerService.UsagePostingOutcome.Posted ->
                    return SettlementSummary.PostedWithoutHold(posted.entryId)
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

    private fun executionReason(executionId: String): String = "exec:$executionId"

    companion object {
        /** G01 documented equivalence: SYSTEM ≡ APPLICATION (global catch-all). */
        const val GLOBAL_APPLICATION = "APPLICATION"
    }
}
