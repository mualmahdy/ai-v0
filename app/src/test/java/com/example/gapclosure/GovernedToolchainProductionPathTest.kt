package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.governed.AdmissionControlService
import com.example.application.governed.CodingToolchainService
import com.example.application.governed.ConsentGrantPort
import com.example.application.governed.GovernedCodingToolAdapter
import com.example.application.governed.HumanApprovalGate
import com.example.application.governed.ToolDeclarationResolver
import com.example.application.governed.FakeWorkspaceStorage
import com.example.application.security.PermissionGrantService
import com.example.domain.core.Outcome
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.security.governance.AdmissionDecision
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.infrastructure.governed.RoomHumanApprovalStore
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.DimensionSummary
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricSnapshot
import com.example.domain.core.observability.MetricType
import com.example.domain.ports.observability.TelemetryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 * GAP-02 part 2 (Design Closure 2026, ADR-2c) — GovernedToolchainProductionPathTest
 * ============================================================================
 *
 * The deferred half of the consent loop: the governed coding toolchain is
 * now reachable AS THE PRODUCTION FILE PATH via [GovernedCodingToolAdapter]
 * (one adapter per governed tool, registered in the runtime ComponentRegistry).
 * This test pins the FULL loop through the adapter — the same shape
 * ExecutionService drives (self-admitting tool → single internal gate):
 *
 *   1. delete_file with NO consent → PAUSE (NEEDS_HUMAN_APPROVAL) + a
 *      durable PENDING request — the surface card can finally render it;
 *   2. user approves once → the token transports through the adapter's
 *      provider → ONE admission consumes it → the file is REALLY deleted;
 *   3. the consumed token is never re-consumed (retry re-pauses honestly);
 *   4. "allow always" (a USER-principal standing grant — what the Phase-1
 *      surface writes) allows WITHOUT any token (stage-9 standing consent,
 *      device-user-aware — the Phase-1 principal mismatch fixed);
 *   5. the SINGLE-GATE contract: exactly ONE admission audit row per call.
 */
@RunWith(RobolectricTestRunner::class)
class GovernedToolchainProductionPathTest {

    private lateinit var db: AppDatabase
    private lateinit var gate: HumanApprovalGate
    private lateinit var storage: FakeWorkspaceStorage
    private lateinit var adapter: GovernedCodingToolAdapter
    private lateinit var permissions: PermissionGrantService
    private val admissionAuditCount = AtomicInteger(0)

    private companion object {
        const val WS = "ws-governed"
        const val PROJECT = 42L
        const val DEVICE_USER = "user_local_device"
        const val EXEC = "exec-governed-1"
    }

    /** Minimal no-op TelemetryPort (project convention: hand-rolled fakes). */
    private class NoopTelemetryPort : TelemetryPort {
        override suspend fun record(sample: MetricSample) = Unit
        override suspend fun recordBatch(samples: List<MetricSample>) = Unit
        override suspend fun recordAudit(event: AuditEvent): Long = 0L
        override suspend fun recordTraceNode(node: ExecutionTraceNode) = Unit
        override fun snapshots(): Flow<List<MetricSnapshot>> = flowOf(emptyList())
        override fun dimensionSummaries(): Flow<List<DimensionSummary>> = flowOf(emptyList())
        override fun auditEvents(limit: Int): Flow<List<AuditEvent>> = flowOf(emptyList())
        override fun traceForExecution(executionId: String): Flow<List<ExecutionTraceNode>> = flowOf(emptyList())
        override fun recentTraceNodes(limit: Int): Flow<List<ExecutionTraceNode>> = flowOf(emptyList())
        override suspend fun snapshotByType(type: MetricType): List<MetricSnapshot> = emptyList()
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        gate = HumanApprovalGate(store = RoomHumanApprovalStore(db.humanApprovalRequestDao()))
        permissions = PermissionGrantService(
            permissionGrantDao = db.permissionGrantDao(),
            telemetryPort = NoopTelemetryPort(),
            deviceUserPrincipalId = { DEVICE_USER }
        )
        storage = FakeWorkspaceStorage()

        val admission = AdmissionControlService(
            toolDeclarations = ToolDeclarationResolver { name ->
                CodingToolchainService.declarations[name]
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
            auditSink = { _, _, _, _, _, decision, _, _, _ ->
                if (decision == "ALLOWED" || decision == "DENIED" || decision == "NEEDS_HUMAN_APPROVAL") {
                    admissionAuditCount.incrementAndGet()
                }
            },
            consentGrantPort = ConsentGrantPort { request ->
                permissions.checkCoveringDeviceUser(
                    request.principalType,
                    request.principalId,
                    SecurableResourceType.TOOL,
                    request.toolName,
                    Permission.EXECUTE,
                    request.workspaceId
                )
            },
            // Stage 6 (path policy) is fail-closed: a governed DELETE with a
            // projectId MUST resolve a workspace root (the production
            // resolver reads filesDir). Same recipe as GovernedTestFakes'
            // PipelineFactory: a lexical root per project id.
            workspaceRootResolver = { projectId -> "/data/workspaces/proj_$projectId" }
        )

        val toolchain = CodingToolchainService(admission = admission, workspaceStorage = storage)
        adapter = GovernedCodingToolAdapter(
            toolchain = toolchain,
            governedToolName = "delete_file",
            workspaceIdProvider = { WS },
            projectIdProvider = { PROJECT },
            fallbackPrincipalId = { DEVICE_USER },
            approvalTokenProvider = { executionId, toolName ->
                gate.findApprovedToken(executionId, toolName)
            }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun callDeleteFile(
        executionId: String = EXEC,
        path: String = "notes/old.md"
    ) = ExecutionScope(executionId, WS, PROJECT).let { scope ->
            kotlinx.coroutines.withContext(scope) {
                adapter.execute(
                    ToolInput(
                        toolName = "delete_file",
                        arguments = mapOf("path" to path),
                        executionId = executionId,
                        principalId = "agent_coder"
                    )
                )
            }
        }

    @Test
    fun `delete_file pauses for approval then executes once with the token`() = runBlocking {
        storage.seed(PROJECT, "notes/old.md", "old content")

        // 1. No consent → honest PAUSE with a durable request (the card renders it).
        val first = callDeleteFile()
        assertTrue(first is Outcome.Error)
        val denied = (first as Outcome.Error).failure as ToolFailure.SecurityDenied
        assertEquals("HUMAN_APPROVAL_PENDING", denied.ruleName)
        val pending = gate.pendingApprovals()
        assertEquals("GAP-02/2: the pause must persist the request the surface renders", 1, pending.size)

        // 2. The user approves once; the retry transports the token.
        gate.approve(pending.first().approvalId, DEVICE_USER)
        val second = callDeleteFile()
        assertTrue("the approved token must admit the governed tool: $second", second is Outcome.Success)
        assertEquals("the file must be REALLY deleted", false, storage.fileExists(PROJECT, "notes/old.md"))

        // 3. One-shot honesty: the consumed token is gone — a further call
        //    re-pauses with a NEW request (never re-consumes, never DENIES
        //    silently).
        val third = callDeleteFile()
        assertTrue(third is Outcome.Error)
        assertEquals("HUMAN_APPROVAL_PENDING", (third as Outcome.Error).let { (it.failure as ToolFailure.SecurityDenied).ruleName })
    }

    @Test
    fun `standing consent via the device-user grant allows without a token`() = runBlocking {
        storage.seed(PROJECT, "notes/stale.md", "stale")

        // "Allow always" — exactly what grantAlwaysForApproval writes: a
        // USER-principal, workspace-global EXECUTE grant.
        permissions.grant(
            principalType = PrincipalType.USER,
            principalId = DEVICE_USER,
            resourceType = SecurableResourceType.TOOL,
            resourceId = "delete_file",
            permission = Permission.EXECUTE,
            grantedBy = DEVICE_USER
        )

        val result = callDeleteFile("exec-standing", path = "notes/stale.md")
        assertTrue(
            "GAP-02/2: the USER grant must cover the AGENT call (device-user semantics): $result",
            result is Outcome.Success
        )
        assertEquals("the file must be REALLY deleted", false, storage.fileExists(PROJECT, "notes/stale.md"))
        assertEquals(
            "standing consent must not persist any approval request",
            0,
            gate.pendingApprovals().size
        )
    }

    @Test
    fun `single gate - exactly one admission audit row per governed call`() = runBlocking {
        storage.seed(PROJECT, "notes/audit.md", "x")
        permissions.grant(
            principalType = PrincipalType.USER,
            principalId = DEVICE_USER,
            resourceType = SecurableResourceType.TOOL,
            resourceId = "delete_file",
            permission = Permission.EXECUTE,
            grantedBy = DEVICE_USER
        )
        val before = admissionAuditCount.get()
        val result = callDeleteFile("exec-audit", path = "notes/audit.md")
        assertTrue(result is Outcome.Success)
        assertEquals(
            "GAP-02/2: the self-admitting tool must run the pipeline EXACTLY once (no double admission)",
            before + 1,
            admissionAuditCount.get()
        )
        assertNotNull(result)
    }
}
