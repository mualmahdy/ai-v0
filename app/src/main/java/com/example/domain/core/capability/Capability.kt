package com.example.domain.core.capability

/**
 * Fundamental capabilities recognized by the AI-V0 system.
 */
enum class CapabilityType(val code: String, val displayName: String) {
    LLM_GENERATION("llm_generation", "توليد النصوص والذكاء الاصطناعي"),
    STREAMING("streaming", "البث التدفقي للأحداث والنصوص"),
    SEARCH("search", "البث والاستعلام الشبكي الموثوق"),
    EMBEDDING("embedding", "تضمين النصوص بالمتجهات الدلالية"),
    VECTOR_STORE("vector_store", "تخزين ومطابقة المتجهات"),
    TOOL_EXECUTION("tool_execution", "تنفيذ الأدوات المبرمجة"),
    SHELL_EXECUTION("shell_execution", "الأوامر الآمنة المعزولة"),
    FILE_STORAGE("file_storage", "إدارة وتخزين ملفات المشروع"),
    MEMORY_RETRIEVAL("memory_retrieval", "استرجاع الذاكرة الذكية طويلة المدى")
}

/**
 * Operational state of a capability at runtime.
 */
enum class CapabilityState {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE
}

/**
 * Runtime descriptor of a capability.
 */
data class CapabilityDescriptor(
    val type: CapabilityType,
    val state: CapabilityState,
    val providerId: String,
    val isLocal: Boolean,
    val degradedReason: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Requirement specification for executing a task or workflow step.
 */
data class CapabilityRequirement(
    val type: CapabilityType,
    val minimumState: CapabilityState = CapabilityState.DEGRADED,
    val preferredProviderId: String? = null
)
