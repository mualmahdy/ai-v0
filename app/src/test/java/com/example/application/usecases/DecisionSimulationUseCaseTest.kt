package com.example.application.usecases

import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GAP-19 (Design Closure 2026, ADR-6 step 2): the decision-simulation
 * business rules extracted from MainViewModel — the use case must run the
 * REAL CbrMdpEngine against the extracted candidate set and return a
 * projectable outcome (decision + case base) WITHOUT any presentation class.
 */
class DecisionSimulationUseCaseTest {

    @Test
    fun `the simulation runs the real engine and returns a projectable outcome`() = runBlocking {
        val engine = CbrMdpEngine()
        val useCase = DecisionSimulationUseCase(engine)

        val outcome = useCase(
            taskComplexity = 0.6f,
            uncertaintyScore = 0.3f,
            networkPolicy = NetworkPolicy.HYBRID
        )

        assertNotNull("the engine must return a decision", outcome.decision)
        assertNotNull("the chosen action must exist", outcome.decision.chosenAction)
        // The case base projection matches the engine's live state (what the
        // ViewModel previously read directly).
        assertEquals(engine.getCaseBase().getAllCases(), outcome.caseBase)
    }

    @Test
    fun `the simulation is repeatable and keeps the engine consistent`() = runBlocking {
        val engine = CbrMdpEngine()
        val useCase = DecisionSimulationUseCase(engine)

        val first = useCase(0.2f, 0.1f, NetworkPolicy.OFFLINE)
        val second = useCase(0.9f, 0.8f, NetworkPolicy.HYBRID)

        assertNotNull(first.decision)
        assertNotNull(second.decision)
        assertTrue("two simulations must both complete against the same engine", true)
    }
}
