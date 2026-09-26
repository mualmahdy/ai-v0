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
 * CHAT CAPABILITIES (Task 2 §16, DB v18) — Migration17to18Test
 * ============================================================================
 *
 * Validates MIGRATION_17_TO_18 directly against a raw SQLite database
 * seeded with the v17-era surviving schema holding REAL data (same pattern
 * as Migration16to17Test — reflection reaches the private migration object
 * so the PRODUCTION code under test runs, not a copy):
 *
 *   1. `chat_turns` GAINS the `attachmentsJson TEXT NOT NULL DEFAULT '[]'`
 *      column (pure additive ALTER).
 *   2. ALL existing turn data (prompt/answer/aggregates) is untouched.
 *   3. Every legacy row's attachmentsJson starts as the honest '[]'
 *      ("no attachments" — no fabricated references).
 */
@RunWith(RobolectricTestRunner::class)
class Migration17to18Test {

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

    /** The v17-era `chat_turns` shape + one session and two real turns. */
    private fun seedV17SchemaAndData(db: SupportSQLiteDatabase) {
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
                createdAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO chat_sessions (sessionId, workspaceId, title, mode, createdAtEpochMs, lastActiveAtEpochMs) " +
                    "VALUES ('sess_seed', 'ws_seed', 'جلسة قديمة', 'QUICK_CHAT', 1, 2)"
        )
        db.execSQL(
            "INSERT INTO chat_turns (turnId, sessionId, prompt, answer, tokensConsumed, isSuccessful, eventCount, createdAtEpochMs) " +
                    "VALUES ('turn_a', 'sess_seed', 'السؤال الأول', 'الجواب الأول', 42, 1, 7, 100)"
        )
        db.execSQL(
            "INSERT INTO chat_turns (turnId, sessionId, prompt, answer, tokensConsumed, isSuccessful, eventCount, createdAtEpochMs) " +
                    "VALUES ('turn_b', 'sess_seed', 'السؤال الثاني', 'الجواب الثاني', 17, 0, 3, 200)"
        )
    }

    private fun runMigration17to18(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_17_TO_18")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    @Test
    fun `the attachmentsJson column is added with the honest empty default`() {
        createRawDb().use { db ->
            seedV17SchemaAndData(db)
            runMigration17to18(db)
            db.query("SELECT attachmentsJson FROM chat_turns ORDER BY turnId").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
                assertTrue(cursor.moveToNext())
                assertEquals("[]", cursor.getString(0))
            }
        }
    }

    @Test
    fun `all existing turn data survives untouched`() {
        createRawDb().use { db ->
            seedV17SchemaAndData(db)
            runMigration17to18(db)
            db.query(
                "SELECT prompt, answer, tokensConsumed, isSuccessful, eventCount, createdAtEpochMs " +
                        "FROM chat_turns WHERE turnId = 'turn_a'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("السؤال الأول", cursor.getString(0))
                assertEquals("الجواب الأول", cursor.getString(1))
                assertEquals(42, cursor.getInt(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(7, cursor.getInt(4))
                assertEquals(100L, cursor.getLong(5))
            }
            db.query("SELECT COUNT(*) FROM chat_turns WHERE sessionId = 'sess_seed'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `the schema version is current and the migration is registered in the chain`() {
        // FUNCTIONAL CLOSURE (Phase 1 §9): the version moved on to 19 — this
        // test pins the 17→18 STEP's registration, not the tip.
        assertEquals(20, AppDatabase.SCHEMA_VERSION)
        val allField = AppDatabase::class.java.getDeclaredField("ALL_MIGRATIONS")
        allField.isAccessible = true
        val migrations = allField.get(null) as Array<androidx.room.migration.Migration>
        assertTrue(
            "the v17→v18 step must be part of the upgrade chain",
            migrations.any { it.startVersion == 17 && it.endVersion == 18 }
        )
        // The chain is CONTINUOUS from v1 — an upgrade never crashes with
        // "migration not found".
        val ranges = migrations.map { it.startVersion to it.endVersion }
        (1 until 19).forEach { version ->
            assertTrue("missing migration step $version→${version + 1}", ranges.contains(version to version + 1))
        }
    }
}
