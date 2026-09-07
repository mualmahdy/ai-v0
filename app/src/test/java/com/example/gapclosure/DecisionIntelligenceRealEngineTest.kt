package com.example.gapclosure

import com.example.application.decision.DecisionIntelligenceService
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.InMemoryMdpLearningStore
import com.example.domain.core.decision.MdpQEntry
import com.example.domain.core.task.TaskId
import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * DecisionIntelligenceRealEngineTest — gap-closure P0-08
 * ============================================================================
 *
 * Proves the Decision Intelligence layer measures the REAL engine, not a
 * fabricated simulation:
 *  - policy evaluation runs the engine's actual action selection (and says
 *    so in its notes);
 *  - lookahead reads REAL Q-table cells: asymmetric learned values produce
 *    asymmetric lookahead scores (the old placeholder returned a constant);
 *  - with NOTHING learned, lookahead honestly returns a neutral value.
 */
class DecisionIntelligenceRealEngineTest {

    private fun state(): DecisionState = DecisionState(
        taskId = TaskId("t"),
        requiresCoding = false,
        currentStep = 0,
        networkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable = true
    )

    private fun engineWithLearnedTable(vararg entries: MdpQEntry): CbrMdpEngine {
        val store = InMemoryMdpLearningStore()
        runBlocking { store.persist(entries.toList()) }
        val engine = CbrMdpEngine(mdpStore = store)
        runBlocking { engine.loadPersistedQTable() }
        return engine
    }

    @Test
    fun `policy evaluation uses the REAL engine and reports so`() = runBlocking {
        val engine = CbrMdpEngine()
        val service = DecisionIntelligenceService(
            decisionCaseDao = NoopDecisionCaseDao,
            cbrMdpEngine = engine
        )

        // Suite where "expected" is whatever the engine ACTUALLY picks for
        // the state — a simulation would fail this only by luck; the real
        // engine by construction. We verify BOTH the success count and that
        // the report names the real engine.
        val states = (1..5).map { state().copy(taskId = TaskId("t$it")) }
        // The EXPECTED action is whatever the engine ACTUALLY selects over
        // the service's own standard candidate space (visible for tests).
        val expectations = states.map { s ->
            val decision = engine.evaluateAndSelectAction(s, service.standardCandidateActions())
            s to decision.chosenAction.type.name
        }

        val report = service.evaluatePolicy("v-test", expectations)
        assertEquals(expectations.size, report.taskSuiteSize)
        assertEquals(
            "The real engine must match its own choices (100% success)",
            expectations.size,
            report.successCount
        )
        assertTrue(
            "Report notes must state the REAL engine was used (no silent simulation)",
            report.notes.contains("المحرك الحقيقي")
        )
    }

    @Test
    fun `lookahead reads REAL Q-table values (asymmetric learning shows)`() {
        // Region for the default test state: learn a STRONG SELECT_MODEL and
        // a WEAK COMPLETE for the same region.
        val engine = CbrMdpEngine()
        val region = engine.stateRegionKey(state())
        val store = InMemoryMdpLearningStore()
        runBlocking {
            store.persist(
                listOf(
                    MdpQEntry(regionKey = region, actionType = DecisionActionType.SELECT_MODEL, qValue = 0.9f, visitCount = 10, successCount = 10),
                    MdpQEntry(regionKey = region, actionType = DecisionActionType.COMPLETE, qValue = 0.1f, visitCount = 10, successCount = 1)
                )
            )
        }
        runBlocking { engine.loadPersistedQTable() }
        val engine2 = CbrMdpEngine(mdpStore = store)
        runBlocking { engine2.loadPersistedQTable() }
        val service = DecisionIntelligenceService(NoopDecisionCaseDao, engine2)

        val strong = service.lookahead(state(), DecisionActionType.SELECT_MODEL.name, horizon = 1)
        val weak = service.lookahead(state(), DecisionActionType.COMPLETE.name, horizon = 1)

        assertTrue(
            "Real Q values must differentiate actions (strong=$strong, weak=$weak); the old placeholder returned a constant for both",
            strong > weak + 0.3f
        )
    }

    @Test
    fun `nothing learned returns an honest neutral lookahead`() {
        val engine = CbrMdpEngine() // empty Q-table
        val service = DecisionIntelligenceService(NoopDecisionCaseDao, engine)
        val value = service.lookahead(state(), DecisionActionType.SEARCH.name, horizon = 3)
        assertEquals("No learned data => honest neutral 0f (never a fabricated constant)", 0f, value)
    }

    @Test
    fun `lookahead is horizon-sensitive with real values`() {
        val engine = CbrMdpEngine()
        val region = engine.stateRegionKey(state())
        val store = InMemoryMdpLearningStore()
        runBlocking {
            store.persist(
                listOf(
                    MdpQEntry(regionKey = region, actionType = DecisionActionType.SELECT_MODEL, qValue = 0.5f, visitCount = 5, successCount = 5)
                )
            )
        }
        val engine2 = CbrMdpEngine(mdpStore = store)
        runBlocking { engine2.loadPersistedQTable() }
        val service = DecisionIntelligenceService(NoopDecisionCaseDao, engine2)

        val short = service.lookahead(state(), DecisionActionType.SELECT_MODEL.name, horizon = 1)
        val long = service.lookahead(state(), DecisionActionType.SELECT_MODEL.name, horizon = 5)
        assertTrue("Longer horizon must accumulate more value with real Q cells", long > short)
        assertNotEquals(0f, short)
    }

    /** No-op DAO — calibration paths are not under test here. */
    private object NoopDecisionCaseDao : com.example.infrastructure.persistence.dao.DecisionCaseDao {
        override suspend fun getAllCases(): List<com.example.infrastructure.persistence.entities.DecisionCaseEntity> = emptyList()
        override suspend fun getRecentCases(limit: Int): List<com.example.infrastructure.persistence.entities.DecisionCaseEntity> = emptyList()
        override suspend fun insertCase(caseEntity: com.example.infrastructure.persistence.entities.DecisionCaseEntity) {}
        override suspend fun insertAll(cases: List<com.example.infrastructure.persistence.entities.DecisionCaseEntity>) {}
    }
}
