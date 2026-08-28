package com.example.domain.ports.security

import com.example.domain.core.Outcome
import com.example.domain.core.security.SecurityEvaluation
import com.example.domain.core.security.SecurityFailure
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.tools.ToolInput

/**
 * Standard Port for the Security Guard Engine.
 *
 * Enforces policies strictly BEFORE any sensitive capability or tool is executed.
 */
interface SecurityGuardPort {
    /**
     * Evaluates a proposed tool execution or capability invocation against the active policy.
     */
    fun evaluateToolExecution(input: ToolInput, policy: SecurityPolicy): SecurityEvaluation

    /**
     * Sanitizes potentially untrusted output from third-party tools to prevent indirect prompt injections.
     */
    fun sanitizeUntrustedOutput(rawOutput: String): String

    /**
     * Validates if a proposed token expenditure complies with session budget limits.
     */
    suspend fun validateTokenBudget(requestedTokens: Int, sessionTotalTokens: Int, policy: SecurityPolicy): Outcome<Unit, SecurityFailure>
}
