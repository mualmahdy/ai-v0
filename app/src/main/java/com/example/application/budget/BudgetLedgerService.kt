package com.example.application.budget

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetHoldSettlementEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import com.example.infrastructure.persistence.entities.BudgetPeriodEntity
import com.example.infrastructure.persistence.entities.BudgetPolicyEntity
import com.example.infrastructure.persistence.entities.ScopeDescriptorEntity
import java.security.MessageDigest

/**
 * ============================================================================
 * BudgetLedgerService — the accounting budget core (Stage-1, Section و)
 * ============================================================================
 * Approved 2026-10-14 ("أعتمد الكل", D-7). Four atomic operations over the
 * five-table core, every one of them a SINGLE Room transaction:
 *
 *   authorizeAndReserve — hold-based pre-execution authorization
 *   settleHold          — actual cost vs the hold (partial supported)
 *   releaseHold         — free the remaining hold, NO ledger entry (G06)
 *   postAdjustment      — ADJUSTMENT / REVERSAL corrections
 *
 * Governing rules baked into this service (Section و.5):
 *  - The available balance is computed EXCLUSIVELY from stored rows
 *    (equation و.2); no external "theoretical balance" is ever accepted.
 *  - Concurrency (ز-1): holds are inserted inside the same transaction
 *    that reads the balance; SQLite's single-writer serialization makes
 *    over-commitment impossible.
 *  - Idempotency (ز-2): read-before-insert replay + UNIQUE keys; a retried
 *    operation with the same key returns the ORIGINAL outcome.
 *  - Expiry (ز-9): lazy evaluation — the balance ignores an expired hold
 *    immediately, before the background cleaner marks it EXPIRED.
 *  - UNKNOWN cost is never zero: an unknown estimate creates NO hold and
 *    an entry may carry entry_basis='UNKNOWN' without touching available.
 *
 * The service never mutates a POSTED ledger entry and never deletes one;
 * corrections are REVERSAL entries with the exact opposite effect.
 */
class BudgetLedgerService(
    private val database: AppDatabase,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {

    // ------------------------------------------------------------------
    // Domain result types
    // ------------------------------------------------------------------

    /** Verdict of [authorizeAndReserve]. */
    sealed class HoldReservation {
        /** Hold created (or an identical earlier one replayed). */
        data class Reserved(
            val holdId: String,
            val periodId: String,
            val holdAmountMicroUnits: Long,
            val availableAfterMicroUnits: Long,
            val replayed: Boolean
        ) : HoldReservation()

        /** Insufficient available balance; no hold was created. */
        data class Denied(
            val requiredMicroUnits: Long,
            val availableMicroUnits: Long,
            val overagePolicy: String,
            val periodId: String
        ) : HoldReservation()

        /** No active policy/period for the scope — caller falls back. */
        object NoActivePolicy : HoldReservation()
    }

    /** Verdict of [settleHold]. */
    sealed class SettlementOutcome {
        data class Settled(
            val settlementId: String,
            val ledgerEntryId: String,
            val holdStatusAfter: String,
            val amountMicroUnits: Long,
            val replayed: Boolean
        ) : SettlementOutcome()

        object HoldNotFound : SettlementOutcome()
        data class InvalidHoldState(val status: String) : SettlementOutcome()
        data class OverSettlement(
            val settledSoFarMicroUnits: Long,
            val holdAmountMicroUnits: Long,
            val requestedMicroUnits: Long
        ) : SettlementOutcome()
    }

    /** Verdict of [releaseHold]. */
    sealed class ReleaseOutcome {
        data class Released(
            val settlementId: String?,
            val releasedMicroUnits: Long,
            val replayed: Boolean
        ) : ReleaseOutcome()

        object HoldNotFound : ReleaseOutcome()
        data class InvalidHoldState(val status: String) : ReleaseOutcome()
        object NothingToRelease : ReleaseOutcome()
    }

    /** Verdict of [postAdjustment]. */
    sealed class AdjustmentOutcome {
        data class Posted(val entryId: String, val replayed: Boolean) : AdjustmentOutcome()
        object PeriodNotFound : AdjustmentOutcome()
        data class InvalidReversal(val reason: String) : AdjustmentOutcome()
    }

    /** The balance equation terms, always recomputed from raw rows. */
    data class AvailableBudget(
        val periodId: String,
        val limitMicroUnits: Long,
        val postedEffectsMicroUnits: Long,
        val activeHoldRemainingMicroUnits: Long
    ) {
        val availableMicroUnits: Long
            get() = limitMicroUnits + postedEffectsMicroUnits - activeHoldRemainingMicroUnits
    }

    /** Active policy + covering period for a scope (peek, no side effects). */
    data class ActiveBudget(
        val policy: BudgetPolicyEntity,
        val period: BudgetPeriodEntity
    )

    // ------------------------------------------------------------------
    // Policy management (scope descriptor + policy + period)
    // ------------------------------------------------------------------

    /** Request to create a v1 policy with its first open period. */
    data class CreateBudgetRequest(
        val scopeType: String,
        val platformObjectType: String?,
        val platformObjectId: String?,
        val providerId: String?,
        val budgetName: String,
        val currency: String,
        val limitMicroUnits: Long,
        val periodStartEpochMs: Long,
        val periodEndEpochMs: Long,
        val periodType: String = "FIXED_WINDOW",
        val rolloverPolicy: String = "NO_ROLLOVER",
        val overagePolicy: String = OVERAGE_HARD_LIMIT,
        val allocationPolicy: String = "SUPERVISORY_OVERLAP"
    )

    data class CreatedBudget(
        val scopeId: String,
        val policyId: String,
        val policyFamilyId: String,
        val periodId: String
    )

    /**
     * Creates (idempotently) the scope descriptor, the v1 policy and its
     * first OPEN period in one transaction. Re-running with the same scope
     * identity reuses the descriptor but creates a NEW policy family —
     * callers wanting idempotent policies reuse [policyFamilyId] through
     * [createPolicyVersion] instead.
     */
    suspend fun createBudget(request: CreateBudgetRequest): CreatedBudget {
        require(request.limitMicroUnits >= 0) { "limit must be >= 0" }
        require(request.periodStartEpochMs < request.periodEndEpochMs) { "period window invalid" }
        val now = clock()
        return database.withTransaction {
            val descriptor = ensureDescriptor(
                scopeType = request.scopeType,
                platformObjectType = request.platformObjectType,
                platformObjectId = request.platformObjectId,
                providerId = request.providerId,
                now = now
            )
            val policyId = idGenerator()
            val familyId = idGenerator()
            database.budgetPolicyDao().insert(
                BudgetPolicyEntity(
                    budgetPolicyId = policyId,
                    policyFamilyId = familyId,
                    budgetName = request.budgetName,
                    budgetScopeType = request.scopeType,
                    scopeId = descriptor.scopeId,
                    currency = request.currency,
                    defaultLimitMicroUnits = request.limitMicroUnits,
                    periodType = request.periodType,
                    rolloverPolicy = request.rolloverPolicy,
                    overagePolicy = request.overagePolicy,
                    allocationPolicy = request.allocationPolicy,
                    policyVersion = 1,
                    effectiveFromEpochMs = now,
                    policyStatus = STATUS_ACTIVE,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            val periodId = idGenerator()
            database.budgetPeriodDao().insert(
                BudgetPeriodEntity(
                    budgetPeriodId = periodId,
                    budgetPolicyId = policyId,
                    periodStartEpochMs = request.periodStartEpochMs,
                    periodEndEpochMs = request.periodEndEpochMs,
                    limitMicroUnits = request.limitMicroUnits,
                    periodStatus = PERIOD_OPEN,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            CreatedBudget(descriptor.scopeId, policyId, familyId, periodId)
        }
    }

    /**
     * PDM-G05: a material policy edit is a NEW immutable version row in
     * the same family (the old version keeps serving the periods that
     * point at it — full historical reinterpretation).
     */
    suspend fun createPolicyVersion(
        familyId: String,
        updatedRequest: CreateBudgetRequest
    ): CreatedBudget {
        val now = clock()
        return database.withTransaction {
            val latest = database.budgetPolicyDao().familyHistory(familyId).maxByOrNull { it.policyVersion }
                ?: throw IllegalArgumentException("unknown policy family $familyId")
            val descriptor = ensureDescriptor(
                scopeType = updatedRequest.scopeType,
                platformObjectType = updatedRequest.platformObjectType,
                platformObjectId = updatedRequest.platformObjectId,
                providerId = updatedRequest.providerId,
                now = now
            )
            val policyId = idGenerator()
            database.budgetPolicyDao().insert(
                BudgetPolicyEntity(
                    budgetPolicyId = policyId,
                    policyFamilyId = familyId,
                    budgetName = updatedRequest.budgetName,
                    budgetScopeType = updatedRequest.scopeType,
                    scopeId = descriptor.scopeId,
                    currency = updatedRequest.currency,
                    defaultLimitMicroUnits = updatedRequest.limitMicroUnits,
                    periodType = updatedRequest.periodType,
                    rolloverPolicy = updatedRequest.rolloverPolicy,
                    overagePolicy = updatedRequest.overagePolicy,
                    allocationPolicy = updatedRequest.allocationPolicy,
                    policyVersion = latest.policyVersion + 1,
                    effectiveFromEpochMs = now,
                    policyStatus = STATUS_ACTIVE,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            // Retire the previous ACTIVE versions of this family so lookups
            // resolve to the newest one.
            database.budgetPolicyDao().familyHistory(familyId).forEach { version ->
                if (version.budgetPolicyId != policyId && version.policyStatus == STATUS_ACTIVE) {
                    database.budgetPolicyDao().updateStatus(version.budgetPolicyId, STATUS_RETIRED, now)
                }
            }
            val periodId = idGenerator()
            database.budgetPeriodDao().insert(
                BudgetPeriodEntity(
                    budgetPeriodId = periodId,
                    budgetPolicyId = policyId,
                    periodStartEpochMs = updatedRequest.periodStartEpochMs,
                    periodEndEpochMs = updatedRequest.periodEndEpochMs,
                    limitMicroUnits = updatedRequest.limitMicroUnits,
                    periodStatus = PERIOD_OPEN,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            CreatedBudget(descriptor.scopeId, policyId, familyId, periodId)
        }
    }

    // ------------------------------------------------------------------
    // Atomic operation 1: authorizeAndReserve
    // ------------------------------------------------------------------

    suspend fun authorizeAndReserve(
        scopeType: String,
        platformObjectId: String?,
        amountMicroUnits: Long,
        reason: String?,
        idempotencyKey: String,
        expiresAtEpochMs: Long? = null,
        nowEpochMs: Long = clock()
    ): HoldReservation {
        require(amountMicroUnits > 0) { "hold amount must be > 0 (UNKNOWN cost never reserves zero)" }
        try {
            return database.withTransaction {
                val active = resolveActiveBudget(scopeType, platformObjectId, nowEpochMs)
                    ?: return@withTransaction HoldReservation.NoActivePolicy

                // Idempotent replay: the same key returns the ORIGINAL
                // outcome, never a second hold.
                database.budgetHoldDao().byIdempotencyKey(active.period.budgetPeriodId, idempotencyKey)
                    ?.let { existing ->
                        return@withTransaction HoldReservation.Reserved(
                            holdId = existing.holdId,
                            periodId = existing.budgetPeriodId,
                            holdAmountMicroUnits = existing.holdAmountMicroUnits,
                            availableAfterMicroUnits = availableForPeriod(
                                active.period.budgetPeriodId, nowEpochMs
                            ).availableMicroUnits,
                            replayed = true
                        )
                    }

                val available = availableForPeriod(active.period.budgetPeriodId, nowEpochMs)
                if (available.availableMicroUnits < amountMicroUnits) {
                    return@withTransaction HoldReservation.Denied(
                        requiredMicroUnits = amountMicroUnits,
                        availableMicroUnits = available.availableMicroUnits,
                        overagePolicy = active.policy.overagePolicy,
                        periodId = active.period.budgetPeriodId
                    )
                }

                val holdId = idGenerator()
                database.budgetHoldDao().insert(
                    BudgetHoldEntity(
                        holdId = holdId,
                        budgetPeriodId = active.period.budgetPeriodId,
                        holdAmountMicroUnits = amountMicroUnits,
                        currency = active.policy.currency,
                        reason = reason,
                        requestOperationId = null, // deferred FK — Stage 2
                        status = HOLD_ACTIVE,
                        createdAtEpochMs = nowEpochMs,
                        expiresAtEpochMs = expiresAtEpochMs,
                        idempotencyKey = idempotencyKey
                    )
                )
                HoldReservation.Reserved(
                    holdId = holdId,
                    periodId = active.period.budgetPeriodId,
                    holdAmountMicroUnits = amountMicroUnits,
                    availableAfterMicroUnits = available.availableMicroUnits - amountMicroUnits,
                    replayed = false
                )
            }
        } catch (e: SQLiteConstraintException) {
            // Concurrent writer inserted the same (period, idempotency_key)
            // first — this request is a retry: replay the original outcome.
            val replayed = replayHold(scopeType, platformObjectId, idempotencyKey, nowEpochMs)
            if (replayed != null) return replayed
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Atomic operation 2: settleHold
    // ------------------------------------------------------------------

    suspend fun settleHold(
        holdId: String,
        actualAmountMicroUnits: Long,
        idempotencyKey: String,
        nowEpochMs: Long = clock()
    ): SettlementOutcome {
        require(actualAmountMicroUnits > 0) { "settlement amount must be > 0" }
        try {
            return database.withTransaction {
                // replay
                database.budgetHoldSettlementDao().byIdempotencyKey(holdId, idempotencyKey)
                    ?.let { existing ->
                        val hold = database.budgetHoldDao().byId(holdId)
                        return@withTransaction SettlementOutcome.Settled(
                            settlementId = existing.settlementId,
                            ledgerEntryId = existing.ledgerEntryId ?: "",
                            holdStatusAfter = hold?.status ?: HOLD_SETTLED,
                            amountMicroUnits = existing.amountMicroUnits,
                            replayed = true
                        )
                    }

                val hold = database.budgetHoldDao().byId(holdId)
                    ?: return@withTransaction SettlementOutcome.HoldNotFound
                if (hold.status !in MUTABLE_HOLD_STATUSES) {
                    return@withTransaction SettlementOutcome.InvalidHoldState(hold.status)
                }
                val settledSoFar = database.budgetHoldSettlementDao().totalSettledMicro(holdId)
                val newTotal = settledSoFar + actualAmountMicroUnits
                if (newTotal > hold.holdAmountMicroUnits) {
                    return@withTransaction SettlementOutcome.OverSettlement(
                        settledSoFarMicroUnits = settledSoFar,
                        holdAmountMicroUnits = hold.holdAmountMicroUnits,
                        requestedMicroUnits = actualAmountMicroUnits
                    )
                }

                // 1) POSTED consumption entry — the actual cost (negative
                //    effect; the estimate/actual gap lands here alone).
                val entryId = idGenerator()
                database.budgetLedgerEntryDao().insert(
                    BudgetLedgerEntryEntity(
                        entryId = entryId,
                        budgetPeriodId = hold.budgetPeriodId,
                        entryType = ENTRY_CONSUMPTION,
                        postingState = POSTING_POSTED,
                        availableEffectMicroUnits = -actualAmountMicroUnits,
                        entryBasis = BASIS_ACTUAL,
                        currency = hold.currency,
                        originalAmountPrecise = null,
                        originalCurrency = null,
                        conversionRuleId = null,
                        entryTimestampEpochMs = nowEpochMs,
                        postedAtEpochMs = nowEpochMs,
                        costCalculationLineId = null,
                        usageRecordId = null,
                        reconciliationRecordId = null,
                        holdId = holdId,
                        reversalOfEntryId = null,
                        allocationGroupId = null,
                        allocationRole = null,
                        idempotencyKey = idempotencyKey
                    )
                )
                // 2) SETTLE settlement linked to that entry (trigger 4
                //    verifies entry/hold/amount/currency coherence).
                val settlementId = idGenerator()
                database.budgetHoldSettlementDao().insert(
                    BudgetHoldSettlementEntity(
                        settlementId = settlementId,
                        holdId = holdId,
                        settlementType = SETTLEMENT_SETTLE,
                        ledgerEntryId = entryId,
                        amountMicroUnits = actualAmountMicroUnits,
                        currency = hold.currency,
                        createdAtEpochMs = nowEpochMs,
                        idempotencyKey = idempotencyKey
                    )
                )
                // 3) one-way hold lifecycle transition
                val newStatus = if (newTotal == hold.holdAmountMicroUnits) HOLD_SETTLED else HOLD_PARTIALLY_SETTLED
                database.budgetHoldDao().updateStatus(holdId, newStatus)
                SettlementOutcome.Settled(settlementId, entryId, newStatus, actualAmountMicroUnits, replayed = false)
            }
        } catch (e: SQLiteConstraintException) {
            val hold = database.budgetHoldDao().byId(holdId)
            val existing = database.budgetHoldSettlementDao().byIdempotencyKey(holdId, idempotencyKey)
            if (hold != null && existing != null) {
                return SettlementOutcome.Settled(
                    settlementId = existing.settlementId,
                    ledgerEntryId = existing.ledgerEntryId ?: "",
                    holdStatusAfter = hold.status,
                    amountMicroUnits = existing.amountMicroUnits,
                    replayed = true
                )
            }
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Atomic operation 3: releaseHold
    // ------------------------------------------------------------------

    suspend fun releaseHold(
        holdId: String,
        idempotencyKey: String,
        nowEpochMs: Long = clock()
    ): ReleaseOutcome {
        try {
            return database.withTransaction {
                database.budgetHoldSettlementDao().byIdempotencyKey(holdId, idempotencyKey)
                    ?.let { existing ->
                        return@withTransaction ReleaseOutcome.Released(
                            settlementId = existing.settlementId,
                            releasedMicroUnits = existing.amountMicroUnits,
                            replayed = true
                        )
                    }

                val hold = database.budgetHoldDao().byId(holdId)
                    ?: return@withTransaction ReleaseOutcome.HoldNotFound
                if (hold.status !in MUTABLE_HOLD_STATUSES) {
                    return@withTransaction ReleaseOutcome.InvalidHoldState(hold.status)
                }
                val settledSoFar = database.budgetHoldSettlementDao().totalSettledMicro(holdId)
                val remaining = hold.holdAmountMicroUnits - settledSoFar
                if (remaining <= 0) {
                    return@withTransaction ReleaseOutcome.NothingToRelease
                }
                // G06: a RELEASE settlement exists, but NO ledger entry is
                // ever created — releasing a hold is not a positive effect.
                val settlementId = idGenerator()
                database.budgetHoldSettlementDao().insert(
                    BudgetHoldSettlementEntity(
                        settlementId = settlementId,
                        holdId = holdId,
                        settlementType = SETTLEMENT_RELEASE,
                        ledgerEntryId = null,
                        amountMicroUnits = remaining,
                        currency = hold.currency,
                        createdAtEpochMs = nowEpochMs,
                        idempotencyKey = idempotencyKey
                    )
                )
                database.budgetHoldDao().updateStatus(holdId, HOLD_RELEASED)
                ReleaseOutcome.Released(settlementId, remaining, replayed = false)
            }
        } catch (e: SQLiteConstraintException) {
            val existing = database.budgetHoldSettlementDao().byIdempotencyKey(holdId, idempotencyKey)
            if (existing != null) {
                return ReleaseOutcome.Released(existing.settlementId, existing.amountMicroUnits, replayed = true)
            }
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Atomic operation 4: postAdjustment / postReversal
    // ------------------------------------------------------------------

    suspend fun postAdjustment(
        periodId: String,
        effectMicroUnits: Long,
        currency: String,
        entryBasis: String,
        reason: String?,
        idempotencyKey: String,
        reversalOfEntryId: String? = null,
        nowEpochMs: Long = clock()
    ): AdjustmentOutcome {
        val isReversal = reversalOfEntryId != null
        try {
            return database.withTransaction {
                database.budgetLedgerEntryDao().byIdempotencyKey(periodId, idempotencyKey)
                    ?.let { existing ->
                        return@withTransaction AdjustmentOutcome.Posted(existing.entryId, replayed = true)
                    }

                val period = database.budgetPeriodDao().byId(periodId)
                    ?: return@withTransaction AdjustmentOutcome.PeriodNotFound

                if (isReversal) {
                    val original = database.budgetLedgerEntryDao().byId(reversalOfEntryId!!)
                    when {
                        original == null -> return@withTransaction AdjustmentOutcome.InvalidReversal(
                            "original entry $reversalOfEntryId not found"
                        )
                        original.postingState != POSTING_POSTED -> return@withTransaction AdjustmentOutcome.InvalidReversal(
                            "original entry is not POSTED"
                        )
                        original.currency != currency -> return@withTransaction AdjustmentOutcome.InvalidReversal(
                            "reversal currency must match original (${original.currency})"
                        )
                        effectMicroUnits != -original.availableEffectMicroUnits -> return@withTransaction AdjustmentOutcome.InvalidReversal(
                            "reversal effect must be the exact opposite of the original " +
                                "(${original.availableEffectMicroUnits}); got $effectMicroUnits"
                        )
                    }
                }

                val entryId = idGenerator()
                database.budgetLedgerEntryDao().insert(
                    BudgetLedgerEntryEntity(
                        entryId = entryId,
                        budgetPeriodId = period.budgetPeriodId,
                        entryType = if (isReversal) ENTRY_REVERSAL else ENTRY_ADJUSTMENT,
                        postingState = POSTING_POSTED,
                        availableEffectMicroUnits = effectMicroUnits,
                        entryBasis = entryBasis,
                        currency = currency,
                        originalAmountPrecise = null,
                        originalCurrency = null,
                        conversionRuleId = null,
                        entryTimestampEpochMs = nowEpochMs,
                        postedAtEpochMs = nowEpochMs,
                        costCalculationLineId = null,
                        usageRecordId = null,
                        reconciliationRecordId = null,
                        holdId = null,
                        reversalOfEntryId = reversalOfEntryId,
                        allocationGroupId = null,
                        allocationRole = null,
                        idempotencyKey = idempotencyKey
                    )
                )
                AdjustmentOutcome.Posted(entryId, replayed = false)
            }
        } catch (e: SQLiteConstraintException) {
            val existing = database.budgetLedgerEntryDao().byIdempotencyKey(periodId, idempotencyKey)
            if (existing != null) return AdjustmentOutcome.Posted(existing.entryId, replayed = true)
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Atomic operation 5 (Stage 2, I-5): postUsageActual
    // ------------------------------------------------------------------

    /** Outcome of posting an actual usage cost with no surviving hold. */
    sealed class UsagePostingOutcome {
        data class Posted(val entryId: String, val replayed: Boolean) : UsagePostingOutcome()
        object NoActivePolicy : UsagePostingOutcome()
    }

    /**
     * Posts an ACTUAL consumption entry against the active budget of a
     * scope when NO hold is there to settle — the two production cases
     * being (a) the I-3 path (UNKNOWN estimate was honestly ALLOWED with
     * no hold; the actual cost lands here, as promised at authorization)
     * and (b) the I-11 overage remainder (actual exceeded the hold; the
     * excess is posted, never silently truncated). Same transaction,
     * replay and fail-closed discipline as the other four operations.
     */
    suspend fun postUsageActual(
        scopeType: String,
        platformObjectId: String?,
        amountMicroUnits: Long,
        reason: String?,
        idempotencyKey: String,
        usageRecordId: String? = null,
        nowEpochMs: Long = clock()
    ): UsagePostingOutcome {
        require(amountMicroUnits > 0) { "actual usage amount must be > 0" }
        return try {
            database.withTransaction {
                val active = resolveActiveBudget(scopeType, platformObjectId, nowEpochMs)
                    ?: return@withTransaction UsagePostingOutcome.NoActivePolicy

                database.budgetLedgerEntryDao()
                    .byIdempotencyKey(active.period.budgetPeriodId, idempotencyKey)
                    ?.let { return@withTransaction UsagePostingOutcome.Posted(it.entryId, replayed = true) }

                val entryId = idGenerator()
                database.budgetLedgerEntryDao().insert(
                    BudgetLedgerEntryEntity(
                        entryId = entryId,
                        budgetPeriodId = active.period.budgetPeriodId,
                        entryType = ENTRY_CONSUMPTION,
                        postingState = POSTING_POSTED,
                        availableEffectMicroUnits = -amountMicroUnits,
                        entryBasis = BASIS_ACTUAL,
                        currency = active.policy.currency,
                        originalAmountPrecise = null,
                        originalCurrency = null,
                        conversionRuleId = null,
                        entryTimestampEpochMs = nowEpochMs,
                        postedAtEpochMs = nowEpochMs,
                        costCalculationLineId = null,
                        usageRecordId = usageRecordId,
                        reconciliationRecordId = null,
                        holdId = null,
                        reversalOfEntryId = null,
                        allocationGroupId = null,
                        allocationRole = null,
                        idempotencyKey = idempotencyKey
                    )
                )
                UsagePostingOutcome.Posted(entryId, replayed = false)
            }
        } catch (e: SQLiteConstraintException) {
            // concurrent duplicate: replay whichever row won
            val active = resolveActiveBudget(scopeType, platformObjectId, nowEpochMs)
            if (active != null) {
                database.budgetLedgerEntryDao()
                    .byIdempotencyKey(active.period.budgetPeriodId, idempotencyKey)
                    ?.let { return UsagePostingOutcome.Posted(it.entryId, replayed = true) }
            }
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Reads & maintenance
    // ------------------------------------------------------------------

    /** The balance equation, recomputed from raw rows only (و.2). */
    suspend fun availableForPeriod(periodId: String, nowEpochMs: Long = clock()): AvailableBudget {
        val period = database.budgetPeriodDao().byId(periodId)
            ?: throw IllegalArgumentException("unknown period $periodId")
        val posted = database.budgetLedgerEntryDao().sumPostedEffectsMicro(periodId)
        val holdRemaining = database.budgetHoldDao().sumRemainingHoldsMicro(periodId, nowEpochMs)
        return AvailableBudget(
            periodId = periodId,
            limitMicroUnits = period.limitMicroUnits,
            postedEffectsMicroUnits = posted,
            activeHoldRemainingMicroUnits = holdRemaining
        )
    }

    /** Peek used by the authorize() integration: active policy + period. */
    suspend fun activeBudgetForScope(
        scopeType: String,
        platformObjectId: String?,
        nowEpochMs: Long = clock()
    ): ActiveBudget? {
        val descriptor = findDescriptor(scopeType, platformObjectId) ?: return null
        val policy = database.budgetPolicyDao()
            .latestEffectiveForScope(descriptor.scopeId, STATUS_ACTIVE, nowEpochMs) ?: return null
        val period = database.budgetPeriodDao()
            .activePeriodForPolicy(policy.budgetPolicyId, nowEpochMs) ?: return null
        return ActiveBudget(policy, period)
    }

    /** Marks expired holds EXPIRED (bookkeeping only — availability already
     *  ignores them lazily since the balance query filters by expiry). */
    suspend fun expireHolds(nowEpochMs: Long = clock()): Int =
        database.budgetHoldDao().markExpired(nowEpochMs)

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun resolveActiveBudget(
        scopeType: String,
        platformObjectId: String?,
        nowEpochMs: Long
    ): ActiveBudget? {
        val descriptor = findDescriptor(scopeType, platformObjectId) ?: return null
        val policy = database.budgetPolicyDao()
            .latestEffectiveForScope(descriptor.scopeId, STATUS_ACTIVE, nowEpochMs) ?: return null
        val period = database.budgetPeriodDao()
            .activePeriodForPolicy(policy.budgetPolicyId, nowEpochMs) ?: return null
        return ActiveBudget(policy, period)
    }

    private suspend fun findDescriptor(
        scopeType: String,
        platformObjectId: String?
    ): ScopeDescriptorEntity? {
        val canonical = canonicalRepresentation(scopeType, platformObjectId)
        val hash = sha256Hex(canonical)
        return database.scopeDescriptorDao()
            .byCanonicalHash(scopeType, CANONICALIZATION_VERSION, hash)
    }

    private suspend fun ensureDescriptor(
        scopeType: String,
        platformObjectType: String?,
        platformObjectId: String?,
        providerId: String?,
        now: Long
    ): ScopeDescriptorEntity {
        val canonical = canonicalRepresentation(scopeType, platformObjectId)
        val hash = sha256Hex(canonical)
        database.scopeDescriptorDao()
            .byCanonicalHash(scopeType, CANONICALIZATION_VERSION, hash)
            ?.let { return it }
        return ScopeDescriptorEntity(
            scopeId = "$scopeType#$hash",
            scopeKind = scopeType,
            canonicalRepresentation = canonical,
            canonicalHash = hash,
            hashAlgorithm = HASH_ALGORITHM,
            canonicalizationVersion = CANONICALIZATION_VERSION,
            providerDimensionState = if (providerId != null) DIMENSION_KNOWN else DIMENSION_NOT_APPLICABLE,
            providerId = providerId,
            integrationDimensionState = DIMENSION_NOT_APPLICABLE,
            integrationId = null,
            accessContextDimensionState = DIMENSION_NOT_APPLICABLE,
            accessContextId = null,
            providerScopeNodeDimensionState = DIMENSION_NOT_APPLICABLE,
            providerScopeNodeId = null,
            offeringDimensionState = DIMENSION_NOT_APPLICABLE,
            offeringId = null,
            requestAttemptDimensionState = DIMENSION_NOT_APPLICABLE,
            requestAttemptId = null,
            budgetPolicyDimensionState = DIMENSION_UNKNOWN,
            budgetPolicyId = null,
            budgetPeriodDimensionState = DIMENSION_UNKNOWN,
            budgetPeriodId = null,
            regionDimensionState = DIMENSION_NOT_APPLICABLE,
            region = null,
            endpointDimensionState = DIMENSION_NOT_APPLICABLE,
            endpointRef = null,
            periodDimensionState = DIMENSION_NOT_APPLICABLE,
            periodStart = null,
            periodEnd = null,
            platformDimensionState = if (platformObjectId != null) DIMENSION_KNOWN else DIMENSION_NOT_APPLICABLE,
            platformObjectType = if (platformObjectId != null) (platformObjectType ?: scopeType) else null,
            platformObjectId = platformObjectId,
            createdAtEpochMs = now
        ).also { database.scopeDescriptorDao().insert(it) }
    }

    private fun replayHold(
        scopeType: String,
        platformObjectId: String?,
        idempotencyKey: String,
        nowEpochMs: Long
    ): HoldReservation.Reserved? {
        // Best-effort synchronous replay lookup — called only on the UNIQUE
        // race path where another writer already committed the same key.
        // (Room suspend DAOs cannot run on this blocking catch path, so we
        // resolve via a blocking query on the same database handle.)
        val descriptor = kotlinx.coroutines.runBlocking { findDescriptor(scopeType, platformObjectId) }
            ?: return null
        val active = kotlinx.coroutines.runBlocking { resolveActiveBudget(scopeType, platformObjectId, nowEpochMs) }
            ?: return null
        val existing = kotlinx.coroutines.runBlocking {
            database.budgetHoldDao().byIdempotencyKey(active.period.budgetPeriodId, idempotencyKey)
        } ?: return null
        val available = kotlinx.coroutines.runBlocking {
            availableForPeriod(active.period.budgetPeriodId, nowEpochMs)
        }
        return HoldReservation.Reserved(
            holdId = existing.holdId,
            periodId = existing.budgetPeriodId,
            holdAmountMicroUnits = existing.holdAmountMicroUnits,
            availableAfterMicroUnits = available.availableMicroUnits,
            replayed = true
        )
    }

    companion object {
        const val CANONICALIZATION_VERSION = 1
        const val HASH_ALGORITHM = "SHA-256"

        const val DIMENSION_KNOWN = "KNOWN"
        const val DIMENSION_UNKNOWN = "UNKNOWN"
        const val DIMENSION_NOT_APPLICABLE = "NOT_APPLICABLE"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RETIRED = "RETIRED"
        const val PERIOD_OPEN = "OPEN"

        const val HOLD_ACTIVE = "ACTIVE"
        const val HOLD_PARTIALLY_SETTLED = "PARTIALLY_SETTLED"
        const val HOLD_SETTLED = "SETTLED"
        const val HOLD_RELEASED = "RELEASED"
        const val HOLD_EXPIRED = "EXPIRED"

        val MUTABLE_HOLD_STATUSES = setOf(HOLD_ACTIVE, HOLD_PARTIALLY_SETTLED)

        const val ENTRY_CONSUMPTION = "CONSUMPTION"
        const val ENTRY_ADJUSTMENT = "ADJUSTMENT"
        const val ENTRY_REVERSAL = "REVERSAL"

        const val POSTING_POSTED = "POSTED"
        const val BASIS_ACTUAL = "ACTUAL"
        const val BASIS_ESTIMATE = "ESTIMATE"
        const val BASIS_UNKNOWN = "UNKNOWN"

        const val SETTLEMENT_SETTLE = "SETTLE"
        const val SETTLEMENT_RELEASE = "RELEASE"

        const val OVERAGE_HARD_LIMIT = "HARD_LIMIT"
        const val OVERAGE_SOFT_LIMIT = "SOFT_LIMIT"
        const val OVERAGE_REQUIRE_APPROVAL = "REQUIRE_APPROVAL"

        /** Canonical, versioned scope identity string (hash input). */
        fun canonicalRepresentation(scopeType: String, platformObjectId: String?): String =
            "v$CANONICALIZATION_VERSION|$scopeType|platform=$platformObjectId"

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
