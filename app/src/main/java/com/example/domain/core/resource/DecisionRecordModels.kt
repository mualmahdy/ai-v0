package com.example.domain.core.resource

/**
 * P0.4 — Governance States (APPROVED-BASELINE v2.1, Section H — LOCKED, Review
 * Point 6).
 *
 * RULE GOV-1: [GovernanceResult.state] is never null. "No governance" =
 * [GovernanceState.NOT_APPLICABLE].
 * RULE GOV-2: "Resource enabled" != "User authorized". If authorization checks
 * are not yet implemented in P0 the system MUST explicitly emit NOT_APPLICABLE
 * (honest limitation) rather than pretending enablement = authorization.
 * RULE GOV-3: Execution is permitted when securityResult permits AND
 * governanceResult.state in {ALLOWED, NOT_APPLICABLE}.
 */
enum class GovernanceState {
    NOT_APPLICABLE,
    ALLOWED,
    BLOCKED,
    REQUIRES_APPROVAL
}

/** Explicit, auditable governance evaluation attached to every DecisionRecord. */
data class GovernanceResult(
    val state: GovernanceState,
    /** Identifier of the policy that decided, if any. */
    val policyId: String?,
    val reason: String
) {
    companion object {
        /** Honest P0 default: no governance policy applies (RULE GOV-1 / GOV-2). */
        val NOT_APPLICABLE: GovernanceResult = GovernanceResult(
            state = GovernanceState.NOT_APPLICABLE,
            policyId = null,
            reason = "No governance policy is implemented for this resource/decision class in P0."
        )
    }
}

/**
 * Explicit security evaluation attached to every DecisionRecord (Section F).
 * Mirrors the existing SecurityGuardService evaluation in a locked, non-null shape.
 */
data class SecurityResult(
    val permitted: Boolean,
    val ruleId: String?,
    val reason: String
) {
    companion object {
        fun permitted(reason: String): SecurityResult =
            SecurityResult(permitted = true, ruleId = null, reason = reason)

        fun denied(ruleId: String, reason: String): SecurityResult =
            SecurityResult(permitted = false, ruleId = ruleId, reason = reason)
    }
}

/**
 * P0.4 — FallbackPolicy (APPROVED-BASELINE v2.1, Section F — LOCKED, Review Point 4).
 *
 * RULE FB-1 (CRITICAL): fallbackPolicy is a PLANNING HINT for the next
 * DecisionRecord, never an authorization for the current execution to substitute
 * resources. Execution either uses selectedResourceId or fails/replans.
 */
sealed interface FallbackPolicy {
    /** Fail the step; surface error to task/step level. */
    data object Fail : FallbackPolicy

    /** Stop execution; trigger immediate re-decision with failure context. */
    data class Replan(val reason: String) : FallbackPolicy

    /** Re-decide with explicit hint of preferred alternative. */
    data class PreferAlternative(val candidateResourceIds: List<String>) : FallbackPolicy
}

/**
 * Evaluation of one candidate resource produced during decision scoring.
 * A DecisionRecord must contain at least the evaluation of the selected resource.
 */
data class CandidateEvaluation(
    val resourceId: ResourceId,
    val providerId: ProviderId,
    val serviceId: ServiceId,
    val capabilityFit: Double,
    val healthScore: Double,
    val estimatedLatencyMs: Long,
    val estimatedCost: Double,
    val finalScore: Double,
    val isSelected: Boolean,
    val rationale: String
)

/**
 * P0.4 — DecisionRecord (APPROVED-BASELINE v2.1, Section F — LOCKED).
 *
 * Exact selection is immutable for this decision. No fields beyond Section F are
 * permitted (Section M — Forbidden).
 */
data class DecisionRecord(
    val decisionId: String,
    val taskId: String,
    val stepId: String,
    val timestamp: Long,
    val decisionVersion: Int = 1,

    // EXACT SELECTION — immutable for this decision
    val selectedResourceId: ResourceId,
    val selectedProviderId: ProviderId,
    val selectedServiceId: ServiceId,
    val selectedConfigurationVersion: ConfigurationVersion,

    // OPTIONAL SELECTIONS
    val selectedAgentId: String? = null,
    val selectedToolIds: List<String> = emptyList(),

    // REQUIREMENTS
    val requiredCapabilities: Set<String>,

    // EVALUATION
    val candidateEvaluations: List<CandidateEvaluation>,
    val decisionRationale: String,
    val confidence: Double,

    // SECURITY & GOVERNANCE
    val securityResult: SecurityResult,
    val governanceResult: GovernanceResult,

    // RECOVERY POLICY — hint for replanning, NOT execution authority
    val fallbackPolicy: FallbackPolicy
)

/** RULE GOV-3 helper: execution permission evaluation for a DecisionRecord. */
fun DecisionRecord.executionPermitted(): Boolean =
    securityResult.permitted &&
        (governanceResult.state == GovernanceState.ALLOWED ||
            governanceResult.state == GovernanceState.NOT_APPLICABLE)
