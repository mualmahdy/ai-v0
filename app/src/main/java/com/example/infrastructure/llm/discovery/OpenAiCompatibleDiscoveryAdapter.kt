package com.example.infrastructure.llm.discovery

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

/**
 * Discovers models from an OpenAI-compatible endpoint (Ollama, LocalAI, vLLM, etc.).
 *
 * FIX INF-P0-14: The previous implementation had two critical honesty bugs:
 *   1. `getLocalFallbackModels()` returned 2 fabricated models (llama-3.2-3b-instruct,
 *      qwen-2.5-coder-7b) with `isLocalOnDevice = true` and `health = HEALTHY` even when
 *      the discovery endpoint was unreachable. The UI displayed these as real available
 *      local models. Now they are labelled `isLocalOnDevice = false` with `health = UNKNOWN`
 *      and `discoverySource = "HARDCODED_FALLBACK"` so the UI can distinguish "discovered
 *      real models" from "we fell back to a static list".
 *   2. Both the non-200 HTTP path and the exception path silently returned `Success(...)`
 *      with the fallback list — callers could not distinguish "real discovery succeeded"
 *      from "fallback used". Now non-200 and exceptions return `Outcome.Degraded` with the
 *      fallback list (so callers can still use the list but know it's degraded) or
 *      `Outcome.Error` for hard transport failures.
 *
 * FIX: The `checkHealth()` method previously always returned `Success(HEALTHY)` without
 * any HTTP call. Now it actually pings the endpoint and returns the real status.
 *
 * FIX: Added Android emulator host (10.0.2.2) as a default baseUrl option since the
 * previous `127.0.0.1` doesn't work on Android hardware (only on the emulator's loopback).
 */
class OpenAiCompatibleDiscoveryAdapter(
    override val providerId: String = "local_ollama",
    private val baseUrl: String = "http://10.0.2.2:11434",
    private val apiKeyProvider: () -> String? = { null }
) : ModelDiscoveryPort {

    override suspend fun discoverModels(): Outcome<List<ModelDescriptor>, DiscoveryFailure> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$baseUrl/v1/models"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                apiKeyProvider()?.let { setRequestProperty("Authorization", "Bearer $it") }
            }

            val code = connection.responseCode
            if (code == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val dataArray = json.optJSONArray("data")
                if (dataArray == null || dataArray.length() == 0) {
                    // Real endpoint reachable but returned no models — return empty list, not fallback.
                    return@withContext Outcome.Success(emptyList())
                }

                val models = mutableListOf<ModelDescriptor>()
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val modelId = item.optString("id", "")
                    if (modelId.isNotBlank()) {
                        models.add(
                            ModelDescriptor(
                                id = modelId,
                                providerId = providerId,
                                name = modelId,
                                version = "1.0",
                                contextWindowTokens = 8192,
                                maxOutputTokens = 4096,
                                inputModalities = setOf(Modality.TEXT),
                                outputModalities = setOf(Modality.TEXT),
                                supportsReasoning = TriStateCapability.UNKNOWN,
                                supportsVision = if (modelId.contains("llava") || modelId.contains("vision")) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
                                supportsToolCalling = TriStateCapability.SUPPORTED,
                                supportsStructuredOutput = TriStateCapability.SUPPORTED,
                                supportsStreaming = TriStateCapability.SUPPORTED,
                                isLocalOnDevice = true,
                                health = HealthStatus.HEALTHY,
                                discoverySource = "OPENAI_COMPATIBLE_ENDPOINT",
                                confidence = 0.9f,
                                lastDiscoveredTimestampMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
                Outcome.Success(models)
            } else {
                // FIX INF-P0-14: HTTP non-200 is a real failure, not a silent fallback.
                // Return Degraded with the honest fallback list (clearly labelled) so
                // callers can still show something but know it's not real discovery.
                Outcome.Degraded(
                    partialValue = getHonestFallbackModels(),
                    reason = com.example.domain.core.DegradedReason.PROVIDER_UNREACHABLE,
                    diagnosticMessage = "Endpoint returned HTTP $code; showing static fallback list (not real discovery)."
                )
            }
        } catch (e: java.net.ConnectException) {
            // Most common case: Ollama not running on the device. Be honest about it.
            Outcome.Degraded(
                partialValue = getHonestFallbackModels(),
                reason = com.example.domain.core.DegradedReason.PROVIDER_UNREACHABLE,
                diagnosticMessage = "Cannot connect to $baseUrl — ${e.message}. Showing static fallback list (not real discovery)."
            )
        } catch (e: Exception) {
            Outcome.Error(
                failure = DiscoveryFailure.TransportError(providerId, e.message ?: e::class.java.simpleName),
                diagnosticMessage = "Discovery failed: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    /**
     * FIX INF-P0-14: Previously called `getLocalFallbackModels()` and returned models
     * with `isLocalOnDevice = true` and `health = HEALTHY`. That was misleading because
     * these models are NOT actually loaded on the device — they are author-curated
     * examples. Now we return them with:
     *   - isLocalOnDevice = false  (they're a catalog, not a runtime)
     *   - health = UNKNOWN          (we haven't verified they're available)
     *   - discoverySource = "HARDCODED_FALLBACK"
     *   - confidence = 0.0f         (we have no measurement)
     *   - averageLatencyMs = 0      (we haven't measured)
     *
     * The UI can show these as "Reference models — install Ollama to enable" instead of
     * presenting them as ready-to-use local models.
     */
    private fun getHonestFallbackModels(): List<ModelDescriptor> {
        return listOf(
            ModelDescriptor(
                id = "llama-3.2-3b-instruct",
                providerId = providerId,
                name = "Llama 3.2 3B Instruct (Reference — install Ollama)",
                version = "3.2",
                contextWindowTokens = 8192,
                maxOutputTokens = 2048,
                inputModalities = setOf(Modality.TEXT),
                outputModalities = setOf(Modality.TEXT),
                supportsReasoning = TriStateCapability.SUPPORTED,
                supportsVision = TriStateCapability.UNSUPPORTED,
                supportsToolCalling = TriStateCapability.SUPPORTED,
                supportsStructuredOutput = TriStateCapability.SUPPORTED,
                supportsStreaming = TriStateCapability.SUPPORTED,
                // FIX: do NOT claim isLocalOnDevice = true without verification.
                isLocalOnDevice = false,
                // FIX: do NOT claim HEALTHY without ping.
                health = HealthStatus.UNKNOWN,
                discoverySource = "HARDCODED_FALLBACK",
                confidence = 0.0f,
                estimatedCostPer1kTokensUsd = 0.0,
                averageLatencyMs = 0
            ),
            ModelDescriptor(
                id = "qwen-2.5-coder-7b",
                providerId = providerId,
                name = "Qwen 2.5 Coder 7B (Reference — install Ollama)",
                version = "2.5",
                contextWindowTokens = 32768,
                maxOutputTokens = 4096,
                inputModalities = setOf(Modality.TEXT),
                outputModalities = setOf(Modality.TEXT),
                supportsReasoning = TriStateCapability.SUPPORTED,
                supportsVision = TriStateCapability.UNSUPPORTED,
                supportsToolCalling = TriStateCapability.SUPPORTED,
                supportsStructuredOutput = TriStateCapability.SUPPORTED,
                supportsStreaming = TriStateCapability.SUPPORTED,
                isLocalOnDevice = false,
                health = HealthStatus.UNKNOWN,
                discoverySource = "HARDCODED_FALLBACK",
                confidence = 0.0f,
                estimatedCostPer1kTokensUsd = 0.0,
                averageLatencyMs = 0
            )
        )
    }

    /**
     * FIX: Real health check. Previously returned Success(HEALTHY) without any HTTP call.
     * Now we actually ping the /v1/models endpoint and return the real status.
     */
    override suspend fun checkHealth(): Outcome<ProviderDescriptor, DiscoveryFailure> = withContext(Dispatchers.IO) {
        return@withContext try {
            val endpoint = "$baseUrl/v1/models"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                apiKeyProvider()?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            val code = connection.responseCode
            val health = when (code) {
                200 -> HealthStatus.HEALTHY
                in 400..499 -> HealthStatus.DEGRADED
                else -> HealthStatus.UNAVAILABLE
            }
            Outcome.Success(
                ProviderDescriptor(
                    id = providerId,
                    name = "Local / Edge Runtime (Ollama/On-Device)",
                    type = ProviderType.LLM,
                    isConfigured = true,
                    isLocal = true,
                    health = health,
                    endpointUrl = baseUrl,
                    supportedCapabilities = listOf("offline", "chat", "streaming", "local_privacy"),
                    lastDiscoveredTimestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: java.net.ConnectException) {
            Outcome.Success(
                ProviderDescriptor(
                    id = providerId,
                    name = "Local / Edge Runtime (Ollama/On-Device)",
                    type = ProviderType.LLM,
                    isConfigured = false,
                    isLocal = true,
                    health = HealthStatus.UNAVAILABLE,
                    endpointUrl = baseUrl,
                    supportedCapabilities = listOf("offline", "chat", "streaming", "local_privacy"),
                    lastDiscoveredTimestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Outcome.Error(
                failure = DiscoveryFailure.TransportError(providerId, e.message ?: e::class.java.simpleName),
                diagnosticMessage = "Health check failed: ${e.localizedMessage ?: e.message}"
            )
        }
    }
}
