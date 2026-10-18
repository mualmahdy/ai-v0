package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.infrastructure.persistence.entities.CurrencyConversionRuleEntity
import com.example.infrastructure.persistence.entities.EntitlementConsumptionEntity
import com.example.infrastructure.persistence.entities.EntitlementRecordEntity
import com.example.infrastructure.persistence.entities.ProviderBillingRecordEntity
import com.example.infrastructure.persistence.entities.ReconciliationAdjustmentEntity
import com.example.infrastructure.persistence.entities.ReconciliationInclusionEntity
import com.example.infrastructure.persistence.entities.ReconciliationRecordEntity

/**
 * Stage-3 DAOs (currencies + provider billing + reconciliation +
 * entitlements). All inserts are plain @Insert (ABORT on conflict):
 * idempotency is a service-level concern (read-before-insert replay, the
 * v23 pattern) and silent replacement of financial evidence is forbidden
 * (ADR-B4). The two legal finality transitions run through explicit
 * UPDATE queries so the G04 triggers can police them.
 */
@Dao
interface CurrencyConversionRuleDao {
    @Insert
    suspend fun insert(rule: CurrencyConversionRuleEntity)

    @Query("SELECT * FROM currency_conversion_rule WHERE currency_conversion_rule_id = :ruleId LIMIT 1")
    suspend fun byId(ruleId: String): CurrencyConversionRuleEntity?

    /**
     * The rule covering [atEpochMs] for a pair. Multiple covering windows
     * are a writer discipline violation; the ORDER BY keeps the pick
     * deterministic even if data drifts in (the pricing-version pattern).
     */
    @Query(
        "SELECT * FROM currency_conversion_rule WHERE from_currency = :fromCurrency " +
            "AND to_currency = :toCurrency " +
            "AND effective_from_epoch_ms <= :atEpochMs " +
            "AND (effective_until_epoch_ms IS NULL OR effective_until_epoch_ms > :atEpochMs) " +
            "ORDER BY effective_from_epoch_ms DESC LIMIT 1"
    )
    suspend fun coveringForPair(fromCurrency: String, toCurrency: String, atEpochMs: Long): CurrencyConversionRuleEntity?
}

@Dao
interface ProviderBillingRecordDao {
    @Insert
    suspend fun insert(record: ProviderBillingRecordEntity)

    @Query("SELECT * FROM provider_billing_record WHERE provider_billing_record_id = :recordId LIMIT 1")
    suspend fun byId(recordId: String): ProviderBillingRecordEntity?

    /**
     * External-id dedup lookup (I-14: the partial unique index became a
     * regular index; uniqueness is enforced in the importer's transaction).
     * `IS ?` compares NULLs correctly (the PDM partial index semantics).
     */
    @Query(
        "SELECT * FROM provider_billing_record WHERE provider_id = :providerId " +
            "AND access_context_id IS :accessContextId " +
            "AND external_billing_id IS :externalBillingId LIMIT 1"
    )
    suspend fun byExternalId(
        providerId: String,
        accessContextId: String?,
        externalBillingId: String?
    ): ProviderBillingRecordEntity?

    /** Id nonce source — identical payloads without external ids are DISTINCT records. */
    @Query("SELECT COUNT(*) FROM provider_billing_record WHERE provider_id = :providerId")
    suspend fun countByProvider(providerId: String): Int
}

@Dao
interface ReconciliationRecordDao {
    @Insert
    suspend fun insert(record: ReconciliationRecordEntity)

    @Query("SELECT * FROM reconciliation_record WHERE reconciliation_id = :reconciliationId LIMIT 1")
    suspend fun byId(reconciliationId: String): ReconciliationRecordEntity?

    /**
     * The ONE legal mutation surface (G04): DRAFT -> FINALIZED (sign-off)
     * or DRAFT -> REVOKED (supersession). The frozen/unique/finality-guard
     * triggers police every row this touches.
     */
    @Query(
        "UPDATE reconciliation_record SET finality_status = :finalityStatus, " +
            "reconciled_at_epoch_ms = :reconciledAtEpochMs, superseded_by = :supersededBy " +
            "WHERE reconciliation_id = :reconciliationId"
    )
    suspend fun updateFinality(
        reconciliationId: String,
        finalityStatus: String,
        reconciledAtEpochMs: Long?,
        supersededBy: String?
    )

    @Query(
        "SELECT * FROM reconciliation_record WHERE provider_billing_record_id = :billingRecordId " +
            "AND scope_id = :scopeId AND period_start_epoch_ms = :periodStartEpochMs " +
            "AND period_end_epoch_ms = :periodEndEpochMs AND finality_status = 'FINALIZED' LIMIT 1"
    )
    suspend fun finalizedFor(
        billingRecordId: String,
        scopeId: String,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long
    ): ReconciliationRecordEntity?

    /** Drafts for the same key may coexist — the id nonce. */
    @Query(
        "SELECT COUNT(*) FROM reconciliation_record WHERE provider_billing_record_id = :billingRecordId " +
            "AND scope_id = :scopeId AND period_start_epoch_ms = :periodStartEpochMs " +
            "AND period_end_epoch_ms = :periodEndEpochMs"
    )
    suspend fun countFor(
        billingRecordId: String,
        scopeId: String,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long
    ): Int
}

@Dao
interface ReconciliationInclusionDao {
    @Insert
    suspend fun insert(inclusion: ReconciliationInclusionEntity)

    @Query("SELECT * FROM reconciliation_inclusion WHERE reconciliation_id = :reconciliationId")
    suspend fun forReconciliation(reconciliationId: String): List<ReconciliationInclusionEntity>
}

@Dao
interface ReconciliationAdjustmentDao {
    @Insert
    suspend fun insert(adjustment: ReconciliationAdjustmentEntity)

    @Query("SELECT * FROM reconciliation_adjustment WHERE reconciliation_id = :reconciliationId ORDER BY created_at_epoch_ms")
    suspend fun forReconciliation(reconciliationId: String): List<ReconciliationAdjustmentEntity>
}

@Dao
interface EntitlementRecordDao {
    @Insert
    suspend fun insert(entitlement: EntitlementRecordEntity)

    @Query("SELECT * FROM entitlement_record WHERE entitlement_id = :entitlementId LIMIT 1")
    suspend fun byId(entitlementId: String): EntitlementRecordEntity?
}

@Dao
interface EntitlementConsumptionDao {
    @Insert
    suspend fun insert(consumption: EntitlementConsumptionEntity)

    @Query(
        "SELECT * FROM entitlement_consumption WHERE entitlement_id = :entitlementId " +
            "AND idempotency_key = :idempotencyKey LIMIT 1"
    )
    suspend fun byIdempotencyKey(entitlementId: String, idempotencyKey: String): EntitlementConsumptionEntity?

    @Query(
        "SELECT * FROM entitlement_consumption WHERE entitlement_id = :entitlementId " +
            "ORDER BY recorded_at_epoch_ms"
    )
    suspend fun forEntitlement(entitlementId: String): List<EntitlementConsumptionEntity>
}
