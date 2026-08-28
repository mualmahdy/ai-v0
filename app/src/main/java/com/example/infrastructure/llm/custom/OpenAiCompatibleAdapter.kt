package com.example.infrastructure.llm.custom

import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Generic OpenAI-Compatible Endpoints Adapter (Ollama, LocalAI, vLLM, OpenRouter, etc.).
 *
 * Implements clean decoupled HTTP requests conforming to standard chat completion schema.
 */
class OpenAiCompatibleAdapter(
    override val providerId: String,
    private val endpointUrl: String,
    private val modelName: String,
    private val apiKeyProvider: () -> String? = { null },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : LlmProviderPort {

    override val metadata: SafeProviderMetadata
        get() = SafeProviderMetadata(
            id = providerId,
            name = "OpenAI-Compatible: $providerId",
            providerType = "OPENAI_COMPATIBLE_REST",
            defaultModel = modelName,
            isConfigured = endpointUrl.isNotBlank(),
            isOnline = true,
            isLocal = endpointUrl.contains("localhost") || endpointUrl.contains("127.0.0.1"),
            supportedCapabilities = listOf("llm_generation", "streaming", "custom_endpoint")
        )

    override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val jsonBody = JSONObject().apply {
                put("model", modelName)
                put("temperature", request.config.temperature)
                put("max_tokens", request.config.maxOutputTokens)
                val messagesArray = JSONArray()
                request.messages.forEach { msg ->
                    messagesArray.put(JSONObject().apply {
                        put("role", when (msg.role) {
                            MessageRole.SYSTEM -> "system"
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            MessageRole.TOOL -> "tool"
                        })
                        put("content", msg.content)
                    })
                }
                put("messages", messagesArray)
            }

            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

            apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
                reqBuilder.addHeader("Authorization", "Bearer $key")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    val code = response.code
                    return@withContext if (code == 401 || code == 403) {
                        Outcome.Error(LlmFailure.AuthenticationFailed(providerId, "فشل المصادقة مع النقطة النهائية."))
                    } else if (code == 429) {
                        Outcome.Error(LlmFailure.RateLimitExceeded(providerId, 60000L, "تم تجاوز حد الطلبات."))
                    } else {
                        Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "استجابة غير ناجحة: $code"))
                    }
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val choices = json.optJSONArray("choices")
                val text = if (choices != null && choices.length() > 0) {
                    choices.getJSONObject(0).optJSONObject("message")?.optString("content") ?: ""
                } else {
                    ""
                }

                val usageObj = json.optJSONObject("usage")
                val promptTokens = usageObj?.optInt("prompt_tokens") ?: (request.messages.sumOf { it.content.length / 4 })
                val completionTokens = usageObj?.optInt("completion_tokens") ?: (text.length / 4)

                Outcome.Success(
                    value = LlmResponse(
                        text = text,
                        usage = TokenUsage(promptTokens, completionTokens),
                        modelId = modelName
                    ),
                    metadata = OutcomeMetadata(durationMs = duration, tokensConsumed = promptTokens + completionTokens, providerId = providerId)
                )
            }
        } catch (e: Exception) {
            Outcome.Error(LlmFailure.NetworkTimeout(providerId, 30000L), diagnosticMessage = e.message ?: "Network error")
        }
    }

    override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
        when (val genResult = generate(request)) {
            is Outcome.Success -> {
                emit(ExecutionEvent.Started(executionId = executionId, agentId = com.example.domain.core.agent.AgentId("custom"), modelId = modelName))
                emit(ExecutionEvent.ContentChunk(executionId = executionId, deltaText = genResult.value.text, sequenceIndex = 0))
                emit(ExecutionEvent.UsageBudgetUpdate(
                    executionId = executionId,
                    promptTokens = genResult.value.usage.promptTokens,
                    completionTokens = genResult.value.usage.completionTokens,
                    totalSessionTokens = genResult.value.usage.totalTokens,
                    remainingBudgetTokens = 30000 - genResult.value.usage.totalTokens
                ))
                emit(ExecutionEvent.Completed(executionId = executionId, finalText = genResult.value.text, totalDurationMs = genResult.metadata.durationMs))
            }
            is Outcome.Degraded -> {
                emit(ExecutionEvent.Degraded(executionId = executionId, reason = genResult.reason, message = genResult.diagnosticMessage))
            }
            is Outcome.Error -> {
                emit(ExecutionEvent.Error(executionId = executionId, failureCode = "CUSTOM_LLM_ERROR", message = genResult.diagnosticMessage))
            }
        }
    }
}
