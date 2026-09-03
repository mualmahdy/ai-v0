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
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Generic OpenAI-Compatible Endpoints Adapter (Ollama, LocalAI, vLLM, OpenRouter, etc.).
 *
 * Implements clean decoupled HTTP requests conforming to standard chat completion schema.
 *
 * FIX INF-P0-13 (fake streaming): The previous `stream()` implementation called
 * `generate()` (blocking) and then emitted the entire response as a single
 * `ContentChunk`. This made the streaming UI a lie — UX was identical to non-streaming.
 * Now `stream()` sends `stream: true` to the endpoint and parses SSE `data:` lines,
 * emitting one `ContentChunk` per delta token chunk as the server produces them.
 *
 * FIX INF-P0-16 (error mapping): The previous `generate()` mapped ALL exceptions to
 * `NetworkTimeout`, which masked authentication failures, rate limits, and parse errors
 * behind a generic timeout message. Now we inspect HTTP status codes and exception types
 * to produce the correct `LlmFailure` subtype.
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
            isLocal = isLocalEndpoint(endpointUrl),
            supportedCapabilities = listOf("llm_generation", "streaming", "custom_endpoint")
        )

    /**
     * FIX: Detect local endpoints more robustly. The previous check only matched
     * "localhost" and "127.0.0.1" — it missed the Android emulator host (10.0.2.2),
     * LAN IPs (192.168.x.x / 10.x.x.x), and .local mDNS hostnames.
     */
    private fun isLocalEndpoint(url: String): Boolean {
        return url.contains("localhost") ||
                url.contains("127.0.0.1") ||
                url.contains("10.0.2.2") ||
                url.contains("192.168.") ||
                url.contains("10.") ||
                url.contains(".local") ||
                url.contains("0.0.0.0")
    }

    override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val jsonBody = buildJsonBody(request, stream = false)

            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

            apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
                reqBuilder.addHeader("Authorization", "Bearer $key")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    return@withContext mapHttpFailure(response.code, response.body?.string())
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
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Error(LlmFailure.NetworkTimeout(providerId, 30000L), diagnosticMessage = e.message ?: "Socket timeout")
        } catch (e: java.net.UnknownHostException) {
            Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "Unknown host: ${e.message}"), diagnosticMessage = e.message ?: "DNS resolution failed")
        } catch (e: javax.net.ssl.SSLException) {
            Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "SSL error: ${e.message}"), diagnosticMessage = e.message ?: "SSL handshake failed")
        } catch (e: org.json.JSONException) {
            Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "Malformed response JSON: ${e.message}"), diagnosticMessage = e.message ?: "JSON parse error")
        } catch (e: Exception) {
            Outcome.Error(LlmFailure.ProviderUnavailable(providerId, e.message ?: "Unknown error"), diagnosticMessage = e.message ?: "Unknown error")
        }
    }

    /**
     * FIX INF-P0-13: Real SSE streaming implementation.
     *
     * Sends `stream: true` to the endpoint, then reads the response body as a stream
     * of Server-Sent Events. Each `data:` line contains a JSON chunk with a `delta`
     * field; we parse it and emit a `ContentChunk` event per delta.
     *
     * The stream terminates when the server sends `data: [DONE]` or closes the connection.
     */
    override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
        val startTime = System.currentTimeMillis()
        val fullText = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0

        try {
            val jsonBody = buildJsonBody(request, stream = true)

            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Accept", "text/event-stream")

            apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
                reqBuilder.addHeader("Authorization", "Bearer $key")
            }

            emit(ExecutionEvent.Started(executionId = executionId, agentId = com.example.domain.core.agent.AgentId("custom"), modelId = modelName))

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    val failure = mapHttpFailure(response.code, errBody)
                    if (failure is Outcome.Error) {
                        emit(ExecutionEvent.Error(
                            executionId = executionId,
                            failureCode = failure.failure::class.simpleName ?: "CUSTOM_LLM_ERROR",
                            message = failure.diagnosticMessage
                        ))
                    }
                    return@use
                }

                val body = response.body ?: run {
                    emit(ExecutionEvent.Error(
                        executionId = executionId,
                        failureCode = "EMPTY_RESPONSE_BODY",
                        message = "استجابة فارغة من النقطة النهائية."
                    ))
                    return@use
                }

                var sequenceIndex = 0
                BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        // SSE: lines starting with "data: " carry JSON chunks.
                        if (!l.startsWith("data:")) continue
                        val payload = l.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue

                        try {
                            val chunkJson = JSONObject(payload)
                            val choices = chunkJson.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) continue
                            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                            val deltaText = delta.optString("content", "")
                            if (deltaText.isNotEmpty()) {
                                fullText.append(deltaText)
                                emit(ExecutionEvent.ContentChunk(
                                    executionId = executionId,
                                    deltaText = deltaText,
                                    sequenceIndex = sequenceIndex++
                                ))
                            }
                            // Some endpoints stream usage in the final chunk
                            chunkJson.optJSONObject("usage")?.let { u ->
                                promptTokens = u.optInt("prompt_tokens", promptTokens)
                                completionTokens = u.optInt("completion_tokens", completionTokens)
                            }
                        } catch (_: org.json.JSONException) {
                            // Skip malformed chunks — common with some providers
                        }
                    }
                }

                if (promptTokens == 0) promptTokens = request.messages.sumOf { it.content.length / 4 }
                if (completionTokens == 0) completionTokens = fullText.length / 4

                emit(ExecutionEvent.UsageBudgetUpdate(
                    executionId = executionId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalSessionTokens = promptTokens + completionTokens,
                    remainingBudgetTokens = 30000 - (promptTokens + completionTokens)
                ))

                val duration = System.currentTimeMillis() - startTime
                emit(ExecutionEvent.Completed(
                    executionId = executionId,
                    finalText = fullText.toString(),
                    totalDurationMs = duration
                ))
            }
        } catch (e: java.net.SocketTimeoutException) {
            emit(ExecutionEvent.Error(
                executionId = executionId,
                failureCode = "NETWORK_TIMEOUT",
                message = e.message ?: "انتهت مهلة الشبكة أثناء البث."
            ))
        } catch (e: Exception) {
            emit(ExecutionEvent.Error(
                executionId = executionId,
                failureCode = "STREAM_ERROR",
                message = e.message ?: "خطأ غير معروف أثناء البث."
            ))
        }
    }

    /** Builds the JSON request body, with `stream` toggled for streaming vs blocking calls. */
    private fun buildJsonBody(request: LlmRequest, stream: Boolean): JSONObject {
        return JSONObject().apply {
            put("model", modelName)
            put("temperature", request.config.temperature)
            put("max_tokens", request.config.maxOutputTokens)
            put("stream", stream)
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
    }

    /** Maps an unsuccessful HTTP response code to the correct LlmFailure subtype. */
    private fun mapHttpFailure(code: Int, errorBody: String?): Outcome<LlmResponse, LlmFailure> {
        return when (code) {
            401, 403 -> Outcome.Error(
                LlmFailure.AuthenticationFailed(providerId, "فشل المصادقة مع النقطة النهائية ($code)."),
                diagnosticMessage = errorBody ?: "HTTP $code"
            )
            429 -> Outcome.Error(
                LlmFailure.RateLimitExceeded(providerId, 60000L, "تم تجاوز حد الطلبات (429)."),
                diagnosticMessage = errorBody ?: "HTTP 429"
            )
            408 -> Outcome.Error(
                LlmFailure.NetworkTimeout(providerId, 30000L),
                diagnosticMessage = errorBody ?: "HTTP 408 Request Timeout"
            )
            in 500..599 -> Outcome.Error(
                LlmFailure.ProviderUnavailable(providerId, "خطأ في الخادم ($code)."),
                diagnosticMessage = errorBody ?: "HTTP $code"
            )
            else -> Outcome.Error(
                LlmFailure.ProviderUnavailable(providerId, "استجابة غير ناجحة: $code"),
                diagnosticMessage = errorBody ?: "HTTP $code"
            )
        }
    }
}
