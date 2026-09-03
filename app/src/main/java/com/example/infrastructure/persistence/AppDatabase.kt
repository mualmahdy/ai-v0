package com.example.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/**
 * AI-V0 Ultimate — Room Database
 *
 * FIX INF-P0-10: Replaced `fallbackToDestructiveMigration()` with explicit Migration objects.
 * The previous behaviour wiped all user data (projects, sessions, memory vectors, decision
 * cases, radar items) on every schema bump. This is the single most dangerous line in the
 * codebase for a production-targeted app.
 *
 * Migration policy going forward:
 *   1. Bump `version` below when adding columns/tables.
 *   2. Add a new `MIGRATION_N_TO_N1` Migration object to `ALL_MIGRATIONS`.
 *   3. Set `exportSchema = true` once the schemas/ directory is wired in build.gradle.
 *
 * Note: We still keep `exportSchema = false` for now because enabling it requires
 * adding `room.schemaLocation` argument to ksp in build.gradle and checking in the
 * generated JSON. That's tracked as a follow-up — the destructive migration removal
 * is the critical safety fix; explicit migrations cover all schema changes from v3
 * forward.
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
        ProviderConfigEntity::class
    ],
    version = 4,
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

        /**
         * Migration v3 → v4: add full-fidelity TaskEntity columns needed by
         * AgentOrchestrator.resumeTask (APP-P0-07 fix). All new columns have
         * defaults so existing rows migrate cleanly.
         */
        private val MIGRATION_3_TO_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new columns to the tasks table. Room's @PrimaryKey on `id`
                // is preserved. All columns have defaults so the ALTER TABLE is safe.
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

                // Backfill `goal` from `rawPrompt` so existing rows have a non-empty goal.
                db.execSQL("UPDATE tasks SET goal = rawPrompt WHERE goal = '' AND rawPrompt != ''")
            }
        }

        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_3_TO_4,
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agent_orchestrator_platform.db"
                )
                    // FIX INF-P0-10: explicit migrations instead of destructive fallback.
                    .addMigrations(*ALL_MIGRATIONS)
                    // If a migration is missing (e.g. user downgrades or we forget one),
                    // fail loudly instead of silently wiping user data.
                    // On a fresh install (no DB file) Room creates the schema directly
                    // — no migration needed.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
