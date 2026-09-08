package com.example.gapclosure

import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionCaseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * REPORT GAP-CLOSURE — CaseBaseHonestPersistenceTest
 * ============================================================================
 *
 * Report verdict: "domain → infrastructure violation" + "persistence
 * semantics incomplete — exceptions swallowed inside the domain
 * (`catch (_: Exception) {}`)."
 *
 * This test pins the fix:
 *  1. CaseBase takes the DOMAIN-OWNED [DecisionCaseStore] port (no Room
 *     types in the domain — the boundary fix).
 *  2. A failing store is COUNTED (persistenceFailureCount) with the last
 *     error message — never silently swallowed.
 *  3. A healthy store loads persisted cases back into the in-memory base.
 */
class CaseBaseHonestPersistenceTest {

    private class FailingStore : DecisionCaseStore {
        override suspend fun loadAll(): List<DecisionCase> = throw IllegalStateException("disk offline")
        override suspend fun append(case: DecisionCase) = throw IllegalStateException("disk full")
        override suspend fun pruneOldest(keepMostRecent: Int) = throw IllegalStateException("disk full")
    }

    private class RecordingStore : DecisionCaseStore {
        val stored = mutableListOf<DecisionCase>()
        override suspend fun loadAll(): List<DecisionCase> = stored.toList()
        override suspend fun append(case: DecisionCase) { stored.add(case) }
        override suspend fun pruneOldest(keepMostRecent: Int) { /* no-op */ }
    }

    @Test
    fun `store failures are counted, never silently swallowed`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val caseBase = CaseBase(store = FailingStore(), persistenceScope = scope)

        // The load failure already happened synchronously (Unconfined).
        assertTrue(caseBase.persistenceFailureCount >= 1)
        assertNotNull(caseBase.lastPersistenceError)
        assertTrue(caseBase.lastPersistenceError!!.contains("LOAD_FAILED"))
        // Bootstrap cases still serve the engine (honest degradation).
        assertEquals(5, caseBase.getAllCases().size)

        caseBase.addCase(
            DecisionCase(
                id = "case_x",
                problemFeatures = FloatArray(15),
                chosenAction = DecisionAction(DecisionActionType.STOP),
                outcomeReward = 0.5f,
                taskType = "TEST"
            )
        )
        // The append failure is counted too (Unconfined = synchronous).
        assertTrue(caseBase.persistenceFailureCount >= 2)
        assertTrue(caseBase.lastPersistenceError!!.contains("APPEND_FAILED"))
    }

    @Test
    fun `healthy store round-trips cases and stays durable-healthy`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = RecordingStore()
        val caseBase = CaseBase(store = store, persistenceScope = scope)

        caseBase.addCase(
            DecisionCase(
                id = "case_keep",
                problemFeatures = floatArrayOf(0.1f, 0.2f),
                chosenAction = DecisionAction(DecisionActionType.REPLAN),
                outcomeReward = 0.8f,
                taskType = "TEST"
            )
        )
        assertEquals(1, store.stored.size)

        // A new CaseBase over the same store reloads persisted cases.
        val reloaded = CaseBase(store = store, persistenceScope = scope)
        assertTrue(reloaded.durableStoreHealthy)
        assertTrue(reloaded.getAllCases().any { it.id == "case_keep" })
    }

    @Test
    fun `case base keeps its FIFO bound`() {
        val caseBase = CaseBase()
        repeat(CaseBase.CASE_BASE_BOUND + 5) { i ->
            caseBase.addCase(
                DecisionCase(
                    id = "case_$i",
                    problemFeatures = FloatArray(15),
                    chosenAction = DecisionAction(DecisionActionType.STOP),
                    outcomeReward = 0.1f,
                    timestampMs = i.toLong(),
                    taskType = "TEST"
                )
            )
        }
        assertEquals(CaseBase.CASE_BASE_BOUND, caseBase.caseCount())
    }
}
