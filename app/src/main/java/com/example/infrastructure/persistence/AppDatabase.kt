package com.example.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.infrastructure.persistence.dao.ExecutionLogDao
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.SessionDao
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.SessionEntity

@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MemoryEntity::class,
        ExecutionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun executionLogDao(): ExecutionLogDao

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
