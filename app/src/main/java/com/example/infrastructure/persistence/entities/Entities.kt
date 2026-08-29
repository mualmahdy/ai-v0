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
    val lifecycleState: String, // CREATED, PLANNING, RUNNING, COMPLETED, FAILED, DEGRADED, CANCELLED
    val autonomyPolicy: String,
    val resultSummary: String?,
    val totalTokensConsumed: Int = 0,
    val durationMs: Long = 0L,
    val isDegraded: Boolean = false,
    val degradedReason: String? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
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
