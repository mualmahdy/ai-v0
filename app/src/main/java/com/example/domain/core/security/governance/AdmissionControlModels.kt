package com.example.domain.core.security.governance

import com.example.domain.core.security.RiskLevel
import com.example.domain.core.security.SecurityDecision

/**
 * ============================================================================
 * Admission Control Domain Models — Phase 1 (Governed Intelligent Execution)
 * ============================================================================
 *
 * Models the ORDERED admission pipeline every tool request MUST traverse
 * before any execution is allowed:
 *
 *   Tool Request
 *     -> 1. PARAMETER_VALIDATION        (schema check; model output is untrusted)
 *     -> 2. PRINCIPAL_AUTHORIZATION     (principal X permission on tool Y)
 *     -> 3. RISK_CLASSIFICATION         (tool + argument risk classification)
 *     -> 4. SECURITY_POLICY             (SecurityGuard evaluation; ceiling)
 *     -> 5. WORKSPACE_SCOPE_VALIDATION  (workspace-bound tools need a scope)
 *     -> 6. PATH_POLICY                 (containment + forbidden patterns)
 *     -> 7. BUDGET_AUTHORIZATION        (economic gate; never silently zero)
 *     -> 8. RATE_LIMIT                  (RPM/TPM + 429 backoff)
 *     -> 9. HUMAN_APPROVAL              (explicit consent when required)
 *     -> 10. SANDBOX_ADMISSION          (sandbox lifecycle + limits)
 *     -> EXECUTION (only via an admitted request)
 *     -> AUDIT (every decision recorded, deny or allow)
 *
 * Design rules enforced by this model:
 *  - The pipeline is the ONLY path to execution. There is no bypass flag.
 *  - A deny at ANY stage stops the pipeline with the stage identity recorded.
 *  - Security decisions are a CEILING: no later stage (budget, learning)
 *    can upgrade a security deny into an allow.
 *  - Human approval is an explicit one-shot token; approval state is never
 *    implicitly inferred.
 */

/**
 * Canonical, ordered admission stages. The ordinal order IS the execution
 * order of the pipeline; tests assert it.
 */
enum class AdmissionStage(val stageCode: String, val displayLabelAr: String) {
    PARAMETER_VALIDATION("PARAMETER_VALIDATION", "التحقق من المعاملات"),
    PRINCIPAL_AUTHORIZATION("PRINCIPAL_AUTHORIZATION", "تفويض الهوية"),
    RISK_CLASSIFICATION("RISK_CLASSIFICATION", "تصنيف المخاطر"),
    SECURITY_POLICY("SECURITY_POLICY", "سياسة الأمان"),
    WORKSPACE_SCOPE_VALIDATION("WORKSPACE_SCOPE_VALIDATION", "نطاق مساحة العمل"),
    PATH_POLICY("PATH_POLICY", "سياسة المسارات"),
    BUDGET_AUTHORIZATION("BUDGET_AUTHORIZATION", "تفويض الميزانية"),
    RATE_LIMIT("RATE_LIMIT", "حدود المعدل"),
    HUMAN_APPROVAL("HUMAN_APPROVAL", "الموافقة البشرية"),
    SANDBOX_ADMISSION("SANDBOX_ADMISSION", "إدخال الصندوق الرملي"),
    EXECUTION("EXECUTION", "التنفيذ"),
    AUDIT("AUDIT", "التدقيق")
}

/** Final admission verdict. NEEDS_HUMAN_APPROVAL is not a deny — it is a pause. */
enum class AdmissionDecision(val code: String) {
    ALLOWED("ALLOWED"),
    NEEDS_HUMAN_APPROVAL("NEEDS_HUMAN_APPROVAL"),
    DENIED("DENIED")
}

/** Outcome of a single pipeline stage. */
data class AdmissionStageOutcome(
    val stage: AdmissionStage,
    val passed: Boolean,
    val decision: AdmissionDecision,
    val detail: String,
    val durationMs: Long = 0L
)

/** Request for tool admission into the governed runtime. */
data class ToolAdmissionRequest(
    val requestId: String,
    val executionId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val principalType: PrincipalType,
    val principalId: String,
    val workspaceId: String? = null,
    val projectId: Long? = null,
    val pathArguments: List<String> = emptyList(),
    val estimatedCostMicros: Long? = null,
    val estimatedTokens: Int? = null,
    val budgetScopeKey: String? = null,
    val approvalTokenId: String? = null,
    /**
     * GAP-05 (Design Closure 2026, ADR-5): resource identity for REMOTE
     * (paid) tools — e.g. the materialized MCP tool's ResourceId. Null for
     * local tools, which honestly carry no cash cost.
     */
    val remoteResourceId: String? = null,
    val requestedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Final admission result including the FULL ordered stage trace —
 * the trace is the audit evidence of what was checked and in which order.
 */
data class AdmissionResult(
    val requestId: String,
    val decision: AdmissionDecision,
    val stageTrace: List<AdmissionStageOutcome>,
    val denyStage: AdmissionStage? = null,
    val denyReason: String? = null,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val approvalRequestId: String? = null,
    val approvalPrompt: String? = null,
    val sandboxSessionId: String? = null,
    val admittedAtEpochMs: Long = System.currentTimeMillis()
) {
    val isAllowed: Boolean get() = decision == AdmissionDecision.ALLOWED
    val isDenied: Boolean get() = decision == AdmissionDecision.DENIED
}

/** Risk classification of a tool operation feeding stage 3. */
data class ToolRiskProfile(
    val toolName: String,
    val baseRisk: RiskLevel,
    val isDestructive: Boolean = false,
    val isIrreversible: Boolean = false,
    val requiresSandbox: Boolean = false,
    val isWorkspaceBound: Boolean = false
)

/**
 * Security ceiling contract: the evaluation from [SecurityGuardService] —
 * the pipeline re-uses its verdict as a hard ceiling at stage 4.
 */
data class SecurityCeilingEvaluation(
    val securityDecision: SecurityDecision,
    val riskLevel: RiskLevel,
    val matchedRule: String? = null,
    val explanation: String
)

/** Budget authorization contract fed from EconomicGovernanceService. */
enum class BudgetAuthorizationVerdict(val code: String) {
    ALLOWED("ALLOWED"),
    WARNED("WARNED"),
    APPROVAL_REQUIRED("APPROVAL_REQUIRED"),
    DOWNGRADE("DOWNGRADE"),
    LOCAL_FALLBACK("LOCAL_FALLBACK"),
    DENIED("DENIED")
}

/** One-shot human approval token. */
enum class ApprovalResolution(val code: String) {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    EXPIRED("EXPIRED")
}
