package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * STAGE 5 ENTITIES — the derived-state projection + the full G01 scope
 * dimension tables (schema v28)
 * ============================================================================
 * The second increment of the roadmap's CONTINUOUS Phase 4 (طبقة الذكاء
 * والتعميق المستمر), from the documented deferral list
 * (stage4_implementation_record.deferred_to_next_increment):
 *
 *   1. current_resource_state + current_resource_state_source — the
 *      DERIVED half of Group D («إسقاط مشتق قابل لإعادة البناء؛ فهارس
 *      جزئية تمنع أكثر من حالة نشطة للموضوع/المقياس/النطاق»). The PDM's
 *      seven partial UNIQUE indices cannot be expressed by Room @Entity
 *      indices (no WHERE clause — the I-32 discipline), so the
 *      at-most-one-active-row-per-(subject, metric, scope) invariant is
 *      enforced by the trg_current_state_unique_active_{insert,update}
 *      trigger pair in [Stage5MigrationSql] (the v0.2 change-7 precedent:
 *      partial UNIQUE index -> trigger, Room compatibility).
 *
 *   2. scope_internal_dimension + scope_custom_dimension — the full G01
 *      dimension tables (open-vocabulary dimension codes with a
 *      KNOWN/UNKNOWN/NOT_APPLICABLE state and a KNOWN-shape CHECK — the
 *      scope_descriptor platform pair precedent, applied to open dimension
 *      namespaces).
 *
 * Adaptation set (identical to Stages 2/3/4 — approved PDM v0.2 rules):
 *   * TEXT timestamps -> INTEGER *_epoch_ms (v0.2 change 4);
 *   * table-level UNIQUE -> NAMED unique indices ux_* (Stage-0 د.2 rule 4);
 *   * no money columns exist in this domain (ADR-B1 has nothing to convert);
 *     current_numeric_value_precise stays TEXT precise-decimal (the
 *     stage-3 rate_precise precedent);
 *   * every referenced parent exists by v27, so every FK below is real.
 */
// ---------------------------------------------------------------------------
// Group D, derived half: the current-state projection.
// ---------------------------------------------------------------------------

@Entity(
    tableName = "current_resource_state",
    indices = [
        Index(value = ["metric_id", "is_active"], name = "ix_current_state_metric_active"),
        Index(value = ["scope_id", "is_active"], name = "ix_current_state_scope_active"),
        Index(value = ["state_as_of_epoch_ms"], name = "ix_current_state_as_of")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["subject_provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccessContextEntity::class,
            parentColumns = ["access_context_id"],
            childColumns = ["subject_access_context_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderScopeNodeEntity::class,
            parentColumns = ["scope_node_id"],
            childColumns = ["subject_provider_scope_node_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderModelRecordEntity::class,
            parentColumns = ["provider_model_record_id"],
            childColumns = ["subject_provider_model_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["model_id"],
            childColumns = ["subject_model_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelOfferingEntity::class,
            parentColumns = ["offering_id"],
            childColumns = ["subject_offering_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderRequestAttemptEntity::class,
            parentColumns = ["request_attempt_id"],
            childColumns = ["subject_request_attempt_id"],
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
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = FreshnessPolicyEntity::class,
            parentColumns = ["policy_id"],
            childColumns = ["derivation_policy_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CurrentResourceStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "state_id") val stateId: String,
    @ColumnInfo(name = "subject_provider_id") val subjectProviderId: String?,
    @ColumnInfo(name = "subject_access_context_id") val subjectAccessContextId: String?,
    @ColumnInfo(name = "subject_provider_scope_node_id") val subjectProviderScopeNodeId: String?,
    @ColumnInfo(name = "subject_provider_model_record_id") val subjectProviderModelRecordId: String?,
    @ColumnInfo(name = "subject_model_id") val subjectModelId: String?,
    @ColumnInfo(name = "subject_offering_id") val subjectOfferingId: String?,
    @ColumnInfo(name = "subject_request_attempt_id") val subjectRequestAttemptId: String?,
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "current_value_type") val currentValueType: String,
    @ColumnInfo(name = "current_numeric_value_precise") val currentNumericValuePrecise: String?,
    @ColumnInfo(name = "current_text_value") val currentTextValue: String?,
    @ColumnInfo(name = "current_boolean_value") val currentBooleanValue: Int?,
    @ColumnInfo(name = "current_state_value") val currentStateValue: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "scope_id") val scopeId: String?,
    /** The observation the value is as-of (the PRIMARY source's observed_at). */
    @ColumnInfo(name = "state_as_of_epoch_ms") val stateAsOfEpochMs: Long,
    @ColumnInfo(name = "freshness_state") val freshnessState: String,
    @ColumnInfo(name = "derivation_policy_id") val derivationPolicyId: String,
    @ColumnInfo(name = "state_status") val stateStatus: String,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Int,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

/**
 * The observations that contributed to a state row's derivation —
 * PRIMARY for the latest valid observation, SUPPORTING for the earlier
 * valid ones inside the evaluation window. Append-only by design (the
 * state rows they describe are superseded, never rewritten).
 */
@Entity(
    tableName = "current_resource_state_source",
    indices = [
        Index(value = ["state_id"], name = "ix_current_state_source_state"),
        Index(value = ["observation_id"], name = "ix_current_state_source_observation"),
        Index(
            value = ["state_id", "observation_id", "source_role"],
            unique = true,
            name = "ux_current_state_source_triple"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = CurrentResourceStateEntity::class,
            parentColumns = ["state_id"],
            childColumns = ["state_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ObservationEntity::class,
            parentColumns = ["observation_id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CurrentResourceStateSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "state_source_id") val stateSourceId: String,
    @ColumnInfo(name = "state_id") val stateId: String,
    @ColumnInfo(name = "observation_id") val observationId: String,
    /** PRIMARY / SUPPORTING / CONFLICTING / EXCLUDED (PDM vocabulary). */
    @ColumnInfo(name = "source_role") val sourceRole: String,
    @ColumnInfo(name = "included_at_epoch_ms") val includedAtEpochMs: Long
)

// ---------------------------------------------------------------------------
// The full G01 scope dimension tables (open dimension namespaces).
// ---------------------------------------------------------------------------

/**
 * An INTERNAL scope dimension — platform-reserved dimension codes with a
 * KNOWN/UNKNOWN/NOT_APPLICABLE state and a KNOWN-shape CHECK (a KNOWN
 * dimension carries a value + a value type; UNKNOWN/NOT_APPLICABLE carry
 * neither — the scope_descriptor platform pair precedent).
 */
@Entity(
    tableName = "scope_internal_dimension",
    indices = [
        Index(value = ["scope_id"], name = "ix_scope_internal_dimension_scope"),
        Index(
            value = ["scope_id", "dimension_code"],
            unique = true,
            name = "ux_scope_internal_dimension"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ScopeInternalDimensionEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope_dimension_id") val scopeDimensionId: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "dimension_code") val dimensionCode: String,
    @ColumnInfo(name = "dimension_state") val dimensionState: String,
    @ColumnInfo(name = "dimension_value") val dimensionValue: String?,
    @ColumnInfo(name = "value_type") val valueType: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

/**
 * A CUSTOM (caller-namespace) scope dimension — same shape as the internal
 * one, plus the namespace that partitions the dimension-code space.
 */
@Entity(
    tableName = "scope_custom_dimension",
    indices = [
        Index(value = ["scope_id"], name = "ix_scope_custom_dimension_scope"),
        Index(
            value = ["scope_id", "namespace", "dimension_code"],
            unique = true,
            name = "ux_scope_custom_dimension"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ScopeCustomDimensionEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope_dimension_id") val scopeDimensionId: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "namespace") val namespace: String,
    @ColumnInfo(name = "dimension_code") val dimensionCode: String,
    @ColumnInfo(name = "dimension_state") val dimensionState: String,
    @ColumnInfo(name = "dimension_value") val dimensionValue: String?,
    @ColumnInfo(name = "value_type") val valueType: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)
