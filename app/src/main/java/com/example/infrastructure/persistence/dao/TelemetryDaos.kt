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

@Dao
interface MetricEventDao {
    @Query("SELECT * FROM metric_events WHERE metric_type = :type ORDER BY recorded_at_epoch_ms DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 200): List<MetricEventEntity>

    @Query("SELECT * FROM metric_events WHERE execution_id = :executionId ORDER BY recorded_at_epoch_ms ASC")
    fun forExecution(executionId: String): Flow<List<MetricEventEntity>>

    @Query("SELECT * FROM metric_events ORDER BY recorded_at_epoch_ms DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<MetricEventEntity>>

    @Query("SELECT metric_type, dimensions_key, COUNT(*) as cnt, SUM(value) as sum, MIN(value) as mn, MAX(value) as mx FROM metric_events GROUP BY metric_type, dimensions_key")
    suspend fun aggregateBuckets(): List<MetricBucketRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MetricEventEntity>)

    @Query("DELETE FROM metric_events WHERE recorded_at_epoch_ms < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

data class MetricBucketRow(
    val metric_type: String,
    val dimensions_key: String,
    val cnt: Long,
    val sum: Long,
    val mn: Long,
    val mx: Long
)

@Dao
interface AuditTrailDao {
    @Query("SELECT * FROM audit_trail ORDER BY occurred_at_epoch_ms DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<AuditTrailEntity>>

    @Query("SELECT * FROM audit_trail WHERE resource_type = :type AND resource_id = :id ORDER BY occurred_at_epoch_ms DESC")
    suspend fun forResource(type: String, id: String): List<AuditTrailEntity>

    @Query("SELECT * FROM audit_trail WHERE workspace_id = :workspaceId ORDER BY occurred_at_epoch_ms DESC LIMIT :limit")
    suspend fun forWorkspace(workspaceId: String, limit: Int = 100): List<AuditTrailEntity>

    @Query("SELECT * FROM audit_trail WHERE severity = :severity ORDER BY occurred_at_epoch_ms DESC LIMIT :limit")
    suspend fun forSeverity(severity: String, limit: Int = 100): List<AuditTrailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AuditTrailEntity): Long

    @Query("DELETE FROM audit_trail WHERE occurred_at_epoch_ms < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int
}

@Dao
interface HealthProbeDao {
    @Query("SELECT * FROM health_probes WHERE resource_id = :resourceId ORDER BY probed_at_epoch_ms DESC LIMIT :limit")
    suspend fun forResource(resourceId: String, limit: Int = 50): List<HealthProbeEntity>

    @Query("SELECT * FROM health_probes ORDER BY probed_at_epoch_ms DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<HealthProbeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(probe: HealthProbeEntity): Long
}

@Dao
interface ExecutionTraceDao {
    @Query("SELECT * FROM execution_trace_nodes WHERE execution_id = :executionId ORDER BY step_index ASC")
    fun forExecution(executionId: String): Flow<List<ExecutionTraceNodeEntity>>

    @Query("SELECT * FROM execution_trace_nodes ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<ExecutionTraceNodeEntity>>

    @Query("SELECT * FROM execution_trace_nodes WHERE workspace_id IS :workspaceId ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    fun forWorkspace(workspaceId: String?, limit: Int = 200): Flow<List<ExecutionTraceNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: ExecutionTraceNodeEntity): Long
}

@Dao
interface ToolAuditDao {
    @Query("SELECT * FROM tool_audit_log WHERE tool_name = :toolName ORDER BY occurred_at_epoch_ms DESC LIMIT :limit")
    suspend fun forTool(toolName: String, limit: Int = 100): List<ToolAuditEntity>

    @Query("SELECT * FROM tool_audit_log WHERE execution_id = :executionId ORDER BY occurred_at_epoch_ms ASC")
    fun forExecution(executionId: String): Flow<List<ToolAuditEntity>>

    @Query("SELECT * FROM tool_audit_log ORDER BY occurred_at_epoch_ms DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<ToolAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ToolAuditEntity): Long

    @Query("SELECT COUNT(*) FROM tool_audit_log WHERE tool_name = :toolName AND outcome = 'SUCCESS'")
    suspend fun successCountForTool(toolName: String): Int

    @Query("SELECT COUNT(*) FROM tool_audit_log WHERE tool_name = :toolName AND outcome = 'FAILURE'")
    suspend fun failureCountForTool(toolName: String): Int
}

@Dao
interface ToolLifecycleDao {
    @Query("SELECT * FROM tool_lifecycle_states WHERE tool_name = :toolName LIMIT 1")
    suspend fun byName(toolName: String): ToolLifecycleStateEntity?

    @Query("SELECT * FROM tool_lifecycle_states WHERE lifecycle_state != 'REVOKED' AND is_enabled = 1")
    suspend fun active(): List<ToolLifecycleStateEntity>

    @Query("SELECT * FROM tool_lifecycle_states ORDER BY registered_at_epoch_ms DESC")
    fun allFlow(): Flow<List<ToolLifecycleStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ToolLifecycleStateEntity)

    @Query("UPDATE tool_lifecycle_states SET lifecycle_state = :state, last_validated_at_epoch_ms = :now WHERE tool_id = :id")
    suspend fun updateLifecycle(id: String, state: String, now: Long)

    @Query("UPDATE tool_lifecycle_states SET lifecycle_state = 'REVOKED', is_enabled = 0, revoked_at_epoch_ms = :now, revoke_reason = :reason WHERE tool_id = :id")
    suspend fun revoke(id: String, reason: String, now: Long)
}

@Dao
interface ToolHealthDao {
    @Query("SELECT * FROM tool_health_snapshots")
    suspend fun all(): List<ToolHealthSnapshotEntity>

    @Query("SELECT * FROM tool_health_snapshots WHERE tool_id = :toolId LIMIT 1")
    suspend fun byTool(toolId: String): ToolHealthSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ToolHealthSnapshotEntity)

    @Query("UPDATE tool_health_snapshots SET circuit_state = :state, opened_at_epoch_ms = :openedAt WHERE tool_id = :toolId")
    suspend fun updateCircuitState(toolId: String, state: String, openedAt: Long?)
}

@Dao
interface PermissionGrantDao {
    @Query("SELECT * FROM permission_grants WHERE principal_type = :principalType AND principal_id = :principalId")
    suspend fun forPrincipal(principalType: String, principalId: String): List<PermissionGrantEntity>

    @Query("SELECT * FROM permission_grants WHERE principal_type = :principalType AND principal_id = :principalId AND resource_type = :resourceType AND resource_id = :resourceId AND permission = :permission LIMIT 1")
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
    @Query("SELECT * FROM policy_versions WHERE policy_kind = :kind AND is_promoted = 1 ORDER BY promoted_at_epoch_ms DESC LIMIT 1")
    suspend fun activeFor(kind: String): PolicyVersionEntity?

    @Query("SELECT * FROM policy_versions WHERE policy_kind = :kind ORDER BY created_at_epoch_ms DESC")
    fun historyFor(kind: String): Flow<List<PolicyVersionEntity>>

    @Query("SELECT * FROM policy_versions ORDER BY created_at_epoch_ms DESC")
    fun allFlow(): Flow<List<PolicyVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PolicyVersionEntity)

    @Query("UPDATE policy_versions SET is_promoted = 0 WHERE policy_kind = :kind")
    suspend fun demoteAll(kind: String)

    @Query("UPDATE policy_versions SET is_promoted = 1, promoted_at_epoch_ms = :now, promoted_by = :actor WHERE version_id = :id")
    suspend fun promote(id: String, actor: String, now: Long)
}

@Dao
interface WorkflowExecutionDao {
    @Query("SELECT * FROM workflow_executions WHERE lifecycle_state IN ('RUNNING', 'PAUSED', 'COMPENSATING') ORDER BY started_at_epoch_ms DESC")
    suspend fun resumable(): List<WorkflowExecutionEntity>

    @Query("SELECT * FROM workflow_executions WHERE workspace_id = :workspaceId ORDER BY started_at_epoch_ms DESC")
    fun forWorkspace(workspaceId: String): Flow<List<WorkflowExecutionEntity>>

    @Query("SELECT * FROM workflow_executions WHERE workflow_id = :id LIMIT 1")
    suspend fun byId(id: String): WorkflowExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowExecutionEntity)

    @Query("UPDATE workflow_executions SET lifecycle_state = :state, current_step_index = :step, last_checkpoint_at_epoch_ms = :now WHERE workflow_id = :id")
    suspend fun checkpoint(id: String, state: String, step: Int, now: Long)

    @Query("UPDATE workflow_executions SET lifecycle_state = :state, completed_at_epoch_ms = :now, failure_reason = :reason WHERE workflow_id = :id")
    suspend fun terminate(id: String, state: String, now: Long, reason: String?)
}

@Dao
interface WorkflowStepStateDao {
    @Query("SELECT * FROM workflow_step_states WHERE workflow_id = :workflowId ORDER BY step_index ASC")
    suspend fun forWorkflow(workflowId: String): List<WorkflowStepStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<WorkflowStepStateEntity>)

    @Query("UPDATE workflow_step_states SET status = :status, output_summary = :summary, duration_ms = :duration, completed_at_epoch_ms = :now WHERE workflow_id = :workflowId AND step_id = :stepId")
    suspend fun updateStepStatus(workflowId: String, stepId: String, status: String, summary: String?, duration: Long?, now: Long)
}

@Dao
interface AgentMemoryNamespaceDao {
    @Query("SELECT * FROM agent_memory_namespaces WHERE agent_id = :agentId AND workspace_id = :workspaceId AND is_active = 1 LIMIT 1")
    suspend fun forAgentInWorkspace(agentId: String, workspaceId: String): AgentMemoryNamespaceEntity?

    @Query("SELECT * FROM agent_memory_namespaces WHERE workspace_id = :workspaceId AND is_active = 1")
    suspend fun forWorkspace(workspaceId: String): List<AgentMemoryNamespaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMemoryNamespaceEntity)

    @Query("UPDATE agent_memory_namespaces SET is_active = 0 WHERE namespace_id = :id")
    suspend fun deactivate(id: String)
}
