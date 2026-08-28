package com.example.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val localPath: String? = null,
    val description: String? = null,
    val isDefault: Boolean = false,
    val createdAt: String = "",
    val lastOpenedAt: String? = null
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val projectId: Long,
    val title: String? = null,
    val agentName: String? = null,
    val messageCount: Int = 0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val createdAt: String = ""
)

@Entity(tableName = "agents_config")
data class AgentConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val description: String = "",
    val agentType: String = "configurable",
    val modelRole: String = "fast_model",
    val toolsJson: String = "[]",
    val systemPrompt: String = "",
    val enabled: Boolean = true,
    val budget: Int? = null,
    val createdAt: String = ""
)

@Entity(tableName = "model_providers")
data class ModelProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val providerType: String, // "local_heuristic", "gemini", "openai_compatible", "ollama"
    val baseUrl: String? = null,
    val apiKeyEncrypted: String? = null,
    val defaultModel: String? = null,
    val priority: Int = 1,
    val enabled: Boolean = true,
    val isOnlineOnly: Boolean = false,
    val tagsJson: String = "[]",
    val capabilitiesJson: String = "{}",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Entity(tableName = "model_roles")
data class ModelRoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val roleName: String,
    val providerId: Long,
    val modelName: String
)

@Entity(tableName = "search_providers")
data class SearchProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val providerType: String, // "offline_knowledge", "local_memory", "brave", "google"
    val apiKeyEncrypted: String? = null,
    val baseUrl: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 1,
    val isOnlineOnly: Boolean = false,
    val configJson: String = "{}",
    val createdAt: String = ""
)

@Entity(tableName = "embedding_providers")
data class EmbeddingProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val providerType: String, // "local_embedder", "gemini", "ollama"
    val baseUrl: String? = null,
    val apiKeyEncrypted: String? = null,
    val embeddingModel: String? = null,
    val dimension: Int? = 64,
    val priority: Int = 1,
    val enabled: Boolean = true,
    val isDefault: Boolean = false
)

@Entity(tableName = "long_term_memory")
data class LongTermMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val userId: String = "default",
    val content: String,
    val embeddingJson: String? = null,
    val embeddingDimension: Int? = null,
    val importance: Float = 1.0f,
    val memoryType: String = "preference", // "preference", "case", "insight"
    val status: String = "active", // "active", "candidate", "superseded"
    val confidence: Float = 1.0f,
    val provenance: String? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val agentId: String? = null,
    val supersededBy: Long? = null,
    val timestamp: String = ""
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val docId: String,
    val title: String,
    val content: String,
    val collectionName: String = "default",
    val metadataJson: String = "{}"
)

@Entity(tableName = "document_chunks")
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val docId: String,
    val chunkIndex: Int,
    val text: String,
    val embeddingJson: String? = null,
    val embeddingProvider: String? = null,
    val embeddingDimension: Int? = null
)

@Entity(tableName = "knowledge_collections")
data class KnowledgeCollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val description: String? = null,
    val createdAt: String = ""
)

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val description: String? = null,
    val templateJson: String = "{}",
    val createdAt: String = ""
)

@Entity(tableName = "workflow_state")
data class WorkflowStateEntity(
    @PrimaryKey val workflowId: String,
    val projectId: Long,
    val stateJson: String,
    val updatedAt: String = ""
)

@Entity(tableName = "token_budget_usage")
data class TokenBudgetUsageEntity(
    @PrimaryKey val compositeKey: String, // "projectId_agentName"
    val projectId: Long,
    val agentName: String,
    val usedTokens: Int,
    val updatedAt: String = ""
)

@Entity(tableName = "file_versions")
data class FileVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val relativePath: String,
    val content: String,
    val sizeBytes: Long,
    val createdAt: String = ""
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventTopic: String,
    val payloadJson: String,
    val timestamp: String = ""
)

@Entity(tableName = "workspace_components")
data class WorkspaceComponentEntity(
    @PrimaryKey val componentId: String,
    val title: String,
    val iconName: String,
    val isVisible: Boolean = true,
    val displayOrder: Int = 0,
    val panelWeight: Float = 1.0f
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
