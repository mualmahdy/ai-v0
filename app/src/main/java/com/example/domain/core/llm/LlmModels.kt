package com.example.domain.core.llm

import com.example.domain.core.tools.ToolDeclaration

/**
 * Message roles in LLM conversations.
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

/**
 * Single conversational message exchanged with an LLM.
 */
data class LlmMessage(
    val role: MessageRole,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val isUntrustedInput: Boolean = false
)

/**
 * Hyperparameters and control configurations for LLM generation.
 */
data class GenerationConfig(
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int = 2048,
    val topP: Float = 0.95f,
    val stopSequences: List<String> = emptyList(),
    val responseMimeType: String? = null
)

/**
 * Request payload submitted to an LLM provider.
 */
data class LlmRequest(
    val messages: List<LlmMessage>,
    val config: GenerationConfig = GenerationConfig(),
    val availableTools: List<ToolDeclaration> = emptyList(),
    val streamEvents: Boolean = false
)

/**
 * Exact token count telemetry.
 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens,
    val estimatedCostUsd: Double = 0.0
)

/**
 * Parsed request for a tool call emitted by an LLM.
 */
data class ToolCallRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String
)

/**
 * Full non-streaming response from an LLM.
 */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val usage: TokenUsage,
    val finishReason: String? = null,
    val modelId: String = ""
)

/**
 * Safe metadata descriptor for LLM providers (Never exposes secrets or API keys).
 */
data class SafeProviderMetadata(
    val id: String,
    val name: String,
    val providerType: String,
    val defaultModel: String?,
    val isConfigured: Boolean,
    val isOnline: Boolean,
    val isLocal: Boolean,
    val supportedCapabilities: List<String> = emptyList()
)

/**
 * Failures that can occur during LLM communication or generation.
 */
sealed interface LlmFailure {
    data class AuthenticationFailed(val providerId: String, val message: String) : LlmFailure
    data class RateLimitExceeded(val providerId: String, val retryAfterMs: Long?, val message: String) : LlmFailure
    data class ContextLengthExceeded(val providerId: String, val tokenCount: Int, val maxAllowed: Int) : LlmFailure
    data class ProviderUnavailable(val providerId: String, val message: String) : LlmFailure
    data class NetworkTimeout(val providerId: String, val timeoutMs: Long) : LlmFailure
    data class InvalidResponse(val providerId: String, val rawResponse: String?, val reason: String) : LlmFailure
}
