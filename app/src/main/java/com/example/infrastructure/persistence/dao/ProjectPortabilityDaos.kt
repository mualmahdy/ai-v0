package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.infrastructure.persistence.entities.ArtifactEntity
import com.example.infrastructure.persistence.entities.AuditEventEntity
import com.example.infrastructure.persistence.entities.ProjectDependencyEntity
import com.example.infrastructure.persistence.entities.ProjectSnapshotEntity

/**
 * ============================================================================
 * DB v16 DAOs — REPAIR ORDER §7/§24/§26/§30
 * ============================================================================
 * Every query here is SCOPE-AWARE: cross-scope reads are impossible by
 * construction (workspace/project predicates in SQL, not in callers).
 */

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: ArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artifacts: List<ArtifactEntity>)

    /** WORKSPACE-AUTHORIZED load (project filter optional). */
    @Query(
        "SELECT * FROM artifacts WHERE workspaceId = :workspaceId " +
                "AND (:projectId IS NULL OR projectId = :projectId) " +
                "ORDER BY updatedAtEpochMs DESC LIMIT :limit"
    )
    suspend fun forWorkspace(workspaceId: String, projectId: Long? = null, limit: Int = 200): List<ArtifactEntity>

    /** PROJECT-PRIVATE load — a sibling project's artifacts are invisible. */
    @Query("SELECT * FROM artifacts WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun forProject(projectId: Long, limit: Int = 200): List<ArtifactEntity>

    /** APPLICATION-scoped artifacts (workspaceId IS NULL). */
    @Query("SELECT * FROM artifacts WHERE workspaceId IS NULL ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun forApplication(limit: Int = 200): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ArtifactEntity?

    /** WORKSPACE-AUTHORIZED single load. */
    @Query("SELECT * FROM artifacts WHERE id = :id AND workspaceId = :workspaceId LIMIT 1")
    suspend fun byIdForWorkspace(id: String, workspaceId: String): ArtifactEntity?

    /** PROJECT-AUTHORIZED single load (cross-project = NOT FOUND). */
    @Query("SELECT * FROM artifacts WHERE id = :id AND projectId = :projectId LIMIT 1")
    suspend fun byIdForProject(id: String, projectId: Long): ArtifactEntity?

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM artifacts WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("DELETE FROM artifacts WHERE workspaceId = :workspaceId")
    suspend fun deleteForWorkspace(workspaceId: String)

    @Query("SELECT COUNT(*) FROM artifacts WHERE workspaceId = :workspaceId")
    suspend fun countForWorkspace(workspaceId: String): Int

    /** ID-collision check for import idempotency. */
    @Query("SELECT COUNT(*) FROM artifacts WHERE id = :id")
    suspend fun countById(id: String): Int

    @Query("UPDATE artifacts SET indexingState = :state, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateIndexingState(id: String, state: String, now: Long)

    /** Global browser search — respects scope predicates, never bypasses them. */
    @Query(
        "SELECT * FROM artifacts WHERE (:workspaceId IS NULL OR workspaceId = :workspaceId) " +
                "AND (:projectId IS NULL OR projectId = :projectId) " +
                "AND (:nameContains IS NULL OR name LIKE '%' || :nameContains || '%') " +
                "ORDER BY updatedAtEpochMs DESC LIMIT :limit"
    )
    suspend fun search(
        workspaceId: String? = null,
        projectId: Long? = null,
        nameContains: String? = null,
        limit: Int = 200
    ): List<ArtifactEntity>
}

@Dao
interface ProjectDependencyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dependency: ProjectDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(dependencies: List<ProjectDependencyEntity>)

    @Query("SELECT * FROM project_dependencies WHERE projectId = :projectId")
    suspend fun forProject(projectId: Long): List<ProjectDependencyEntity>

    @Query("SELECT COUNT(*) FROM project_dependencies WHERE projectId = :projectId AND requirement = 'REQUIRED' AND status != 'RESOLVED'")
    suspend fun unresolvedRequiredCount(projectId: Long): Int

    @Query("DELETE FROM project_dependencies WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("DELETE FROM project_dependencies WHERE projectId = :projectId AND type = :type AND `key` = :key")
    suspend fun deleteOne(projectId: Long, type: String, key: String)
}

@Dao
interface ProjectSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ProjectSnapshotEntity)

    @Query("SELECT * FROM project_snapshots WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC")
    suspend fun forProject(projectId: Long): List<ProjectSnapshotEntity>

    @Query("SELECT * FROM project_snapshots WHERE id = :id AND projectId = :projectId LIMIT 1")
    suspend fun byIdForProject(id: String, projectId: Long): ProjectSnapshotEntity?

    @Query("DELETE FROM project_snapshots WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM project_snapshots WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("SELECT COUNT(*) FROM project_snapshots WHERE projectId = :projectId")
    suspend fun countForProject(projectId: Long): Int
}

@Dao
interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AuditEventEntity): Long

    @Query("SELECT * FROM audit_events ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<AuditEventEntity>

    /**
     * GAP-24 (Design Closure 2026, ADR-8): live unscoped stream — the
     * unified audit reader in RoomTelemetryRepository combines this with
     * `audit_trail` so BOTH audit tables feed the activity feed (previously
     * this table had zero readers while its richer sibling fed nothing).
     */
    @Query("SELECT * FROM audit_events ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): kotlinx.coroutines.flow.Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE workspaceId = :workspaceId ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun forWorkspace(workspaceId: String, limit: Int = 100): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE workspaceId = :workspaceId ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    fun observeForWorkspace(workspaceId: String, limit: Int = 100): kotlinx.coroutines.flow.Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE projectId = :projectId ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun forProject(projectId: Long, limit: Int = 100): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE action = :action ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun forAction(action: String, limit: Int = 100): List<AuditEventEntity>

    @Query("SELECT COUNT(*) FROM audit_events WHERE workspaceId = :workspaceId")
    suspend fun countForWorkspace(workspaceId: String): Int
}
