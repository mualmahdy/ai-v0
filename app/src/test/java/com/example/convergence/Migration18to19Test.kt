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
 * FUNCTIONAL CLOSURE (Phase 1 §9/§10, DB v19) — Migration18to19Test
 * ============================================================================
 *
 * Validates MIGRATION_18_TO_19 directly against a raw SQLite database seeded
 * with the v18-era surviving schema holding REAL data (same pattern as
 * Migration17to18Test — reflection reaches the private migration object so
 * the PRODUCTION code under test runs, not a copy):
 *
 *   1. `chat_turns` GAINS the `sourcesJson TEXT NOT NULL DEFAULT '[]'`
 *      column (pure additive ALTER — legacy turns decode as "no sources").
 *   2. The NEW `chat_timeline_events` table is created with its indices
 *      (capability results + approval blocks become durable conversation
 *      history — they previously vanished on every session reopen).
 *   3. ALL existing turn data (prompt/answer/attachments) is untouched.
 */
@RunWith(RobolectricTestRunner::class)
class Migration18to19Test {

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

    /** The v18-era `chat_turns` shape + one session and one real turn. */
    private fun seedV18SchemaAndData(db: SupportSQLiteDatabase) {
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
                lastActiveAtEpochMs INTEGER NOT NULL,
                projectId INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_turns (
                turnId TEXT NOT NULL PRIMARY KEY,
                sessionId TEXT NOT NULL,
                prompt TEXT NOT NULL,
                answer TEXT NOT NULL,
                agentName TEXT,
                agentRole TEXT,
                modelResourceId TEXT,
                tokensConsumed INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL DEFAULT 0,
                isSuccessful INTEGER NOT NULL DEFAULT 1,
                eventCount INTEGER NOT NULL DEFAULT 0,
                createdAtEpochMs INTEGER NOT NULL,
                attachmentsJson TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO chat_sessions (sessionId, workspaceId, title, mode, createdAtEpochMs, lastActiveAtEpochMs) " +
                    "VALUES ('sess_seed19', 'ws_seed19', 'جلسة الإصدار 18', 'QUICK_CHAT', 1, 2)"
        )
        db.execSQL(
            "INSERT INTO chat_turns (turnId, sessionId, prompt, answer, tokensConsumed, isSuccessful, eventCount, createdAtEpochMs, attachmentsJson) " +
                    "VALUES ('turn_18a', 'sess_seed19', 'السؤال القديم', 'الجواب القديم', 42, 1, 7, 100, '[]')"
        )
    }

    private fun runMigration18to19(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_18_TO_19")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    @Test
    fun `the sourcesJson column is added with the honest empty default`() {
        createRawDb().use { db ->
            seedV18SchemaAndData(db)
            runMigration18to19(db)
            db.query("SELECT sourcesJson FROM chat_turns WHERE turnId = 'turn_18a'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
            }
        }
    }

    @Test
    fun `the chat_timeline_events table is created with its indices`() {
        createRawDb().use { db ->
            seedV18SchemaAndData(db)
            runMigration18to19(db)

            // The table exists with the full declared shape.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='chat_timeline_events'"
            ).use { cursor ->
                assertTrue("chat_timeline_events must exist after the migration", cursor.moveToFirst())
            }
            // A real row round-trips through the new columns.
            db.execSQL(
                "INSERT INTO chat_timeline_events (eventId, sessionId, kind, title, summary, sourcesJson, isSuccessful, isDegraded, createdAtEpochMs, approvalId, approvalState) " +
                        "VALUES ('evt_1', 'sess_seed19', 'CAPABILITY_RESULT', 'بحث ذكي', 'تم', '[]', 1, 0, 500, NULL, NULL)"
            )
            db.query(
                "SELECT kind, title, isSuccessful, createdAtEpochMs FROM chat_timeline_events WHERE eventId = 'evt_1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CAPABILITY_RESULT", cursor.getString(0))
                assertEquals("بحث ذكي", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(500L, cursor.getLong(3))
            }
            // Both indices exist (session-scoped + timestamp-ordered lookups).
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='chat_timeline_events'"
            ).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                assertTrue(
                    "the sessionId index must exist (found: $names)",
                    names.any { it.contains("chat_timeline_events_sessionId") }
                )
                assertTrue(
                    "the (sessionId, createdAtEpochMs) index must exist (found: $names)",
                    names.any { it.contains("chat_timeline_events_sessionId_createdAtEpochMs") }
                )
            }
        }
    }

    @Test
    fun `all existing turn data and attachments survive untouched`() {
        createRawDb().use { db ->
            seedV18SchemaAndData(db)
            runMigration18to19(db)
            db.query(
                "SELECT prompt, answer, tokensConsumed, isSuccessful, eventCount, createdAtEpochMs, attachmentsJson " +
                        "FROM chat_turns WHERE turnId = 'turn_18a'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("السؤال القديم", cursor.getString(0))
                assertEquals("الجواب القديم", cursor.getString(1))
                assertEquals(42, cursor.getInt(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(7, cursor.getInt(4))
                assertEquals(100L, cursor.getLong(5))
                assertEquals("[]", cursor.getString(6))
            }
        }
    }

    @Test
    fun `the schema version is 19 and the migration is registered in the chain`() {
        assertEquals(20, AppDatabase.SCHEMA_VERSION)
        val allField = AppDatabase::class.java.getDeclaredField("ALL_MIGRATIONS")
        allField.isAccessible = true
        val migrations = allField.get(null) as Array<androidx.room.migration.Migration>
        assertTrue(
            "the v18→v19 step must be part of the upgrade chain",
            migrations.any { it.startVersion == 18 && it.endVersion == 19 }
        )
        // The chain is CONTINUOUS from v1 — an upgrade never crashes with
        // "migration not found".
        val ranges = migrations.map { it.startVersion to it.endVersion }
        (1 until 19).forEach { version ->
            assertTrue("missing migration step $version→${version + 1}", ranges.contains(version to version + 1))
        }
    }
}
