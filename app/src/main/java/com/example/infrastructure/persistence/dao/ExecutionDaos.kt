package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ActionIntentEntity
import com.example.infrastructure.persistence.entities.AgentDefinitionEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * ExecutionDaos — gap-closure persistence (DB v11)
 * ============================================================================
 *
 * [ActionIntentDao] — durable idempotency ledger (P0-05 / P0-06).
 * [AgentDefinitionDao] — canonical durable agent registry (P1-08 / P1-09).
 */
@Dao
interface ActionIntentDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIntent(intent: ActionIntentEntity): Long

    @Query("SELECT * FROM action_intents WHERE executionId = :executionId AND actionKey = :actionKey LIMIT 1")
    suspend fun getIntent(executionId: String, actionKey: String): ActionIntentEntity?

    @Query("SELECT * FROM action_intents WHERE executionId = :executionId ORDER BY stepIndex ASC")
    suspend fun getIntentsForExecution(executionId: String): List<ActionIntentEntity>

    @Query("UPDATE action_intents SET state = :state, outputFingerprint = :fingerprint, outputSummary = :summary, updatedAtEpochMs = :now WHERE executionId = :executionId AND actionKey = :actionKey")
    suspend fun updateIntentOutcome(
        executionId: String,
        actionKey: String,
        state: String,
        fingerprint: String?,
        summary: String?,
        now: Long
    )

    @Query("DELETE FROM action_intents WHERE executionId = :executionId")
    suspend fun clearIntentsForExecution(executionId: String)

    @Query("SELECT COUNT(*) FROM action_intents WHERE executionId = :executionId AND state = 'COMPLETED'")
    suspend fun completedIntentCount(executionId: String): Int
}

@Dao
interface AgentDefinitionDao {

    @Query("SELECT * FROM agent_definitions ORDER BY createdAtEpochMs ASC")
    fun allAgentsFlow(): Flow<List<AgentDefinitionEntity>>

    @Query("SELECT * FROM agent_definitions ORDER BY createdAtEpochMs ASC")
    suspend fun allAgents(): List<AgentDefinitionEntity>

    @Query("SELECT * FROM agent_definitions WHERE id = :id LIMIT 1")
    suspend fun getAgentById(id: String): AgentDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: AgentDefinitionEntity)

    @Query("DELETE FROM agent_definitions WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("SELECT COUNT(*) FROM agent_definitions")
    suspend fun agentCount(): Int
}
