package com.example.application.budget

import androidx.room.withTransaction
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ReconciliationAdjustmentEntity
import com.example.infrastructure.persistence.entities.ReconciliationInclusionEntity
import com.example.infrastructure.persistence.entities.ReconciliationRecordEntity
import java.security.MessageDigest

/**
 * ============================================================================
 * ReconciliationService — provider invoice vs internal itemized cost
 * (Stage 3; roadmap exit criterion: "مصالحة نهائية لفترة تجريبية كاملة
 * مع فرق مفسَّر بالكامل دون فروق غير مفسرة")
 * ============================================================================
 * The comparison machine (G03):
 *  - comparison currency = the PROVIDER billing currency (the approved G03
 *    recommendation — the invoice is the anchor being verified);
 *  - internal cost lines (Stage-2 cost_calculation_line rows, provider-
 *    attributed through the pricing chain) are summed in THEIR currency
 *    (local) and converted with ONE windowed FX rule when local ≠ provider
 *    — the rate, its window and its version are recorded ON the
 *    reconciliation row, so every historical comparison stays reproducible
 *    (the rule itself is frozen once used, by trigger);
 *  - difference = provider_amount − internal_amount_in_comparison_currency
 *    (positive: the provider billed more than the platform computed);
 *  - status: MATCHED (difference 0) / WITHIN_TOLERANCE (|difference| ≤
 *    tolerance) / CONFLICT — or NOT_POSSIBLE with an honest reason code.
 *
 * The finality machine (G04) — result status and finality status are two
 * DIFFERENT columns with different rules:
 *  - every reconciliation is born DRAFT with its result;
 *  - DRAFT + terminal result (MATCHED / WITHIN_TOLERANCE / CONFLICT) may
 *    be FINALIZED (the finance sign-off; recorded in reconciled_at);
 *  - a DRAFT may be superseded (REVOKED + superseded_by the successor);
 *  - FINALIZED and REVOKED rows are FROZEN at the DB level (triggers);
 *    corrections to a finalized reconciliation ship as a NEW billing
 *    record (adjusts_billing_record_id) + a NEW reconciliation;
 *  - at most ONE FINALIZED reconciliation per (billing record, scope,
 *    period) — the change-7 uniqueness trigger.
 *
 * The difference-quality loop: adjustments classify the difference
 * (PROVIDER_ONLY_TAX / PROVIDER_ONLY_CREDIT / PROVIDER_ONLY_FEE /
 * CONVERSION_DIFFERENCE / TOLERANCE / LOCAL_UNMODELED_ADJUSTMENT) and
 * [explainedResidual] returns what remains unexplained — the quality
 * metric the roadmap expects to shrink over time.
 */
class ReconciliationService(
    private val database: AppDatabase,
    private val fx: CurrencyConversionService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    sealed class BuildOutcome {
        data class Recorded(
            val reconciliationId: String,
            val reconciliationStatus: String,
            val coverageStatus: String,
            val providerAmountMicroUnits: Long?,
            val localAmountMicroUnits: Long?,
            /** The internal total expressed in the comparison currency. */
            val comparedInternalMicroUnits: Long?,
            val differenceMicroUnits: Long?,
            val conversionRuleId: String?,
            val reasonCode: String?
        ) : BuildOutcome()

        /**
         * Nothing was persisted — the PDM CHECK forbids a cross-currency
         * reconciliation row without its conversion rule (I-24: the
         * attempt is reportable, not recordable; register a rule first).
         */
        data class NotRecordable(val reasonCode: String) : BuildOutcome()

        data class InvalidRequest(val reason: String) : BuildOutcome()
    }

    sealed class FinalizeOutcome {
        data class Finalized(val reconciliationId: String, val reconciledAtEpochMs: Long) : FinalizeOutcome()
        data class Rejected(val reason: String) : FinalizeOutcome()
    }

    sealed class SupersedeOutcome {
        data class Superseded(val reconciliationId: String, val supersededBy: String) : SupersedeOutcome()
        data class Rejected(val reason: String) : SupersedeOutcome()
    }

    /**
     * Builds a DRAFT reconciliation for one billing record + scope +
     * period, pulling the provider's CALCULATED cost lines in the window
     * (attribution instant = calculated_at_epoch_ms for REQUEST_ATTEMPT
     * rows, I-23) and comparing them with the invoice amount.
     */
    suspend fun buildReconciliation(
        billingRecordId: String,
        scopeId: String,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        toleranceMicroUnits: Long = 0L
    ): BuildOutcome {
        if (periodStartEpochMs >= periodEndEpochMs) {
            return BuildOutcome.InvalidRequest("period must be non-empty (start < end)")
        }
        if (toleranceMicroUnits < 0) {
            return BuildOutcome.InvalidRequest("tolerance must not be negative")
        }
        val billing = database.providerBillingRecordDao().byId(billingRecordId)
            ?: return BuildOutcome.InvalidRequest("billing record not found: $billingRecordId")

        // ---- pull the internal itemized cost for this provider+window ----
        val calculated = database.costCalculationDao().forProviderWindow(
            providerId = billing.providerId,
            calculationStatus = CostBreakdownService.STATUS_CALCULATED,
            fromEpochMs = periodStartEpochMs,
            untilEpochMs = periodEndEpochMs
        )
        val skippedInWindow = database.costCalculationDao().forProviderWindow(
            providerId = billing.providerId,
            calculationStatus = CostBreakdownService.STATUS_ESTIMATED,
            fromEpochMs = periodStartEpochMs,
            untilEpochMs = periodEndEpochMs
        ).size + database.costCalculationDao().forProviderWindow(
            providerId = billing.providerId,
            calculationStatus = CostBreakdownService.STATUS_NOT_POSSIBLE,
            fromEpochMs = periodStartEpochMs,
            untilEpochMs = periodEndEpochMs
        ).size

        val lines = if (calculated.isEmpty()) {
            emptyList()
        } else {
            database.costCalculationLineDao()
                .activeLinesForCalculations(calculated.map { it.costCalculationId })
        }
        val lineCurrencies = lines.map { it.currency }.distinct()
        if (lineCurrencies.size > 1) {
            return BuildOutcome.NotRecordable("MIXED_LINE_CURRENCIES:${lineCurrencies.sorted().joinToString("/")}")
        }
        val localCurrency = lineCurrencies.singleOrNull() ?: billing.currency

        // ---- G03: comparison currency = the provider billing currency ----
        val providerCurrency = billing.currency
        val comparisonCurrency = providerCurrency

        // ---- provider amount: primary first, gross as fallback (I-27) ----
        val providerAmount = billing.primaryAmountMicroUnits ?: billing.grossPayableMicroUnits

        // ---- the single conversion, when currencies differ ----
        val localAmount = lines.sumOf { it.finalAmountMicroUnits ?: 0L }
        var conversionRuleId: String? = null
        // G03 identity: same currency -> the internal total IS the compared
        // total, no rule involved (the PDM CHECK keeps conversion_rule_id
        // NULL on this path).
        var comparedInternal: Long? = if (localCurrency == providerCurrency) localAmount else null
        if (localCurrency != providerCurrency) {
            // G03/I-24: without a covering rule the row is not even
            // recordable (the CHECK demands the rule's presence).
            val rule = fx.resolveRule(localCurrency, providerCurrency, atEndOf(periodEndEpochMs))
                ?: return BuildOutcome.NotRecordable("NO_CONVERSION_RULE:$localCurrency>$providerCurrency")
            when (val converted = fx.convert(localAmount, localCurrency, providerCurrency, atEndOf(periodEndEpochMs))) {
                is CurrencyConversionService.ConversionResult.Converted -> {
                    conversionRuleId = converted.ruleId
                    comparedInternal = converted.targetMicroUnits
                }
                is CurrencyConversionService.ConversionResult.NoRule ->
                    return BuildOutcome.NotRecordable("NO_CONVERSION_RULE:$localCurrency>$providerCurrency")
                is CurrencyConversionService.ConversionResult.InvalidRate ->
                    return BuildOutcome.NotRecordable("INVALID_RATE")
            }
        }

        // ---- coverage & result ----
        val coverageStatus = when {
            lines.isEmpty() -> COVERAGE_NOT_POSSIBLE
            skippedInWindow > 0 -> COVERAGE_PARTIAL
            else -> COVERAGE_COMPLETE
        }
        val reasonCode = when {
            lines.isEmpty() && (providerAmount == null || providerAmount != 0L) -> "NO_INTERNAL_COVERAGE"
            providerAmount == null -> "PROVIDER_AMOUNT_UNKNOWN"
            else -> null
        }
        val difference = if (providerAmount != null && comparedInternal != null) {
            providerAmount - comparedInternal
        } else null
        val status = when {
            reasonCode != null -> STATUS_NOT_POSSIBLE
            difference == 0L -> STATUS_MATCHED
            kotlin.math.abs(difference ?: Long.MAX_VALUE) <= toleranceMicroUnits -> STATUS_WITHIN_TOLERANCE
            else -> STATUS_CONFLICT
        }

        val now = clock()
        // deterministic + collision-free: rebuilding the SAME key creates a
        // NEW draft (nonce = existing rows for that key); only FINALIZED is
        // unique (change 7), drafts may coexist by design.
        val nonce = database.reconciliationRecordDao()
            .countFor(billingRecordId, scopeId, periodStartEpochMs, periodEndEpochMs)
        val reconciliationId =
            reconciliationId(billingRecordId, scopeId, periodStartEpochMs, periodEndEpochMs, now, nonce)
        database.withTransaction {
            database.reconciliationRecordDao().insert(
                ReconciliationRecordEntity(
                    reconciliationId = reconciliationId,
                    providerBillingRecordId = billingRecordId,
                    scopeId = scopeId,
                    periodStartEpochMs = periodStartEpochMs,
                    periodEndEpochMs = periodEndEpochMs,
                    providerCurrency = providerCurrency,
                    localCurrency = localCurrency,
                    comparisonCurrency = comparisonCurrency,
                    conversionRuleId = conversionRuleId,
                    localAmountMicroUnits = if (lines.isEmpty()) null else localAmount,
                    providerAmountMicroUnits = providerAmount,
                    differenceMicroUnits = difference,
                    toleranceMicroUnits = toleranceMicroUnits,
                    coverageStatus = coverageStatus,
                    reconciliationStatus = status,
                    finalityStatus = FINALITY_DRAFT,
                    reasonCode = reasonCode,
                    ruleVersion = RULE_VERSION,
                    reconciledAtEpochMs = null,
                    recordedAtEpochMs = now,
                    supersededBy = null
                )
            )
            lines.forEach { line ->
                database.reconciliationInclusionDao().insert(
                    ReconciliationInclusionEntity(
                        inclusionId = "ri_${reconciliationId}_${line.lineId}",
                        reconciliationId = reconciliationId,
                        costCalculationLineId = line.lineId,
                        includedAmountMicroUnits = line.finalAmountMicroUnits ?: 0L,
                        currency = line.currency,
                        inclusionStatus = INCLUSION_INCLUDED
                    )
                )
            }
        }

        return BuildOutcome.Recorded(
            reconciliationId = reconciliationId,
            reconciliationStatus = status,
            coverageStatus = coverageStatus,
            providerAmountMicroUnits = providerAmount,
            localAmountMicroUnits = if (lines.isEmpty()) null else localAmount,
            comparedInternalMicroUnits = comparedInternal,
            differenceMicroUnits = difference,
            conversionRuleId = conversionRuleId,
            reasonCode = reasonCode
        )
    }

    /**
     * Classifies part of the difference. DRAFT reconciliations only (the
     * draft-guard trigger backstops the service check). Amounts are
     * signed micro units in the COMPARISON currency.
     */
    suspend fun addAdjustment(
        reconciliationId: String,
        adjustmentType: String,
        amountMicroUnits: Long,
        reasonCode: String,
        notes: String? = null
    ): ReconciliationAdjustmentEntity {
        val record = database.reconciliationRecordDao().byId(reconciliationId)
            ?: throw IllegalArgumentException("reconciliation not found: $reconciliationId")
        require(record.finalityStatus == FINALITY_DRAFT) {
            "adjustments can only be added to a DRAFT reconciliation (found ${record.finalityStatus})"
        }
        val existing = database.reconciliationAdjustmentDao().forReconciliation(reconciliationId)
        return database.withTransaction {
            val adjustment = ReconciliationAdjustmentEntity(
                adjustmentId = "ra_${reconciliationId}_${existing.size + 1}",
                reconciliationId = reconciliationId,
                adjustmentType = adjustmentType,
                amountMicroUnits = amountMicroUnits,
                currency = record.comparisonCurrency,
                reasonCode = reasonCode,
                notes = notes,
                createdAtEpochMs = clock()
            )
            database.reconciliationAdjustmentDao().insert(adjustment)
            adjustment
        }
    }

    /**
     * The unexplained remainder: difference − Σ adjustments. The roadmap
     * quality metric — zero means the difference is fully explained
     * (taxes / credits / fees / conversion), the Stage-3 exit criterion.
     */
    suspend fun explainedResidual(reconciliationId: String): Long? {
        val record = database.reconciliationRecordDao().byId(reconciliationId) ?: return null
        val difference = record.differenceMicroUnits ?: return null
        val adjustments = database.reconciliationAdjustmentDao().forReconciliation(reconciliationId)
        return difference - adjustments.sumOf { it.amountMicroUnits }
    }

    /** G04 sign-off: DRAFT + terminal result only. */
    suspend fun finalize(reconciliationId: String): FinalizeOutcome {
        val record = database.reconciliationRecordDao().byId(reconciliationId)
            ?: return FinalizeOutcome.Rejected("NOT_FOUND")
        if (record.finalityStatus != FINALITY_DRAFT) {
            return FinalizeOutcome.Rejected("NOT_DRAFT:${record.finalityStatus}")
        }
        if (record.reconciliationStatus !in TERMINAL_RESULTS) {
            return FinalizeOutcome.Rejected("NON_TERMINAL_RESULT:${record.reconciliationStatus}")
        }
        val now = clock()
        return try {
            database.withTransaction {
                database.reconciliationRecordDao().updateFinality(
                    reconciliationId = reconciliationId,
                    finalityStatus = FINALITY_FINALIZED,
                    reconciledAtEpochMs = now,
                    supersededBy = null
                )
            }
            FinalizeOutcome.Finalized(reconciliationId, now)
        } catch (e: android.database.SQLException) {
            // the DB-level police spoke (frozen / unique-finalized /
            // cross-finalized double inclusion) — surface it honestly.
            FinalizeOutcome.Rejected("TRIGGER:${e.message?.substringBefore('\n')}")
        }
    }

    /** G04 correction path: a DRAFT steps aside for its successor. */
    suspend fun supersede(reconciliationId: String, supersededByReconciliationId: String): SupersedeOutcome {
        val record = database.reconciliationRecordDao().byId(reconciliationId)
            ?: return SupersedeOutcome.Rejected("NOT_FOUND")
        if (record.finalityStatus != FINALITY_DRAFT) {
            return SupersedeOutcome.Rejected("NOT_DRAFT:${record.finalityStatus}")
        }
        if (database.reconciliationRecordDao().byId(supersededByReconciliationId) == null) {
            return SupersedeOutcome.Rejected("SUCCESSOR_NOT_FOUND")
        }
        return try {
            database.withTransaction {
                database.reconciliationRecordDao().updateFinality(
                    reconciliationId = reconciliationId,
                    finalityStatus = FINALITY_REVOKED,
                    reconciledAtEpochMs = record.reconciledAtEpochMs,
                    supersededBy = supersededByReconciliationId
                )
            }
            SupersedeOutcome.Superseded(reconciliationId, supersededByReconciliationId)
        } catch (e: android.database.SQLException) {
            SupersedeOutcome.Rejected("TRIGGER:${e.message?.substringBefore('\n')}")
        }
    }

    suspend fun byId(reconciliationId: String): ReconciliationRecordEntity? =
        database.reconciliationRecordDao().byId(reconciliationId)

    suspend fun inclusions(reconciliationId: String): List<ReconciliationInclusionEntity> =
        database.reconciliationInclusionDao().forReconciliation(reconciliationId)

    suspend fun adjustments(reconciliationId: String): List<ReconciliationAdjustmentEntity> =
        database.reconciliationAdjustmentDao().forReconciliation(reconciliationId)

    /** G03 window semantics: the rule in force at the period's close. */
    private fun atEndOf(periodEndEpochMs: Long): Long = periodEndEpochMs - 1

    private fun reconciliationId(
        billingRecordId: String,
        scopeId: String,
        periodStart: Long,
        periodEnd: Long,
        now: Long,
        nonce: Int
    ): String {
        val canonical = "$billingRecordId|$scopeId|$periodStart|$periodEnd"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "rec_${digest.take(20)}_${now}_$nonce"
    }

    companion object {
        const val STATUS_MATCHED = "MATCHED"
        const val STATUS_WITHIN_TOLERANCE = "WITHIN_TOLERANCE"
        const val STATUS_CONFLICT = "CONFLICT"
        const val STATUS_NOT_POSSIBLE = "NOT_POSSIBLE"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SUPERSEDED = "SUPERSEDED"

        const val COVERAGE_COMPLETE = "COMPLETE"
        const val COVERAGE_PARTIAL = "PARTIAL"
        const val COVERAGE_NOT_POSSIBLE = "NOT_POSSIBLE"

        const val FINALITY_DRAFT = "DRAFT"
        const val FINALITY_FINALIZED = "FINALIZED"
        const val FINALITY_REVOKED = "REVOKED"

        const val ADJUSTMENT_PROVIDER_ONLY_TAX = "PROVIDER_ONLY_TAX"
        const val ADJUSTMENT_PROVIDER_ONLY_CREDIT = "PROVIDER_ONLY_CREDIT"
        const val ADJUSTMENT_PROVIDER_ONLY_FEE = "PROVIDER_ONLY_FEE"
        const val ADJUSTMENT_CONVERSION_DIFFERENCE = "CONVERSION_DIFFERENCE"
        const val ADJUSTMENT_TOLERANCE = "TOLERANCE"
        const val ADJUSTMENT_LOCAL_UNMODELED = "LOCAL_UNMODELED_ADJUSTMENT"

        const val INCLUSION_INCLUDED = "INCLUDED"

        /** G03/G08: versions the comparison math (fx + rounding family). */
        const val RULE_VERSION = "rounding:v1|${CurrencyConversionService.FX_RULE_VERSION}"

        /** G04: results that may carry a sign-off. */
        val TERMINAL_RESULTS = setOf(STATUS_MATCHED, STATUS_WITHIN_TOLERANCE, STATUS_CONFLICT)
    }
}
