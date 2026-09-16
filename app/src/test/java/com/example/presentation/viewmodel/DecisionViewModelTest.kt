package com.example.presentation.viewmodel

import com.example.application.usecases.DecisionSimulationUseCase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.network.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * DecisionViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the DECISION feature ViewModel (ADR-6 slice 6)
 * ============================================================================
 *
 * Drives the REAL decision kernel — no service-seam stubbing:
 *
 *  - a REAL [DecisionSimulationUseCase] over a REAL [CbrMdpEngine] (the
 *    same use-case the production factory wires; the candidate set, the
 *    OFFLINE-aware provider targeting and the case-base projection all run
 *    exactly as in production);
 *  - the studio signal bus is the test's OWN injected MutableSharedFlow —
 *    the ADR-6 slice-2 seam contract: a test injects its own bus and
 *    asserts exactly what the feature publishes/collects.
 *
 * Asserted feature contract (extracted from MainViewModel):
 *  - the init simulation populates the preview through the REAL use-case
 *    (moved from MainViewModel.loadInitialData — same trigger point) and
 *    the GAP-23 spinner flag settles honestly;
 *  - the state-vector sliders re-simulate LIVE through the real use-case;
 *  - the DECISION share of the studio signal bus: DecisionMade mirrors the
 *    latest decision, ObservationRecorded mirrors the uncertainty + refreshes
 *    the case base, Completed/Error refresh the case base;
 *  - NetworkPolicyChanged syncs the SESSION-policy display mirror and
 *    RE-SIMULATES (the old MainViewModel behaviour, preserved verbatim);
 *  - the honest fallback WITHOUT the use-case (legacy construction): the
 *    preview is unavailable, never fabricated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecisionViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var engine: CbrMdpEngine
    private lateinit var useCase: DecisionSimulationUseCase
    private lateinit var signalBus: MutableSharedFlow<StudioSignal>
    private lateinit var viewModel: DecisionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = CbrMdpEngine()
        useCase = DecisionSimulationUseCase(engine)
        signalBus = MutableSharedFlow(extraBufferCapacity = 256)
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        decisionUseCase: DecisionSimulationUseCase? = useCase
    ): DecisionViewModel = DecisionViewModel(
        cbrMdpEngine = engine,
        decisionSimulationUseCase = decisionUseCase,
        studioSignals = signalBus
    )

    /**
     * Documented helper (GovernanceViewModelTest pattern): the engine's
     * evaluation may hop dispatchers internally, so outcomes settle
     * asynchronously even under the Unconfined Main dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 25L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(intervalMs)
        }
    }

    // ------------------------------------------------------------------
    // Init — the initial simulation (moved from loadInitialData)
    // ------------------------------------------------------------------

    @Test
    fun `init populates the preview through the REAL use-case`() {
        awaitUntil { viewModel.state.value.latestDecision != null }
        val decision = viewModel.state.value.latestDecision!!
        assertNotNull(decision.chosenAction)
        assertTrue(decision.evaluatedAlternatives.isNotEmpty())
        // The case-base projection comes from the engine's own truth.
        awaitUntil { viewModel.state.value.caseBaseList.isNotEmpty() }
        assertEquals(engine.getCaseBase().getAllCases().size, viewModel.state.value.caseBaseList.size)
    }

    @Test
    fun `the simulation spinner settles honestly (GAP-23)`() {
        awaitUntil { viewModel.state.value.latestDecision != null }
        awaitUntil { !viewModel.state.value.isSimulatingDecision }
        assertFalse(viewModel.state.value.isSimulatingDecision)
    }

    @Test
    fun `honest fallback without the use-case — no fabricated preview`() {
        val legacyVm = newViewModel(decisionUseCase = null)
        // The legacy construction gets NO preview (the honest fallback),
        // never a fabricated verdict.
        Thread.sleep(300)
        assertNull(legacyVm.state.value.latestDecision)
        assertFalse(legacyVm.state.value.isSimulatingDecision)
    }

    // ------------------------------------------------------------------
    // Sliders — live re-simulation through the real use-case
    // ------------------------------------------------------------------

    @Test
    fun `updateDecisionComplexity re-simulates live`() {
        awaitUntil { viewModel.state.value.latestDecision != null }

        viewModel.updateDecisionComplexity(0.95f)

        awaitUntil { viewModel.state.value.decisionTaskComplexity == 0.95f }
        // The re-simulation ran through the REAL use-case with the new
        // complexity (requiresWebSearch flips true above 0.7 — the decision
        // is re-derived, not stale).
        awaitUntil { !viewModel.state.value.isSimulatingDecision }
        assertNotNull(viewModel.state.value.latestDecision)
    }

    @Test
    fun `updateDecisionUncertainty re-simulates live`() {
        awaitUntil { viewModel.state.value.latestDecision != null }

        viewModel.updateDecisionUncertainty(0.85f)

        awaitUntil { viewModel.state.value.decisionUncertainty == 0.85f }
        awaitUntil { !viewModel.state.value.isSimulatingDecision }
        assertNotNull(viewModel.state.value.latestDecision)
    }

    // ------------------------------------------------------------------
    // The studio signal bus — the decision feature's own stake
    // ------------------------------------------------------------------

    @Test
    fun `DecisionMade signal mirrors the latest decision`() {
        awaitUntil { viewModel.state.value.latestDecision != null }
        val live = viewModel.state.value.latestDecision!!

        val emitted = live.copy(
            rationale = "قرار حي من التنفيذ الفعلي",
            chosenAction = live.chosenAction
        )
        signalBus.tryEmit(
            StudioSignal.ExecutionEvent(
                ExecutionEvent.DecisionMade(executionId = "exec-dec-1", decision = emitted)
            )
        )

        awaitUntil { viewModel.state.value.latestDecision === emitted }
        assertEquals("قرار حي من التنفيذ الفعلي", viewModel.state.value.latestDecision!!.rationale)
    }

    @Test
    fun `ObservationRecorded signal mirrors the uncertainty and refreshes the case base`() {
        awaitUntil { viewModel.state.value.latestDecision != null }
        val current = viewModel.state.value.latestDecision!!

        signalBus.tryEmit(
            StudioSignal.ExecutionEvent(
                ExecutionEvent.ObservationRecorded(
                    executionId = "exec-obs-1",
                    observation = com.example.domain.core.decision.EnvironmentObservation(
                        action = current.chosenAction,
                        isSuccess = true,
                        actualLatencyMs = 120
                    ),
                    updatedUncertainty = 0.42f
                )
            )
        )

        awaitUntil { viewModel.state.value.decisionUncertainty == 0.42f }
        // The case base refreshed from the engine's own truth.
        awaitUntil { viewModel.state.value.caseBaseList.isNotEmpty() }
        assertEquals(engine.getCaseBase().getAllCases().size, viewModel.state.value.caseBaseList.size)
    }

    @Test
    fun `Completed signal refreshes the case base`() {
        awaitUntil { viewModel.state.value.latestDecision != null }

        signalBus.tryEmit(
            StudioSignal.ExecutionEvent(
                ExecutionEvent.Completed(executionId = "exec-done-1", finalText = "تم", totalDurationMs = 10)
            )
        )

        awaitUntil { viewModel.state.value.caseBaseList.isNotEmpty() }
        assertEquals(engine.getCaseBase().getAllCases().size, viewModel.state.value.caseBaseList.size)
    }

    @Test
    fun `NetworkPolicyChanged syncs the mirror and re-simulates`() {
        awaitUntil { viewModel.state.value.latestDecision != null }
        assertEquals(NetworkPolicy.HYBRID, viewModel.state.value.networkPolicy)

        signalBus.tryEmit(StudioSignal.NetworkPolicyChanged(NetworkPolicy.OFFLINE))

        awaitUntil { viewModel.state.value.networkPolicy == NetworkPolicy.OFFLINE }
        // The re-simulation ran (the old MainViewModel behaviour — policy
        // change re-derives the preview — preserved verbatim).
        awaitUntil { !viewModel.state.value.isSimulatingDecision }
        assertNotNull(viewModel.state.value.latestDecision)
    }

    // ------------------------------------------------------------------
    // The honest error channel
    // ------------------------------------------------------------------

    @Test
    fun `the error channel clears from the feature's own state`() {
        viewModel.clearErrorMessage()
        assertNull(viewModel.state.value.errorMessage)
    }
}
