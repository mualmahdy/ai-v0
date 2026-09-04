package com.example.domain.core.provider

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.offering.OfferingCatalog
import com.example.domain.core.provider.offering.OfferingType
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * AI-V0 Ultimate Generalized Provider Architecture
 * ============================================================================
 *
 * Replaces monolithic ProviderConfiguration with the authoritative chain:
 *
 * Provider
 *   ↓
 * Service
 *   ↓
 * Protocol
 *   ↓
 * Configuration
 *   ↓
 * Adapter
 *   ↓
 * Discovery
 *   ↓
 * Offering Catalog
 *   ↓
 * Resource
 *   ↓
 * Registry
 *   ↓
 * CapabilityGraph
 *   ↓
 * DecisionService
 *   ↓
 * DecisionRecord
 *   ↓
 * ExecutionService
 */

/**
 * 1. PROVIDER
 * Authoritative top-level entity representing a vendor, host, or local subsystem.
 */
data class Provider(
    val id: String,
    val name: String,
    val description: String = "",
    val websiteUrl: String? = null,
    val isLocal: Boolean = false,
    val isEnabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * 2. SERVICE
 * Functional category of service exposed by a Provider.
 */
enum class ServiceType(val code: String, val displayName: String) {
    LLM("llm", "نماذج التوليد واللغة (LLM)"),
    EMBEDDING("embedding", "نماذج التضمين الدلالي (Embedding)"),
    SEARCH("search", "محركات البحث الشبكي (Web Search)"),
    IMAGE_GENERATION("image_gen", "توليد الصور والرؤية (Image Gen)"),
    SPEECH("speech", "معالجة الصوت والكلام (Speech)"),
    VECTOR_STORE("vector_store", "مستودعات المتجهات (Vector Store)"),
    MCP("mcp", "خوادم MCP (Model Context Protocol)")
}

data class ProviderService(
    val id: String,
    val providerId: String,
    val name: String,
    val serviceType: ServiceType,
    val description: String = "",
    val supportedProtocolIds: List<String> = emptyList(),
    val isEnabled: Boolean = true
)

/**
 * 3. PROTOCOL
 * Wire-level protocol or transport contract for communicating with a service.
 */
enum class ProtocolWireFormat(val code: String) {
    REST_JSON("rest_json"),
    REST_SSE("rest_sse"),
    JSON_RPC_2_0("json_rpc_2_0"),
    NATIVE_SDK("native_sdk"),
    IN_PROCESS("in_process")
}

enum class AuthenticationType(val code: String) {
    API_KEY_HEADER("api_key_header"),
    BEARER_TOKEN("bearer_token"),
    QUERY_PARAM("query_param"),
    NONE("none")
}

data class ServiceProtocol(
    val id: String,
    val name: String,
    val wireFormat: ProtocolWireFormat,
    val authType: AuthenticationType,
    val defaultEndpointTemplate: String = "",
    val requiresSecretKey: Boolean = true
)

/**
 * 4. CONFIGURATION
 * Operational runtime binding uniting a Service over a Protocol to an endpoint and credentials.
 */
data class ServiceConfiguration(
    val id: String,
    val serviceId: String,
    val protocolId: ServiceProtocolId,
    val endpointUrl: String,
    val defaultOfferingId: String = "",
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val lastValidatedEpochMs: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val lastErrorMessage: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 30,
    val hasSecretKey: Boolean = false,
    val authAlias: String? = null,
    val configurationVersion: Long = 1L,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    /**
     * Phase 4 (Correction #3): monotonic, atomic version bump. The repository
     * calls this on every update so decision records can detect stale
     * configurations and demand revalidation instead of silently reusing them.
     */
    fun withBumpedVersion(fromVersion: Long): ServiceConfiguration = copy(
        configurationVersion = fromVersion + 1L,
        updatedAtEpochMs = System.currentTimeMillis()
    )
}

private fun flavorToProtocolId(flavor: ProviderFlavor): ServiceProtocolId = when (flavor) {
    ProviderFlavor.GEMINI -> ServiceProtocolId.GEMINI_NATIVE
    ProviderFlavor.OPENAI_COMPATIBLE -> ServiceProtocolId.OPENAI_COMPATIBLE
    ProviderFlavor.OLLAMA -> ServiceProtocolId.OLLAMA_NATIVE
    ProviderFlavor.TAVILY -> ServiceProtocolId.TAVILY_NATIVE
    ProviderFlavor.MULTI_SOURCE_SEARCH -> ServiceProtocolId.IN_PROCESS
    ProviderFlavor.LOCAL_EMBEDDING -> ServiceProtocolId.IN_PROCESS
}

/**
 * Extension bridge functions translating legacy ProviderConfiguration into
 * the generalized Provider -> Service -> Protocol -> Configuration -> Offering pipeline.
 */
fun ProviderConfiguration.toProvider(): Provider {
    return Provider(
        id = this.flavor.name.lowercase(),
        name = when (this.flavor) {
            ProviderFlavor.GEMINI -> "Google DeepMind"
            ProviderFlavor.OPENAI_COMPATIBLE -> "OpenAI Compatible Host"
            ProviderFlavor.OLLAMA -> "Ollama Local Edge"
            ProviderFlavor.TAVILY -> "Tavily AI Search"
            ProviderFlavor.MULTI_SOURCE_SEARCH -> "Multi-Source Metasearch"
            ProviderFlavor.LOCAL_EMBEDDING -> "Local In-Memory Engine"
        },
        description = "مزوّد تم تهيئته عبر واجهة التحكم",
        isLocal = this.flavor == ProviderFlavor.OLLAMA || this.flavor == ProviderFlavor.LOCAL_EMBEDDING,
        isEnabled = this.isEnabled,
        createdAtEpochMs = this.createdAtEpochMs,
        updatedAtEpochMs = this.updatedAtEpochMs
    )
}

fun ProviderConfiguration.toService(): ProviderService {
    val serviceType = when (this.category) {
        ProviderCategory.LLM -> ServiceType.LLM
        ProviderCategory.EMBEDDING -> ServiceType.EMBEDDING
        ProviderCategory.SEARCH -> ServiceType.SEARCH
        ProviderCategory.VECTOR_STORE -> ServiceType.VECTOR_STORE
    }
    return ProviderService(
        id = "${this.id}-service",
        providerId = this.flavor.name.lowercase(),
        name = "${this.name} Service",
        serviceType = serviceType,
        supportedProtocolIds = listOf("${this.flavor.name.lowercase()}-protocol"),
        isEnabled = this.isEnabled
    )
}

fun ProviderConfiguration.toProtocol(): ServiceProtocol {
    val (wireFormat, authType) = when (this.flavor) {
        ProviderFlavor.GEMINI -> ProtocolWireFormat.REST_JSON to AuthenticationType.API_KEY_HEADER
        ProviderFlavor.OPENAI_COMPATIBLE -> ProtocolWireFormat.REST_JSON to AuthenticationType.BEARER_TOKEN
        ProviderFlavor.OLLAMA -> ProtocolWireFormat.REST_JSON to AuthenticationType.NONE
        ProviderFlavor.TAVILY -> ProtocolWireFormat.REST_JSON to AuthenticationType.API_KEY_HEADER
        ProviderFlavor.MULTI_SOURCE_SEARCH -> ProtocolWireFormat.REST_JSON to AuthenticationType.NONE
        ProviderFlavor.LOCAL_EMBEDDING -> ProtocolWireFormat.IN_PROCESS to AuthenticationType.NONE
    }
    return ServiceProtocol(
        id = "${this.flavor.name.lowercase()}-protocol",
        name = "${this.flavor.displayName} Protocol",
        wireFormat = wireFormat,
        authType = authType,
        defaultEndpointTemplate = this.flavor.defaultEndpoint,
        requiresSecretKey = authType != AuthenticationType.NONE
    )
}

fun ProviderConfiguration.toServiceConfiguration(): ServiceConfiguration {
    return ServiceConfiguration(
        id = "cfg-${this.id}",
        serviceId = "${this.id}-service",
        protocolId = flavorToProtocolId(this.flavor),
        endpointUrl = this.endpointUrl,
        defaultOfferingId = this.defaultModelId,
        isEnabled = this.isEnabled,
        isDefault = this.isDefault,
        healthStatus = this.healthStatus,
        lastValidatedEpochMs = this.lastValidatedEpochMs,
        lastLatencyMs = this.lastLatencyMs,
        lastErrorMessage = this.lastErrorMessage,
        timeoutSeconds = this.timeoutSeconds,
        hasSecretKey = this.hasSecretKey,
        configurationVersion = 1L,
        createdAtEpochMs = this.createdAtEpochMs,
        updatedAtEpochMs = this.updatedAtEpochMs
    )
}

fun ProviderConfiguration.toDefaultOffering(): ServiceOffering {
    val caps = when (this.category) {
        ProviderCategory.LLM -> setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING)
        ProviderCategory.EMBEDDING -> setOf(CapabilityType.EMBEDDING)
        ProviderCategory.SEARCH -> setOf(CapabilityType.SEARCH)
        ProviderCategory.VECTOR_STORE -> setOf(CapabilityType.VECTOR_STORE, CapabilityType.MEMORY_RETRIEVAL)
    }
    val offeringType = when (this.category) {
        ProviderCategory.LLM -> OfferingType.MODEL
        ProviderCategory.EMBEDDING -> OfferingType.MODEL
        ProviderCategory.SEARCH -> OfferingType.INDEX
        ProviderCategory.VECTOR_STORE -> OfferingType.ENDPOINT
    }
    return ServiceOffering(
        id = this.defaultModelId.ifBlank { "${this.id}-default-offering" },
        serviceId = "${this.id}-service",
        offeringType = offeringType,
        name = "${this.name} Default Offering",
        supportedCapabilities = caps,
        isLocal = this.flavor == ProviderFlavor.OLLAMA || this.flavor == ProviderFlavor.LOCAL_EMBEDDING,
        isAvailable = this.isEnabled && this.healthStatus != HealthStatus.UNAVAILABLE,
        latencyScoreMs = this.lastLatencyMs
    )
}

fun ProviderConfiguration.toAuthoritativeResourceRecord(): ResourceRecord {
    val resType = when (this.category) {
        ProviderCategory.LLM -> ResourceType.LLM
        ProviderCategory.EMBEDDING -> ResourceType.EMBEDDING
        ProviderCategory.SEARCH -> ResourceType.SEARCH
        ProviderCategory.VECTOR_STORE -> ResourceType.STORAGE
    }
    val defaultOffering = toDefaultOffering()
    val svcConfig = toServiceConfiguration()
    return defaultOffering.toResourceRecord(
        providerId = this.flavor.name.lowercase(),
        config = svcConfig,
        resourceType = resType
    )
}
