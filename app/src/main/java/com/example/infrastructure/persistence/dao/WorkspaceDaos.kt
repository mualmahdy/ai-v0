package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.ResourceEdgeEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 — DAO for the persistent Workspace registry.
 *
 * Provides the persistence operations needed by WorkspaceRuntimeService so the
 * active workspace survives app restart and users can switch between multiple
 * workspaces.
 */
@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY lastAccessedEpochMs DESC")
    fun observeAllWorkspaces(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY lastAccessedEpochMs DESC")
    suspend fun getAllWorkspaces(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getWorkspaceById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveWorkspace(): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE isActive = 1 LIMIT 1")
    fun observeActiveWorkspace(): Flow<WorkspaceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(workspace: WorkspaceEntity)

    @Update
    suspend fun update(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE workspaces SET isActive = 1, lastAccessedEpochMs = :now WHERE id = :id")
    suspend fun setActive(id: String, now: Long)

    @Query("UPDATE workspaces SET lastActiveProjectId = :projectId, lastAccessedEpochMs = :now WHERE id = :workspaceId")
    suspend fun setActiveProject(workspaceId: String, projectId: Long?, now: Long)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)
}

/**
 * Phase 2 — DAO for knowledge documents (RAG persistence).
 */
@Dao
interface KnowledgeDocumentDao {
    @Query("SELECT * FROM knowledge_documents WHERE workspaceId = :workspaceId AND isArchived = 0 ORDER BY createdAtEpochMs DESC")
    fun observeDocumentsForWorkspace(workspaceId: String): Flow<List<KnowledgeDocumentEntity>>

    @Query("SELECT * FROM knowledge_documents WHERE workspaceId = :workspaceId AND isArchived = 0 ORDER BY createdAtEpochMs DESC")
    suspend fun getDocumentsForWorkspace(workspaceId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_documents WHERE projectId = :projectId AND isArchived = 0 ORDER BY createdAtEpochMs DESC")
    suspend fun getDocumentsForProject(projectId: Long): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: String): KnowledgeDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(document: KnowledgeDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<KnowledgeDocumentEntity>)

    @Query("UPDATE knowledge_documents SET isArchived = 1, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun archive(id: String, now: Long)

    @Query("DELETE FROM knowledge_documents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_documents WHERE workspaceId = :workspaceId")
    suspend fun deleteAllForWorkspace(workspaceId: String)

    @Query("SELECT COUNT(*) FROM knowledge_documents WHERE workspaceId = :workspaceId AND isArchived = 0")
    suspend fun countForWorkspace(workspaceId: String): Int
}

/**
 * Phase 2 — DAO for document chunks (RAG persistence).
 *
 * Note: Vector similarity search is still done in-application code (load all
 * chunks for a workspace, score against query vector). This is O(n) per query
 * which is fine for < 10k chunks. Phase 4 will introduce sqlite-vec or HNSW
 * for production-scale vector search.
 */
@Dao
interface DocumentChunkDao {
    @Query("SELECT * FROM document_chunks WHERE workspaceId = :workspaceId ORDER BY documentId, chunkIndex")
    suspend fun getChunksForWorkspace(workspaceId: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY chunkIndex")
    suspend fun getChunksForDocument(documentId: String): List<DocumentChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(chunk: DocumentChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteChunksForDocument(documentId: String)

    @Query("DELETE FROM document_chunks WHERE workspaceId = :workspaceId")
    suspend fun deleteChunksForWorkspace(workspaceId: String)

    @Query("SELECT COUNT(*) FROM document_chunks WHERE workspaceId = :workspaceId")
    suspend fun countForWorkspace(workspaceId: String): Int
}

/**
 * Phase 2 — DAO for the persistent Resource Graph edges.
 */
@Dao
interface ResourceEdgeDao {
    @Query("SELECT * FROM resource_edges WHERE workspaceId = :workspaceId ORDER BY createdAtEpochMs DESC")
    suspend fun getEdgesForWorkspace(workspaceId: String): List<ResourceEdgeEntity>

    @Query("SELECT * FROM resource_edges WHERE workspaceId = :workspaceId AND sourceId = :sourceId")
    suspend fun getOutgoingEdges(workspaceId: String, sourceId: String): List<ResourceEdgeEntity>

    @Query("SELECT * FROM resource_edges WHERE workspaceId = :workspaceId AND targetId = :targetId")
    suspend fun getIncomingEdges(workspaceId: String, targetId: String): List<ResourceEdgeEntity>

    @Query("SELECT * FROM resource_edges WHERE workspaceId = :workspaceId AND edgeType = :edgeType")
    suspend fun getEdgesByType(workspaceId: String, edgeType: String): List<ResourceEdgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: ResourceEdgeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(edges: List<ResourceEdgeEntity>): List<Long>

    @Query("DELETE FROM resource_edges WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM resource_edges WHERE workspaceId = :workspaceId AND sourceId = :sourceId AND targetId = :targetId AND edgeType = :edgeType")
    suspend fun deleteEdge(workspaceId: String, sourceId: String, targetId: String, edgeType: String)

    @Query("DELETE FROM resource_edges WHERE workspaceId = :workspaceId")
    suspend fun deleteAllForWorkspace(workspaceId: String)

    @Query("SELECT COUNT(*) FROM resource_edges WHERE workspaceId = :workspaceId")
    suspend fun countForWorkspace(workspaceId: String): Int
}
