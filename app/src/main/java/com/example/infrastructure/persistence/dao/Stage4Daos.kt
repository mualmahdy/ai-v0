package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.infrastructure.persistence.entities.AccessContextEntity
import com.example.infrastructure.persistence.entities.AcquisitionRunEntity
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import com.example.infrastructure.persistence.entities.CapabilityStatementEntity
import com.example.infrastructure.persistence.entities.CredentialBindingEntity
import com.example.infrastructure.persistence.entities.CredentialReferenceEntity
import com.example.infrastructure.persistence.entities.CurrencyConversionRuleEvidenceEntity
import com.example.infrastructure.persistence.entities.EntitlementEvidenceEntity
import com.example.infrastructure.persistence.entities.EvidenceEntity
import com.example.infrastructure.persistence.entities.FreshnessPolicyEntity
import com.example.infrastructure.persistence.entities.MetricRelationshipEntity
import com.example.infrastructure.persistence.entities.MetricRelationshipEvidenceEntity
import com.example.infrastructure.persistence.entities.ModelEntity
import com.example.infrastructure.persistence.entities.ModelIdentityMatchEntity
import com.example.infrastructure.persistence.entities.ModelIdentityMatchEvidenceEntity
import com.example.infrastructure.persistence.entities.ModelOfferingEntity
import com.example.infrastructure.persistence.entities.ObservationEntity
import com.example.infrastructure.persistence.entities.ObservationEvidenceEntity
import com.example.infrastructure.persistence.entities.PricingComponentEvidenceEntity
import com.example.infrastructure.persistence.entities.PricingProfileEvidenceEntity
import com.example.infrastructure.persistence.entities.PricingProfileVersionEvidenceEntity
import com.example.infrastructure.persistence.entities.ProviderBillingRecordEvidenceEntity
import com.example.infrastructure.persistence.entities.ProviderCapabilityManifestEntity
import com.example.infrastructure.persistence.entities.PdmProviderEntity
import com.example.infrastructure.persistence.entities.ProviderIntegrationEntity
import com.example.infrastructure.persistence.entities.ProviderModelRecordEntity
import com.example.infrastructure.persistence.entities.ProviderRequestAttemptEntity
import com.example.infrastructure.persistence.entities.ProviderScopeNodeEntity
import com.example.infrastructure.persistence.entities.ReconciliationEvidenceEntity
import com.example.infrastructure.persistence.entities.RequestOperationEntity
import com.example.infrastructure.persistence.entities.ResourceConstraintEntity
import com.example.infrastructure.persistence.entities.ResourceConstraintEvidenceEntity
import com.example.infrastructure.persistence.entities.UsageRecordEvidenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * Stage-4 DAOs (v27): the evidence & sources network + the first
 * intelligence increment.
 * ============================================================================
 * All inserts are plain @Insert (ABORT on conflict): idempotency is a
 * service-level concern (read-before-insert replay, the v23 pattern) and a
 * silent replacement of evidence or an evidence link is forbidden (ADR-B4).
 * The legal status transitions into an AUTHORITATIVE state run through
 * explicit UPDATE queries so the trg_*_requires_primary_evidence guards
 * can police them (I-35).
 */
@Dao
interface EvidenceDao {
    @Insert
    suspend fun insert(evidence: EvidenceEntity)

    @Query("SELECT * FROM evidence WHERE evidence_id = :evidenceId LIMIT 1")
    suspend fun byId(evidenceId: String): EvidenceEntity?

    @Query("SELECT * FROM evidence WHERE payload_digest = :digest LIMIT 1")
    suspend fun byDigest(digest: String): EvidenceEntity?

    @Query("SELECT COUNT(*) FROM evidence")
    suspend fun count(): Int
}

/** The twelve typed link tables. hasPrimary* is the service half of the
 *  I-35 guard (the DB half is the trigger family). */
@Dao
interface EvidenceLinkDao {
    @Insert
    suspend fun linkCurrencyConversionRule(link: CurrencyConversionRuleEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM currency_conversion_rule_evidence WHERE currency_conversion_rule_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryCurrencyConversionRule(t: String): Boolean

    @Insert
    suspend fun linkEntitlement(link: EntitlementEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM entitlement_evidence WHERE entitlement_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryEntitlement(t: String): Boolean

    @Insert
    suspend fun linkMetricRelationship(link: MetricRelationshipEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM metric_relationship_evidence WHERE relation_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryMetricRelationship(t: String): Boolean

    @Insert
    suspend fun linkModelIdentityMatch(link: ModelIdentityMatchEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM model_identity_match_evidence WHERE match_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryModelIdentityMatch(t: String): Boolean

    @Insert
    suspend fun linkObservation(link: ObservationEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM observation_evidence WHERE observation_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryObservation(t: String): Boolean

    @Insert
    suspend fun linkPricingComponent(link: PricingComponentEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM pricing_component_evidence WHERE pricing_component_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryPricingComponent(t: String): Boolean

    @Insert
    suspend fun linkPricingProfile(link: PricingProfileEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM pricing_profile_evidence WHERE pricing_profile_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryPricingProfile(t: String): Boolean

    @Insert
    suspend fun linkPricingProfileVersion(link: PricingProfileVersionEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM pricing_profile_version_evidence WHERE pricing_profile_version_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryPricingProfileVersion(t: String): Boolean

    @Insert
    suspend fun linkProviderBillingRecord(link: ProviderBillingRecordEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM provider_billing_record_evidence WHERE provider_billing_record_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryProviderBillingRecord(t: String): Boolean

    @Insert
    suspend fun linkReconciliation(link: ReconciliationEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM reconciliation_evidence WHERE reconciliation_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryReconciliation(t: String): Boolean

    @Insert
    suspend fun linkResourceConstraint(link: ResourceConstraintEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM resource_constraint_evidence WHERE constraint_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryResourceConstraint(t: String): Boolean

    @Insert
    suspend fun linkUsageRecord(link: UsageRecordEvidenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM usage_record_evidence WHERE usage_record_id=:t AND evidence_role='PRIMARY')")
    suspend fun hasPrimaryUsageRecord(t: String): Boolean

    // -- composite-PK existence checks (the v23 read-before-insert replay
    //    pattern for links; a replay must be distinguishable from an error) --

    @Query("SELECT EXISTS(SELECT 1 FROM currency_conversion_rule_evidence WHERE currency_conversion_rule_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun currencyConversionRuleLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM entitlement_evidence WHERE entitlement_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun entitlementLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM metric_relationship_evidence WHERE relation_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun metricRelationshipLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM model_identity_match_evidence WHERE match_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun modelIdentityMatchLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM observation_evidence WHERE observation_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun observationLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pricing_component_evidence WHERE pricing_component_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun pricingComponentLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pricing_profile_evidence WHERE pricing_profile_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun pricingProfileLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pricing_profile_version_evidence WHERE pricing_profile_version_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun pricingProfileVersionLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM provider_billing_record_evidence WHERE provider_billing_record_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun providerBillingRecordLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM reconciliation_evidence WHERE reconciliation_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun reconciliationLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM resource_constraint_evidence WHERE constraint_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun resourceConstraintLinkExists(t: String, e: String, r: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM usage_record_evidence WHERE usage_record_id=:t AND evidence_id=:e AND evidence_role=:r)")
    suspend fun usageRecordLinkExists(t: String, e: String, r: String): Boolean

    /** Count of every PRIMARY link across the twelve tables — dashboard /
     *  coverage evidence (how many authoritative facts carry their source). */
    @Query(
        "SELECT (" +
            "(SELECT COUNT(*) FROM currency_conversion_rule_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM entitlement_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM metric_relationship_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM model_identity_match_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM observation_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM pricing_component_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM pricing_profile_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM pricing_profile_version_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM provider_billing_record_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM reconciliation_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM resource_constraint_evidence WHERE evidence_role='PRIMARY') + " +
            "(SELECT COUNT(*) FROM usage_record_evidence WHERE evidence_role='PRIMARY'))"
    )
    suspend fun countPrimaryLinks(): Int
}

/** The acquisition chain (Group ج first half). */
@Dao
interface ProviderRegistryDao {
    @Insert
    suspend fun insertProvider(provider: PdmProviderEntity)

    @Query("SELECT * FROM provider WHERE provider_id = :providerId LIMIT 1")
    suspend fun providerById(providerId: String): PdmProviderEntity?

    @Query("SELECT * FROM provider WHERE provider_code = :code LIMIT 1")
    suspend fun providerByCode(code: String): PdmProviderEntity?

    @Insert
    suspend fun insertIntegration(integration: ProviderIntegrationEntity)

    @Query("SELECT * FROM provider_integration WHERE integration_id = :integrationId LIMIT 1")
    suspend fun integrationById(integrationId: String): ProviderIntegrationEntity?

    @Insert
    suspend fun insertScopeNode(node: ProviderScopeNodeEntity)

    @Query("SELECT * FROM provider_scope_node WHERE scope_node_id = :scopeNodeId LIMIT 1")
    suspend fun scopeNodeById(scopeNodeId: String): ProviderScopeNodeEntity?

    @Insert
    suspend fun insertManifest(manifest: ProviderCapabilityManifestEntity)

    @Query("SELECT * FROM provider_capability_manifest WHERE manifest_id = :manifestId LIMIT 1")
    suspend fun manifestById(manifestId: String): ProviderCapabilityManifestEntity?

    @Insert
    suspend fun insertCapabilityStatement(statement: CapabilityStatementEntity)

    @Query("SELECT * FROM capability_statement WHERE capability_id = :capabilityId LIMIT 1")
    suspend fun capabilityStatementById(capabilityId: String): CapabilityStatementEntity?

    @Insert
    suspend fun insertCredentialReference(reference: CredentialReferenceEntity)

    @Query("SELECT * FROM credential_reference WHERE credential_ref_id = :credentialRefId LIMIT 1")
    suspend fun credentialReferenceById(credentialRefId: String): CredentialReferenceEntity?

    @Insert
    suspend fun insertAccessContext(context: AccessContextEntity)

    @Query("SELECT * FROM access_context WHERE access_context_id = :accessContextId LIMIT 1")
    suspend fun accessContextById(accessContextId: String): AccessContextEntity?

    @Insert
    suspend fun insertCredentialBinding(binding: CredentialBindingEntity)

    @Query("SELECT * FROM credential_binding WHERE credential_binding_id = :bindingId LIMIT 1")
    suspend fun credentialBindingById(bindingId: String): CredentialBindingEntity?

    @Insert
    suspend fun insertAcquisitionRun(run: AcquisitionRunEntity)

    @Query("SELECT * FROM acquisition_run WHERE acquisition_run_id = :runId LIMIT 1")
    suspend fun acquisitionRunById(runId: String): AcquisitionRunEntity?
}

/** The canonical model registry + identity matches + request attempts. */
@Dao
interface ModelRegistryDao {
    @Insert
    suspend fun insertModel(model: ModelEntity)

    @Query("SELECT * FROM model WHERE model_id = :modelId LIMIT 1")
    suspend fun modelById(modelId: String): ModelEntity?

    @Insert
    suspend fun insertProviderModelRecord(record: ProviderModelRecordEntity)

    @Query("SELECT * FROM provider_model_record WHERE provider_model_record_id = :recordId LIMIT 1")
    suspend fun providerModelRecordById(recordId: String): ProviderModelRecordEntity?

    @Insert
    suspend fun insertIdentityMatch(match: ModelIdentityMatchEntity)

    @Query("SELECT * FROM model_identity_match WHERE match_id = :matchId LIMIT 1")
    suspend fun identityMatchById(matchId: String): ModelIdentityMatchEntity?

    /** Legal transition into the authoritative state — the explicit UPDATE
     *  the trg_match_verified_requires_primary_evidence guard polices. */
    @Query("UPDATE model_identity_match SET match_status='VERIFIED' WHERE match_id=:matchId AND match_status='PROPOSED'")
    suspend fun verifyIdentityMatch(matchId: String): Int

    @Insert
    suspend fun insertOffering(offering: ModelOfferingEntity)

    @Query("SELECT * FROM model_offering WHERE offering_id = :offeringId LIMIT 1")
    suspend fun offeringById(offeringId: String): ModelOfferingEntity?

    @Insert
    suspend fun insertRequestOperation(operation: RequestOperationEntity)

    @Query("SELECT * FROM request_operation WHERE request_operation_id = :operationId LIMIT 1")
    suspend fun requestOperationById(operationId: String): RequestOperationEntity?

    @Insert
    suspend fun insertRequestAttempt(attempt: ProviderRequestAttemptEntity)

    @Query("SELECT * FROM provider_request_attempt WHERE request_attempt_id = :attemptId LIMIT 1")
    suspend fun requestAttemptById(attemptId: String): ProviderRequestAttemptEntity?
}

/** observation + freshness policies (Group د). */
@Dao
interface ObservationDao {
    @Insert
    suspend fun insertObservation(observation: ObservationEntity)

    @Query("SELECT * FROM observation WHERE observation_id = :observationId LIMIT 1")
    suspend fun observationById(observationId: String): ObservationEntity?

    @Query("SELECT COUNT(*) FROM observation")
    suspend fun count(): Int

    @Insert
    suspend fun insertFreshnessPolicy(policy: FreshnessPolicyEntity)

    @Query("SELECT * FROM freshness_policy WHERE policy_id = :policyId LIMIT 1")
    suspend fun freshnessPolicyById(policyId: String): FreshnessPolicyEntity?
}

/** metric relationships + resource constraints, with their legal
 *  authoritative transitions (guarded by the I-35 trigger family). */
@Dao
interface SemanticsDao {
    @Insert
    suspend fun insertMetricRelationship(relation: MetricRelationshipEntity)

    @Query("SELECT * FROM metric_relationship WHERE relation_id = :relationId LIMIT 1")
    suspend fun metricRelationshipById(relationId: String): MetricRelationshipEntity?

    @Query("UPDATE metric_relationship SET relation_status='ACTIVE' WHERE relation_id=:relationId AND relation_status<>'ACTIVE' AND relation_status<>'REJECTED' AND relation_status<>'SUPERSEDED'")
    suspend fun activateMetricRelationship(relationId: String): Int

    @Insert
    suspend fun insertResourceConstraint(constraint: ResourceConstraintEntity)

    @Query("SELECT * FROM resource_constraint WHERE constraint_id = :constraintId LIMIT 1")
    suspend fun resourceConstraintById(constraintId: String): ResourceConstraintEntity?

    @Query("UPDATE resource_constraint SET constraint_status='ACTIVE' WHERE constraint_id=:constraintId AND constraint_status<>'ACTIVE'")
    suspend fun activateResourceConstraint(constraintId: String): Int
}

/**
 * The forecast inputs: the DAILY posted series of a period. Grouped by
 * UTC day of posted_at_epoch_ms (the balance equation's posted term is
 * available_effect over POSTED entries; consumption is negative). Days
 * with no postings simply do not appear — the service fills gaps as
 * zero-spend days (documented I-38).
 */
@Dao
interface BudgetForecastDao {
    @Query(
        "SELECT (posted_at_epoch_ms / 86400000) AS utcDay, " +
            "COALESCE(SUM(available_effect_micro_units), 0) AS postedEffectMicroUnits " +
            "FROM budget_ledger_entry " +
            "WHERE budget_period_id = :periodId AND posting_state = 'POSTED' " +
            "AND posted_at_epoch_ms IS NOT NULL " +
            "GROUP BY utcDay ORDER BY utcDay"
    )
    suspend fun postedEffectsByDay(periodId: String): List<DailyPostedEffect>

    @Query("SELECT COUNT(*) FROM budget_ledger_entry WHERE budget_period_id = :periodId AND posting_state='POSTED' AND posted_at_epoch_ms IS NOT NULL")
    suspend fun countPostedWithTimestamp(periodId: String): Int
}

data class DailyPostedEffect(val utcDay: Long, val postedEffectMicroUnits: Long)

/**
 * The live dashboard (roadmap: "لوحة متابعة حية — استهلاك حسب الوكيل/
 * المزود/النموذج/مساحة العمل، ونسب الاستغلال، ورصيد كل فترة"). Flows
 * read straight from Room (the presentation layer consumes Flows).
 * Provider attribution rides the pricing chain (calculation -> version ->
 * profile -> provider_id, I-23's precedent); scope-kind attribution rides
 * the calculation scope; per-period balance recomputes the WHOLE balance
 * equation from raw rows in SQL (و.2 — never cached).
 */
@Dao
interface BudgetDashboardDao {
    /** One row per period, balance equation recomputed from raw rows. */
    @Query(
        "SELECT bp.budget_period_id AS periodId, " +
            "bp.period_start_epoch_ms AS periodStartEpochMs, " +
            "bp.period_end_epoch_ms AS periodEndEpochMs, " +
            "bp.limit_micro_units AS limitMicroUnits, " +
            "COALESCE((SELECT SUM(e.available_effect_micro_units) FROM budget_ledger_entry e WHERE e.budget_period_id = bp.budget_period_id AND e.posting_state='POSTED'), 0) AS postedEffectsMicroUnits, " +
            "COALESCE((SELECT SUM(remaining) FROM (" +
            "  SELECT h.hold_amount_micro_units - COALESCE((SELECT SUM(s.amount_micro_units) FROM budget_hold_settlement s WHERE s.hold_id = h.hold_id), 0) AS remaining " +
            "  FROM budget_hold h WHERE h.budget_period_id = bp.budget_period_id AND h.status IN ('ACTIVE','PARTIALLY_SETTLED')" +
            "   AND (h.expires_at_epoch_ms IS NULL OR h.expires_at_epoch_ms > :nowEpochMs))), 0) AS remainingHoldMicroUnits " +
            "FROM budget_period bp WHERE bp.period_status IN ('OPEN','CLOSED') " +
            "ORDER BY bp.period_start_epoch_ms"
    )
    fun periodBalancesFlow(nowEpochMs: Long): Flow<List<PeriodBalanceRow>>

    /** Entries of one period (live Flow — the ledger feed). */
    @Query(
        "SELECT * FROM budget_ledger_entry WHERE budget_period_id = :periodId " +
            "ORDER BY entry_timestamp_epoch_ms"
    )
    fun entriesFlow(periodId: String): Flow<List<BudgetLedgerEntryEntity>>

    /** Holds of one period (live Flow). */
    @Query(
        "SELECT * FROM budget_hold WHERE budget_period_id = :periodId ORDER BY created_at_epoch_ms"
    )
    fun holdsFlow(periodId: String): Flow<List<BudgetHoldEntity>>

    /**
     * Cost by provider over the pricing chain. Only CALCULATED cost is
     * attributable — entries without a calculation line are excluded and
     * the dashboard says so (I-37: coverage honesty, the coverage-status
     * precedent from Stage 3).
     */
    @Query(
        "SELECT pp.provider_id AS providerId, COUNT(DISTINCT c.cost_calculation_id) AS calculationCount, " +
            "COALESCE(SUM(l.final_amount_micro_units), 0) AS calculatedCostMicroUnits " +
            "FROM cost_calculation_line l " +
            "JOIN cost_calculation c ON c.cost_calculation_id = l.cost_calculation_id " +
            "JOIN pricing_profile_version v ON v.pricing_profile_version_id = c.pricing_profile_version_id " +
            "JOIN pricing_profile pp ON pp.pricing_profile_id = v.pricing_profile_id " +
            "GROUP BY pp.provider_id ORDER BY calculatedCostMicroUnits DESC"
    )
    fun costByProviderFlow(): Flow<List<ProviderCostRow>>
}

data class PeriodBalanceRow(
    val periodId: String,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val limitMicroUnits: Long,
    val postedEffectsMicroUnits: Long,
    val remainingHoldMicroUnits: Long
) {
    /** available = limit + ΣPOSTED − Σremaining_holds (و.2). A function on
     *  purpose — Room must not try to map it as a query column. */
    fun availableMicroUnits(): Long =
        limitMicroUnits + postedEffectsMicroUnits - remainingHoldMicroUnits
}

data class ProviderCostRow(
    val providerId: String,
    val calculationCount: Int,
    val calculatedCostMicroUnits: Long
)
