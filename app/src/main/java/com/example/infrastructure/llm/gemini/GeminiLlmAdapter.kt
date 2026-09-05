package com.example.infrastructure.llm.gemini

import com.example.domain.core.DegradedReason
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * GeminiLlmAdapter — REST-native Gemini adapter (fix: "firebase not initiated")
 * ============================================================================
 *
 * Speaks the Generative Language REST API directly:
 *
 *   POST {baseUrl}/v1beta/models/{model}:generateContent
 *   POST {baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse
 *
 * Why REST instead of the Firebase AI SDK:
 *   1. The Firebase AI SDK REQUIRES a bundled google-services.json (a real
 *      Firebase project + config file). Without it every call failed with
 *      "FirebaseApp initialization unsuccessful" — the exact crash users saw
 *      on real devices ("firebase not initiated").
 *   2. The SDK path never consumed the user's stored API key — the key the
 *      user entered was validated against the REST endpoint but then ignored
 *      at generation time. Here the vault key IS the execution key.
 *
 * The key is read lazily via [apiKeyProvider] (vault-backed) and sent in the
 * `x-goog-api-key` header (never in the URL). SYSTEM messages are passed as
 * `systemInstruction` — the REST-native mechanism. All network work runs on
 * Dispatchers.IO. Errors map honestly onto the domain LlmFailure taxonomy.
 */
class GeminiLlmAdapter(
    private val defaultModelName: String = "gemini-2.5-flash",
    private val apiKeyProvider: suspend () -> String? = { null },
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    override val providerId: String = "gemini",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
) : LlmProviderPort {

    override val metadata: SafeProviderMetadata
        get() = SafeProviderMetadata(
            id = providerId,
            name = "Google Gemini (REST)",
            providerType = "GEMINI_REST",
            defaultModel = defaultModelName,
            isConfigured = true,
            isOnline = true,
            isLocal = false,
            supportedCapabilities = listOf("llm_generation", "streaming", "reasoning")
        )

    // ------------------------------------------------------------------
    // Request building (shared by generate + stream)
    // ------------------------------------------------------------------

    /** REST payload of the non-system conversation turns. */
    private fun buildContents(messages: List<LlmMessage>): JSONArray {
        val contents = JSONArray()
        messages
            .filter { it.role != MessageRole.SYSTEM }
            .forEach { msg ->
                val role = if (msg.role == MessageRole.ASSISTANT) "model" else "user"
                contents.put(
                    JSONObject()
                        .put("role", role)
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", msg.content))
                        )
                )
            }
        return contents
    }

    private fun buildSystemInstruction(messages: List<LlmMessage>): JSONObject? {
        val systemText = messages.filter { it.role == MessageRole.SYSTEM }
            .joinToString(separator = "\n\n") { it.content }
            .takeIf { it.isNotBlank() } ?: return null
        return JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", systemText)))
    }

    private fun buildRequestBody(request: LlmRequest, stream: Boolean): String {
        val body = JSONObject()
            .put("contents", buildContents(request.messages))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", request.config.temperature.toDouble())
                    .put("topP", 0.95)
                    .put("maxOutputTokens", request.config.maxOutputTokens)
            )
        buildSystemInstruction(request.messages)?.let { body.put("systemInstruction", it) }
        if (stream) body.put("stream", true) // informational only for REST; alt=sse drives it
        return body.toString()
    }

    private suspend fun requestWithKey(url: String, body: String, stream: Boolean): Request {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrBlank()) {
            throw MissingGeminiKeyException()
        }
        val builder = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key) // header, never the URL (P0-8)
            .post(body.toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    // ------------------------------------------------------------------
    // generate (single-shot)
    // ------------------------------------------------------------------

    override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val model = defaultModelName
            try {
                val url = "$baseUrl/v1beta/models/$model:generateContent"
                val call = client.newCall(requestWithKey(url, buildRequestBody(request, stream = false), stream = false))
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext httpFailure(response.code, url)
                    }
                    val text = response.body?.string()
                        ?: return@withContext Outcome.Error(
                            LlmFailure.ProviderUnavailable(providerId, "Empty body"),
                            "Empty response body from $url"
                        )
                    val json = JSONObject(text)
                    val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                    val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                        }
                    }
                    val usageJson = json.optJSONObject("usageMetadata")
                    val usage = TokenUsage(
                        promptTokens = usageJson?.optInt("promptTokenCount", 0) ?: 0,
                        completionTokens = usageJson?.optInt("candidatesTokenCount", 0) ?: 0
                    )
                    val finish = candidate?.optString("finishReason") ?: "STOP"
                    Outcome.Success(
                        LlmResponse(
                            text = sb.toString(),
                            usage = usage,
                            finishReason = finish,
                            modelId = json.optString("modelVersion", model)
                        ),
                        OutcomeMetadata(
                            durationMs = System.currentTimeMillis() - start,
                            tokensConsumed = usage.promptTokens + usage.completionTokens,
                            providerId = providerId
                        )
                    )
                }
            } catch (e: MissingGeminiKeyException) {
                Outcome.Error(
                    LlmFailure.AuthenticationFailed(
                        providerId,
                        "لا يوجد مفتاح Gemini API — أدخل المفتاح من شاشة المزودين ثم أعد المحاولة"
                    ),
                    "No stored Gemini API key"
                )
            } catch (e: java.net.SocketTimeoutException) {
                Outcome.Error(
                    LlmFailure.NetworkTimeout(providerId, 180_000L),
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

    // ------------------------------------------------------------------
    // stream (SSE)
    // ------------------------------------------------------------------

    override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
        val start = System.currentTimeMillis()
        val model = defaultModelName
        val url = "$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse"
        try {
            val call = client.newCall(requestWithKey(url, buildRequestBody(request, stream = true), stream = true))
            var sequence = 0
            val fullText = StringBuilder()
            var promptTokens = 0
            var completionTokens = 0

            // The whole builder runs on Dispatchers.IO via flowOn below — blocking
            // line reads and vault lookups are safe, emissions stay in-context.
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOExceptionWithCode(response.code, "HTTP ${response.code} from Gemini stream")
                }
                val reader = response.body?.byteStream()
                    ?.bufferedReader(Charsets.UTF_8)
                    ?: throw IOExceptionWithCode(-1, "Empty stream body from Gemini")
                val pending = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) {
                        // End of an SSE event — flush the accumulated data payload.
                        val payload = pending.toString().trim()
                        pending.setLength(0)
                        if (payload.startsWith("data:")) {
                            val json = payload.removePrefix("data:").trim()
                            if (json.isNotBlank() && json != "[DONE]") {
                                val delta = extractDelta(json)
                                if (delta.isNotEmpty()) {
                                    fullText.append(delta)
                                    emit(
                                        ExecutionEvent.ContentChunk(
                                            executionId = executionId,
                                            deltaText = delta,
                                            sequenceIndex = sequence++
                                        )
                                    )
                                }
                                readUsage(json)?.let { (p, c) ->
                                    promptTokens = p
                                    completionTokens = c
                                }
                            }
                        }
                    } else {
                        pending.appendLine(line)
                    }
                }
            }

            if (fullText.isBlank()) {
                // SSE produced nothing (some regions / proxies strip it) — fall back
                // to the single-shot endpoint and emit one chunk. Honest, visible.
                val fallback = generate(request)
                when (fallback) {
                    is Outcome.Success -> {
                        fullText.append(fallback.value.text)
                        emit(
                            ExecutionEvent.ContentChunk(
                                executionId = executionId,
                                deltaText = fallback.value.text,
                                sequenceIndex = sequence++
                            )
                        )
                    }
                    else -> {
                        val diag = (fallback as? Outcome.Error)?.diagnosticMessage
                            ?: (fallback as? Outcome.Degraded)?.diagnosticMessage
                            ?: "Gemini generation failed"
                        emit(
                            ExecutionEvent.Error(
                                executionId = executionId,
                                failureCode = "LLM_ERROR",
                                message = diag
                            )
                        )
                        return@flow
                    }
                }
            }

            emit(
                ExecutionEvent.UsageBudgetUpdate(
                    executionId = executionId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalSessionTokens = promptTokens + completionTokens,
                    remainingBudgetTokens = 30000 - (promptTokens + completionTokens)
                )
            )
            emit(
                ExecutionEvent.Completed(
                    executionId = executionId,
                    finalText = fullText.toString(),
                    totalDurationMs = System.currentTimeMillis() - start
                )
            )
        } catch (e: MissingGeminiKeyException) {
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "AUTHENTICATION_FAILED",
                    message = "لا يوجد مفتاح Gemini API — أضفه من شاشة المزودين (مفتاح API) ثم أعد المحاولة"
                )
            )
        } catch (e: Exception) {
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "LLM_ERROR",
                    message = e.message ?: "حدث خطأ أثناء البث التدفقي لنموذج Gemini"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /** Extracts the concatenated text delta from one SSE data payload. */
    private fun extractDelta(json: String): String {
        return runCatching {
            val obj = JSONObject(json)
            val parts = obj.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            }
            sb.toString()
        }.getOrDefault("")
    }

    private fun readUsage(json: String): Pair<Int, Int>? {
        return runCatching {
            val obj = JSONObject(json)
            val usage = obj.optJSONObject("usageMetadata") ?: return null
            Pair(
                usage.optInt("promptTokenCount", 0),
                usage.optInt("candidatesTokenCount", 0)
            )
        }.getOrNull()
    }

    /** Maps a non-2xx HTTP code onto the domain failure taxonomy. */
    private fun <T> httpFailure(code: Int, url: String): Outcome<T, LlmFailure> = when (code) {
        400 -> Outcome.Error(
            LlmFailure.ProviderUnavailable(providerId, "HTTP 400 — طلب غير صالح (راجع اسم النموذج)"),
            "Bad request: HTTP 400 from $url"
        )
        401, 403 -> Outcome.Error(
            LlmFailure.AuthenticationFailed(providerId, "HTTP $code — مفتاح Gemini غير صالح أو غير مصرّح"),
            "Authentication rejected by $url"
        )
        404 -> Outcome.Error(
            LlmFailure.ProviderUnavailable(providerId, "HTTP 404 — النموذج $defaultModelName غير موجود"),
            "Model not found: $defaultModelName"
        )
        429 -> Outcome.Degraded(
            partialValue = null,
            reason = DegradedReason.RATE_LIMIT_BACKOFF,
            diagnosticMessage = "Rate limited by $url (HTTP 429)",
            underlyingFailure = LlmFailure.RateLimitExceeded(providerId, null, "HTTP 429")
        )
        503 -> Outcome.Degraded(
            partialValue = null,
            reason = DegradedReason.PROVIDER_UNREACHABLE,
            diagnosticMessage = "Gemini temporarily overloaded (HTTP 503)",
            underlyingFailure = LlmFailure.ProviderUnavailable(providerId, "HTTP 503 overloaded")
        )
        else -> Outcome.Error(
            LlmFailure.ProviderUnavailable(providerId, "HTTP $code"),
            "Request failed: HTTP $code from $url"
        )
    }

    /** Thrown when no API key is available — mapped to an honest auth failure. */
    private class MissingGeminiKeyException : Exception("No Gemini API key stored")

    /** Carries an HTTP code through the SSE loop. */
    private class IOExceptionWithCode(val code: Int, message: String) : Exception(message)
}
