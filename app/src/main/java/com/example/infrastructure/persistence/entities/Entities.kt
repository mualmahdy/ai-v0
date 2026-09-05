package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val description: String?,
    val rootPath: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isArchived: Boolean = false
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val sessionId: String,
    val projectId: Long,
    val title: String,
    val assignedAgentId: String,
    val activeModelId: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val totalTokensConsumed: Int = 0
)

@Entity(tableName = "memory_records")
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val text: String,
    val vectorDimension: Int,
    val vectorJson: String, // Normalized float array serialized as JSON
    val source: String,
    val confidence: Float,
    val createdAtEpochMs: Long,
    val lastAccessedEpochMs: Long,
    val accessCount: Int = 1,
    val isArchived: Boolean = false
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val executionId: String,
    val sessionId: String,
    val eventType: String,
    val payloadJson: String,
    val timestampEpochMs: Long
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val assignedAgentId: String,
    val rawPrompt: String,
    val lifecycleState: String, // CREATED, PLANNING, RUNNING, COMPLETED, DEGRADED, FAILED, CANCELLED, WAITING
    val autonomyPolicy: String,
    val resultSummary: String?,
    val totalTokensConsumed: Int = 0,
    val durationMs: Long = 0L,
    val isDegraded: Boolean = false,
    val degradedReason: String? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    // FIX APP-P0-07 (DOM-P0-02): Added fields to support full TaskDefinition round-trip
    // so that resumeTask can reconstruct the original task instead of dropping most fields.
    val goal: String = rawPrompt,
    val currentStepIndex: Int = 0,
    val tokenLimit: Int = 30000,
    val maxRetries: Int = 3,
    val allowDegradedExecution: Boolean = true,
    val requireHumanConsentForSensitiveTools: Boolean = true,
    val timeoutMs: Long = 60000L,
    val minOutputLengthChars: Int = 1,
    val verificationStrategy: String = "STRICT",
    val assignedModelId: String? = null,
    val activeToolsJson: String? = null,         // JSON array of tool IDs
    val requiredCapabilitiesJson: String? = null, // JSON array of CapabilityType names
    val requiredEvidenceKeysJson: String? = null, // JSON array of evidence keys
    val requiredOutputKeysJson: String? = null,   // JSON array of output keys
    val executionLogJson: String? = null          // JSON array of log entries
)

@Entity(tableName = "decision_cases")
data class DecisionCaseEntity(
    @PrimaryKey
    val id: String,
    val featuresJson: String, // Float array as JSON
    val actionType: String,
    val targetId: String?,
    val outcomeReward: Float,
    val taskType: String,
    val timestampEpochMs: Long
)

@Entity(tableName = "radar_items")
data class RadarItemEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val sourceUrl: String,
    val sourceName: String,
    val relevanceScore: Float,
    val confidence: Float,
    val provenance: String, // LIVE_RSS_FEED, LIVE_GITHUB_API, LOCAL_PERSISTENT_CACHE
    val tagsJson: String,
    val extractedCapabilityJson: String?,
    val discoveredTimestampEpochMs: Long
)

@Entity(tableName = "evolution_candidates")
data class EvolutionCandidateEntity(
    @PrimaryKey
    val id: String,
    val radarItemId: String,
    val title: String,
    val description: String,
    val stage: String, // DISCOVERED, UNDERSTOOD, CLASSIFIED, EVALUATED, CANDIDATE, APPROVAL, INTEGRATED, VERIFIED, REGISTERED
    val targetType: String,
    val evaluationNotes: String,
    val securityAuditPassed: Boolean,
    val governanceApproved: Boolean,
    val confidence: Float,
    val provenanceUrl: String,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "extension_configs")
data class ExtensionConfigEntity(
    @PrimaryKey
    val id: String,
    val type: String, // SKILL, PLUGIN, MCP_SERVER, INTEGRATION
    val name: String,
    val endpointOrConfig: String,
    val isEnabled: Boolean,
    val isConnected: Boolean,
    val healthStatus: String, // HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN, NOT_CONFIGURED
    val authMetadataJson: String?,
    val lastVerifiedEpochMs: Long
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val category: String, // LLM, EMBEDDING, SEARCH, VECTOR_STORE
    val flavor: String, // GEMINI, OPENAI_COMPATIBLE, OLLAMA, TAVILY, MULTI_SOURCE_SEARCH, LOCAL_EMBEDDING
    val endpointUrl: String,
    val defaultModelId: String,
    val isEnabled: Boolean,
    val isDefault: Boolean,
    val healthStatus: String, // HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN
    val lastValidatedEpochMs: Long,
    val lastLatencyMs: Long,
    val lastErrorMessage: String?,
    val extraHeadersJson: String?,
    val timeoutSeconds: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)


/**
 * FIX D-1 / D-4 (audit c03919d): tabular MDP Q-table cell.
 * One row per (state-region, action) pair — stores the learned Q value and
 * transition statistics so the CBR-MDP engine accumulates REAL experience
 * across sessions (previously estimates were in-memory per-action-type only
 * and were wiped on every restart).
 */
@Entity(
    tableName = "mdp_q_values",
    primaryKeys = ["regionKey", "actionType"]
)
data class MdpQValueEntity(
    val regionKey: String,
    val actionType: String,
    val qValue: Float,
    val visitCount: Int,
    val successCount: Int,
    val lastUpdatedEpochMs: Long
)
