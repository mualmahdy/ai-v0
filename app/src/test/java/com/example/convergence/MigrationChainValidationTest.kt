package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GAP-01 / GAP-18 (Design Closure 2026, ADR-1) — Migration Chain Identity Test
 * ============================================================================
 *
 * WHAT THIS PROVES
 * ----------------
 * That a real upgrading device survives the full migration path: the raw
 * SQLite database is migrated by the REAL production Migration objects and
 * then validated by ROOM ITSELF (RoomOpenHelper.onUpgrade →
 * onValidateSchema → TableInfo comparison). If the post-migration schema
 * does not match the entity-declared schema — column affinity, NOT NULL,
 * default values, primary keys or indices — Room throws
 * `IllegalStateException: Migration didn't properly handle ...` and this
 * test fails. This is the exact mechanism that crashed upgraded devices
 * before GAP-01 was fixed, so it is the acceptance test for the fix.
 *
 * WHY NOT MigrationTestHelper
 * ---------------------------
 * MigrationTestHelper needs an exported schema JSON for EVERY version
 * (1.json … 16.json). exportSchema was `false` from the first commit, so
 * historical JSONs were never produced and cannot be honestly regenerated
 * without recompiling fifteen historical revisions. This test achieves the
 * SAME guarantee (Room's own validation of the end state) using:
 *   - the v1 schema reconstructed from git history (commit c7d61a1 —
 *     the v1 entity definitions, i.e. what a v1 device actually had), and
 *   - the REAL migration objects, applied by Room's own open path.
 *
 * PATHS COVERED
 * -------------
 *  1. v1  → v16 : the full 15-step chain (any historically shipped device).
 *  2. v15 → v16 : the last step only (the concrete upgrade the Design
 *     Closure called out as the P0 risk).
 *
 * Both paths also assert DATA SURVIVAL (no destructive migration, no row
 * loss) and the GAP-01 reconciliation indices.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationChainValidationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    // ------------------------------------------------------------------
    // Real production migrations (via reflection — repo convention, see
    // Migration15to16Test). ALL_MIGRATIONS is a private companion field of
    // AppDatabase; Kotlin compiles it to a static field.
    // ------------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    private fun allMigrations(): Array<Migration> {
        val field = AppDatabase::class.java.getDeclaredField("ALL_MIGRATIONS")
        field.isAccessible = true
        return field.get(null) as Array<Migration>
    }

    /**
     * The v1 schema exactly as a v1 device had it (git c7d61a1): four
     * tables — projects, sessions, memory_records, execution_logs — with
     * Room-generated semantics (columns, NOT NULL, PKs). No indices: the
     * v1 entities declared none.
     */
    private fun createV1Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                rootPath TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                isArchived INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                sessionId TEXT NOT NULL PRIMARY KEY,
                projectId INTEGER NOT NULL,
                title TEXT NOT NULL,
                assignedAgentId TEXT NOT NULL,
                activeModelId TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                totalTokensConsumed INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_records (
                id TEXT NOT NULL PRIMARY KEY,
                text TEXT NOT NULL,
                vectorDimension INTEGER NOT NULL,
                vectorJson TEXT NOT NULL,
                source TEXT NOT NULL,
                confidence REAL NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                lastAccessedEpochMs INTEGER NOT NULL,
                accessCount INTEGER NOT NULL,
                isArchived INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS execution_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                executionId TEXT NOT NULL,
                sessionId TEXT NOT NULL,
                eventType TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                timestampEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * Rows that must SURVIVE the whole chain (proves migrations are not
     * destructive and the v15→v16 lifecycle backfill behaves).
     */
    private fun seedV1Data(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO projects (name, description, rootPath, createdAtEpochMs, updatedAtEpochMs, isArchived) " +
                "VALUES ('GapActive', null, 'gap/active', 100, 111, 0)"
        )
        db.execSQL(
            "INSERT INTO projects (name, description, rootPath, createdAtEpochMs, updatedAtEpochMs, isArchived) " +
                "VALUES ('GapArchived', null, 'gap/archived', 100, 222, 1)"
        )
        db.execSQL(
            "INSERT INTO memory_records (id, text, vectorDimension, vectorJson, source, confidence, createdAtEpochMs, lastAccessedEpochMs, accessCount, isArchived) " +
                "VALUES ('mem1', 'gap memory', 2, '[0.1,0.9]', 'TEST', 0.9, 100, 100, 1, 0)"
        )
        db.execSQL(
            "INSERT INTO execution_logs (executionId, sessionId, eventType, payloadJson, timestampEpochMs) " +
                "VALUES ('exec1', 'sess1', 'TASK_STARTED', '{}', 100)"
        )
        db.execSQL(
            "INSERT INTO execution_logs (executionId, sessionId, eventType, payloadJson, timestampEpochMs) " +
                "VALUES ('exec1', 'sess1', 'TASK_COMPLETED', '{}', 200)"
        )
    }

    /**
     * Creates a raw FILE database seeded with the v1 schema + data, then
     * applies migrations 1→[targetVersion] manually (bypassing Room's open
     * path) and stamps user_version. The next Room open then performs ONLY
     * the remaining step(s) and — critically — runs Room's real migration
     * validation on the result.
     */
    private fun createRawDatabaseUpgradedTo(dbName: String, targetVersion: Int): Context {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV1Schema(db)
                        seedV1Data(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // Handled manually below — Room performs the real upgrade.
                    }
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            val migrations = allMigrations()
                .filter { it.endVersion <= targetVersion }
                .sortedBy { it.startVersion }
            migrations.forEach { it.migrate(db) }
            db.execSQL("PRAGMA user_version = $targetVersion")
        }
        helper.close()
        return context
    }

    // ------------------------------------------------------------------
    // PATH 1: v1 → v16 (the full chain — any historically shipped device)
    // ------------------------------------------------------------------

    @Test
    fun `full upgrade chain v1 to v16 passes Room's own schema validation`() {
        // Seed a file database that IS a v1 database (only v1 tables + data).
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("gap01-v1.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV1Schema(db)
                        seedV1Data(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // Room performs the real upgrade below.
                    }
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()

        // Open with Room + ALL real migrations: runs 1→2→…→16 inside
        // RoomOpenHelper.onUpgrade, then onValidateSchema compares the
        // resulting schema with the entity-declared schema. Any mismatch
        // (column/NOT NULL/default/PK/index) throws
        // IllegalStateException("Migration didn't properly handle: ...").
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "gap01-v1.db")
            .addMigrations(*allMigrations())
            .allowMainThreadQueries()
            .build()
        val db = room.openHelper.writableDatabase // <-- the real upgrade happens here

        try {
            // Version converged to 17 (v16→v17 drops the three dead GAP-08 tables).
            db.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(17, c.getInt(0))
            }

            // Room accepted the migrated schema: it wrote its identity hash.
            db.query("SELECT identity_hash FROM room_master_table").use { c ->
                assertTrue("room_master_table must contain the identity hash after validation", c.moveToFirst())
                assertTrue(c.getString(0).isNotBlank())
            }

            // The legacy sessions remnant was dropped by MIGRATION_11_TO_12.
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sessions'").use { c ->
                assertFalse("legacy sessions table must be dropped", c.moveToFirst())
            }

            // GAP-01 reconciliation indices exist on the migrated database.
            for (indexName in listOf(
                "index_tasks_projectId",
                "index_knowledge_documents_projectId",
                "index_memory_records_workspaceId",
                "index_tool_audit_log_callerAgentId"
            )) {
                db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$indexName'").use { c ->
                    assertTrue("$indexName must exist after the full chain", c.moveToFirst())
                }
            }

            // DATA SURVIVAL: nothing was destroyed across 15 migrations.
            db.query("SELECT COUNT(*) FROM projects").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM memory_records").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM execution_logs").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
            }

            // v15→v16 lifecycle backfill: isArchived=1 → lifecycleState='ARCHIVED'
            // (with archivedAtEpochMs backfilled from updatedAtEpochMs).
            db.query(
                "SELECT lifecycleState, archivedAtEpochMs FROM projects WHERE name='GapArchived'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ARCHIVED", c.getString(0))
                assertEquals(222L, c.getLong(1))
            }
            db.query(
                "SELECT lifecycleState, archivedAtEpochMs FROM projects WHERE name='GapActive'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ACTIVE", c.getString(0))
                assertTrue(c.isNull(1))
            }

            // Memory content survived intact (text + vector).
            db.query("SELECT text, vectorJson, confidence FROM memory_records WHERE id='mem1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("gap memory", c.getString(0))
                assertEquals("[0.1,0.9]", c.getString(1))
                assertEquals(0.9f, c.getFloat(2), 0.0001f)
            }
        } finally {
            room.close()
        }
    }

    // ------------------------------------------------------------------
    // PATH 2: v15 → v16 (the concrete P0 upgrade scenario from the audit)
    // ------------------------------------------------------------------

    @Test
    fun `v15 to v16 upgrade passes Room's own schema validation`() {
        createRawDatabaseUpgradedTo("gap01-v15.db", 15)

        val room = Room.databaseBuilder(context, AppDatabase::class.java, "gap01-v15.db")
            .addMigrations(*allMigrations())
            .allowMainThreadQueries()
            .build()
        // Runs ONLY MIGRATION_15_TO_16, then validates the end state.
        val db = room.openHelper.writableDatabase

        try {
            db.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(17, c.getInt(0))
            }
            // Validation accepted → identity hash written.
            db.query("SELECT identity_hash FROM room_master_table").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getString(0).isNotBlank())
            }
            // The missing tool_audit_log index was reconciled by 15→16.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_tool_audit_log_callerAgentId'"
            ).use { c ->
                assertTrue("callerAgentId index must be reconciled by 15→16", c.moveToFirst())
            }
            // Data still intact.
            db.query("SELECT COUNT(*) FROM projects").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
            }
            db.query(
                "SELECT lifecycleState FROM projects WHERE name='GapArchived'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ARCHIVED", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM execution_logs").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    // ------------------------------------------------------------------
    // PATH 3: fresh install parity — the schema Room CREATES for a new
    // device must itself validate (guards the @ColumnInfo defaultValue
    // annotations added by GAP-01 against typos: a fresh open runs the
    // same TableInfo comparison via checkIdentity).
    // ------------------------------------------------------------------

    @Test
    fun `fresh v16 database opens and validates cleanly`() {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "gap01-fresh.db")
            .addMigrations(*allMigrations())
            .allowMainThreadQueries()
            .build()
        val db = room.openHelper.writableDatabase
        try {
            db.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(17, c.getInt(0))
            }
            db.query("SELECT identity_hash FROM room_master_table").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getString(0).isNotBlank())
            }
        } finally {
            room.close()
        }
    }
}
