package com.example.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetForecastService
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.RationalizationService
import com.example.application.observability.TelemetryService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
import com.example.infrastructure.persistence.Stage5MigrationSql
import com.example.testing.FakeTelemetryPort
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
 * STAGE 5 e2e #2 — the layered Phase-4 intelligence on ONE real run
 * ============================================================================
 * A 30-day real ledger run (every day's spend POSTED through the real
 * service) drives the WHOLE intelligence stack at once:
 *   * Stage 4's forecaster (the first increment),
 *   * Stage 5's rationalization (this increment): an under-utilization
 *     recommendation with the exact numbers, emitted over the existing
 *     telemetry audit channel,
 *   * a mid-run policy version bump — G05's row-per-version while the run
 *     is live (the old version keeps serving its own period untouched).
 * Plus the honesty arms: the provider comparison stays silent on an empty
 * registry (INSUFFICIENT_DATA, never a guess).
 */
@RunWith(RobolectricTestRunner::class)
class RationalizationE2ETest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var forecast: BudgetForecastService
    private lateinit var rational: RationalizationService
    private lateinit var telemetryPort: FakeTelemetryPort

    private val dayMs = 86_400_000L
    private val t0 = 1_000_000L

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
                    Stage5MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        var tick = 1L
        ledger = BudgetLedgerService(database = db, idGenerator = { "id-${tick++}" }, clock = { t0 })
        telemetryPort = FakeTelemetryPort()
        forecast = BudgetForecastService(
            ledger = ledger, database = db,
            telemetry = TelemetryService(telemetryPort), clock = { t0 }
        )
        rational = RationalizationService(database = db, telemetry = telemetryPort, clock = { t0 })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a 30-day low-utilization run layers the forecast and the rationalization`() = runBlocking {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE",
                platformObjectId = "ws-rat-e2e", providerId = null,
                budgetName = "Rationalization e2e", currency = "USD",
                limitMicroUnits = 10_000_000L, // ~10 USD
                periodStartEpochMs = t0, periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        val periodId = created.periodId

        // ---- the run: 30 days, 100k/day = 3M of a 10M limit (30%) ----
        for (d in 1..30) {
            val posted = ledger.postUsageActual(
                scopeType = "WORKSPACE", platformObjectId = "ws-rat-e2e",
                amountMicroUnits = 100_000L, reason = "day-$d",
                idempotencyKey = "e2e-rat-$d", nowEpochMs = endOfDay(d)
            )
            assertTrue(posted is BudgetLedgerService.UsagePostingOutcome.Posted)

            // the forecaster evaluates every day (the Stage-4 increment)
            forecast.checkAndWarn(periodId, asOfEpochMs = endOfDay(d))
        }

        // ---- AFTER the run: the policy version bump (G05 row-per-version) ----
        ledger.createPolicyVersion(
            familyId = created.policyFamilyId,
            updatedRequest = BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE",
                platformObjectId = "ws-rat-e2e", providerId = null,
                budgetName = "Rationalization e2e v2", currency = "USD",
                limitMicroUnits = 12_000_000L,
                periodStartEpochMs = t0 + 60 * dayMs,
                periodEndEpochMs = t0 + 120 * dayMs
            )
        )
        // the OLD period keeps pointing at the OLD version (stability across
        // the bump — the roadmap's «old periods stable» acceptance test)
        val oldPeriod = db.budgetPeriodDao().byId(periodId)
        assertEquals(created.policyId, oldPeriod!!.budgetPolicyId)
        assertEquals(10_000_000L, oldPeriod.limitMicroUnits)
        val v1Row = db.budgetPolicyDao().byId(created.policyId)!!
        assertEquals("RETIRED", v1Row.policyStatus)
        // and the old version's substance is untouched by the bump
        assertEquals("Rationalization e2e", v1Row.budgetName)
        assertEquals(10_000_000L, v1Row.defaultLimitMicroUnits)

        // ---- the rationalization pass over the SAME run ----
        val recs = rational.underUtilization(nowEpochMs = endOfDay(30))
        assertEquals(1, recs.size)
        val rec = recs.single()
        assertEquals(RationalizationService.Vocab.KIND_UNDER_UTILIZATION, rec.kind)
        assertEquals(periodId, rec.resourceId)
        assertEquals("3000000", rec.evidence["postedConsumptionMicroUnits"])
        assertEquals("10000000", rec.evidence["limitMicroUnits"])
        assertEquals("30", rec.evidence["distinctPostingDays"])
        assertTrue(rec.evidence["utilization"]!!.startsWith("0.3"))
        assertTrue(rec.headline.contains("under-consumed"))

        // ---- the provider comparison on an EMPTY registry: honest silence ----
        assertTrue("empty registry - INSUFFICIENT_DATA, never a guess", rational.providerComparison().isEmpty())

        // ---- the reconciliation quality on NO data: honest silence ----
        assertTrue(rational.reconciliationQuality().isEmpty())

        // ---- one pass emits the whole family over the audit channel ----
        telemetryPort.auditEvents.clear()
        val ids = rational.checkAndRecommend(nowEpochMs = endOfDay(30))
        assertEquals(1, ids.size)
        assertEquals(1, telemetryPort.auditEvents.size)
        val event = telemetryPort.auditEvents.single()
        assertEquals("RATIONALIZATION", event.action)
        assertEquals("UNDER_UTILIZATION", event.decision)
        assertEquals("budget_period", event.resourceType)
        assertEquals(periodId, event.resourceId)
        assertEquals("3000000", event.attributes["postedConsumptionMicroUnits"])
        assertEquals("rationalization:v1", event.attributes["policyVersion"])

        // ---- the forecast, over the same run, stayed honestly silent
        //      (no projection fabricates a depletion day at 30% utilization;
        //      the audit channel carries ONLY the rationalization event) ----
        val forecastEvents = telemetryPort.auditEvents.filter { it.action == "BUDGET_FORECAST" }
        assertTrue(forecastEvents.isEmpty())
    }
}
