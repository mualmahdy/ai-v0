package com.example.application.budget

import androidx.room.withTransaction
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProviderBillingRecordEntity
import java.security.MessageDigest

/**
 * ============================================================================
 * ProviderBillingService — the invoice import path (Stage 3)
 * ============================================================================
 * Roadmap Phase 3: "مسار استيراد الفواتير (ملفات أو واجهات برمجية حيث
 * تتاح)". This service is the PROGRAMMATIC import entry point every file
 * parser or provider-API adapter will call (interpretation I-26: concrete
 * per-provider file formats are out of Stage-3 scope — the durable,
 * auditable LANDING surface is not).
 *
 *  - records are IMMUTABLE evidence (trg_no_update/no_delete_billing_
 *    record): a corrected invoice is a NEW row chained via
 *    adjusts_billing_record_id, never an UPDATE;
 *  - external-id dedup follows the I-14 pattern (the PDM's partial unique
 *    index ux_provider_billing_external_id became a regular index +
 *    importer-transaction uniqueness): the same (provider, access
 *    context, external id) replays to the existing row, never duplicates;
 *  - amount consistency is checked at import: when the full breakdown is
 *    present, gross must equal net + tax − credit + adjustment
 *    (signed convention), else the record is honestly INCONSISTENT —
 *    never silently "fixed".
 */
class ProviderBillingService(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** What to import. Amounts are signed micro units in [currency]. */
    data class ImportSpec(
        val providerId: String,
        val accessContextId: String? = null,
        val externalBillingId: String? = null,
        val billingRecordType: String = TYPE_INVOICE,
        val recordGranularity: String = GRANULARITY_MONTHLY,
        val periodStartEpochMs: Long? = null,
        val periodEndEpochMs: Long? = null,
        val scopeId: String? = null,
        val primaryAmountType: String = PRIMARY_GROSS_PAYABLE,
        val primaryAmountMicroUnits: Long? = null,
        val grossPayableMicroUnits: Long? = null,
        val netBeforeTaxMicroUnits: Long? = null,
        val taxAmountMicroUnits: Long? = null,
        val creditAmountMicroUnits: Long? = null,
        val adjustmentAmountMicroUnits: Long? = null,
        val currency: String,
        val sourceAuthority: String,
        val reportedAtEpochMs: Long? = null,
        val adjustsBillingRecordId: String? = null
    )

    sealed class ImportResult {
        data class Imported(val recordId: String) : ImportResult()

        /** Same external id already imported — the existing row stands. */
        data class Replayed(val recordId: String) : ImportResult()
    }

    suspend fun import(spec: ImportSpec): ImportResult = database.withTransaction {
        require(spec.providerId.isNotBlank()) { "providerId must not be blank" }
        require(spec.currency.length == 3) { "currency must be a 3-letter ISO code" }

        // I-14 dedup: same external id under the same provider+context replays.
        if (spec.externalBillingId != null) {
            val existing = database.providerBillingRecordDao().byExternalId(
                providerId = spec.providerId,
                accessContextId = spec.accessContextId,
                externalBillingId = spec.externalBillingId
            )
            if (existing != null) {
                return@withTransaction ImportResult.Replayed(existing.providerBillingRecordId)
            }
        }

        val now = clock()
        // deterministic + collision-free: identical payloads WITHOUT an
        // external id are distinct import events (nonce = provider row
        // count); WITH an external id the dedup replay above already
        // returned, so this path only mints genuinely new records.
        val nonce = database.providerBillingRecordDao().countByProvider(spec.providerId)
        val recordId = recordId(spec, nonce)
        database.providerBillingRecordDao().insert(
            ProviderBillingRecordEntity(
                providerBillingRecordId = recordId,
                providerId = spec.providerId,
                accessContextId = spec.accessContextId,
                externalBillingId = spec.externalBillingId,
                billingRecordType = spec.billingRecordType,
                recordGranularity = spec.recordGranularity,
                periodStartEpochMs = spec.periodStartEpochMs,
                periodEndEpochMs = spec.periodEndEpochMs,
                scopeId = spec.scopeId,
                primaryAmountType = spec.primaryAmountType,
                primaryAmountMicroUnits = spec.primaryAmountMicroUnits,
                grossPayableMicroUnits = spec.grossPayableMicroUnits,
                netBeforeTaxMicroUnits = spec.netBeforeTaxMicroUnits,
                taxAmountMicroUnits = spec.taxAmountMicroUnits,
                creditAmountMicroUnits = spec.creditAmountMicroUnits,
                adjustmentAmountMicroUnits = spec.adjustmentAmountMicroUnits,
                currency = spec.currency,
                amountConsistencyStatus = consistencyOf(spec),
                sourceAuthority = spec.sourceAuthority,
                reportedAtEpochMs = spec.reportedAtEpochMs,
                retrievedAtEpochMs = now,
                recordedAtEpochMs = now,
                billingStatus = STATUS_IMPORTED,
                adjustsBillingRecordId = spec.adjustsBillingRecordId
            )
        )
        ImportResult.Imported(recordId)
    }

    suspend fun byId(recordId: String): ProviderBillingRecordEntity? =
        database.providerBillingRecordDao().byId(recordId)

    /**
     * gross = net + tax − credit + adjustment when ALL five are present;
     * UNKNOWN when any is missing (honest absence, never a guess).
     */
    private fun consistencyOf(spec: ImportSpec): String {
        val gross = spec.grossPayableMicroUnits ?: return CONSISTENCY_UNKNOWN
        val net = spec.netBeforeTaxMicroUnits ?: return CONSISTENCY_UNKNOWN
        val tax = spec.taxAmountMicroUnits ?: return CONSISTENCY_UNKNOWN
        val credit = spec.creditAmountMicroUnits ?: return CONSISTENCY_UNKNOWN
        val adjustment = spec.adjustmentAmountMicroUnits ?: return CONSISTENCY_UNKNOWN
        return if (gross == net + tax - credit + adjustment) {
            CONSISTENCY_CONSISTENT
        } else {
            CONSISTENCY_INCONSISTENT
        }
    }

    /** Deterministic id from the full payload + nonce (v23 discipline). */
    private fun recordId(spec: ImportSpec, nonce: Int): String {
        val canonical = buildString {
            append(spec.providerId).append('|')
            append(spec.accessContextId ?: "-").append('|')
            append(spec.externalBillingId ?: "-").append('|')
            append(spec.billingRecordType).append('|')
            append(spec.periodStartEpochMs ?: "-").append('|')
            append(spec.periodEndEpochMs ?: "-").append('|')
            append(spec.currency).append('|')
            append(spec.grossPayableMicroUnits ?: "-").append(',')
            append(spec.netBeforeTaxMicroUnits ?: "-").append(',')
            append(spec.taxAmountMicroUnits ?: "-").append(',')
            append(spec.creditAmountMicroUnits ?: "-").append(',')
            append(spec.adjustmentAmountMicroUnits ?: "-").append('|')
            append(spec.adjustsBillingRecordId ?: "-")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "pbr_${digest.take(24)}_$nonce"
    }

    companion object {
        const val TYPE_INVOICE = "INVOICE"
        const val TYPE_CREDIT_NOTE = "CREDIT_NOTE"
        const val TYPE_STATEMENT = "STATEMENT"
        const val TYPE_ADJUSTMENT = "ADJUSTMENT"
        const val GRANULARITY_MONTHLY = "MONTHLY"
        const val GRANULARITY_AGGREGATE = "AGGREGATE"
        const val PRIMARY_GROSS_PAYABLE = "GROSS_PAYABLE"
        const val STATUS_IMPORTED = "IMPORTED"
        const val CONSISTENCY_CONSISTENT = "CONSISTENT"
        const val CONSISTENCY_INCONSISTENT = "INCONSISTENT"
        const val CONSISTENCY_UNKNOWN = "UNKNOWN"
    }
}
