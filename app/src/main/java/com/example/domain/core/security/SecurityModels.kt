package com.example.domain.core.security

/**
 * Security decision rendered prior to sensitive execution.
 */
enum class SecurityDecision {
    ALLOW,
    DENY,
    REQUIRE_CONSENT,
    DEGRADE
}

/**
 * Risk classification for actions.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Declared security policy enforced on tasks, workflows, and tool calls.
 */
data class SecurityPolicy(
    val allowShellCommands: Boolean = false,
    val allowExternalNetwork: Boolean = true,
    val restrictedPaths: Set<String> = setOf("/system", "/data", "/proc", "/sys", ".."),
    val maxSingleTaskTokenBudget: Int = 16000,
    val sanitizeUntrustedToolOutputs: Boolean = true
)

/**
 * Evaluation outcome produced by the Security Guard.
 */
data class SecurityEvaluation(
    val decision: SecurityDecision,
    val riskLevel: RiskLevel,
    val matchedRule: String? = null,
    val explanation: String,
    val requiredConsentPrompt: String? = null
)

/**
 * Security failures preventing execution.
 */
sealed interface SecurityFailure {
    data class ActionBlocked(val rule: String, val reason: String) : SecurityFailure
    data class ConsentRejected(val action: String) : SecurityFailure
    data class DangerousPathDetected(val path: String) : SecurityFailure
    data class PromptInjectionSuspected(val source: String, val details: String) : SecurityFailure
    data class BudgetExceeded(val requestedTokens: Int, val maxAllowed: Int) : SecurityFailure
}
