package com.example.convergence

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.decision.InMemoryMdpLearningStore
import com.example.domain.core.decision.RESOURCE_AXIS_NONE
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.task.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P0/P1 CONVERGENCE — ResourceAwareDecisionLearningTest
 * ============================================================================
 *
 * Audit step 12 §4: the learning state was keyed practically by
 * "region + action type" — NOT by resource/model identity — so the engine
 * could learn "SEARCH is good" but never "SEARCH via resource X beats
 * resource Y for this state". Decision learning must become
 * resource/model-aware (one of the audit's top-5 changes).
 *
 * Proves:
 *   1. Two resources under the SAME action type learn into SEPARATE cells.
 *   2. A resource's learned value does NOT leak into another resource's
 *      decision (the cold cell of resource B stays cold after A learns).
 *   3. Resource-less actions keep learning on the R:none axis.
 *   4. The state region distinguishes capability coverage (the previously
 *      discarded contextFeatures now shape the state).
 *   5. The learning survives persistence (store round-trip keeps the axis).
 */
class ResourceAwareDecisionLearningTest {

    private fun state(capabilityCoverage: Float? = null) = DecisionState(
        taskId = TaskId("t_res"),
        contextFeatures = capabilityCoverage?.let { mapOf("capabilityCoverageRatio" to it) } ?: emptyMap()
    )

    private fun record(resource: String) = DecisionRecord(
        selectedResourceId = ResourceId(resource),
        providerId = "prov_$resource",
        serviceId = "svc_$resource",
        configurationVersion = 1,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
        rationale = "r",
        confidence = 0.8f
    )

    private fun selectModelAction(resource: String) = DecisionAction(
        type = DecisionActionType.SELECT_MODEL,
        targetId = resource,
        decisionRecord = record(resource)
    )

    @Test
    fun `two resources under the same action learn into SEPARATE cells`() {
        val engine = CbrMdpEngine(caseBase = CaseBase())

        val resA = "res_cloud/providerA/modelX"
        val resB = "res_local/providerB/modelY"

        engine.processObservationAndUpdateBelief(
            state(),
            EnvironmentObservation(action = selectModelAction(resA), isSuccess = true, actualLatencyMs = 100)
        )
        engine.processObservationAndUpdateBelief(
            state(),
            EnvironmentObservation(action = selectModelAction(resB), isSuccess = false, actualLatencyMs = 900)
        )

        val region = engine.stateRegionKey(state())
        val cellA = engine.getQEntry(region, "R:$resA", DecisionActionType.SELECT_MODEL)
        val cellB = engine.getQEntry(region, "R:$resB", DecisionActionType.SELECT_MODEL)

        assertNotNull("Resource A must have its own learned cell", cellA)
        assertNotNull("Resource B must have its own learned cell", cellB)
        assertNotEquals(
            "The success and the failure must NOT collapse into one cell (the old defect)",
            cellA!!.successCount, cellB!!.successCount
        )
        assertEquals(1, cellA.successCount)
        assertEquals(0, cellB.successCount)
    }

    @Test
    fun `a resource's learning does not leak into another resource's cold cell`() {
        val engine = CbrMdpEngine(caseBase = CaseBase())
        val resA = "res_x"
        val resB = "res_y"

        // Only resource A ever executes.
        repeat(5) {
            engine.processObservationAndUpdateBelief(
                state(),
                EnvironmentObservation(action = selectModelAction(resA), isSuccess = true, actualLatencyMs = 50)
            )
        }

        val region = engine.stateRegionKey(state())
        val warmA = engine.getQEntry(region, "R:$resA", DecisionActionType.SELECT_MODEL)
        val coldB = engine.getQEntry(region, "R:$resB", DecisionActionType.SELECT_MODEL)

        assertEquals(5, warmA!!.visitCount)
        assertNull(
            "Resource B must stay cold — A's experience must not silently vouch for B",
            coldB
        )
    }

    @Test
    fun `resource-less actions keep learning on the R none axis`() {
        val engine = CbrMdpEngine(caseBase = CaseBase())
        engine.processObservationAndUpdateBelief(
            state(),
            EnvironmentObservation(
                action = DecisionAction(DecisionActionType.COMPLETE),
                isSuccess = true, actualLatencyMs = 10
            )
        )
        val region = engine.stateRegionKey(state())
        val cell = engine.getQEntry(region, RESOURCE_AXIS_NONE, DecisionActionType.COMPLETE)
        assertNotNull("Resource-less actions learn on the R:none axis (legacy rows migrate here)", cell)
        assertEquals(1, cell!!.visitCount)
    }

    @Test
    fun `the aggregate Q lookup still sees the most-visited resource cell`() {
        val engine = CbrMdpEngine(caseBase = CaseBase())
        val resA = "res_agg_a"
        repeat(3) {
            engine.processObservationAndUpdateBelief(
                state(),
                EnvironmentObservation(action = selectModelAction(resA), isSuccess = true, actualLatencyMs = 10)
            )
        }
        val region = engine.stateRegionKey(state())
        val aggregate = engine.getQEntry(region, DecisionActionType.SELECT_MODEL)
        assertNotNull(aggregate)
        assertEquals(3, aggregate!!.visitCount)
    }

    @Test
    fun `state region distinguishes capability coverage buckets`() {
        val engine = CbrMdpEngine(caseBase = CaseBase())
        val starved = engine.stateRegionKey(state(capabilityCoverage = 0.1f))
        val rich = engine.stateRegionKey(state(capabilityCoverage = 0.9f))
        val unknown = engine.stateRegionKey(state(capabilityCoverage = null))

        assertNotEquals("Capability-starved and capability-rich states must NOT share a region", starved, rich)
        assertNotEquals("Unknown coverage must be its own honest bucket", unknown, rich)
        assertTrue(starved.endsWith("C0"))
        assertTrue(rich.endsWith("C2"))
        assertTrue(unknown.endsWith("C?"))
    }

    @Test
    fun `resource axis prefers the decision record identity over the raw target`() {
        val engine = CbrMdpEngine(caseBase = CaseBase())
        val action = DecisionAction(
            type = DecisionActionType.SELECT_MODEL,
            targetId = "something-else",
            decisionRecord = record("res_canonical")
        )
        assertEquals(
            "The canonical DecisionRecord.selectedResourceId wins over the loose targetId",
            "R:res_canonical",
            engine.resourceAxisKey(action)
        )
    }

    @Test
    fun `resource-aware learning survives the persistent store round-trip`() {
        kotlinx.coroutines.runBlocking {
            val store = InMemoryMdpLearningStore()
            val engine = CbrMdpEngine(caseBase = CaseBase(), mdpStore = store)
            val res = "res_persist"
            engine.processObservationAndUpdateBelief(
                state(),
                EnvironmentObservation(action = selectModelAction(res), isSuccess = true, actualLatencyMs = 10)
            )
            val region = engine.stateRegionKey(state())
            val cell = engine.getQEntry(region, "R:$res", DecisionActionType.SELECT_MODEL)
            store.persist(listOf(cell!!))

            // A NEW engine (post-restart) restores the resource-aware cell.
            val restarted = CbrMdpEngine(caseBase = CaseBase(), mdpStore = store)
            restarted.loadPersistedQTable()
            val restored = restarted.getQEntry(region, "R:$res", DecisionActionType.SELECT_MODEL)
            assertNotNull("The resource-aware cell must survive the store round-trip", restored)
            assertEquals(cell.visitCount, restored!!.visitCount)
        }
    }
}
