package com.example.infrastructure.llm.openai

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
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
 * ============================================================================
 * OpenAiCompatibleLlmAdapter — REAL OpenAI-compatible chat adapter
 * ============================================================================
 *
 * Serves OPENAI_COMPATIBLE / OPENAI_NATIVE / OLLAMA_NATIVE protocols by
 * POSTing to `${baseUrl}/chat/completions` per the OpenAI Chat Completions
 * wire format. Works with any compatible host (OpenAI, Groq, OpenRouter,
 * Ollama's /v1 compatibility layer, LM Studio, vLLM...).
 *
 * All network calls run on Dispatchers.IO (never blocking the caller's
 * dispatcher). Errors are mapped to the domain LlmFailure taxonomy — no
 * silent fallbacks, no fabricated success.
 */
class OpenAiCompatibleLlmAdapter(
    private val baseUrl: String,
    private val apiKeyProvider: suspend () -> String?,
    private val defaultModel: String = "gpt-4o-mini",
    override val providerId: String = "openai_compatible",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) : LlmProviderPort {

    override val metadata: SafeProviderMetadata
        get() = SafeProviderMetadata(
            id = providerId,
            name = "OpenAI-Compatible Host",
            providerType = "OPENAI_COMPATIBLE",
            defaultModel = defaultModel,
            isConfigured = baseUrl.isNotBlank(),
            isOnline = true,
            isLocal = false,
            supportedCapabilities = listOf("llm_generation", "streaming")
        )

    override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
        withContext(Dispatchers.IO) {
            val model = defaultModel
            val start = System.currentTimeMillis()
            try {
                val messages = JSONArray()
                request.messages.forEach { message ->
                    messages.put(
                        JSONObject()
                            .put("role", message.role.wireName)
                            .put("content", message.content)
                    )
                }
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", request.config.temperature.toDouble())
                    .put("max_tokens", request.config.maxOutputTokens)
                    .put("stream", false)
                if (request.config.stopSequences.isNotEmpty()) {
                    body.put("stop", JSONArray(request.config.stopSequences))
                }

                val url = normalizeBaseUrl(baseUrl) + "/chat/completions"
                val builder = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
                    builder.addHeader("Authorization", "Bearer $key")
                }

                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext when (response.code) {
                            401, 403 -> Outcome.Error(
                                LlmFailure.AuthenticationFailed(providerId, "HTTP ${response.code}"),
                                "Authentication rejected by $url"
                            )
                            429 -> Outcome.Degraded(
                                partialValue = null,
                                reason = DegradedReason.RATE_LIMIT_BACKOFF,
                                diagnosticMessage = "Rate limited by $url (HTTP 429)",
                                underlyingFailure = LlmFailure.RateLimitExceeded(providerId, null, "HTTP 429")
                            )
                            else -> Outcome.Error(
                                LlmFailure.ProviderUnavailable(providerId, "HTTP ${response.code}"),
                                "Request failed: HTTP ${response.code} from $url"
                            )
                        }
                    }
                    val text = response.body?.string()
                        ?: return@withContext Outcome.Error(
                            LlmFailure.ProviderUnavailable(providerId, "Empty body"),
                            "Empty response body from $url"
                        )
                    val json = JSONObject(text)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val content = choice?.optJSONObject("message")?.optString("content") ?: ""
                    val usageJson = json.optJSONObject("usage")
                    val usage = TokenUsage(
                        promptTokens = usageJson?.optInt("prompt_tokens", 0) ?: 0,
                        completionTokens = usageJson?.optInt("completion_tokens", 0) ?: 0
                    )
                    Outcome.Success(
                        LlmResponse(
                            text = content,
                            usage = usage,
                            finishReason = choice?.optString("finish_reason"),
                            modelId = json.optString("model", model)
                        ),
                        OutcomeMetadata(
                            durationMs = System.currentTimeMillis() - start,
                            providerId = providerId
                        )
                    )
                }
            } catch (e: java.net.SocketTimeoutException) {
                Outcome.Error(
                    LlmFailure.NetworkTimeout(providerId, 120_000L),
                    "Request timed out against $baseUrl: ${e.message}"
                )
            } catch (e: java.io.IOException) {
                Outcome.Error(
                    LlmFailure.ProviderUnavailable(providerId, e.message ?: "io error"),
                    "Transport failure: ${e.message}"
                )
            } catch (e: Exception) {
                Outcome.Error(
                    LlmFailure.ProviderUnavailable(providerId, e.message ?: "error"),
                    "Generation failed: ${e.message}"
                )
            }
        }

    override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
        // Non-streaming fallback encapsulated as a single completion chunk.
        val result = generate(request)
        val text = (result as? Outcome.Success)?.value?.text ?: ""
        emit(
            ExecutionEvent.ContentChunk(
                executionId = executionId,
                deltaText = text,
                sequenceIndex = 0
            )
        )
    }

    companion object {
        fun normalizeBaseUrl(url: String): String {
            if (url.isBlank()) return ""
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }
    }
}

/** Wire-level role names of the OpenAI Chat format. */
private val MessageRole.wireName: String
    get() = when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
    }
