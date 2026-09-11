package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Durable Human Approval Request (v15 — audit 2026 §17 fix)
 * ============================================================================
 *
 * P1-15 / §17 (audit 2026 — "Human approval is not durable"): the approval
 * gate previously used [com.example.infrastructure.governed.InMemoryHumanApprovalStore],
 * so approvals did NOT survive process death. Losing a PENDING approval is
 * fail-safe (the tool re-requests), but an APPROVED one-shot token dying
 * with the process forced re-approval mid-workflow and made approval state
 * un-auditable across restarts.
 *
 * This table persists the FULL approval lifecycle: request → pending →
 * resolved (APPROVED/DENIED/EXPIRED) + one-shot token consumption. The
 * one-shot token semantics are enforced by `isTokenConsumed` — a consumed
 * token can never authorize a second admission, even after a restart.
 */
@Entity(
    tableName = "human_approval_requests",
    indices = [
        Index("executionId"),
        Index("toolName"),
        Index("resolution"),
        Index("expiresAtEpochMs")
    ]
)
data class HumanApprovalRequestEntity(
    @PrimaryKey
    val approvalId: String,
    val executionId: String,
    val toolName: String,
    val riskLevel: String,
    val prompt: String,
    val justification: String,
    val requestedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /** PENDING, APPROVED, DENIED, EXPIRED (ApprovalResolution names). */
    val resolution: String,
    val resolvedBy: String?,
    val resolvedAtEpochMs: Long?,
    /** One-shot token semantics: TRUE once the approval authorized one admission.
     * GAP-01 (Design Closure 2026): mirrors the table's v15 creation DDL
     * (MIGRATION_14_TO_15: isTokenConsumed INTEGER NOT NULL DEFAULT 0). */
    @ColumnInfo(defaultValue = "0")
    val isTokenConsumed: Boolean = false
)
