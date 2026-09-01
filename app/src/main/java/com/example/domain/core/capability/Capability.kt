package com.example.domain.core.capability

/**
 * Functional taxonomy categories for AI-V0 capabilities.
 */
enum class CapabilityCategory(val code: String, val displayName: String) {
    REASONING_SYNTHESIS("reasoning_synthesis", "التفكير والتوليد والاستنتاج"),
    TOOL_OPERATION("tool_operation", "تشغيل الأدوات والبرمجيات"),
    SYSTEM_EXECUTION("system_execution", "التنفيذ النظامي والتشخيص المعزول"),
    STORAGE_IO("storage_io", "إدارة الملفات والتخزين المستديم"),
    KNOWLEDGE_RETRIEVAL("knowledge_retrieval", "استرجاع المعرفة والذاكرة الذكية"),
    NETWORK_IO("network_io", "الاتصال والبحث الشبكي الموثوق"),
    MEDIA_ANALYSIS("media_analysis", "معالجة وتحليل الوسائط والرؤية"),
    CODE_ENGINEERING("code_engineering", "هندسة وبناء الأكواد والبرمجيات"),
    SECURITY_GOVERNANCE("security_governance", "التدقيق الأمني والحوكمة"),
    PLANNING("planning", "التخطيط الاستراتيجي وهندسة المسارات")
}

/**
 * Network operational constraints required for a capability.
 */
enum class NetworkRequirement {
    LOCAL_ONLY,
    OFFLINE_ONLY,
    ONLINE_ONLY,
    HYBRID
}

/**
 * Physical execution locality of the capability provider.
 */
enum class Locality {
    LOCAL_ON_DEVICE,
    EDGE,
    REMOTE_CLOUD
}

/**
 * Side-effect impact classification for capability execution.
 */
enum class SideEffectClassification {
    READ_ONLY,
    IDEMPOTENT,
    STATE_MUTATION,
    EXTERNAL_SIDE_EFFECT,
    IRREVERSIBLE
}

/**
 * Fundamental capabilities recognized by the AI-V0 system.
 */
enum class CapabilityType(
    val code: String,
    val displayName: String,
    val category: CapabilityCategory,
    val defaultNetworkRequirement: NetworkRequirement = NetworkRequirement.LOCAL_ONLY,
    val defaultLocality: Locality = Locality.LOCAL_ON_DEVICE,
    val defaultSideEffect: SideEffectClassification = SideEffectClassification.READ_ONLY
) {
    LLM_GENERATION("llm_generation", "توليد النصوص والذكاء الاصطناعي", CapabilityCategory.REASONING_SYNTHESIS, NetworkRequirement.HYBRID, Locality.LOCAL_ON_DEVICE, SideEffectClassification.IDEMPOTENT),
    REASONING("reasoning", "التفكير المتسلسل والاستدلال المعقد", CapabilityCategory.REASONING_SYNTHESIS, NetworkRequirement.HYBRID, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    STREAMING("streaming", "البث التدفقي للأحداث والنصوص", CapabilityCategory.REASONING_SYNTHESIS, NetworkRequirement.HYBRID, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    VISION("vision", "تحليل وفهم الصور والوسائط المتعددة", CapabilityCategory.MEDIA_ANALYSIS, NetworkRequirement.HYBRID, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    SEARCH("search", "البث والاستعلام الشبكي الموثوق", CapabilityCategory.NETWORK_IO, NetworkRequirement.ONLINE_ONLY, Locality.REMOTE_CLOUD, SideEffectClassification.READ_ONLY),
    EMBEDDING("embedding", "تضمين النصوص بالمتجهات الدلالية", CapabilityCategory.KNOWLEDGE_RETRIEVAL, NetworkRequirement.HYBRID, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    VECTOR_STORE("vector_store", "تخزين ومطابقة المتجهات", CapabilityCategory.KNOWLEDGE_RETRIEVAL, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    MEMORY_RETRIEVAL("memory_retrieval", "استرجاع الذاكرة الذكية طويلة المدى", CapabilityCategory.KNOWLEDGE_RETRIEVAL, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    TOOL_EXECUTION("tool_execution", "تنفيذ الأدوات المبرمجة", CapabilityCategory.TOOL_OPERATION, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    SHELL_EXECUTION("shell_execution", "الأوامر الآمنة المعزولة", CapabilityCategory.SYSTEM_EXECUTION, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    SYSTEM_EXECUTION("system_execution", "التشخيص وفحص موارد النظام", CapabilityCategory.SYSTEM_EXECUTION, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    FILE_STORAGE("file_storage", "إدارة وتخزين ملفات المشروع", CapabilityCategory.STORAGE_IO, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    FILE_READ("file_read", "قراءة الملفات والمستندات", CapabilityCategory.STORAGE_IO, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    FILE_WRITE("file_write", "كتابة وحفظ الملفات", CapabilityCategory.STORAGE_IO, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    CODE_ANALYSIS("code_analysis", "التحليل الساكن ومراجعة الأكواد", CapabilityCategory.CODE_ENGINEERING, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    CODE_ENGINEERING("code_engineering", "توليد وهندسة هياكل البرمجيات", CapabilityCategory.CODE_ENGINEERING, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    SECURITY_AUDIT("security_audit", "الفحص الأمني واكتشاف الثغرات", CapabilityCategory.SECURITY_GOVERNANCE, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY),
    MCP_INVOCATION("mcp_invocation", "استدعاء بروتوكول MCP المعياري", CapabilityCategory.TOOL_OPERATION, NetworkRequirement.HYBRID, Locality.LOCAL_ON_DEVICE, SideEffectClassification.STATE_MUTATION),
    INTEGRATION_SYNC("integration_sync", "المزامنة مع الخدمات الخارجية", CapabilityCategory.NETWORK_IO, NetworkRequirement.ONLINE_ONLY, Locality.REMOTE_CLOUD, SideEffectClassification.STATE_MUTATION),
    HASH_COMPUTATION("hash_computation", "حساب البصمات والتحقق الرقمي", CapabilityCategory.SYSTEM_EXECUTION, NetworkRequirement.LOCAL_ONLY, Locality.LOCAL_ON_DEVICE, SideEffectClassification.READ_ONLY);

    companion object {
        fun fromCode(code: String): CapabilityType? = values().firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * Complete immutable semantic specification of a Capability.
 */
data class CapabilityDefinition(
    val type: CapabilityType,
    val category: CapabilityCategory = type.category,
    val description: String = type.displayName,
    val inputRequirements: List<String> = emptyList(),
    val outputGuarantees: List<String> = emptyList(),
    val prerequisites: List<CapabilityType> = emptyList(),
    val networkRequirement: NetworkRequirement = type.defaultNetworkRequirement,
    val locality: Locality = type.defaultLocality,
    val sideEffects: SideEffectClassification = type.defaultSideEffect,
    val requiredPermissions: List<String> = emptyList()
) {
    companion object {
        fun forType(type: CapabilityType): CapabilityDefinition = CapabilityDefinition(type = type)
    }
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
 * Truthful capability states as mandated by Rule 18.
 */
enum class CapabilityStatus(val code: String, val displayName: String) {
    CAPABILITY_SATISFIED("capability_satisfied", "القدرات مستوفاة بالكامل"),
    CAPABILITY_PARTIAL("capability_partial", "القدرات مستوفاة جزئياً"),
    CAPABILITY_MISSING("capability_missing", "توجد قدرات مطلوبة مفقودة"),
    CAPABILITY_UNAVAILABLE("capability_unavailable", "القدرات المطلوبة غير متاحة حالياً"),
    NO_CAPABLE_RESOURCE("no_capable_resource", "لا يوجد مورد قادر على تنفيذ المطلوب"),
    NO_CAPABLE_AGENT("no_capable_agent", "لا يوجد وكيل مؤهل للمهمة"),
    BLOCKED("blocked", "المسار محجوب بسبب قيود أمنية أو شبكية"),
    NEEDS_USER_INPUT("needs_user_input", "يتطلب تدخل وموافقة المستخدم")
}

/**
 * Runtime descriptor of a concrete capability exposed by an agent, tool, model, or provider.
 */
data class CapabilityDescriptor(
    val type: CapabilityType,
    val definition: CapabilityDefinition = CapabilityDefinition.forType(type),
    val state: CapabilityState = CapabilityState.AVAILABLE,
    val providerId: String,
    val resourceType: String = "RESOURCE", // TOOL, AGENT, MODEL, PROVIDER, MCP, SKILL
    val isLocal: Boolean = true,
    val reliabilityScore: Float = 1.0f,
    val degradedReason: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Requirement specification for executing a task or workflow step.
 */
data class CapabilityRequirement(
    val type: CapabilityType,
    val minimumState: CapabilityState = CapabilityState.DEGRADED,
    val preferredProviderId: String? = null,
    val isRequired: Boolean = true
)

/**
 * Level of capability match resulting from deterministic matching as mandated by Rule 2.
 */
enum class CapabilityMatchLevel {
    FULL_MATCH,
    PARTIAL_MATCH,
    NO_MATCH,
    CONFLICT,
    UNAVAILABLE
}

/**
 * Result of deterministic capability matching between requirements and a resource candidate.
 */
data class CapabilityMatchResult(
    val matchLevel: CapabilityMatchLevel,
    val satisfiedCapabilities: Set<CapabilityType>,
    val missingCapabilities: Set<CapabilityType>,
    val partiallySatisfiedCapabilities: Set<CapabilityType> = emptySet(),
    val conflictingCapabilities: Set<CapabilityType> = emptySet(),
    val prohibitedViolations: Set<CapabilityType> = emptySet(),
    val unavailableCapabilities: Set<CapabilityType> = emptySet(),
    val coverageRatio: Float = 0.0f,
    val satisfyingResources: Map<CapabilityType, List<String>> = emptyMap(),
    val matchRationale: String = ""
) {
    val isFullMatch: Boolean get() = matchLevel == CapabilityMatchLevel.FULL_MATCH
    val hasViolations: Boolean get() = prohibitedViolations.isNotEmpty() || conflictingCapabilities.isNotEmpty()
}

/**
 * Structural gap analysis identifying missing, satisfied, and conflicting capabilities.
 */
data class CapabilityGapAnalysis(
    val targetTaskId: String,
    val requiredCapabilities: Set<CapabilityType>,
    val optionalCapabilities: Set<CapabilityType> = emptySet(),
    val prohibitedCapabilities: Set<CapabilityType> = emptySet(),
    val satisfiedCapabilities: Set<CapabilityType> = emptySet(),
    val missingCapabilities: Set<CapabilityType> = emptySet(),
    val pendingCapabilities: Set<CapabilityType> = emptySet(),
    val partiallySatisfiedCapabilities: Set<CapabilityType> = emptySet(),
    val conflictingCapabilities: Set<CapabilityType> = emptySet(),
    val unavailableCapabilities: Set<CapabilityType> = emptySet(),
    val candidateResourcesForPending: Map<CapabilityType, List<CapabilityDescriptor>> = emptyMap(),
    val candidateResourcesForMissing: Map<CapabilityType, List<CapabilityDescriptor>> = candidateResourcesForPending,
    val status: CapabilityStatus = when {
        conflictingCapabilities.isNotEmpty() && satisfiedCapabilities.isEmpty() -> CapabilityStatus.BLOCKED
        unavailableCapabilities.isNotEmpty() && missingCapabilities.isNotEmpty() -> CapabilityStatus.CAPABILITY_UNAVAILABLE
        missingCapabilities.isNotEmpty() -> CapabilityStatus.NO_CAPABLE_RESOURCE
        satisfiedCapabilities.containsAll(requiredCapabilities) && requiredCapabilities.isNotEmpty() -> CapabilityStatus.CAPABILITY_SATISFIED
        satisfiedCapabilities.isNotEmpty() -> CapabilityStatus.CAPABILITY_PARTIAL
        requiredCapabilities.isEmpty() -> CapabilityStatus.CAPABILITY_SATISFIED
        else -> CapabilityStatus.CAPABILITY_PARTIAL
    },
    val gapReport: String = "",
    val isFullySatisfied: Boolean = requiredCapabilities.isEmpty() || (satisfiedCapabilities.containsAll(requiredCapabilities) && conflictingCapabilities.isEmpty())
)

