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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 2C — FX-rule operational management acceptance
 * ============================================================================
 *
 * The operator-facing management surface over the EXISTING conversion
 * authority. Locked behaviors (order §8):
 *
 *   - successful registration through the immutable contract;
 *   - idempotent identical re-registration;
 *   - conflicting duplicate registration REJECTED (never an overwrite of a
 *     rule financial records may reference — corrections are NEW rules);
 *   - invalid currency codes rejected;
 *   - invalid / zero / negative rates rejected;
 *   - invalid effective windows rejected;
 *   - directional conversion honored (A→B never converts B→A);
 *   - no covering rule at the requested instant -> honest NoRule (never 1:1);
 *   - historical conversions reproducible (windowed, exact-decimal);
 *   - the operator rate input parser's exact-decimal discipline;
 *   - the applicability states (ACTIVE_NOW / OPEN_ENDED / NOT_YET / EXPIRED).
 */
@RunWith(RobolectricTestRunner::class)
class FxRuleManagementServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var fx: CurrencyConversionService
    private lateinit var management: FxRuleManagementService

    private var tick = 100_000L

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
        fx = CurrencyConversionService(database = db, clock = { tick })
        management = FxRuleManagementService(database = db, fx = fx, clock = { tick })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Registration: success, idempotency, conflict
    // ------------------------------------------------------------------

    @Test
    fun `a valid rule registers successfully and is listed with its evidence`() = runBlocking {
        val outcome = management.registerRule(
            fromCurrency = "EUR",
            toCurrency = "USD",
            ratePrecise = "1.0854",
            effectiveFromEpochMs = tick,
            effectiveUntilEpochMs = tick + 86_400_000L,
            sourceAuthority = "operator:ecb-daily"
        )
        assertTrue(outcome is FxRuleManagementService.RegisterOutcome.Registered)

        val rules = management.listRules()
        assertEquals(1, rules.size)
        val rule = rules.single()
        assertEquals("EUR", rule.fromCurrency)
        assertEquals("USD", rule.toCurrency)
        assertEquals("1.0854", rule.ratePrecise)
        assertEquals(CurrencyConversionService.DIRECTION, rule.rateDirection)
        assertEquals("operator:ecb-daily", rule.sourceAuthority)
        assertEquals(FxRuleManagementService.RuleApplicability.ACTIVE_NOW, rule.applicability)
    }

    @Test
    fun `an identical re-registration is an idempotent replay`() = runBlocking {
        val first = management.registerRule("EUR", "USD", "1.0854", tick, null, "operator:ecb-daily")
        val second = management.registerRule("EUR", "USD", "1.0854", tick, null, "operator:ecb-daily")
        assertTrue(first is FxRuleManagementService.RegisterOutcome.Registered)
        assertTrue(second is FxRuleManagementService.RegisterOutcome.Replayed)
        assertEquals(
            (first as FxRuleManagementService.RegisterOutcome.Registered).ruleId,
            (second as FxRuleManagementService.RegisterOutcome.Replayed).ruleId
        )
        assertEquals("still exactly one rule row", 1, management.listRules().size)
    }

    @Test
    fun `a conflicting duplicate registration is rejected - never an overwrite`() = runBlocking {
        management.registerRule("EUR", "USD", "1.0854", tick, null, "operator:ecb-daily")
        // Same identity (from/to/effectiveFrom), DIFFERENT rate: a conflict.
        val conflict = management.registerRule("EUR", "USD", "1.0900", tick, null, "operator:ecb-daily")
        assertTrue(conflict is FxRuleManagementService.RegisterOutcome.Rejected)
        // The original is untouched — the rule is immutable once registered.
        val rule = management.listRules().single()
        assertEquals("1.0854", rule.ratePrecise)
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    @Test
    fun `invalid currency codes are rejected`() = runBlocking {
        assertTrue(
            management.registerRule("EURO", "USD", "1.1", tick, null, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue(
            management.registerRule("EUR", "D", "1.1", tick, null, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue(
            management.registerRule("EUR", "EUR", "1.1", tick, null, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue("nothing was written", management.listRules().isEmpty())
    }

    @Test
    fun `invalid, zero and negative rates are rejected`() = runBlocking {
        assertTrue(
            "a non-numeric rate is rejected",
            management.registerRule("EUR", "USD", "not-a-rate", tick, null, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue(
            "a ZERO rate is rejected",
            management.registerRule("EUR", "USD", "0", tick, null, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue(
            "a NEGATIVE rate is rejected",
            management.registerRule("EUR", "USD", "-1.0854", tick, null, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue("nothing was written", management.listRules().isEmpty())
    }

    @Test
    fun `invalid effective windows are rejected`() = runBlocking {
        assertTrue(
            "an empty window (from >= until) is rejected",
            management.registerRule("EUR", "USD", "1.1", tick, tick, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue(
            "an inverted window is rejected",
            management.registerRule("EUR", "USD", "1.1", tick + 1000, tick, "operator") is
                FxRuleManagementService.RegisterOutcome.Rejected
        )
        assertTrue("nothing was written", management.listRules().isEmpty())
    }

    // ------------------------------------------------------------------
    // Conversion behavior through the managed rules
    // ------------------------------------------------------------------

    @Test
    fun `conversion is directional - the reverse pair never converts`() = runBlocking {
        management.registerRule("EUR", "USD", "2", 0, null, "operator")
        val forward = fx.convert(1_000_000L, "EUR", "USD", tick)
        assertTrue(forward is CurrencyConversionService.ConversionResult.Converted)
        assertEquals(2_000_000L, (forward as CurrencyConversionService.ConversionResult.Converted).targetMicroUnits)

        val reverse = fx.convert(1_000_000L, "USD", "EUR", tick)
        assertTrue(
            "no silent inversion: the reverse direction has NO rule (never 1:2, never 1:1)",
            reverse is CurrencyConversionService.ConversionResult.NoRule
        )
    }

    @Test
    fun `no covering rule at the requested instant is an honest NoRule`() = runBlocking {
        // A windowed rule that has already ended.
        management.registerRule("EUR", "USD", "2", 0, 1_000L, "operator")
        val outside = fx.convert(1_000_000L, "EUR", "USD", atEpochMs = 5_000L)
        assertTrue(outside is CurrencyConversionService.ConversionResult.NoRule)
        // And a pair with no rule at all.
        assertTrue(fx.convert(1L, "GBP", "JPY", tick) is CurrencyConversionService.ConversionResult.NoRule)
    }

    @Test
    fun `historical conversions are reproducible across rule versions`() = runBlocking {
        // v1 covers [0, 10_000); v2 covers [10_000, ∞) with a new rate —
        // the documented correction path (new immutable rule, new window).
        management.registerRule("EUR", "USD", "2", 0, 10_000L, "operator:v1")
        management.registerRule("EUR", "USD", "3", 10_000L, null, "operator:v2")

        val historical = fx.convert(1_000_000L, "EUR", "USD", atEpochMs = 5_000L)
        assertTrue(historical is CurrencyConversionService.ConversionResult.Converted)
        assertEquals(
            "the HISTORICAL instant still resolves the v1 rate — reproducible",
            2_000_000L,
            (historical as CurrencyConversionService.ConversionResult.Converted).targetMicroUnits
        )
        val current = fx.convert(1_000_000L, "EUR", "USD", atEpochMs = 20_000L)
        assertEquals(
            3_000_000L,
            (current as CurrencyConversionService.ConversionResult.Converted).targetMicroUnits
        )
    }

    @Test
    fun `exact decimal rates convert with exact math - never binary floats`() = runBlocking {
        management.registerRule("USD", "JPY", "157.304", 0, null, "operator")
        val converted = fx.convert(1_234_567L, "USD", "JPY", tick)
        assertTrue(converted is CurrencyConversionService.ConversionResult.Converted)
        val c = converted as CurrencyConversionService.ConversionResult.Converted
        // 1_234_567 × 157.304 = 194_202_327.368 -> HALF_UP at micro scale = 194_202_327
        assertEquals(194_202_327L, c.targetMicroUnits)
        assertEquals("194202327.368", c.preRoundedPrecise)
    }

    // ------------------------------------------------------------------
    // Applicability states
    // ------------------------------------------------------------------

    @Test
    fun `the applicability state distinguishes active, future and expired windows`() = runBlocking {
        management.registerRule("AAA", "BBB", "2", tick - 1000, tick + 1000, "op")   // ACTIVE_NOW
        management.registerRule("CCC", "DDD", "2", tick - 1000, null, "op")          // OPEN_ENDED_ACTIVE
        management.registerRule("EEE", "FFF", "2", tick + 5000, null, "op")          // NOT_YET_EFFECTIVE
        management.registerRule("GGG", "HHH", "2", 0, tick - 100, "op")              // EXPIRED

        val byPair = management.listRules().associateBy { it.fromCurrency to it.toCurrency }
        assertEquals(FxRuleManagementService.RuleApplicability.ACTIVE_NOW, byPair["AAA" to "BBB"]!!.applicability)
        assertEquals(FxRuleManagementService.RuleApplicability.OPEN_ENDED_ACTIVE, byPair["CCC" to "DDD"]!!.applicability)
        assertEquals(FxRuleManagementService.RuleApplicability.NOT_YET_EFFECTIVE, byPair["EEE" to "FFF"]!!.applicability)
        assertEquals(FxRuleManagementService.RuleApplicability.EXPIRED, byPair["GGG" to "HHH"]!!.applicability)
    }

    @Test
    fun `a rule referenced by financial records stays immutable - corrections are new rules`() = runBlocking {
        val first = management.registerRule("EUR", "USD", "2", 0, null, "operator:v1")
        assertTrue(first is FxRuleManagementService.RegisterOutcome.Registered)
        val firstId = (first as FxRuleManagementService.RegisterOutcome.Registered).ruleId

        // The rule is USED by a financial record (a conversion in flight)…
        val used = fx.convert(1_000_000L, "EUR", "USD", tick)
        assertTrue(used is CurrencyConversionService.ConversionResult.Converted)

        // …any attempt to change its content under the same identity fails.
        val attemptedEdit = management.registerRule("EUR", "USD", "2.5", 0, null, "operator:v1")
        assertTrue(attemptedEdit is FxRuleManagementService.RegisterOutcome.Rejected)
        assertEquals(
            "the used rule's stored rate is unchanged",
            "2",
            management.listRules().first { it.ruleId == firstId }.ratePrecise
        )

        // The CORRECTION path: a NEW rule with a NEW window.
        val correction = management.registerRule("EUR", "USD", "2.5", tick + 1, null, "operator:v2")
        assertTrue(correction is FxRuleManagementService.RegisterOutcome.Registered)
        assertNotNull(management.listRules().firstOrNull { it.ratePrecise == "2.5" })
    }

    // ------------------------------------------------------------------
    // The operator rate input parser (FxRateInput)
    // ------------------------------------------------------------------

    @Test
    fun `the rate parser enforces the exact-decimal discipline`() {
        assertTrue(FxRateInput.parse("1.0854") is FxRateInput.Parsed.Rate)
        assertTrue(FxRateInput.parse("0.000063900123") is FxRateInput.Parsed.Rate)
        assertTrue(FxRateInput.parse(" 2 ") is FxRateInput.Parsed.Rate)
        assertEquals("2", (FxRateInput.parse("2.0") as FxRateInput.Parsed.Rate).ratePrecise)
        assertTrue("NaN rejected", FxRateInput.parse("NaN") is FxRateInput.Parsed.Invalid)
        assertTrue("Infinity rejected", FxRateInput.parse("Infinity") is FxRateInput.Parsed.Invalid)
        assertTrue("negative rejected", FxRateInput.parse("-1.5") is FxRateInput.Parsed.Invalid)
        assertTrue("zero rejected", FxRateInput.parse("0") is FxRateInput.Parsed.Invalid)
        assertTrue("plus-prefix rejected", FxRateInput.parse("+1.5") is FxRateInput.Parsed.Invalid)
        assertTrue("commas rejected", FxRateInput.parse("1,0854") is FxRateInput.Parsed.Invalid)
        assertTrue("non-numeric rejected", FxRateInput.parse("abc") is FxRateInput.Parsed.Invalid)
        assertTrue("blank rejected", FxRateInput.parse("   ") is FxRateInput.Parsed.Invalid)
        assertTrue(
            "beyond 12 fractional digits rejected",
            FxRateInput.parse("1.0000000000001") is FxRateInput.Parsed.Invalid
        )
        assertTrue(
            "beyond the magnitude ceiling rejected",
            FxRateInput.parse("1000000000001") is FxRateInput.Parsed.Invalid
        )
    }
}
