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
 * STAGE 2 — MIGRATION_24_TO_25 (pricing catalog + usage metering +
 * itemized cost breakdown + the pricing_entries data mirror)
 * ============================================================================
 * Mirrors the Stage-1 discipline (Migration22to23Test):
 *  - the FROM state is the machine-exported v24 schema (24.json) — the
 *    REAL 51+7-table baseline, not a hand-written approximation;
 *  - the upgrade runs the REAL production migration (reflection — repo
 *    convention) and Room's own TableInfo validation then accepts the
 *    end state on open (the 25.json-isolated variant lands with the
 *    first build's schema export, exactly as 23.json/24.json did —
 *    deferred by design, not omitted);
 *  - behavior is asserted with raw SQL: 12 new tables, 18 immutability
 *    triggers, seed reference data, the pricing mirror (windows, dedupe,
 *    NOT_CALCULABLE, components), and v23/v24 budget data survival.
 */
@RunWith(RobolectricTestRunner::class)
class Migration24to25Test {

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

    private val stage2Tables = listOf(
        "unit_definition", "metric_definition",
        "pricing_profile", "pricing_profile_version", "pricing_component", "pricing_tier",
        "usage_record", "usage_measurement",
        "cost_calculation", "cost_calculation_input", "cost_calculation_line", "cost_calculation_line_input"
    )

    private val eighteenTriggers = listOf(
        "trg_pp_version_no_update", "trg_pp_version_no_delete",
        "trg_pricing_component_no_update", "trg_pricing_component_no_delete",
        "trg_pricing_tier_no_update", "trg_pricing_tier_no_delete",
        "trg_usage_record_no_update", "trg_usage_record_no_delete",
        "trg_usage_measurement_no_update", "trg_usage_measurement_no_delete",
        "trg_cost_calculation_no_update", "trg_cost_calculation_no_delete",
        "trg_cost_calculation_input_no_update", "trg_cost_calculation_input_no_delete",
        "trg_cost_line_no_update", "trg_cost_line_no_delete",
        "trg_cost_line_input_no_update", "trg_cost_line_input_no_delete"
    )

    private fun insertPricingEntry(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String, scopeType: String, providerId: String,
        serviceId: String?, modelId: String?,
        inputPrice: Long?, outputPrice: Long?, cachedPrice: Long?,
        currency: String = "USD", provenance: String = "manual",
        fromMs: Long, toMs: Long?, createdAtMs: Long
    ) {
        db.execSQL(
            "INSERT INTO pricing_entries (id, scopeType, providerId, serviceId, modelId, " +
                "inputPriceMicroPerMillion, outputPriceMicroPerMillion, cachedInputPriceMicroPerMillion, " +
                "currency, billingClass, pricingVersion, effectiveFromEpochMs, effectiveToEpochMs, " +
                "provenance, createdAtEpochMs) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            // BF-13: arrayOf<Any> cannot hold the helper's nullable
            // arguments — the bind array must be Array<Any?>.
            arrayOf<Any?>(id, scopeType, providerId, serviceId, modelId, inputPrice, outputPrice,
                cachedPrice, currency, "CLOUD", "v1", fromMs, toMs, provenance, createdAtMs)
        )
    }

    @Test
    fun `24 to 25 creates the Stage-2 schema from the real v24 baseline and Room validates it`() {
        val dbName = "stage2-mig-24to25.db"
        helper.createDatabase(dbName, 24).use { db ->
            // legacy pricing content the mirror must carry over — a
            // representative set: normal windows, an overlap to clamp, a
            // same-from duplicate (latest createdAt wins), a provider-level
            // entry, and a price-less row (NOT_CALCULABLE)
            insertPricingEntry(db, "pe-m1", "MODEL", "prov-a", null, "model-x",
                1500L, 3000L, null, fromMs = 100, toMs = 500, createdAtMs = 10)
            insertPricingEntry(db, "pe-m1b", "MODEL", "prov-a", null, "model-x",
                1800L, 3600L, 750L, fromMs = 500, toMs = null, createdAtMs = 20)
            insertPricingEntry(db, "pe-m1c", "MODEL", "prov-a", null, "model-x",
                9999L, 9999L, null, fromMs = 500, toMs = null, createdAtMs = 15) // same-from, older -> shadowed
            insertPricingEntry(db, "pe-p1", "PROVIDER", "prov-a", null, null,
                2000L, 4000L, null, fromMs = 0, toMs = null, createdAtMs = 5)
            insertPricingEntry(db, "pe-n1", "MODEL", "prov-b", null, "model-y",
                null, null, null, fromMs = 0, toMs = null, createdAtMs = 1)
            // v23/v24 budget content that must survive untouched
            db.execSQL(
                "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                    "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                    "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                    "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                    "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                    "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                    "('sd-s2','WORKSPACE','v1|WORKSPACE|ws-9','h-s2','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                    "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-9',0)"
            )
            db.execSQL(
                "INSERT INTO budget_policy VALUES ('bp-s2','fam-s2','survivor','WORKSPACE','sd-s2'," +
                    "'USD',1000000,'MONTHLY','NO_ROLLOVER','HARD_LIMIT','ACCOUNTING_ALLOCATION',1,0,'ACTIVE',0,0)"
            )
            db.execSQL("INSERT INTO budget_period VALUES ('bper-s2','bp-s2',0,4102444800000,1000000,'OPEN',0,0)")
            db.execSQL(
                "INSERT INTO budget_hold VALUES ('bh-s2','bper-s2',700,'USD','exec:s2-survivor',NULL,'ACTIVE',0,NULL,'ik-s2')"
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(migration("MIGRATION_24_TO_25"), migration("MIGRATION_25_TO_26")) // v26: the entity set moved on — both steps run (the 22to23 precedent)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase // open -> migrate -> Room validates

            db.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(26, c.getInt(0))
            }

            // ---- 12 Stage-2 tables exist ----
            for (table in stage2Tables) {
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'"
                ).use { c -> assertTrue("table $table must exist", c.moveToFirst()) }
            }

            // ---- 18 immutability triggers exist ----
            for (trigger in eighteenTriggers) {
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='trigger' AND name='$trigger'"
                ).use { c -> assertTrue("trigger $trigger must exist", c.moveToFirst()) }
            }

            // ---- seeds ----
            db.query("SELECT COUNT(*) FROM unit_definition").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM metric_definition").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(4, c.getInt(0))
            }

            // ---- the pricing mirror ----
            db.query("SELECT COUNT(*) FROM pricing_profile").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("prov-a (2 targets) + prov-b (1) = 3 profiles", 3, c.getInt(0))
            }
            // prov-a MODEL:model-x:- profile: 2 surviving versions (100-500, 500-∞)
            db.query(
                "SELECT COUNT(*) FROM pricing_profile_version v JOIN pricing_profile p " +
                    "ON p.pricing_profile_id = v.pricing_profile_id " +
                    "WHERE p.provider_id='prov-a' AND p.profile_code='MODEL:model-x:-'"
            ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
            // window clamping: first version ends exactly at the second's start
            db.query(
                "SELECT v.effective_from_epoch_ms, v.effective_until_epoch_ms FROM pricing_profile_version v " +
                    "JOIN pricing_profile p ON p.pricing_profile_id = v.pricing_profile_id " +
                    "WHERE p.provider_id='prov-a' AND p.profile_code='MODEL:model-x:-' ORDER BY v.version_number"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(100L, c.getLong(0)); assertEquals(500L, c.getLong(1))
                assertTrue(c.moveToNext())
                assertEquals(500L, c.getLong(0))
                assertEquals("open-ended second window", true, c.isNull(1))
            }
            // the same-from duplicate with the OLDER createdAt is shadowed: no 9999 rates anywhere
            db.query(
                "SELECT COUNT(*) FROM pricing_component WHERE rate_amount_precise='9999'"
            ).use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            // the winning duplicate carried its three prices
            db.query(
                "SELECT COUNT(*) FROM pricing_component c JOIN pricing_profile_version v " +
                    "ON v.pricing_profile_version_id = c.pricing_profile_version_id " +
                    "WHERE v.version_number = 2 AND c.rate_amount_precise IN ('1800','3600','750')"
            ).use { c -> assertTrue(c.moveToFirst()); assertEquals(3, c.getInt(0)) }
            // the price-less row mirrors as a NOT_CALCULABLE version with no components
            db.query(
                "SELECT v.pricing_status, (SELECT COUNT(*) FROM pricing_component c " +
                    "WHERE c.pricing_profile_version_id = v.pricing_profile_version_id) " +
                    "FROM pricing_profile_version v JOIN pricing_profile p " +
                    "ON p.pricing_profile_id = v.pricing_profile_id " +
                    "WHERE p.provider_id='prov-b'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("NOT_CALCULABLE", c.getString(0))
                assertEquals(0, c.getInt(1))
            }

            // ---- v23/v24 budget data survived verbatim ----
            db.query("SELECT status FROM budget_hold WHERE hold_id='bh-s2'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("ACTIVE", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM pricing_entries").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("legacy rows untouched (mirror copies, never moves)", 5, c.getInt(0))
            }

            // ---- immutability is behavioral, not decorative ----
            try {
                db.execSQL("UPDATE pricing_profile_version SET effective_status='RETIRED' WHERE version_number=1")
                assertFalse("pricing_profile_version UPDATE must be rejected", true)
            } catch (expected: android.database.SQLException) {
                // trigger fired — correct
            }
            // BF-15: SQLite triggers are FOR EACH ROW — the DELETE proof
            // needs a row to delete, or an empty table deletes silently.
            db.execSQL(
                "INSERT INTO usage_record (usage_record_id, request_attempt_id, report_source, " +
                    "report_version, report_status, is_final_report, recorded_at_epoch_ms) " +
                    "VALUES ('ur-proof','att-proof','PROVIDER','1','FINAL',1,0)"
            )
            try {
                db.execSQL("DELETE FROM usage_record")
                assertFalse("usage_record DELETE must be rejected", true)
            } catch (expected: android.database.SQLException) {
                // trigger fired — correct
            }
        } finally {
            room.close()
        }
    }
}
