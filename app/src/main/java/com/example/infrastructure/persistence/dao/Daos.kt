package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import com.example.infrastructure.persistence.entities.EvolutionCandidateEntity
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.MdpQValueEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.ProviderConfigEntity
import com.example.infrastructure.persistence.entities.RadarItemEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY updatedAtEpochMs DESC")
    fun getAllActiveProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllActiveProjectsList(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): ProjectEntity?

    // ------------------------------------------------------------------
    // P1-7 (audit 2026 §6 — ProjectDao does not enforce workspace
    // ownership): workspace-scoped ownership queries. The legacy global
    // queries above remain for system-maintenance paths ONLY — every
    // workspace-facing surface must use the scoped variants, where another
    // workspace's project is indistinguishable from nonexistent.
    // ------------------------------------------------------------------

    /** Projects OWNED by [workspaceId] (another workspace's are invisible). */
    @Query("SELECT * FROM projects WHERE isArchived = 0 AND workspaceId = :workspaceId ORDER BY updatedAtEpochMs DESC")
    fun getActiveProjectsForWorkspace(workspaceId: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE isArchived = 0 AND workspaceId = :workspaceId ORDER BY updatedAtEpochMs DESC")
    suspend fun getActiveProjectsForWorkspaceList(workspaceId: String): List<ProjectEntity>

    /** WORKSPACE-AUTHORIZED load — another workspace's project is NOT FOUND. */
    @Query("SELECT * FROM projects WHERE id = :id AND workspaceId = :workspaceId LIMIT 1")
    suspend fun getProjectByIdForWorkspace(id: Long, workspaceId: String): ProjectEntity?

    /** WORKSPACE-AUTHORIZED archive — only the owning workspace's row. */
    @Query("UPDATE projects SET isArchived = 1 WHERE id = :id AND workspaceId = :workspaceId")
    suspend fun archiveProjectForWorkspace(id: Long, workspaceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET isArchived = 1 WHERE id = :id")
    suspend fun archiveProject(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_records WHERE isArchived = 0 ORDER BY confidence DESC")
    fun getAllActiveMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0")
    suspend fun getAllActiveMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 AND workspaceId IS :workspaceId")
    suspend fun getActiveForWorkspace(workspaceId: String?): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 AND workspaceId IS :workspaceId AND (agentId IS :agentId OR agentId IS NULL)")
    suspend fun getActiveForWorkspaceAndAgent(workspaceId: String?, agentId: String?): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 AND agentId IS :agentId")
    suspend fun getActiveForAgent(agentId: String?): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 AND memoryType IN (:types)")
    suspend fun getActiveByTypes(types: List<String>): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 AND workspaceId IS :workspaceId AND memoryType IN (:types) AND (agentId IS :agentId OR agentId IS NULL)")
    suspend fun getActiveForWorkspaceAndAgentAndTypes(
        workspaceId: String?,
        agentId: String?,
        types: List<String>
    ): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE lastDecayEvaluatedAtEpochMs < :cutoff AND isArchived = 0")
    suspend fun getMemoriesDueForDecay(cutoff: Long): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE decayScore < :threshold AND isArchived = 0")
    suspend fun getMemoriesBelowDecay(threshold: Float): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 1 AND lastAccessedEpochMs < :cutoff")
    suspend fun getArchivedOlderThan(cutoff: Long): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("UPDATE memory_records SET lastAccessedEpochMs = :now, accessCount = accessCount + 1, decayScore = MIN(1.0, decayScore + :accessBoost) WHERE id = :id")
    suspend fun touchMemory(id: String, now: Long, accessBoost: Float = 0.2f)

    @Query("UPDATE memory_records SET decayScore = :decayScore, lastDecayEvaluatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateDecayScore(id: String, decayScore: Float, now: Long)

    @Query("UPDATE memory_records SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE memory_records SET text = :content, confidence = :confidence, memoryType = :type WHERE id = :id")
    suspend fun mergeContent(id: String, content: String, confidence: Float, type: String)

    @Query("DELETE FROM memory_records WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memory_records")
    suspend fun clearAll()

    @Query("DELETE FROM memory_records WHERE isArchived = 1 AND lastAccessedEpochMs < :cutoff")
    suspend fun deleteArchivedOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM memory_records WHERE isArchived = 0 AND workspaceId IS :workspaceId")
    suspend fun activeCountForWorkspace(workspaceId: String?): Int

    @Query("SELECT COUNT(*) FROM memory_records WHERE isArchived = 0 AND agentId IS :agentId")
    suspend fun activeCountForAgent(agentId: String?): Int

    @Query("SELECT COUNT(*) FROM memory_records WHERE isArchived = 0")
    suspend fun activeCountGlobal(): Int

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 AND workspaceId IS :workspaceId ORDER BY lastAccessedEpochMs ASC LIMIT :limit")
    suspend fun leastRecentlyUsedForWorkspace(workspaceId: String?, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memory_records WHERE isArchived = 0 ORDER BY lastAccessedEpochMs ASC LIMIT :limit")
    suspend fun leastRecentlyUsedGlobal(limit: Int): List<MemoryEntity>
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

    // ------------------------------------------------------------------
    // P1-7 (audit 2026 §6 — TaskEntity carries no workspace identity):
    // workspace-scoped task queries (v15 column). Legacy rows with NULL
    // workspaceId are visible ONLY through the global maintenance queries
    // above — never through a workspace's scoped view.
    // ------------------------------------------------------------------

    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId ORDER BY createdAtEpochMs DESC")
    fun getTasksForWorkspaceFlow(workspaceId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE workspaceId = :workspaceId ORDER BY createdAtEpochMs DESC")
    suspend fun getTasksForWorkspace(workspaceId: String): List<TaskEntity>

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

    /**
     * Durable-execution checkpoint (audit 2026 fix): persists the closed-loop
     * state (current step, accumulated evidence/output, token count) so a
     * task that survives process death can be RESUMED instead of re-run.
     */
    @Query("UPDATE tasks SET currentStepIndex = :stepIndex, checkpointJson = :checkpointJson, totalTokensConsumed = :tokens, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateCheckpoint(id: String, stepIndex: Int, checkpointJson: String, tokens: Int, now: Long)
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

    /**
     * FIFO eviction support for the bounded case base (domain-owned bound of
     * CaseBase.CASE_BASE_BOUND; the store prunes older-than-cutoff rows).
     */
    @Query("DELETE FROM decision_cases WHERE timestampEpochMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
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

    /**
     * GAP-CLOSURE P1-17: durable security-audit verdict for an evolution
     * candidate (previously securityAuditPassed could NEVER become true —
     * the whole pipeline was a dead end at the governance gate).
     */
    @Query("UPDATE evolution_candidates SET securityAuditPassed = :passed, evaluationNotes = :notes, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateSecurityAudit(id: String, passed: Boolean, notes: String, now: Long)

    /** GAP-CLOSURE P1-17: durable retirement reason. */
    @Query("UPDATE evolution_candidates SET stage = :stage, evaluationNotes = :notes, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateStageWithNotes(id: String, stage: String, notes: String, now: Long)
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


@Dao
interface MdpQValueDao {
    @Query("SELECT * FROM mdp_q_values")
    suspend fun getAll(): List<MdpQValueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<MdpQValueEntity>)

    @Query("DELETE FROM mdp_q_values WHERE lastUpdatedEpochMs < :cutoffEpochMs")
    suspend fun pruneStale(cutoffEpochMs: Long)
}
