package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.db.daos.*
import com.example.data.local.db.entities.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        AgentConfigEntity::class,
        ModelProviderEntity::class,
        ModelRoleEntity::class,
        SearchProviderEntity::class,
        EmbeddingProviderEntity::class,
        LongTermMemoryEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
        KnowledgeCollectionEntity::class,
        WorkflowEntity::class,
        WorkflowStateEntity::class,
        TokenBudgetUsageEntity::class,
        FileVersionEntity::class,
        AuditLogEntity::class,
        WorkspaceComponentEntity::class,
        AppSettingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun searchProviderDao(): SearchProviderDao
    abstract fun embeddingProviderDao(): EmbeddingProviderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun tokenBudgetDao(): TokenBudgetDao
    abstract fun fileVersionDao(): FileVersionDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun workspaceComponentDao(): WorkspaceComponentDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_v0_ultimate_android.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(DatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(
        private val scope: CoroutineScope
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    DatabaseInitializer.populateInitialData(database)
                }
            }
        }
    }
}
