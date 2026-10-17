package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * STAGE 2 (Room v25, MIGRATION_24_TO_25 — PDM v0.2 Stage-2 subset)
 * ============================================================================
 * Pricing catalog, generic usage metering and itemized cost breakdown
 * (feasibility roadmap Phase 2, approved scope; first Stage-2 item I-5 —
 * production settlement hooks — lives in HoldSettlementService).
 *
 * Same adaptation rules as the v23 budget core (Stage-0 Section د.2):
 *  - Money is integer MICRO units (ADR-B1); precise decimal evidence is
 *    preserved as TEXT (G08: deterministic decimal, never binary floats).
 *  - CHECK constraints live in [com.example.infrastructure.persistence
 *    .Stage2MigrationSql] only (Room's TableInfo ignores them).
 *  - UNIQUE constraints are NAMED indices declared identically here and in
 *    the migration DDL (byte-identical schema from both creation paths).
 *  - Deferred-FK columns (provider, provider_request_attempt — Stage-3
 *    tables) are plain columns with no @ForeignKey (v0.2 change #8).
 *  - INTEGER defaults present in the DDL are declared here verbatim
 *    ("0") so entity-created tables validate against migrated ones.
 *  - Every fact table below is append-only at the DATABASE level (eighteen
 *    immutability triggers, ADR-B2 extension) — corrections arrive as new
 *    version/report/calculation rows, never as UPDATE or DELETE.
 */
@Entity(
    tableName = "unit_definition",
    indices = [
        Index(value = ["unit_code"], unique = true, name = "ux_unit_definition_code")
    ],
    foreignKeys = [
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["base_unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class UnitDefinitionEntity(
    @PrimaryKey
    @ColumnInfo(name = "unit_id") val unitId: String,
    @ColumnInfo(name = "unit_code") val unitCode: String,
    @ColumnInfo(name = "unit_family") val unitFamily: String,
    @ColumnInfo(name = "dimension") val dimension: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "base_unit_id") val baseUnitId: String?,
    @ColumnInfo(name = "conversion_rule") val conversionRule: String?,
    @ColumnInfo(name = "conversion_authority") val conversionAuthority: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "metric_definition",
    indices = [
        Index(value = ["metric_code"], unique = true, name = "ux_metric_definition_code")
    ],
    foreignKeys = [
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["default_unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class MetricDefinitionEntity(
    @PrimaryKey
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "metric_code") val metricCode: String,
    @ColumnInfo(name = "metric_domain") val metricDomain: String,
    @ColumnInfo(name = "metric_role") val metricRole: String,
    @ColumnInfo(name = "metric_kind") val metricKind: String,
    @ColumnInfo(name = "default_unit_id") val defaultUnitId: String?,
    @ColumnInfo(name = "value_type") val valueType: String,
    @ColumnInfo(name = "aggregation_semantics") val aggregationSemantics: String,
    @ColumnInfo(name = "is_monotonic", defaultValue = "0") val isMonotonic: Int,
    @ColumnInfo(name = "reset_policy") val resetPolicy: String?,
    @ColumnInfo(name = "default_window") val defaultWindow: String?,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

/**
 * A pricing profile = one provider's price list for ONE applicability
 * target, identified by [profileCode] (`SCOPE:model:service`, `-` for a
 * blank segment). Versions hang off it with non-overlapping effective
 * windows; components (and optional tiers) hang off versions.
 */
@Entity(
    tableName = "pricing_profile",
    indices = [
        Index(value = ["provider_id", "profile_code"], unique = true, name = "ux_pricing_profile_provider_code")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["applicability_scope_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "pricing_profile_id") val pricingProfileId: String,
    /** Deferred FK — the provider table is Stage-3 scope (I-12). */
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "profile_code") val profileCode: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "applicability_scope_id") val applicabilityScopeId: String,
    @ColumnInfo(name = "profile_status") val profileStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

@Entity(
    tableName = "pricing_profile_version",
    indices = [
        Index(value = ["pricing_profile_id", "version_number"], unique = true, name = "ux_ppv_profile_version"),
        Index(value = ["pricing_profile_version_id", "pricing_profile_id"], unique = true, name = "ux_ppv_id_profile")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PricingProfileEntity::class,
            parentColumns = ["pricing_profile_id"],
            childColumns = ["pricing_profile_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingProfileVersionEntity(
    @PrimaryKey
    @ColumnInfo(name = "pricing_profile_version_id") val pricingProfileVersionId: String,
    @ColumnInfo(name = "pricing_profile_id") val pricingProfileId: String,
    @ColumnInfo(name = "version_number") val versionNumber: Int,
    /** Provenance of the price data, e.g. `MIGRATED:pricing_entries:<id>`. */
    @ColumnInfo(name = "pricing_source") val pricingSource: String,
    @ColumnInfo(name = "authority") val authority: String,
    @ColumnInfo(name = "effective_from_epoch_ms") val effectiveFromEpochMs: Long,
    @ColumnInfo(name = "effective_until_epoch_ms") val effectiveUntilEpochMs: Long?,
    @ColumnInfo(name = "retrieved_at_epoch_ms") val retrievedAtEpochMs: Long?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
    @ColumnInfo(name = "effective_status") val effectiveStatus: String,
    @ColumnInfo(name = "verification_status") val verificationStatus: String,
    @ColumnInfo(name = "precedence", defaultValue = "0") val precedence: Int,
    @ColumnInfo(name = "pricing_status") val pricingStatus: String,
    /** G08 (closed this stage): ROUND_LINE_THEN_SUM is the v1 policy. */
    @ColumnInfo(name = "rounding_strategy") val roundingStrategy: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "pricing_component",
    indices = [
        Index(
            value = ["pricing_profile_version_id", "component_code", "component_order"],
            unique = true,
            name = "ux_pricing_component_version_code_order"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = PricingProfileVersionEntity::class,
            parentColumns = ["pricing_profile_version_id"],
            childColumns = ["pricing_profile_version_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["metric_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["quantity_unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingComponentEntity(
    @PrimaryKey
    @ColumnInfo(name = "pricing_component_id") val pricingComponentId: String,
    @ColumnInfo(name = "pricing_profile_version_id") val pricingProfileVersionId: String,
    @ColumnInfo(name = "component_code") val componentCode: String,
    @ColumnInfo(name = "component_type") val componentType: String,
    @ColumnInfo(name = "metric_id") val metricId: String?,
    @ColumnInfo(name = "quantity_unit_id") val quantityUnitId: String?,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "calculation_mode") val calculationMode: String,
    @ColumnInfo(name = "component_application_basis") val componentApplicationBasis: String?,
    @ColumnInfo(name = "tier_application_mode") val tierApplicationMode: String,
    @ColumnInfo(name = "quantity_scope") val quantityScope: String,
    /** Exact decimal rate in micro currency units per [priceUnitQuantityPrecise] units (TEXT, G08). */
    @ColumnInfo(name = "rate_amount_precise") val rateAmountPrecise: String?,
    @ColumnInfo(name = "price_unit_quantity_precise") val priceUnitQuantityPrecise: String?,
    @ColumnInfo(name = "rounding_policy") val roundingPolicy: String?,
    @ColumnInfo(name = "component_order") val componentOrder: Int,
    @ColumnInfo(name = "component_status") val componentStatus: String
)

@Entity(
    tableName = "pricing_tier",
    indices = [
        Index(value = ["component_id", "tier_order"], unique = true, name = "ux_pricing_tier_component_order")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PricingComponentEntity::class,
            parentColumns = ["pricing_component_id"],
            childColumns = ["component_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["quantity_unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingTierEntity(
    @PrimaryKey
    @ColumnInfo(name = "tier_id") val tierId: String,
    @ColumnInfo(name = "component_id") val componentId: String,
    @ColumnInfo(name = "tier_order") val tierOrder: Int,
    @ColumnInfo(name = "lower_bound_precise") val lowerBoundPrecise: String,
    @ColumnInfo(name = "upper_bound_precise") val upperBoundPrecise: String?,
    @ColumnInfo(name = "bound_closure") val boundClosure: String,
    @ColumnInfo(name = "quantity_unit_id") val quantityUnitId: String,
    @ColumnInfo(name = "unit_price_precise") val unitPricePrecise: String?,
    @ColumnInfo(name = "flat_fee_precise") val flatFeePrecise: String?,
    @ColumnInfo(name = "currency") val currency: String
)

/**
 * One usage report for one request attempt (I-12: [requestAttemptId]
 * carries the platform executionId verbatim until the Stage-3
 * provider_request_attempt table exists). Revised reports supersede;
 * nothing is ever overwritten (trigger-enforced).
 */
@Entity(
    tableName = "usage_record",
    indices = [
        Index(
            value = ["request_attempt_id", "report_source", "report_version"],
            unique = true,
            name = "ux_usage_record_attempt_source_version"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = UsageRecordEntity::class,
            parentColumns = ["usage_record_id"],
            childColumns = ["supersedes_usage_record_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class UsageRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String,
    /** Deferred FK (Stage-3 provider_request_attempt) — carries executionId. */
    @ColumnInfo(name = "request_attempt_id") val requestAttemptId: String,
    @ColumnInfo(name = "report_source") val reportSource: String,
    @ColumnInfo(name = "report_version") val reportVersion: String,
    @ColumnInfo(name = "reported_at_epoch_ms") val reportedAtEpochMs: Long?,
    @ColumnInfo(name = "retrieved_at_epoch_ms") val retrievedAtEpochMs: Long?,
    @ColumnInfo(name = "report_status") val reportStatus: String,
    @ColumnInfo(name = "is_final_report", defaultValue = "0") val isFinalReport: Int,
    @ColumnInfo(name = "supersedes_usage_record_id") val supersedesUsageRecordId: String?,
    @ColumnInfo(name = "provider_request_id") val providerRequestId: String?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long
)

/**
 * A measured value for one metric — UNKNOWN is an explicit state with a
 * NULL value, never a zero (platform money-truth discipline).
 */
@Entity(
    tableName = "usage_measurement",
    indices = [
        Index(value = ["usage_record_id", "metric_id"], name = "ix_usage_measurement_record")
    ],
    foreignKeys = [
        ForeignKey(
            entity = UsageRecordEntity::class,
            parentColumns = ["usage_record_id"],
            childColumns = ["usage_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["metric_id"],
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
data class UsageMeasurementEntity(
    @PrimaryKey
    @ColumnInfo(name = "measurement_id") val measurementId: String,
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String,
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "value_precise") val valuePrecise: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "source_authority") val sourceAuthority: String,
    @ColumnInfo(name = "source_field") val sourceField: String?,
    @ColumnInfo(name = "measurement_status") val measurementStatus: String,
    @ColumnInfo(name = "knowledge_status") val knowledgeStatus: String,
    @ColumnInfo(name = "unknown_reason_code") val unknownReasonCode: String?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long
)

/**
 * An itemized, auditable cost computation (roadmap Phase-2 exit
 * criterion). Line-by-line with exact pre-rounded decimals and a rounded
 * micro integer per line; the total is the sum of rounded lines
 * (ROUND_LINE_THEN_SUM, G08 v1).
 */
@Entity(
    tableName = "cost_calculation",
    indices = [
        Index(value = ["request_attempt_id", "calculated_at_epoch_ms"], name = "ix_cost_calculation_attempt"),
        Index(
            value = ["calculation_scope_id", "period_start_epoch_ms", "period_end_epoch_ms"],
            name = "ix_cost_calculation_scope_period"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = UsageRecordEntity::class,
            parentColumns = ["usage_record_id"],
            childColumns = ["usage_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["calculation_scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PricingProfileVersionEntity::class,
            parentColumns = ["pricing_profile_version_id"],
            childColumns = ["pricing_profile_version_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CostCalculationEntity::class,
            parentColumns = ["cost_calculation_id"],
            childColumns = ["supersedes_calculation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CostCalculationEntity(
    @PrimaryKey
    @ColumnInfo(name = "cost_calculation_id") val costCalculationId: String,
    @ColumnInfo(name = "calculation_level") val calculationLevel: String,
    /** Deferred FK (Stage-3) — carries executionId (I-12). */
    @ColumnInfo(name = "request_attempt_id") val requestAttemptId: String?,
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String?,
    @ColumnInfo(name = "calculation_scope_id") val calculationScopeId: String?,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long?,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long?,
    @ColumnInfo(name = "pricing_profile_version_id") val pricingProfileVersionId: String,
    @ColumnInfo(name = "calculation_engine_version") val calculationEngineVersion: String,
    @ColumnInfo(name = "rule_set_version") val ruleSetVersion: String,
    @ColumnInfo(name = "input_hash") val inputHash: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "total_amount_micro_units") val totalAmountMicroUnits: Long?,
    @ColumnInfo(name = "calculation_status") val calculationStatus: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "calculated_at_epoch_ms") val calculatedAtEpochMs: Long,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
    @ColumnInfo(name = "supersedes_calculation_id") val supersedesCalculationId: String?
)

@Entity(
    tableName = "cost_calculation_input",
    indices = [
        Index(value = ["cost_calculation_id"], name = "ix_cost_calculation_input_parent")
    ],
    foreignKeys = [
        ForeignKey(
            entity = CostCalculationEntity::class,
            parentColumns = ["cost_calculation_id"],
            childColumns = ["cost_calculation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UsageMeasurementEntity::class,
            parentColumns = ["measurement_id"],
            childColumns = ["usage_measurement_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UsageRecordEntity::class,
            parentColumns = ["usage_record_id"],
            childColumns = ["usage_record_id"],
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
data class CostCalculationInputEntity(
    @PrimaryKey
    @ColumnInfo(name = "input_id") val inputId: String,
    @ColumnInfo(name = "cost_calculation_id") val costCalculationId: String,
    @ColumnInfo(name = "input_role") val inputRole: String,
    @ColumnInfo(name = "usage_measurement_id") val usageMeasurementId: String?,
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String?,
    /** Deferred FK (Stage-3) — carries executionId (I-12). */
    @ColumnInfo(name = "request_attempt_id") val requestAttemptId: String?,
    @ColumnInfo(name = "quantity_precise") val quantityPrecise: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "included_at_epoch_ms") val includedAtEpochMs: Long,
    @ColumnInfo(name = "source_authority") val sourceAuthority: String
)

@Entity(
    tableName = "cost_calculation_line",
    indices = [
        Index(value = ["cost_calculation_id", "line_order"], unique = true, name = "ux_cost_calculation_line_order")
    ],
    foreignKeys = [
        ForeignKey(
            entity = CostCalculationEntity::class,
            parentColumns = ["cost_calculation_id"],
            childColumns = ["cost_calculation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PricingComponentEntity::class,
            parentColumns = ["pricing_component_id"],
            childColumns = ["pricing_component_id"],
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
            childColumns = ["basis_unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CostCalculationLineEntity(
    @PrimaryKey
    @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "cost_calculation_id") val costCalculationId: String,
    @ColumnInfo(name = "pricing_component_id") val pricingComponentId: String?,
    @ColumnInfo(name = "line_order") val lineOrder: Int,
    @ColumnInfo(name = "line_basis_type") val lineBasisType: String,
    @ColumnInfo(name = "usage_measurement_id") val usageMeasurementId: String?,
    @ColumnInfo(name = "basis_quantity_precise") val basisQuantityPrecise: String?,
    @ColumnInfo(name = "basis_unit_id") val basisUnitId: String?,
    @ColumnInfo(name = "base_amount_micro_units") val baseAmountMicroUnits: Long?,
    @ColumnInfo(name = "rate_value_precise") val rateValuePrecise: String?,
    @ColumnInfo(name = "unit_price_precise") val unitPricePrecise: String?,
    @ColumnInfo(name = "pre_rounded_amount_precise") val preRoundedAmountPrecise: String?,
    @ColumnInfo(name = "final_amount_micro_units") val finalAmountMicroUnits: Long?,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "rounding_mode") val roundingMode: String,
    @ColumnInfo(name = "line_status") val lineStatus: String,
    @ColumnInfo(name = "notes") val notes: String?
)

@Entity(
    tableName = "cost_calculation_line_input",
    indices = [
        Index(value = ["line_id"], name = "ix_cost_line_input_parent")
    ],
    foreignKeys = [
        ForeignKey(
            entity = CostCalculationLineEntity::class,
            parentColumns = ["line_id"],
            childColumns = ["line_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UsageMeasurementEntity::class,
            parentColumns = ["measurement_id"],
            childColumns = ["usage_measurement_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CostCalculationInputEntity::class,
            parentColumns = ["input_id"],
            childColumns = ["calculation_input_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CostCalculationLineEntity::class,
            parentColumns = ["line_id"],
            childColumns = ["cost_calculation_line_id"],
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
data class CostCalculationLineInputEntity(
    @PrimaryKey
    @ColumnInfo(name = "line_input_id") val lineInputId: String,
    @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "input_role") val inputRole: String,
    @ColumnInfo(name = "usage_measurement_id") val usageMeasurementId: String?,
    @ColumnInfo(name = "calculation_input_id") val calculationInputId: String?,
    @ColumnInfo(name = "cost_calculation_line_id") val costCalculationLineId: String?,
    @ColumnInfo(name = "constant_value_precise") val constantValuePrecise: String?,
    @ColumnInfo(name = "quantity_precise") val quantityPrecise: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "notes") val notes: String?
)
