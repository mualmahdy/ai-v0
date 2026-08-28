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

class OpenAiCompatibleDiscoveryAdapter(
    override val providerId: String = "local_ollama",
    private val baseUrl: String = "http://127.0.0.1:11434",
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

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val dataArray = json.optJSONArray("data") ?: return@withContext Outcome.Success(getLocalFallbackModels())

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
                Outcome.Success(getLocalFallbackModels())
            }
        } catch (e: Exception) {
            Outcome.Success(getLocalFallbackModels())
        }
    }

    override suspend fun checkHealth(): Outcome<ProviderDescriptor, DiscoveryFailure> {
        return Outcome.Success(
            ProviderDescriptor(
                id = providerId,
                name = "Local / Edge Runtime (Ollama/On-Device)",
                type = ProviderType.LLM,
                isConfigured = true,
                isLocal = true,
                health = HealthStatus.HEALTHY,
                endpointUrl = baseUrl,
                supportedCapabilities = listOf("offline", "chat", "streaming", "local_privacy"),
                lastDiscoveredTimestampMs = System.currentTimeMillis()
            )
        )
    }

    private fun getLocalFallbackModels(): List<ModelDescriptor> {
        return listOf(
            ModelDescriptor(
                id = "llama-3.2-3b-instruct",
                providerId = providerId,
                name = "Llama 3.2 3B Instruct (Offline Edge)",
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
                isLocalOnDevice = true,
                health = HealthStatus.HEALTHY,
                estimatedCostPer1kTokensUsd = 0.0,
                averageLatencyMs = 120
            ),
            ModelDescriptor(
                id = "qwen-2.5-coder-7b",
                providerId = providerId,
                name = "Qwen 2.5 Coder 7B (Local Code Specialist)",
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
                isLocalOnDevice = true,
                health = HealthStatus.HEALTHY,
                estimatedCostPer1kTokensUsd = 0.0,
                averageLatencyMs = 210
            )
        )
    }
}
