package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
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
 * STAGE 4 — BudgetActualsFeed (the decision-engine bridge, Circle 1)
 * ============================================================================
 * The REAL balance-equation state as context features: available ratio,
 * exhaustion flag, and MONETARY credits only (I-39: money never enters the
 * feature vector as an absolute Float; quantity/provider-credit remainings
 * are not money).
 */
@RunWith(RobolectricTestRunner::class)
class BudgetActualsFeedTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var entitlements: EntitlementService
    private lateinit var feed: BudgetActualsFeed

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
        entitlements = EntitlementService(database = db, clock = { t0 })
        feed = BudgetActualsFeed(
            ledger = ledger,
            entitlements = entitlements,
            database = db,
            clock = { t0 }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `no active policy - features are honestly ABSENT, never zero-filled`() = runBlocking {
        val features = feed.featuresFor(
            workspaceObjectId = "ws-none",
            agentObjectId = "ag-none",
            taskObjectId = null,
            nowEpochMs = t0
        )
        assertTrue("no policy anywhere -> empty map", features.isEmpty())
    }

    @Test
    fun `with an active policy the feed carries the balance-equation ratios`() = runBlocking {
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-fd",
                providerId = null,
                budgetName = "Feed budget",
                currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 90 * dayMs
            )
        )
        // 25% of the period spent
        ledger.postUsageActual(
            scopeType = "WORKSPACE", platformObjectId = "ws-fd",
            amountMicroUnits = 250_000L, reason = "spend",
            idempotencyKey = "fd-1", nowEpochMs = t0 + 10_000
        )
        val features = feed.featuresFor(
            workspaceObjectId = "ws-fd",
            agentObjectId = "ag-x",
            taskObjectId = null,
            nowEpochMs = t0 + 20_000
        )
        assertEquals(1f, features[BudgetActualsFeed.Keys.HAS_ACTIVE_POLICY])
        assertEquals(0.75f, features[BudgetActualsFeed.Keys.AVAILABLE_RATIO]!!, 1e-6f)
        assertEquals(0f, features[BudgetActualsFeed.Keys.EXHAUSTED]!!)
        assertEquals(0.75f, features[BudgetActualsFeed.Keys.EFFECTIVE_AVAILABLE_RATIO]!!, 1e-6f)
    }

    @Test
    fun `monetary credits raise the effective ratio - quantities never do`() = runBlocking {
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-fd",
                providerId = null,
                budgetName = "Feed budget",
                currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 90 * dayMs
            )
        )
        val scopeId = ledger.scopeIdFor("WORKSPACE", "ws-fd", t0)!!

        // a monetary credit worth 20% of the limit
        entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov-fd",
                applicabilityScopeId = scopeId,
                entitlementType = "PROMO",
                valueKind = EntitlementService.VALUE_MONETARY_CREDIT,
                monetaryLimitMicroUnits = 200_000L,
                currency = "USD"
            )
        )
        // a QUANTITY entitlement (tokens) — not money, must NOT count.
        // unitId rides the SEEDED reference row (Stage-2 SEEDS, UNIT_TOKEN).
        entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov-fd",
                applicabilityScopeId = scopeId,
                entitlementType = "FREE_TIER",
                valueKind = EntitlementService.VALUE_QUANTITY_LIMIT,
                quantityLimitPrecise = "1000000",
                unitId = "UNIT_TOKEN"
            )
        )
        val features = feed.featuresFor(
            workspaceObjectId = "ws-fd", agentObjectId = null, taskObjectId = null,
            nowEpochMs = t0 + 20_000
        )
        assertEquals(0.2f, features[BudgetActualsFeed.Keys.CREDITS_RATIO]!!, 1e-6f)
        assertEquals(1.2f, features[BudgetActualsFeed.Keys.EFFECTIVE_AVAILABLE_RATIO]!!, 1e-6f)
    }

    @Test
    fun `exhaustion is flagged honestly`() = runBlocking {
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-fd",
                providerId = null,
                budgetName = "Feed budget",
                currency = "USD",
                limitMicroUnits = 100_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 90 * dayMs
            )
        )
        ledger.postUsageActual(
            scopeType = "WORKSPACE", platformObjectId = "ws-fd",
            amountMicroUnits = 150_000L, reason = "overspend",
            idempotencyKey = "fd-over", nowEpochMs = t0 + 10_000
        )
        val features = feed.featuresFor(
            workspaceObjectId = "ws-fd", agentObjectId = null, taskObjectId = null,
            nowEpochMs = t0 + 20_000
        )
        assertEquals(1f, features[BudgetActualsFeed.Keys.EXHAUSTED]!!)
        assertTrue(features[BudgetActualsFeed.Keys.AVAILABLE_RATIO]!! < 0f)
    }
}
