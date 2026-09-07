package com.example.gapclosure

import com.example.application.execution.ExecutionContextCodec
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.execution.CanonicalExecutionContext
import com.example.domain.core.task.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * CanonicalExecutionContextTest — gap-closure P1-01 / P1-02 / P1-03
 * ============================================================================
 *
 * Proves the canonical context round-trips durably (stable executionId,
 * pinned workspace/agent/model, attempt counter) — the identity contract
 * the resume path depends on.
 */
class CanonicalExecutionContextTest {

    private fun sampleContext() = CanonicalExecutionContext(
        executionId = "exec_abc123",
        taskId = TaskId("task_1"),
        workspaceId = "ws_research",
        projectId = 42L,
        agentId = AgentId("code_craftsman"),
        agentRole = AgentRole.CODER,
        modelId = "gemini-2.5-pro",
        parentTaskId = null,
        delegationDepth = 0,
        attempt = 1,
        startedAtEpochMs = 1_700_000_000_000L
    )

    @Test
    fun `encode-decode round-trips every field exactly`() {
        val original = sampleContext()
        val decoded = ExecutionContextCodec.decode(ExecutionContextCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(original.executionId, decoded!!.executionId)
        assertEquals(original.taskId, decoded.taskId)
        assertEquals(original.workspaceId, decoded.workspaceId)
        assertEquals(original.projectId, decoded.projectId)
        assertEquals(original.agentId, decoded.agentId)
        assertEquals(original.agentRole, decoded.agentRole)
        assertEquals(original.modelId, decoded.modelId)
        assertEquals(original.delegationDepth, decoded.delegationDepth)
        assertEquals(original.attempt, decoded.attempt)
        assertEquals(original.startedAtEpochMs, decoded.startedAtEpochMs)
    }

    @Test
    fun `nullable fields survive a null round-trip`() {
        val minimal = CanonicalExecutionContext(
            executionId = "exec_min",
            taskId = TaskId("t"),
            workspaceId = "default",
            agentId = AgentId("agent_general"),
            agentRole = AgentRole.GENERAL_ASSISTANT
        )
        val decoded = ExecutionContextCodec.decode(ExecutionContextCodec.encode(minimal))
        assertNotNull(decoded)
        assertNull(decoded!!.projectId)
        assertNull(decoded.modelId)
        assertNull(decoded.parentTaskId)
        assertEquals(1, decoded.attempt)
    }

    @Test
    fun `resume increments attempt but keeps the SAME execution identity`() {
        val first = sampleContext()
        val resumed = first.nextAttempt()

        assertEquals("P1-03: executionId is stable across resume", first.executionId, resumed.executionId)
        assertEquals("Pinned workspace survives resume", first.workspaceId, resumed.workspaceId)
        assertEquals("Pinned agent survives resume", first.agentId, resumed.agentId)
        assertEquals("Pinned role survives resume", first.agentRole, resumed.agentRole)
        assertEquals(2, resumed.attempt)

        val decoded = ExecutionContextCodec.decode(ExecutionContextCodec.encode(resumed))
        assertEquals(first.executionId, decoded!!.executionId)
        assertEquals(2, decoded.attempt)
    }

    @Test
    fun `decode is honest about null and malformed input`() {
        assertNull(ExecutionContextCodec.decode(null))
        assertNull(ExecutionContextCodec.decode(""))
        assertNull(ExecutionContextCodec.decode("not-json{{"))
    }

    @Test
    fun `context construction rejects blank identity (fail-closed contract)`() {
        var rejected = false
        try {
            CanonicalExecutionContext(
                executionId = "",
                taskId = TaskId("t"),
                workspaceId = "ws",
                agentId = AgentId("a"),
                agentRole = AgentRole.CODER
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Blank executionId must be rejected", rejected)

        rejected = false
        try {
            CanonicalExecutionContext(
                executionId = "exec",
                taskId = TaskId("t"),
                workspaceId = "",
                agentId = AgentId("a"),
                agentRole = AgentRole.CODER
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Blank workspaceId must be rejected (no implicit default scope)", rejected)
    }
}
