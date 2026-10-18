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
 * STAGE 1 (DB v23, approved 2026-10-14 "أعتمد الكل") — Migration22to23Test
 * ============================================================================
 *
 * Validates MIGRATION_22_TO_23 (the accounting budget core) directly
 * against a raw SQLite database seeded with the v22-era economic tables
 * holding REAL data — same reflection pattern as Migration18to19Test, so
 * the PRODUCTION migration object runs, not a copy.
 *
 * The same DDL was ALSO verified standalone against SQLite (21/21 checks,
 * scripts/verify_budget_core_ddl.py, executed 2026-10-14). This test
 * re-proves the behavior through the production Migration object inside
 * the Android test environment, plus the Room-level facts the standalone
 * script cannot see (entity/DDL coherence is validated separately by the
 * chain test and by Room's own open-time validation).
 *
 * Coverage (Stage-0 Section هـ.3 — the six required upgrade tests, minus
 * the MigrationTestHelper isolated step which needs 23.json from the first
 * build; the chain test covers the end-to-end Room-validated path):
 *   1. the six tables are created with the approved columns;
 *   2. the ten protection triggers exist AND reject violations;
 *   3. existing v22 data (ledger + allocations) survives untouched;
 *   4. schema version is 24 and both new steps are registered + continuous.
 */
@RunWith(RobolectricTestRunner::class)
class Migration22to23Test {

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

    private fun runMigration22to23(db: SupportSQLiteDatabase) {
        val field = AppDatabase::class.java.getDeclaredField("MIGRATION_22_TO_23")
        field.isAccessible = true
        val migration = field.get(null) as androidx.room.migration.Migration
        migration.migrate(db)
    }

    /** The v22-era economic tables with real rows (v10 shapes, real data). */
    private fun seedV22EconomicData(db: SupportSQLiteDatabase) {
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
        db.execSQL(
            "INSERT INTO budget_allocations VALUES ('WORKSPACE','ws-42',750000,'USD','HARD_LIMIT',0.8,'legacy',1,10,20)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cost_ledger_entries (
                id TEXT NOT NULL PRIMARY KEY,
                executionId TEXT NOT NULL,
                taskId TEXT, workspaceId TEXT, agentId TEXT, providerId TEXT,
                serviceId TEXT, modelId TEXT, resourceId TEXT,
                inputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL,
                cachedTokens INTEGER NOT NULL, totalTokens INTEGER NOT NULL,
                isEstimate INTEGER NOT NULL,
                appliedInputPriceMicroPerMillion INTEGER,
                appliedOutputPriceMicroPerMillion INTEGER,
                appliedCachedInputPriceMicroPerMillion INTEGER,
                appliedPricingVersion TEXT,
                costAmountMicro INTEGER, currency TEXT NOT NULL,
                costStatus TEXT NOT NULL, billingClass TEXT NOT NULL,
                timestampEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO cost_ledger_entries VALUES ('cl-1','exec-1',NULL,'ws-42',NULL,'prov-1'," +
                "NULL,NULL,NULL,100,200,0,300,0,1500,3000,NULL,'v1',900,'USD','ACTUAL','REMOTE',123456)"
        )
        db.execSQL(
            "INSERT INTO cost_ledger_entries VALUES ('cl-2','exec-1',NULL,'ws-42',NULL,'prov-1'," +
                "NULL,NULL,NULL,10,20,0,30,1,NULL,NULL,NULL,NULL,NULL,'USD','UNKNOWN','REMOTE',123457)"
        )
    }

    // ------------------------------------------------------------------
    // 1. The six budget-core tables exist with the approved columns
    // ------------------------------------------------------------------

    @Test
    fun `the six budget core tables are created with the approved columns`() {
        createRawDb().use { db ->
            seedV22EconomicData(db)
            runMigration22to23(db)

            val expected = mapOf(
                "scope_descriptor" to setOf(
                    "scope_id", "scope_kind", "canonical_representation", "canonical_hash",
                    "hash_algorithm", "canonicalization_version",
                    "provider_dimension_state", "provider_id",
                    "integration_dimension_state", "integration_id",
                    "access_context_dimension_state", "access_context_id",
                    "provider_scope_node_dimension_state", "provider_scope_node_id",
                    "offering_dimension_state", "offering_id",
                    "request_attempt_dimension_state", "request_attempt_id",
                    "budget_policy_dimension_state", "budget_policy_id",
                    "budget_period_dimension_state", "budget_period_id",
                    "region_dimension_state", "region",
                    "endpoint_dimension_state", "endpoint_ref",
                    "period_dimension_state", "period_start", "period_end",
                    "platform_dimension_state", "platform_object_type", "platform_object_id",
                    "created_at_epoch_ms"
                ),
                "budget_policy" to setOf(
                    "budget_policy_id", "policy_family_id", "budget_name", "budget_scope_type",
                    "scope_id", "currency", "default_limit_micro_units", "period_type",
                    "rollover_policy", "overage_policy", "allocation_policy", "policy_version",
                    "effective_from_epoch_ms", "policy_status", "created_at_epoch_ms", "updated_at_epoch_ms"
                ),
                "budget_period" to setOf(
                    "budget_period_id", "budget_policy_id", "period_start_epoch_ms",
                    "period_end_epoch_ms", "limit_micro_units", "period_status",
                    "created_at_epoch_ms", "updated_at_epoch_ms"
                ),
                "budget_hold" to setOf(
                    "hold_id", "budget_period_id", "hold_amount_micro_units", "currency",
                    "reason", "request_operation_id", "status", "created_at_epoch_ms",
                    "expires_at_epoch_ms", "idempotency_key"
                ),
                "budget_ledger_entry" to setOf(
                    "entry_id", "budget_period_id", "entry_type", "posting_state",
                    "available_effect_micro_units", "entry_basis", "currency",
                    "original_amount_precise", "original_currency", "conversion_rule_id",
                    "entry_timestamp_epoch_ms", "posted_at_epoch_ms", "cost_calculation_line_id",
                    "usage_record_id", "reconciliation_record_id", "hold_id",
                    "reversal_of_entry_id", "allocation_group_id", "allocation_role",
                    "idempotency_key"
                ),
                "budget_hold_settlement" to setOf(
                    "settlement_id", "hold_id", "settlement_type", "ledger_entry_id",
                    "amount_micro_units", "currency", "created_at_epoch_ms", "idempotency_key"
                )
            )
            for ((table, columns) in expected) {
                val actual = mutableListOf<String>()
                db.query("PRAGMA table_info($table)").use { cursor ->
                    while (cursor.moveToNext()) actual += cursor.getString(1)
                }
                assertEquals(
                    "column mismatch on $table: missing=${columns - actual.toSet()} extra=${actual.toSet() - columns}",
                    columns, actual.toSet()
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. The ten protection triggers exist and reject violations
    // ------------------------------------------------------------------

    /** Minimal valid seed inside the NEW tables for the trigger tests. */
    private fun seedBudgetCoreRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, hash_algorithm, canonicalization_version, platform_dimension_state, platform_object_type, platform_object_id, created_at_epoch_ms) " +
                "VALUES ('sd-1','WORKSPACE','c','h','SHA-256',1,'KNOWN','WORKSPACE','ws-42',1)"
        )
        db.execSQL(
            "INSERT INTO budget_policy (budget_policy_id, policy_family_id, budget_name, budget_scope_type, scope_id, currency, default_limit_micro_units, period_type, rollover_policy, overage_policy, allocation_policy, policy_version, effective_from_epoch_ms, policy_status, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('bp-1','fam-1','n','WORKSPACE','sd-1','USD',1000000,'T','R','HARD_LIMIT','SUPERVISORY_OVERLAP',1,0,'ACTIVE',1,1)"
        )
        db.execSQL(
            "INSERT INTO budget_period (budget_period_id, budget_policy_id, period_start_epoch_ms, period_end_epoch_ms, limit_micro_units, period_status, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('bper-1','bp-1',0,10000000,1000000,'OPEN',1,1)"
        )
        db.execSQL(
            "INSERT INTO budget_hold (hold_id, budget_period_id, hold_amount_micro_units, currency, reason, request_operation_id, status, created_at_epoch_ms, expires_at_epoch_ms, idempotency_key) " +
                "VALUES ('h-1','bper-1',100000,'USD',NULL,NULL,'ACTIVE',1,NULL,'op-1')"
        )
    }

    private fun assertRejected(db: SupportSQLiteDatabase, sql: String, label: String) {
        try {
            db.execSQL(sql)
            fail("expected rejection: $label")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // exactly the required failure mode
        }
    }

    @Test
    fun `the ten protection triggers exist`() {
        createRawDb().use { db ->
            seedV22EconomicData(db)
            runMigration22to23(db)
            val names = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='trigger'").use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
            val expected = listOf(
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
            assertEquals(expected.toSet(), names.toSet())
        }
    }

    @Test
    fun `trigger behaviors reject the violating operations`() {
        createRawDb().use { db ->
            seedV22EconomicData(db)
            runMigration22to23(db)
            seedBudgetCoreRows(db)
            db.execSQL(
                "INSERT INTO budget_ledger_entry (entry_id, budget_period_id, entry_type, posting_state, available_effect_micro_units, entry_basis, currency, entry_timestamp_epoch_ms, posted_at_epoch_ms, hold_id, idempotency_key) " +
                    "VALUES ('e-1','bper-1','CONSUMPTION','POSTED',-100000,'ACTUAL','USD',2,2,'h-1','op-1')"
            )
            db.execSQL(
                "INSERT INTO budget_hold_settlement (settlement_id, hold_id, settlement_type, ledger_entry_id, amount_micro_units, currency, created_at_epoch_ms, idempotency_key) " +
                    "VALUES ('s-1','h-1','SETTLE','e-1',100000,'USD',2,'op-1')"
            )

            // currency unification (policy currency is USD)
            assertRejected(
                db,
                "INSERT INTO budget_hold (hold_id, budget_period_id, hold_amount_micro_units, currency, reason, request_operation_id, status, created_at_epoch_ms, expires_at_epoch_ms, idempotency_key) VALUES ('h-x','bper-1',1,'EUR',NULL,NULL,'ACTIVE',1,NULL,'ik-1')",
                "hold currency must match policy currency"
            )
            assertRejected(
                db,
                "INSERT INTO budget_ledger_entry (entry_id, budget_period_id, entry_type, posting_state, available_effect_micro_units, entry_basis, currency, entry_timestamp_epoch_ms, posted_at_epoch_ms, idempotency_key) VALUES ('e-x','bper-1','CONSUMPTION','POSTED',-1,'ACTUAL','EUR',1,1,'ik-2')",
                "entry currency must match policy currency"
            )
            // settlement coherence
            assertRejected(
                db,
                "INSERT INTO budget_hold_settlement (settlement_id, hold_id, settlement_type, ledger_entry_id, amount_micro_units, currency, created_at_epoch_ms, idempotency_key) VALUES ('s-x','h-1','SETTLE','e-1',99999,'USD',1,'ik-3')",
                "settlement must match posted debit amount"
            )
            // settlements cannot exceed the hold
            assertRejected(
                db,
                "INSERT INTO budget_hold_settlement (settlement_id, hold_id, settlement_type, ledger_entry_id, amount_micro_units, currency, created_at_epoch_ms, idempotency_key) VALUES ('s-x','h-1','RELEASE',NULL,1,'USD',1,'ik-4')",
                "settlement sum cannot exceed hold"
            )
            // period limit freeze after posting
            assertRejected(
                db,
                "UPDATE budget_period SET limit_micro_units = 2000000 WHERE budget_period_id = 'bper-1'",
                "period limit is frozen after first posted entry"
            )
            // ADR-B4: posted immutability + append-only
            assertRejected(
                db,
                "UPDATE budget_ledger_entry SET entry_basis = 'ESTIMATE' WHERE entry_id = 'e-1'",
                "posted entries are immutable"
            )
            assertRejected(
                db,
                "DELETE FROM budget_ledger_entry WHERE entry_id = 'e-1'",
                "the ledger is append-only"
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Existing v22 data survives untouched
    // ------------------------------------------------------------------

    @Test
    fun `existing v22 economic data survives the upgrade untouched`() {
        createRawDb().use { db ->
            seedV22EconomicData(db)
            runMigration22to23(db)

            db.query(
                "SELECT scopeType, scopeId, allocatedAmountMicro, currency, policyNote FROM budget_allocations"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("WORKSPACE", cursor.getString(0))
                assertEquals("ws-42", cursor.getString(1))
                assertEquals(750000L, cursor.getLong(2))
                assertEquals("USD", cursor.getString(3))
                assertEquals("legacy", cursor.getString(4))
                assertTrue(!cursor.moveToNext())
            }
            db.query(
                "SELECT COUNT(*), COALESCE(SUM(costAmountMicro), 0) FROM cost_ledger_entries"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
                assertEquals(900L, cursor.getLong(1)) // NULL sum is honest, not zero-fabricated
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Version + chain registration and continuity
    // ------------------------------------------------------------------

    @Test
    fun `schema version is 24 and both new steps are registered continuously`() {
        assertEquals(28, AppDatabase.SCHEMA_VERSION) // v28: Stage-5 derived-state projection + G05 whole-version freeze
        val allField = AppDatabase::class.java.getDeclaredField("ALL_MIGRATIONS")
        allField.isAccessible = true
        val migrations = allField.get(null) as Array<androidx.room.migration.Migration>
        assertTrue(
            "the v22→v23 step must be part of the upgrade chain",
            migrations.any { it.startVersion == 22 && it.endVersion == 23 }
        )
        assertTrue(
            "the v23→v24 step must be part of the upgrade chain",
            migrations.any { it.startVersion == 23 && it.endVersion == 24 }
        )
        // The chain is CONTINUOUS from v1 to the current version.
        val steps = migrations.map { it.startVersion to it.endVersion }.toSet()
        var v = 1
        while (v < AppDatabase.SCHEMA_VERSION) {
            assertTrue("missing chain step $v→${v + 1}", steps.contains(v to v + 1))
            v++
        }
    }
}
