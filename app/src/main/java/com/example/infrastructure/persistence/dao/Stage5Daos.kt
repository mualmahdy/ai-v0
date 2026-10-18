package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.infrastructure.persistence.entities.CurrentResourceStateEntity
import com.example.infrastructure.persistence.entities.CurrentResourceStateSourceEntity
import com.example.infrastructure.persistence.entities.ObservationEntity
import com.example.infrastructure.persistence.entities.ScopeCustomDimensionEntity
import com.example.infrastructure.persistence.entities.ScopeInternalDimensionEntity

/**
 * ============================================================================
 * STAGE 5 DAOs — the derived-state projection, the scope dimension tables
 * and the rationalization inputs
 * ============================================================================
 * The seven subject columns are passed as seven nullable parameters and
 * matched with an OR-of-arms: the one non-NULL arm matches rows for THAT
 * subject; the six NULL arms compare NULL = NULL-able-column and select
 * nothing. The scope comparison is spelled out NULL-safe — a NULL scope
 * keys with a NULL scope, never with a real one (the PDM's
 * COALESCE(scope_id,'__NO_SCOPE__') semantics, the same key the
 * trg_current_state_unique_active_* pair guards).
 */
@Dao
interface CurrentStateDao {

    @Insert
    suspend fun insertState(state: CurrentResourceStateEntity)

    @Insert
    suspend fun insertSource(source: CurrentResourceStateSourceEntity)

    @Insert
    suspend fun insertSources(sources: List<CurrentResourceStateSourceEntity>)

    /**
     * The latest VALID observations for one (subject, metric, scope) key,
     * newest first — the derivation input.
     */
    @Query(
        "SELECT * FROM observation WHERE metric_id = :metricId " +
            "AND (scope_id = :scopeId OR (scope_id IS NULL AND :scopeId IS NULL)) " +
            "AND validity_status = 'VALID' " +
            "AND (subject_provider_id = :subjectProviderId " +
            "  OR subject_access_context_id = :subjectAccessContextId " +
            "  OR subject_provider_scope_node_id = :subjectProviderScopeNodeId " +
            "  OR subject_provider_model_record_id = :subjectProviderModelRecordId " +
            "  OR subject_model_id = :subjectModelId " +
            "  OR subject_offering_id = :subjectOfferingId " +
            "  OR subject_request_attempt_id = :subjectRequestAttemptId) " +
            "ORDER BY observed_at_epoch_ms DESC, recorded_at_epoch_ms DESC LIMIT :limit"
    )
    suspend fun latestValidObservations(
        metricId: String,
        scopeId: String?,
        subjectProviderId: String?,
        subjectAccessContextId: String?,
        subjectProviderScopeNodeId: String?,
        subjectProviderModelRecordId: String?,
        subjectModelId: String?,
        subjectOfferingId: String?,
        subjectRequestAttemptId: String?,
        limit: Int
    ): List<ObservationEntity>

    /** The active state row for one (subject, metric, scope) key, if any. */
    @Query(
        "SELECT * FROM current_resource_state WHERE is_active = 1 AND metric_id = :metricId " +
            "AND (scope_id = :scopeId OR (scope_id IS NULL AND :scopeId IS NULL)) " +
            "AND (subject_provider_id = :subjectProviderId " +
            "  OR subject_access_context_id = :subjectAccessContextId " +
            "  OR subject_provider_scope_node_id = :subjectProviderScopeNodeId " +
            "  OR subject_provider_model_record_id = :subjectProviderModelRecordId " +
            "  OR subject_model_id = :subjectModelId " +
            "  OR subject_offering_id = :subjectOfferingId " +
            "  OR subject_request_attempt_id = :subjectRequestAttemptId) LIMIT 1"
    )
    suspend fun activeStateFor(
        metricId: String,
        scopeId: String?,
        subjectProviderId: String?,
        subjectAccessContextId: String?,
        subjectProviderScopeNodeId: String?,
        subjectProviderModelRecordId: String?,
        subjectModelId: String?,
        subjectOfferingId: String?,
        subjectRequestAttemptId: String?
    ): CurrentResourceStateEntity?

    /**
     * Deactivate the previous active rows for one key — the supersede half
     * of the writer's transaction (the uniqueness triggers then admit the
     * new active row).
     */
    @Query(
        "UPDATE current_resource_state SET is_active = 0, updated_at_epoch_ms = :nowEpochMs " +
            "WHERE is_active = 1 AND metric_id = :metricId " +
            "AND (scope_id = :scopeId OR (scope_id IS NULL AND :scopeId IS NULL)) " +
            "AND (subject_provider_id = :subjectProviderId " +
            "  OR subject_access_context_id = :subjectAccessContextId " +
            "  OR subject_provider_scope_node_id = :subjectProviderScopeNodeId " +
            "  OR subject_provider_model_record_id = :subjectProviderModelRecordId " +
            "  OR subject_model_id = :subjectModelId " +
            "  OR subject_offering_id = :subjectOfferingId " +
            "  OR subject_request_attempt_id = :subjectRequestAttemptId)"
    )
    suspend fun deactivateActiveForKey(
        nowEpochMs: Long,
        metricId: String,
        scopeId: String?,
        subjectProviderId: String?,
        subjectAccessContextId: String?,
        subjectProviderScopeNodeId: String?,
        subjectProviderModelRecordId: String?,
        subjectModelId: String?,
        subjectOfferingId: String?,
        subjectRequestAttemptId: String?
    ): Int

    /** The rebuild helper: retire every active row in one pass. */
    @Query("UPDATE current_resource_state SET is_active = 0, updated_at_epoch_ms = :nowEpochMs WHERE is_active = 1")
    suspend fun deactivateAll(nowEpochMs: Long): Int

    @Query("SELECT * FROM current_resource_state WHERE is_active = 1 ORDER BY metric_id, updated_at_epoch_ms")
    suspend fun activeStates(): List<CurrentResourceStateEntity>

    @Query("SELECT * FROM current_resource_state WHERE state_id = :stateId LIMIT 1")
    suspend fun stateById(stateId: String): CurrentResourceStateEntity?

    @Query("SELECT * FROM current_resource_state_source WHERE state_id = :stateId ORDER BY included_at_epoch_ms")
    suspend fun sourcesForState(stateId: String): List<CurrentResourceStateSourceEntity>

    @Query("SELECT COUNT(*) FROM current_resource_state")
    suspend fun countStates(): Int

    @Query("SELECT COUNT(*) FROM current_resource_state WHERE is_active = 1")
    suspend fun countActiveStates(): Int
}

/** The G01 full dimension tables — insert + per-scope reads. */
@Dao
interface ScopeDimensionDao {

    @Insert
    suspend fun insertInternal(dimension: ScopeInternalDimensionEntity)

    @Insert
    suspend fun insertCustom(dimension: ScopeCustomDimensionEntity)

    @Query("SELECT * FROM scope_internal_dimension WHERE scope_id = :scopeId ORDER BY dimension_code")
    suspend fun internalForScope(scopeId: String): List<ScopeInternalDimensionEntity>

    @Query("SELECT * FROM scope_custom_dimension WHERE scope_id = :scopeId ORDER BY namespace, dimension_code")
    suspend fun customForScope(scopeId: String): List<ScopeCustomDimensionEntity>

    @Query("SELECT COUNT(*) FROM scope_internal_dimension")
    suspend fun countInternal(): Int

    @Query("SELECT COUNT(*) FROM scope_custom_dimension")
    suspend fun countCustom(): Int
}

// ---------------------------------------------------------------------------
// The rationalization inputs. All one-shot (the recommendation pass is a
// deterministic computation over the tables, recomputed per call — the
// I-41 forecast precedent: never cached, never persisted as first-class
// rows; the durable artifact is the audit event).
// ---------------------------------------------------------------------------

/** One row per OPEN/CLOSED period: the utilization inputs. */
data class PeriodUtilizationRow(
    val periodId: String,
    val limitMicroUnits: Long,
    val postedEffectsMicroUnits: Long,
    val remainingHoldMicroUnits: Long,
    val distinctPostingDays: Int
)

/** One row per (provider, canonical model): the comparison inputs. */
data class ProviderModelCostRow(
    val providerId: String,
    val canonicalModelId: String,
    val canonicalModelName: String,
    val calculationCount: Int,
    val totalCostMicroUnits: Long,
    val totalTokens: Double
)

/** One row per reconciliation record: the quality-indicator inputs. */
data class ReconciliationQualityRow(
    val reconciliationId: String,
    val providerId: String,
    val providerBillingRecordId: String,
    val totalAdjustments: Int,
    val totalAbsAmountMicroUnits: Long,
    val unexplainedAbsAmountMicroUnits: Long
)

/** One row per historical classification type for a provider. */
data class ClassificationCountRow(
    val adjustmentType: String,
    val cnt: Int
)

@Dao
interface RationalizationDao {

    /**
     * The under-utilization / overage inputs, one row per period. The
     * balance equation is recomputed from raw rows (و.2 — never cached);
     * the remaining-holds arm skips expired holds exactly like the
     * dashboard's.
     */
    @Query(
        "SELECT bp.budget_period_id AS periodId, " +
            "bp.limit_micro_units AS limitMicroUnits, " +
            "COALESCE((SELECT SUM(e.available_effect_micro_units) FROM budget_ledger_entry e WHERE e.budget_period_id = bp.budget_period_id AND e.posting_state='POSTED'), 0) AS postedEffectsMicroUnits, " +
            "COALESCE((SELECT SUM(remaining) FROM (" +
            "  SELECT h.hold_amount_micro_units - COALESCE((SELECT SUM(s.amount_micro_units) FROM budget_hold_settlement s WHERE s.hold_id = h.hold_id), 0) AS remaining " +
            "  FROM budget_hold h WHERE h.budget_period_id = bp.budget_period_id AND h.status IN ('ACTIVE','PARTIALLY_SETTLED')" +
            "   AND (h.expires_at_epoch_ms IS NULL OR h.expires_at_epoch_ms > :nowEpochMs))), 0) AS remainingHoldMicroUnits, " +
            "(SELECT COUNT(DISTINCT e.posted_at_epoch_ms / 86400000) FROM budget_ledger_entry e WHERE e.budget_period_id = bp.budget_period_id AND e.posting_state='POSTED' AND e.posted_at_epoch_ms IS NOT NULL) AS distinctPostingDays " +
            "FROM budget_period bp WHERE bp.period_status IN ('OPEN','CLOSED') ORDER BY bp.period_start_epoch_ms"
    )
    suspend fun periodUtilization(nowEpochMs: Long): List<PeriodUtilizationRow>

    /**
     * The provider-comparison inputs over the identity chain: a
     * calculation is attributed to a canonical model through its attempt ->
     * offering -> provider_model_record -> VERIFIED identity match. Token
     * volume is the measured input/output/cached tokens of the usage record
     * the calculation consumed (CAST to double for the RATIO only — the
     * money side stays exact integer micro units).
     */
    @Query(
        "SELECT a.provider_id AS providerId, " +
            "m.model_id AS canonicalModelId, " +
            "m.canonical_name AS canonicalModelName, " +
            "COUNT(DISTINCT c.cost_calculation_id) AS calculationCount, " +
            "COALESCE(SUM(c.total_amount_micro_units), 0) AS totalCostMicroUnits, " +
            "COALESCE(SUM((SELECT COALESCE(SUM(CAST(um.value_precise AS REAL)), 0) FROM usage_measurement um WHERE um.usage_record_id = c.usage_record_id AND um.metric_id IN ('METRIC_INPUT_TOKENS','METRIC_OUTPUT_TOKENS','METRIC_CACHED_TOKENS'))), 0) AS totalTokens " +
            "FROM cost_calculation c " +
            "JOIN provider_request_attempt a ON a.request_attempt_id = c.request_attempt_id " +
            "JOIN model_offering o ON o.offering_id = a.offering_id " +
            "JOIN model_identity_match mim ON mim.provider_model_record_id = o.provider_model_record_id AND mim.match_status = 'VERIFIED' " +
            "JOIN model m ON m.model_id = mim.canonical_model_id " +
            "WHERE c.total_amount_micro_units IS NOT NULL " +
            "GROUP BY a.provider_id, m.model_id, m.canonical_name " +
            "ORDER BY m.canonical_name, a.provider_id"
    )
    suspend fun providerModelCostVolume(): List<ProviderModelCostRow>

    /**
     * The quality-indicator inputs per reconciliation record: the absolute
     * adjustment volume and the LOCAL_UNMODELED (unexplained) share of it.
     */
    @Query(
        "SELECT rr.reconciliation_id AS reconciliationId, " +
            "pbr.provider_id AS providerId, " +
            "rr.provider_billing_record_id AS providerBillingRecordId, " +
            "COUNT(ra.adjustment_id) AS totalAdjustments, " +
            "COALESCE(SUM(ABS(ra.amount_micro_units)), 0) AS totalAbsAmountMicroUnits, " +
            "COALESCE(SUM(CASE WHEN ra.adjustment_type = 'LOCAL_UNMODELED_ADJUSTMENT' THEN ABS(ra.amount_micro_units) ELSE 0 END), 0) AS unexplainedAbsAmountMicroUnits " +
            "FROM reconciliation_record rr " +
            "JOIN provider_billing_record pbr ON pbr.provider_billing_record_id = rr.provider_billing_record_id " +
            "LEFT JOIN reconciliation_adjustment ra ON ra.reconciliation_id = rr.reconciliation_id " +
            "GROUP BY rr.reconciliation_id, pbr.provider_id, rr.provider_billing_record_id " +
            "ORDER BY rr.reconciliation_id"
    )
    suspend fun reconciliationQuality(): List<ReconciliationQualityRow>

    /**
     * The accumulated classification history for one provider, the modal
     * type first — the suggestion engine's input (the study: «مع تراكم
     * البيانات تتحول قواعد التصنيف اليدوية إلى اقتراحات تلقائية»).
     */
    @Query(
        "SELECT ra.adjustment_type AS adjustmentType, COUNT(*) AS cnt " +
            "FROM reconciliation_adjustment ra " +
            "JOIN reconciliation_record rr ON rr.reconciliation_id = ra.reconciliation_id " +
            "JOIN provider_billing_record pbr ON pbr.provider_billing_record_id = rr.provider_billing_record_id " +
            "WHERE pbr.provider_id = :providerId AND ra.adjustment_type <> 'LOCAL_UNMODELED_ADJUSTMENT' " +
            "GROUP BY ra.adjustment_type ORDER BY cnt DESC, adjustmentType ASC"
    )
    suspend fun classificationHistory(providerId: String): List<ClassificationCountRow>
}
