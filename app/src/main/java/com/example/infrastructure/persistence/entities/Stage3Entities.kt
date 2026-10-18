package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * STAGE 3 (Room v26, MIGRATION_25_TO_26 — PDM v0.2 Stage-3 subset)
 * ============================================================================
 * Currency conversion rules, provider billing records, reconciliation
 * (record + inclusions + adjustments) and entitlements (record +
 * consumption) — the approved roadmap Phase-3 scope, closing gates
 * PDM-G03 (comparison currency & direction) and PDM-G04 (result vs
 * finality separation).
 *
 * Same adaptation rules as v23/v25 (Stage-0 Section د.2):
 *  - Money is integer MICRO units (ADR-B1); precise decimals are TEXT.
 *  - CHECK constraints live in [com.example.infrastructure.persistence
 *    .Stage3MigrationSql] only (Room's TableInfo ignores them).
 *  - UNIQUE constraints are NAMED indices declared identically here and
 *    in the migration DDL (byte-identical schema from both paths).
 *  - Deferred-FK columns (provider, access_context, provider_request_
 *    attempt — later PDM groups) are plain columns with no @ForeignKey
 *    (v0.2 change #8).
 *  - The one DEFAULT in the DDL (reconciliation_record.finality_status
 *    'DRAFT') is declared here verbatim so entity-created tables match.
 *  - Evidence tables are append-only at the DATABASE level (immutability
 *    + finality triggers in Stage3MigrationSql, ADR-B2 extension);
 *    entitlement_record itself stays mutable (administrative record —
 *    the pricing_profile precedent, I-28).
 */
@Entity(
    tableName = "currency_conversion_rule",
    indices = [
        Index(value = ["from_currency", "to_currency", "effective_from_epoch_ms"], name = "ix_currency_conversion_pair")
    ]
)
data class CurrencyConversionRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "currency_conversion_rule_id") val currencyConversionRuleId: String,
    @ColumnInfo(name = "from_currency") val fromCurrency: String,
    @ColumnInfo(name = "to_currency") val toCurrency: String,
    /** Exact decimal TEXT (G08 discipline; never a binary float). */
    @ColumnInfo(name = "rate_precise") val ratePrecise: String,
    /** G03: always TARGET_UNITS_PER_SOURCE_UNIT. */
    @ColumnInfo(name = "rate_direction") val rateDirection: String,
    @ColumnInfo(name = "effective_from_epoch_ms") val effectiveFromEpochMs: Long,
    @ColumnInfo(name = "effective_until_epoch_ms") val effectiveUntilEpochMs: Long?,
    @ColumnInfo(name = "source_authority") val sourceAuthority: String,
    @ColumnInfo(name = "rounding_policy") val roundingPolicy: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "provider_billing_record",
    indices = [
        Index(value = ["provider_id", "scope_id", "period_start_epoch_ms", "period_end_epoch_ms"], name = "ix_provider_billing_period_scope"),
        Index(value = ["provider_id", "access_context_id", "external_billing_id"], name = "ix_provider_billing_external_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderBillingRecordEntity::class,
            parentColumns = ["provider_billing_record_id"],
            childColumns = ["adjusts_billing_record_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderBillingRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_billing_record_id") val providerBillingRecordId: String,
    /** Deferred FK — the provider table is a later PDM group (I-12). */
    @ColumnInfo(name = "provider_id") val providerId: String,
    /** Deferred FK — access_context is a later PDM group (I-12). */
    @ColumnInfo(name = "access_context_id") val accessContextId: String?,
    @ColumnInfo(name = "external_billing_id") val externalBillingId: String?,
    /** INVOICE / CREDIT_NOTE / STATEMENT / ADJUSTMENT (service constants). */
    @ColumnInfo(name = "billing_record_type") val billingRecordType: String,
    @ColumnInfo(name = "record_granularity") val recordGranularity: String,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long?,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long?,
    @ColumnInfo(name = "scope_id") val scopeId: String?,
    @ColumnInfo(name = "primary_amount_type") val primaryAmountType: String,
    @ColumnInfo(name = "primary_amount_micro_units") val primaryAmountMicroUnits: Long?,
    @ColumnInfo(name = "gross_payable_micro_units") val grossPayableMicroUnits: Long?,
    @ColumnInfo(name = "net_before_tax_micro_units") val netBeforeTaxMicroUnits: Long?,
    @ColumnInfo(name = "tax_amount_micro_units") val taxAmountMicroUnits: Long?,
    @ColumnInfo(name = "credit_amount_micro_units") val creditAmountMicroUnits: Long?,
    @ColumnInfo(name = "adjustment_amount_micro_units") val adjustmentAmountMicroUnits: Long?,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "amount_consistency_status") val amountConsistencyStatus: String,
    @ColumnInfo(name = "source_authority") val sourceAuthority: String,
    @ColumnInfo(name = "reported_at_epoch_ms") val reportedAtEpochMs: Long?,
    @ColumnInfo(name = "retrieved_at_epoch_ms") val retrievedAtEpochMs: Long?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
    @ColumnInfo(name = "billing_status") val billingStatus: String,
    /** Correction chain: this row adjusts that row (never UPDATE, ADR-B2). */
    @ColumnInfo(name = "adjusts_billing_record_id") val adjustsBillingRecordId: String?
)

@Entity(
    tableName = "reconciliation_record",
    indices = [
        Index(
            value = ["provider_billing_record_id", "scope_id", "period_start_epoch_ms", "period_end_epoch_ms", "finality_status"],
            name = "ix_reconciliation_billing_scope_period"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProviderBillingRecordEntity::class,
            parentColumns = ["provider_billing_record_id"],
            childColumns = ["provider_billing_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CurrencyConversionRuleEntity::class,
            parentColumns = ["currency_conversion_rule_id"],
            childColumns = ["conversion_rule_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ReconciliationRecordEntity::class,
            parentColumns = ["reconciliation_id"],
            childColumns = ["superseded_by"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ReconciliationRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "reconciliation_id") val reconciliationId: String,
    @ColumnInfo(name = "provider_billing_record_id") val providerBillingRecordId: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long,
    @ColumnInfo(name = "provider_currency") val providerCurrency: String,
    /** The currency the internal cost lines were computed in. */
    @ColumnInfo(name = "local_currency") val localCurrency: String,
    /** G03: the single currency the comparison happens in. */
    @ColumnInfo(name = "comparison_currency") val comparisonCurrency: String,
    /** NULL exactly when provider_currency == local_currency (CHECK). */
    @ColumnInfo(name = "conversion_rule_id") val conversionRuleId: String?,
    @ColumnInfo(name = "local_amount_micro_units") val localAmountMicroUnits: Long?,
    @ColumnInfo(name = "provider_amount_micro_units") val providerAmountMicroUnits: Long?,
    /** provider_amount − internal_amount_in_comparison_currency (signed). */
    @ColumnInfo(name = "difference_micro_units") val differenceMicroUnits: Long?,
    @ColumnInfo(name = "tolerance_micro_units") val toleranceMicroUnits: Long?,
    @ColumnInfo(name = "coverage_status") val coverageStatus: String,
    /** The RESULT of the comparison (what was found). */
    @ColumnInfo(name = "reconciliation_status") val reconciliationStatus: String,
    /** The SIGN-OFF machine (G04): DRAFT -> FINALIZED / REVOKED. */
    @ColumnInfo(name = "finality_status", defaultValue = "DRAFT") val finalityStatus: String,
    @ColumnInfo(name = "reason_code") val reasonCode: String?,
    @ColumnInfo(name = "rule_version") val ruleVersion: String,
    @ColumnInfo(name = "reconciled_at_epoch_ms") val reconciledAtEpochMs: Long?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
    /** Set when this DRAFT was superseded by a newer reconciliation. */
    @ColumnInfo(name = "superseded_by") val supersededBy: String?
)

@Entity(
    tableName = "reconciliation_inclusion",
    indices = [
        Index(value = ["reconciliation_id", "cost_calculation_line_id"], unique = true, name = "ux_reconciliation_inclusion_line")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ReconciliationRecordEntity::class,
            parentColumns = ["reconciliation_id"],
            childColumns = ["reconciliation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CostCalculationLineEntity::class,
            parentColumns = ["line_id"],
            childColumns = ["cost_calculation_line_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ReconciliationInclusionEntity(
    @PrimaryKey
    @ColumnInfo(name = "inclusion_id") val inclusionId: String,
    @ColumnInfo(name = "reconciliation_id") val reconciliationId: String,
    @ColumnInfo(name = "cost_calculation_line_id") val costCalculationLineId: String,
    @ColumnInfo(name = "included_amount_micro_units") val includedAmountMicroUnits: Long,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "inclusion_status") val inclusionStatus: String
)

@Entity(
    tableName = "reconciliation_adjustment",
    indices = [
        Index(value = ["reconciliation_id"], name = "ix_reconciliation_adjustment_reconciliation")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ReconciliationRecordEntity::class,
            parentColumns = ["reconciliation_id"],
            childColumns = ["reconciliation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ReconciliationAdjustmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "adjustment_id") val adjustmentId: String,
    @ColumnInfo(name = "reconciliation_id") val reconciliationId: String,
    /** PROVIDER_ONLY_TAX / PROVIDER_ONLY_CREDIT / PROVIDER_ONLY_FEE / CONVERSION_DIFFERENCE / TOLERANCE / LOCAL_UNMODELED_ADJUSTMENT. */
    @ColumnInfo(name = "adjustment_type") val adjustmentType: String,
    /** Signed: taxes positive, credits negative, conversion deltas either. */
    @ColumnInfo(name = "amount_micro_units") val amountMicroUnits: Long,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "reason_code") val reasonCode: String,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "entitlement_record",
    indices = [
        Index(value = ["applicability_scope_id"], name = "ix_entitlement_record_scope")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["applicability_scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["credit_unit_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PricingProfileEntity::class,
            parentColumns = ["pricing_profile_id"],
            childColumns = ["linked_pricing_profile_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class EntitlementRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "entitlement_id") val entitlementId: String,
    /** Deferred FK — the provider table is a later PDM group (I-12). */
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "applicability_scope_id") val applicabilityScopeId: String,
    @ColumnInfo(name = "entitlement_type") val entitlementType: String,
    /** Exactly-one-of value shape (table CHECK + service validation). */
    @ColumnInfo(name = "value_kind") val valueKind: String,
    @ColumnInfo(name = "quantity_limit_precise") val quantityLimitPrecise: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "monetary_limit_micro_units") val monetaryLimitMicroUnits: Long?,
    @ColumnInfo(name = "currency") val currency: String?,
    @ColumnInfo(name = "credit_limit_precise") val creditLimitPrecise: String?,
    @ColumnInfo(name = "credit_unit_id") val creditUnitId: String?,
    @ColumnInfo(name = "price_override_mode") val priceOverrideMode: String?,
    @ColumnInfo(name = "linked_pricing_profile_id") val linkedPricingProfileId: String?,
    @ColumnInfo(name = "starts_at_epoch_ms") val startsAtEpochMs: Long?,
    @ColumnInfo(name = "expires_at_epoch_ms") val expiresAtEpochMs: Long?,
    @ColumnInfo(name = "reset_policy") val resetPolicy: String?,
    @ColumnInfo(name = "stacking_policy") val stackingPolicy: String?,
    /** Spend-routing precedence (consumed by the Stage-4 intelligence). */
    @ColumnInfo(name = "consumption_order") val consumptionOrder: Int?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "verification_status") val verificationStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "entitlement_consumption",
    indices = [
        Index(value = ["entitlement_id", "idempotency_key"], unique = true, name = "ux_entitlement_consumption_idem"),
        Index(value = ["usage_record_id"], name = "ix_entitlement_consumption_usage_record")
    ],
    foreignKeys = [
        ForeignKey(
            entity = EntitlementRecordEntity::class,
            parentColumns = ["entitlement_id"],
            childColumns = ["entitlement_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UsageRecordEntity::class,
            parentColumns = ["usage_record_id"],
            childColumns = ["usage_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UsageMeasurementEntity::class,
            parentColumns = ["measurement_id"],
            childColumns = ["usage_measurement_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class EntitlementConsumptionEntity(
    @PrimaryKey
    @ColumnInfo(name = "consumption_id") val consumptionId: String,
    @ColumnInfo(name = "entitlement_id") val entitlementId: String,
    @ColumnInfo(name = "consumption_granularity") val consumptionGranularity: String,
    /** Deferred FK — provider_request_attempt is a later PDM group (I-12). */
    @ColumnInfo(name = "request_attempt_id") val requestAttemptId: String?,
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String?,
    @ColumnInfo(name = "usage_measurement_id") val usageMeasurementId: String?,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long?,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "quantity_precise") val quantityPrecise: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "amount_micro_units") val amountMicroUnits: Long?,
    @ColumnInfo(name = "credit_amount_precise") val creditAmountPrecise: String?,
    @ColumnInfo(name = "consumption_status") val consumptionStatus: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long
)
