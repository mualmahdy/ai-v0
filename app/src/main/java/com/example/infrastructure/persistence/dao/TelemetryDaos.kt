package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.AuditTrailEntity
import com.example.infrastructure.persistence.entities.ExecutionTraceNodeEntity
import com.example.infrastructure.persistence.entities.MetricEventEntity
import com.example.infrastructure.persistence.entities.PermissionGrantEntity
import com.example.infrastructure.persistence.entities.ToolAuditEntity
import com.example.infrastructure.persistence.entities.ToolHealthSnapshotEntity
import com.example.infrastructure.persistence.entities.ToolLifecycleStateEntity
import com.example.infrastructure.persistence.entities.WorkflowExecutionEntity
import com.example.infrastructure.persistence.entities.WorkflowStepStateEntity
import com.example.infrastructure.persistence.entities.AgentMemoryNamespaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * Telemetry / Observability / Governance DAOs (Phase 5 entities)
 * ============================================================================
 *
 * FIX (audit 2026): every @Query now references the REAL Room column names.
 * The entities declare camelCase properties (no @ColumnInfo renaming) and
 * MIGRATION_7_TO_8 creates the tables with camelCase columns, so all SQL
 * must use camelCase — the previous snake_case references made KSP fail
 * ("no such column") and left the whole observability layer uncompilable.
 */
@Dao
interface MetricEventDao {
    @Query("SELECT * FROM metric_events WHERE metricType = :type ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 200): List<MetricEventEntity>

    @Query("SELECT * FROM metric_events WHERE executionId = :executionId ORDER BY recordedAtEpochMs ASC")
    fun forExecution(executionId: String): Flow<List<MetricEventEntity>>

    @Query("SELECT * FROM metric_events ORDER BY recordedAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<MetricEventEntity>>

    @Query("SELECT metricType AS metricType, dimensionsKey AS dimensionsKey, COUNT(*) as cnt, SUM(value) as sum, MIN(value) as mn, MAX(value) as mx FROM metric_events GROUP BY metricType, dimensionsKey")
    suspend fun aggregateBuckets(): List<MetricBucketRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MetricEventEntity>)

    @Query("DELETE FROM metric_events WHERE recordedAtEpochMs < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

data class MetricBucketRow(
    val metricType: String,
    val dimensionsKey: String,
    val cnt: Long,
    val sum: Long,
    val mn: Long,
    val mx: Long
)

@Dao
interface AuditTrailDao {
    @Query("SELECT * FROM audit_trail ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<AuditTrailEntity>>

    @Query("SELECT * FROM audit_trail WHERE workspaceId = :workspaceId ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    fun forWorkspace(workspaceId: String, limit: Int = 100): Flow<List<AuditTrailEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AuditTrailEntity): Long
}

@Dao
interface ExecutionTraceDao {
    @Query("SELECT * FROM execution_trace_nodes WHERE executionId = :executionId ORDER BY stepIndex ASC")
    fun forExecution(executionId: String): Flow<List<ExecutionTraceNodeEntity>>

    @Query("SELECT * FROM execution_trace_nodes ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<ExecutionTraceNodeEntity>>

    /**
     * P1-10 FIX (audit 2026 §20 — forWorkspace had NO workspace predicate in
     * SQL; it was byte-identical to recent()): the trace query is now
     * workspace-scoped at the SQL boundary using the v15 workspaceId column.
     */
    @Query("SELECT * FROM execution_trace_nodes WHERE workspaceId = :workspaceId ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun forWorkspace(workspaceId: String, limit: Int = 200): Flow<List<ExecutionTraceNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: ExecutionTraceNodeEntity): Long
}

@Dao
interface ToolAuditDao {
    @Query("SELECT * FROM tool_audit_log WHERE toolName = :toolName ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun forTool(toolName: String, limit: Int = 100): List<ToolAuditEntity>

    @Query("SELECT * FROM tool_audit_log WHERE executionId = :executionId ORDER BY occurredAtEpochMs ASC")
    fun forExecution(executionId: String): Flow<List<ToolAuditEntity>>

    @Query("SELECT * FROM tool_audit_log ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<ToolAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ToolAuditEntity): Long

    @Query("SELECT COUNT(*) FROM tool_audit_log WHERE toolName = :toolName AND outcome = 'SUCCESS'")
    suspend fun successCountForTool(toolName: String): Int

    @Query("SELECT COUNT(*) FROM tool_audit_log WHERE toolName = :toolName AND outcome = 'FAILURE'")
    suspend fun failureCountForTool(toolName: String): Int
}

@Dao
interface ToolLifecycleDao {
    @Query("SELECT * FROM tool_lifecycle_states WHERE toolName = :toolName LIMIT 1")
    suspend fun byName(toolName: String): ToolLifecycleStateEntity?

    /**
     * P1-2 (audit 2026 §10): ALL lifecycle rows for a tool NAME — including
     * REVOKED/disabled ones. The execution boundary uses this to enforce
     * that a revoked tool can never run again through ANY path.
     */
    @Query("SELECT * FROM tool_lifecycle_states WHERE toolName = :toolName")
    suspend fun allByName(toolName: String): List<ToolLifecycleStateEntity>

    @Query("SELECT * FROM tool_lifecycle_states WHERE lifecycleState != 'REVOKED' AND isEnabled = 1")
    suspend fun active(): List<ToolLifecycleStateEntity>

    @Query("SELECT * FROM tool_lifecycle_states ORDER BY registeredAtEpochMs DESC")
    fun allFlow(): Flow<List<ToolLifecycleStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ToolLifecycleStateEntity)

    @Query("UPDATE tool_lifecycle_states SET lifecycleState = :state, lastValidatedAtEpochMs = :now WHERE toolId = :id")
    suspend fun updateLifecycle(id: String, state: String, now: Long)

    @Query("UPDATE tool_lifecycle_states SET lifecycleState = 'REVOKED', isEnabled = 0, revokedAtEpochMs = :now, revokeReason = :reason WHERE toolId = :id")
    suspend fun revoke(id: String, reason: String, now: Long)
}

@Dao
interface ToolHealthDao {
    @Query("SELECT * FROM tool_health_snapshots")
    suspend fun all(): List<ToolHealthSnapshotEntity>

    @Query("SELECT * FROM tool_health_snapshots WHERE toolId = :toolId LIMIT 1")
    suspend fun byTool(toolId: String): ToolHealthSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ToolHealthSnapshotEntity)

    @Query("UPDATE tool_health_snapshots SET circuitState = :state, openedAtEpochMs = :openedAt WHERE toolId = :toolId")
    suspend fun updateCircuitState(toolId: String, state: String, openedAt: Long?)
}

@Dao
interface PermissionGrantDao {
    @Query("SELECT * FROM permission_grants WHERE principalType = :principalType AND principalId = :principalId")
    suspend fun forPrincipal(principalType: String, principalId: String): List<PermissionGrantEntity>

    /**
     * WORKSPACE-SCOPED lookup (defect family 2 — workspace context is part
     * of authorization): a grant authorizes only when it is explicitly
     * GLOBAL (workspaceId IS NULL) or scoped to the SAME workspace as the
     * execution. A grant scoped to another workspace can never authorize.
     */
    @Query(
        "SELECT * FROM permission_grants WHERE principalType = :principalType " +
            "AND principalId = :principalId AND resourceType = :resourceType " +
            "AND resourceId = :resourceId AND permission = :permission " +
            "AND (workspaceId IS NULL OR workspaceId = :workspaceId) LIMIT 1"
    )
    suspend fun lookupScoped(
        principalType: String,
        principalId: String,
        resourceType: String,
        resourceId: String,
        permission: String,
        workspaceId: String?
    ): PermissionGrantEntity?

    /** Legacy unscoped lookup (kept for compatibility paths). */
    @Query("SELECT * FROM permission_grants WHERE principalType = :principalType AND principalId = :principalId AND resourceType = :resourceType AND resourceId = :resourceId AND permission = :permission LIMIT 1")
    suspend fun lookup(
        principalType: String,
        principalId: String,
        resourceType: String,
        resourceId: String,
        permission: String
    ): PermissionGrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: PermissionGrantEntity): Long

    @Query("DELETE FROM permission_grants WHERE id = :id")
    suspend fun revoke(id: Long)
}

@Dao
interface AgentRevisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(revision: com.example.infrastructure.persistence.entities.AgentRevisionEntity)

    @Query("SELECT * FROM agent_revisions WHERE agentId = :agentId ORDER BY createdAtEpochMs DESC")
    suspend fun forAgent(agentId: String): List<com.example.infrastructure.persistence.entities.AgentRevisionEntity>

    @Query("SELECT * FROM agent_revisions WHERE agentId = :agentId ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latestForAgent(agentId: String): com.example.infrastructure.persistence.entities.AgentRevisionEntity?

    @Query("SELECT COUNT(*) FROM agent_revisions WHERE agentId = :agentId")
    suspend fun countForAgent(agentId: String): Int

    @Query("DELETE FROM agent_revisions WHERE agentId = :agentId")
    suspend fun deleteForAgent(agentId: String)
}

@Dao
interface WorkflowExecutionDao {
    @Query("SELECT * FROM workflow_executions WHERE lifecycleState IN ('RUNNING', 'PAUSED', 'COMPENSATING') ORDER BY startedAtEpochMs DESC")
    suspend fun resumable(): List<WorkflowExecutionEntity>

    /** WORKSPACE-SCOPED resumable list (defect family 1). */
    @Query("SELECT * FROM workflow_executions WHERE lifecycleState IN ('RUNNING', 'PAUSED', 'COMPENSATING') AND workspaceId = :workspaceId ORDER BY startedAtEpochMs DESC")
    suspend fun resumableForWorkspace(workspaceId: String): List<WorkflowExecutionEntity>

    @Query("SELECT * FROM workflow_executions WHERE workspaceId = :workspaceId ORDER BY startedAtEpochMs DESC")
    fun forWorkspace(workspaceId: String): Flow<List<WorkflowExecutionEntity>>

    @Query("SELECT * FROM workflow_executions WHERE workflowId = :id LIMIT 1")
    suspend fun byId(id: String): WorkflowExecutionEntity?

    /** WORKSPACE-AUTHORIZED load — another workspace's run is
     *  indistinguishable from nonexistent. */
    @Query("SELECT * FROM workflow_executions WHERE workflowId = :id AND workspaceId = :workspaceId LIMIT 1")
    suspend fun byIdAndWorkspace(id: String, workspaceId: String): WorkflowExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowExecutionEntity)

    @Query("UPDATE workflow_executions SET lifecycleState = :state, currentStepIndex = :step, lastCheckpointAtEpochMs = :now WHERE workflowId = :id")
    suspend fun checkpoint(id: String, state: String, step: Int, now: Long)

    /** WORKSPACE-AUTHORIZED checkpoint — only the owning workspace's row. */
    @Query("UPDATE workflow_executions SET lifecycleState = :state, currentStepIndex = :step, lastCheckpointAtEpochMs = :now WHERE workflowId = :id AND workspaceId = :workspaceId")
    suspend fun checkpointForWorkspace(id: String, workspaceId: String, state: String, step: Int, now: Long)

    @Query("UPDATE workflow_executions SET lifecycleState = :state, completedAtEpochMs = :now, failureReason = :reason WHERE workflowId = :id")
    suspend fun terminate(id: String, state: String, now: Long, reason: String?)

    /** WORKSPACE-AUTHORIZED terminal write — only the owning workspace's row. */
    @Query("UPDATE workflow_executions SET lifecycleState = :state, completedAtEpochMs = :now, failureReason = :reason WHERE workflowId = :id AND workspaceId = :workspaceId")
    suspend fun terminateForWorkspace(id: String, workspaceId: String, state: String, now: Long, reason: String?)
}

@Dao
interface WorkflowStepStateDao {
    @Query("SELECT * FROM workflow_step_states WHERE workflowId = :workflowId ORDER BY stepIndex ASC")
    suspend fun forWorkflow(workflowId: String): List<WorkflowStepStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<WorkflowStepStateEntity>)

    @Query("UPDATE workflow_step_states SET status = :status, outputSummary = :summary, durationMs = :duration, completedAtEpochMs = :now WHERE workflowId = :workflowId AND stepId = :stepId")
    suspend fun updateStepStatus(workflowId: String, stepId: String, status: String, summary: String?, duration: Long?, now: Long)

    /** Durable artifact payloads (defect family 7). */
    @Query("UPDATE workflow_step_states SET artifactsJson = :artifactsJson WHERE workflowId = :workflowId AND stepId = :stepId")
    suspend fun updateStepArtifacts(workflowId: String, stepId: String, artifactsJson: String)
}

@Dao
interface AgentMemoryNamespaceDao {
    @Query("SELECT * FROM agent_memory_namespaces WHERE agentId = :agentId AND workspaceId = :workspaceId AND isActive = 1 LIMIT 1")
    suspend fun forAgentInWorkspace(agentId: String, workspaceId: String): AgentMemoryNamespaceEntity?

    @Query("SELECT * FROM agent_memory_namespaces WHERE workspaceId = :workspaceId AND isActive = 1")
    suspend fun forWorkspace(workspaceId: String): List<AgentMemoryNamespaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMemoryNamespaceEntity)

    @Query("UPDATE agent_memory_namespaces SET isActive = 0 WHERE namespaceId = :id")
    suspend fun deactivate(id: String)
}
