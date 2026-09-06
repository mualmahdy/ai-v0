package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.AuditTrailEntity
import com.example.infrastructure.persistence.entities.ExecutionTraceNodeEntity
import com.example.infrastructure.persistence.entities.HealthProbeEntity
import com.example.infrastructure.persistence.entities.MetricEventEntity
import com.example.infrastructure.persistence.entities.PermissionGrantEntity
import com.example.infrastructure.persistence.entities.PolicyVersionEntity
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

    @Query("SELECT * FROM audit_trail WHERE resourceType = :type AND resourceId = :id ORDER BY occurredAtEpochMs DESC")
    suspend fun forResource(type: String, id: String): List<AuditTrailEntity>

    @Query("SELECT * FROM audit_trail WHERE workspaceId = :workspaceId ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun forWorkspace(workspaceId: String, limit: Int = 100): List<AuditTrailEntity>

    @Query("SELECT * FROM audit_trail WHERE severity = :severity ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun forSeverity(severity: String, limit: Int = 100): List<AuditTrailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AuditTrailEntity): Long

    @Query("DELETE FROM audit_trail WHERE occurredAtEpochMs < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface HealthProbeDao {
    @Query("SELECT * FROM health_probes WHERE resourceId = :resourceId ORDER BY probedAtEpochMs DESC LIMIT :limit")
    suspend fun forResource(resourceId: String, limit: Int = 50): List<HealthProbeEntity>

    @Query("SELECT * FROM health_probes ORDER BY probedAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<HealthProbeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(probe: HealthProbeEntity): Long
}

@Dao
interface ExecutionTraceDao {
    @Query("SELECT * FROM execution_trace_nodes WHERE executionId = :executionId ORDER BY stepIndex ASC")
    fun forExecution(executionId: String): Flow<List<ExecutionTraceNodeEntity>>

    @Query("SELECT * FROM execution_trace_nodes ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<ExecutionTraceNodeEntity>>

    @Query("SELECT * FROM execution_trace_nodes ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun forWorkspace(limit: Int = 200): Flow<List<ExecutionTraceNodeEntity>>

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
interface PolicyVersionDao {
    @Query("SELECT * FROM policy_versions WHERE policyKind = :kind AND isPromoted = 1 ORDER BY promotedAtEpochMs DESC LIMIT 1")
    suspend fun activeFor(kind: String): PolicyVersionEntity?

    @Query("SELECT * FROM policy_versions WHERE policyKind = :kind ORDER BY createdAtEpochMs DESC")
    fun historyFor(kind: String): Flow<List<PolicyVersionEntity>>

    @Query("SELECT * FROM policy_versions ORDER BY createdAtEpochMs DESC")
    fun allFlow(): Flow<List<PolicyVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PolicyVersionEntity)

    @Query("UPDATE policy_versions SET isPromoted = 0 WHERE policyKind = :kind")
    suspend fun demoteAll(kind: String)

    @Query("UPDATE policy_versions SET isPromoted = 1, promotedAtEpochMs = :now, promotedBy = :actor WHERE versionId = :id")
    suspend fun promote(id: String, actor: String, now: Long)
}

@Dao
interface WorkflowExecutionDao {
    @Query("SELECT * FROM workflow_executions WHERE lifecycleState IN ('RUNNING', 'PAUSED', 'COMPENSATING') ORDER BY startedAtEpochMs DESC")
    suspend fun resumable(): List<WorkflowExecutionEntity>

    @Query("SELECT * FROM workflow_executions WHERE workspaceId = :workspaceId ORDER BY startedAtEpochMs DESC")
    fun forWorkspace(workspaceId: String): Flow<List<WorkflowExecutionEntity>>

    @Query("SELECT * FROM workflow_executions WHERE workflowId = :id LIMIT 1")
    suspend fun byId(id: String): WorkflowExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowExecutionEntity)

    @Query("UPDATE workflow_executions SET lifecycleState = :state, currentStepIndex = :step, lastCheckpointAtEpochMs = :now WHERE workflowId = :id")
    suspend fun checkpoint(id: String, state: String, step: Int, now: Long)

    @Query("UPDATE workflow_executions SET lifecycleState = :state, completedAtEpochMs = :now, failureReason = :reason WHERE workflowId = :id")
    suspend fun terminate(id: String, state: String, now: Long, reason: String?)
}

@Dao
interface WorkflowStepStateDao {
    @Query("SELECT * FROM workflow_step_states WHERE workflowId = :workflowId ORDER BY stepIndex ASC")
    suspend fun forWorkflow(workflowId: String): List<WorkflowStepStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<WorkflowStepStateEntity>)

    @Query("UPDATE workflow_step_states SET status = :status, outputSummary = :summary, durationMs = :duration, completedAtEpochMs = :now WHERE workflowId = :workflowId AND stepId = :stepId")
    suspend fun updateStepStatus(workflowId: String, stepId: String, status: String, summary: String?, duration: Long?, now: Long)
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
