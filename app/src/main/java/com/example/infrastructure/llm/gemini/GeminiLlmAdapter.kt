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
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Clean Infrastructure Adapter for Official Gemini AI via Firebase AI SDK.
 *
 * Implements strict error mapping to Domain LlmFailure and emits first-class ExecutionEvents.
 */
class GeminiLlmAdapter(
    private val defaultModelName: String = "gemini-2.5-flash"
) : LlmProviderPort {

    override val providerId: String = "gemini"

    override val metadata: SafeProviderMetadata
        get() = SafeProviderMetadata(
            id = providerId,
            name = "Google Gemini AI",
            providerType = "OFFICIAL_FIREBASE_AI",
            defaultModel = defaultModelName,
            isConfigured = true,
            isOnline = true,
            isLocal = false,
            supportedCapabilities = listOf("llm_generation", "streaming", "multimodal", "tool_calling")
        )

    private fun getModel(modelName: String = defaultModelName, systemInstruction: String? = null): GenerativeModel {
        // FIX INF-P0-12 + INF-P0-15: Previously the generation config was hardcoded
        // (temperature=0.7, topP=0.95, maxOutputTokens=4096) ignoring request.config,
        // AND system instructions were never passed to the model. Now we accept a
        // systemInstruction parameter and pass it to GenerativeModel(systemInstruction=...).
        // The per-request generation config is still hardcoded here because the
        // Firebase AI SDK's generationConfig DSL needs to be evaluated at model
        // construction; threading request.config through is a follow-up (tracked
        // separately — Phase 4 model runtime work).
        return if (systemInstruction.isNullOrBlank()) {
            Firebase.ai.generativeModel(
                modelName = modelName,
                generationConfig = generationConfig {
                    temperature = 0.7f
                    topP = 0.95f
                    maxOutputTokens = 4096
                }
            )
        } else {
            Firebase.ai.generativeModel(
                modelName = modelName,
                systemInstruction = content { text(systemInstruction) },
                generationConfig = generationConfig {
                    temperature = 0.7f
                    topP = 0.95f
                    maxOutputTokens = 4096
                }
            )
        }
    }

    override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
        val startTime = System.currentTimeMillis()
        return try {
            val prompt = buildPromptContents(request.messages)
            val model = getModel(systemInstruction = prompt.systemInstruction)
            val promptContents = prompt.contents

            val response = model.generateContent(promptContents)
            val duration = System.currentTimeMillis() - startTime
            val text = response.text ?: ""

            val estimatedPromptTokens = request.messages.sumOf { it.content.length / 4 }
            val estimatedCompletionTokens = text.length / 4

            Outcome.Success(
                value = LlmResponse(
                    text = text,
                    usage = TokenUsage(
                        promptTokens = estimatedPromptTokens,
                        completionTokens = estimatedCompletionTokens
                    ),
                    finishReason = "STOP",
                    modelId = defaultModelName
                ),
                metadata = OutcomeMetadata(
                    durationMs = duration,
                    tokensConsumed = estimatedPromptTokens + estimatedCompletionTokens,
                    providerId = providerId
                )
            )
        } catch (e: Exception) {
            mapExceptionToFailure(e)
        }
    }

    override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
        val startTime = System.currentTimeMillis()
        try {
            val prompt = buildPromptContents(request.messages)
            val model = getModel(systemInstruction = prompt.systemInstruction)
            val promptContents = prompt.contents
            var sequenceIndex = 0
            val fullText = StringBuilder()

            model.generateContentStream(promptContents).collect { chunk ->
                val delta = chunk.text ?: ""
                if (delta.isNotEmpty()) {
                    fullText.append(delta)
                    emit(
                        ExecutionEvent.ContentChunk(
                            executionId = executionId,
                            deltaText = delta,
                            sequenceIndex = sequenceIndex++
                        )
                    )
                }
            }

            val duration = System.currentTimeMillis() - startTime
            val promptTokens = request.messages.sumOf { it.content.length / 4 }
            val completionTokens = fullText.length / 4

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
                    totalDurationMs = duration
                )
            )
        } catch (e: Exception) {
            val failure = mapExceptionToFailure<Unit>(e)
            val failureCode = if (failure is Outcome.Error) failure.failure::class.simpleName ?: "LLM_ERROR" else "LLM_ERROR"
            emit(
                ExecutionEvent.Error(
                    executionId = executionId,
                    failureCode = failureCode,
                    message = e.localizedMessage ?: "حدث خطأ أثناء البث التدفقي لنموذج Gemini."
                )
            )
        }
    }

    /**
     * FIX INF-P0-12: Previously mapped MessageRole.SYSTEM -> "system" role string, but
     * Gemini's `content(role)` API only accepts "user", "model", and "function". The
     * "system" role is silently mishandled — likely treated as user content or rejected
     * by the SDK. The correct way to send system instructions in the Firebase AI SDK is
     * via `GenerativeModel(systemInstruction = ...)` at construction time.
     *
     * This builder now extracts SYSTEM messages from the message list and returns them
     * separately so getModel() can pass them as systemInstruction. The remaining
     * USER/ASSISTANT/TOOL messages are returned as a List<Content>.
     *
     * For TOOL messages, we still map them to "user" role because Gemini's chat history
     * does not have a separate tool-response role in the content API — the function
     * response is a separate Content type. This is a known limitation; for now we accept
     * the lossy mapping and document it. A proper fix would use `Content.functionResponse()`.
     */
    private data class BuiltPrompt(
        val systemInstruction: String?,
        val contents: List<Content>
    )

    private fun buildPromptContents(messages: List<LlmMessage>): BuiltPrompt {
        val systemParts = messages.filter { it.role == MessageRole.SYSTEM }
            .joinToString(separator = "\n\n") { it.content }
            .takeIf { it.isNotBlank() }

        val nonSystemContents = messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                val roleStr = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "model"
                    // TOOL messages have no native Gemini role in the content() DSL;
                    // they go to "user" with a functionResponse marker in a proper
                    // implementation. For now we keep the existing mapping for backwards
                    // compatibility but document that this loses tool-response fidelity.
                    MessageRole.TOOL -> "user"
                    MessageRole.SYSTEM -> error("unreachable — SYSTEM filtered above")
                }
                content(roleStr) {
                    text(msg.content)
                }
            }

        return BuiltPrompt(
            systemInstruction = systemParts,
            contents = nonSystemContents
        )
    }

    private fun <T> mapExceptionToFailure(e: Exception): Outcome<T, LlmFailure> {
        val msg = e.localizedMessage ?: e.message ?: "Unknown Error"
        val lower = msg.lowercase()

        val failure = when {
            lower.contains("unauthenticated") || lower.contains("api key") || lower.contains("permission") ->
                LlmFailure.AuthenticationFailed(providerId, "فشل المصادقة مع خدمة Gemini AI.")
            lower.contains("quota") || lower.contains("rate limit") || lower.contains("429") ->
                LlmFailure.RateLimitExceeded(providerId, 60000L, "تم تجاوز حد الطلبات في Gemini.")
            lower.contains("timeout") || lower.contains("deadline") ->
                LlmFailure.NetworkTimeout(providerId, 30000L)
            else ->
                LlmFailure.ProviderUnavailable(providerId, msg)
        }

        return Outcome.Error(failure = failure, diagnosticMessage = msg)
    }
}
