package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * BUDGET CORE (Room v23, MIGRATION_22_TO_23 — PDM v0.2 Stage-1 subset)
 * ============================================================================
 * The accounting budget core as approved on 2026-10-14 ("أعتمد الكل",
 * decisions D-5/D-6/D-7): scope_descriptor + the five budget tables.
 *
 * Money is integer MICRO units (ADR-B1, 10^-6); timestamps are epoch
 * milliseconds (PDM v0.2 change #4); the ledger is append-only and is
 * protected at the DATABASE level by triggers created in the migration
 * (ADR-B4) — Room never validates triggers, so each one carries an explicit
 * acceptance test.
 *
 * Room adaptation rules applied (Stage-0 Section د.2):
 *  - CHECK constraints live in the migration DDL only. Room's TableInfo
 *    comparison ignores CHECKs, so they stay enforced by SQLite at runtime
 *    and each has an acceptance test instead.
 *  - UNIQUE table constraints from the approved DDL are declared as NAMED
 *    UNIQUE indices so the entity declarations and the migration DDL
 *    produce byte-identical schemas (a table-level UNIQUE constraint would
 *    be invisible to Room's index comparison and fail validation).
 *  - Deferred-FK columns (provider/integration/access_context/scope_node/
 *    offering/request_attempt, request_operation, conversion_rule,
 *    cost_calculation_line, usage_record, reconciliation_record — Stage-2/3
 *    tables) are plain nullable columns with NO @ForeignKey until their
 *    parent tables exist (PDM v0.2 change #8).
 *  - String DEFAULTs are declared as SQL literals in quotes
 *    (`defaultValue = "'UNKNOWN'"`) because Room uses the annotation value
 *    verbatim in CREATE TABLE and compares it verbatim at validation.
 */
@Entity(
    tableName = "scope_descriptor",
    indices = [
        Index(
            value = ["scope_kind", "canonicalization_version", "canonical_hash"],
            unique = true,
            name = "ux_scope_descriptor_hash"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = BudgetPolicyEntity::class,
            parentColumns = ["budget_policy_id"],
            childColumns = ["budget_policy_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BudgetPeriodEntity::class,
            parentColumns = ["budget_period_id"],
            childColumns = ["budget_period_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ScopeDescriptorEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "canonical_representation") val canonicalRepresentation: String,
    @ColumnInfo(name = "canonical_hash") val canonicalHash: String,
    @ColumnInfo(name = "hash_algorithm") val hashAlgorithm: String,
    @ColumnInfo(name = "canonicalization_version") val canonicalizationVersion: Int,
    @ColumnInfo(name = "provider_dimension_state", defaultValue = "'UNKNOWN'")
    val providerDimensionState: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "integration_dimension_state", defaultValue = "'UNKNOWN'")
    val integrationDimensionState: String,
    @ColumnInfo(name = "integration_id") val integrationId: String?,
    @ColumnInfo(name = "access_context_dimension_state", defaultValue = "'UNKNOWN'")
    val accessContextDimensionState: String,
    @ColumnInfo(name = "access_context_id") val accessContextId: String?,
    @ColumnInfo(name = "provider_scope_node_dimension_state", defaultValue = "'UNKNOWN'")
    val providerScopeNodeDimensionState: String,
    @ColumnInfo(name = "provider_scope_node_id") val providerScopeNodeId: String?,
    @ColumnInfo(name = "offering_dimension_state", defaultValue = "'UNKNOWN'")
    val offeringDimensionState: String,
    @ColumnInfo(name = "offering_id") val offeringId: String?,
    @ColumnInfo(name = "request_attempt_dimension_state", defaultValue = "'UNKNOWN'")
    val requestAttemptDimensionState: String,
    @ColumnInfo(name = "request_attempt_id") val requestAttemptId: String?,
    @ColumnInfo(name = "budget_policy_dimension_state", defaultValue = "'UNKNOWN'")
    val budgetPolicyDimensionState: String,
    @ColumnInfo(name = "budget_policy_id") val budgetPolicyId: String?,
    @ColumnInfo(name = "budget_period_dimension_state", defaultValue = "'UNKNOWN'")
    val budgetPeriodDimensionState: String,
    @ColumnInfo(name = "budget_period_id") val budgetPeriodId: String?,
    @ColumnInfo(name = "region_dimension_state", defaultValue = "'UNKNOWN'")
    val regionDimensionState: String,
    @ColumnInfo(name = "region") val region: String?,
    @ColumnInfo(name = "endpoint_dimension_state", defaultValue = "'UNKNOWN'")
    val endpointDimensionState: String,
    @ColumnInfo(name = "endpoint_ref") val endpointRef: String?,
    @ColumnInfo(name = "period_dimension_state", defaultValue = "'UNKNOWN'")
    val periodDimensionState: String,
    @ColumnInfo(name = "period_start") val periodStart: String?,
    @ColumnInfo(name = "period_end") val periodEnd: String?,
    /** v0.2 / ADR-B3: platform-side dimension pair for AGENT/TASK/
     *  EXECUTION/STEP/WORKSPACE scopes that exist in the engine today. */
    @ColumnInfo(name = "platform_dimension_state", defaultValue = "'UNKNOWN'")
    val platformDimensionState: String,
    @ColumnInfo(name = "platform_object_type") val platformObjectType: String?,
    @ColumnInfo(name = "platform_object_id") val platformObjectId: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

/**
 * Budget policy — row-per-version (PDM-G05, closed 2026-10-14): every
 * material edit inserts a NEW row under the same [policyFamilyId] with
 * [policyVersion] + 1; published versions are frozen images. The budget
 * scope types include the four engine-side values added by ADR-B3.
 */
@Entity(
    tableName = "budget_policy",
    indices = [
        Index(
            value = ["scope_id", "policy_status", "effective_from_epoch_ms"],
            name = "ix_budget_policy_scope"
        ),
        Index(
            value = ["policy_family_id", "policy_version"],
            unique = true,
            name = "ux_budget_policy_family_version"
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
data class BudgetPolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "budget_policy_id") val budgetPolicyId: String,
    @ColumnInfo(name = "policy_family_id") val policyFamilyId: String,
    @ColumnInfo(name = "budget_name") val budgetName: String,
    @ColumnInfo(name = "budget_scope_type") val budgetScopeType: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "default_limit_micro_units") val defaultLimitMicroUnits: Long,
    @ColumnInfo(name = "period_type") val periodType: String,
    @ColumnInfo(name = "rollover_policy") val rolloverPolicy: String,
    @ColumnInfo(name = "overage_policy") val overagePolicy: String,
    @ColumnInfo(name = "allocation_policy") val allocationPolicy: String,
    @ColumnInfo(name = "policy_version") val policyVersion: Int,
    @ColumnInfo(name = "effective_from_epoch_ms") val effectiveFromEpochMs: Long,
    @ColumnInfo(name = "policy_status") val policyStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

/**
 * Budget period — a concrete window with a concrete limit, pointing at the
 * exact policy version that governs it. The limit is frozen by trigger
 * `trg_budget_period_limit_immutable_after_posting` once any POSTED ledger
 * entry exists for the period.
 */
@Entity(
    tableName = "budget_period",
    indices = [
        Index(
            value = ["budget_policy_id", "period_start_epoch_ms", "period_end_epoch_ms"],
            unique = true,
            name = "ux_budget_period_policy_window"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = BudgetPolicyEntity::class,
            parentColumns = ["budget_policy_id"],
            childColumns = ["budget_policy_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BudgetPeriodEntity(
    @PrimaryKey
    @ColumnInfo(name = "budget_period_id") val budgetPeriodId: String,
    @ColumnInfo(name = "budget_policy_id") val budgetPolicyId: String,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long,
    @ColumnInfo(name = "limit_micro_units") val limitMicroUnits: Long,
    @ColumnInfo(name = "period_status") val periodStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

/**
 * Budget hold — a pre-execution reservation. Holds never exceed the
 * available balance (single-writer serialization inside the reserve
 * transaction, risk ز-1). Status transitions are one-way:
 * ACTIVE → PARTIALLY_SETTLED → SETTLED, or ACTIVE/PARTIALLY_SETTLED →
 * RELEASED, or → EXPIRED by the lazy-evaluated expiry rule.
 */
@Entity(
    tableName = "budget_hold",
    indices = [
        Index(
            value = ["budget_period_id", "idempotency_key"],
            unique = true,
            name = "ux_budget_hold_idempotency"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = BudgetPeriodEntity::class,
            parentColumns = ["budget_period_id"],
            childColumns = ["budget_period_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BudgetHoldEntity(
    @PrimaryKey
    @ColumnInfo(name = "hold_id") val holdId: String,
    @ColumnInfo(name = "budget_period_id") val budgetPeriodId: String,
    @ColumnInfo(name = "hold_amount_micro_units") val holdAmountMicroUnits: Long,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "reason") val reason: String?,
    /** Deferred FK: request_operation table arrives in Stage 2. */
    @ColumnInfo(name = "request_operation_id") val requestOperationId: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "expires_at_epoch_ms") val expiresAtEpochMs: Long?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String
)

/**
 * Budget ledger entry — THE financial record. Append-only: there is no
 * UPDATE or DELETE DAO path, and triggers
 * `trg_budget_ledger_no_update_posted` / `trg_budget_ledger_no_delete`
 * enforce it at the database level (ADR-B4). Corrections happen through
 * REVERSAL entries referencing [reversalOfEntryId] with the exact opposite
 * effect. Sign convention (Section و.2): consumption is negative, refunds
 * positive, adjustments carry their declared sign, reversal is the exact
 * opposite of the original.
 */
@Entity(
    tableName = "budget_ledger_entry",
    indices = [
        Index(
            value = ["budget_period_id", "posting_state", "entry_timestamp_epoch_ms"],
            name = "ix_budget_ledger_period_posted"
        ),
        Index(
            value = ["budget_period_id", "idempotency_key"],
            unique = true,
            name = "ux_budget_ledger_entry_idempotency"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = BudgetPeriodEntity::class,
            parentColumns = ["budget_period_id"],
            childColumns = ["budget_period_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BudgetHoldEntity::class,
            parentColumns = ["hold_id"],
            childColumns = ["hold_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BudgetLedgerEntryEntity::class,
            parentColumns = ["entry_id"],
            childColumns = ["reversal_of_entry_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BudgetLedgerEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "budget_period_id") val budgetPeriodId: String,
    @ColumnInfo(name = "entry_type") val entryType: String,
    @ColumnInfo(name = "posting_state") val postingState: String,
    @ColumnInfo(name = "available_effect_micro_units") val availableEffectMicroUnits: Long,
    /** v0.2 change #5: ESTIMATE / ACTUAL / UNKNOWN — honest basis, never
     *  silently coerced (UNKNOWN cost is not zero). */
    @ColumnInfo(name = "entry_basis") val entryBasis: String,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "original_amount_precise") val originalAmountPrecise: String?,
    @ColumnInfo(name = "original_currency") val originalCurrency: String?,
    /** Deferred FK: currency_conversion_rule (Stage 3). */
    @ColumnInfo(name = "conversion_rule_id") val conversionRuleId: String?,
    @ColumnInfo(name = "entry_timestamp_epoch_ms") val entryTimestampEpochMs: Long,
    @ColumnInfo(name = "posted_at_epoch_ms") val postedAtEpochMs: Long?,
    /** Deferred FKs: Stage 2/3 tables. */
    @ColumnInfo(name = "cost_calculation_line_id") val costCalculationLineId: String?,
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String?,
    @ColumnInfo(name = "reconciliation_record_id") val reconciliationRecordId: String?,
    @ColumnInfo(name = "hold_id") val holdId: String?,
    @ColumnInfo(name = "reversal_of_entry_id") val reversalOfEntryId: String?,
    @ColumnInfo(name = "allocation_group_id") val allocationGroupId: String?,
    @ColumnInfo(name = "allocation_role") val allocationRole: String?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String
)

/**
 * Budget hold settlement — SETTLE (against a posted debit entry of the
 * same amount and currency, enforced by trigger) or RELEASE (against the
 * hold only, NEVER producing a ledger entry — rule G06: releasing a hold
 * is not a positive financial effect).
 */
@Entity(
    tableName = "budget_hold_settlement",
    indices = [
        Index(
            value = ["hold_id", "idempotency_key"],
            unique = true,
            name = "ux_budget_hold_settlement_idempotency"
        ),
        Index(
            value = ["ledger_entry_id"],
            unique = true,
            name = "ux_budget_hold_settlement_entry"
        )
    ],
    foreignKeys = [
        ForeignKey(
            entity = BudgetHoldEntity::class,
            parentColumns = ["hold_id"],
            childColumns = ["hold_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BudgetLedgerEntryEntity::class,
            parentColumns = ["entry_id"],
            childColumns = ["ledger_entry_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BudgetHoldSettlementEntity(
    @PrimaryKey
    @ColumnInfo(name = "settlement_id") val settlementId: String,
    @ColumnInfo(name = "hold_id") val holdId: String,
    @ColumnInfo(name = "settlement_type") val settlementType: String,
    @ColumnInfo(name = "ledger_entry_id") val ledgerEntryId: String?,
    @ColumnInfo(name = "amount_micro_units") val amountMicroUnits: Long,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String
)
