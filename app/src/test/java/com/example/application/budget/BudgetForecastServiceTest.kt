package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * STAGE 4 — BudgetForecastService (the budget-depletion forecast, Circle 2)
 * ============================================================================
 * The WMA math, the honesty rules (insufficient data, zero rate, already
 * exhausted), and the early-warning channel over the existing telemetry
 * audit path (roadmap: «دون بناء جديد»).
 */
@RunWith(RobolectricTestRunner::class)
class BudgetForecastServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var forecast: BudgetForecastService
    private lateinit var telemetryPort: FakeTelemetryPort

    private val dayMs = 86_400_000L
    private val t0 = 1_000_000L

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

    /** End-of-day-d epoch (1-based): posting at this moment lands in UTC day d. */
    private fun endOfDay(d: Int): Long = t0 + d * dayMs - 1_000

    private suspend fun createPeriod(limitMicro: Long): String {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-fc",
                providerId = null,
                budgetName = "Forecast period",
                currency = "USD",
                limitMicroUnits = limitMicro,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 90 * dayMs
            )
        )
        return created.periodId
    }

    private suspend fun postDay(periodId: String, d: Int, amountMicro: Long) {
        val outcome = ledger.postUsageActual(
            scopeType = "WORKSPACE",
            platformObjectId = "ws-fc",
            amountMicroUnits = amountMicro,
            reason = "spend-$d",
            idempotencyKey = "fc-$d",
            nowEpochMs = endOfDay(d)
        )
        assertTrue("day $d posts", outcome is BudgetLedgerService.UsagePostingOutcome.Posted)
    }

    // ------------------------------------------------------------------
    // Honesty rules first
    // ------------------------------------------------------------------

    @Test
    fun `insufficient data - no projection is ever guessed`() = runBlocking {
        val periodId = createPeriod(3_000_000L)
        postDay(periodId, 1, 100_000L)
        postDay(periodId, 2, 100_000L) // 2 posting days < MIN_DATA_DAYS(3)

        val result = forecast.forecast(periodId, asOfEpochMs = endOfDay(2))
        assertEquals(BudgetForecastService.Confidence.INSUFFICIENT_DATA, result.confidence)
        assertEquals(null, result.projectedDepletionEpochMs)
        assertTrue(result.reason.contains("insufficient"))
    }

    @Test
    fun `net-negative window - depletion is not projectable`() = runBlocking {
        val periodId = createPeriod(3_000_000L)
        // three spend days, then a large CREDIT adjustment on day 3:
        // the window's weighted burn goes NEGATIVE — no depletion projectable.
        postDay(periodId, 1, 100_000L)
        postDay(periodId, 2, 100_000L)
        postDay(periodId, 3, 100_000L)
        ledger.postAdjustment(
            periodId = periodId,
            effectMicroUnits = 400_000L, // positive effect: a credit
            currency = "USD",
            entryBasis = BudgetLedgerService.BASIS_ACTUAL,
            reason = "promo-credit",
            idempotencyKey = "fc-credit",
            nowEpochMs = endOfDay(3)
        )
        val result = forecast.forecast(periodId, asOfEpochMs = endOfDay(3))
        assertEquals(null, result.projectedDepletionEpochMs)
        assertTrue(result.reason.contains("no positive weighted burn"))
    }

    @Test
    fun `already exhausted - reported honestly as zero lead`() = runBlocking {
        val periodId = createPeriod(300_000L)
        postDay(periodId, 1, 100_000L)
        postDay(periodId, 2, 100_000L)
        postDay(periodId, 3, 100_000L)
        postDay(periodId, 4, 100_000L) // cumulative 400k > limit 300k
        val result = forecast.forecast(periodId, asOfEpochMs = endOfDay(4))
        assertEquals(0L, result.daysUntilDepletion)
        assertTrue(result.availableNowMicroUnits < 0)
        assertTrue(result.reason.contains("already exhausted"))
    }

    // ------------------------------------------------------------------
    // The WMA math (I-38: calendar-day series; gap days burn zero)
    // ------------------------------------------------------------------

    @Test
    fun `weighted moving average over a known series`() = runBlocking {
        val periodId = createPeriod(1_000_000L)
        // burns 100k, 200k, 300k MICRO on days 1..3; weights 1,2,3
        // WMA = (100k*1 + 200k*2 + 300k*3) / 6 = 1,400k/6 = 233,333 micro (HALF_UP)
        postDay(periodId, 1, 100_000L)
        postDay(periodId, 2, 200_000L)
        postDay(periodId, 3, 300_000L)
        val result = forecast.forecast(periodId, asOfEpochMs = endOfDay(3))
        assertEquals(233_333L, result.weightedDailyBurnMicroUnits)
        // available = 1,000,000 - 600,000 = 400,000; days = ceil(400k/233333) = 2
        assertEquals(2L, result.daysUntilDepletion)
        // series carries the calendar days with weights
        assertEquals(3, result.series.size)
        assertEquals(100_000L, result.series[0].burnMicroUnits)
        assertEquals(300_000L, result.series[2].burnMicroUnits)
        assertEquals(3, result.series[2].weight)
    }

    @Test
    fun `gap days contribute zero burn - a silent week lowers the average`() = runBlocking {
        val periodId = createPeriod(10_000_000L)
        // postings on days 1,2 then silence days 3..8, then day 9
        postDay(periodId, 1, 300_000L)
        postDay(periodId, 2, 300_000L)
        postDay(periodId, 9, 300_000L)
        val result = forecast.forecast(periodId, asOfEpochMs = endOfDay(9))
        // window = days 1..9 (9 calendar days): burns in MICRO
        // [300k,300k,0,0,0,0,0,0,300k] weights 1..9
        // sum(w*b) = 300k*1 + 300k*2 + 300k*9 = 300k*12 = 3,600k; sum(w) = 45
        // WMA = 3,600k/45 = 80,000 micro
        assertEquals(80_000L, result.weightedDailyBurnMicroUnits)
        assertEquals(9, result.windowCalendarDays)
        assertEquals(9, result.series.size)
        assertEquals(0L, result.series[4].burnMicroUnits)
    }

    // ------------------------------------------------------------------
    // The early-warning channel (existing telemetry audit path)
    // ------------------------------------------------------------------

    @Test
    fun `checkAndWarn emits an EARLY_WARNING audit event inside the horizon`() = runBlocking {
        val periodId = createPeriod(1_000_000L)
        // 10 flat days of 100k: available 0 at day 10 -> daysUntil at day 4 = 6 <= 14
        for (d in 1..4) postDay(periodId, d, 100_000L)
        val outcome = forecast.checkAndWarn(periodId, asOfEpochMs = endOfDay(4))
        assertTrue("emitted: ${(outcome as? BudgetForecastService.WarningOutcome.Suppressed)?.why}", outcome is BudgetForecastService.WarningOutcome.Emitted)

        assertEquals(1, telemetryPort.auditEvents.size)
        val event = telemetryPort.auditEvents.first()
        assertEquals("BUDGET_FORECAST", event.action)
        assertEquals("EARLY_WARNING", event.decision)
        assertEquals("budget_period", event.resourceType)
        assertEquals(periodId, event.resourceId)
        assertEquals("6", event.attributes["daysUntilDepletion"])
        assertEquals("7", event.attributes["requiredLeadDays"])
        assertNotNull(event.attributes["projectedDepletionEpochMs"])
    }

    @Test
    fun `checkAndWarn stays silent outside the horizon`() = runBlocking {
        val periodId = createPeriod(100_000_000L)
        for (d in 1..3) postDay(periodId, d, 100_000L)
        // available ~ 99.7M, rate 100k -> ~997 days out: far outside 14
        val outcome = forecast.checkAndWarn(periodId, asOfEpochMs = endOfDay(3))
        assertTrue(outcome is BudgetForecastService.WarningOutcome.Suppressed)
        assertTrue((outcome as BudgetForecastService.WarningOutcome.Suppressed).why.contains("outside"))
        assertEquals(0, telemetryPort.auditEvents.size)
    }

    @Test
    fun `suppressed when nothing is projectable`() = runBlocking {
        val periodId = createPeriod(1_000_000L)
        postDay(periodId, 1, 100_000L) // only 1 day -> INSUFFICIENT_DATA
        val outcome = forecast.checkAndWarn(periodId, asOfEpochMs = endOfDay(1))
        assertTrue(outcome is BudgetForecastService.WarningOutcome.Suppressed)
        assertEquals(0, telemetryPort.auditEvents.size)
    }
}
