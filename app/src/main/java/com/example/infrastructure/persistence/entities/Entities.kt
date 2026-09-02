package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.Index
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

// =============================================================================
// P0 RESOURCE CONTRACT (APPROVED-BASELINE v2.1) — schema revision v4
// =============================================================================

/**
 * P0.2 — ResourceRecord persistence (Section B locked contract, Room projection).
 * The (providerId, serviceId) logical key is UNIQUE at the database level,
 * enforcing RULE REG-1 / REG-4 (one ResourceRecord per logical key).
 */
@Entity(
    tableName = "resource_records",
    indices = [Index(value = ["providerId", "serviceId"], unique = true)]
)
data class ResourceRecordEntity(
    @PrimaryKey
    val resourceId: String,
    val providerId: String,
    val serviceId: String,
    val resourceType: String,         // LLM, EMBEDDING, SEARCH, TOOL, AGENT, STORAGE
    val category: String,             // REMOTE or LOCAL
    val capabilitiesJson: String,     // CapabilityId list serialized as JSON array
    val lifecycleState: String,       // CONFIGURED, VALIDATING, HEALTHY, UNAVAILABLE, DISABLED
    val runtimeSupported: Boolean,    // SEPARATE from lifecycleState
    val configurationVersion: Int,
    val isFallback: Boolean = false,
    val registeredAt: Long,
    val lastStateChangeAt: Long,
    val metadataJson: String          // Extensible, flat key-value JSON object
)

/**
 * P0.3 — Health snapshot persistence (best-effort restart survival, Section G).
 */
@Entity(tableName = "resource_health_snapshots")
data class ResourceHealthSnapshotEntity(
    @PrimaryKey
    val resourceId: String,
    val successRate: Double,
    val averageLatencyMs: Long,
    val p95LatencyMs: Long,
    val timeoutRate: Double,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastFailureReason: String?,
    val inCooldownUntil: Long?,
    val sampleSize: Int,
    val healthScore: Double,
    val consecutiveFailures: Int,
    val windowJson: String,
    val updatedAt: Long
)

/**
 * P0.4 — DecisionRecord persistence (Section F: "persisted via Room `decisions` table").
 */
@Entity(tableName = "decision_records")
data class DecisionRecordEntity(
    @PrimaryKey
    val decisionId: String,
    val taskId: String,
    val stepId: String,
    val timestamp: Long,
    val decisionVersion: Int,
    val selectedResourceId: String,
    val selectedProviderId: String,
    val selectedServiceId: String,
    val selectedConfigurationVersion: Int,
    val selectedAgentId: String?,
    val selectedToolIdsJson: String,
    val requiredCapabilitiesJson: String,
    val candidateEvaluationsJson: String,
    val decisionRationale: String,
    val confidence: Double,
    val securityPermitted: Boolean,
    val securityRuleId: String?,
    val securityReason: String,
    val governanceState: String,      // NOT_APPLICABLE, ALLOWED, BLOCKED, REQUIRES_APPROVAL
    val governancePolicyId: String?,
    val governanceReason: String,
    val fallbackPolicyType: String,   // Fail, Replan, PreferAlternative
    val fallbackPolicyPayloadJson: String,
    val createdAt: Long
)

/**
 * P0.8 — Execution state store (Section I).
 */
@Entity(tableName = "execution_records")
data class ExecutionRecordEntity(
    @PrimaryKey
    val executionId: String,
    val decisionId: String,
    val taskId: String,
    val stepId: String,
    val resourceId: String,
    val outcome: String,              // SUCCESS, FAILURE, REPLAN_REQUESTED
    val transportError: String?,
    val latencyMs: Long,
    val timestamp: Long
)

/**
 * P0.8 — Evidence store (Section I).
 */
@Entity(tableName = "evidence_records")
data class EvidenceRecordEntity(
    @PrimaryKey
    val evidenceId: String,
    val taskId: String,
    val stepId: String,
    val decisionId: String,
    val resourceId: String,
    val evidenceKeysJson: String,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long
)

/**
 * P0.8 — Verification outcome store (Section I).
 */
@Entity(tableName = "verification_outcomes")
data class VerificationOutcomeEntity(
    @PrimaryKey
    val id: String,
    val taskId: String,
    val stepId: String,
    val verified: Boolean,
    val confidence: Double,
    val summary: String,
    val createdAt: Long
)

