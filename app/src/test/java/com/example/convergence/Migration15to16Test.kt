package com.example.convergence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * REPAIR ORDER §33/§34 — Migration15to16Test (Robolectric, raw SQLite)
 * ============================================================================
 *
 * Validates MIGRATION_15_TO_16 directly against a raw SQLite database seeded
 * with a complete v15 schema (a real upgrading device has every table the
 * migration touches):
 *
 *   1. `projects` gains lifecycleState/archivedAtEpochMs/trashedAtEpochMs;
 *      legacy isArchived=1 backfills to 'ARCHIVED' (+ index).
 *   2. `knowledge_documents` / `chat_sessions` / `tasks` gain nullable
 *      projectId (+ indices); tasks.projectId is backfilled from the pinned
 *      canonical execution context JSON.
 *   3. `mdp_q_values` gains actionSpaceVersion (legacy rows stay NULL).
 *   4. NEW tables exist with correct shape: artifacts,
 *      project_dependencies, project_snapshots, audit_events.
 *   5. Existing data survives untouched (additive-only migration).
 */
@RunWith(RobolectricTestRunner::class)
class Migration15to16Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun createRawDb(): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) { /* no-op */ }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
                })
                .build()
        )
        return helper.writableDatabase
    }

    /** A COMPLETE v15-era schema (every table MIGRATION_15_TO_16 touches). */
    private fun seedV15Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                rootPath TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                isArchived INTEGER NOT NULL DEFAULT 0,
                workspaceId TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS knowledge_documents (
                id TEXT NOT NULL PRIMARY KEY,
                workspaceId TEXT NOT NULL,
                title TEXT NOT NULL,
                sourceUri TEXT NOT NULL,
                content TEXT NOT NULL,
                mimeType TEXT NOT NULL DEFAULT 'text/markdown',
                tagsJson TEXT NOT NULL,
                totalChunks INTEGER NOT NULL,
                totalTokensEstimated INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                isArchived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                sessionId TEXT NOT NULL PRIMARY KEY,
                workspaceId TEXT NOT NULL,
                title TEXT NOT NULL,
                mode TEXT NOT NULL,
                agentId TEXT,
                agentName TEXT,
                modelResourceId TEXT,
                modelDisplayName TEXT,
                turnCount INTEGER NOT NULL DEFAULT 0,
                totalTokensConsumed INTEGER NOT NULL DEFAULT 0,
                createdAtEpochMs INTEGER NOT NULL,
                lastActiveAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT NOT NULL PRIMARY KEY,
                assignedAgentId TEXT NOT NULL,
                rawPrompt TEXT NOT NULL,
                lifecycleState TEXT NOT NULL,
                autonomyPolicy TEXT NOT NULL,
                resultSummary TEXT,
                totalTokensConsumed INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL DEFAULT 0,
                isDegraded INTEGER NOT NULL DEFAULT 0,
                degradedReason TEXT,
                errorMessage TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                goal TEXT NOT NULL,
                currentStepIndex INTEGER NOT NULL DEFAULT 0,
                tokenLimit INTEGER NOT NULL DEFAULT 30000,
                maxRetries INTEGER NOT NULL DEFAULT 3,
                allowDegradedExecution INTEGER NOT NULL DEFAULT 1,
                requireHumanConsentForSensitiveTools INTEGER NOT NULL DEFAULT 1,
                timeoutMs INTEGER NOT NULL DEFAULT 60000,
                minOutputLengthChars INTEGER NOT NULL DEFAULT 1,
                verificationStrategy TEXT NOT NULL DEFAULT 'STRICT',
                assignedModelId TEXT,
                activeToolsJson TEXT,
                requiredCapabilitiesJson TEXT,
                requiredEvidenceKeysJson TEXT,
                requiredOutputKeysJson TEXT,
                executionLogJson TEXT,
                parentTaskId TEXT,
                delegationDepth INTEGER NOT NULL DEFAULT 0,
                checkpointJson TEXT,
                executionContextJson TEXT,
                workspaceId TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mdp_q_values (
                regionKey TEXT NOT NULL,
                resourceKey TEXT NOT NULL,
                actionType TEXT NOT NULL,
                qValue REAL NOT NULL,
                visitCount INTEGER NOT NULL,
                successCount INTEGER NOT NULL,
                lastUpdatedEpochMs INTEGER NOT NULL,
                PRIMARY KEY(regionKey, resourceKey, actionType)
            )
            """.trimIndent()
        )

        // Seed data that must SURVIVE the migration.
        db.execSQL(
            "INSERT INTO projects (name, description, rootPath, createdAtEpochMs, updatedAtEpochMs, isArchived, workspaceId) " +
                    "VALUES ('ActiveProj', NULL, 'r', 1, 1, 0, 'ws1')"
        )
        db.execSQL(
            "INSERT INTO projects (name, description, rootPath, createdAtEpochMs, updatedAtEpochMs, isArchived, workspaceId) " +
                    "VALUES ('ArchivedProj', NULL, 'r', 1, 1, 1, 'ws1')"
        )
        db.execSQL(
            "INSERT INTO knowledge_documents (id, workspaceId, title, sourceUri, content, tagsJson, totalChunks, totalTokensEstimated, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('doc1', 'ws1', 'T', 's', 'c', '[]', 1, 1, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO chat_sessions (sessionId, workspaceId, title, mode, turnCount, totalTokensConsumed, createdAtEpochMs, lastActiveAtEpochMs) " +
                    "VALUES ('sess1', 'ws1', 'S', 'QUICK_CHAT', 0, 0, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO tasks (id, assignedAgentId, rawPrompt, lifecycleState, autonomyPolicy, createdAtEpochMs, updatedAtEpochMs, goal, executionContextJson, workspaceId) " +
                    "VALUES ('task1', 'a1', 'p', 'COMPLETED', 'SUPERVISED', 1, 1, 'p', " +
                    "'{\"executionId\":\"exec_1\",\"workspaceId\":\"ws1\",\"projectId\":7}', 'ws1')"
        )
        db.execSQL(
            "INSERT INTO mdp_q_values (regionKey, resourceKey, actionType, qValue, visitCount, successCount, lastUpdatedEpochMs) " +
                    "VALUES ('r1', 'R:none', 'COMPLETE', 0.5, 3, 2, 1)"
        )
    }

    private fun runMigration15To16(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_15_TO_16")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
            return c.moveToFirst()
        }
    }

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info($table)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(nameIdx) == column) return true
        }
        return false
    }

    @Test
    fun `projects gain lifecycle columns and archived rows backfill to ARCHIVED`() {
        createRawDb().use { db ->
            seedV15Schema(db)
            runMigration15To16(db)
            assertTrue(columnExists(db, "projects", "lifecycleState"))
            assertTrue(columnExists(db, "projects", "archivedAtEpochMs"))
            assertTrue(columnExists(db, "projects", "trashedAtEpochMs"))
            // Data survives + backfill semantics.
            db.query("SELECT name, lifecycleState FROM projects WHERE name='ActiveProj'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ACTIVE", c.getString(1))
            }
            db.query("SELECT lifecycleState FROM projects WHERE name='ArchivedProj'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ARCHIVED", c.getString(0))
            }
            // The lifecycle index exists.
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_projects_lifecycleState'").use { c ->
                assertTrue("lifecycleState index must exist", c.moveToFirst())
            }
        }
    }

    @Test
    fun `scope columns land on knowledge sessions and tasks with backfill`() {
        createRawDb().use { db ->
            seedV15Schema(db)
            runMigration15To16(db)
            assertTrue(columnExists(db, "knowledge_documents", "projectId"))
            assertTrue(columnExists(db, "chat_sessions", "projectId"))
            assertTrue(columnExists(db, "tasks", "projectId"))
            // tasks.projectId backfilled from the pinned execution context JSON.
            db.query("SELECT projectId FROM tasks WHERE id='task1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7L, c.getLong(0))
            }
            // Knowledge + sessions backfill to NULL (honest workspace-shared).
            db.query("SELECT projectId FROM knowledge_documents WHERE id='doc1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
            db.query("SELECT projectId FROM chat_sessions WHERE sessionId='sess1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
        }
    }

    @Test
    fun `mdp_q_values gain actionSpaceVersion with legacy rows NULL`() {
        createRawDb().use { db ->
            seedV15Schema(db)
            runMigration15To16(db)
            assertTrue(columnExists(db, "mdp_q_values", "actionSpaceVersion"))
            // Legacy row kept, version NULL (invalidated honestly at engine load).
            db.query("SELECT qValue, actionSpaceVersion FROM mdp_q_values WHERE regionKey='r1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0.5, c.getDouble(0), 0.0001)
                assertTrue(c.isNull(1))
            }
        }
    }

    @Test
    fun `new v16 tables exist with the right shape`() {
        createRawDb().use { db ->
            seedV15Schema(db)
            runMigration15To16(db)
            for (table in listOf("artifacts", "project_dependencies", "project_snapshots", "audit_events")) {
                assertTrue("$table must exist after migration", tableExists(db, table))
            }
            // Artifacts shape: scope + type + storage + security columns.
            for (col in listOf("workspaceId", "projectId", "type", "contentHash", "storageUri", "securityClassification", "indexingState")) {
                assertTrue("artifacts.$col must exist", columnExists(db, "artifacts", col))
            }
            // Dependencies shape: composite PK columns.
            for (col in listOf("projectId", "type", "key", "requirement", "status")) {
                assertTrue("project_dependencies.$col must exist", columnExists(db, "project_dependencies", col))
            }
            // Snapshots shape.
            for (col in listOf("id", "projectId", "workspaceId", "label", "reason", "manifestJson", "contentHash")) {
                assertTrue("project_snapshots.$col must exist", columnExists(db, "project_snapshots", col))
            }
            // Audit shape: WHO/WHAT/WHEN/SOURCE/TARGET/POLICY/RESULT.
            for (col in listOf("actorType", "actorId", "action", "resourceType", "sourceScopeType", "targetScopeType", "policy", "result", "occurredAtEpochMs")) {
                assertTrue("audit_events.$col must exist", columnExists(db, "audit_events", col))
            }
        }
    }

    @Test
    fun `migration is additive - all pre-existing data survives`() {
        createRawDb().use { db ->
            seedV15Schema(db)
            runMigration15To16(db)
            db.query("SELECT COUNT(*) FROM projects").use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM knowledge_documents").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM chat_sessions").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM tasks").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM mdp_q_values").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }
}
