package com.example.runtime.providers

import com.example.domain.models.ToolCallInfo
import kotlinx.coroutines.flow.Flow

interface BaseModelProvider {
    val name: String
    val providerType: String
    val isOnlineOnly: Boolean

    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null,
        model: String? = null
    ): String

    fun streamGenerate(
        prompt: String,
        systemInstruction: String? = null,
        model: String? = null
    ): Flow<String>

    suspend fun generateWithTools(
        prompt: String,
        availableTools: List<String>,
        systemInstruction: String? = null
    ): ModelToolResult

    suspend fun healthCheck(): Boolean
}

data class ModelToolResult(
    val content: String?,
    val requestedToolCalls: List<ToolCallInfo> = emptyList(),
    val status: String = "success" // "success", "degraded", "error"
)
