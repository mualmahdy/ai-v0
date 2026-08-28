package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.SessionEntity
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
