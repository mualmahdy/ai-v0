package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
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
 * STAGE 3 — MIGRATION_25_TO_26 (reconciliation + provider billing +
 * currency conversion + entitlements)
 * ============================================================================
 * Mirrors the Stage-1/2 discipline:
 *  - the FROM state is the machine-exported v25 schema (25.json — the export
 *    the Stage-2 delivery had to defer, backfilled by the Stage-3
 *    verification build exactly as 23.json was in Stage 1);
 *  - the upgrade runs the REAL production migration (reflection — repo
 *    convention) and Room's own TableInfo validation accepts the end state
 *    on open (the 26.json export ships with this commit);
 *  - behavior is asserted with raw SQL: 7 new tables, 9 named indices, the
 *    seventeen G03/G04/ADR-B2 triggers, the G04 finality machine
 *    (frozen-after-draft, unique FINALIZED, draft guards) and v25 data
 *    survival.
 */
@RunWith(RobolectricTestRunner::class)
class Migration25to26Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Suppress("UNCHECKED_CAST")
    private fun migration(name: String): androidx.room.migration.Migration {
        val field = AppDatabase::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as androidx.room.migration.Migration
    }

    private val stage3Tables = listOf(
        "currency_conversion_rule", "provider_billing_record", "reconciliation_record",
        "reconciliation_inclusion", "reconciliation_adjustment",
        "entitlement_record", "entitlement_consumption"
    )

    private val seventeenTriggers = listOf(
        "trg_currency_rule_immutable_when_used",
        "trg_no_update_billing_record", "trg_no_delete_billing_record",
        "trg_reconciliation_conversion_rule_insert",
        "trg_reconciliation_finality_inclusion_guard",
        "trg_reconciliation_finalized_unique_insert", "trg_reconciliation_finalized_unique_update",
        "trg_reconciliation_no_update_frozen", "trg_reconciliation_no_delete",
        "trg_no_update_reconciliation_inclusion", "trg_no_delete_reconciliation_inclusion",
        "trg_reconciliation_inclusion_draft_guard",
        "trg_no_update_reconciliation_adjustment", "trg_no_delete_reconciliation_adjustment",
        "trg_reconciliation_adjustment_draft_guard",
        "trg_no_update_entitlement_consumption", "trg_no_delete_entitlement_consumption"
    )

    @Test
    fun `25 to 26 creates the Stage-3 schema from the real v25 baseline and Room validates it`() {
        val dbName = "stage3-mig-25to26.db"
        helper.createDatabase(dbName, 25).use { db ->
            // A fresh 25.json database carries the SCHEMA only — the unit/
            // metric seeds live in the MIGRATION path (24->25) and the
            // fresh-install onCreate callback, so they are legitimately
            // absent here (verified in Migration24to25Test instead).
            db.execSQL(
                "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                    "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                    "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                    "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                    "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                    "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                    "('sd-s3','WORKSPACE','v1|WORKSPACE|ws-s3','h-s3','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-s3',0)"
            )
            db.execSQL(
                "INSERT INTO pricing_profile VALUES ('pp-s3','prov-s3','MODEL:m:-','USD','sd-s3','ACTIVE',0,0)"
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(migration("MIGRATION_25_TO_26"), migration("MIGRATION_26_TO_27"), migration("MIGRATION_27_TO_28")) // v28: the entity set moved on — all steps run (the 22to23 precedent)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase // open -> migrate -> Room validates

            db.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(28, c.getInt(0))
            }

            // ---- 7 Stage-3 tables ----
            for (table in stage3Tables) {
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'"
                ).use { c -> assertTrue("table $table must exist", c.moveToFirst()) }
            }

            // ---- named indices (incl. the change-7 regular form) ----
            for (index in listOf(
                "ix_currency_conversion_pair", "ix_provider_billing_period_scope",
                "ix_provider_billing_external_id", "ix_reconciliation_billing_scope_period",
                "ux_reconciliation_inclusion_line", "ix_reconciliation_adjustment_reconciliation",
                "ix_entitlement_record_scope", "ux_entitlement_consumption_idem",
                "ix_entitlement_consumption_usage_record"
            )) {
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='$index'"
                ).use { c -> assertTrue("index $index must exist", c.moveToFirst()) }
            }

            // ---- 17 triggers ----
            for (trigger in seventeenTriggers) {
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='trigger' AND name='$trigger'"
                ).use { c -> assertTrue("trigger $trigger must exist", c.moveToFirst()) }
            }

            // ---- v25 data survived verbatim ----
            db.query("SELECT profile_status FROM pricing_profile WHERE pricing_profile_id='pp-s3'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("ACTIVE", c.getString(0))
            }
            // the 25->26 step plants no seeds and fabricates nothing:
            // the empty reference tables stay empty (fresh installs get
            // their seeds from the v25 onCreate path, not from this step).
            db.query("SELECT COUNT(*) FROM unit_definition").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }

            // ---- the G04 finality machine is behavioral ----
            db.execSQL(
                "INSERT INTO provider_billing_record (provider_billing_record_id,provider_id," +
                    "external_billing_id,billing_record_type,record_granularity,period_start_epoch_ms," +
                    "period_end_epoch_ms,scope_id,primary_amount_type,primary_amount_micro_units,currency," +
                    "amount_consistency_status,source_authority,recorded_at_epoch_ms,billing_status) VALUES " +
                    "('pbr-m','prov-s3','INV-M','INVOICE','MONTHLY',0,10000,'sd-s3','GROSS_PAYABLE',100,'USD'," +
                    "'UNKNOWN','test',0,'IMPORTED')"
            )
            val insertRec = "INSERT INTO reconciliation_record (reconciliation_id,provider_billing_record_id," +
                "scope_id,period_start_epoch_ms,period_end_epoch_ms,provider_currency,local_currency," +
                "comparison_currency,conversion_rule_id,coverage_status,reconciliation_status,finality_status," +
                "rule_version,recorded_at_epoch_ms) VALUES ('rec-m','pbr-m','sd-s3',0,10000,'USD','USD','USD'," +
                "NULL,'COMPLETE','MATCHED','DRAFT','v',0)"
            db.execSQL(insertRec)
            // Stage-4 (I-35): the sign-off now requires PRIMARY evidence —
            // the v27 guard lives on this table. Link it before the UPDATE.
            db.execSQL(
                "INSERT INTO evidence (evidence_id, evidence_type, payload_digest, hash_algorithm, " +
                    "payload_storage_mode, verification_available, redaction_status, evidence_status, " +
                    "security_classification, recorded_at_epoch_ms) VALUES " +
                    "('ev-s3x','PROVIDER_INVOICE','ds3','SHA-256','DIGEST_ONLY',0,'NONE','ACTIVE','INTERNAL',0)"
            )
            db.execSQL(
                "INSERT INTO reconciliation_evidence (reconciliation_id, evidence_id, evidence_role, " +
                    "confidence_state, is_primary, created_at_epoch_ms) VALUES " +
                    "('rec-m','ev-s3x','PRIMARY','HIGH',1,0)"
            )
            // legal: DRAFT -> FINALIZED
            db.execSQL("UPDATE reconciliation_record SET finality_status='FINALIZED', reconciled_at_epoch_ms=1 WHERE reconciliation_id='rec-m'")
            // frozen: any further UPDATE rejected
            try {
                db.execSQL("UPDATE reconciliation_record SET reason_code='x' WHERE reconciliation_id='rec-m'")
                assertFalse("FINALIZED reconciliation UPDATE must be rejected", true)
            } catch (expected: android.database.SQLException) {
                // trigger fired — correct
            }
            try {
                db.execSQL("DELETE FROM reconciliation_record")
                assertFalse("reconciliation DELETE must be rejected", true)
            } catch (expected: android.database.SQLException) {
                // trigger fired — correct
            }
            // billing immutability (FOR EACH ROW — the table has a row)
            try {
                db.execSQL("UPDATE provider_billing_record SET billing_status='X'")
                assertFalse("billing UPDATE must be rejected", true)
            } catch (expected: android.database.SQLException) {
                // trigger fired — correct
            }
        } finally {
            room.close()
        }
    }
}
