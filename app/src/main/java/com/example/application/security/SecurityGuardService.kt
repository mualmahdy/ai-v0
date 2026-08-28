package com.example.application.security

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.security.RiskLevel
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityEvaluation
import com.example.domain.core.security.SecurityFailure
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.tools.ToolInput
import com.example.domain.ports.security.SecurityGuardPort

/**
 * Standard Application Service for Pre-Execution Security & Budget Guardrails.
 */
class SecurityGuardService(
    private val defaultPolicy: SecurityPolicy = SecurityPolicy()
) : SecurityGuardPort {

    override fun evaluateToolExecution(input: ToolInput, policy: SecurityPolicy): SecurityEvaluation {
        val toolName = input.toolName.lowercase()

        // 1. Check Shell / Terminal execution policy
        if (toolName == "shell" || toolName == "terminal" || toolName == "safeshell") {
            if (!policy.allowShellCommands) {
                return SecurityEvaluation(
                    decision = SecurityDecision.DENY,
                    riskLevel = RiskLevel.HIGH,
                    matchedRule = "POLICY_SHELL_FORBIDDEN",
                    explanation = "تنفيذ أوامر المحطة الطرفية غير مسموح وفقاً للسياسة الأمنية النشطة."
                )
            }
            val command = input.arguments["command"]?.toString() ?: ""
            if (containsDangerousPatterns(command)) {
                return SecurityEvaluation(
                    decision = SecurityDecision.DENY,
                    riskLevel = RiskLevel.CRITICAL,
                    matchedRule = "DANGEROUS_SHELL_COMMAND",
                    explanation = "الأمر يحتوي على أنماط تشغيلية خطرة تم حظرها قطعيًا."
                )
            }
            return SecurityEvaluation(
                decision = SecurityDecision.REQUIRE_CONSENT,
                riskLevel = RiskLevel.HIGH,
                matchedRule = "SHELL_EXECUTION_CONSENT",
                explanation = "تنفيذ الأوامر يتطلب موافقة المستخدم الصريحة.",
                requiredConsentPrompt = "هل توافق على تشغيل الأمر: $command؟"
            )
        }

        // 2. Check File System path restrictions
        if (toolName.contains("file") || toolName == "read_file" || toolName == "write_file" || toolName == "delete_file") {
            val path = input.arguments["path"]?.toString() ?: ""
            for (restricted in policy.restrictedPaths) {
                if (path.startsWith(restricted) || path.contains("..")) {
                    return SecurityEvaluation(
                        decision = SecurityDecision.DENY,
                        riskLevel = RiskLevel.HIGH,
                        matchedRule = "RESTRICTED_PATH_ACCESS",
                        explanation = "الوصول للمسار $path محظور أمنياً خارج نطاق مساحة العمل."
                    )
                }
            }

            if (toolName == "delete_file" || toolName == "write_file") {
                return SecurityEvaluation(
                    decision = SecurityDecision.ALLOW,
                    riskLevel = RiskLevel.MEDIUM,
                    matchedRule = "WORKSPACE_FILE_MUTATION",
                    explanation = "عملية تعديل ملف آمنة داخل مساحة العمل."
                )
            }
        }

        // 3. Default Safe Tools (Search, Memory, Diagnostics)
        return SecurityEvaluation(
            decision = SecurityDecision.ALLOW,
            riskLevel = RiskLevel.LOW,
            matchedRule = "DEFAULT_SAFE_TOOL",
            explanation = "الأداة مصنفة كأداة آمنة وغير حساسة."
        )
    }

    override fun sanitizeUntrustedOutput(rawOutput: String): String {
        // Prevents indirect prompt injection by escaping system instruction triggers
        var sanitized = rawOutput
        val dangerousPhrases = listOf(
            "System Prompt:", "Ignore previous instructions", "SYSTEM INSTRUCTION",
            "You are now in debug mode", "Developer Mode Enabled", "Override policy"
        )
        for (phrase in dangerousPhrases) {
            if (sanitized.contains(phrase, ignoreCase = true)) {
                sanitized = sanitized.replace(phrase, "[FILTERED_SECURITY_DIRECTIVE]", ignoreCase = true)
            }
        }
        return sanitized
    }

    override suspend fun validateTokenBudget(
        requestedTokens: Int,
        sessionTotalTokens: Int,
        policy: SecurityPolicy
    ): Outcome<Unit, SecurityFailure> {
        val totalAfter = sessionTotalTokens + requestedTokens
        return if (totalAfter > policy.maxSingleTaskTokenBudget * 5) {
            Outcome.Error(
                failure = SecurityFailure.BudgetExceeded(requestedTokens, policy.maxSingleTaskTokenBudget * 5),
                diagnosticMessage = "تم تجاوز السقف الإجمالي لميزانية التوكنز في هذه الجلسة."
            )
        } else if (totalAfter > policy.maxSingleTaskTokenBudget * 4) {
            Outcome.Degraded(
                partialValue = Unit,
                reason = DegradedReason.BUDGET_APPROACHING_LIMIT,
                diagnosticMessage = "استهلاك التوكنز اقترب من الحد الأقصى للجلسة.",
                metadata = OutcomeMetadata(tokensConsumed = totalAfter)
            )
        } else {
            Outcome.Success(Unit, OutcomeMetadata(tokensConsumed = totalAfter))
        }
    }

    private fun containsDangerousPatterns(command: String): Boolean {
        val lower = command.lowercase()
        val dangerousKeywords = listOf("rm -rf /", "mkfs", "dd if=", ":(){ :|:& };:", "chmod 777 /", "su -", "sudo ")
        return dangerousKeywords.any { lower.contains(it) }
    }
}
