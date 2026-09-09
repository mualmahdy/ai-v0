package com.example.application.governed

import com.example.domain.core.security.RiskLevel
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.security.governance.AdmissionDecision
import com.example.domain.core.security.governance.AdmissionResult
import com.example.domain.core.security.governance.AdmissionStage
import com.example.domain.core.security.governance.AdmissionStageOutcome
import com.example.domain.core.security.governance.BudgetAuthorizationVerdict
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.core.security.governance.SecurityCeilingEvaluation
import com.example.domain.core.security.governance.ToolAdmissionRequest
import com.example.domain.core.security.governance.ToolRiskProfile
import com.example.domain.core.security.governance.WorkspacePathPolicy
import com.example.domain.core.security.governance.WorkspacePathPolicyEngine
import com.example.domain.core.security.governance.PathOperation
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolInput
import com.example.domain.ports.governed.AdmissionAuditPort
import com.example.domain.ports.governed.PrincipalAuthorizationPort
import com.example.domain.ports.security.SecurityGuardPort
import java.util.UUID

/**
 * ============================================================================
 * AdmissionControlService — Phase 1 (Governed Intelligent Execution)
 * ============================================================================
 *
 * THE single ordered gate between an agent's tool request and execution.
 *
 * Order (fixed, asserted by tests):
 *   PARAMETER_VALIDATION -> PRINCIPAL_AUTHORIZATION -> RISK_CLASSIFICATION
 *   -> SECURITY_POLICY -> WORKSPACE_SCOPE_VALIDATION -> PATH_POLICY
 *   -> BUDGET_AUTHORIZATION -> RATE_LIMIT -> HUMAN_APPROVAL
 *   -> SANDBOX_ADMISSION -> (ALLOWED)
 *
 * Invariants:
 *  - There is NO bypass parameter. Every tool execution MUST pass admit().
 *  - Security is a CEILING: a budget ALLOW can never override a security DENY
 *    (security runs BEFORE budget; a later stage cannot revisit it).
 *  - Every terminal decision (ALLOWED / DENIED / NEEDS_HUMAN_APPROVAL) is
 *    written to the audit sink with its full stage trace.
 *  - Failures are explicit and machine-readable; nothing is silently zero.
 */

/** Resolves the declaration of a tool by name (registry lookup). */
fun interface ToolDeclarationResolver {
    fun resolve(toolName: String): ToolDeclaration?
}

/** Budget gate boundary (production: EconomicGovernanceService.authorize). */
fun interface BudgetAuthorizationPort {
    suspend fun authorize(request: ToolAdmissionRequest): BudgetAuthorizationOutcome
}

data class BudgetAuthorizationOutcome(
    val verdict: BudgetAuthorizationVerdict,
    val reason: String
)

/** Rate-limit boundary (production: RateLimitGovernor).
 *
 * P1-12 FIX (audit 2026 §25 — TOCTOU): the check-and-reserve operation is
 * ATOMIC. The legacy `allowsRequest()` pure read + separately-invoked
 * `recordRequest()` allowed N concurrent callers to all pass the check
 * before any of them incremented the window. The admission gate now calls
 * [tryAcquire], which validates the window AND reserves the request slot
 * under the same lock. */
fun interface RateLimitCheckPort {
    fun tryAcquire(scopeKey: String): Boolean
}

class AdmissionControlService(
    private val toolDeclarations: ToolDeclarationResolver,
    private val principalAuthorization: PrincipalAuthorizationPort,
    private val securityGuard: SecurityGuardPort,
    private val budgetAuthorization: BudgetAuthorizationPort,
    private val rateLimitCheck: RateLimitCheckPort,
    private val approvalGate: HumanApprovalGate,
    private val sandboxService: SandboxLifecycleService,
    private val auditSink: AdmissionAuditPort,
    private val policy: SecurityPolicy = SecurityPolicy(),
    private val pathPolicy: WorkspacePathPolicy = WorkspacePathPolicy.Default,
    /** projectId -> canonical absolute workspace root (production: File-based). */
    private val workspaceRootResolver: (suspend (Long) -> String?) = { null },
    /** Platform canonical path resolver (production: File.getCanonicalPath). */
    private val canonicalResolver: (String) -> String =
        WorkspacePathPolicyEngine::lexicalCanonicalResolver,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Runs the full ordered pipeline for [request]. Returns an
     * [AdmissionResult] with the complete stage trace. This is the ONLY
     * public entry point to an admission.
     */
    suspend fun admit(request: ToolAdmissionRequest): AdmissionResult {
        val trace = mutableListOf<AdmissionStageOutcome>()
        var risk = RiskLevel.LOW

        // -------- 1. PARAMETER VALIDATION (untrusted model output) --------
        val start = clock()
        val declaration = toolDeclarations.resolve(request.toolName)
        if (declaration == null) {
            trace += AdmissionStageOutcome(AdmissionStage.PARAMETER_VALIDATION, false, AdmissionDecision.DENIED, "أداة غير معروفة: ${request.toolName} — لا يوجد مخطط معلن.", clock() - start)
            val result = buildDenied(request, trace, AdmissionStage.PARAMETER_VALIDATION, "UNKNOWN_TOOL", risk)
            auditAdmission(request, result)
            return result
        }
        val paramFailure = validateParameters(declaration, request.arguments)
        if (paramFailure != null) {
            trace += AdmissionStageOutcome(AdmissionStage.PARAMETER_VALIDATION, false, AdmissionDecision.DENIED, paramFailure, clock() - start)
            val result = buildDenied(request, trace, AdmissionStage.PARAMETER_VALIDATION, paramFailure, risk)
            auditAdmission(request, result)
            return result
        }
        trace += AdmissionStageOutcome(AdmissionStage.PARAMETER_VALIDATION, true, AdmissionDecision.ALLOWED, "المعاملات مطابقة لمخطط الأداة.", clock() - start)

        // -------- 2. PRINCIPAL AUTHORIZATION ------------------------------
        // P0-1/P1-11: workspace-aware check — a grant authorizes only when
        // GLOBAL or scoped to the SAME workspace as the request.
        val authStart = clock()
        val authorized = principalAuthorization.check(
            request.principalType, request.principalId,
            SecurableResourceType.TOOL, request.toolName, Permission.EXECUTE,
            request.workspaceId
        )
        if (!authorized) {
            trace += AdmissionStageOutcome(AdmissionStage.PRINCIPAL_AUTHORIZATION, false, AdmissionDecision.DENIED, "الهوية ${request.principalId} لا تملك تصريح EXECUTE على الأداة ${request.toolName}.", clock() - authStart)
            val result = buildDenied(request, trace, AdmissionStage.PRINCIPAL_AUTHORIZATION, "PRINCIPAL_NOT_AUTHORIZED", risk)
            auditAdmission(request, result)
            return result
        }
        trace += AdmissionStageOutcome(AdmissionStage.PRINCIPAL_AUTHORIZATION, true, AdmissionDecision.ALLOWED, "الهوية مُفوَّضة.", clock() - authStart)

        // -------- 3. RISK CLASSIFICATION ----------------------------------
        val riskStart = clock()
        val profile = riskProfileFor(request.toolName, declaration)
        risk = profile.baseRisk
        if (risk == RiskLevel.CRITICAL && !policy.allowShellCommands && profile.requiresSandbox) {
            // CRITICAL sandbox-requiring tools are only admissible with
            // explicit consent AND sandbox admission below; we do not deny
            // here — the sandbox stage will enforce honest isolation.
            trace += AdmissionStageOutcome(AdmissionStage.RISK_CLASSIFICATION, true, AdmissionDecision.ALLOWED, "مخاطر حرجة: تتطلب موافقة صريحة وصندوقاً رملياً.", clock() - riskStart)
        } else {
            trace += AdmissionStageOutcome(AdmissionStage.RISK_CLASSIFICATION, true, AdmissionDecision.ALLOWED, "تصنيف المخاطر: ${risk.name}.", clock() - riskStart)
        }

        // -------- 4. SECURITY POLICY (CEILING) ----------------------------
        val secStart = clock()
        // CANONICAL CLASSIFICATION INPUTS (defect family 2): the SAME
        // declaration facts the execution boundary supplies — one
        // classification authority, closed-world, never a fabricated ALLOW.
        val evaluation = securityGuard.evaluateToolExecution(
            ToolInput(
                toolName = request.toolName,
                arguments = request.arguments,
                executionId = request.executionId,
                contextAttributes = mapOf(
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_SIDE_EFFECTS to declaration.sideEffects.name,
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_SENSITIVE to declaration.isSensitive.toString(),
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_REQUIRES_CONSENT to declaration.requiresHumanConsent.toString(),
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_NETWORK_REQUIREMENT to declaration.networkRequirement.name
                )
            ),
            policy
        )
        val ceiling = SecurityCeilingEvaluation(
            securityDecision = evaluation.decision,
            riskLevel = evaluation.riskLevel,
            matchedRule = evaluation.matchedRule,
            explanation = evaluation.explanation
        )
        if (ceiling.securityDecision == SecurityDecision.DENY) {
            trace += AdmissionStageOutcome(AdmissionStage.SECURITY_POLICY, false, AdmissionDecision.DENIED, ceiling.explanation, clock() - secStart)
            val result = buildDenied(request, trace, AdmissionStage.SECURITY_POLICY, "SECURITY_DENY:${ceiling.matchedRule ?: "POLICY"}", ceiling.riskLevel)
            auditAdmission(request, result)
            return result
        }
        trace += AdmissionStageOutcome(
            AdmissionStage.SECURITY_POLICY, true,
            if (ceiling.securityDecision == SecurityDecision.REQUIRE_CONSENT) AdmissionDecision.NEEDS_HUMAN_APPROVAL else AdmissionDecision.ALLOWED,
            ceiling.explanation, clock() - secStart
        )

        // -------- 5. WORKSPACE SCOPE VALIDATION ---------------------------
        val scopeStart = clock()
        if (profile.isWorkspaceBound && (request.projectId == null || request.workspaceId == null)) {
            trace += AdmissionStageOutcome(AdmissionStage.WORKSPACE_SCOPE_VALIDATION, false, AdmissionDecision.DENIED, "الأداة ${request.toolName} مقيّدة بمساحة عمل، والطلب لا يحمل نطاقاً.", clock() - scopeStart)
            val result = buildDenied(request, trace, AdmissionStage.WORKSPACE_SCOPE_VALIDATION, "WORKSPACE_SCOPE_REQUIRED", risk)
            auditAdmission(request, result)
            return result
        }
        trace += AdmissionStageOutcome(AdmissionStage.WORKSPACE_SCOPE_VALIDATION, true, AdmissionDecision.ALLOWED, "نطاق مساحة العمل مُقدَّم أو غير مطلوب.", clock() - scopeStart)

        // -------- 6. PATH POLICY ------------------------------------------
        val pathStart = clock()
        if (request.pathArguments.isNotEmpty() && request.projectId != null) {
            val root = workspaceRootResolver(request.projectId)
            if (root == null) {
                trace += AdmissionStageOutcome(AdmissionStage.PATH_POLICY, false, AdmissionDecision.DENIED, "تعذر تحديد جذر مساحة العمل للمشروع ${request.projectId}.", clock() - pathStart)
                val result = buildDenied(request, trace, AdmissionStage.PATH_POLICY, "WORKSPACE_ROOT_UNRESOLVED", risk)
                auditAdmission(request, result)
                return result
            }
            val operation = operationForTool(request.toolName)
            for (path in request.pathArguments) {
                val failure = WorkspacePathPolicyEngine.validate(
                    rawPath = path,
                    operation = operation,
                    policy = pathPolicy,
                    workspaceRootCanonical = root,
                    canonicalResolver = canonicalResolver
                )
                if (failure != null) {
                    val reason = "PATH_POLICY:${failure::class.simpleName}"
                    trace += AdmissionStageOutcome(AdmissionStage.PATH_POLICY, false, AdmissionDecision.DENIED, failure.toString(), clock() - pathStart)
                    val result = buildDenied(request, trace, AdmissionStage.PATH_POLICY, reason, risk)
                    auditAdmission(request, result)
                    return result
                }
            }
        }
        trace += AdmissionStageOutcome(AdmissionStage.PATH_POLICY, true, AdmissionDecision.ALLOWED, "المسارات داخل النطاق وغير محظورة.", clock() - pathStart)

        // -------- 7. BUDGET AUTHORIZATION ---------------------------------
        val budgetStart = clock()
        val budgetOutcome = budgetAuthorization.authorize(request)
        when (budgetOutcome.verdict) {
            BudgetAuthorizationVerdict.DENIED -> {
                trace += AdmissionStageOutcome(AdmissionStage.BUDGET_AUTHORIZATION, false, AdmissionDecision.DENIED, budgetOutcome.reason, clock() - budgetStart)
                val result = buildDenied(request, trace, AdmissionStage.BUDGET_AUTHORIZATION, "BUDGET_DENIED", risk)
                auditAdmission(request, result)
                return result
            }
            else -> trace += AdmissionStageOutcome(
                AdmissionStage.BUDGET_AUTHORIZATION, true,
                if (budgetOutcome.verdict == BudgetAuthorizationVerdict.APPROVAL_REQUIRED) AdmissionDecision.NEEDS_HUMAN_APPROVAL else AdmissionDecision.ALLOWED,
                budgetOutcome.reason, clock() - budgetStart
            )
        }

        // -------- 8. RATE LIMIT (P1-12: atomic check-and-reserve) --------
        val rateStart = clock()
        val scopeKey = request.budgetScopeKey ?: "admission:tool:${request.toolName}"
        if (!rateLimitCheck.tryAcquire(scopeKey)) {
            trace += AdmissionStageOutcome(AdmissionStage.RATE_LIMIT, false, AdmissionDecision.DENIED, "حدود المعدل مُفعَّلة على $scopeKey.", clock() - rateStart)
            val result = buildDenied(request, trace, AdmissionStage.RATE_LIMIT, "RATE_LIMITED", risk)
            auditAdmission(request, result)
            return result
        }
        trace += AdmissionStageOutcome(AdmissionStage.RATE_LIMIT, true, AdmissionDecision.ALLOWED, "ضمن حدود المعدل.", clock() - rateStart)

        // -------- 9. HUMAN APPROVAL ---------------------------------------
        val approvalStart = clock()
        val needsApproval = declaration.requiresHumanConsent ||
            ceiling.securityDecision == SecurityDecision.REQUIRE_CONSENT ||
            budgetOutcome.verdict == BudgetAuthorizationVerdict.APPROVAL_REQUIRED ||
            risk == RiskLevel.HIGH || risk == RiskLevel.CRITICAL

        if (needsApproval) {
            val token = request.approvalTokenId
            if (token == null) {
                val approval = approvalGate.requestApproval(
                    executionId = request.executionId,
                    toolName = request.toolName,
                    riskLevel = risk.name,
                    prompt = ceiling.explanation,
                    justification = budgetOutcome.reason
                )
                trace += AdmissionStageOutcome(AdmissionStage.HUMAN_APPROVAL, false, AdmissionDecision.NEEDS_HUMAN_APPROVAL, "طلب موافقة بشرية: ${approval.approvalId}", clock() - approvalStart)
                val result = AdmissionResult(
                    requestId = request.requestId,
                    decision = AdmissionDecision.NEEDS_HUMAN_APPROVAL,
                    stageTrace = trace.toList(),
                    riskLevel = risk,
                    approvalRequestId = approval.approvalId,
                    approvalPrompt = approval.prompt
                )
                auditAdmission(request, result)
                return result
            }
            val consumed = approvalGate.tryConsumeToken(token, request.executionId, request.toolName)
            if (!consumed) {
                trace += AdmissionStageOutcome(AdmissionStage.HUMAN_APPROVAL, false, AdmissionDecision.DENIED, "رمز الموافقة غير صالح أو مستهلك أو منتهي.", clock() - approvalStart)
                val result = buildDenied(request, trace, AdmissionStage.HUMAN_APPROVAL, "INVALID_APPROVAL_TOKEN", risk)
                auditAdmission(request, result)
                return result
            }
            trace += AdmissionStageOutcome(AdmissionStage.HUMAN_APPROVAL, true, AdmissionDecision.ALLOWED, "رمز موافقة صالح تم استهلاكه لمرة واحدة.", clock() - approvalStart)
        } else {
            trace += AdmissionStageOutcome(AdmissionStage.HUMAN_APPROVAL, true, AdmissionDecision.ALLOWED, "لا تتطلب موافقة بشرية.", clock() - approvalStart)
        }

        // -------- 10. SANDBOX ADMISSION -----------------------------------
        val sandboxStart = clock()
        var sandboxSessionId: String? = null
        if (profile.requiresSandbox) {
            val provisioned = sandboxService.provision(
                workspaceId = request.workspaceId ?: "__global__",
                requestedBy = request.principalId,
                requiredIsolation = com.example.domain.core.runtime.IsolationLevel.TRUE_ISOLATION
            )
            when (provisioned) {
                is com.example.domain.core.Outcome.Error -> {
                    val reason = "SANDBOX_INSUFFICIENT_ISOLATION:${provisioned.failure::class.simpleName}"
                    trace += AdmissionStageOutcome(AdmissionStage.SANDBOX_ADMISSION, false, AdmissionDecision.DENIED, provisioned.failure.toString(), clock() - sandboxStart)
                    val result = buildDenied(request, trace, AdmissionStage.SANDBOX_ADMISSION, reason, risk)
                    auditAdmission(request, result)
                    return result
                }
                is com.example.domain.core.Outcome.Success -> {
                    sandboxSessionId = provisioned.value.sessionId
                    trace += AdmissionStageOutcome(AdmissionStage.SANDBOX_ADMISSION, true, AdmissionDecision.ALLOWED, "صندوق رملي جاهز: ${provisioned.value.sessionId} بعزل ${provisioned.value.isolationLevel.code}.", clock() - sandboxStart)
                }
                else -> {}
            }
        } else {
            trace += AdmissionStageOutcome(AdmissionStage.SANDBOX_ADMISSION, true, AdmissionDecision.ALLOWED, "لا يتطلب صندوقاً رملياً.", clock() - sandboxStart)
        }

        // -------- ALLOWED ---------------------------------------------------
        val result = AdmissionResult(
            requestId = request.requestId,
            decision = AdmissionDecision.ALLOWED,
            stageTrace = trace.toList(),
            riskLevel = risk,
            sandboxSessionId = sandboxSessionId
        )
        auditAdmission(request, result)
        return result
    }

    // ------------------------------------------------------------------ //

    private fun riskProfileFor(toolName: String, declaration: ToolDeclaration): ToolRiskProfile =
        riskProfiles[toolName] ?: ToolRiskProfile(
            toolName = toolName,
            baseRisk = when (declaration.sideEffects) {
                com.example.domain.core.capability.SideEffectClassification.READ_ONLY -> RiskLevel.LOW
                com.example.domain.core.capability.SideEffectClassification.IDEMPOTENT -> RiskLevel.LOW
                com.example.domain.core.capability.SideEffectClassification.STATE_MUTATION -> RiskLevel.MEDIUM
                com.example.domain.core.capability.SideEffectClassification.EXTERNAL_SIDE_EFFECT -> RiskLevel.HIGH
                com.example.domain.core.capability.SideEffectClassification.IRREVERSIBLE -> RiskLevel.CRITICAL
            },
            isWorkspaceBound = declaration.locality == com.example.domain.core.capability.Locality.LOCAL_ON_DEVICE
        )

    private val riskProfiles: Map<String, ToolRiskProfile> = defaultRiskProfiles()

    private fun validateParameters(declaration: ToolDeclaration, arguments: Map<String, Any?>): String? {
        for (param in declaration.parameters) {
            if (param.isRequired && !arguments.containsKey(param.name)) {
                return "MISSING_PARAMETER:${param.name}"
            }
            val value = arguments[param.name] ?: continue
            val typeOk = when (param.type) {
                "string" -> value is String
                "number" -> value is Number
                "boolean" -> value is Boolean
                "object" -> value is Map<*, *>
                "array" -> value is List<*>
                else -> true
            }
            if (!typeOk) return "PARAMETER_TYPE_MISMATCH:${param.name}:${param.type}"
            if (param.enumValues.isNotEmpty() && value is String && value !in param.enumValues) {
                return "PARAMETER_ENUM_VIOLATION:${param.name}"
            }
        }
        return null
    }

    private fun operationForTool(toolName: String): PathOperation = when (toolName) {
        "read_file" -> PathOperation.READ
        "list_files" -> PathOperation.LIST
        "search_files" -> PathOperation.SEARCH
        "create_file" -> PathOperation.CREATE
        "write_file" -> PathOperation.WRITE
        "apply_patch" -> PathOperation.MODIFY
        "rename_file" -> PathOperation.RENAME_SOURCE
        "delete_file" -> PathOperation.DELETE
        else -> PathOperation.READ
    }

    private fun buildDenied(
        request: ToolAdmissionRequest,
        trace: List<AdmissionStageOutcome>,
        stage: AdmissionStage,
        reason: String,
        risk: RiskLevel
    ): AdmissionResult = AdmissionResult(
        requestId = request.requestId,
        decision = AdmissionDecision.DENIED,
        stageTrace = trace.toList(),
        denyStage = stage,
        denyReason = reason,
        riskLevel = risk
    )

    private suspend fun auditAdmission(request: ToolAdmissionRequest, result: AdmissionResult) {
        val severity = when {
            result.decision == AdmissionDecision.ALLOWED -> "INFO"
            result.decision == AdmissionDecision.NEEDS_HUMAN_APPROVAL -> "WARN"
            result.riskLevel == RiskLevel.CRITICAL -> "CRITICAL"
            else -> "WARN"
        }
        val attributes = buildMap {
            put("requestId", request.requestId)
            put("stagesChecked", result.stageTrace.size.toString())
            put("denyStage", result.denyStage?.stageCode ?: "")
            put("principal", "${request.principalType.code}:${request.principalId}")
        }
        auditSink.record(
            severity = severity,
            actor = "${request.principalType.code}:${request.principalId}",
            action = "ADMISSION_${result.decision.code}",
            resourceType = "TOOL",
            resourceId = request.toolName,
            decision = result.decision.code,
            reason = result.denyReason ?: "تم الترخيص بعد اجتياز ${result.stageTrace.size} مرحلة.",
            workspaceId = request.workspaceId,
            attributes = attributes
        )
    }

    companion object {
        fun defaultRiskProfiles(): Map<String, ToolRiskProfile> = mapOf(
            "list_files" to ToolRiskProfile("list_files", RiskLevel.LOW, isWorkspaceBound = true),
            "read_file" to ToolRiskProfile("read_file", RiskLevel.LOW, isWorkspaceBound = true),
            "search_files" to ToolRiskProfile("search_files", RiskLevel.LOW, isWorkspaceBound = true),
            "create_file" to ToolRiskProfile("create_file", RiskLevel.MEDIUM, isWorkspaceBound = true),
            "write_file" to ToolRiskProfile("write_file", RiskLevel.MEDIUM, isDestructive = true, isWorkspaceBound = true),
            "apply_patch" to ToolRiskProfile("apply_patch", RiskLevel.MEDIUM, isWorkspaceBound = true),
            "rename_file" to ToolRiskProfile("rename_file", RiskLevel.MEDIUM, isDestructive = true, isWorkspaceBound = true),
            "delete_file" to ToolRiskProfile("delete_file", RiskLevel.HIGH, isDestructive = true, isIrreversible = true, isWorkspaceBound = true),
            "run_code" to ToolRiskProfile("run_code", RiskLevel.CRITICAL, requiresSandbox = true),
            "run_tests" to ToolRiskProfile("run_tests", RiskLevel.CRITICAL, requiresSandbox = true)
        )
    }
}
