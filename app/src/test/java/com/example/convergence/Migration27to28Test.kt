package com.example.convergence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.infrastructure.persistence.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 5 — MIGRATION_27_TO_28 (the derived-state projection + G05 freeze)
 * ============================================================================
 * Mirrors the Stage-1..4 discipline:
 *  - the FROM state is the machine-exported v27 schema (27.json — the export
 *    the Stage-4 verification build produced);
 *  - the upgrade runs the REAL production migration (reflection — repo
 *    convention) and Room's own TableInfo validation accepts the end state
 *    on open (the 28.json export ships with this commit: 112 entities);
 *  - behavior is asserted with raw SQL: 4 new tables, 10 named indices, the
 *    four Stage-5 triggers, FK enforcement, the G05 whole-version freeze
 *    (substantive UPDATE of a governing version rejected, status-only
 *    retirement legal, a non-governing version still editable, deletion of
 *    a governing version blocked by the FK), the at-most-one-active guards
 *    (INSERT + the UPDATE activation arm), the state shape CHECKs, the
 *    scope-dimension shape CHECKs, and v27 data survival.
 */
@RunWith(RobolectricTestRunner::class)
class Migration27to28Test {

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

    private val stage5Tables = listOf(
        "current_resource_state", "current_resource_state_source",
        "scope_internal_dimension", "scope_custom_dimension"
    )

    private val stage5Indices = listOf(
        "ix_current_state_metric_active", "ix_current_state_scope_active",
        "ix_current_state_as_of", "ix_current_state_source_state",
        "ix_current_state_source_observation", "ux_current_state_source_triple",
        "ix_scope_internal_dimension_scope", "ux_scope_internal_dimension",
        "ix_scope_custom_dimension_scope", "ux_scope_custom_dimension"
    )

    private val fourTriggers = listOf(
        "trg_current_state_unique_active_insert",
        "trg_current_state_unique_active_update",
        "trg_current_state_subject_provider_insert",
        "trg_budget_policy_version_freeze_when_governing"
    )

    @Test
    fun `27 to 28 migration is schema-valid and behaviorally sound`() {
        // 1) a REAL v27 database (machine-exported 27.json), with v27 witness
        //    data that must survive the upgrade untouched.
        helper.createDatabase(TEST_DB, 27).use { v27 ->
            // the scope every witness hangs off
            v27.execSQL(
                "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                    "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                    "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                    "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                    "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                    "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                    "('sd-m5','WORKSPACE','v1|WORKSPACE|ws-m5','hm5','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-m5',0)"
            )
            // a registry provider + metric + freshness policy the v28 tables FK to
            v27.execSQL(
                "INSERT INTO provider (provider_id, provider_code, name, provider_status, created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES ('prov-m5','pdx','PDX','ACTIVE',0,0)"
            )
            v27.execSQL(
                "INSERT INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, " +
                    "value_type, aggregation_semantics, is_monotonic, created_at_epoch_ms) VALUES " +
                    "('md-m5','tokens.in.m5','LLM_USAGE','CONSUMED','COUNTER','NUMERIC','ADDITIVE',0,0)"
            )
            v27.execSQL(
                "INSERT INTO freshness_policy (policy_id, metric_id, expected_update_interval_seconds, " +
                    "staleness_threshold_seconds, source_delay_seconds, unknown_when_expired, " +
                    "effective_from_epoch_ms, created_at_epoch_ms) VALUES " +
                    "('fp-m5','md-m5',3600,86400,0,1,0,0)"
            )
            // a v27 observation that must survive (the projection's input)
            v27.execSQL(
                "INSERT INTO observation (observation_id, provider_id, subject_provider_id, metric_id, value_type, " +
                    "numeric_value_precise, unit_id, scope_id, observed_at_epoch_ms, validity_status, source_authority, " +
                    "knowledge_status, recorded_at_epoch_ms) VALUES " +
                    "('obs-m5','prov-m5','prov-m5','md-m5','NUMERIC','4321',null,'sd-m5',5000,'VALID','TEST','KNOWN',5000)"
            )
            // a GOVERNING policy (has a period) and a non-governing one (no period yet)
            v27.execSQL(
                "INSERT INTO budget_policy (budget_policy_id, policy_family_id, budget_name, budget_scope_type, scope_id, " +
                    "currency, default_limit_micro_units, period_type, rollover_policy, overage_policy, allocation_policy, " +
                    "policy_version, effective_from_epoch_ms, policy_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES " +
                    "('bp-m5','fam-m5','Governing','WORKSPACE','sd-m5','USD',1000000,'MONTHLY','NO_ROLLOVER','WARN','SUPERVISORY_OVERLAP',1,0,'ACTIVE',0,0)"
            )
            v27.execSQL(
                "INSERT INTO budget_period (budget_period_id, budget_policy_id, period_start_epoch_ms, period_end_epoch_ms, " +
                    "limit_micro_units, period_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES " +
                    "('bper-m5','bp-m5',0,2592000000,1000000,'OPEN',0,0)"
            )
            v27.execSQL(
                "INSERT INTO budget_policy (budget_policy_id, policy_family_id, budget_name, budget_scope_type, scope_id, " +
                    "currency, default_limit_micro_units, period_type, rollover_policy, overage_policy, allocation_policy, " +
                    "policy_version, effective_from_epoch_ms, policy_status, created_at_epoch_ms, updated_at_epoch_ms) VALUES " +
                    "('bp-m5b','fam-m5b','Draft (no period yet)','WORKSPACE','sd-m5','USD',500000,'MONTHLY','NO_ROLLOVER','WARN','SUPERVISORY_OVERLAP',1,0,'ACTIVE',0,0)"
            )
        }

        // 2) run the REAL production migration, then let MigrationTestHelper
        //    compare the result against the machine-exported 28.json.
        helper.runMigrationsAndValidate(TEST_DB, 28, true, migration("MIGRATION_27_TO_28")).use { db ->
            val sql = db

            // ---- 4 new tables + 10 named indices + 4 triggers ----
            for (table in stage5Tables) {
                sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
                    assertTrue("Stage-5 table $table must exist", c.moveToFirst())
                }
            }
            for (index in stage5Indices) {
                sql.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$index'").use { c ->
                    assertTrue("Stage-5 index $index must exist", c.moveToFirst())
                }
            }
            for (trigger in fourTriggers) {
                sql.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name='$trigger'").use { c ->
                    assertTrue("Stage-5 trigger $trigger must exist", c.moveToFirst())
                }
            }

            // ---- v27 witness data survived untouched ----
            sql.query("SELECT numeric_value_precise FROM observation WHERE observation_id='obs-m5'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("4321", c.getString(0))
            }
            sql.query("SELECT policy_status FROM budget_policy WHERE budget_policy_id='bp-m5'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("ACTIVE", c.getString(0))
            }

            // ---- FK enforcement: an orphan state row is rejected ----
            sql.execSQL("PRAGMA foreign_keys = ON")
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-orphan','prov-missing','md-m5','NUMERIC','1','sd-m5',0,'FRESH','fp-m5','OK',1,0)"
                )
                assertTrue("orphan current state must be rejected by the FK", false)
            } catch (expected: android.database.SQLException) {
                // foreign key constraint — correct
            }

            // ---- G05: the whole-version freeze ----
            // (a) a substantive UPDATE of a GOVERNING version is rejected.
            try {
                sql.execSQL("UPDATE budget_policy SET budget_name='Renamed' WHERE budget_policy_id='bp-m5'")
                assertTrue("a governing version's substantive columns must be frozen", false)
            } catch (expected: android.database.SQLException) {
                // the freeze spoke — correct
            }
            // (b) even the limit — the financially meaningful column.
            try {
                sql.execSQL("UPDATE budget_policy SET default_limit_micro_units=2000000 WHERE budget_policy_id='bp-m5'")
                assertTrue("a governing version's limit must be frozen", false)
            } catch (expected: android.database.SQLException) { }
            // (c) the status-only retirement is exactly the surface the
            //     service's retirement path needs — legal.
            sql.execSQL("UPDATE budget_policy SET policy_status='RETIRED', updated_at_epoch_ms=100 WHERE budget_policy_id='bp-m5'")
            sql.query("SELECT policy_status FROM budget_policy WHERE budget_policy_id='bp-m5'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("RETIRED", c.getString(0))
            }
            // (d) a NON-governing version (no period yet) is still editable —
            //     the pricing-version precedent (frozen only once used).
            sql.execSQL("UPDATE budget_policy SET budget_name='Draft renamed' WHERE budget_policy_id='bp-m5b'")
            sql.query("SELECT budget_name FROM budget_policy WHERE budget_policy_id='bp-m5b'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("Draft renamed", c.getString(0))
            }
            // (e) deleting a governing version is blocked by the FK.
            try {
                sql.execSQL("DELETE FROM budget_policy WHERE budget_policy_id='bp-m5'")
                assertTrue("deleting a governing version must be blocked", false)
            } catch (expected: android.database.SQLException) { }

            // ---- the at-most-one-active guards ----
            // A legal active row goes in first.
            sql.execSQL(
                "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                    "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                    "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                    "('st-a','prov-m5','md-m5','NUMERIC','100','sd-m5',5000,'FRESH','fp-m5','OK',1,0)"
            )
            // (a) a second ACTIVE row for the same (subject, metric, scope) is ABORTED.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-b','prov-m5','md-m5','NUMERIC','101','sd-m5',6000,'FRESH','fp-m5','OK',1,0)"
                )
                assertTrue("a second active row for the same key must be ABORTED", false)
            } catch (expected: android.database.SQLException) { }
            // (b) a NULL-scope row does NOT collide with the real-scope row
            //     (the PDM's COALESCE '__NO_SCOPE__' semantics).
            sql.execSQL(
                "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                    "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                    "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                    "('st-null','prov-m5','md-m5','NUMERIC','90',null,7000,'FRESH','fp-m5','OK',1,0)"
            )
            // (c) the supersede path: retire st-a, then st-b goes in legally.
            sql.execSQL("UPDATE current_resource_state SET is_active=0, updated_at_epoch_ms=10 WHERE state_id='st-a'")
            sql.execSQL(
                "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                    "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                    "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                    "('st-b','prov-m5','md-m5','NUMERIC','101','sd-m5',6000,'FRESH','fp-m5','OK',1,0)"
            )
            // (d) the UPDATE arm: ACTIVATING st-a while st-b is active is ABORTED.
            try {
                sql.execSQL("UPDATE current_resource_state SET is_active=1 WHERE state_id='st-a'")
                assertTrue("activating a second active row must be ABORTED", false)
            } catch (expected: android.database.SQLException) { }
            // (e) an in-place no-op update of the ACTIVE row itself is legal
            //     (the guard excludes the row's own state_id).
            sql.execSQL("UPDATE current_resource_state SET updated_at_epoch_ms=20 WHERE state_id='st-b'")

            // ---- the state shape CHECKs ----
            // exactly-one-subject: zero subjects rejected.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-bad0','md-m5','NUMERIC','1','sd-m5',0,'FRESH','fp-m5','OK',1,0)"
                )
                assertTrue("zero subjects must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // exactly-one-subject: two subjects rejected.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, subject_model_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-bad2','prov-m5','x','md-m5','NUMERIC','1','sd-m5',0,'FRESH','fp-m5','OK',1,0)"
                )
                assertTrue("two subjects must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // UNKNOWN status carries no value.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-bad3','prov-m5','md-m5','NUMERIC','1','sd-m5',0,'FRESH','fp-m5','UNKNOWN',1,0)"
                )
                assertTrue("UNKNOWN status with a value must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // NOT_DERIVABLE status carries no value.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                        "current_text_value, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-bad4','prov-m5','md-m5','TEXT','x','sd-m5',0,'FRESH','fp-m5','NOT_DERIVABLE',1,0)"
                )
                assertTrue("NOT_DERIVABLE status with a value must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // state_status vocabulary.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-bad5','prov-m5','md-m5','NUMERIC','1','sd-m5',0,'FRESH','fp-m5','BOGUS',1,0)"
                )
                assertTrue("a bogus state_status must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // freshness vocabulary.
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                        "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                        "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                        "('st-bad6','prov-m5','md-m5','NUMERIC','1','sd-m5',0,'STALEISH','fp-m5','OK',1,0)"
                )
                assertTrue("a bogus freshness_state must be rejected", false)
            } catch (expected: android.database.SQLException) { }

            // ---- the sources: the source_role vocabulary + the unique triple ----
            sql.execSQL(
                "INSERT INTO current_resource_state_source (state_source_id, state_id, observation_id, source_role, included_at_epoch_ms) " +
                    "VALUES ('src-1','st-b','obs-m5','PRIMARY',0)"
            )
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state_source (state_source_id, state_id, observation_id, source_role, included_at_epoch_ms) " +
                        "VALUES ('src-2','st-b','obs-m5','PRIMARY',0)"
                )
                assertTrue("the (state, observation, role) triple must be unique", false)
            } catch (expected: android.database.SQLException) { }
            try {
                sql.execSQL(
                    "INSERT INTO current_resource_state_source (state_source_id, state_id, observation_id, source_role, included_at_epoch_ms) " +
                        "VALUES ('src-3','st-b','obs-m5','BOGUS',0)"
                )
                assertTrue("a bogus source_role must be rejected", false)
            } catch (expected: android.database.SQLException) { }

            // ---- the scope dimension shape CHECKs ----
            sql.execSQL(
                "INSERT INTO scope_internal_dimension (scope_dimension_id, scope_id, dimension_code, dimension_state, dimension_value, value_type, created_at_epoch_ms) " +
                    "VALUES ('dim-1','sd-m5','cost_center','KNOWN','CC-7','TEXT',0)"
            )
            // KNOWN without a value rejected.
            try {
                sql.execSQL(
                    "INSERT INTO scope_internal_dimension (scope_dimension_id, scope_id, dimension_code, dimension_state, dimension_value, value_type, created_at_epoch_ms) " +
                        "VALUES ('dim-2','sd-m5','cost_center2','KNOWN',null,null,0)"
                )
                assertTrue("KNOWN without a value must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // UNKNOWN with a value rejected.
            try {
                sql.execSQL(
                    "INSERT INTO scope_internal_dimension (scope_dimension_id, scope_id, dimension_code, dimension_state, dimension_value, value_type, created_at_epoch_ms) " +
                        "VALUES ('dim-3','sd-m5','env','UNKNOWN','prod','TEXT',0)"
                )
                assertTrue("UNKNOWN with a value must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // duplicate (scope, code) rejected by the named unique index.
            try {
                sql.execSQL(
                    "INSERT INTO scope_internal_dimension (scope_dimension_id, scope_id, dimension_code, dimension_state, dimension_value, value_type, created_at_epoch_ms) " +
                        "VALUES ('dim-4','sd-m5','cost_center','NOT_APPLICABLE',null,null,0)"
                )
                assertTrue("duplicate (scope, dimension_code) must be rejected", false)
            } catch (expected: android.database.SQLException) { }
            // custom dimensions: the namespace partitions the code space.
            sql.execSQL(
                "INSERT INTO scope_custom_dimension (scope_dimension_id, scope_id, namespace, dimension_code, dimension_state, dimension_value, value_type, created_at_epoch_ms) " +
                    "VALUES ('cdim-1','sd-m5','team','cost_center','KNOWN','CC-7','TEXT',0)"
            )
            try {
                sql.execSQL(
                    "INSERT INTO scope_custom_dimension (scope_dimension_id, scope_id, namespace, dimension_code, dimension_state, dimension_value, value_type, created_at_epoch_ms) " +
                        "VALUES ('cdim-2','sd-m5','team','cost_center','KNOWN','CC-9','TEXT',0)"
                )
                assertTrue("duplicate (scope, namespace, code) must be rejected", false)
            } catch (expected: android.database.SQLException) { }
        }
    }

    companion object {
        private const val TEST_DB = "migration27to28-test.db"
    }
}
