package com.example.security

import com.example.application.security.SecurityGuardService
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.tools.ToolInput
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SecurityGuardServiceTest {

    private lateinit var securityGuard: SecurityGuardService
    private val policy = SecurityPolicy()

    @Before
    fun setup() {
        securityGuard = SecurityGuardService(policy)
    }

    @Test
    fun `test valid tool execution allowed`() {
        val eval = securityGuard.evaluateToolExecution(
            input = ToolInput(toolName = "workspace_file_tool", arguments = mapOf("action" to "list")),
            policy = policy
        )
        assertEquals(SecurityDecision.ALLOW, eval.decision)
    }

    @Test
    fun `test path traversal payload is blocked`() {
        val eval = securityGuard.evaluateToolExecution(
            input = ToolInput(toolName = "workspace_file_tool", arguments = mapOf("path" to "../../../../etc/passwd")),
            policy = policy
        )
        assertEquals(SecurityDecision.DENY, eval.decision)
    }

    @Test
    fun `test dangerous shell command blocked`() {
        val eval = securityGuard.evaluateToolExecution(
            input = ToolInput(toolName = "shell", arguments = mapOf("command" to "rm -rf /")),
            policy = policy.copy(allowShellCommands = true)
        )
        assertEquals(SecurityDecision.DENY, eval.decision)
    }
}
