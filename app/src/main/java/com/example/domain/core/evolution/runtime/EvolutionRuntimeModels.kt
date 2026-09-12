package com.example.domain.core.evolution.runtime

/**
 * ============================================================================
 * Evolution / Self-Improvement Domain Models — Phase 5 (P1)
 * ============================================================================
 *
 * GAP-08 (Design Closure 2026, ADR-7): the dead policy-versioning tail
 * (PolicyVersion / PromotionDecision / RollbackResult + the
 * policy_versions table + PolicyVersionService) was DELETED — it was
 * built, wired and never consumed. PolicyKind and
 * PolicyEvaluationReport survive: DecisionIntelligenceService uses them
 * as its live evaluation-contract types.
 */

enum class PolicyKind(val code: String) {
    CBR_MDP_Q_TABLE("CBR_MDP_Q_TABLE"),
    ROUTING("ROUTING"),
    AGENT_SELECTION("AGENT_SELECTION"),
    TOOL_SELECTION("TOOL_SELECTION")
}

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

