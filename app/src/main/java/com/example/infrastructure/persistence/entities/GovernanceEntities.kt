package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Governance Phase entities — Capability Radar + Economic Budget
 * (Room v10, MIGRATION_9_TO_10 — purely additive)
 * ============================================================================
 */

/**
 * TRACK A — append-only capability evidence store. Every row is a real
 * observation from a real emission site (execution bus, control plane,
 * budget governance, health checks). No fabricated rows are ever inserted.
 */
@Entity(
    tableName = "capability_evidence",
    indices = [
        Index(value = ["capabilityKey", "timestampEpochMs"]),
        Index(value = ["workspaceId"]),
        Index(value = ["executionId"])
    ]
)
data class CapabilityEvidenceEntity(
    @PrimaryKey
    val id: String,
    val capabilityKey: String,
    val source: String,          // EvidenceSource.name
    val outcome: String,         // EvidenceOutcome.name
    val confidence: Float,
    val providerId: String?,
    val serviceId: String?,
    val modelId: String?,
    val resourceId: String?,
    val executionId: String?,
    val workspaceId: String?,
    val agentId: String?,
    val detail: String?,
    val timestampEpochMs: Long
)

/**
 * TRACK A — last derived radar state per (capability, workspace). Upserted
 * on every re-derivation; the full history lives in capability_changes.
 *
 * `workspaceId` uses the [GLOBAL_SCOPE_KEY] sentinel ("__global__") for the
 * global scope because Room requires composite primary keys to be NOT NULL;
 * the domain model keeps String? and the store maps null ↔ sentinel.
 */
@Entity(
    tableName = "radar_capability_states",
    primaryKeys = ["capabilityKey", "workspaceId"],
    indices = [
        Index(value = ["workspaceId"]),
        Index(value = ["state"])
    ]
)
data class RadarCapabilityStateEntity(
    val capabilityKey: String,
    /** Workspace id or [GLOBAL_SCOPE_KEY] sentinel — never null (Room PK). */
    val workspaceId: String,
    val state: String,           // OperationalCapabilityState.name
    val dimensionsJson: String,  // CapabilityEvaluationDimensions as JSON
    val health: String,          // CapabilityHealth.name
    val trend: String,           // CapabilityTrend.name
    val evidenceCount: Int,
    val lastEvidenceEpochMs: Long?,
    val contributingResourceIdsJson: String,
    val rationale: String,
    val derivedAtEpochMs: Long
) {
    companion object {
        const val GLOBAL_SCOPE_KEY = "__global__"
    }
}

/**
 * TRACK A — capability change log (evolution detection). Append-only.
 */
@Entity(
    tableName = "capability_changes",
    indices = [
        Index(value = ["workspaceId", "detectedAtEpochMs"]),
        Index(value = ["capabilityKey"])
    ]
)
data class CapabilityChangeEntity(
    @PrimaryKey
    val id: String,
    val capabilityKey: String,
    val workspaceId: String?,
    val fromState: String,
    val toState: String,
    val changeType: String,      // CapabilityChangeType.name
    val evidenceId: String?,
    val detail: String,
    val detectedAtEpochMs: Long
)

/**
 * TRACK A — evidence-backed recommendations produced by the radar rules.
 */
@Entity(
    tableName = "radar_recommendations",
    indices = [
        Index(value = ["workspaceId", "isDismissed"]),
        Index(value = ["capabilityKey"])
    ]
)
data class RadarRecommendationEntity(
    @PrimaryKey
    val id: String,
    val capabilityKey: String,
    val workspaceId: String?,
    val type: String,            // RadarRecommendationType.name
    val priority: String,        // RecommendationPriority.name
    val message: String,
    val actionHint: String?,
    val supportingEvidenceIdsJson: String,
    val createdAtEpochMs: Long,
    val isDismissed: Boolean
)

/**
 * TRACK B — pricing entries. Id is deterministic from
 * (scope, provider, service, model, version) so re-imports REPLACE rather
 * than duplicate. Effective-time windows are honored at resolution time.
 */
@Entity(
    tableName = "pricing_entries",
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["scopeType", "providerId", "serviceId", "modelId"]),
        Index(value = ["effectiveFromEpochMs"])
    ]
)
data class PricingEntryEntity(
    @PrimaryKey
    val id: String,
    val scopeType: String,       // PricingScope.name
    val providerId: String,
    val serviceId: String?,
    val modelId: String?,
    /** Micro currency units per million input tokens; null = unpublished. */
    val inputPriceMicroPerMillion: Long?,
    val outputPriceMicroPerMillion: Long?,
    val cachedInputPriceMicroPerMillion: Long?,
    val currency: String,
    val billingClass: String,    // BillingClass.name
    val pricingVersion: String,
    val effectiveFromEpochMs: Long,
    val effectiveToEpochMs: Long?,
    val provenance: String,
    val createdAtEpochMs: Long
)

/**
 * TRACK B — the cost ledger. Append-only usage/cost accounting with full
 * attribution. Estimated and actual costs are distinguished; unknown cost
 * is stored as null amount (never 0).
 */
@Entity(
    tableName = "cost_ledger_entries",
    indices = [
        Index(value = ["workspaceId", "timestampEpochMs"]),
        Index(value = ["executionId"]),
        Index(value = ["providerId"]),
        Index(value = ["taskId"]),
        Index(value = ["agentId"])
    ]
)
data class CostLedgerEntryEntity(
    @PrimaryKey
    val id: String,
    val executionId: String,
    val taskId: String?,
    val workspaceId: String?,
    val agentId: String?,
    val providerId: String?,
    val serviceId: String?,
    val modelId: String?,
    val resourceId: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int,
    val totalTokens: Int,
    val isEstimate: Boolean,
    /** Snapshot of the unit pricing applied (micro units per million). */
    val appliedInputPriceMicroPerMillion: Long?,
    val appliedOutputPriceMicroPerMillion: Long?,
    val appliedCachedInputPriceMicroPerMillion: Long?,
    val appliedPricingVersion: String?,
    /** Cost in micro currency units; NULL = unknown (never fabricated). */
    val costAmountMicro: Long?,
    val currency: String,
    val costStatus: String,      // CostStatus.name
    val billingClass: String,    // BillingClass.name
    val timestampEpochMs: Long
)

/**
 * TRACK B — monetary budget allocations per scope, with enforceable policy.
 * Token QUOTAS are NOT stored here (they stay on tasks/agents); this table
 * is spending authority only.
 */
@Entity(
    tableName = "budget_allocations",
    primaryKeys = ["scopeType", "scopeId"],
    indices = [Index(value = ["scopeType"])]
)
data class BudgetAllocationEntity(
    val scopeType: String,       // BudgetScopeType.name
    val scopeId: String,
    /** Allocated amount in micro currency units. */
    val allocatedAmountMicro: Long,
    val currency: String,
    /** Ordered CSV of BudgetPolicyAction names — precedence = order. */
    val policyActionsCsv: String,
    val warnThresholdRatio: Float,
    val policyNote: String?,
    val isActive: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
