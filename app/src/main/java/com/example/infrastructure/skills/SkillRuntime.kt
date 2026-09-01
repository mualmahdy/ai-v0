package com.example.infrastructure.skills

import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.domain.ports.tools.ToolPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executable Skills Runtime implementing real capability execution.
 */
interface ExecutableSkill {
    val skillId: String
    val providedCapabilities: Set<CapabilityType> get() = setOf(CapabilityType.TOOL_EXECUTION)
    suspend fun execute(parameters: Map<String, Any?>): Outcome<String, String>
}

/**
 * Clean Architecture Scaffolding Skill: Generates real directory structure and Kotlin templates in the Workspace.
 */
class CleanArchitectureScaffolderSkill(
    private val storagePort: WorkspaceStoragePort,
    private val defaultProjectId: Long = 1L
) : ExecutableSkill {
    override val skillId: String = "skill_clean_arch_scaffold"
    override val providedCapabilities: Set<CapabilityType> = setOf(
        CapabilityType.CODE_ENGINEERING,
        CapabilityType.FILE_STORAGE,
        CapabilityType.FILE_WRITE,
        CapabilityType.TOOL_EXECUTION
    )

    override suspend fun execute(parameters: Map<String, Any?>): Outcome<String, String> = withContext(Dispatchers.IO) {

        val moduleName = parameters["moduleName"]?.toString()?.ifBlank { "feature_module" } ?: "feature_module"
        val basePath = "src/main/java/com/example/$moduleName"

        val filesToCreate = mapOf(
            "$basePath/domain/models/DomainModel.kt" to """
                package com.example.$moduleName.domain.models

                data class ${moduleName.replaceFirstChar { it.uppercase() }}Item(
                    val id: String,
                    val name: String,
                    val timestampEpochMs: Long = System.currentTimeMillis()
                )
            """.trimIndent(),

            "$basePath/domain/ports/${moduleName.replaceFirstChar { it.uppercase() }}RepositoryPort.kt" to """
                package com.example.$moduleName.domain.ports

                import com.example.domain.core.Outcome
                import com.example.$moduleName.domain.models.${moduleName.replaceFirstChar { it.uppercase() }}Item

                interface ${moduleName.replaceFirstChar { it.uppercase() }}RepositoryPort {
                    suspend fun fetchItems(): Outcome<List<${moduleName.replaceFirstChar { it.uppercase() }}Item>, String>
                }
            """.trimIndent(),

            "$basePath/application/usecases/Get${moduleName.replaceFirstChar { it.uppercase() }}UseCase.kt" to """
                package com.example.$moduleName.application.usecases

                import com.example.$moduleName.domain.ports.${moduleName.replaceFirstChar { it.uppercase() }}RepositoryPort

                class Get${moduleName.replaceFirstChar { it.uppercase() }}UseCase(
                    private val repository: ${moduleName.replaceFirstChar { it.uppercase() }}RepositoryPort
                ) {
                    suspend operator fun invoke() = repository.fetchItems()
                }
            """.trimIndent()
        )

        var createdCount = 0
        for ((path, content) in filesToCreate) {
            when (storagePort.writeFile(defaultProjectId, path, content)) {
                is Outcome.Success -> createdCount++
                else -> Unit
            }
        }

        Outcome.Success("تم بنجاح إنشاء هيكل Clean Architecture للوحدة '$moduleName' ($createdCount ملفات تم حفظها في مساحة العمل).")
    }
}

/**
 * Security Auditor Skill: Real static analysis for secrets, unsafe operations, and policy violations.
 */
class SecurityAuditorSkill : ExecutableSkill {
    override val skillId: String = "skill_code_review_security"
    override val providedCapabilities: Set<CapabilityType> = setOf(
        CapabilityType.SECURITY_AUDIT,
        CapabilityType.CODE_ANALYSIS,
        CapabilityType.TOOL_EXECUTION
    )

    override suspend fun execute(parameters: Map<String, Any?>): Outcome<String, String> = withContext(Dispatchers.Default) {

        val codeOrText = parameters["content"]?.toString() ?: ""
        if (codeOrText.isBlank()) {
            return@withContext Outcome.Error("المحتوى المراد تدقيقه أمنياً فارغ.")
        }

        val findings = mutableListOf<String>()
        var riskScore = 0

        // 1. API Key leakage checks
        val secretPatterns = listOf(
            Regex("""(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*["'][A-Za-z0-9_\-]{8,}["']""") to "كشف محتمل لمفتاح سري أو كلمة مرور (Hardcoded Secret)",
            Regex("""ghp_[A-Za-z0-9]{20,}""") to "مفتاح شخصي مسرب لمنصة GitHub (Personal Access Token)",
            Regex("""AIza[0-9A-Za-z-_]{35}""") to "مفتاح مسرب لخدمات Google Cloud / Firebase API Key",
            Regex("""sk-[A-Za-z0-9]{20,}""") to "مفتاح مسرب لمنصات OpenAI / LLM Endpoints"
        )

        for ((regex, description) in secretPatterns) {
            if (regex.containsMatchIn(codeOrText)) {
                findings.add("⚠️ [حرج] $description")
                riskScore += 40
            }
        }

        // 2. Dangerous functions
        if (codeOrText.contains("Runtime.getRuntime().exec(") || codeOrText.contains("ProcessBuilder(")) {
            findings.add("⚠️ [متوسط] استدعاء أوامر نظام التشغيل مباشرة (Command Execution Risk)")
            riskScore += 25
        }

        if (codeOrText.contains("ALLOW_ALL_HOSTNAME_VERIFIER") || codeOrText.contains("TrustAllCerts")) {
            findings.add("⚠️ [حرج] تعطيل التحقق من شهادات SSL/TLS (Insecure TrustManager)")
            riskScore += 35
        }

        val summary = buildString {
            appendLine("=== تقرير التدقيق الأمني (Security Audit Report) ===")
            appendLine("مستوى الخطورة الإجمالي: $riskScore / 100")
            if (findings.isEmpty()) {
                appendLine("✅ لم يتم اكتشاف ثغرات أمنية واضحة أو مفاتيح مسربة في الشيفرة المفحوصة.")
            } else {
                appendLine("الملاحظات المكتشفة:")
                findings.forEach { appendLine("- $it") }
            }
        }

        Outcome.Success(summary)
    }
}
