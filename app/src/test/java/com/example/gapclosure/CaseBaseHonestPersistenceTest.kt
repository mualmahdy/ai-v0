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
import org.junit.Assert.assertFalse
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
 * DEFECT FAMILY 5 REPAIR (cumulative correctness & authority order):
 *  1. CaseBase takes the DOMAIN-OWNED [DecisionCaseStore] port (no Room
 *     types in the domain — the boundary fix).
 *  2. A failing store is COUNTED (persistenceFailureCount) with the last
 *     error message — never silently swallowed.
 *  3. A healthy store loads persisted cases back into the in-memory base.
 *  4. NEW — HONEST addCase: the returned boolean is PROOF of the durable
 *     append (previously addCase returned a hardcoded `true` regardless of
 *     the persistence outcome).
 *  5. NEW — READINESS: the startup load is trackable (isLoadComplete /
 *     awaitReady) — the loading race is observable and gateable.
 *  6. NEW — IDEMPOTENCY: re-adding a case with the SAME id updates it in
 *     place (memory) and the store insert is REPLACE-conflict — a
 *     crash-replay cannot duplicate rows.
 *  7. NEW — CRASH/RESTART: a fresh CaseBase over the same durable store
 *     reloads the persisted cases (kill → reopen → same case base).
 */
class CaseBaseHonestPersistenceTest {

    private class FailingStore : DecisionCaseStore {
        override suspend fun loadAll(): List<DecisionCase> = throw IllegalStateException("disk offline")
        override suspend fun append(case: DecisionCase) = throw IllegalStateException("disk full")
        override suspend fun pruneOldest(keepMostRecent: Int) = throw IllegalStateException("disk full")
    }

    private class RecordingStore : DecisionCaseStore {
        val stored = mutableListOf<DecisionCase>()
        var appendCalls = 0
        override suspend fun loadAll(): List<DecisionCase> = stored.toList()
        override suspend fun append(case: DecisionCase) {
            appendCalls++
            stored.removeAll { it.id == case.id }
            stored.add(case)
        }
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
        // The append failure is counted too — AND now reported HONESTLY.
        assertTrue(caseBase.persistenceFailureCount >= 2)
        assertTrue(caseBase.lastPersistenceError!!.contains("APPEND_FAILED"))
    }

    @Test
    fun `addCase returns PROOF of the durable write - honest boolean`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val caseBase = CaseBase(store = FailingStore(), persistenceScope = scope)

        // The durable append FAILS → addCase must return FALSE (previously
        // it returned a hardcoded true — success without proof).
        val durableResult = caseBase.addCase(
            DecisionCase(
                id = "case_failing",
                problemFeatures = FloatArray(15),
                chosenAction = DecisionAction(DecisionActionType.STOP),
                outcomeReward = 0.5f,
                taskType = "TEST"
            )
        )
        assertFalse("addCase must return false when the durable append fails", durableResult)
    }

    @Test
    fun `healthy store round-trips cases and stays durable-healthy`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = RecordingStore()
        val caseBase = CaseBase(store = store, persistenceScope = scope)

        val durableResult = caseBase.addCase(
            DecisionCase(
                id = "case_keep",
                problemFeatures = floatArrayOf(0.1f, 0.2f),
                chosenAction = DecisionAction(DecisionActionType.REPLAN),
                outcomeReward = 0.8f,
                taskType = "TEST"
            )
        )
        assertTrue("addCase must return true when the durable append completes", durableResult)
        assertEquals(1, store.stored.size)

        // A new CaseBase over the same store reloads persisted cases.
        val reloaded = CaseBase(store = store, persistenceScope = scope)
        assertTrue(reloaded.durableStoreHealthy)
        assertTrue(reloaded.getAllCases().any { it.id == "case_keep" })
    }

    @Test
    fun `startup load readiness is observable and awaitable`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = RecordingStore()
        val caseBase = CaseBase(store = store, persistenceScope = scope)

        // In-memory-only mode is ready immediately.
        assertTrue(CaseBase().awaitReady(timeoutMs = 10))

        // The durable load completed (Unconfined + non-suspending store).
        assertTrue(caseBase.isLoadComplete)
        assertTrue(caseBase.awaitReady(timeoutMs = 100))
        assertTrue(caseBase.durableStoreHealthy)
    }

    @Test
    fun `re-adding a case id is idempotent - no duplicates on crash replay`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val store = RecordingStore()
        val caseBase = CaseBase(store = store, persistenceScope = scope)

        val first = DecisionCase(
            id = "same_id",
            problemFeatures = FloatArray(15),
            chosenAction = DecisionAction(DecisionActionType.STOP),
            outcomeReward = 0.5f,
            taskType = "TEST"
        )
        val second = first.copy(outcomeReward = 0.9f)

        assertTrue(caseBase.addCase(first))
        assertTrue(caseBase.addCase(second)) // replayed after crash

        // In-memory: ONE case with the LATEST value — not duplicated.
        assertEquals(1, caseBase.getAllCases().count { it.id == "same_id" })
        assertEquals(0.9f, caseBase.getAllCases().first { it.id == "same_id" }.outcomeReward)
        // Durable: REPLACE semantics — one row, latest value.
        assertEquals(1, store.stored.count { it.id == "same_id" })
        assertEquals(0.9f, store.stored.first { it.id == "same_id" }.outcomeReward)
    }

    @Test
    fun `case base keeps its FIFO bound`() = runBlocking {
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
