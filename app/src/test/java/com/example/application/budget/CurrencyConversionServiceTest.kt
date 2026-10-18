package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 3 — CurrencyConversionService (gate PDM-G03)
 * ============================================================================
 * The G03 acceptance test from the gate register: "direction CHECK test +
 * signed rounding golden test + micro tolerance".
 */
@RunWith(RobolectricTestRunner::class)
class CurrencyConversionServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var fx: CurrencyConversionService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        fx = CurrencyConversionService(database = db, clock = { 0L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `G03 direction - a rule converts only its own direction, never the reverse`() = runBlocking {
        fx.registerRule("EUR", "USD", "1.1", 0L, 10_000L, "test")

        // EUR -> USD resolves (1.1 target units per source unit)
        val forward = fx.convert(1_000_000L, "EUR", "USD", 5_000L)
        assertTrue(forward is CurrencyConversionService.ConversionResult.Converted)
        assertEquals(1_100_000L, (forward as CurrencyConversionService.ConversionResult.Converted).targetMicroUnits)

        // USD -> EUR has NO rule: fail-closed, never an inverted-rate guess
        val backward = fx.convert(1_000_000L, "USD", "EUR", 5_000L)
        assertTrue(backward is CurrencyConversionService.ConversionResult.NoRule)
    }

    @Test
    fun `G03 signed rounding golden - HALF_UP at micro scale, away from zero on negatives`() = runBlocking {
        fx.registerRule("EUR", "USD", "1.1", 0L, null, "test")

        // +2.5 micro rounds away from zero -> 3
        val positive = fx.convert(25L, "EUR", "USD", 1L) as CurrencyConversionService.ConversionResult.Converted
        assertEquals(28L, positive.targetMicroUnits) // 25 × 1.1 = 27.5 -> HALF_UP -> 28
        assertEquals("27.5", positive.preRoundedPrecise)

        // negative amounts (credits/adjustments): -27.5 -> -28 (away from zero)
        val negative = fx.convert(-25L, "EUR", "USD", 1L) as CurrencyConversionService.ConversionResult.Converted
        assertEquals(-28L, negative.targetMicroUnits)
        assertEquals("-27.5", negative.preRoundedPrecise)

        // exact halves on the positive side: 5 × 1.1 = 5.5 -> 6
        val half = fx.convert(5L, "EUR", "USD", 1L) as CurrencyConversionService.ConversionResult.Converted
        assertEquals(6L, half.targetMicroUnits)
    }

    @Test
    fun `windowed resolution - only the covering rule applies, latest from wins`() = runBlocking {
        fx.registerRule("EUR", "USD", "1.0", 0L, 1_000L, "old")
        fx.registerRule("EUR", "USD", "1.5", 1_000L, null, "new")

        val before = fx.convert(100L, "EUR", "USD", 999L) as CurrencyConversionService.ConversionResult.Converted
        assertEquals(100L, before.targetMicroUnits) // 1.0 window
        assertEquals("fx_EUR_USD_0", before.ruleId)

        val after = fx.convert(100L, "EUR", "USD", 1_000L) as CurrencyConversionService.ConversionResult.Converted
        assertEquals(150L, after.targetMicroUnits) // 1.5 window
        assertEquals("fx_EUR_USD_1000", after.ruleId)
    }

    @Test
    fun `identity conversion needs no rule and fabricates nothing`() = runBlocking {
        val identity = fx.convert(123_456L, "USD", "USD", 5_000L)
                as CurrencyConversionService.ConversionResult.Converted
        assertEquals(123_456L, identity.targetMicroUnits)
        assertEquals("", identity.ruleId) // G03/PDM: same currency -> NULL rule, no fabricated row
    }

    @Test
    fun `registration is idempotent by window and honest on conflict`() = runBlocking {
        val first = fx.registerRule("GBP", "USD", "1.3", 0L, null, "test")
        assertTrue(first is CurrencyConversionService.RuleRegistration.Registered)
        val replay = fx.registerRule("GBP", "USD", "1.3", 0L, null, "test")
        assertTrue(replay is CurrencyConversionService.RuleRegistration.Replayed)
        try {
            fx.registerRule("GBP", "USD", "9.9", 0L, null, "test")
            assertTrue("conflicting re-registration must be rejected", false)
        } catch (expected: IllegalArgumentException) {
            // honest conflict — register a new window instead
        }
    }

    @Test
    fun `a rule used by a reconciliation is frozen at the DB level`() = runBlocking {
        fx.registerRule("EUR", "USD", "1.1", 0L, null, "test")
        // seed the minimal chain a reconciliation row needs
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-fx','WORKSPACE','v1|WORKSPACE|ws-fx','h','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-fx',0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO provider_billing_record (provider_billing_record_id,provider_id,billing_record_type," +
                "record_granularity,primary_amount_type,currency,amount_consistency_status,source_authority," +
                "recorded_at_epoch_ms,billing_status) VALUES ('pbr-fx','p','INVOICE','MONTHLY','GROSS_PAYABLE'," +
                "'USD','UNKNOWN','t',0,'IMPORTED')"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO reconciliation_record (reconciliation_id,provider_billing_record_id,scope_id," +
                "period_start_epoch_ms,period_end_epoch_ms,provider_currency,local_currency,comparison_currency," +
                "conversion_rule_id,coverage_status,reconciliation_status,finality_status,rule_version," +
                "recorded_at_epoch_ms) VALUES ('rec-fx','pbr-fx','sd-fx',0,100,'USD','EUR','USD'," +
                "'fx_EUR_USD_0','COMPLETE','MATCHED','DRAFT','v',0)"
        )
        try {
            db.openHelper.writableDatabase.execSQL("UPDATE currency_conversion_rule SET rate_precise='2'")
            assertTrue("used rule UPDATE must be rejected", false)
        } catch (expected: android.database.SQLException) {
            // trg_currency_rule_immutable_when_used fired — correct
        }
    }
}
