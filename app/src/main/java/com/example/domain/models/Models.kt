package com.example.domain.models

data class Project(
    val id: Long,
    val name: String,
    val localPath: String?,
    val description: String?,
    val isDefault: Boolean,
    val createdAt: String,
    val lastOpenedAt: String?
)

data class Session(
    val sessionId: String,
    val projectId: Long,
    val title: String?,
    val agentName: String?,
    val messageCount: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val updatedAt: String
)

data class ChatMessage(
    val id: Long = 0,
    val projectId: Long,
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val providerName: String? = null,
    val modelName: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val status: String? = null, // "success", "degraded", "error"
    val degradedReason: String? = null,
    val createdAt: String = "",
    val toolCalls: List<ToolCallInfo> = emptyList()
)

data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val isSuccess: Boolean = true,
    val durationMs: Long = 0
)

data class AgentConfig(
    val id: Long = 0,
    val projectId: Long,
    val name: String,
    val description: String,
    val agentType: String = "configurable",
    val modelRole: String = "fast_model",
    val tools: List<String> = emptyList(),
    val systemPrompt: String = "",
    val enabled: Boolean = true,
    val budget: Int = 20000,
    val usedTokens: Int = 0,
    val inFlight: Int = 0
)

data class ModelProvider(
    val id: Long = 0,
    val projectId: Long,
    val name: String,
    val providerType: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val defaultModel: String? = null,
    val priority: Int = 1,
    val enabled: Boolean = true,
    val isOnlineOnly: Boolean = false,
    val isCircuitOpen: Boolean = false,
    val healthStatus: Boolean? = null
)

data class SearchProvider(
    val id: Long = 0,
    val projectId: Long,
    val name: String,
    val providerType: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 1,
    val isOnlineOnly: Boolean = false
)

data class EmbeddingProvider(
    val id: Long = 0,
    val projectId: Long,
    val name: String,
    val providerType: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val embeddingModel: String? = null,
    val dimension: Int = 64,
    val enabled: Boolean = true,
    val isDefault: Boolean = false
)

data class LongTermMemory(
    val id: Long = 0,
    val projectId: Long,
    val content: String,
    val memoryType: String = "preference", // "preference", "case", "insight"
    val status: String = "active", // "active", "candidate", "superseded"
    val importance: Float = 1.0f,
    val confidence: Float = 1.0f,
    val provenance: String? = null,
    val timestamp: String = "",
    val similarityScore: Float? = null
)

data class DocumentItem(
    val id: Long = 0,
    val docId: String,
    val title: String,
    val content: String,
    val collectionName: String = "default",
    val chunkCount: Int = 0
)

data class DocumentChunk(
    val chunkIndex: Int,
    val text: String,
    val similarity: Float? = null
)

data class WorkflowPlan(
    val goal: String,
    val steps: List<PlanStep>,
    val executionMode: String = "sequential"
)

data class PlanStep(
    val id: Int,
    val action: String,
    val description: String,
    val agent: String,
    val tools: List<String> = emptyList(),
    var status: String = "pending", // "pending", "running", "done", "error", "skipped"
    var output: String? = null
)

data class WorkflowExecutionResult(
    val goal: String,
    val status: String, // "completed", "completed_with_errors", "stopped_early", "failed"
    val quality: String, // "SUCCESS", "DEGRADED", "FAILED"
    val steps: List<PlanStep>,
    val beliefExpectedValue: Float,
    val beliefBins: List<Float>,
    val addedStepsCount: Int,
    val durationMs: Long
)

data class BeliefState(
    val bins: List<Float>,
    val weights: List<Float>,
    val expectedValue: Float
)

data class GraphNode(
    val id: String,
    val agent: String,
    val description: String,
    val status: String
)

data class WorldState(
    val projectId: Long,
    val activeAgents: Map<String, AgentConfig>,
    val activeProviders: Map<String, ModelProvider>,
    val belief: BeliefState,
    val totalMemoryCount: Int,
    val totalDocumentsCount: Int
)

data class WorkspaceComponent(
    val componentId: String,
    val title: String,
    val iconName: String,
    val isVisible: Boolean,
    val displayOrder: Int,
    val panelWeight: Float
)

data class SystemDiagnostics(
    val cpuUsagePercent: Float,
    val memoryUsedMb: Long,
    val memoryTotalMb: Long,
    val storageFreeMb: Long,
    val isNetworkConnected: Boolean,
    val isOfflineModeEnforced: Boolean,
    val activeTasksCount: Int,
    val uptimeSeconds: Long
)
