package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.governed.AdmissionControlService
import com.example.application.governed.HumanApprovalGate
import com.example.domain.core.security.governance.AdmissionDecision
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.ToolAdmissionRequest
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.infrastructure.governed.RoomHumanApprovalStore
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-02 (Design Closure 2026, ADR-2) — ApprovalConsentLoopTest
 * ============================================================================
 *
 * The audit finding (P1): the human-approval consent loop was a DEAD END —
 * approval functions had zero screen callers, resolvedBy was the anonymous
 * "user", expireStale had no production caller, and (worst) an APPROVED
 * request satisfied NOTHING: the admission pipeline's requestApproval only
 * re-uses PENDING rows, so the model's retry minted a NEW request forever;
 * nothing in production ever set `approvalTokenId`.
 *
 * This test pins the now-CLOSEABLE loop against a REAL durable (Room)
 * store + the REAL admission pipeline:
 *   1. request → approve → findApprovedToken (the GAP-02 transport) →
 *      admission WITH the token → ALLOWED — one-shot;
 *   2. replay of the consumed token → DENIED (INVALID_APPROVAL_TOKEN);
 *   3. findApprovedToken is honest: null for PENDING / REJECTED / consumed
 *      / expired;
 *   4. expireStale sweeps overdue PENDING rows (the production sweep runs
 *      in bootstrapRuntime).
 */
@RunWith(RobolectricTestRunner::class)
class ApprovalConsentLoopTest {

    private lateinit var db: AppDatabase
    private lateinit var gate: HumanApprovalGate
    private lateinit var admission: AdmissionControlService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gate = HumanApprovalGate(store = RoomHumanApprovalStore(db.humanApprovalRequestDao()))
        admission = AdmissionControlService(
            toolDeclarations = com.example.application.governed.ToolDeclarationResolver { name ->
                com.example.application.governed.CodingToolchainService.declarations[name]
            },
            principalAuthorization = { _, _, _, _, _, _ -> true },
            securityGuard = com.example.application.security.SecurityGuardService(),
            budgetAuthorization = {
                com.example.application.governed.BudgetAuthorizationOutcome(
                    com.example.domain.core.security.governance.BudgetAuthorizationVerdict.ALLOWED,
                    "لا ميزانية مُعرّفة — سماح."
                )
            },
            rateLimitCheck = { true },
            approvalGate = gate,
            sandboxService = com.example.application.governed.SandboxLifecycleService(
                hostIsolationLevel = com.example.domain.core.runtime.IsolationLevel.APP_SANDBOX_BEST_EFFORT
            ),
            auditSink = { _, _, _, _, _, _, _, _, _ -> },
            workspaceRootResolver = { null }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun toolRequest(approvalTokenId: String? = null, executionId: String = "exec-consent"): ToolAdmissionRequest =
        ToolAdmissionRequest(
            requestId = "adm_test_${System.nanoTime()}",
            executionId = executionId,
            toolName = "delete_file",
            arguments = mapOf("path" to "notes/old.md"),
            principalType = PrincipalType.AGENT,
            principalId = "agent_coder",
            workspaceId = "ws-consent",
            approvalTokenId = approvalTokenId
        )

    @Test
    fun `the consent loop closes - approved token transports once and admits`() = runBlocking {
        // 1. First admission: sensitive tool, no token → pause + durable request.
        val first = admission.admit(toolRequest())
        assertEquals(AdmissionDecision.NEEDS_HUMAN_APPROVAL, first.decision)
        val requestId = first.approvalRequestId
        assertNotNull("GAP-02: the pause must carry the approval request id", requestId)

        // 2. The user approves (UI surface; explicit principal).
        val resolution = gate.approve(requestId!!, "user_local_device")
        assertEquals(com.example.domain.core.security.governance.ApprovalResolution.APPROVED, resolution)

        // 3. GAP-02 TRANSPORT: the approved token is discoverable for the retry.
        val token = gate.findApprovedToken("exec-consent", "delete_file")
        assertEquals("GAP-02: findApprovedToken must surface the approved request id", requestId, token)

        // 4. The retry admission carries the token → ALLOWED (consumed once).
        val second = admission.admit(toolRequest(approvalTokenId = token))
        assertEquals(AdmissionDecision.ALLOWED, second.decision)

        // 5. One-shot: the token is consumed — a replay DENIES.
        assertNull("GAP-02: consumed token must no longer be discoverable", gate.findApprovedToken("exec-consent", "delete_file"))
        val replay = admission.admit(toolRequest(approvalTokenId = token))
        assertEquals(AdmissionDecision.DENIED, replay.decision)
        assertTrue(replay.denyReason.orEmpty().contains("INVALID_APPROVAL_TOKEN"))
    }

    @Test
    fun `findApprovedToken is honest for pending rejected consumed and expired`() = runBlocking {
        // PENDING: not yet approved → no token.
        val pending = gate.requestApproval("exec-a", "delete_file", "HIGH", "prompt", "justification")
        assertNull(gate.findApprovedToken("exec-a", "delete_file"))

        // REJECTED: resolution exists but is a refusal → no token.
        gate.approve(pending.approvalId, "user_local_device")
        // (approve above resolves APPROVED; reject a separate request)
        val pending2 = gate.requestApproval("exec-b", "run_code", "HIGH", "prompt", "justification")
        gate.reject(pending2.approvalId, "user_local_device")
        assertNull(gate.findApprovedToken("exec-b", "run_code"))

        // EXPIRED: an approved request past TTL → no token.
        val expiredRequest = HumanApprovalRequest(
            approvalId = "apr_expired",
            executionId = "exec-c",
            toolName = "delete_file",
            riskLevel = "HIGH",
            prompt = "p",
            justification = "j",
            expiresAtEpochMs = System.currentTimeMillis() - 1_000L
        )
        db.humanApprovalRequestDao().let { dao ->
            dao.upsert(
                com.example.infrastructure.persistence.entities.HumanApprovalRequestEntity(
                    approvalId = expiredRequest.approvalId,
                    executionId = expiredRequest.executionId,
                    toolName = expiredRequest.toolName,
                    riskLevel = expiredRequest.riskLevel,
                    prompt = expiredRequest.prompt,
                    justification = expiredRequest.justification,
                    requestedAtEpochMs = expiredRequest.requestedAtEpochMs,
                    expiresAtEpochMs = expiredRequest.expiresAtEpochMs,
                    resolution = "APPROVED",
                    resolvedBy = "user_local_device",
                    resolvedAtEpochMs = System.currentTimeMillis(),
                    isTokenConsumed = false
                )
            )
        }
        assertNull(gate.findApprovedToken("exec-c", "delete_file"))
    }

    @Test
    fun `expireStale sweeps overdue pending requests`() = runBlocking {
        val dao = db.humanApprovalRequestDao()
        dao.upsert(
            com.example.infrastructure.persistence.entities.HumanApprovalRequestEntity(
                approvalId = "apr_stale",
                executionId = "exec-d",
                toolName = "delete_file",
                riskLevel = "HIGH",
                prompt = "p",
                justification = "j",
                requestedAtEpochMs = System.currentTimeMillis() - 20 * 60 * 1000L,
                expiresAtEpochMs = System.currentTimeMillis() - 10 * 60 * 1000L, // 10 min overdue
                resolution = "PENDING",
                resolvedBy = null,
                resolvedAtEpochMs = null,
                isTokenConsumed = false
            )
        )
        val swept = gate.expireStale()
        assertTrue("GAP-02: the TTL sweep must clean overdue PENDING rows", swept >= 1)
        assertEquals(
            "GAP-02: the swept row must read EXPIRED",
            "EXPIRED",
            dao.find("apr_stale")?.resolution
        )
    }
}
