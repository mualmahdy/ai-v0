package com.example.convergence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * P0 CONVERGENCE — Migration11to12Test (Robolectric, raw SQLite)
 * ============================================================================
 *
 * Validates MIGRATION_11_TO_12 directly against a raw SQLite database
 * (same pattern as GovernanceMigrationTest), seeded with a COMPLETE v11
 * schema (a real upgrading device has every table the migration touches):
 *
 *   1. `projects` gains `workspaceId`, backfilled from the
 *      workspaces.lastActiveProjectId bridge (explicit ownership).
 *   2. The legacy implicit project 1L is MATERIALIZED as a real owned row
 *      when (and only when) a workspace still references it.
 *   3. The dead `sessions` table is dropped.
 *   4. `knowledge_documents` is rebuilt WITHOUT the dead projectId column
 *      and WITH mimeType — data preserved.
 *   5. `document_chunks` gains `metadataJson`.
 *   6. `mdp_q_values` is rebuilt with the `resourceKey` primary-key axis —
 *      legacy rows map to 'R:none', data preserved.
 */
@RunWith(RobolectricTestRunner::class)
class Migration11to12Test {

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

    /** A COMPLETE v11-era schema (every table MIGRATION_11_TO_12 touches). */
    private fun seedV11Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE projects (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, description TEXT, rootPath TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL,
                isArchived INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE workspaces (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
                networkPolicy TEXT NOT NULL, autonomyPolicy TEXT NOT NULL, settingsJson TEXT NOT NULL,
                isActive INTEGER NOT NULL, lastActiveProjectId INTEGER,
                createdAtEpochMs INTEGER NOT NULL, lastAccessedEpochMs INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE sessions (
                sessionId TEXT NOT NULL PRIMARY KEY, projectId INTEGER NOT NULL,
                title TEXT NOT NULL, assignedAgentId TEXT NOT NULL, activeModelId TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL,
                totalTokensConsumed INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE knowledge_documents (
                id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, projectId INTEGER,
                title TEXT NOT NULL, sourceUri TEXT NOT NULL, content TEXT NOT NULL,
                tagsJson TEXT NOT NULL, totalChunks INTEGER NOT NULL, totalTokensEstimated INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL,
                isArchived INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE document_chunks (
                id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, workspaceId TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL, text TEXT NOT NULL, tokenCount INTEGER NOT NULL,
                vectorDimension INTEGER NOT NULL, vectorJson TEXT NOT NULL, retrievalSource TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE mdp_q_values (
                regionKey TEXT NOT NULL, actionType TEXT NOT NULL, qValue REAL NOT NULL,
                visitCount INTEGER NOT NULL, successCount INTEGER NOT NULL,
                lastUpdatedEpochMs INTEGER NOT NULL, PRIMARY KEY(regionKey, actionType))"""
        )
    }

    private fun runMigration(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_11_TO_12")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) cols.add(cursor.getString(1))
        }
        return cols
    }

    @Test
    fun `projects gain workspaceId with ownership backfilled from the bridge`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            db.execSQL("INSERT INTO projects (name, description, rootPath, createdAtEpochMs, updatedAtEpochMs) VALUES ('P55','d','r',1,1)")
            val projectId = db.query("SELECT id FROM projects LIMIT 1").use { it.moveToFirst(); it.getLong(0) }
            db.execSQL("INSERT INTO workspaces VALUES ('ws_owner','n','d','HYBRID','SUPERVISED','{}',1,$projectId,1,1)")
            db.execSQL("INSERT INTO workspaces VALUES ('ws_none','n','d','HYBRID','SUPERVISED','{}',0,NULL,1,1)")

            runMigration(db)

            // Ownership backfilled: the project belongs to ws_owner.
            db.query("SELECT workspaceId FROM projects WHERE id = $projectId").use {
                assertTrue(it.moveToFirst())
                assertEquals("ws_owner", it.getString(0))
            }
            assertTrue(columnsOf(db, "projects").contains("workspaceId"))
        }
    }

    @Test
    fun `the legacy implicit 1L reference is materialized as a REAL owned row`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            // The default workspace references the IMPLICIT 1L, and no such row exists.
            db.execSQL("INSERT INTO workspaces VALUES ('default','n','d','HYBRID','SUPERVISED','{}',1,1,1,1)")

            runMigration(db)

            db.query("SELECT COUNT(*) FROM projects WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(
                    "The implicit 1L reference must become a REAL row (data repair)",
                    1L, it.getLong(0)
                )
            }
            db.query("SELECT workspaceId FROM projects WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("default", it.getString(0))
            }
        }
    }

    @Test
    fun `no phantom 1L row is created when nothing references it`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            // A workspace with its own project 5 — nothing references 1L.
            db.execSQL("INSERT INTO projects (id, name, description, rootPath, createdAtEpochMs, updatedAtEpochMs) VALUES (5,'own','d','r',1,1)")
            db.execSQL("INSERT INTO workspaces VALUES ('ws_x','n','d','HYBRID','SUPERVISED','{}',1,5,1,1)")

            runMigration(db)

            db.query("SELECT COUNT(*) FROM projects WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(
                    "The 1L materialization is CONDITIONAL — no phantom row when nothing references it",
                    0L, it.getLong(0)
                )
            }
        }
    }

    @Test
    fun `the dead sessions remnant is dropped`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            runMigration(db)
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
            }
            assertFalse("The dead sessions table must be dropped", tables.contains("sessions"))
        }
    }

    @Test
    fun `knowledge_documents lose the dead projectId and gain mimeType - data preserved`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            db.execSQL(
                "INSERT INTO knowledge_documents VALUES ('doc1','ws1',NULL,'العنوان','uri','المحتوى','[]',2,10,5,6,0)"
            )
            runMigration(db)

            val cols = columnsOf(db, "knowledge_documents")
            assertFalse("The dead projectId column must be gone", cols.contains("projectId"))
            assertTrue("mimeType must be persisted now", cols.contains("mimeType"))

            db.query("SELECT title, content, mimeType, totalChunks FROM knowledge_documents WHERE id='doc1'").use {
                assertTrue("Existing document data must survive the rebuild", it.moveToFirst())
                assertEquals("العنوان", it.getString(0))
                assertEquals("المحتوى", it.getString(1))
                assertEquals("text/markdown", it.getString(2))
                assertEquals(2, it.getInt(3))
            }
        }
    }

    @Test
    fun `document_chunks gain metadataJson`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            db.execSQL(
                "INSERT INTO document_chunks VALUES ('c1','doc1','ws1',0,'نص',3,3,'[1,2,3]','SEMANTIC',7)"
            )
            runMigration(db)

            assertTrue(columnsOf(db, "document_chunks").contains("metadataJson"))
            db.query("SELECT text, metadataJson FROM document_chunks WHERE id='c1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("نص", it.getString(0))
                assertEquals("{}", it.getString(1))
            }
        }
    }

    @Test
    fun `mdp_q_values rebuilt with the resourceKey primary-key axis - data preserved on R none`() {
        createRawDb().use { db ->
            seedV11Schema(db)
            db.execSQL(
                "INSERT INTO mdp_q_values VALUES ('GEN|M0|S0|T0|F0|P0|ON','SELECT_MODEL',0.7,12,9,99)"
            )
            runMigration(db)

            val cols = columnsOf(db, "mdp_q_values")
            assertTrue("The resourceKey axis must exist", cols.contains("resourceKey"))

            db.query("SELECT resourceKey, qValue, visitCount, successCount FROM mdp_q_values").use {
                assertTrue("Legacy learning data must survive the rebuild", it.moveToFirst())
                assertEquals(
                    "Legacy rows map to the resource-less axis (where resource-less actions keep learning)",
                    "R:none", it.getString(0)
                )
                assertEquals(0.7f, it.getFloat(1), 0.0001f)
                assertEquals(12, it.getInt(2))
                assertEquals(9, it.getInt(3))
            }

            // The new primary key includes the resource axis: distinct rows
            // for the same (region, action) on different resources.
            db.execSQL(
                "INSERT INTO mdp_q_values (regionKey, resourceKey, actionType, qValue, visitCount, successCount, lastUpdatedEpochMs) " +
                    "VALUES ('GEN|M0|S0|T0|F0|P0|ON','R:res_a','SELECT_MODEL',0.9,1,1,1)"
            )
            db.execSQL(
                "INSERT INTO mdp_q_values (regionKey, resourceKey, actionType, qValue, visitCount, successCount, lastUpdatedEpochMs) " +
                    "VALUES ('GEN|M0|S0|T0|F0|P0|ON','R:res_b','SELECT_MODEL',0.1,1,0,1)"
            )
            db.query("SELECT COUNT(*) FROM mdp_q_values").use {
                assertTrue(it.moveToFirst())
                assertEquals(3L, it.getLong(0))
            }
        }
    }
}
