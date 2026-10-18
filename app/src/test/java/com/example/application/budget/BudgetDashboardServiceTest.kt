package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
import kotlinx.coroutines.flow.first
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
 * STAGE 4 — BudgetDashboardService (the live monitoring feeds)
 * ============================================================================
 * The per-period balance equation recomputed from raw rows per emission
 * (never cached), the ledger/holds Flows, and calculated cost by provider
 * over the pricing chain (I-37: CALCULATED cost only — attribution
 * honesty).
 */
@RunWith(RobolectricTestRunner::class)
class BudgetDashboardServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var dashboard: BudgetDashboardService

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
        dashboard = BudgetDashboardService(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `period balances recompute the balance equation from raw rows`() = runBlocking {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-dash",
                providerId = null,
                budgetName = "Dash budget",
                currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 90 * dayMs
            )
        )
        ledger.postUsageActual(
            scopeType = "WORKSPACE", platformObjectId = "ws-dash",
            amountMicroUnits = 250_000L, reason = "spend",
            idempotencyKey = "dash-1", nowEpochMs = t0 + 10_000
        )
        // an ACTIVE hold of 100k (no expiry) — the reservation term
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO budget_hold (hold_id, budget_period_id, hold_amount_micro_units, currency, " +
                "reason, status, created_at_epoch_ms, idempotency_key) VALUES " +
                "('hold-dash','${created.periodId}',100000,'USD','dash','ACTIVE',0,'ik-dash')"
        )

        val rows = dashboard.periodBalances(nowEpochMs = t0 + 20_000).first()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(created.periodId, row.periodId)
        assertEquals(1_000_000L, row.limitMicroUnits)
        assertEquals(-250_000L, row.postedEffectsMicroUnits)
        assertEquals(100000L, row.remainingHoldMicroUnits)
        // available = limit + posted(-250k) - holds(100k) = 650k
        assertEquals(650_000L, row.availableMicroUnits())

        val utilization = dashboard.utilization(row)
        assertEquals(-0.25f, utilization.postedToLimit, 1e-6f)
        assertEquals(0.1f, utilization.holdsToLimit, 1e-6f)
        assertEquals(0.65f, utilization.availableToLimit, 1e-6f)
    }

    @Test
    fun `entries and holds flows emit the live feeds`() = runBlocking {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-dash",
                providerId = null,
                budgetName = "Dash budget",
                currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 90 * dayMs
            )
        )
        ledger.postUsageActual(
            scopeType = "WORKSPACE", platformObjectId = "ws-dash",
            amountMicroUnits = 50_000L, reason = "spend",
            idempotencyKey = "dash-2", nowEpochMs = t0 + 10_000
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO budget_hold (hold_id, budget_period_id, hold_amount_micro_units, currency, " +
                "reason, status, created_at_epoch_ms, idempotency_key) VALUES " +
                "('hold-dash2','${created.periodId}',100000,'USD','dash','ACTIVE',0,'ik-dash2')"
        )

        val entries = dashboard.entries(created.periodId).first()
        assertEquals(1, entries.size)
        assertEquals("POSTED", entries.first().postingState)

        val holds = dashboard.holds(created.periodId).first()
        assertEquals(1, holds.size)
        assertEquals("ACTIVE", holds.first().status)
    }

    @Test
    fun `cost by provider rides the pricing chain - calculated cost only`() = runBlocking {
        // the pricing chain for provider "prov-a": profile -> version ->
        // calculation -> lines (the ReconciliationServiceTest.seedCosts shape)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-dash','WORKSPACE','v1|WORKSPACE|ws-dash','hd','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-dash',0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO pricing_profile VALUES ('pp-a','prov-a','MODEL:m:-','USD','sd-dash','ACTIVE',0,0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO pricing_profile_version VALUES ('ppv-a','pp-a',1,'t','t',0,NULL,0,0,'EFFECTIVE','UNKNOWN',0,'CALCULABLE','ROUND_LINE_THEN_SUM',0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO usage_record (usage_record_id,request_attempt_id,report_source,report_version," +
                "report_status,is_final_report,recorded_at_epoch_ms) VALUES ('ur-a','att-a','PROVIDER','1','FINAL',1,0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO cost_calculation (cost_calculation_id,calculation_level,request_attempt_id,usage_record_id," +
                "pricing_profile_version_id,calculation_engine_version,rule_set_version,input_hash,currency," +
                "total_amount_micro_units,calculation_status,confidence_state,calculated_at_epoch_ms," +
                "recorded_at_epoch_ms) VALUES ('cc-a','REQUEST_ATTEMPT','att-a','ur-a','ppv-a'," +
                "'e','r','h','USD',300,'CALCULATED','DECLARED',0,0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO cost_calculation_line (line_id,cost_calculation_id,line_order,line_basis_type," +
                "final_amount_micro_units,currency,rounding_mode,line_status) VALUES " +
                "('cl-a-1','cc-a',1,'FIXED_FEE',200,'USD','HALF_UP','ACTIVE')"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO cost_calculation_line (line_id,cost_calculation_id,line_order,line_basis_type," +
                "final_amount_micro_units,currency,rounding_mode,line_status) VALUES " +
                "('cl-a-2','cc-a',2,'FIXED_FEE',100,'USD','HALF_UP','ACTIVE')"
        )

        val byProvider = dashboard.costByProvider().first()
        assertEquals(1, byProvider.size)
        val row = byProvider.first()
        assertEquals("prov-a", row.providerId)
        assertEquals(1, row.calculationCount)
        assertEquals(300L, row.calculatedCostMicroUnits)
        assertTrue("cost rows are ordered descending", byProvider == byProvider.sortedByDescending { it.calculatedCostMicroUnits })
    }
}
