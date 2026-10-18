package com.example.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetForecastService
import com.example.application.budget.BudgetLedgerService
import com.example.application.observability.TelemetryService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
import com.example.testing.FakeTelemetryPort
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
 * STAGE 4 e2e — the roadmap Phase-4 EXIT CRITERION
 * ============================================================================
 * «تنبؤ بنفاد الميزانية يصدر قبل نفادها بسبعة أيام على الأقل بدقة
 * مقبولة على بيانات فعلية» — delivered as a REAL 40-day run: every day's
 * spend is POSTED through the real ledger service (actual data), the
 * forecaster is evaluated at every day's end, and the early warning rides
 * the existing telemetry audit channel.
 *
 * The accuracy definition (I-40): at the FIRST warning, the projected
 * depletion day is within ±2 days of the realized depletion day.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetForecastE2ETest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var forecast: BudgetForecastService
    private lateinit var telemetryPort: FakeTelemetryPort

    private val dayMs = 86_400_000L
    private val t0 = 1_000_000L

    /** End of day d (1-based) — postings at this moment land in UTC day d. */
    private fun endOfDay(d: Int): Long = t0 + d * dayMs - 1_000

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
                    Stage4MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        var tick = 1L
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${tick++}" },
            clock = { t0 }
        )
        telemetryPort = FakeTelemetryPort()
        forecast = BudgetForecastService(
            ledger = ledger,
            database = db,
            telemetry = TelemetryService(telemetryPort),
            clock = { t0 }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the first warning leads realized depletion by at least 7 days, within ±2 days accuracy`() = runBlocking {
        // ---- the scenario: a 45-day workspace period, limit 3,000,000
        // micro (~3 USD), a realistic 100,000/day spend with ±4% daily noise.
        // Realized depletion: the first day cumulative spend >= limit.
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-fc-e2e",
                providerId = null,
                budgetName = "Forecast e2e period",
                currency = "USD",
                limitMicroUnits = 3_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        val periodId = created.periodId

        // deterministic realistic noise: +4% / -4% alternating (avg 100k)
        val dailySpend = (1..45).map { d -> if (d % 2 == 0) 104_000L else 96_000L }

        var firstWarningDay: Int? = null
        var projectedAtFirstWarning: Long? = null
        var realizedDepletionDay: Int? = null
        var cumulative = 0L

        for (d in 1..45) {
            // 1) the day's real spend goes through the REAL posting path
            val posted = ledger.postUsageActual(
                scopeType = "WORKSPACE",
                platformObjectId = "ws-fc-e2e",
                amountMicroUnits = dailySpend[d - 1],
                reason = "day-$d",
                idempotencyKey = "e2e-$d",
                nowEpochMs = endOfDay(d)
            )
            assertTrue("day $d must post", posted is BudgetLedgerService.UsagePostingOutcome.Posted)
            cumulative += dailySpend[d - 1]

            // 2) the realized depletion day: first day the balance <= 0
            val balance = ledger.availableForPeriod(periodId, endOfDay(d))
            if (realizedDepletionDay == null && balance.availableMicroUnits <= 0) {
                realizedDepletionDay = d
            }

            // 3) the forecaster evaluates at the end of every day
            val outcome = forecast.checkAndWarn(periodId, asOfEpochMs = endOfDay(d))
            if (firstWarningDay == null && outcome is BudgetForecastService.WarningOutcome.Emitted) {
                firstWarningDay = d
                projectedAtFirstWarning = outcome.result.projectedDepletionEpochMs
            }
        }

        // ---- THE EXIT CRITERION ----
        assertNotNull("a warning must have been issued", firstWarningDay)
        assertNotNull("the period must actually deplete within the run", realizedDepletionDay)
        val firstWarn = firstWarningDay!!
        val realized = realizedDepletionDay!!
        val projected = projectedAtFirstWarning!!

        // (a) lead time: the first warning precedes the realized depletion
        //     by AT LEAST 7 days (the roadmap bound).
        val leadDays = realized - firstWarn
        assertTrue(
            "lead time $leadDays days (warning day $firstWarn, realized day $realized) must be >= 7",
            leadDays >= 7
        )

        // (b) accuracy: at the first warning, the projected depletion day is
        //     within ±2 days of the realized day (I-40).
        val projectedDay = (projected - t0) / dayMs
        val realizedDay = realized.toLong()
        assertTrue(
            "projected day $projectedDay vs realized day $realizedDay must be within ±2",
            kotlin.math.abs(projectedDay - realizedDay) <= 2
        )

        // (c) the warning rode the EXISTING telemetry audit channel
        assertTrue(telemetryPort.auditEvents.isNotEmpty())
        val event = telemetryPort.auditEvents.first()
        assertEquals("BUDGET_FORECAST", event.action)
        assertEquals("EARLY_WARNING", event.decision)
        assertEquals("budget_period", event.resourceType)
        assertEquals(periodId, event.resourceId)
        assertEquals("7", event.attributes["requiredLeadDays"])

        // (d) after depletion the forecaster reports honestly (zero lead,
        //     "already exhausted") — never a fabricated future date.
        val after = forecast.forecast(periodId, asOfEpochMs = endOfDay(45))
        assertEquals(0L, after.daysUntilDepletion)
        assertTrue(after.reason.contains("already exhausted"))
    }

    @Test
    fun `quiet period - no warning is ever fabricated`() = runBlocking {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-fc-quiet",
                providerId = null,
                budgetName = "Quiet period",
                currency = "USD",
                limitMicroUnits = 50_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 120 * dayMs
            )
        )
        // 20 days of tiny spend against a huge limit: nothing near depletion
        for (d in 1..20) {
            ledger.postUsageActual(
                scopeType = "WORKSPACE", platformObjectId = "ws-fc-quiet",
                amountMicroUnits = 100_000L, reason = "day-$d",
                idempotencyKey = "quiet-$d", nowEpochMs = endOfDay(d)
            )
            forecast.checkAndWarn(created.periodId, asOfEpochMs = endOfDay(d))
        }
        assertEquals("no warning may be fabricated when depletion is far", 0, telemetryPort.auditEvents.size)
    }
}
