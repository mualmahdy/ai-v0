package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P0 CONVERGENCE (audit step 12 §6): the Project is now explicitly
 * WORKSPACE-OWNED. `workspaceId` is the owning workspace's id (null =
 * legacy rows whose owner could not be determined during migration —
 * they remain readable but are never implicitly re-assigned).
 *
 * REPAIR ORDER §27 (DB v16): full safe lifecycle state machine —
 * ACTIVE / ARCHIVED / TRASHED / DELETED / PURGED — replacing the boolean
 * isArchived as the authority (isArchived is kept as a DERIVED compatibility
 * column: lifecycleState == ARCHIVED). Deletion is NEVER the first action.
 */
@Entity(
    tableName = "projects",
    indices = [Index("workspaceId"), Index("lifecycleState")]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val description: String?,
    val rootPath: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isArchived: Boolean = false,
    /** Owning workspace id — set at creation, never implicit. */
    val workspaceId: String? = null,
    /** REPAIR ORDER §27: authoritative lifecycle state (DB v16). */
    val lifecycleState: String = "ACTIVE",
    val archivedAtEpochMs: Long? = null,
    val trashedAtEpochMs: Long? = null
) {
    val effectiveLifecycleState: String
        get() = when {
            lifecycleState.isBlank() -> if (isArchived) "ARCHIVED" else "ACTIVE"
            // Legacy authority: pre-v16 rows whose isArchived was the only
            // lifecycle signal (migrated rows are set explicitly, but any
            // straggler with isArchived=1 + default state resolves honestly).
            isArchived && lifecycleState == "ACTIVE" -> "ARCHIVED"
            else -> lifecycleState
        }
}

/*
 * P0 CONVERGENCE REMOVAL: `SessionEntity` (table `sessions`) was deleted.
 *
 * The whole `SessionRepositoryPort` surface (getActiveProject / listProjects /
 * createProject / listSessions / saveSession) had ZERO production callers —
 * it was a dead remnant of the pre-workspace project-scoped architecture:
 *  - ownership was projectId-only (never workspace-scoped),
 *  - it lazily bootstrapped the legacy shared project id=1L,
 *  - and no production path ever wrote or read a session row.
 *
 * The durable conversation/session surface of the runtime is the Task/
 * Execution/ExecutionLog path (workspace-scoped). MIGRATION_11_TO_12 drops
 * the table; a future first-class Session experience must be born
 * workspace-owned, not resurrected from this remnant.
 */

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
    val isArchived: Boolean = false,
    // ---- Phase 5 (Memory Intelligence): full taxonomy + workspace/agent scoping ----
    // See MIGRATION_7_TO_8 — all new columns have defaults so existing rows migrate cleanly.
    val memoryType: String = "FACTUAL_INSIGHT", // WORKING, EPISODIC, SEMANTIC, PROCEDURAL, PREFERENCE, FACTUAL_INSIGHT, CASE_EXAMPLE, CONVERSATION_SUMMARY, WORKSPACE, AGENT
    val importance: Float = 1.0f,
    val decayScore: Float = 1.0f, // starts at 1.0 (full strength), decays over time
    val workspaceId: String? = null,
    val agentId: String? = null,
    val tagsJson: String = "[]",
    val lastDecayEvaluatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "execution_logs", indices = [Index("executionId"), Index("workspaceId")])
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val executionId: String,
    val sessionId: String,
    val eventType: String,
    val payloadJson: String,
    val timestampEpochMs: Long,
    /**
     * P1-7 (audit 2026 §6 — ExecutionLog relies on executionId instead of
     * explicit workspace identity): owning workspace id, written from the
     * trace node's attribution (pinned execution→workspace binding).
     * Null = honestly UNATTRIBUTED (legacy rows before v15).
     */
    val workspaceId: String? = null
)

@Entity(tableName = "tasks", indices = [Index("workspaceId"), Index("parentTaskId")])
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
    val executionLogJson: String? = null,         // JSON array of log entries
    // ---- Durable execution (audit 2026 fix, MIGRATION_8_TO_9) ----
    // Parent task for delegated child tasks (NULL for top-level tasks).
    val parentTaskId: String? = null,
    // Delegation nesting depth (0 = top-level). Guards against runaway recursion.
    val delegationDepth: Int = 0,
    // JSON checkpoint of the closed-loop state (step index, accumulated
    // evidence, accumulated output, consumed tokens) — the resume payload.
    val checkpointJson: String? = null,
    // ---- Canonical Execution Context (gap-closure P0/P1, MIGRATION_10_TO_11) ----
    // Serialized CanonicalExecutionContext: STABLE executionId + PINNED
    // workspaceId/projectId/agentId/agentRole/modelId + attempt counter.
    // Restored on resume so a resumed execution keeps its identity (P1-03)
    // and refuses silent agent migration (P1-04).
    val executionContextJson: String? = null,
    /**
     * P1-7 (audit 2026 §6 — TaskEntity does not carry workspaceId as a
     * first-class identity): explicit owning-workspace column (from the
     * pinned canonical execution context at insert time). Null = honestly
     * UNATTRIBUTED (legacy rows before v15 — never implicitly re-assigned).
     */
    val workspaceId: String? = null,
    /**
     * REPAIR ORDER §5 (DB v16): project ownership promoted out of
     * executionContextJson into a QUERYABLE column. Backfilled from the
     * serialized context during migration; null = unattributed.
     */
    val projectId: Long? = null
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
 * One row per (state-region, RESOURCE, action) triple — stores the learned
 * Q value and transition statistics so the CBR-MDP engine accumulates REAL
 * experience across sessions (previously estimates were in-memory per-
 * action-type only and were wiped on every restart).
 *
 * P0/P1 CONVERGENCE (audit step 12 §4): learning is RESOURCE-AWARE. The
 * previous (regionKey, actionType) primary key collapsed every
 * SELECT_MODEL/EXECUTE_TOOL/SEARCH over EVERY provider/model/resource into
 * one cell, so the engine could learn "SEARCH is good" but never "SEARCH
 * via resource X beats resource Y in this state". The new `resourceKey`
 * column (MIGRATION_11_TO_12, default "R:none") extends the primary key;
 * legacy rows map to the resource-less axis, which is exactly where
 * resource-less actions (COMPLETE/STOP/RETRY/...) continue to learn.
 */
@Entity(
    tableName = "mdp_q_values",
    primaryKeys = ["regionKey", "resourceKey", "actionType"]
)
data class MdpQValueEntity(
    val regionKey: String,
    val resourceKey: String,
    val actionType: String,
    val qValue: Float,
    val visitCount: Int,
    val successCount: Int,
    val lastUpdatedEpochMs: Long,
    /** REPAIR ORDER §19 (DB v16): action-space version binding. */
    val actionSpaceVersion: String? = null
)
