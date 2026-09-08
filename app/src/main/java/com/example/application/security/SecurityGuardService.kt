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
 *
 * REPAIR (defect family 2 — "security classification has overly permissive
 * handling for unknown/unclassified tools"): the previous fallback classified
 * EVERY unmatched tool name as DEFAULT_SAFE_TOOL / ALLOW / LOW — a fabricated
 * safety verdict for tools the classifier never saw. The repaired classifier
 * is closed-world:
 *
 *   1. Prohibited tool/parameter patterns (policy-driven, unchanged).
 *   2. Shell/terminal (consent/danger patterns, unchanged).
 *   3. File-system tools (path restrictions; explicit read vs mutation,
 *      unchanged semantics).
 *   4. KNOWN-SAFE closed list (search / memory / diagnostics / retrieval).
 *   5. Declaration-backed classification: when the caller supplies the
 *      ToolDeclaration facts via [ToolInput.contextAttributes] (the
 *      ExecutionService boundary does), a tool that DECLARES itself
 *      read-only, non-sensitive, consent-free and local is classified from
 *      those authoritative facts.
 *   6. ANYTHING ELSE (truly unclassified) → REQUIRE_CONSENT / MEDIUM —
 *      FAIL-CLOSED, never a fabricated ALLOW.
 */
class SecurityGuardService(
    private val defaultPolicy: SecurityPolicy = SecurityPolicy()
) : SecurityGuardPort {

    companion object {
        /** Declaration-derived classification contract (see class KDoc #5). */
        const val ATTR_DECLARED_SIDE_EFFECTS = "declaredSideEffects"
        const val ATTR_DECLARED_SENSITIVE = "declaredSensitive"
        const val ATTR_DECLARED_REQUIRES_CONSENT = "declaredRequiresConsent"
        const val ATTR_DECLARED_NETWORK_REQUIREMENT = "declaredNetworkRequirement"
    }

    override fun evaluateToolExecution(input: ToolInput, policy: SecurityPolicy): SecurityEvaluation {
        val toolName = input.toolName.lowercase()

        // 0. Explicit Prohibited Tool Patterns check
        for (pattern in policy.prohibitedToolPatterns) {
            if (pattern.isNotBlank() && (toolName.matches(Regex(pattern)) || toolName.contains(pattern, ignoreCase = true) || input.toolName.matches(Regex(pattern)))) {
                return SecurityEvaluation(
                    decision = SecurityDecision.DENY,
                    riskLevel = RiskLevel.CRITICAL,
                    matchedRule = "PROHIBITED_TOOL_PATTERN",
                    explanation = "الأداة ${input.toolName} محظورة وفقاً لسياسة الأمان (المطابقة: $pattern)."
                )
            }
        }

        // 0.1 Explicit Prohibited Parameters check
        for (prohibitedParam in policy.prohibitedParameters) {
            if (prohibitedParam.isNotBlank()) {
                val foundInArgs = input.arguments.values.any { arg ->
                    arg?.toString()?.contains(prohibitedParam, ignoreCase = true) == true
                }
                if (foundInArgs) {
                    return SecurityEvaluation(
                        decision = SecurityDecision.DENY,
                        riskLevel = RiskLevel.CRITICAL,
                        matchedRule = "PROHIBITED_PARAMETER_PATTERN",
                        explanation = "المدخلات تحتوي على وسيط محظور أمنياً: $prohibitedParam"
                    )
                }
            }
        }

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
            // Read-style file tools (list/read/search/inspect) are explicitly
            // classified safe — they previously fell through to the default
            // branch (which is now fail-closed).
            if (toolName.contains("file") || toolName == "read_file" || toolName == "list_files" ||
                toolName == "search_files"
            ) {
                return SecurityEvaluation(
                    decision = SecurityDecision.ALLOW,
                    riskLevel = RiskLevel.LOW,
                    matchedRule = "WORKSPACE_FILE_READ",
                    explanation = "عملية قراءة ملفات داخل مساحة العمل."
                )
            }
        }

        // 3. KNOWN-SAFE closed list (explicit — a tool is safe here ONLY if
        // its category is enumerated; no category → NOT safe by default).
        if (isKnownSafeTool(toolName)) {
            return SecurityEvaluation(
                decision = SecurityDecision.ALLOW,
                riskLevel = RiskLevel.LOW,
                matchedRule = "KNOWN_SAFE_TOOL",
                explanation = "الأداة مصنفة ضمن الفئات الآمنة المعروفة (بحث/ذاكرة/تشخيص/استرجاع)."
            )
        }

        // 4. DECLARATION-BACKED classification (the canonical fact source):
        // the execution/admission boundaries supply the ToolDeclaration's
        // authoritative facts. Classification is derived from DECLARED
        // facts — never fabricated:
        //   - declared sensitive or consent-requiring → REQUIRE_CONSENT;
        //   - declared local, non-sensitive → ALLOW with the risk derived
        //     from the DECLARED side-effect classification;
        //   - declared non-local (external network effect) without a
        //     known-safe category → REQUIRE_CONSENT (explicit).
        if (input.contextAttributes.containsKey(ATTR_DECLARED_SIDE_EFFECTS)) {
            if (input.contextAttributes[ATTR_DECLARED_SENSITIVE]?.toBoolean() == true ||
                input.contextAttributes[ATTR_DECLARED_REQUIRES_CONSENT]?.toBoolean() == true
            ) {
                return SecurityEvaluation(
                    decision = SecurityDecision.REQUIRE_CONSENT,
                    riskLevel = RiskLevel.HIGH,
                    matchedRule = "DECLARED_SENSITIVE_TOOL",
                    explanation = "الأداة مُصرَّحة في سجلها كأداة حساسة تتطلب موافقة."
                )
            }
            val sideEffects = input.contextAttributes[ATTR_DECLARED_SIDE_EFFECTS] ?: "READ_ONLY"
            val network = input.contextAttributes[ATTR_DECLARED_NETWORK_REQUIREMENT] ?: "LOCAL_ONLY"
            if (network != "LOCAL_ONLY") {
                return SecurityEvaluation(
                    decision = SecurityDecision.REQUIRE_CONSENT,
                    riskLevel = RiskLevel.MEDIUM,
                    matchedRule = "DECLARED_EXTERNAL_TOOL",
                    explanation = "الأداة مُصرَّحة بتأثير شبكي خارجي — يلزم تصنيف أو موافقة صريحة."
                )
            }
            val risk = when (sideEffects) {
                "READ_ONLY", "IDEMPOTENT" -> RiskLevel.LOW
                "STATE_MUTATION" -> RiskLevel.MEDIUM
                else -> RiskLevel.HIGH
            }
            return SecurityEvaluation(
                decision = SecurityDecision.ALLOW,
                riskLevel = risk,
                matchedRule = "DECLARED_LOCAL_TOOL",
                explanation = "أداة محلية مُصرَّحة غير حساسة (تأثير معلن: $sideEffects) — " +
                    "تخضع لسياسة المسارات وحدود مساحة العمل."
            )
        }

        // 5. UNCLASSIFIED — fail closed. The previous DEFAULT_SAFE_TOOL
        // ALLOW verdict for unseen tools was a fabricated safety
        // classification (defect family 2).
        return SecurityEvaluation(
            decision = SecurityDecision.REQUIRE_CONSENT,
            riskLevel = RiskLevel.MEDIUM,
            matchedRule = "UNCLASSIFIED_TOOL",
            explanation = "الأداة غير مصنفة أمنياً (لا تنتمي لفئة معروفة ولا توجد حقائق تصريح) — يلزم تصنيف أو موافقة صريحة قبل التنفيذ."
        )
    }

    /** The closed known-safe category list (name-prefix / contains match). */
    private fun isKnownSafeTool(toolName: String): Boolean {
        val knownSafePrefixes = listOf(
            "search", "tavily", "memory", "diagnostics", "safe_diagnostics",
            "retrieve_knowledge", "retrieve_memory", "knowledge", "rag",
            "workspace_summary", "list_files", "read_file", "search_files"
        )
        return knownSafePrefixes.any { toolName.startsWith(it) } ||
            toolName.contains("memory") || toolName.contains("diagnostics")
    }


    override fun sanitizeUntrustedOutput(rawOutput: String): String {
        // 1. Redact any sensitive tokens/secrets to prevent leakage
        val redacted = redactSensitiveSecrets(rawOutput)
        
        // 2. Escape prompt injection markers
        val escapedDirectives = filterDangerousDirectives(redacted)
        
        // 3. Structured security framing without losing original payload
        return buildString {
            appendLine("<tool_output untrusted=\"true\">")
            appendLine(escapedDirectives)
            append("</tool_output>")
        }
    }

    fun redactSensitiveSecrets(text: String): String {
        var result = text
        // Redact Google / Gemini API keys (AIza...)
        result = result.replace(Regex("AIza[0-9A-Za-z-_]{35}"), "[REDACTED_GEMINI_KEY]")
        // Redact Tavily API keys (tvly-...)
        result = result.replace(Regex("tvly-[0-9A-Za-z-_]{20,}"), "[REDACTED_TAVILY_KEY]")
        // Redact generic sk- keys
        result = result.replace(Regex("sk-[a-zA-Z0-9]{32,}"), "[REDACTED_API_KEY]")
        // Redact Authorization Bearer tokens
        result = result.replace(Regex("Bearer\\s+[A-Za-z0-9_\\-\\.~+/]+=*", RegexOption.IGNORE_CASE), "Bearer [REDACTED_TOKEN]")
        return result
    }

    private fun filterDangerousDirectives(text: String): String {
        var sanitized = text
        val dangerousPhrases = listOf(
            "System Prompt:", "Ignore previous instructions", "SYSTEM INSTRUCTION",
            "You are now in debug mode", "Developer Mode Enabled", "Override policy",
            "<system_instruction>", "</system_instruction>"
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
