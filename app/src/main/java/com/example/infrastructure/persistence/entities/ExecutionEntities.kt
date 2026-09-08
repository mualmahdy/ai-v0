package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import com.example.domain.core.execution.ActionIntent
import com.example.domain.core.execution.ActionIntentState

/**
 * ============================================================================
 * ExecutionEntities — gap-closure persistence (DB v11)
 * ============================================================================
 *
 * Two new durable stores backing the Canonical Execution Kernel:
 *
 *  1. `action_intents` (P0-06) — the ACTION IDEMPOTENCY LEDGER. One row per
 *     (executionId, actionKey). Written BEFORE a side-effectful action runs
 *     and updated AFTER its outcome is known, so a crash between "side effect
 *     happened" and "checkpoint persisted" is detectable on resume and the
 *     action is never blindly re-executed.
 *
 *  2. `agent_definitions` (P1-08 / P1-09) — the CANONICAL, durable agent
 *     registry. One authority for agent identity shared by the UI catalog
 *     and the runtime ComponentRegistry (previously the UI invented agents
 *     the runtime never registered, and agent definitions lived only in
 *     memory).
 */
@Entity(
    tableName = "action_intents",
    primaryKeys = ["executionId", "actionKey"]
)
data class ActionIntentEntity(
    val executionId: String,
    val actionKey: String,
    val actionType: String,
    val targetId: String?,
    val stepIndex: Int,
    /** INTENDED / COMPLETED / FAILED. */
    val state: String,
    val outputFingerprint: String?,
    val outputSummary: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    fun toDomain(): ActionIntent = ActionIntent(
        executionId = executionId,
        actionKey = actionKey,
        actionType = actionType,
        targetId = targetId,
        stepIndex = stepIndex,
        state = runCatching { ActionIntentState.valueOf(state) }.getOrDefault(ActionIntentState.INTENDED),
        outputFingerprint = outputFingerprint,
        outputSummary = outputSummary,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        fun fromDomain(intent: ActionIntent): ActionIntentEntity = ActionIntentEntity(
            executionId = intent.executionId,
            actionKey = intent.actionKey,
            actionType = intent.actionType,
            targetId = intent.targetId,
            stepIndex = intent.stepIndex,
            state = intent.state.name,
            outputFingerprint = intent.outputFingerprint,
            outputSummary = intent.outputSummary,
            createdAtEpochMs = intent.createdAtEpochMs,
            updatedAtEpochMs = intent.updatedAtEpochMs
        )
    }
}

@Entity(tableName = "agent_definitions")
data class AgentDefinitionEntity(
    @androidx.room.PrimaryKey
    val id: String,
    val name: String,
    /** AgentRole.name */
    val role: String,
    val description: String,
    val systemPrompt: String,
    /** JSON array of CapabilityType names. */
    val capabilitiesJson: String,
    /** JSON array of workspace scope ids. */
    val workspaceScopeJson: String,
    val maxTokens: Int,
    val enabled: Boolean,
    /** Monotonic revision counter — configuration lineage (P1-09). */
    val version: Int,
    /** PLANNER-authored (user-created in Agent Studio) vs PLATFORM-seeded. */
    val origin: String,
    // v13 — full-fidelity agent persistence (report: round-tripping a
    // durable agent previously LOST goals, networkRequirement, locality and
    // authorityLevel on every save).
    /** JSON array of {"description": ..., "priority": n} goal objects. */
    val goalsJson: String = "[]",
    /** NetworkRequirement.name. */
    val networkRequirement: String = "HYBRID",
    /** Locality.name. */
    val locality: String = "LOCAL_ON_DEVICE",
    /** Authority level (domain string, default STANDARD). */
    val authorityLevel: String = "STANDARD",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/**
 * DURABLE AGENT REVISION LEDGER (defect family 4 — "agent revisions/
 * versioning must be durable and reproducible"): one row per registered
 * revision of an agent. The full version chain (major.minor.patch,
 * previousVersionId, author, snapshot) survives process death, so the
 * lifecycle service's version history is REPRODUCIBLE after restart —
 * previously the chain lived only in an in-memory map and evaporated.
 */
@Entity(
    tableName = "agent_revisions",
    primaryKeys = ["agentId", "revisionId"]
)
data class AgentRevisionEntity(
    val agentId: String,
    /** Unique revision identifier (VersionedAgentDefinition.version.revisionId). */
    val revisionId: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Version string of the previous revision in the chain (null = first). */
    val previousVersionId: String?,
    /** Full snapshot of the definition at this revision (JSON). */
    val snapshotJson: String,
    val createdBy: String,
    val createdAtEpochMs: Long
)
