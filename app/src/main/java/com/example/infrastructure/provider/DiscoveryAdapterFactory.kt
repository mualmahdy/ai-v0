package com.example.infrastructure.provider

import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.model.ModelDescriptor
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.offering.OfferingType
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.ports.llm.LlmProviderPort
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * DiscoveryAdapterFactory — Dynamic per-(ServiceType+Protocol) discovery
 * ============================================================================
 *
 * Per the architectural plan (Section 9 + Correction #9):
 *
 * The system must NOT have hard-coded discovery registration such as
 * `local_ollama → fixed endpoint` as the only route. Discovery must be derived
 * from `ServiceType + ServiceProtocol + ServiceConfiguration`.
 *
 * For OpenAI-compatible services:
 *   - use the configured endpoint
 *   - derive `/models` correctly where supported
 *   - discover model IDs
 *   - create/reconcile ServiceOfferings (OfferingType.MODEL)
 *   - DO NOT materialize ResourceRecords automatically
 *
 * For Gemini:
 *   - use the actual configured Gemini service
 *   - use the existing Gemini discovery capability where applicable
 *   - do not fabricate model availability
 *
 * For services that do not expose model discovery:
 *   - allow explicit offering configuration where architecturally appropriate
 *   - do not pretend discovery succeeded
 *
 * Discovery result: `ServiceOffering` — NOT `ResourceRecord`.
 */
object DiscoveryAdapterFactory {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Discover offerings for the given (service, config). Returns a list of
     * `ServiceOffering`s (NOT ResourceRecords). The caller must materialize
     * each offering separately.
     */
    suspend fun discover(
        service: ProviderService,
        config: ServiceConfiguration,
        apiKeyProvider: () -> String?
    ): Outcome<List<ServiceOffering>, String> {
        return when (service.serviceType) {
            ServiceType.LLM, ServiceType.EMBEDDING -> discoverOpenAiCompatibleOrGeminiModels(
                service, config, apiKeyProvider
            )
            ServiceType.SEARCH -> Outcome.Success(emptyList())  // Search does not expose model discovery
            ServiceType.MCP -> Outcome.Success(emptyList())      // Handled via McpAdapterPort.discoverTools()
            ServiceType.IMAGE_GENERATION, ServiceType.SPEECH, ServiceType.VECTOR_STORE ->
                Outcome.Success(emptyList())
        }
    }

    /**
     * Dynamic discovery for LLM/Embedding services using the configured endpoint.
     *
     * For OPENAI_COMPATIBLE / OPENAI_NATIVE / OLLAMA_NATIVE:
     *   - GET `${endpoint}/models` (or `${endpoint}/v1/models` if endpoint does not include /v1)
     *   - Parse the response `data` array
     *   - For each model, create a ServiceOffering (OfferingType.MODEL)
     *
     * For GEMINI_NATIVE:
     *   - GET `https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey`
     *   - Parse the response `models` array, keep only items supporting `generateContent`
     *
     * For ANTHROPIC_NATIVE:
     *   - Currently no public /models endpoint — return an empty list (Phase 4)
     *
     * For IN_PROCESS / NATIVE_SDK:
     *   - No discovery — return empty list
     */
    private fun discoverOpenAiCompatibleOrGeminiModels(
        service: ProviderService,
        config: ServiceConfiguration,
        apiKeyProvider: () -> String?
    ): Outcome<List<ServiceOffering>, String> {
        return try {
            when (config.protocolId) {
                ServiceProtocolId.OPENAI_COMPATIBLE,
                ServiceProtocolId.OPENAI_NATIVE,
                ServiceProtocolId.OLLAMA_NATIVE -> {
                    val baseUrl = normalizeBaseUrl(config.endpointUrl)
                    val url = "$baseUrl/models"
                    val builder = Request.Builder().url(url)
                    val apiKey = apiKeyProvider()
                    if (!apiKey.isNullOrBlank()) {
                        builder.addHeader("Authorization", "Bearer $apiKey")
                    }
                    val response = httpClient.newCall(builder.build()).execute()
                    if (!response.isSuccessful) {
                        return Outcome.Error(
                            "HTTP_${response.code}",
                            "Discovery failed: HTTP ${response.code} from $url"
                        )
                    }
                    val body = response.body?.string()
                        ?: return Outcome.Error("EMPTY_BODY", "Discovery returned empty body")
                    val json = JSONObject(body)
                    val data: JSONArray = if (json.has("data")) json.getJSONArray("data")
                    else if (json.has("models")) json.getJSONArray("models")
                    else JSONArray()
                    val offerings = (0 until data.length()).mapNotNull { i ->
                        val item = data.getJSONObject(i)
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isBlank()) return@mapNotNull null
                        ServiceOffering(
                            id = id,
                            serviceId = service.id,
                            offeringType = OfferingType.MODEL,
                            name = id,
                            description = item.optString("description", "Discovered model"),
                            supportedCapabilities = if (service.serviceType == ServiceType.LLM)
                                setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING, CapabilityType.STREAMING)
                            else setOf(CapabilityType.EMBEDDING, CapabilityType.MEMORY_RETRIEVAL),
                            isLocal = service.serviceType == ServiceType.LLM && config.protocolId == ServiceProtocolId.OLLAMA_NATIVE,
                            isAvailable = true,
                            discoveredEpochMs = System.currentTimeMillis(),
                            discoverySource = "OPENAI_COMPATIBLE_${config.protocolId.code}"
                        )
                    }
                    Outcome.Success(offerings)
                }

                ServiceProtocolId.GEMINI_NATIVE -> {
                    val apiKey = apiKeyProvider()
                    if (apiKey.isNullOrBlank()) {
                        return Outcome.Error(
                            "NO_API_KEY",
                            "Gemini discovery requires an API key"
                        )
                    }
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
                    if (!response.isSuccessful) {
                        return Outcome.Error(
                            "HTTP_${response.code}",
                            "Gemini discovery failed: HTTP ${response.code}"
                        )
                    }
                    val body = response.body?.string()
                        ?: return Outcome.Error("EMPTY_BODY", "Gemini discovery returned empty body")
                    val json = JSONObject(body)
                    val models = json.optJSONArray("models") ?: JSONArray()
                    val offerings = (0 until models.length()).mapNotNull { i ->
                        val item = models.getJSONObject(i)
                        val name = item.optString("name").removePrefix("models/")
                        if (name.isBlank()) return@mapNotNull null
                        val supports = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        if ((0 until supports.length()).none { supports.getString(it) == "generateContent" }) {
                            return@mapNotNull null
                        }
                        ServiceOffering(
                            id = name,
                            serviceId = service.id,
                            offeringType = OfferingType.MODEL,
                            name = name,
                            description = item.optString("description", "Discovered Gemini model"),
                            supportedCapabilities = setOf(
                                CapabilityType.LLM_GENERATION,
                                CapabilityType.REASONING,
                                CapabilityType.STREAMING,
                                CapabilityType.VISION
                            ),
                            isLocal = false,
                            isAvailable = true,
                            contextWindowTokens = item.optInt("inputTokenLimit").takeIf { it > 0 },
                            discoveredEpochMs = System.currentTimeMillis(),
                            discoverySource = "GEMINI_V1BETA_API"
                        )
                    }
                    Outcome.Success(offerings)
                }

                ServiceProtocolId.ANTHROPIC_NATIVE -> {
                    // Anthropic does not expose a public /models endpoint at this time.
                    // Return an empty list — the user can manually add offerings.
                    Outcome.Success(emptyList())
                }

                ServiceProtocolId.IN_PROCESS, ServiceProtocolId.NATIVE_SDK -> {
                    // No discovery for in-process or native-SDK protocols.
                    Outcome.Success(emptyList())
                }

                else -> Outcome.Success(emptyList())
            }
        } catch (e: Exception) {
            Outcome.Error("DISCOVERY_EXCEPTION", "Discovery exception: ${e.message}")
        }
    }

    /**
     * Normalize a base URL so that appending `/models` produces a valid discovery URL.
     *
     * Examples:
     *   "https://api.openai.com/v1"     → "https://api.openai.com/v1"
     *   "https://api.openai.com/v1/"    → "https://api.openai.com/v1"
     *   "https://example.com"           → "https://example.com/v1"
     *   "http://127.0.0.1:11434"        → "http://127.0.0.1:11434/v1"
     *   "http://127.0.0.1:11434/v1"     → "http://127.0.0.1:11434/v1"
     */
    private fun normalizeBaseUrl(url: String): String {
        if (url.isBlank()) return ""
        val trimmed = url.trimEnd('/')
        return if (trimmed.endsWith("/v1")) trimmed
        else "$trimmed/v1"
    }
}
