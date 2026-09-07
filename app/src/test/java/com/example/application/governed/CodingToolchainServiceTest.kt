package com.example.application.governed

import com.example.domain.core.Outcome
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.security.governance.PrincipalType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * CodingToolchainServiceTest — Phase 1
 * ============================================================================
 *
 * End-to-end governed execution: a tool call ONLY executes when the
 * admission pipeline explicitly allows it; every denial is surfaced as a
 * structured ToolFailure; the file primitives are REAL (in-memory storage
 * fake mirrors production port semantics).
 */
class CodingToolchainServiceTest {

    private fun buildPipeline(): Pair<CodingToolchainService, GovernedPipelineFactory.PipelineParts> {
        val parts = GovernedPipelineFactory.build()
        val chain = CodingToolchainService(
            admission = parts.admission,
            workspaceStorage = parts.storage
        )
        return chain to parts
    }

    @Test
    fun `read_file executes after passing admission`() = runBlocking {
        val (chain, parts) = buildPipeline()
        parts.storage.seed(1L, "src/Main.kt", "fun main() {}")

        val outcome = chain.executeTool(
            executionId = "exec-1",
            principalId = "agent-1",
            toolName = "read_file",
            arguments = mapOf("path" to "src/Main.kt"),
            projectId = 1L,
            workspaceId = "ws-1"
        )
        assertTrue(outcome is Outcome.Success)
        assertEquals("fun main() {}", (outcome as Outcome.Success).value.content)
    }

    @Test
    fun `denied admission means NO execution — no storage touch`() = runBlocking {
        val (chain, parts) = buildPipeline()
        parts.storage.seed(1L, "src/Secret.kt", "class Secret")
        // Seed the deny: traversal path — admission must refuse.
        val outcome = chain.executeTool(
            executionId = "exec-1",
            principalId = "agent-1",
            toolName = "read_file",
            arguments = mapOf("path" to "../../../etc/passwd"),
            projectId = 1L,
            workspaceId = "ws-1"
        )
        assertTrue(outcome is Outcome.Error)
        val failure = (outcome as Outcome.Error).failure
        assertTrue(failure is ToolFailure.SecurityDenied)
        assertTrue((failure as ToolFailure.SecurityDenied).ruleName.isNotEmpty())
    }

    @Test
    fun `apply_patch performs a real atomic modification`() = runBlocking {
        val (chain, parts) = buildPipeline()
        parts.storage.seed(1L, "src/App.kt", "val version = 1")

        val outcome = chain.executeTool(
            executionId = "exec-1",
            principalId = "agent-1",
            toolName = "apply_patch",
            arguments = mapOf(
                "path" to "src/App.kt",
                "hunks" to listOf(mapOf("expect" to "version = 1", "replaceWith" to "version = 2"))
            ),
            projectId = 1L,
            workspaceId = "ws-1"
        )
        // apply_patch is MEDIUM (non-destructive, reversible) — admissible
        // WITHOUT human approval; governance still runs all 10 stages.
        assertTrue(outcome is Outcome.Success)
        // Verify the REAL atomic modification landed:
        assertEquals(
            "val version = 2",
            parts.storage.readFile(1L, "src/App.kt").let { (it as Outcome.Success).value }
        )
        // Audit proves the admission happened (not a bypass).
        assertEquals(1, parts.audit.records.size)
        assertEquals("ADMISSION_ALLOWED", parts.audit.records[0].action)
    }

    @Test
    fun `write_file with stale hash is refused — no silent overwrite`() = runBlocking {
        val (chain, parts) = buildPipeline()
        parts.storage.seed(1L, "src/Live.kt", "content v1")

        val staleHash = com.example.domain.core.tools.patch.FilePatchEngine.sha256Hex("content v0")

        // Approve first (MEDIUM risk).
        val approval = parts.approvalGate.requestApproval(
            "exec-3", "write_file", "MEDIUM", "كتابة ملف", "تحديث"
        )
        parts.approvalGate.approve(approval.approvalId, "user:owner")

        val outcome = chain.executeTool(
            executionId = "exec-3",
            principalId = "agent-1",
            toolName = "write_file",
            arguments = mapOf(
                "path" to "src/Live.kt",
                "content" to "content v2",
                "expected_hash" to staleHash
            ),
            projectId = 1L,
            workspaceId = "ws-1",
            approvalTokenId = approval.approvalId
        )
        assertTrue(outcome is Outcome.Error)
        val failure = (outcome as Outcome.Error).failure
        assertTrue(failure is ToolFailure.SecurityDenied)
        assertEquals("OPTIMISTIC_CONCURRENCY_CONFLICT", (failure as ToolFailure.SecurityDenied).ruleName)
        // File NOT modified:
        assertEquals("content v1", parts.storage.readFile(1L, "src/Live.kt").let { (it as Outcome.Success).value })
    }

    @Test
    fun `run_code NEVER executes on Android — honest capability refusal`() = runBlocking {
        val (chain, parts) = buildPipeline()
        val approval = parts.approvalGate.requestApproval(
            "exec-4", "run_code", "CRITICAL", "تنفيذ شيفرة", "اختبار"
        )
        parts.approvalGate.approve(approval.approvalId, "user:owner")

        val outcome = chain.executeTool(
            executionId = "exec-4",
            principalId = "agent-1",
            toolName = "run_code",
            arguments = mapOf("language" to "kotlin", "code" to "println(42)"),
            projectId = 1L,
            workspaceId = "ws-1",
            approvalTokenId = approval.approvalId
        )
        // Even with human approval, sandbox admission refuses honestly.
        assertTrue(outcome is Outcome.Error)
        val failure = (outcome as Outcome.Error).failure
        assertTrue(failure is ToolFailure.SecurityDenied)
        assertTrue(failure.toString().contains("SANDBOX"))
    }

    @Test
    fun `search_files performs real content search`() = runBlocking {
        val (chain, parts) = buildPipeline()
        parts.storage.seed(1L, "src/A.kt", "fun alpha() {}\nfun beta() {}")
        parts.storage.seed(1L, "src/B.kt", "fun gamma() {}")

        val outcome = chain.executeTool(
            executionId = "exec-5",
            principalId = "agent-1",
            toolName = "search_files",
            arguments = mapOf("query" to "beta"),
            projectId = 1L,
            workspaceId = "ws-1"
        )
        assertTrue(outcome is Outcome.Success)
        val content = (outcome as Outcome.Success).value.content
        assertTrue(content.contains("src/A.kt:2"))
    }

    @Test
    fun `create_file then read_file roundtrip via governance`() = runBlocking {
        val (chain, parts) = buildPipeline()

        val approval = parts.approvalGate.requestApproval(
            "exec-6", "create_file", "MEDIUM", "إنشاء ملف", "ملف جديد"
        )
        parts.approvalGate.approve(approval.approvalId, "user:owner")

        val created = chain.executeTool(
            executionId = "exec-6",
            principalId = "agent-1",
            toolName = "create_file",
            arguments = mapOf("path" to "src/New.kt", "content" to "// new"),
            projectId = 1L,
            workspaceId = "ws-1",
            approvalTokenId = approval.approvalId
        )
        assertTrue(created is Outcome.Success)

        val read = chain.executeTool(
            executionId = "exec-7",
            principalId = "agent-1",
            toolName = "read_file",
            arguments = mapOf("path" to "src/New.kt"),
            projectId = 1L,
            workspaceId = "ws-1"
        )
        assertEquals("// new", (read as Outcome.Success).value.content)
    }

    @Test
    fun `executions are attributed in the audit trail`() = runBlocking {
        val (chain, parts) = buildPipeline()
        parts.storage.seed(1L, "src/A.kt", "x")
        chain.executeTool(
            "exec-8", "agent-9", PrincipalType.EXTENSION, "read_file",
            mapOf("path" to "src/A.kt"), 1L, "ws-1"
        )
        assertEquals(1, parts.audit.records.size)
        assertEquals("EXTENSION:agent-9", parts.audit.records[0].actor)
        assertEquals("read_file", parts.audit.records[0].resourceId)
    }
}
