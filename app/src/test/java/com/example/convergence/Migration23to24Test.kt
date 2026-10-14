package com.example.convergence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 1 Day 8-9 (DB v24) — Migration23to24Test
 * ============================================================================
 *
 * Validates the budget_allocations NULLABILITY FIX (finding NF-2 / test
 * ج-4): the v10 NOT NULL column becomes NULLABLE so an UNKNOWN allocation
 * amount stays unknown in storage instead of being coerced to 0.
 *
 * Why this is a SEPARATE migration: the approved 22→23 plan forbids
 * touching existing tables, while the approved Day 8-9 sequence requires
 * this fix — the isolated rebuild honors both (recorded as interpretation
 * I-6 in the stage register).
 *
 * Data preservation contract: every existing row is copied VERBATIM; a
 * stored 0 remains the 0 a previous build wrote (this fix stops FUTURE
 * corruption, it does not rewrite history).
 */
@RunWith(RobolectricTestRunner::class)
class Migration23to24Test {

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

    private fun runMigration23to24(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_23_TO_24")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    /** The v10/v22-era budget_allocations shape with three real rows. */
    private fun seedV23Shape(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS budget_allocations (
                scopeType TEXT NOT NULL,
                scopeId TEXT NOT NULL,
                allocatedAmountMicro INTEGER NOT NULL,
                currency TEXT NOT NULL,
                policyActionsCsv TEXT NOT NULL,
                warnThresholdRatio REAL NOT NULL,
                policyNote TEXT,
                isActive INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(scopeType, scopeId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_allocations_scopeType ON budget_allocations(scopeType)")
        db.execSQL("INSERT INTO budget_allocations VALUES ('WORKSPACE','ws-42',750000,'USD','HARD_LIMIT',0.8,'note',1,10,20)")
        db.execSQL("INSERT INTO budget_allocations VALUES ('AGENT','a-1',0,'USD','SOFT_LIMIT',0.5,NULL,1,10,20)")
        db.execSQL("INSERT INTO budget_allocations VALUES ('TASK','t-1',123,'EUR','HARD_LIMIT',0.9,NULL,0,10,20)")
    }

    @Test
    fun `the nullability fix preserves every existing row verbatim`() {
        createRawDb().use { db ->
            seedV23Shape(db)
            runMigration23to24(db)
            db.query(
                "SELECT scopeType, scopeId, allocatedAmountMicro, currency, policyNote, isActive FROM budget_allocations ORDER BY scopeType"
            ).use { cursor ->
                val rows = mutableListOf<List<Any>>()
                while (cursor.moveToNext()) {
                    rows += listOf(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getInt(5))
                }
                assertEquals(
                    listOf(
                        listOf("AGENT", "a-1", 0L, "USD", null, 1),
                        listOf("TASK", "t-1", 123L, "EUR", null, 0),
                        listOf("WORKSPACE", "ws-42", 750000L, "USD", "note", 1)
                    ),
                    rows
                )
            }
        }
    }

    @Test
    fun `an UNKNOWN allocation can now be stored as NULL (j-4 invariant)`() {
        createRawDb().use { db ->
            seedV23Shape(db)
            runMigration23to24(db)
            // Before the fix this INSERT failed (NOT NULL); now NULL is the
            // honest representation of "spending authority UNKNOWN".
            db.execSQL("INSERT INTO budget_allocations VALUES ('PROVIDER','prov-9',NULL,'USD','HARD_LIMIT',0.8,NULL,1,10,20)")
            db.query("SELECT allocatedAmountMicro FROM budget_allocations WHERE scopeId = 'prov-9'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("expected SQL NULL", cursor.isNull(0))
            }
        }
    }

    @Test
    fun `primary key and scopeType index survive the rebuild`() {
        createRawDb().use { db ->
            seedV23Shape(db)
            runMigration23to24(db)
            try {
                db.execSQL("INSERT INTO budget_allocations VALUES ('TASK','t-1',7,'USD','HARD_LIMIT',0.8,NULL,1,10,20)")
                fail("duplicate PK must be rejected after the rebuild")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                // PK still enforced
            }
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_budget_allocations_scopeType'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
