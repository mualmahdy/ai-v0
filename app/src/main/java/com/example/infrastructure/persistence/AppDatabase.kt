package com.example.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.infrastructure.persistence.dao.DecisionCaseDao
import com.example.infrastructure.persistence.dao.EvolutionCandidateDao
import com.example.infrastructure.persistence.dao.ExecutionLogDao
import com.example.infrastructure.persistence.dao.ExtensionConfigDao
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.ProviderConfigDao
import com.example.infrastructure.persistence.dao.RadarItemDao
import com.example.infrastructure.persistence.dao.SessionDao
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import com.example.infrastructure.persistence.entities.EvolutionCandidateEntity
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.ProviderConfigEntity
import com.example.infrastructure.persistence.entities.RadarItemEntity
import com.example.infrastructure.persistence.entities.SessionEntity
import com.example.infrastructure.persistence.entities.TaskEntity

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
        ProviderConfigEntity::class
    ],
    version = 3,
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


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agent_orchestrator_platform.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
