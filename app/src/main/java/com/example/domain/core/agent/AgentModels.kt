package com.example.domain.core.agent

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.NetworkRequirement

/**
 * Unique identifier for an agent.
 */
@JvmInline
value class AgentId(val value: String)

/**
 * Functional roles assigned to specialized agents.
 */
enum class AgentRole(val displayName: String, val defaultSystemPrompt: String) {
    PLANNER(
        displayName = "المخطط الاستراتيجي",
        defaultSystemPrompt = "أنت المخطط الرئيسي. مهمتك تحليل الطلب وتفكيكه إلى خطة مهام منظمة ومحددة دون تنفيذ عشوائي."
    ),
    CODER(
        displayName = "المبرمج التنفيذي",
        defaultSystemPrompt = "أنت مهندس البرمجيات. مهمتك كتابة كود نظيف وقابل للصيانة والالتزام بالمعايير الصارمة."
    ),
    REVIEWER(
        displayName = "مراجع الجودة والأداء",
        defaultSystemPrompt = "أنت مراجع الكود والجودة. مهمتك فحص التغييرات واكتشاف الثغرات وتأكيد السلامة المعمارية."
    ),
    SECURITY_GUARD(
        displayName = "حارس الأمان والسياسات",
        defaultSystemPrompt = "أنت حارس الأمان. مهمتك تقييم العمليات الحساسة، منع حقن الأوامر، وتأكيد خصوصية البيانات."
    ),
    RESEARCHER(
        displayName = "الباحث ومحلل المعرفة",
        defaultSystemPrompt = "أنت الباحث المعرفي. مهمتك استخراج المعلومات الدقيقة من التوثيق ومصادر المعرفة الموثوقة."
    ),
    EXECUTOR(
        displayName = "منفذ المهام المباشر",
        defaultSystemPrompt = "أنت المنفذ الذاتي. مهمتك تنسيق تنفيذ الأدوات وجمع النتائج وتحديث الحالة التشغيلية."
    ),
    GENERAL_ASSISTANT(
        displayName = "المساعد الشامل",
        defaultSystemPrompt = "أنت المساعد الذكي لبيئة AI-V0. تقدم إجابات واضحة ومباشرة وتساعد في إدارة مساحة العمل."
    )
}

/**
 * Goal definition for an agent.
 */
data class AgentGoal(
    val description: String,
    val priority: Int = 1
)

/**
 * Static Identity and prompt persona of an agent.
 */
data class AgentIdentity(
    val id: AgentId,
    val name: String,
    val role: AgentRole,
    val description: String,
    val systemPrompt: String
)

/**
 * Budget tracking for an agent instance.
 */
data class AgentBudget(
    val maxTokens: Int = 30000,
    val usedTokens: Int = 0,
    val inFlightTokens: Int = 0
) {
    val remainingTokens: Int get() = (maxTokens - usedTokens - inFlightTokens).coerceAtLeast(0)
    val isDepleted: Boolean get() = remainingTokens <= 0
}

/**
 * Complete immutable definition of a configured agent.
 */
data class AgentDefinition(
    val identity: AgentIdentity,
    val allowedCapabilities: Set<CapabilityType>,
    val budget: AgentBudget,
    val goals: List<AgentGoal> = emptyList(),
    val enabled: Boolean = true,
    val networkRequirement: NetworkRequirement = NetworkRequirement.HYBRID,
    val locality: Locality = Locality.LOCAL_ON_DEVICE,
    val authorityLevel: String = "STANDARD",
    val workspaceScope: List<String> = listOf("default")
)

