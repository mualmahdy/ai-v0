package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import com.example.infrastructure.persistence.entities.DecisionRecordEntity
import com.example.infrastructure.persistence.entities.EvidenceRecordEntity
import com.example.infrastructure.persistence.entities.ExecutionRecordEntity
import com.example.infrastructure.persistence.entities.EvolutionCandidateEntity
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.ProviderConfigEntity
import com.example.infrastructure.persistence.entities.RadarItemEntity
import com.example.infrastructure.persistence.entities.ResourceHealthSnapshotEntity
import com.example.infrastructure.persistence.entities.ResourceRecordEntity
import com.example.infrastructure.persistence.entities.SessionEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.persistence.entities.VerificationOutcomeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY updatedAtEpochMs DESC")
    fun getAllActiveProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET isArchived = 1 WHERE id = :id")
    suspend fun archiveProject(id: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC")
    fun getSessionsForProject(projectId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET updatedAtEpochMs = :updatedAt, totalTokensConsumed = totalTokensConsumed + :tokensAdded WHERE sessionId = :sessionId")
    suspend fun recordSessionTokens(sessionId: String, tokensAdded: Int, updatedAt: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_records WHERE isArchived = 0 ORDER BY confidence DESC")
    fun getAllActiveMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0")
    suspend fun getAllActiveMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("UPDATE memory_records SET lastAccessedEpochMs = :now, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun touchMemory(id: String, now: Long)

    @Query("DELETE FROM memory_records WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memory_records")
    suspend fun clearAll()
}

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs WHERE executionId = :executionId ORDER BY timestampEpochMs ASC")
    fun getLogsForExecution(executionId: String): Flow<List<ExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLogEntity): Long
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAtEpochMs DESC")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAtEpochMs DESC")
    suspend fun getAllTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTask(task: TaskEntity)

    @Query("UPDATE tasks SET lifecycleState = :state, resultSummary = :summary, totalTokensConsumed = :tokens, durationMs = :duration, isDegraded = :isDegraded, degradedReason = :degradedReason, errorMessage = :errorMsg, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateTaskStatus(
        id: String,
        state: String,
        summary: String?,
        tokens: Int,
        duration: Long,
        isDegraded: Boolean,
        degradedReason: String?,
        errorMsg: String?,
        now: Long
    )
}

@Dao
interface DecisionCaseDao {
    @Query("SELECT * FROM decision_cases ORDER BY timestampEpochMs DESC")
    suspend fun getAllCases(): List<DecisionCaseEntity>

    @Query("SELECT * FROM decision_cases ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun getRecentCases(limit: Int): List<DecisionCaseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCase(caseEntity: DecisionCaseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cases: List<DecisionCaseEntity>)
}

@Dao
interface RadarItemDao {
    @Query("SELECT * FROM radar_items ORDER BY relevanceScore DESC, discoveredTimestampEpochMs DESC")
    fun getAllRadarItemsFlow(): Flow<List<RadarItemEntity>>

    @Query("SELECT * FROM radar_items ORDER BY relevanceScore DESC, discoveredTimestampEpochMs DESC")
    suspend fun getAllRadarItems(): List<RadarItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RadarItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: RadarItemEntity)

    @Query("DELETE FROM radar_items WHERE id = :id")
    suspend fun deleteItem(id: String)
}

@Dao
interface EvolutionCandidateDao {
    @Query("SELECT * FROM evolution_candidates ORDER BY updatedAtEpochMs DESC")
    fun getAllCandidatesFlow(): Flow<List<EvolutionCandidateEntity>>

    @Query("SELECT * FROM evolution_candidates ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllCandidates(): List<EvolutionCandidateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candidates: List<EvolutionCandidateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidate(candidate: EvolutionCandidateEntity)

    @Query("UPDATE evolution_candidates SET stage = :stage, governanceApproved = :governanceApproved, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateStage(id: String, stage: String, governanceApproved: Boolean, now: Long)
}

@Dao
interface ExtensionConfigDao {
    @Query("SELECT * FROM extension_configs ORDER BY id ASC")
    fun getAllExtensionConfigsFlow(): Flow<List<ExtensionConfigEntity>>

    @Query("SELECT * FROM extension_configs WHERE type = :type")
    suspend fun getConfigsByType(type: String): List<ExtensionConfigEntity>

    @Query("SELECT * FROM extension_configs WHERE id = :id LIMIT 1")
    suspend fun getConfigById(id: String): ExtensionConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateConfig(config: ExtensionConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<ExtensionConfigEntity>)
}

@Dao
interface ProviderConfigDao {
    @Query("SELECT * FROM provider_configs ORDER BY createdAtEpochMs ASC")
    fun getAllProvidersFlow(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs ORDER BY createdAtEpochMs ASC")
    suspend fun getAllProviders(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE id = :id LIMIT 1")
    suspend fun getProviderById(id: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs WHERE category = :category AND isEnabled = 1")
    suspend fun getEnabledByCategory(category: String): List<ProviderConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(config: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<ProviderConfigEntity>)

    @Query("DELETE FROM provider_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE provider_configs SET isEnabled = :isEnabled, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateEnabled(id: String, isEnabled: Boolean, now: Long)

    @Query("UPDATE provider_configs SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END, updatedAtEpochMs = :now WHERE category = :category")
    suspend fun setDefault(id: String, category: String, now: Long)

    @Query("UPDATE provider_configs SET healthStatus = :healthStatus, lastValidatedEpochMs = :validatedMs, lastLatencyMs = :latencyMs, lastErrorMessage = :error, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateHealth(id: String, healthStatus: String, validatedMs: Long, latencyMs: Long, error: String?, now: Long)
}


// =============================================================================
// P0 RESOURCE CONTRACT (APPROVED-BASELINE v2.1) — schema revision v4
// =============================================================================

@Dao
interface ResourceRecordDao {
    @Query("SELECT * FROM resource_records WHERE resourceId = :resourceId LIMIT 1")
    suspend fun getByResourceId(resourceId: String): ResourceRecordEntity?

    @Query("SELECT * FROM resource_records WHERE providerId = :providerId AND serviceId = :serviceId LIMIT 1")
    suspend fun getByLogicalKey(providerId: String, serviceId: String): ResourceRecordEntity?

    @Query("SELECT * FROM resource_records ORDER BY registeredAt ASC")
    suspend fun getAll(): List<ResourceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ResourceRecordEntity)

    @Query("SELECT * FROM resource_records WHERE lifecycleState = :state AND runtimeSupported = 1 ORDER BY registeredAt ASC")
    suspend fun getUsableByLifecycle(state: String): List<ResourceRecordEntity>

    @Query("SELECT * FROM resource_records WHERE lifecycleState = :state AND runtimeSupported = 1 ORDER BY registeredAt ASC")
    fun observeUsableByLifecycle(state: String): Flow<List<ResourceRecordEntity>>

    @Query("UPDATE resource_records SET configurationVersion = configurationVersion + 1, lastStateChangeAt = :now WHERE resourceId = :resourceId")
    suspend fun bumpConfigurationVersion(resourceId: String, now: Long): Int

    @Query("UPDATE resource_records SET lifecycleState = :state, lastStateChangeAt = :now WHERE resourceId = :resourceId")
    suspend fun updateLifecycleState(resourceId: String, state: String, now: Long): Int

    @Query("UPDATE resource_records SET runtimeSupported = :supported, lastStateChangeAt = :now WHERE resourceId = :resourceId")
    suspend fun updateRuntimeSupported(resourceId: String, supported: Boolean, now: Long): Int

    @Query("SELECT * FROM resource_records WHERE lifecycleState = :state AND runtimeSupported = 1")
    suspend fun getByLifecycleAndRuntimeSupport(state: String): List<ResourceRecordEntity>

    @Query("DELETE FROM resource_records WHERE resourceId = :resourceId")
    suspend fun deleteByResourceId(resourceId: String): Int
}

@Dao
interface ResourceHealthSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(snapshots: List<ResourceHealthSnapshotEntity>)

    @Query("SELECT * FROM resource_health_snapshots")
    suspend fun getAll(): List<ResourceHealthSnapshotEntity>

    @Query("DELETE FROM resource_health_snapshots")
    suspend fun clearAll()
}

@Dao
interface DecisionRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DecisionRecordEntity)

    @Query("SELECT * FROM decision_records WHERE decisionId = :decisionId LIMIT 1")
    suspend fun getById(decisionId: String): DecisionRecordEntity?

    @Query("SELECT * FROM decision_records WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getByTask(taskId: String): List<DecisionRecordEntity>

    @Query("SELECT MAX(decisionVersion) FROM decision_records WHERE taskId = :taskId AND stepId = :stepId")
    suspend fun latestVersion(taskId: String, stepId: String): Int?
}

@Dao
interface ExecutionRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ExecutionRecordEntity)

    @Query("SELECT * FROM execution_records WHERE executionId = :executionId LIMIT 1")
    suspend fun getById(executionId: String): ExecutionRecordEntity?

    @Query("SELECT * FROM execution_records WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getByTask(taskId: String): List<ExecutionRecordEntity>
}

@Dao
interface EvidenceRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: EvidenceRecordEntity)

    @Query("SELECT * FROM evidence_records WHERE stepId = :stepId ORDER BY createdAt ASC")
    suspend fun getByStep(stepId: String): List<EvidenceRecordEntity>

    @Query("SELECT * FROM evidence_records WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun getByTask(taskId: String): List<EvidenceRecordEntity>
}

@Dao
interface VerificationOutcomeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VerificationOutcomeEntity)

    @Query("SELECT * FROM verification_outcomes WHERE stepId = :stepId ORDER BY createdAt ASC")
    suspend fun getByStep(stepId: String): List<VerificationOutcomeEntity>
}
