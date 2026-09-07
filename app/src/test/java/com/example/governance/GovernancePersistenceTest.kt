package com.example.governance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.BudgetPolicy
import com.example.domain.core.budget.BudgetPolicyAction
import com.example.domain.core.budget.BudgetScope
import com.example.domain.core.budget.BudgetScopeType
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.radar.CapabilityEvaluationDimensions
import com.example.domain.core.radar.CapabilityEvidence
import com.example.domain.core.radar.CapabilityHealth
import com.example.domain.core.radar.CapabilityTrend
import com.example.domain.core.radar.EvidenceOutcome
import com.example.domain.core.radar.EvidenceSource
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import com.example.infrastructure.persistence.radar.RoomCapabilityRadarStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * GovernancePersistenceTest (Robolectric + in-memory Room)
 * ============================================================================
 *
 * GOVERNANCE PHASE: proves the Room v10 persistence layer round-trips the
 * radar + economic domain models EXACTLY (UNKNOWNs stay null/UNKNOWN, enum
 * names survive, scope aggregations match the production SQL semantics).
 * This is the "persistence/restart" requirement of mission Section 13.
 */
@RunWith(RobolectricTestRunner::class)
class GovernancePersistenceTest {

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var radarStore: RoomCapabilityRadarStore
    private lateinit var economicStore: RoomEconomicStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        radarStore = RoomCapabilityRadarStore(
            evidenceDao = db.capabilityEvidenceDao(),
            stateDao = db.radarCapabilityStateDao(),
            changeDao = db.capabilityChangeDao(),
            recommendationDao = db.radarRecommendationDao()
        )
        economicStore = RoomEconomicStore(
            pricingDao = db.pricingEntryDao(),
            ledgerDao = db.costLedgerEntryDao(),
            allocationDao = db.budgetAllocationDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------- RADAR ----------------

    @Test
    fun `evidence round-trips with full attribution`() = runBlocking {
        val evidence = CapabilityEvidence(
            id = "ev-1",
            capabilityKey = CapabilityType.LLM_GENERATION.code,
            source = EvidenceSource.ACTION_EXECUTION,
            outcome = EvidenceOutcome.SUCCESS,
            timestampEpochMs = 123L,
            confidence = 0.9f,
            providerId = "prov",
            serviceId = "svc",
            modelId = "model",
            resourceId = "res",
            executionId = "exec",
            workspaceId = "ws",
            agentId = "agent",
            detail = "نجاح"
        )
        radarStore.insertEvidence(evidence)
        val loaded = radarStore.recentEvidenceForCapability(CapabilityType.LLM_GENERATION.code, "ws", 10)
        assertEquals(1, loaded.size)
        assertEquals(evidence, loaded.first())
    }

    @Test
    fun `capability status round-trips including null workspace and dimensions`() = runBlocking {
        val status = RadarCapabilityStatus(
            capabilityKey = CapabilityType.EMBEDDING.code,
            workspaceId = null, // global scope row
            state = OperationalCapabilityState.PARTIAL,
            dimensions = CapabilityEvaluationDimensions(
                declared = true, implemented = true, configured = null,
                provisioned = false, dependencyAvailable = true, runtimeAvailable = null,
                runtimeValidated = false, resourceUsable = null, policyAllowed = true,
                healthConfirmed = null, uiExposed = true, evidenceFresh = null
            ),
            health = CapabilityHealth.UNKNOWN,
            trend = CapabilityTrend.UNKNOWN,
            evidenceCount = 0,
            lastEvidenceEpochMs = null,
            contributingResourceIds = listOf("res-1"),
            rationale = "مطبقة دون مورد مهيّأ",
            derivedAtEpochMs = 42L
        )
        radarStore.upsertCapabilityStatus(status)
        val loaded = radarStore.getCapabilityStatus(CapabilityType.EMBEDDING.code, null)
        assertNotNull(loaded)
        assertEquals(status, loaded)
        // Unknown dimensions stay null — never flipped to defaults.
        assertEquals(null, loaded!!.dimensions.configured)
        assertEquals(false, loaded.dimensions.runtimeValidated)
    }

    @Test
    fun `workspace isolation in radar state queries`() = runBlocking {
        val forWs1 = RadarCapabilityStatus(
            capabilityKey = "llm_generation",
            workspaceId = "ws-1",
            state = OperationalCapabilityState.AVAILABLE,
            dimensions = CapabilityEvaluationDimensions.NONE,
            health = CapabilityHealth.HEALTHY,
            trend = CapabilityTrend.STABLE,
            evidenceCount = 1,
            lastEvidenceEpochMs = 1L,
            contributingResourceIds = emptyList(),
            rationale = "r",
            derivedAtEpochMs = 1L
        )
        radarStore.upsertCapabilityStatus(forWs1)
        val ws1 = radarStore.capabilityStatusesForWorkspace("ws-1")
        val ws2 = radarStore.capabilityStatusesForWorkspace("ws-2")
        assertEquals(1, ws1.size)
        assertEquals(0, ws2.size)
    }

    // ---------------- ECONOMIC ----------------

    @Test
    fun `pricing entry round-trips with unknown prices preserved`() = runBlocking {
        val entry = PricingEntry(
            id = "price-1",
            scope = PricingScope.MODEL,
            providerId = "prov",
            serviceId = "svc",
            modelId = "model-x",
            inputPricePerMillion = MoneyAmount.of(500_000L, "USD"),
            outputPricePerMillion = null, // UNKNOWN at this scope
            cachedInputPricePerMillion = null,
            billingClass = BillingClass.PAID,
            pricingVersion = "v1",
            effectiveFromEpochMs = 100L,
            provenance = "TEST"
        )
        economicStore.upsertPricing(entry)
        val loaded = economicStore.pricingById("price-1")
        assertNotNull(loaded)
        assertEquals(500_000L, loaded!!.inputPricePerMillion?.amountMicro)
        assertNull(loaded.outputPricePerMillion) // UNKNOWN stays UNKNOWN
        assertEquals(PricingScope.MODEL, loaded.scope)
        assertEquals(BillingClass.PAID, loaded.billingClass)
    }

    @Test
    fun `ledger record round-trips and scopes aggregate correctly`() = runBlocking {
        suspend fun record(
            execId: String, wsId: String?, agent: String?, cost: MoneyAmount?, status: CostStatus, tokens: Int
        ) = economicStore.insert(
            UsageCostRecord(
                id = "clr-$execId",
                executionId = execId,
                taskId = null,
                workspaceId = wsId,
                agentId = agent,
                providerId = "prov",
                serviceId = null,
                modelId = null,
                resourceId = null,
                usage = TokenUsageRecord(inputTokens = tokens, outputTokens = 0),
                appliedPricing = null,
                cost = cost,
                costStatus = status,
                billingClass = BillingClass.PAID,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
        record("e1", "ws-1", "a1", MoneyAmount.of(100_000L, "USD"), CostStatus.ACTUAL, 1000)
        record("e2", "ws-1", "a1", MoneyAmount.of(50_000L, "USD"), CostStatus.ACTUAL, 2000)
        record("e3", "ws-2", "a2", MoneyAmount.of(900_000L, "USD"), CostStatus.ACTUAL, 5000)
        record("e4", "ws-1", "a1", MoneyAmount.unknown("USD"), CostStatus.UNKNOWN, 100)

        val ws1 = economicStore.consumedCostForScope(BudgetScopeType.WORKSPACE, "ws-1", "USD")
        assertEquals(150_000L, ws1.totalMicro)
        assertEquals(3, ws1.recordCount)
        assertTrue(ws1.hadUnknownCost)

        val ws2 = economicStore.consumedCostForScope(BudgetScopeType.WORKSPACE, "ws-2", "USD")
        assertEquals(900_000L, ws2.totalMicro)

        val agent1 = economicStore.consumedCostForScope(BudgetScopeType.AGENT, "a1", "USD")
        assertEquals(150_000L, agent1.totalMicro)

        val tokensWs1 = economicStore.tokensConsumedForScope(BudgetScopeType.WORKSPACE, "ws-1")
        assertEquals(3_100L, tokensWs1)
    }

    @Test
    fun `budget allocation upsert and policy round-trip`() = runBlocking {
        val scope = BudgetScope(BudgetScopeType.WORKSPACE, "ws-1")
        economicStore.upsertAllocation(
            com.example.domain.core.budget.BudgetAllocation(
                scope = scope,
                allocated = MoneyAmount.of(250_000L, "USD"),
                policy = BudgetPolicy(
                    actions = listOf(BudgetPolicyAction.HARD_LIMIT, BudgetPolicyAction.AUTO_LOCAL_FALLBACK),
                    warnThresholdRatio = 0.75f
                ),
                isActive = true,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L
            )
        )
        val loaded = economicStore.allocationFor(scope)
        assertNotNull(loaded)
        assertEquals(250_000L, loaded!!.allocated.amountMicro)
        assertEquals(listOf(BudgetPolicyAction.HARD_LIMIT, BudgetPolicyAction.AUTO_LOCAL_FALLBACK), loaded.policy.actions)
        assertEquals(0.75f, loaded.policy.warnThresholdRatio)

        economicStore.deactivateAllocation(scope)
        assertNull(economicStore.allocationFor(scope))
    }

    @Test
    fun `db version is 11 with all governance + execution-kernel tables`() {
        // Gap-closure: v11 adds action_intents + agent_definitions + the
        // tasks.executionContextJson column (canonical execution kernel).
        val dbVersion = db.openHelper.writableDatabase.version
        assertEquals(11, dbVersion)
        val tables = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        listOf(
            "capability_evidence", "radar_capability_states", "capability_changes",
            "radar_recommendations", "pricing_entries", "cost_ledger_entries", "budget_allocations",
            "action_intents", "agent_definitions"
        ).forEach { tableName ->
            assertTrue("missing table: $tableName", tables.contains(tableName))
        }
        db.openHelper.readableDatabase.query(
            "PRAGMA table_info(tasks)"
        ).use { cursor ->
            var hasExecutionContext = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "executionContextJson") hasExecutionContext = true
            }
            assertTrue("tasks.executionContextJson column must exist (canonical context)", hasExecutionContext)
        }
    }
}
