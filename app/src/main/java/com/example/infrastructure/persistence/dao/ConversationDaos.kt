package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import com.example.infrastructure.persistence.entities.WorkflowDefinitionEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * DURABLE CONVERSATION SESSION DAOs — DB v13
 * ============================================================================
 */
@Dao
interface ConversationSessionDao {

    @Query("SELECT * FROM chat_sessions WHERE workspaceId = :workspaceId ORDER BY lastActiveAtEpochMs DESC")
    fun forWorkspace(workspaceId: String): Flow<List<ConversationSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :id LIMIT 1")
    suspend fun byId(id: String): ConversationSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ConversationSessionEntity)

    @Query("UPDATE chat_sessions SET modelResourceId = :modelId, modelDisplayName = :modelDisplayName, lastActiveAtEpochMs = :now WHERE sessionId = :id")
    suspend fun updateModel(id: String, modelId: String?, modelDisplayName: String?, now: Long)

    @Query("UPDATE chat_sessions SET title = :title, lastActiveAtEpochMs = :now WHERE sessionId = :id")
    suspend fun rename(id: String, title: String, now: Long)

    /**
     * Atomically bumps session aggregates after a turn append (transactional
     * with the turn insert — see ConversationTurnDao.appendTurnAndBump).
     */
    @Query("UPDATE chat_sessions SET turnCount = turnCount + 1, totalTokensConsumed = totalTokensConsumed + :tokens, lastActiveAtEpochMs = :now WHERE sessionId = :id")
    suspend fun bumpAggregates(id: String, tokens: Int, now: Long)

    @Query("DELETE FROM chat_sessions WHERE sessionId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM chat_sessions WHERE workspaceId = :workspaceId")
    suspend fun countForWorkspace(workspaceId: String): Int
}

@Dao
interface ConversationTurnDao {

    @Query("SELECT * FROM chat_turns WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    fun forSession(sessionId: String): Flow<List<ConversationTurnEntity>>

    @Query("SELECT * FROM chat_turns WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun forSessionOnce(sessionId: String): List<ConversationTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(turn: ConversationTurnEntity)

    @Query("DELETE FROM chat_turns WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

/**
 * ============================================================================
 * WORKFLOW LIBRARY DAO — DB v13 (user-authored workflow assets)
 * ============================================================================
 */
@Dao
interface WorkflowDefinitionDao {

    @Query("SELECT * FROM workflow_definitions WHERE workspaceId = :workspaceId ORDER BY updatedAtEpochMs DESC")
    fun forWorkspace(workspaceId: String): Flow<List<WorkflowDefinitionEntity>>

    @Query("SELECT * FROM workflow_definitions WHERE workflowId = :id LIMIT 1")
    suspend fun byId(id: String): WorkflowDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(definition: WorkflowDefinitionEntity)

    @Query("UPDATE workflow_definitions SET runCount = runCount + 1, lastRunAtEpochMs = :now WHERE workflowId = :id")
    suspend fun recordRun(id: String, now: Long)

    @Query("DELETE FROM workflow_definitions WHERE workflowId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM workflow_definitions WHERE workspaceId = :workspaceId")
    suspend fun countForWorkspace(workspaceId: String): Int
}
