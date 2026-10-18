package com.example.convergence

import android.content.Context
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
 * STAGE 4 — MIGRATION_26_TO_27 (the evidence & sources network)
 * ============================================================================
 * Mirrors the Stage-1/2/3 discipline:
 *  - the FROM state is the machine-exported v26 schema (26.json — the export
 *    the Stage-3 verification build produced);
 *  - the upgrade runs the REAL production migration (reflection — repo
 *    convention) and Room's own TableInfo validation accepts the end state
 *    on open (the 27.json export ships with this commit: 108 entities);
 *  - behavior is asserted with raw SQL: 32 new tables, 31 named indices,
 *    the seventeen Stage-4 triggers (I-32/I-35 families), FK enforcement,
 *    the PRIMARY-evidence guard family, the cycle guard, the observation
 *    exactly-one-subject + value/knowledge CHECKs, and v26 data survival.
 */
@RunWith(RobolectricTestRunner::class)
class Migration26to27Test {

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

    private val stage4Tables = listOf(
        "provider", "provider_integration", "provider_scope_node",
        "provider_capability_manifest", "capability_statement",
        "credential_reference", "credential_binding", "access_context",
        "acquisition_run", "model", "provider_model_record",
        "model_identity_match", "model_offering", "request_operation",
        "provider_request_attempt", "freshness_policy", "metric_relationship",
        "resource_constraint", "observation", "evidence",
        "currency_conversion_rule_evidence", "entitlement_evidence",
        "metric_relationship_evidence", "model_identity_match_evidence",
        "observation_evidence", "pricing_component_evidence",
        "pricing_profile_evidence", "pricing_profile_version_evidence",
        "provider_billing_record_evidence", "reconciliation_evidence",
        "resource_constraint_evidence", "usage_record_evidence"
    )

    private val stage4Indices = listOf(
        "ux_provider_code", "ux_provider_integration_code", "ux_provider_integration_identity",
        "ux_provider_scope_node_identity", "ix_provider_scope_node_parent",
        "ux_capability_statement_key", "ux_credential_reference_store",
        "ux_credential_reference_identity", "ux_credential_binding_context",
        "ux_access_context_label", "ux_access_context_identity",
        "ix_access_context_integration", "ix_access_context_account_scope",
        "ix_access_context_billing_scope", "ix_acquisition_run_target_scope",
        "ix_acquisition_run_capability", "ux_provider_model_record_native",
        "ux_provider_model_record_identity", "ux_model_identity_match_identity",
        "ix_model_identity_match_canonical", "ux_provider_request_attempt_operation",
        "ux_provider_request_attempt_identity", "ix_provider_request_attempt_offering",
        "ix_metric_relationship_pair", "ix_metric_relationship_scope",
        "ix_resource_constraint_scope", "ix_evidence_digest", "ix_evidence_acquisition",
        "ix_observation_metric_time", "ix_observation_context_metric_time",
        "ix_observation_offering_metric_time"
    )

    private val seventeenTriggers = listOf(
        "trg_provider_scope_no_cycle_insert", "trg_provider_scope_no_cycle_update",
        "trg_manifest_provider_integration_insert", "trg_acquisition_provider_integration_insert",
        "trg_observation_subject_provider_insert",
        "trg_credential_binding_provider_match_insert", "trg_credential_binding_provider_match_update",
        "trg_attempt_context_offering_provider_insert", "trg_attempt_credential_context_insert",
        "trg_no_update_evidence", "trg_no_delete_evidence",
        "trg_no_update_observation", "trg_no_delete_observation",
        "trg_finalize_requires_primary_evidence", "trg_match_verified_requires_primary_evidence",
        "trg_metric_relation_active_requires_primary_evidence",
        "trg_resource_constraint_active_requires_primary_evidence"
    )

    @Test
    fun `26 to 27 migration is schema-valid and behaviorally sound`() {
        // 1) a REAL v26 database (machine-exported 26.json), with v26 witness
        //    data that must survive the upgrade untouched.
        helper.createDatabase(TEST_DB, 26).use { v26 ->
            v26.execSQL(
                "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                    "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                    "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                    "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                    "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                    "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                    "('sd-m4','WORKSPACE','v1|WORKSPACE|ws-m4','hm4','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-m4',0)"
            )
            v26.execSQL(
                "INSERT INTO currency_conversion_rule (currency_conversion_rule_id, from_currency, to_currency, " +
                    "rate_precise, rate_direction, effective_from_epoch_ms, effective_until_epoch_ms, " +
                    "source_authority, rounding_policy, created_at_epoch_ms) VALUES " +
                    "('fx-m4','USD','EUR','0.920000','TARGET_UNITS_PER_SOURCE_UNIT',0,null,'manual','fx:v1',0)"
            )
        }

        // 2) run the REAL production migration, then let MigrationTestHelper
        //    compare the result against the machine-exported 27.json
        //    (column/default/PK/FK/index identity — any mismatch throws).
        helper.runMigrationsAndValidate(TEST_DB, 27, true, migration("MIGRATION_26_TO_27")).use { db ->
            // runMigrationsAndValidate hands back the SupportSQLiteDatabase
            // itself (already open, already validated against 27.json).
            val sql = db

            // ---- 32 new tables ----
            for (table in stage4Tables) {
                sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
                    assertTrue("Stage-4 table $table must exist", c.moveToFirst())
                }
            }
            // ---- 31 named indices ----
            for (index in stage4Indices) {
                sql.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$index'").use { c ->
                    assertTrue("Stage-4 index $index must exist", c.moveToFirst())
                }
            }
            // ---- 17 triggers ----
            for (trigger in seventeenTriggers) {
                sql.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name='$trigger'").use { c ->
                    assertTrue("Stage-4 trigger $trigger must exist", c.moveToFirst())
                }
            }

            // ---- FK enforcement is live (orphan child rejected) ----
            sql.execSQL("PRAGMA foreign_keys = ON")
            try {
                sql.execSQL("INSERT INTO provider_integration (integration_id, provider_id, integration_code, integration_type, integration_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES ('int-x','prov-missing','code','API','ACTIVE',0,0)")
                assertTrue("orphan integration must be rejected by the FK", false)
            } catch (expected: android.database.SQLException) {
                // foreign key constraint — correct
            }

            // ---- the acquisition chain: provider -> integration -> run ----
            sql.execSQL("INSERT INTO provider (provider_id, provider_code, name, provider_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES ('prov-m4','openai','OpenAI','ACTIVE',0,0)")
            sql.execSQL("INSERT INTO provider_integration (integration_id, provider_id, integration_code, integration_type, integration_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES ('int-m4','prov-m4','main','API','ACTIVE',0,0)")
            sql.execSQL("INSERT INTO acquisition_run (acquisition_run_id, provider_id, integration_id, operation_type, started_at_epoch_ms, run_status) VALUES ('run-m4','prov-m4','int-m4','EVIDENCE_ACQUISITION',10,'SUCCEEDED')")
            // duplicate provider_code rejected by the named unique index
            try {
                sql.execSQL("INSERT INTO provider (provider_id, provider_code, name, provider_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES ('prov-m5','openai','Other','ACTIVE',0,0)")
                assertTrue("ux_provider_code must reject duplicates", false)
            } catch (expected: android.database.SQLException) {
                // unique index — correct
            }

            // ---- evidence: payload-mode CHECKs + immutability ----
            sql.execSQL(
                "INSERT INTO evidence (evidence_id, acquisition_run_id, evidence_type, payload_digest, hash_algorithm, " +
                    "payload_storage_mode, verification_available, redaction_status, evidence_status, security_classification, " +
                    "recorded_at_epoch_ms) VALUES ('ev-m4','run-m4','PROVIDER_RESPONSE','dm4','SHA-256','DIGEST_ONLY',0,'NONE','ACTIVE','INTERNAL',10)"
            )
            try { sql.execSQL("UPDATE evidence SET evidence_status='RETIRED' WHERE evidence_id='ev-m4'"); assertTrue("evidence is immutable", false) }
            catch (expected: android.database.SQLException) { }
            try { sql.execSQL("DELETE FROM evidence WHERE evidence_id='ev-m4'"); assertTrue("evidence is append-only", false) }
            catch (expected: android.database.SQLException) { }

            // ---- the I-35 guard family: PRIMARY evidence at authorization ----
            // reconciliation: a terminal DRAFT row cannot FINALIZE without a PRIMARY link.
            sql.execSQL(
                "INSERT INTO provider_billing_record (provider_billing_record_id, provider_id, billing_record_type, " +
                    "record_granularity, primary_amount_type, currency, amount_consistency_status, source_authority, " +
                    "recorded_at_epoch_ms, billing_status) VALUES " +
                    "('pbr-m4','prov-m4','INVOICE','MONTHLY','GROSS_PAYABLE','USD','CONSISTENT','manual',0,'POSTED')"
            )
            sql.execSQL(
                "INSERT INTO reconciliation_record (reconciliation_id, provider_billing_record_id, scope_id, " +
                    "period_start_epoch_ms, period_end_epoch_ms, provider_currency, local_currency, comparison_currency, " +
                    "conversion_rule_id, provider_amount_micro_units, difference_micro_units, tolerance_micro_units, " +
                    "coverage_status, reconciliation_status, finality_status, rule_version, recorded_at_epoch_ms) VALUES " +
                    "('rec-m4','pbr-m4','sd-m4',0,5000,'USD','USD','USD',null,1000,0,0,'COMPLETE','CONFLICT','DRAFT','fx:v1',10)"
            )
            try {
                sql.execSQL("UPDATE reconciliation_record SET finality_status='FINALIZED' WHERE reconciliation_id='rec-m4'")
                assertTrue("finalizing without PRIMARY evidence must be rejected", false)
            } catch (expected: android.database.SQLException) {
                // the guard spoke — correct
            }
            // the guard does NOT block the harmless no-op re-set of the same state
            // (it triggers only on the transition INTO the authoritative state).
            // Now link PRIMARY evidence and the sign-off goes through.
            sql.execSQL(
                "INSERT INTO reconciliation_evidence (reconciliation_id, evidence_id, evidence_role, confidence_state, is_primary, created_at_epoch_ms) VALUES " +
                    "('rec-m4','ev-m4','PRIMARY','HIGH',1,10)"
            )
            sql.execSQL("UPDATE reconciliation_record SET finality_status='FINALIZED', reconciled_at_epoch_ms=20 WHERE reconciliation_id='rec-m4'")
            sql.query("SELECT finality_status FROM reconciliation_record WHERE reconciliation_id='rec-m4'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("FINALIZED", c.getString(0))
            }

            // ---- the same guard on the three new state machines ----
            // metric relationship: ACTIVE requires PRIMARY evidence.
            sql.execSQL("INSERT INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, value_type, aggregation_semantics, is_monotonic, created_at_epoch_ms) VALUES ('md-m4','tokens.in','USAGE','MEASURE','COUNTER','NUMERIC','ADDITIVE',0,0)")
            sql.execSQL("INSERT INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, value_type, aggregation_semantics, is_monotonic, created_at_epoch_ms) VALUES ('md-m4b','tokens.out','USAGE','MEASURE','COUNTER','NUMERIC','ADDITIVE',0,0)")
            sql.execSQL(
                "INSERT INTO metric_relationship (relation_id, source_metric_id, target_metric_id, relation_type, " +
                    "effective_from_epoch_ms, relation_status) VALUES ('rel-m4','md-m4','md-m4b','ADDITIVE_WITH',0,'REJECTED')"
            )
            try {
                sql.execSQL("UPDATE metric_relationship SET relation_status='ACTIVE' WHERE relation_id='rel-m4'")
                assertTrue("activating a relation without PRIMARY evidence must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            sql.execSQL(
                "INSERT INTO metric_relationship_evidence (relation_id, evidence_id, evidence_role, confidence_state, is_primary, created_at_epoch_ms) VALUES " +
                    "('rel-m4','ev-m4','PRIMARY','HIGH',1,10)"
            )
            sql.execSQL("UPDATE metric_relationship SET relation_status='ACTIVE' WHERE relation_id='rel-m4'")

            // ---- observation: exactly-one-subject + value/knowledge CHECKs ----
            try {
                sql.execSQL(
                    "INSERT INTO observation (observation_id, provider_id, subject_provider_id, subject_access_context_id, " +
                        "subject_provider_scope_node_id, subject_provider_model_record_id, subject_model_id, subject_offering_id, " +
                        "subject_request_attempt_id, metric_id, value_type, numeric_value_precise, knowledge_status, " +
                        "source_authority, observed_at_epoch_ms, validity_status, recorded_at_epoch_ms) VALUES " +
                        "('obs-bad','prov-m4',null,null,null,null,null,null,null,'md-m4','NUMERIC','1','KNOWN','TEST',0,'VALID',0)"
                )
                assertTrue("zero subjects must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // KNOWN row without its typed value is rejected
            try {
                sql.execSQL(
                    "INSERT INTO observation (observation_id, provider_id, subject_provider_id, metric_id, value_type, " +
                        "knowledge_status, source_authority, observed_at_epoch_ms, validity_status, recorded_at_epoch_ms) VALUES " +
                        "('obs-bad2','prov-m4','prov-m4','md-m4','NUMERIC','KNOWN','TEST',0,'VALID',0)"
                )
                assertTrue("KNOWN without a value must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // the legal shape goes through and is immutable
            sql.execSQL(
                "INSERT INTO observation (observation_id, provider_id, subject_provider_id, metric_id, value_type, " +
                    "numeric_value_precise, knowledge_status, source_authority, observed_at_epoch_ms, validity_status, recorded_at_epoch_ms) VALUES " +
                    "('obs-m4','prov-m4','prov-m4','md-m4','NUMERIC','123.45','KNOWN','TEST',0,'VALID',0)"
            )
            try { sql.execSQL("UPDATE observation SET text_value='x' WHERE observation_id='obs-m4'"); assertTrue("observations are immutable", false) }
            catch (expected: android.database.SQLException) { }

            // ---- the cycle guard on provider_scope_node ----
            sql.execSQL("INSERT INTO provider_scope_node (scope_node_id, provider_id, scope_type, scope_status, first_seen_at_epoch_ms) VALUES ('sn-1','prov-m4','ACCOUNT','ACTIVE',0)")
            sql.execSQL("INSERT INTO provider_scope_node (scope_node_id, provider_id, parent_scope_node_id, scope_type, scope_status, first_seen_at_epoch_ms) VALUES ('sn-2','prov-m4','sn-1','ACCOUNT','ACTIVE',0)")
            try {
                sql.execSQL("UPDATE provider_scope_node SET parent_scope_node_id='sn-2' WHERE scope_node_id='sn-1'")
                assertTrue("a scope cycle must be rejected", false)
            } catch (expected: android.database.SQLException) { }

            // ---- v26 witness data survived the upgrade untouched ----
            sql.query("SELECT COUNT(*) FROM currency_conversion_rule WHERE currency_conversion_rule_id='fx-m4'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            sql.query("SELECT COUNT(*) FROM scope_descriptor WHERE scope_id='sd-m4'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun `fresh v27 database opens and validates cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            // mirrors the production getInstance callback wiring (the test
            // convention: the DB-level protections exist on fresh installs)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.example.infrastructure.persistence.BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    com.example.infrastructure.persistence.Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    com.example.infrastructure.persistence.Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    com.example.infrastructure.persistence.Stage4MigrationSql.TRIGGERS.forEach(db::execSQL)
                }
            })
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT 1").use { it.moveToFirst() }
            db.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_no_update_evidence'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    companion object {
        private const val TEST_DB = "migration26to27-test.db"
    }
}
