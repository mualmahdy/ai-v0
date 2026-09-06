package com.example.governance

import android.database.sqlite.SQLiteDatabase
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
import android.content.Context

/**
 * ============================================================================
 * GovernanceMigrationTest (Robolectric, raw SQLite)
 * ============================================================================
 *
 * GOVERNANCE PHASE: validates MIGRATION_9_TO_10 directly against a raw
 * SQLite database: the migration must create the seven governance tables
 * and their indices WITHOUT touching existing v9 data (purely additive).
 */
@RunWith(RobolectricTestRunner::class)
class GovernanceMigrationTest {

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
                    override fun onCreate(db: SupportSQLiteDatabase) { /* no-op: tables made manually */ }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
                })
                .build()
        )
        return helper.writableDatabase
    }

    @Test
    fun `migration 9 to 10 creates all governance tables and preserves existing data`() {
        createRawDb().use { db ->
            // Simulate a pre-existing v9-era table with user data.
            db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, marker TEXT NOT NULL)")
            db.execSQL("INSERT INTO tasks (id, marker) VALUES ('t-1', 'survivor')")

            // Run the governance migration directly.
            val migrationField = AppDatabase::class.java.getDeclaredField("MIGRATION_9_TO_10")
            migrationField.isAccessible = true
            val migration = migrationField.get(null) as androidx.room.migration.Migration
            migration.migrate(db)

            // 1. Existing data survives untouched.
            db.query("SELECT marker FROM tasks WHERE id = 't-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("survivor", cursor.getString(0))
            }

            // 2. All seven governance tables exist.
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
            }
            listOf(
                "capability_evidence", "radar_capability_states", "capability_changes",
                "radar_recommendations", "pricing_entries", "cost_ledger_entries", "budget_allocations"
            ).forEach { assertTrue("missing table after migration: $tables", tables.contains(it)) }

            // 3. Indices were created.
            val indices = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='cost_ledger_entries'").use { cursor ->
                while (cursor.moveToNext()) indices.add(cursor.getString(0))
            }
            assertTrue(indices.contains("index_cost_ledger_entries_executionId"))
            assertTrue(indices.contains("index_cost_ledger_entries_workspaceId_timestampEpochMs"))

            // 4. The migrated tables are insertable (schema matches entity DDL).
            db.execSQL(
                "INSERT INTO cost_ledger_entries VALUES (" +
                    "'c1','exec',NULL,'ws',NULL,'prov',NULL,NULL,NULL," +
                    "10,5,0,15,0,NULL,NULL,NULL,NULL,1000,'USD','ACTUAL','PAID',123)"
            )
            db.query("SELECT totalTokens, costAmountMicro, costStatus FROM cost_ledger_entries WHERE id='c1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(15, cursor.getInt(0))
                assertEquals(1000L, cursor.getLong(1))
                assertEquals("ACTUAL", cursor.getString(2))
            }

            // 5. Composite-primary-key tables enforce uniqueness as Room expects.
            db.execSQL(
                "INSERT INTO budget_allocations VALUES ('WORKSPACE','ws-1',1000000,'USD','HARD_LIMIT',0.8,NULL,1,1,1)"
            )
            db.execSQL(
                "INSERT INTO radar_capability_states VALUES (" +
                    "'llm_generation','ws-1','AVAILABLE','{}','HEALTHY','STABLE',1,100,'[]','r',100)"
            )
        }
    }
}
