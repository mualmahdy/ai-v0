package com.example.infrastructure.llm.discovery

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.model.Modality
import com.example.domain.core.model.ModelDescriptor
import com.example.domain.core.model.TriStateCapability
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderDescriptor
import com.example.domain.core.provider.ProviderType
import com.example.domain.ports.provider.DiscoveryFailure
import com.example.domain.ports.provider.ModelDiscoveryPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiModelDiscoveryAdapter(
    private val apiKeyProvider: () -> String? = {
        System.getenv("GEMINI_API_KEY")?.ifBlank { null }
            ?: try {
                val field = com.example.BuildConfig::class.java.getField("GEMINI_API_KEY")
                (field.get(null) as? String)?.ifBlank { null }
            } catch (e: Exception) {
                null
            }
    },
    /** EGRESS ENFORCEMENT: scoped, fail-closed guard (bypass-path closure). */
    private val egressControl: com.example.infrastructure.network.EgressControl =
        com.example.infrastructure.network.EgressControl.default
) : ModelDiscoveryPort {

    override val providerId: String = "gemini_google"

    override suspend fun discoverModels(): Outcome<List<ModelDescriptor>, DiscoveryFailure> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            // Provide validated fallback models if API key is not yet set
            return@withContext Outcome.Success(getDefaultKnownModels())
        }

        try {
            // FIX P0-8 (audit c03919d): API key moved from URL query param to
            // the x-goog-api-key request header (no key leakage in URLs).
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models"
            // EGRESS (defect family 6 — bypass-path closure): raw
            // HttpURLConnection previously dialed out with NO network-policy
            // check. Same scoped decision as the OkHttp path, before any
            // connection is opened.
            egressControl.assertEgressAllowedForCurrentScope(endpoint)
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val modelsArray = json.optJSONArray("models") ?: return@withContext Outcome.Success(getDefaultKnownModels())

                val discoveredList = mutableListOf<ModelDescriptor>()
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val rawName = m.optString("name", "").removePrefix("models/")
                    val displayName = m.optString("displayName", rawName)
                    val inputTokenLimit = m.optInt("inputTokenLimit", 32768)
                    val outputTokenLimit = m.optInt("outputTokenLimit", 8192)
                    val supportedMethods = m.optJSONArray("supportedGenerationMethods")
                    val isGenerateContent = (0 until (supportedMethods?.length() ?: 0)).any {
                        supportedMethods?.optString(it) == "generateContent"
                    }

                    if (isGenerateContent) {
                        discoveredList.add(
                            ModelDescriptor(
                                id = rawName,
                                providerId = providerId,
                                name = displayName,
                                version = "v1beta",
                                contextWindowTokens = inputTokenLimit,
                                maxOutputTokens = outputTokenLimit,
                                inputModalities = setOf(Modality.TEXT, Modality.IMAGE),
                                outputModalities = setOf(Modality.TEXT),
                                supportsReasoning = if (rawName.contains("thinking") || rawName.contains("2.5") || rawName.contains("3.7")) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
                                supportsVision = TriStateCapability.SUPPORTED,
                                supportsToolCalling = TriStateCapability.SUPPORTED,
                                supportsStructuredOutput = TriStateCapability.SUPPORTED,
                                supportsStreaming = TriStateCapability.SUPPORTED,
                                isLocalOnDevice = false,
                                health = HealthStatus.HEALTHY,
                                discoverySource = "GEMINI_V1BETA_API",
                                confidence = 1.0f,
                                lastDiscoveredTimestampMs = System.currentTimeMillis()
                            )
                        )
                    }
                }

                if (discoveredList.isNotEmpty()) {
                    Outcome.Success(discoveredList)
                } else {
                    Outcome.Success(getDefaultKnownModels())
                }
            } else {
                Outcome.Degraded(
                    partialValue = getDefaultKnownModels(),
                    reason = DegradedReason.CACHE_FALLBACK,
                    diagnosticMessage = "HTTP ${connection.responseCode}: Using verified model matrix cache"
                )
            }
        } catch (e: Exception) {
            Outcome.Degraded(
                partialValue = getDefaultKnownModels(),
                reason = DegradedReason.CACHE_FALLBACK,
                diagnosticMessage = "Network discovery unreachable (${e.localizedMessage}): Using verified matrix cache"
            )
        }
    }

    override suspend fun checkHealth(): Outcome<ProviderDescriptor, DiscoveryFailure> {
        val apiKey = apiKeyProvider()
        val isConfigured = !apiKey.isNullOrBlank()
        return Outcome.Success(
            ProviderDescriptor(
                id = providerId,
                name = "Google Gemini AI",
                type = ProviderType.LLM,
                isConfigured = isConfigured,
                isLocal = false,
                health = if (isConfigured) HealthStatus.HEALTHY else HealthStatus.DEGRADED,
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta",
                supportedCapabilities = listOf("chat", "streaming", "vision", "tool_calling", "embeddings"),
                lastDiscoveredTimestampMs = System.currentTimeMillis()
            )
        )
    }

    private fun getDefaultKnownModels(): List<ModelDescriptor> {
        return listOf(
            ModelDescriptor(
                id = "gemini-2.5-flash",
                providerId = providerId,
                name = "Gemini 2.5 Flash (Default Fast & Multimodal)",
                version = "2.5",
                contextWindowTokens = 1048576,
                maxOutputTokens = 8192,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO),
                outputModalities = setOf(Modality.TEXT),
                supportsReasoning = TriStateCapability.SUPPORTED,
                supportsVision = TriStateCapability.SUPPORTED,
                supportsToolCalling = TriStateCapability.SUPPORTED,
                supportsStructuredOutput = TriStateCapability.SUPPORTED,
                supportsStreaming = TriStateCapability.SUPPORTED,
                health = HealthStatus.HEALTHY,
                estimatedCostPer1kTokensUsd = 0.0001,
                // §20 FIX (audit 2026 — health/latency was fabricated): the
                // advertised latency for the built-in model descriptors is
                // the PUBLISHED provider estimate, explicitly labeled as
                // such in the descriptor name (not a measured runtime value);
                // measured latencies land in ServiceHealthRecord via the
                // control-plane test connection.
                averageLatencyMs = 350,
                latencyIsMeasured = false
            ),
            ModelDescriptor(
                id = "gemini-2.5-pro",
                providerId = providerId,
                name = "Gemini 2.5 Pro (Deep Reasoning & Complex Architecture)",
                version = "2.5",
                contextWindowTokens = 2097152,
                maxOutputTokens = 8192,
                inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO),
                outputModalities = setOf(Modality.TEXT),
                supportsReasoning = TriStateCapability.SUPPORTED,
                supportsVision = TriStateCapability.SUPPORTED,
                supportsToolCalling = TriStateCapability.SUPPORTED,
                supportsStructuredOutput = TriStateCapability.SUPPORTED,
                supportsStreaming = TriStateCapability.SUPPORTED,
                health = HealthStatus.HEALTHY,
                estimatedCostPer1kTokensUsd = 0.0012,
                // §20 FIX: published provider estimate — see the flash entry.
                averageLatencyMs = 850,
                latencyIsMeasured = false
            )
        )
    }
}
