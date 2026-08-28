package com.example.data.local.db.daos

import androidx.room.*
import com.example.data.local.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY isDefault DESC, lastOpenedAt DESC, createdAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProject(): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun getSessionsForProject(projectId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE projectId = :projectId AND sessionId = :sessionId ORDER BY id ASC")
    fun getMessagesForSession(projectId: Long, sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE projectId = :projectId AND sessionId = :sessionId ORDER BY id ASC")
    suspend fun getMessagesList(projectId: Long, sessionId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE projectId = :projectId AND sessionId = :sessionId")
    suspend fun getMessageCount(projectId: Long, sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
}

@Dao
interface AgentConfigDao {
    @Query("SELECT * FROM agents_config WHERE projectId = :projectId ORDER BY name ASC")
    fun getAgentsForProject(projectId: Long): Flow<List<AgentConfigEntity>>

    @Query("SELECT * FROM agents_config WHERE projectId = :projectId AND name = :name LIMIT 1")
    suspend fun getAgentByName(projectId: Long, name: String): AgentConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentConfigEntity): Long

    @Update
    suspend fun updateAgent(agent: AgentConfigEntity)

    @Query("DELETE FROM agents_config WHERE projectId = :projectId AND name = :name")
    suspend fun deleteAgent(projectId: Long, name: String)
}

@Dao
interface ModelProviderDao {
    @Query("SELECT * FROM model_providers WHERE projectId = :projectId ORDER BY priority ASC")
    fun getProvidersForProject(projectId: Long): Flow<List<ModelProviderEntity>>

    @Query("SELECT * FROM model_providers WHERE projectId = :projectId AND enabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledProviders(projectId: Long): List<ModelProviderEntity>

    @Query("SELECT * FROM model_providers WHERE id = :id LIMIT 1")
    suspend fun getProviderById(id: Long): ModelProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ModelProviderEntity): Long

    @Update
    suspend fun updateProvider(provider: ModelProviderEntity)

    @Query("DELETE FROM model_providers WHERE id = :id")
    suspend fun deleteProvider(id: Long)

    @Query("SELECT * FROM model_roles WHERE projectId = :projectId")
    fun getModelRoles(projectId: Long): Flow<List<ModelRoleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setModelRole(role: ModelRoleEntity)
}

@Dao
interface SearchProviderDao {
    @Query("SELECT * FROM search_providers WHERE projectId = :projectId ORDER BY priority ASC")
    fun getSearchProviders(projectId: Long): Flow<List<SearchProviderEntity>>

    @Query("SELECT * FROM search_providers WHERE projectId = :projectId AND enabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledSearchProviders(projectId: Long): List<SearchProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchProvider(provider: SearchProviderEntity): Long

    @Update
    suspend fun updateSearchProvider(provider: SearchProviderEntity)

    @Query("DELETE FROM search_providers WHERE id = :id")
    suspend fun deleteSearchProvider(id: Long)
}

@Dao
interface EmbeddingProviderDao {
    @Query("SELECT * FROM embedding_providers WHERE projectId = :projectId ORDER BY isDefault DESC, priority ASC")
    fun getEmbeddingProviders(projectId: Long): Flow<List<EmbeddingProviderEntity>>

    @Query("SELECT * FROM embedding_providers WHERE projectId = :projectId AND enabled = 1 ORDER BY isDefault DESC, priority ASC LIMIT 1")
    suspend fun getDefaultEmbeddingProvider(projectId: Long): EmbeddingProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddingProvider(provider: EmbeddingProviderEntity): Long

    @Update
    suspend fun updateEmbeddingProvider(provider: EmbeddingProviderEntity)

    @Query("DELETE FROM embedding_providers WHERE id = :id")
    suspend fun deleteEmbeddingProvider(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM long_term_memory WHERE projectId = :projectId AND status = 'active' ORDER BY importance DESC, timestamp DESC")
    fun getActiveMemories(projectId: Long): Flow<List<LongTermMemoryEntity>>

    @Query("SELECT * FROM long_term_memory WHERE projectId = :projectId ORDER BY id DESC")
    fun getAllMemories(projectId: Long): Flow<List<LongTermMemoryEntity>>

    @Query("SELECT * FROM long_term_memory WHERE projectId = :projectId AND status = 'active' AND (content LIKE '%' || :query || '%')")
    suspend fun searchMemoriesLexical(projectId: Long, query: String): List<LongTermMemoryEntity>

    @Query("SELECT * FROM long_term_memory WHERE projectId = :projectId AND status = 'active' AND embeddingJson IS NOT NULL")
    suspend fun getMemoriesWithEmbeddings(projectId: Long): List<LongTermMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: LongTermMemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: LongTermMemoryEntity)

    @Query("DELETE FROM long_term_memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM documents WHERE projectId = :projectId ORDER BY id DESC")
    fun getDocuments(projectId: Long): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM knowledge_collections WHERE projectId = :projectId")
    fun getCollections(projectId: Long): Flow<List<KnowledgeCollectionEntity>>

    @Query("SELECT * FROM document_chunks WHERE projectId = :projectId")
    suspend fun getAllChunks(projectId: Long): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE projectId = :projectId AND docId = :docId")
    suspend fun getChunksForDoc(projectId: Long, docId: String): List<DocumentChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollection(col: KnowledgeCollectionEntity)

    @Query("DELETE FROM documents WHERE projectId = :projectId AND docId = :docId")
    suspend fun deleteDocument(projectId: Long, docId: String)

    @Query("DELETE FROM document_chunks WHERE projectId = :projectId AND docId = :docId")
    suspend fun deleteChunks(projectId: Long, docId: String)
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows WHERE projectId = :projectId ORDER BY id DESC")
    fun getWorkflows(projectId: Long): Flow<List<WorkflowEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkflow(workflow: WorkflowEntity): Long

    @Query("SELECT * FROM workflow_state WHERE projectId = :projectId AND workflowId = :workflowId LIMIT 1")
    suspend fun getWorkflowState(projectId: Long, workflowId: String): WorkflowStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorkflowState(state: WorkflowStateEntity)
}

@Dao
interface TokenBudgetDao {
    @Query("SELECT * FROM token_budget_usage WHERE projectId = :projectId")
    fun getUsages(projectId: Long): Flow<List<TokenBudgetUsageEntity>>

    @Query("SELECT * FROM token_budget_usage WHERE projectId = :projectId AND agentName = :agentName LIMIT 1")
    suspend fun getUsage(projectId: Long, agentName: String): TokenBudgetUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordUsage(usage: TokenBudgetUsageEntity)

    @Query("DELETE FROM token_budget_usage WHERE projectId = :projectId AND agentName = :agentName")
    suspend fun resetUsage(projectId: Long, agentName: String)
}

@Dao
interface FileVersionDao {
    @Query("SELECT * FROM file_versions WHERE projectId = :projectId AND relativePath = :relativePath ORDER BY id DESC")
    fun getVersions(projectId: Long, relativePath: String): Flow<List<FileVersionEntity>>

    @Query("SELECT * FROM file_versions WHERE id = :id LIMIT 1")
    suspend fun getVersionById(id: Long): FileVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: FileVersionEntity): Long
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY id DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditLogEntity): Long
}

@Dao
interface WorkspaceComponentDao {
    @Query("SELECT * FROM workspace_components ORDER BY displayOrder ASC")
    fun getAllComponents(): Flow<List<WorkspaceComponentEntity>>

    @Query("SELECT * FROM workspace_components WHERE isVisible = 1 ORDER BY displayOrder ASC")
    fun getVisibleComponents(): Flow<List<WorkspaceComponentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponents(components: List<WorkspaceComponentEntity>)

    @Update
    suspend fun updateComponent(component: WorkspaceComponentEntity)
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings")
    fun getAllSettings(): Flow<List<AppSettingEntity>>

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: AppSettingEntity)
}
