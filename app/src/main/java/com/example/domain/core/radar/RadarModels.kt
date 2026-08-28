package com.example.domain.core.radar

/**
 * Ecosystem item categories identified by the Intelligence Radar.
 */
enum class RadarCategory(val code: String, val displayName: String) {
    MODEL_RELEASE("model_release", "إصدار نماذج ذكاء اصطناعي جديدة"),
    OPEN_SOURCE_REPO("open_source", "مشاريع ومستودعات مفتوحة المصدر"),
    MCP_ECOSYSTEM("mcp_server", "خوادم وبروتوكولات MCP"),
    AGENT_FRAMEWORK("agent_framework", "أطر عمل الوكلاء الذاتية"),
    TOOL_LIBRARY("tool_library", "مكتبات وأدوات برمجية"),
    RESEARCH_PAPER("research", "أوراق بحثية وتقنيات متقدمة"),
    PROVIDER_UPDATE("provider_update", "تحديثات المزودين والواجهات")
}

/**
 * Extracted capability profile from a discovered radar item.
 */
data class ExtractedCapabilityProfile(
    val suggestedCapabilityType: String,
    val suggestedIntegrationTarget: String, // "MODEL", "TOOL", "MCP_SERVER", "PLUGIN"
    val compatibilityScore: Float, // 0.0 to 1.0
    val requiresCloudAuth: Boolean,
    val isOfflineCompatible: Boolean,
    val estimatedIntegrationRisk: String = "LOW" // LOW, MEDIUM, HIGH
)

/**
 * Discovered Radar item representing an ecosystem event or technology development.
 */
data class RadarItem(
    val id: String,
    val title: String,
    val summary: String,
    val category: RadarCategory,
    val sourceUrl: String,
    val sourceName: String,
    val relevanceScore: Float, // 0.0 to 1.0
    val confidence: Float = 0.95f,
    val extractedCapability: ExtractedCapabilityProfile? = null,
    val tags: List<String> = emptyList(),
    val discoveredTimestampMs: Long = System.currentTimeMillis()
)
