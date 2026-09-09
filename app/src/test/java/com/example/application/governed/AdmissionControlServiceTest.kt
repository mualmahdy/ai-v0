package com.example.application.governed

import com.example.domain.core.security.RiskLevel
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityEvaluation
import com.example.domain.core.security.governance.AdmissionDecision
import com.example.domain.core.security.governance.AdmissionStage
import com.example.domain.core.security.governance.BudgetAuthorizationVerdict
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.ToolAdmissionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * ============================================================================
 * AdmissionControlServiceTest — Phase 1
 * ============================================================================
 *
 * Asserts the ORDERED pipeline: stages run in the canonical order, a deny
 * at any stage stops execution with the stage identity recorded, security
 * is a CEILING, and human approval is an explicit one-shot token.
 */
class AdmissionControlServiceTest {

    private fun request(
        toolName: String,
        arguments: Map<String, Any?> = mapOf("path" to "src/Main.kt"),
        projectId: Long? = 1L,
        workspaceId: String? = "ws-1"
    ): ToolAdmissionRequest = ToolAdmissionRequest(
        requestId = "req_${UUID.randomUUID()}",
        executionId = "exec-1",
        toolName = toolName,
        arguments = arguments,
        principalType = PrincipalType.AGENT,
        principalId = "agent-1",
        workspaceId = workspaceId,
        projectId = projectId,
        pathArguments = if (toolName in setOf("read_file", "write_file", "create_file", "apply_patch", "delete_file")) {
            listOfNotNull(arguments["path"]?.toString())
        } else emptyList()
    )

    private fun build(
        security: com.example.domain.ports.security.SecurityGuardPort =
            com.example.application.security.SecurityGuardService(),
        budget: ScriptableBudgetGate = ScriptableBudgetGate {
            BudgetAuthorizationOutcome(BudgetAuthorizationVerdict.ALLOWED, "لا ميزانية معرفة.")
        },
        rateLimit: ScriptableRateLimit = ScriptableRateLimit()
    ): GovernedPipelineFactory.PipelineParts =
        GovernedPipelineFactory.build(
            security = security,
            budget = budget,
            rateLimit = rateLimit
        )

    // ---------------- ORDERING ---------------- //

    @Test
    fun `pipeline stages run in canonical order for an allowed read`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(request("read_file"))
        assertTrue(result.decision == AdmissionDecision.ALLOWED)

        val expectedOrder = listOf(
            AdmissionStage.PARAMETER_VALIDATION,
            AdmissionStage.PRINCIPAL_AUTHORIZATION,
            AdmissionStage.RISK_CLASSIFICATION,
            AdmissionStage.SECURITY_POLICY,
            AdmissionStage.WORKSPACE_SCOPE_VALIDATION,
            AdmissionStage.PATH_POLICY,
            AdmissionStage.BUDGET_AUTHORIZATION,
            AdmissionStage.RATE_LIMIT,
            AdmissionStage.HUMAN_APPROVAL,
            AdmissionStage.SANDBOX_ADMISSION
        )
        assertEquals(expectedOrder, result.stageTrace.map { it.stage })
        // All stages passed
        assertTrue(result.stageTrace.all { it.passed })
    }

    // ---------------- STAGE-LEVEL DENIALS ---------------- //

    @Test
    fun `unknown tool is denied at PARAMETER_VALIDATION`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(request("not_a_real_tool"))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.PARAMETER_VALIDATION, result.denyStage)
        assertEquals("UNKNOWN_TOOL", result.denyReason)
    }

    @Test
    fun `missing required parameter is denied at PARAMETER_VALIDATION`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(request("read_file", arguments = emptyMap()))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.PARAMETER_VALIDATION, result.denyStage)
        assertTrue(result.denyReason!!.startsWith("MISSING_PARAMETER"))
    }

    @Test
    fun `type-mismatched parameter is denied`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(request("read_file", arguments = mapOf("path" to 42)))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertTrue(result.denyReason!!.startsWith("PARAMETER_TYPE_MISMATCH"))
    }

    @Test
    fun `unauthorized principal is denied BEFORE security or budget`() = runBlocking {
        val parts = GovernedPipelineFactory.build(
            security = ScriptableSecurityGuard { SecurityEvaluation(SecurityDecision.ALLOW, RiskLevel.LOW, explanation = "سماح") }
        )
        // deny-list the principal via a fake with deny entries
        val deniedPrincipal = object : com.example.domain.ports.governed.PrincipalAuthorizationPort {
            override suspend fun check(
                principalType: PrincipalType,
                principalId: String,
                resourceType: com.example.domain.core.security.governance.SecurableResourceType,
                resourceId: String,
                permission: com.example.domain.core.security.governance.Permission,
                workspaceId: String?
            ): Boolean = false
        }
        // Rebuild admission with the denying principal port
        val admission = AdmissionControlService(
            toolDeclarations = com.example.application.governed.ToolDeclarationResolver { CodingToolchainService.declarations[it] },
            principalAuthorization = deniedPrincipal,
            securityGuard = ScriptableSecurityGuard { SecurityEvaluation(SecurityDecision.ALLOW, RiskLevel.LOW, explanation = "سماح") },
            budgetAuthorization = ScriptableBudgetGate { BudgetAuthorizationOutcome(BudgetAuthorizationVerdict.ALLOWED, "ok") },
            rateLimitCheck = { true },
            approvalGate = parts.approvalGate,
            sandboxService = parts.sandbox,
            auditSink = parts.audit,
            workspaceRootResolver = { parts.workspaceRoots[it] }
        )
        val result = admission.admit(request("read_file"))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.PRINCIPAL_AUTHORIZATION, result.denyStage)
        // Security and budget stages were NEVER reached
        val stages = result.stageTrace.map { it.stage }
        assertTrue(AdmissionStage.SECURITY_POLICY !in stages)
        assertTrue(AdmissionStage.BUDGET_AUTHORIZATION !in stages)
    }

    @Test
    fun `security deny is a CEILING — budget allow cannot override`() = runBlocking {
        val parts = GovernedPipelineFactory.build(
            security = ScriptableSecurityGuard {
                SecurityEvaluation(SecurityDecision.DENY, RiskLevel.CRITICAL, matchedRule = "PROHIBITED_TOOL_PATTERN", explanation = "أداة محظورة أمنياً.")
            },
            budget = ScriptableBudgetGate {
                BudgetAuthorizationOutcome(BudgetAuthorizationVerdict.ALLOWED, "الميزانية تسمح") // budget says yes!
            }
        )
        val result = parts.admission.admit(request("read_file"))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.SECURITY_POLICY, result.denyStage)
        assertTrue(result.denyReason!!.startsWith("SECURITY_DENY"))
        // Budget stage never reached — the ceiling held.
        assertTrue(AdmissionStage.BUDGET_AUTHORIZATION !in result.stageTrace.map { it.stage })
    }

    @Test
    fun `workspace-bound tool without workspace scope is denied`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(request("read_file", projectId = null, workspaceId = null))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.WORKSPACE_SCOPE_VALIDATION, result.denyStage)
    }

    @Test
    fun `path traversal is caught by the pipeline (defense in depth)`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(
            request("read_file", arguments = mapOf("path" to "../../secrets.properties"))
        )
        // The REAL SecurityGuardService denies traversal FIRST (restricted
        // paths contain ".."); path policy would deny it at the next stage.
        // Either way the pipeline refuses — never executes.
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertTrue(
            result.denyStage == AdmissionStage.SECURITY_POLICY ||
                result.denyStage == AdmissionStage.PATH_POLICY
        )
    }

    @Test
    fun `forbidden git target passes security guard but is denied at PATH_POLICY`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(
            request("read_file", arguments = mapOf("path" to ".git/config"))
        )
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.PATH_POLICY, result.denyStage)
        assertTrue(result.denyReason!!.startsWith("PATH_POLICY:"))
    }

    @Test
    fun `budget deny stops at BUDGET_AUTHORIZATION`() = runBlocking {
        val parts = build(
            budget = ScriptableBudgetGate {
                BudgetAuthorizationOutcome(BudgetAuthorizationVerdict.DENIED, "تم استهلاك الميزانية الصارمة.")
            }
        )
        val result = parts.admission.admit(request("read_file"))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.BUDGET_AUTHORIZATION, result.denyStage)
        assertEquals("BUDGET_DENIED", result.denyReason)
    }

    @Test
    fun `rate limit deny stops at RATE_LIMIT`() = runBlocking {
        val rateLimit = ScriptableRateLimit().apply { deny() }
        val parts = build(rateLimit = rateLimit)
        val result = parts.admission.admit(request("read_file"))
        assertEquals(AdmissionDecision.DENIED, result.decision)
        assertEquals(AdmissionStage.RATE_LIMIT, result.denyStage)
        assertEquals("RATE_LIMITED", result.denyReason)
    }

    @Test
    fun `run_code is honestly denied at SANDBOX_ADMISSION on Android`() = runBlocking {
        val parts = build()
        val result = parts.admission.admit(
            request("run_code", arguments = mapOf("language" to "kotlin", "code" to "println(1)"), projectId = null, workspaceId = "ws-1")
        )
        // CRITICAL tool: approval runs BEFORE sandbox, so first request pauses.
        assertEquals(AdmissionDecision.NEEDS_HUMAN_APPROVAL, result.decision)
        val approvalId = result.approvalRequestId!!
        parts.approvalGate.approve(approvalId, resolvedBy = "user:owner")

        val second = parts.admission.admit(
            request("run_code", arguments = mapOf("language" to "kotlin", "code" to "println(1)"), projectId = null, workspaceId = "ws-1")
                .let { it.copy(approvalTokenId = approvalId) }
        )
        assertEquals(AdmissionDecision.DENIED, second.decision)
        assertEquals(AdmissionStage.SANDBOX_ADMISSION, second.denyStage)
        assertTrue(second.denyReason!!.startsWith("SANDBOX_INSUFFICIENT_ISOLATION"))
    }

    // ---------------- HUMAN APPROVAL ---------------- //

    @Test
    fun `delete_file requires approval then executes with one-shot token`() = runBlocking {
        val parts = build()
        parts.storage.seed(1L, "temp/scratch.txt", "x")

        val first = parts.admission.admit(request("delete_file", arguments = mapOf("path" to "temp/scratch.txt")))
        assertEquals(AdmissionDecision.NEEDS_HUMAN_APPROVAL, first.decision)
        val approvalId = first.approvalRequestId
        assertNotNull(approvalId)

        parts.approvalGate.approve(approvalId!!, resolvedBy = "user:owner")

        val second = parts.admission.admit(
            request("delete_file", arguments = mapOf("path" to "temp/scratch.txt"))
                .copy(approvalTokenId = approvalId)
        )
        assertEquals(AdmissionDecision.ALLOWED, second.decision)
    }

    @Test
    fun `approval token is ONE-SHOT — replay is denied`() = runBlocking {
        val parts = build()
        parts.storage.seed(1L, "temp/a.txt", "x")
        parts.storage.seed(1L, "temp/b.txt", "y")

        val first = parts.admission.admit(request("delete_file", arguments = mapOf("path" to "temp/a.txt")))
        val approvalId = first.approvalRequestId!!
        parts.approvalGate.approve(approvalId, "user:owner")

        val second = parts.admission.admit(
            request("delete_file", arguments = mapOf("path" to "temp/a.txt")).copy(approvalTokenId = approvalId)
        )
        assertEquals(AdmissionDecision.ALLOWED, second.decision)

        // REPLAY the same token for a DIFFERENT target must fail.
        val replay = parts.admission.admit(
            request("delete_file", arguments = mapOf("path" to "temp/b.txt")).copy(approvalTokenId = approvalId)
        )
        assertEquals(AdmissionDecision.DENIED, replay.decision)
        assertEquals(AdmissionStage.HUMAN_APPROVAL, replay.denyStage)
        assertEquals("INVALID_APPROVAL_TOKEN", replay.denyReason)
    }

    @Test
    fun `rejected approval denies the request`() = runBlocking {
        val parts = build()
        parts.storage.seed(1L, "temp/c.txt", "x")
        val first = parts.admission.admit(request("delete_file", arguments = mapOf("path" to "temp/c.txt")))
        val approvalId = first.approvalRequestId!!
        parts.approvalGate.reject(approvalId, "user:owner")

        val second = parts.admission.admit(
            request("delete_file", arguments = mapOf("path" to "temp/c.txt")).copy(approvalTokenId = approvalId)
        )
        assertEquals(AdmissionDecision.DENIED, second.decision)
        assertEquals("INVALID_APPROVAL_TOKEN", second.denyReason)
    }

    @Test
    fun `expired approval token is denied`() = runBlocking {
        val store = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
        var now = System.currentTimeMillis()
        val gate = HumanApprovalGate(store = store, clock = { now })
        val parts = GovernedPipelineFactory.build(approvalStore = store).let { factory ->
            // rebuild approval gate with controllable clock inside admission
            val admission = AdmissionControlService(
                toolDeclarations = ToolDeclarationResolver { CodingToolchainService.declarations[it] },
                principalAuthorization = FakePrincipalAuthorization(),
                securityGuard = com.example.application.security.SecurityGuardService(),
                budgetAuthorization = ScriptableBudgetGate { BudgetAuthorizationOutcome(BudgetAuthorizationVerdict.ALLOWED, "ok") },
                rateLimitCheck = { true },
                approvalGate = gate,
                sandboxService = factory.sandbox,
                auditSink = factory.audit,
                workspaceRootResolver = { factory.workspaceRoots[it] }
            )
            factory.copy(admission = admission, approvalGate = gate)
        }

        parts.storage.seed(1L, "temp/d.txt", "x")
        val first = parts.admission.admit(request("delete_file", arguments = mapOf("path" to "temp/d.txt")))
        val approvalId = first.approvalRequestId!!
        now += 11L * 60 * 1000 // advance past the 10-minute TTL
        parts.approvalGate.expireStale()

        val second = parts.admission.admit(
            request("delete_file", arguments = mapOf("path" to "temp/d.txt")).copy(approvalTokenId = approvalId)
        )
        assertEquals(AdmissionDecision.DENIED, second.decision)
        assertEquals(AdmissionStage.HUMAN_APPROVAL, second.denyStage)
    }

    // ---------------- AUDIT ---------------- //

    @Test
    fun `every terminal decision is audited with stage count`() = runBlocking {
        val parts = build()
        parts.admission.admit(request("read_file")) // allowed
        parts.admission.admit(request("not_a_tool")) // denied

        assertEquals(2, parts.audit.records.size)
        val allowed = parts.audit.records[0]
        assertEquals("ADMISSION_ALLOWED", allowed.action)
        assertEquals("INFO", allowed.severity)
        val denied = parts.audit.records[1]
        assertEquals("ADMISSION_DENIED", denied.action)
        assertTrue(denied.severity == "WARN" || denied.severity == "CRITICAL")
        assertEquals("not_a_tool", denied.resourceId)
        assertEquals("PARAMETER_VALIDATION", denied.attributes["denyStage"])
    }

    @Test
    fun `audit carries principal attribution`() = runBlocking {
        val parts = build()
        parts.admission.admit(request("read_file"))
        assertEquals("AGENT:agent-1", parts.audit.records[0].actor)
    }
}
