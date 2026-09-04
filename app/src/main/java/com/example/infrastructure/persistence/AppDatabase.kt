package com.example.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.infrastructure.persistence.dao.DecisionCaseDao
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.dao.EvolutionCandidateDao
import com.example.infrastructure.persistence.dao.ExecutionLogDao
import com.example.infrastructure.persistence.dao.ExtensionConfigDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.ProviderConfigDao
import com.example.infrastructure.persistence.dao.RadarItemDao
import com.example.infrastructure.persistence.dao.ResourceEdgeDao
import com.example.infrastructure.persistence.dao.SessionDao
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.EvolutionCandidateEntity
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.ProviderConfigEntity
import com.example.infrastructure.persistence.entities.RadarItemEntity
import com.example.infrastructure.persistence.entities.ResourceEdgeEntity
import com.example.infrastructure.persistence.entities.SessionEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity

/**
 * AI-V0 Ultimate — Room Database
 *
 * FIX INF-P0-10: Replaced `fallbackToDestructiveMigration()` with explicit Migration objects.
 * The previous behaviour wiped all user data (projects, sessions, memory vectors, decision
 * cases, radar items) on every schema bump. This is the single most dangerous line in the
 * codebase for a production-targeted app.
 *
 * Phase 2: Added workspaces, knowledge_documents, document_chunks, resource_edges tables
 * to support true multi-workspace runtime and RAG persistence. Bumped version 4 → 5.
 *
 * Migration policy going forward:
 *   1. Bump `version` below when adding columns/tables.
 *   2. Add a new `MIGRATION_N_TO_N1` Migration object to `ALL_MIGRATIONS`.
 *   3. Set `exportSchema = true` once the schemas/ directory is wired in build.gradle.
 */
@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MemoryEntity::class,
        ExecutionLogEntity::class,
        TaskEntity::class,
        DecisionCaseEntity::class,
        RadarItemEntity::class,
        EvolutionCandidateEntity::class,
        ExtensionConfigEntity::class,
        ProviderConfigEntity::class,
        // Phase 2 — new entities for true multi-workspace runtime + RAG persistence
        WorkspaceEntity::class,
        KnowledgeDocumentEntity::class,
        DocumentChunkEntity::class,
        ResourceEdgeEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun taskDao(): TaskDao
    abstract fun decisionCaseDao(): DecisionCaseDao
    abstract fun radarItemDao(): RadarItemDao
    abstract fun evolutionCandidateDao(): EvolutionCandidateDao
    abstract fun extensionConfigDao(): ExtensionConfigDao
    abstract fun providerConfigDao(): ProviderConfigDao
    // Phase 2 — new DAOs
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun resourceEdgeDao(): ResourceEdgeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration v3 → v4: add full-fidelity TaskEntity columns needed by
         * AgentOrchestrator.resumeTask (APP-P0-07 fix). All new columns have
         * defaults so existing rows migrate cleanly.
         */
        private val MIGRATION_3_TO_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN goal TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN currentStepIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN tokenLimit INTEGER NOT NULL DEFAULT 30000")
                db.execSQL("ALTER TABLE tasks ADD COLUMN maxRetries INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE tasks ADD COLUMN allowDegradedExecution INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requireHumanConsentForSensitiveTools INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN timeoutMs INTEGER NOT NULL DEFAULT 60000")
                db.execSQL("ALTER TABLE tasks ADD COLUMN minOutputLengthChars INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN verificationStrategy TEXT NOT NULL DEFAULT 'STRICT'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN assignedModelId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN activeToolsJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requiredCapabilitiesJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requiredEvidenceKeysJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requiredOutputKeysJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN executionLogJson TEXT")
                db.execSQL("UPDATE tasks SET goal = rawPrompt WHERE goal = '' AND rawPrompt != ''")
            }
        }

        /**
         * Phase 2 — Migration v4 → v5: add four new tables for true multi-workspace
         * runtime and RAG persistence. No existing table is altered; the migration
         * is purely additive so it's safe even if the user had data in v4.
         *
         *   workspaces           — multi-workspace registry (one active at a time)
         *   knowledge_documents  — RAG document metadata (was in-memory only before)
         *   document_chunks      — RAG chunk text + embedding vectors (was in-memory only)
         *   resource_edges       — persistent workspace resource graph edges
         *
         * After migration runs, WorkspaceRuntimeService.bootstrapDefaultWorkspace()
         * will create a default workspace (id="default") and mark it active so the
         * existing single-project user flow continues to work without interruption.
         */
        private val MIGRATION_4_TO_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        networkPolicy TEXT NOT NULL,
                        autonomyPolicy TEXT NOT NULL,
                        settingsJson TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        lastActiveProjectId INTEGER,
                        createdAtEpochMs INTEGER NOT NULL,
                        lastAccessedEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspaces_isActive ON workspaces(isActive)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspaces_createdAtEpochMs ON workspaces(createdAtEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_documents (
                        id TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        projectId INTEGER,
                        title TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tagsJson TEXT NOT NULL,
                        totalChunks INTEGER NOT NULL,
                        totalTokensEstimated INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_workspaceId ON knowledge_documents(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_projectId ON knowledge_documents(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_createdAtEpochMs ON knowledge_documents(createdAtEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        workspaceId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        tokenCount INTEGER NOT NULL,
                        vectorDimension INTEGER NOT NULL,
                        vectorJson TEXT NOT NULL,
                        retrievalSource TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_documentId ON document_chunks(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_workspaceId ON document_chunks(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_chunkIndex ON document_chunks(chunkIndex)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS resource_edges (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        workspaceId TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        targetId TEXT NOT NULL,
                        targetType TEXT NOT NULL,
                        edgeType TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        metadataJson TEXT NOT NULL DEFAULT '{}',
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_workspaceId ON resource_edges(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_sourceId ON resource_edges(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_targetId ON resource_edges(targetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_edgeType ON resource_edges(edgeType)")
            }
        }

        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_3_TO_4,
            MIGRATION_4_TO_5,
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agent_orchestrator_platform.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
