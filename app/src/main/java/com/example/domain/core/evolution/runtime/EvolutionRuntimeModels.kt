package com.example.domain.core.evolution.runtime

/**
 * ============================================================================
 * Evolution / Self-Improvement Domain Models — Phase 5 (P1)
 * ============================================================================
 *
 * Closes the Evolution/Self-Improvement gap (audit: 25–35% → ~45%) by
 * adding policy versioning, offline replay, regression detection, and
 * safe promotion — none of which existed before (the audit found no
 * `PolicyVersion` entity, no replay tool, no comparison between current
 * and previous policy performance, no staging/canary mechanism, no
 * rollback).
 */

enum class PolicyKind(val code: String) {
    CBR_MDP_Q_TABLE("CBR_MDP_Q_TABLE"),
    ROUTING("ROUTING"),
    AGENT_SELECTION("AGENT_SELECTION"),
    TOOL_SELECTION("TOOL_SELECTION")
}

data class PolicyVersion(
    val versionId: String,
    val kind: PolicyKind,
    val versionLabel: String,
    val snapshotJson: String,
    val evaluationReportJson: String? = null,
    val isPromoted: Boolean = false,
    val promotedBy: String = "",
    val promotedAtEpochMs: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val parentVersionId: String? = null
)

data class PolicyEvaluationReport(
    val versionId: String,
    val taskSuiteSize: Int,
    val successCount: Int,
    val degradedCount: Int,
    val failureCount: Int,
    val averageReward: Float,
    val p95LatencyMs: Long,
    val totalTokensConsumed: Long,
    val regressionDetected: Boolean,
    val regressionScore: Float, // negative = regression, positive = improvement
    val notes: String
)

data class PromotionDecision(
    val versionId: String,
    val isApproved: Boolean,
    val reason: String,
    val conditions: List<String>
)

data class RollbackResult(
    val fromVersionId: String,
    val toVersionId: String,
    val isSuccessful: Boolean,
    val reason: String
)
