package com.example.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetDashboardService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import com.example.infrastructure.persistence.entities.BudgetPeriodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * BudgetViewModelTest — STAGE 7 (wiring the accounting core to the UI)
 * ============================================================================
 *
 * The Stage-1..6 budget intelligence system (v23..v28) shipped with no UI
 * consumer; this suite pins the ViewModel that becomes one:
 *
 *  - the period balance rows flow from the REAL Room dashboard query with
 *    the live equation (limit + posted − remaining holds = available);
 *  - the first period auto-selects (the user lands on real data, not an
 *    empty pane beside it);
 *  - selecting a period drives the ledger + holds feeds of THAT period;
 *  - the utilization derivation: posted/limit and holds/limit, with the
 *    zero-limit period rendering NaN (a monitoring signal, not a division);
 *  - cost-by-provider flows from the pricing-chain query (empty when no
 *    calculations exist — honest, never zeros-that-mean-nothing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BudgetViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: BudgetViewModel

    /** A fixed "now" INSIDE the seeded period (deterministic snapshots). */
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = BudgetViewModel(
            dashboardService = BudgetDashboardService(database = db),
            nowEpochMs = { now }
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedPeriod(
        periodId: String,
        limitMicro: Long,
        postedMicro: Long,
        holdMicro: Long
    ) {
        // The FK chain: scope_descriptor → budget_policy → budget_period →
        // (ledger entries / holds). Seeded bottom-up exactly as the real
        // services write it.
        db.scopeDescriptorDao().insert(
            com.example.infrastructure.persistence.entities.ScopeDescriptorEntity(
                scopeId = "scope_$periodId",
                scopeKind = "WORKSPACE",
                canonicalRepresentation = "test|$periodId",
                canonicalHash = "hash_$periodId",
                hashAlgorithm = "SHA-256",
                canonicalizationVersion = 1,
                providerDimensionState = "UNKNOWN",
                providerId = null,
                integrationDimensionState = "UNKNOWN",
                integrationId = null,
                accessContextDimensionState = "UNKNOWN",
                accessContextId = null,
                providerScopeNodeDimensionState = "UNKNOWN",
                providerScopeNodeId = null,
                offeringDimensionState = "UNKNOWN",
                offeringId = null,
                requestAttemptDimensionState = "UNKNOWN",
                requestAttemptId = null,
                budgetPolicyDimensionState = "UNKNOWN",
                budgetPolicyId = null,
                budgetPeriodDimensionState = "UNKNOWN",
                budgetPeriodId = null,
                regionDimensionState = "UNKNOWN",
                region = null,
                endpointDimensionState = "UNKNOWN",
                endpointRef = null,
                periodDimensionState = "UNKNOWN",
                periodStart = null,
                periodEnd = null,
                platformDimensionState = "UNKNOWN",
                platformObjectType = null,
                platformObjectId = null,
                createdAtEpochMs = now - 86_400_000L
            )
        )
        db.budgetPolicyDao().insert(
            com.example.infrastructure.persistence.entities.BudgetPolicyEntity(
                budgetPolicyId = "pol_$periodId",
                policyFamilyId = "fam_$periodId",
                budgetName = "ميزانية $periodId",
                budgetScopeType = "WORKSPACE",
                scopeId = "scope_$periodId",
                currency = "USD",
                defaultLimitMicroUnits = limitMicro,
                periodType = "MONTHLY",
                rolloverPolicy = "NO_ROLLOVER",
                overagePolicy = "HARD_STOP",
                allocationPolicy = "SINGLE_PERIOD",
                policyVersion = 1,
                effectiveFromEpochMs = now - 86_400_000L,
                policyStatus = "ACTIVE",
                createdAtEpochMs = now - 86_400_000L,
                updatedAtEpochMs = now - 86_400_000L
            )
        )
        db.budgetPeriodDao().insert(
            BudgetPeriodEntity(
                budgetPeriodId = periodId,
                budgetPolicyId = "pol_$periodId",
                periodStartEpochMs = now - 86_400_000L,
                periodEndEpochMs = now + 86_400_000L,
                limitMicroUnits = limitMicro,
                periodStatus = "OPEN",
                createdAtEpochMs = now - 86_400_000L,
                updatedAtEpochMs = now - 86_400_000L
            )
        )
        if (postedMicro != 0L) {
            db.budgetLedgerEntryDao().insert(
                BudgetLedgerEntryEntity(
                    entryId = "entry_$periodId",
                    budgetPeriodId = periodId,
                    entryType = "CONSUMPTION",
                    postingState = "POSTED",
                    availableEffectMicroUnits = postedMicro,
                    entryBasis = "ESTIMATE",
                    currency = "USD",
                    originalAmountPrecise = null,
                    originalCurrency = null,
                    conversionRuleId = null,
                    entryTimestampEpochMs = now,
                    postedAtEpochMs = now,
                    costCalculationLineId = null,
                    usageRecordId = null,
                    reconciliationRecordId = null,
                    holdId = null,
                    reversalOfEntryId = null,
                    allocationGroupId = null,
                    allocationRole = null,
                    idempotencyKey = "idem_entry_$periodId"
                )
            )
        }
        if (holdMicro != 0L) {
            db.budgetHoldDao().insert(
                BudgetHoldEntity(
                    holdId = "hold_$periodId",
                    budgetPeriodId = periodId,
                    holdAmountMicroUnits = holdMicro,
                    currency = "USD",
                    reason = "اختبار الحجز",
                    requestOperationId = null,
                    status = "ACTIVE",
                    createdAtEpochMs = now,
                    expiresAtEpochMs = null,
                    idempotencyKey = "idem_hold_$periodId"
                )
            )
        }
    }

    @Test
    fun `period balances flow with the live equation and auto-select the first period`() = runBlocking {
        seedPeriod("p1", limitMicro = 1_000_000_000L, postedMicro = -250_000_000L, holdMicro = 100_000_000L)
        seedPeriod("p2", limitMicro = 2_000_000_000L, postedMicro = -500_000_000L, holdMicro = 0L)

        // WhileSubscribed(5s) — the production Compose collector keeps these
        // alive; the test holds standing collectors for the same reason.
        val collectors = listOf(
            launch { viewModel.periodBalances.collect {} },
            launch { viewModel.ledgerEntries.collect {} },
            launch { viewModel.holds.collect {} },
            launch { viewModel.costByProvider.collect {} }
        )
        withTimeout(10_000) {
            while (viewModel.periodBalances.value.size < 2 ||
                viewModel.state.value.selectedPeriodId == null
            ) {
                delay(10)
            }
        }

        val balances = viewModel.periodBalances.value
        assertEquals(listOf("p1", "p2"), balances.map { it.periodId })

        val p1 = balances.first { it.periodId == "p1" }
        // available = limit + ΣPOSTED − Σremaining_holds (و.2).
        assertEquals(1_000_000_000L, p1.limitMicroUnits)
        assertEquals(-250_000_000L, p1.postedEffectsMicroUnits)
        assertEquals(100_000_000L, p1.remainingHoldMicroUnits)
        assertEquals(650_000_000L, p1.availableMicroUnits())

        // Auto-selection: the first period's feeds are live.
        assertEquals("p1", viewModel.state.value.selectedPeriodId)
        withTimeout(10_000) {
            while (viewModel.ledgerEntries.value.isEmpty() || viewModel.holds.value.isEmpty()) {
                delay(10)
            }
        }
        assertEquals(1, viewModel.ledgerEntries.value.size)
        assertEquals(1, viewModel.holds.value.size)
        collectors.forEach { it.cancel() }

        // Utilization: posted −25% of limit, holds 10% of limit.
        val utilization = viewModel.utilization(p1)
        assertEquals(-0.25f, utilization.postedToLimit, 0.001f)
        assertEquals(0.10f, utilization.holdsToLimit, 0.001f)
    }

    @Test
    fun `selecting a period switches its ledger and holds feeds`() = runBlocking {
        seedPeriod("pA", limitMicro = 1_000_000L, postedMicro = -100_000L, holdMicro = 50_000L)
        seedPeriod("pB", limitMicro = 3_000_000L, postedMicro = -300_000L, holdMicro = 0L)

        val collectors = listOf(
            launch { viewModel.periodBalances.collect {} },
            launch { viewModel.ledgerEntries.collect {} },
            launch { viewModel.holds.collect {} }
        )
        withTimeout(10_000) {
            while (viewModel.periodBalances.value.size < 2 ||
                viewModel.state.value.selectedPeriodId == null
            ) {
                delay(10)
            }
        }
        viewModel.selectPeriod("pB")
        withTimeout(10_000) {
            while (viewModel.ledgerEntries.value.none { it.budgetPeriodId == "pB" }) {
                delay(10)
            }
        }
        assertTrue(viewModel.ledgerEntries.value.all { it.budgetPeriodId == "pB" })
        assertTrue(viewModel.holds.value.all { it.budgetPeriodId == "pB" })
        collectors.forEach { it.cancel() }
    }

    @Test
    fun `a zero-limit period renders NaN utilization - a monitoring signal`() = runBlocking {
        seedPeriod("pZero", limitMicro = 0L, postedMicro = -10_000L, holdMicro = 5_000L)
        withTimeout(10_000) {
            while (viewModel.periodBalances.value.isEmpty()) {
                delay(10)
            }
        }
        val row = viewModel.periodBalances.value.first { it.periodId == "pZero" }
        val utilization = viewModel.utilization(row)
        assertTrue("zero-limit utilization must be NaN", utilization.postedToLimit.isNaN())
        assertTrue(utilization.holdsToLimit.isNaN())
    }

    @Test
    fun `an empty database renders honest empty state - never fabricated zeros`() = runBlocking {
        withTimeout(5_000) {
            delay(100)
        }
        assertTrue(viewModel.periodBalances.value.isEmpty())
        assertTrue(viewModel.costByProvider.value.isEmpty())
        assertTrue(viewModel.ledgerEntries.value.isEmpty())
        assertEquals(null, viewModel.state.value.selectedPeriodId)
        assertNotNull(viewModel.state.value) // the state object itself is real
    }
}
