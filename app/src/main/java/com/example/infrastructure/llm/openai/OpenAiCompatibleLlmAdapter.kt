package com.example.infrastructure.llm.openai

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
import com.example.domain.core.llm.ToolCallRequest
import com.example.domain.core.tools.ToolDeclaration
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
import java.io.InputStreamReader
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
 * REAL protocol support (audit 2026 remediation):
 *  1. Function calling — `request.availableTools` are serialized as
 *     `tools: [{type:"function", function:{...}}]` and `tool_calls` returned
 *     by the model are parsed into domain `ToolCallRequest`s (both in
 *     `generate` and during SSE streaming). Previously the adapter silently
 *     dropped tool declarations — the declared capability did not exist.
 *  2. Real SSE streaming — `stream()` sends `"stream": true` and emits one
 *     `ContentChunk` per delta. Previously it called `generate()` and emitted
 *     a single fake chunk (streaming was a lie).
 *  3. Tool-result round-trip — TOOL-role messages are serialized as
 *     `{"role":"tool","tool_call_id":...,"content":...}` so multi-turn tool
 *     conversations work.
 *  4. Honest locality — `isLocal` is derived from the endpoint host so the
 *     OFFLINE network policy can correctly admit local Ollama/LM Studio
 *     endpoints instead of blanket-rejecting every OpenAI-protocol resource.
 *  5. Token usage is reported both in `LlmResponse.usage` and via
 *     `UsageBudgetUpdate` + `Completed` events during streaming.
 */
class OpenAiCompatibleLlmAdapter(
    private val baseUrl: String,
    private val apiKeyProvider: suspend () -> String?,
    private val defaultModel: String = "gpt-4o-mini",
    override val providerId: String = "openai_compatible",
    /** EGRESS ENFORCEMENT: scoped, fail-closed transport guard. */
    private val egressControl: com.example.infrastructure.network.EgressControl =
        com.example.infrastructure.network.EgressControl.default,
    private val client: OkHttpClient = OkHttpClient.Builder()
        // EGRESS ENFORCEMENT (report gap: sandbox network-egress restrictions):
        // the guard evaluates the REQUEST-SCOPED workspace policy and fails
        // CLOSED (IOException) before any socket is opened. No pinned policy
        // for the governing workspace = deny (never allow-by-default).
        .addInterceptor(egressControl.interceptor())
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
            isLocal = isLocalEndpoint(baseUrl),
            supportedCapabilities = listOf(
                "llm_generation", "streaming", "tool_calling", "function_calling"
            )
        )

    override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
        withContext(Dispatchers.IO) {
            val model = defaultModel
            val start = System.currentTimeMillis()
            try {
                val body = buildJsonBody(request, stream = false)

                val url = normalizeBaseUrl(baseUrl) + "/chat/completions"
                val builder = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                egressControl.applyEgressScope(builder)
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
                    val message = choice?.optJSONObject("message")
                    val content = message?.optString("content") ?: ""
                    val toolCalls = parseToolCalls(message)
                    val usageJson = json.optJSONObject("usage")
                    val usage = TokenUsage(
                        promptTokens = usageJson?.optInt("prompt_tokens", 0) ?: 0,
                        completionTokens = usageJson?.optInt("completion_tokens", 0) ?: 0,
                        // GOVERNANCE PHASE: cached + provider-total capture
                        // (prompt_tokens_details.cached_tokens / total_tokens).
                        cachedTokens = usageJson?.optJSONObject("prompt_tokens_details")
                            ?.optInt("cached_tokens", 0) ?: 0,
                        totalTokens = usageJson?.takeIf { it.has("total_tokens") }
                            ?.optInt("total_tokens", 0)
                            ?: ((usageJson?.optInt("prompt_tokens", 0) ?: 0) + (usageJson?.optInt("completion_tokens", 0) ?: 0))
                    )
                    Outcome.Success(
                        LlmResponse(
                            text = content,
                            toolCalls = toolCalls,
                            usage = usage,
                            finishReason = choice?.optString("finish_reason"),
                            modelId = json.optString("model", model)
                        ),
                        OutcomeMetadata(
                            durationMs = System.currentTimeMillis() - start,
                            tokensConsumed = usage.promptTokens + usage.completionTokens,
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

    /**
     * REAL SSE streaming: emits one ContentChunk per delta token, ToolRequested
     * per streamed tool call, UsageBudgetUpdate + Completed at the end. The
     * builder runs on Dispatchers.IO (blocking reads are safe there).
     */
    override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
        val start = System.currentTimeMillis()
        val fullText = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0
        // GOVERNANCE PHASE: cached + provider-total capture + estimate marker.
        var cachedTokens = 0
        var providerTotalTokens: Int? = null
        var usageWasReported = false
        try {
            val body = buildJsonBody(request, stream = true)
            val url = normalizeBaseUrl(baseUrl) + "/chat/completions"
            val reqBuilder = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Accept", "text/event-stream")
            egressControl.applyEgressScope(reqBuilder)
            apiKeyProvider()?.takeIf { it.isNotBlank() }?.let { key ->
                reqBuilder.addHeader("Authorization", "Bearer $key")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()
                    emit(
                        ExecutionEvent.Error(
                            executionId = executionId,
                            failureCode = when (response.code) {
                                401, 403 -> "AUTHENTICATION_FAILED"
                                429 -> "RATE_LIMITED"
                                else -> "LLM_ERROR"
                            },
                            message = "HTTP ${response.code} من $url ${errBody?.take(200) ?: ""}"
                        )
                    )
                    return@use
                }
                val responseBody = response.body
                if (responseBody == null) {
                    emit(
                        ExecutionEvent.Error(
                            executionId = executionId,
                            failureCode = "EMPTY_RESPONSE_BODY",
                            message = "استجابة فارغة من النقطة النهائية."
                        )
                    )
                    return@use
                }

                var sequenceIndex = 0
                // ------------------------------------------------------------------
                // TOOL-CALL FRAGMENT ACCUMULATION (defect family 6 — streamed
                // tool calls under fragmented SSE chunks):
                // OpenAI-style streaming delivers tool calls as INDEX-KEYED
                // deltas: the first delta carries (id, name, first arguments
                // fragment); continuation deltas carry ONLY (index,
                // function.arguments). The previous implementation emitted a
                // ToolRequested PER DELTA — executing the FIRST fragment with
                // truncated/empty arguments and silently DROPPING every
                // continuation fragment. Now: id, name and argument fragments
                // are accumulated until the stream terminates, and a tool call
                // is emitted ONLY when a complete, valid JSON argument object
                // exists. Incomplete/malformed accumulations are NEVER
                // executed and are surfaced as an honest Degraded event.
                // ------------------------------------------------------------------
                val toolCallAccumulator = StreamedToolCallAccumulator(executionId)
                var malformedChunks = 0
                val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data:")) continue
                    val payload = l.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue

                    runCatching {
                        val chunk = JSONObject(payload)
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@runCatching
                        val delta = choice.optJSONObject("delta")
                        if (delta != null) {
                            val deltaText = delta.optString("content", "")
                            if (deltaText.isNotEmpty()) {
                                fullText.append(deltaText)
                                emit(
                                    ExecutionEvent.ContentChunk(
                                        executionId = executionId,
                                        deltaText = deltaText,
                                        sequenceIndex = sequenceIndex++
                                    )
                                )
                            }
                            // Streamed tool calls arrive as index-keyed deltas —
                            // ACCUMULATE, never execute a fragment.
                            delta.optJSONArray("tool_calls")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    val tc = arr.optJSONObject(i) ?: continue
                                    val fn = tc.optJSONObject("function")
                                    toolCallAccumulator.offer(
                                        index = tc.optInt("index", i),
                                        id = tc.optString("id", "").takeIf { it.isNotBlank() },
                                        name = fn?.optString("name", "")?.takeIf { it.isNotBlank() },
                                        argumentsFragment = fn?.optString("arguments", "") ?: ""
                                    )
                                }
                            }
                        }
                        chunk.optJSONObject("usage")?.let { u ->
                            usageWasReported = true
                            promptTokens = u.optInt("prompt_tokens", promptTokens)
                            completionTokens = u.optInt("completion_tokens", completionTokens)
                            cachedTokens = u.optJSONObject("prompt_tokens_details")
                                ?.optInt("cached_tokens", cachedTokens) ?: cachedTokens
                            if (u.has("total_tokens")) providerTotalTokens = u.optInt("total_tokens", 0)
                        }
                    }.onFailure { malformedChunks++ }
                }

                // FAIL-SAFE: chunks that could not be parsed are counted and
                // surfaced honestly — never silently dropped.
                if (malformedChunks > 0) {
                    emit(
                        ExecutionEvent.Degraded(
                            executionId = executionId,
                            reason = com.example.domain.core.DegradedReason.UNKNOWN_DEGRADATION,
                            message = "TOOL_STREAM_MALFORMED_CHUNKS: تعذّر تحليل $malformedChunks مقطعاً من مقاطع البث — لم تُنفّذ أي استدعاءات أدوات مستمدة منها."
                        )
                    )
                }

                // FINAL FLUSH: emit accumulated COMPLETE tool calls only. A
                // call executes ONLY when its accumulated arguments form a
                // valid JSON object (or the provider sent no argument
                // fragments at all = empty args).
                toolCallAccumulator.drainComplete().forEach { call ->
                    emit(
                        ExecutionEvent.ToolRequested(
                            executionId = executionId,
                            callId = call.callId,
                            toolName = call.toolName,
                            argumentsJson = call.argumentsJson
                        )
                    )
                }
                // Incomplete/malformed accumulations FAIL SAFELY: they are
                // never executed, and the loss is reported.
                toolCallAccumulator.drainIncomplete().forEach { incomplete ->
                    emit(
                        ExecutionEvent.Degraded(
                            executionId = executionId,
                            reason = com.example.domain.core.DegradedReason.UNKNOWN_DEGRADATION,
                            message = incomplete
                        )
                    )
                }

                // Heuristic fallback ONLY when the provider reported nothing —
                // and marked as estimated (never presented as measured usage).
                val isEstimated = !usageWasReported
                if (promptTokens == 0) promptTokens = request.messages.sumOf { it.content.length / 4 }
                if (completionTokens == 0) completionTokens = fullText.length / 4

                // GOVERNANCE PHASE FIX: measured usage only — the fabricated
                // `30000 - consumed` remaining is gone (REMAINING_UNKNOWN);
                // ExecutionService enriches with the REAL task budget.
                val measuredTotal = providerTotalTokens ?: (promptTokens + completionTokens + cachedTokens)
                emit(
                    ExecutionEvent.UsageBudgetUpdate(
                        executionId = executionId,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalSessionTokens = measuredTotal,
                        remainingBudgetTokens = ExecutionEvent.UsageBudgetUpdate.REMAINING_UNKNOWN,
                        cachedTokens = cachedTokens,
                        totalTokens = measuredTotal,
                        providerId = providerId,
                        modelId = defaultModel,
                        isEstimatedUsage = isEstimated
                    )
                )
                emit(
                    ExecutionEvent.Completed(
                        executionId = executionId,
                        finalText = fullText.toString(),
                        totalDurationMs = System.currentTimeMillis() - start
                    )
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "NETWORK_TIMEOUT",
                    message = e.message ?: "انتهت مهلة الشبكة أثناء البث."
                )
            )
        } catch (e: Exception) {
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = "STREAM_ERROR",
                    message = e.message ?: "خطأ غير معروف أثناء البث."
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Builds the OpenAI Chat Completions request body: messages (including
     * tool results), model params, and function tool declarations.
     */
    private fun buildJsonBody(request: LlmRequest, stream: Boolean): JSONObject {
        return JSONObject().apply {
            put("model", defaultModel)
            put("temperature", request.config.temperature.toDouble())
            put("max_tokens", request.config.maxOutputTokens)
            put("stream", stream)
            if (request.config.stopSequences.isNotEmpty()) {
                put("stop", JSONArray(request.config.stopSequences))
            }
            val messagesArray = JSONArray()
            request.messages.forEach { msg ->
                val obj = JSONObject()
                obj.put("role", msg.role.wireName)
                obj.put("content", msg.content)
                if (msg.role == MessageRole.TOOL) {
                    msg.toolCallId?.let { obj.put("tool_call_id", it) }
                    msg.name?.let { obj.put("name", it) }
                }
                messagesArray.put(obj)
            }
            put("messages", messagesArray)

            // REAL protocol support: declare available tools to the model.
            if (request.availableTools.isNotEmpty()) {
                val toolsArray = JSONArray()
                request.availableTools.forEach { tool ->
                    val fn = JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                    val parameters = JSONObject().put("type", "object")
                    val properties = JSONObject()
                    val required = JSONArray()
                    tool.parameters.forEach { param ->
                        val prop = JSONObject().put("type", param.type.ifBlank { "string" })
                        if (param.description.isNotBlank()) prop.put("description", param.description)
                        if (param.enumValues.isNotEmpty()) prop.put("enum", JSONArray(param.enumValues))
                        properties.put(param.name, prop)
                        if (param.isRequired) required.put(param.name)
                    }
                    parameters.put("properties", properties)
                    if (required.length() > 0) parameters.put("required", required)
                    fn.put("parameters", parameters)
                    toolsArray.put(JSONObject().put("type", "function").put("function", fn))
                }
                put("tools", toolsArray)
            }
        }
    }

    /** Parses a non-streaming assistant message's tool_calls into domain requests. */
    private fun parseToolCalls(message: JSONObject?): List<ToolCallRequest> {
        if (message == null) return emptyList()
        val arr = message.optJSONArray("tool_calls") ?: return emptyList()
        val calls = mutableListOf<ToolCallRequest>()
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            calls.add(
                ToolCallRequest(
                    // Deterministic fallback id (unique per message, never a
                    // wall-clock collision across parallel calls).
                    callId = tc.optString("id", "").takeIf { it.isNotBlank() }
                        ?: "call_msg_${tc.hashCode()}_$i",
                    toolName = name,
                    argumentsJson = fn.optString("arguments", "{}").ifBlank { "{}" }
                )
            )
        }
        return calls
    }

    /**
     * TOOL-CALL FRAGMENT ACCUMULATOR (defect family 6).
     *
     * Accumulates streamed tool-call deltas keyed by their `index`:
     *  - the first delta carries `id` + `function.name` + the FIRST
     *    `function.arguments` fragment;
     *  - continuation deltas carry ONLY `index` + `function.arguments`.
     *
     * A call becomes executable only when (a) a tool name is known and
     * (b) the accumulated arguments parse as a valid JSON object (or no
     * argument fragments arrived at all = `{}`). Anything else is
     * incomplete — it is NEVER executed and is reported for honest
     * degradation instead.
     */
    private class StreamedToolCallAccumulator(
        private val executionId: String
    ) {
        private class Partial {
            var id: String? = null
            var name: String? = null
            var sawAnyArgumentFragment = false
            val arguments = StringBuilder()
        }

        private val byIndex = LinkedHashMap<Int, Partial>()

        fun offer(
            index: Int,
            id: String?,
            name: String?,
            argumentsFragment: String
        ) {
            val partial = byIndex.getOrPut(index) { Partial() }
            if (id != null) partial.id = id
            if (name != null) partial.name = name
            if (argumentsFragment.isNotEmpty()) {
                partial.sawAnyArgumentFragment = true
                partial.arguments.append(argumentsFragment)
            }
        }

        /** Complete, VALID calls — the only ones allowed to execute. */
        fun drainComplete(): List<ToolCallRequest> {
            val complete = mutableListOf<ToolCallRequest>()
            for ((index, partial) in byIndex) {
                val name = partial.name
                if (name.isNullOrBlank()) continue // incomplete — handled below
                val rawArgs = partial.arguments.toString()
                val args = when {
                    !partial.sawAnyArgumentFragment -> "{}" // provider sent no args = empty args
                    else -> if (isValidJsonObject(rawArgs)) rawArgs else continue
                }
                complete.add(
                    ToolCallRequest(
                        callId = partial.id ?: "call_${executionId}_$index",
                        toolName = name,
                        argumentsJson = args
                    )
                )
            }
            return complete
        }

        /** Human-readable reports for accumulations that must NOT execute. */
        fun drainIncomplete(): List<String> {
            val reports = mutableListOf<String>()
            for ((index, partial) in byIndex) {
                val name = partial.name
                if (name.isNullOrBlank()) {
                    reports.add(
                        "TOOL_CALL_INCOMPLETE: استدعاء أداة #$index وصل بدون اسم — لم يُنفّذ."
                    )
                    continue
                }
                val rawArgs = partial.arguments.toString()
                if (partial.sawAnyArgumentFragment && !isValidJsonObject(rawArgs)) {
                    reports.add(
                        "TOOL_CALL_ARGUMENTS_INCOMPLETE: استدعاء '$name' (#$index) وصلت وسائطه مجزّأة غير قابلة للتحليل — لم يُنفّذ."
                    )
                }
            }
            return reports
        }

        private fun isValidJsonObject(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            return try {
                org.json.JSONObject(trimmed)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        fun normalizeBaseUrl(url: String): String {
            if (url.isBlank()) return ""
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }

        /**
         * Honest locality detection: localhost, loopback, link-local, private
         * RFC1918 ranges, the Android emulator host, and mDNS .local names are
         * all local-network endpoints. This is what makes OFFLINE policy work
         * with on-device model servers (Ollama, LM Studio).
         */
        fun isLocalEndpoint(url: String): Boolean {
            val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }
                .getOrNull() ?: url.lowercase()
            if (host.isBlank()) return false
            return host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
                host == "10.0.2.2" || host.endsWith(".local") ||
                host.startsWith("192.168.") || host.startsWith("10.") ||
                host.startsWith("172.16.") || host.startsWith("172.17.") ||
                host.startsWith("172.18.") || host.startsWith("172.19.") ||
                host.startsWith("172.2") || host.startsWith("172.30.") || host.startsWith("172.31.")
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
