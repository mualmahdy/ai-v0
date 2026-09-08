package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * DURABLE CONVERSATION SESSION ENTITIES — DB v13 (report gap: "durable
 * Sessions NOT FIXED — the legacy sessions table was dropped without a
 * durable replacement; the transcript lived only in the ViewModel")
 * ============================================================================
 *
 * `chat_sessions` + `chat_turns` restore a REAL durable session system:
 *   - Born workspace-owned (`workspaceId` index — never a global/1L scope).
 *   - Mode-aware (QUICK_CHAT vs AGENT) — closes the Quick Chat product gap.
 *   - Model-pinned (`modelResourceId`) — closes the user-facing model
 *     selection gap (exact resource binding survives restarts).
 *   - Aggregates (turnCount, totalTokensConsumed, lastActiveAtEpochMs) are
 *     maintained transactionally on appendTurn.
 */

@Entity(
    tableName = "chat_sessions",
    indices = [Index("workspaceId"), Index("lastActiveAtEpochMs")]
)
data class ConversationSessionEntity(
    @PrimaryKey val sessionId: String,
    val workspaceId: String,
    val title: String,
    /** ChatMode.name — QUICK_CHAT (agent-independent) / AGENT. */
    val mode: String,
    val agentId: String? = null,
    val agentName: String? = null,
    val modelResourceId: String? = null,
    val modelDisplayName: String? = null,
    val turnCount: Int = 0,
    val totalTokensConsumed: Int = 0,
    val createdAtEpochMs: Long,
    val lastActiveAtEpochMs: Long
)

@Entity(
    tableName = "chat_turns",
    indices = [Index("sessionId"), Index("createdAtEpochMs")]
)
data class ConversationTurnEntity(
    @PrimaryKey val turnId: String,
    val sessionId: String,
    val prompt: String,
    val answer: String,
    val agentName: String? = null,
    val agentRole: String? = null,
    val modelResourceId: String? = null,
    val tokensConsumed: Int = 0,
    val durationMs: Long = 0,
    val isSuccessful: Boolean = true,
    val eventCount: Int = 0,
    val createdAtEpochMs: Long
)

/**
 * ============================================================================
 * WORKFLOW LIBRARY ENTITY — DB v13 (report gap: "workflow library/history
 * NOT FIXED — durable execution exists, but the USER-AUTHORED workflow
 * definition is not a durable, re-editable asset")
 * ============================================================================
 *
 * `workflow_definitions` stores the user-authored plan as a first-class
 * workspace asset: save → list → load → edit → re-save (versioned) → clone →
 * run (recorded). The steps JSON is the SAME lossless serialization used by
 * WorkflowPersistenceService for executions (schema-tolerant on read).
 */
@Entity(
    tableName = "workflow_definitions",
    indices = [Index("workspaceId"), Index("updatedAtEpochMs")]
)
data class WorkflowDefinitionEntity(
    @PrimaryKey val workflowId: String,
    val workspaceId: String,
    val name: String,
    val goal: String,
    /** ExecutionMode.name. */
    val executionMode: String,
    /** Lossless StepNode serialization (same format as workflow_executions.planJson). */
    val stepsJson: String,
    /** Monotonic edit revision (bumped on every save). */
    val version: Int = 1,
    val runCount: Int = 0,
    val lastRunAtEpochMs: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
