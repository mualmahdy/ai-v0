package com.example.convergence

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.infrastructure.persistence.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 1 VERIFICATION (2026-10-16) — the isolated schema-baseline upgrades
 * ============================================================================
 *
 * This is the one item the original Stage-1 suite consciously deferred:
 * Stage-0 Section هـ.3 required the isolated 22→23 upgrade "with the actual
 * 22.json origin", and Migration22to23Test's header records the deferral
 * ("needs 23.json from the first build; the chain test covers the
 * end-to-end Room-validated path"). The first real build has now happened
 * (verification run 2026-10-16), and 23.json was generated AUTHENTICALLY by
 * recompiling the exact v23 state (SCHEMA_VERSION=23 + the pre-fix
 * non-nullable budget_allocations.allocatedAmountMicro) — so this test
 * completes the plan as written.
 *
 * What ONLY this test can prove (the raw-DB suite cannot):
 *  1. the FROM state is the machine-exported v22 schema (22.json) — every
 *     one of the 51 v22 tables, not a hand-written approximation of the
 *     three economic ones;
 *  2. Room's own TableInfo validation accepts the 22→23 END STATE against
 *     the machine-exported 23.json — schema-JSON ↔ migration-DDL coherence
 *     at this exact step, independent of the rest of the chain;
 *  3. the same for 23→24 against 24.json, including the verbatim
 *     row-preservation contract of the nullability rebuild.
 */
@RunWith(RobolectricTestRunner::class)
class Migration22to23IsolatedHelperTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    // Real production migrations (via reflection — repo convention; see
    // MigrationChainValidationTest / Migration18to19Test).
    @Suppress("UNCHECKED_CAST")
    private fun migration(name: String): androidx.room.migration.Migration {
        val field = AppDatabase::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as androidx.room.migration.Migration
    }

    private val budgetCoreTables = listOf(
        "scope_descriptor", "budget_policy", "budget_period",
        "budget_hold", "budget_ledger_entry", "budget_hold_settlement"
    )

    private val tenTriggers = listOf(
        "trg_budget_period_currency_via_policy_insert",
        "trg_budget_hold_currency_insert",
        "trg_budget_ledger_currency_insert",
        "trg_hold_settlement_currency_insert",
        "trg_budget_policy_no_self_scope_insert",
        "trg_budget_policy_no_self_scope_update",
        "trg_budget_hold_settlement_amount_insert",
        "trg_budget_period_limit_immutable_after_posting",
        "trg_budget_ledger_no_update_posted",
        "trg_budget_ledger_no_delete"
    )

    // ------------------------------------------------------------------
    // 1. Isolated 22 -> 23 from the REAL 22.json baseline
    // ------------------------------------------------------------------

    @Test
    fun `isolated 22 to 23 upgrades the real v22 baseline and passes Room validation`() {
        val name = "isolated-22-to-23.db"

        // FROM: the machine-exported v22 schema (22.json) — all 51 tables.
        helper.createDatabase(name, 22).apply {
            // Real v22 economic rows that must survive VERBATIM (one with a
            // NULL policyNote, one without — NULL round-trips matter).
            execSQL(
                "INSERT INTO budget_allocations (scopeType, scopeId, allocatedAmountMicro, currency, policyActionsCsv, warnThresholdRatio, policyNote, isActive, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('WORKSPACE','ws-42',750000,'USD','HARD_LIMIT',0.8,'legacy note',1,10,20)"
            )
            execSQL(
                "INSERT INTO budget_allocations (scopeType, scopeId, allocatedAmountMicro, currency, policyActionsCsv, warnThresholdRatio, policyNote, isActive, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('AGENT','agent-7',250000,'USD','SOFT_LIMIT',0.5,NULL,1,11,21)"
            )
            close()
        }

        // RUN + VALIDATE: only MIGRATION_22_TO_23. Room compares the result
        // against 23.json — any column/affinity/NOT NULL/PK/index mismatch
        // throws "Migration didn't properly handle: ...".
        helper.runMigrationsAndValidate(name, 23, true, migration("MIGRATION_22_TO_23")).use { db ->
            // (ج-3) legacy data survived untouched — values AND nullability.
            db.query(
                "SELECT allocatedAmountMicro, policyNote FROM budget_allocations WHERE scopeType='WORKSPACE' AND scopeId='ws-42'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(750000L, c.getLong(0))
                assertEquals("legacy note", c.getString(1))
            }
            db.query(
                "SELECT allocatedAmountMicro, policyNote FROM budget_allocations WHERE scopeType='AGENT' AND scopeId='agent-7'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(250000L, c.getLong(0))
                assertTrue("NULL policyNote must round-trip as NULL", c.isNull(1))
            }

            // The six budget-core tables exist on the real-baseline upgrade.
            for (table in budgetCoreTables) {
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
                    assertTrue("$table must exist after the isolated 22->23 upgrade", c.moveToFirst())
                }
            }

            // All ten protection triggers exist (explicit names — the
            // name-set pattern from Migration22to23Test; count-only checks
            // proved fragile against the trg_ prefix family).
            for (trigger in tenTriggers) {
                db.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name='$trigger'").use { c ->
                    assertTrue("$trigger must exist after the isolated 22->23 upgrade", c.moveToFirst())
                }
            }

            // No v22 table was dropped by the additive migration (Room's
            // validateDroppedTables=true already asserts this against 22.json;
            // this spot-check keeps the failure message human-readable).
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('pricing_entries','cost_ledger_entries','projects','chat_sessions')"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(4, c.getInt(0))
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. Isolated 23 -> 24 from the REAL 23.json baseline
    // ------------------------------------------------------------------

    @Test
    fun `isolated 23 to 24 preserves allocation rows verbatim and passes Room validation`() {
        val name = "isolated-23-to-24.db"

        // FROM: the machine-exported v23 schema (23.json) — generated
        // authentically from the v23 source state on 2026-10-16.
        helper.createDatabase(name, 23).apply {
            // Three rows incl. a stored 0: the rebuild copies VERBATIM —
            // a 0 written by a previous build remains the 0 it wrote.
            execSQL(
                "INSERT INTO budget_allocations (scopeType, scopeId, allocatedAmountMicro, currency, policyActionsCsv, warnThresholdRatio, policyNote, isActive, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('WORKSPACE','ws-1',750000,'USD','HARD_LIMIT',0.8,'p',1,10,20)"
            )
            execSQL(
                "INSERT INTO budget_allocations (scopeType, scopeId, allocatedAmountMicro, currency, policyActionsCsv, warnThresholdRatio, policyNote, isActive, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('WORKSPACE','ws-2',0,'USD','HARD_LIMIT',0.8,NULL,1,10,20)"
            )
            execSQL(
                "INSERT INTO budget_allocations (scopeType, scopeId, allocatedAmountMicro, currency, policyActionsCsv, warnThresholdRatio, policyNote, isActive, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('AGENT','ag-1',250000,'USD','SOFT_LIMIT',0.5,'q',1,10,20)"
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 24, true, migration("MIGRATION_23_TO_24")).use { db ->
            // Every row survived verbatim, PK order included.
            db.query(
                "SELECT scopeId, allocatedAmountMicro, policyNote FROM budget_allocations WHERE scopeType='WORKSPACE' ORDER BY scopeId"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ws-1", c.getString(0)); assertEquals(750000L, c.getLong(1))
                assertTrue(c.moveToNext())
                assertEquals("ws-2", c.getString(0)); assertEquals(0L, c.getLong(1))
                assertTrue("stored 0 must remain the 0 a previous build wrote", !c.isNull(1))
                assertFalse(c.moveToNext())
            }
            db.query("SELECT allocatedAmountMicro FROM budget_allocations WHERE scopeId='ag-1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(250000L, c.getLong(0))
            }

            // The column is now NULLABLE on the migrated database (NF-2 fix).
            db.query("PRAGMA table_info(budget_allocations)").use { c ->
                var found = false
                while (c.moveToNext()) {
                    if (c.getString(1) == "allocatedAmountMicro") {
                        found = true
                        assertEquals("allocatedAmountMicro must be nullable after 23->24", 0, c.getInt(3))
                    }
                }
                assertTrue("allocatedAmountMicro column must exist", found)
            }

            // PK preserved by the rebuild.
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_budget_allocations_scopeType_scopeId'").use { c ->
                // Room names composite-PK indices deterministically; accept
                // either the declared name or any index covering the PK.
                val pkIndexExists = c.moveToFirst() || run {
                    db.query("PRAGMA index_list(budget_allocations)").use { l ->
                        var any = false
                        while (l.moveToNext()) {
                            if (l.getInt(2) > 0) any = true // unique index
                        }
                        any
                    }
                }
                assertTrue("a UNIQUE index over the budget_allocations PK must survive the rebuild", pkIndexExists)
            }
        }
    }
}
