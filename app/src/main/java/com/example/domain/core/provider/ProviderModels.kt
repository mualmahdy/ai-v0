package com.example.domain.core.provider

/**
 * Provider categories supported by the platform.
 */
enum class ProviderType(val code: String, val displayName: String) {
    LLM("llm", "نماذج اللغة الكبيرة (LLM)"),
    EMBEDDINGS("embeddings", "نماذج التضمين الدلالي (Embeddings)"),
    IMAGE_GENERATION("image_gen", "توليد الصور والرؤية (Image Gen)"),
    SPEECH("speech", "معالجة الصوت والكلام (Speech/TTS)"),
    SEARCH("search", "محركات البحث الشبكي (Web Search)"),
    STORAGE("storage", "تخزين الملفات والمستندات (Storage)"),
    MCP_BRIDGE("mcp_bridge", "جسر خوادم سياق النماذج (MCP Bridge)")
}

/**
 * Health status of a provider or model.
 */
enum class HealthStatus(val code: String, val displayName: String) {
    HEALTHY("healthy", "يعمل بكفاءة (Healthy)"),
    DEGRADED("degraded", "يعمل بأداء منخفض (Degraded)"),
    UNAVAILABLE("unavailable", "غير متاح حالياً (Unavailable)"),
    UNKNOWN("unknown", "الحالة غير محددة (Unknown)")
}

/**
 * Descriptor of a registered AI capability provider.
 */
data class ProviderDescriptor(
    val id: String,
    val name: String,
    val type: ProviderType,
    val isConfigured: Boolean,
    val isLocal: Boolean,
    val health: HealthStatus = HealthStatus.UNKNOWN,
    val endpointUrl: String? = null,
    val supportedCapabilities: List<String> = emptyList(),
    val rateLimitRequestsPerMin: Int? = null,
    val lastDiscoveredTimestampMs: Long? = null,
    val provenance: String = "REGISTERED_SERVICE"
)
