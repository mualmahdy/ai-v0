package com.example.runtime.agents

import com.example.domain.models.ToolCallInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface BaseAgent {
    val name: String
    val description: String
    val modelRole: String

    suspend fun execute(task: Map<String, Any>): AgentResult

    fun executeStream(task: Map<String, Any>): Flow<String> {
        return flow {
            val res = execute(task)
            emit(res.response)
        }
    }
}

data class AgentResult(
    val response: String,
    val status: String = "success", // "success", "degraded", "error"
    val providerUsed: String? = null,
    val modelUsed: String? = null,
    val toolTrace: List<ToolCallInfo> = emptyList(),
    val degradedReason: String? = null,
    val error: String? = null,
    val score: Float? = null,
    val rawOutput: Any? = null
)
