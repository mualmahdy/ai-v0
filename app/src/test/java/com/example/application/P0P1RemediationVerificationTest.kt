package com.example.application

import com.example.application.extension.ExtensionManager
import com.example.application.observation.ObservationService
import com.example.application.outcome.ActionOutcomeType
import com.example.application.outcome.OutcomeService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.execution.ExecutionResult
import com.example.domain.core.Outcome
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.decision.InMemoryMdpLearningStore
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.task.TaskId
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.tools.ToolPort
import com.example.domain.core.DegradedReason
import com.example.domain.core.tools.ToolFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P0/P1 Remediation Verification (audit c03919d remediation plan)
 * ============================================================================
 *
 * Verifies the acceptance criteria of the comprehensive development plan:
 *   - D-1/D-4: tabular MDP Q-table (per-(region, action)) + persistence
 *   - P1-3:   EVOI gate before ASK_USER
 *   - D-2:    verifyTaskCompletion-quality reward shaping
 *   - D-3:    evaluateActionOutcome wired into the reward loop
 *   - F-10:   governance gates on the evolution stage machine
 *   - F-7:    RuntimeAdapterResolver.listToolDeclarations (tools-to-prompt)
 */
class P0P1RemediationVerificationTest {

    private fun decisionState(
        uncertainty: Float = 0.2f,
        failures: Int = 0,
        step: Int = 0
    ) = DecisionState(
        taskId = TaskId("t-verify"),
        taskComplexity = 0.5f,
        requiresVision = false,
        requiresToolCalling = true,
        requiresWebSearch = false,
        requiresCoding = false,
        uncertaintyScore = uncertainty,
        consecutiveFailures = failures,
        currentStep = step
    )

    private fun observation(
        action: DecisionAction,
        success: Boolean,
        reward: Float
    ) = EnvironmentObservation(
        action = action,
        isSuccess = success,
        actualLatencyMs = 100L,
        tokensConsumed = 10,
        errorDescription = null,
        outputSummary = "test",
        outputData = emptyMap(),
        stepIndex = 0,
        feedbackReward = reward,
        timestampMs = System.currentTimeMillis()
    )

    // ====================================================================
    // D-1 / D-4 — tabular MDP with persistence
    // ====================================================================

    @Test
    fun `q table learns per region-action cells from observations`() {
        val engine = CbrMdpEngine()
        val state = decisionState()
        val action = DecisionAction(DecisionActionType.SEARCH, targetId = "multi_source_search")

        // Two observations taken in the SAME state region (no evidence yet) —
        // they must accumulate in ONE (region, action) cell.
        val updated = engine.processObservationAndUpdateBelief(state, observation(action, true, 0.8f))
        val updated2 = engine.processObservationAndUpdateBelief(state, observation(action, false, -0.4f))

        val region = engine.stateRegionKey(state)
        val cell = engine.getQEntry(region, DecisionActionType.SEARCH)
        assertNotNull("Q cell must exist after an observation (D-1)", cell)
        assertEquals(2, cell!!.visitCount)
        assertEquals(1, cell.successCount)
        assertTrue(cell.qValue in -1.5f..1.5f)

        // The region changed after evidence flags flipped — different cell space.
        assertTrue(engine.qTableSize() >= 1)
        // Evidence flag propagated from the SUCCESSFUL search observation...
        assertTrue(updated.hasSearchEvidence)
        // ...while the FAILED search did NOT fabricate evidence (honest belief).
        assertFalse(updated2.hasSearchEvidence)
    }

    @Test
    fun `q table persists to the learning store and reloads after restart (D-4)`() {
        val store = InMemoryMdpLearningStore()
        // Unconfined scope → persistence flush happens synchronously in-test.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = CbrMdpEngine(mdpStore = store, persistenceScope = scope)

        val state = decisionState()
        val action = DecisionAction(DecisionActionType.RETRIEVE_MEMORY)
        engine.processObservationAndUpdateBelief(state, observation(action, true, 0.9f))
        engine.processObservationAndUpdateBelief(state, observation(action, true, 0.7f))

        // Store received the dirty cells (Unconfined scope → synchronous flush).
        val persisted = kotlinx.coroutines.runBlocking { store.loadAll() }
        assertTrue("cells must be persisted (D-4)", persisted.isNotEmpty())
        val persistedCell = persisted.first { it.actionType == DecisionActionType.RETRIEVE_MEMORY }
        assertEquals(2, persistedCell.visitCount)

        // Simulate an app restart: a NEW engine over the SAME store restores
        // the learned table via loadPersistedQTable (bootstrap path).
        val restarted = CbrMdpEngine(mdpStore = store, persistenceScope = scope)
        kotlinx.coroutines.runBlocking { restarted.loadPersistedQTable() }
        val restored = restarted.getQEntry(engine.stateRegionKey(state), DecisionActionType.RETRIEVE_MEMORY)
        assertNotNull("persisted cells must reload after restart (D-4)", restored)
        assertEquals(2, restored!!.visitCount)
        assertEquals(2, restored.successCount)
    }

    // ====================================================================
    // P1-3 — EVOI gate before ASK_USER
    // ====================================================================

    @Test
    fun `evoi gate penalizes ASK_USER when uncertainty is low`() {
        val engine = CbrMdpEngine()
        val state = decisionState(uncertainty = 0.2f, failures = 0, step = 2)

        val candidates = listOf(
            DecisionAction(DecisionActionType.ASK_USER, payload = mapOf("reason" to "confirm?")),
            DecisionAction(DecisionActionType.SELECT_MODEL, targetId = "gemini-2.5-flash"),
            DecisionAction(DecisionActionType.COMPLETE)
        )
        val result = engine.evaluateAndSelectAction(state, candidates)
        val askScore = result.evaluatedAlternatives.first { it.action.type == DecisionActionType.ASK_USER }.finalScore
        val modelScore = result.evaluatedAlternatives.first { it.action.type == DecisionActionType.SELECT_MODEL }.finalScore
        assertTrue(
            "ASK_USER must be penalized at low uncertainty (EVOI gate): ask=$askScore model=$modelScore",
            askScore < modelScore
        )
        // The gate reason is surfaced in the decision rationale.
        val askReason = result.evaluatedAlternatives.first { it.action.type == DecisionActionType.ASK_USER }.reason
        assertTrue(askReason.contains("EVOI"))
    }

    @Test
    fun `evoi gate allows ASK_USER when uncertainty justifies information value`() {
        val engine = CbrMdpEngine()
        val state = decisionState(uncertainty = 0.8f, failures = 3, step = 1)

        val candidates = listOf(
            DecisionAction(DecisionActionType.ASK_USER, payload = mapOf("reason" to "confirm?")),
            DecisionAction(DecisionActionType.COMPLETE)
        )
        val result = engine.evaluateAndSelectAction(state, candidates)
        val askScore = result.evaluatedAlternatives.first { it.action.type == DecisionActionType.ASK_USER }.finalScore
        val completeScore = result.evaluatedAlternatives.first { it.action.type == DecisionActionType.COMPLETE }.finalScore
        // ASK_USER gets a boost from consecutive failures; COMPLETE is weak
        // (no evidence + early step) — ASK_USER must dominate.
        assertTrue("ASK_USER should be viable under justified uncertainty", askScore > completeScore)
        val askReason = result.evaluatedAlternatives.first { it.action.type == DecisionActionType.ASK_USER }.reason
        assertFalse(askReason.contains("EVOI"))
    }

    // ====================================================================
    // D-2 / D-3 — reward shaping
    // ====================================================================

    @Test
    fun `terminal verification quality shapes the feedback reward (D-2)`() {
        val observationService = ObservationService()
        val action = DecisionAction(DecisionActionType.COMPLETE)
        val result = ExecutionResult(isSuccess = true, outputText = "final answer")

        val verified = observationService.createObservation(
            action, result, stepIndex = 3,
            actionOutcome = ActionOutcomeType.SUCCESS,
            taskVerificationQuality = +0.9f
        )
        val failed = observationService.createObservation(
            action, result, stepIndex = 3,
            actionOutcome = ActionOutcomeType.SUCCESS,
            taskVerificationQuality = -0.9f
        )

        assertTrue(
            "verified completion must reward higher than failed verification",
            verified.feedbackReward > failed.feedbackReward
        )
        // The honest outcome type is exposed (D-3 wiring).
        assertEquals("SUCCESS", verified.outputData["actionOutcomeType"])
    }

    @Test
    fun `action outcome classification shapes the reward (D-3)`() {
        val observationService = ObservationService()
        val action = DecisionAction(DecisionActionType.EXECUTE_TOOL)
        val result = ExecutionResult(isSuccess = true, outputText = "partial", isDegraded = false)

        val successObs = observationService.createObservation(
            action, result, actionOutcome = ActionOutcomeType.SUCCESS
        )
        val partialObs = observationService.createObservation(
            action, result, actionOutcome = ActionOutcomeType.PARTIAL_SUCCESS
        )
        val blockedObs = observationService.createObservation(
            action, result, actionOutcome = ActionOutcomeType.BLOCKED
        )

        assertTrue(successObs.feedbackReward > partialObs.feedbackReward)
        assertTrue(partialObs.feedbackReward > blockedObs.feedbackReward)
    }

    @Test
    fun `outcome service evaluation is wired and classifies results (D-3)`() {
        val outcomeService = OutcomeService()
        val action = DecisionAction(DecisionActionType.EXECUTE_TOOL)
        val degraded = ExecutionResult(
            isSuccess = true, outputText = "partial", isDegraded = true,
            degradedReason = DegradedReason.CACHE_FALLBACK
        )
        assertEquals(
            ActionOutcomeType.PARTIAL_SUCCESS,
            outcomeService.evaluateActionOutcome(action, degraded)
        )
    }

    // ====================================================================
    // F-10 — governance gates on the evolution stage machine
    // ====================================================================

    @Test
    fun `governance gate rejects stage jumps`() {
        val pipeline = IntelligenceRadarPipeline(
            radarSources = emptyList(),
            radarItemDao = null,
            evolutionCandidateDao = null
        )
        val candidate = pipeline.evolutionCandidates.value.first { it.stage == EvolutionStage.DISCOVERED }

        val jump = kotlinx.coroutines.runBlocking {
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.INTEGRATED)
        }
        assertTrue("stage jump DISCOVERED→INTEGRATED must be rejected", jump is Outcome.Error)

        val singleStep = kotlinx.coroutines.runBlocking {
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.UNDERSTOOD)
        }
        assertTrue("single-step DISCOVERED→UNDERSTOOD must be allowed", singleStep is Outcome.Success)
    }

    @Test
    fun `governance gate blocks integration without security and governance approval`() {
        val pipeline = IntelligenceRadarPipeline(
            radarSources = emptyList(),
            radarItemDao = null,
            evolutionCandidateDao = null
        )
        val candidate = pipeline.evolutionCandidates.value.first { it.stage == EvolutionStage.DISCOVERED }

        // Walk legitimately to APPROVAL_PENDING (single steps, honest path).
        kotlinx.coroutines.runBlocking {
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.UNDERSTOOD)
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.CLASSIFIED)
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.EVALUATED)
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.CANDIDATE)
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.APPROVAL_PENDING)
        }

        // securityAuditPassed=false and governanceApproved=false (honest defaults)
        // → INTEGRATED must be blocked by the governance gate.
        val integration = kotlinx.coroutines.runBlocking {
            pipeline.advanceEvolutionStage(candidate.id, EvolutionStage.INTEGRATED)
        }
        assertTrue(
            "integration without security audit + governance approval must be blocked",
            integration is Outcome.Error
        )
    }

    // ====================================================================
    // F-7 — tools-to-prompt plumbing
    // ====================================================================

    @Test
    fun `resolver lists registered tool declarations for the model prompt (F-7)`() {
        val registry = com.example.application.registry.ComponentRegistry(
            resourceRegistry = com.example.application.resource.DurableResourceRegistryService(repository = null)
        )
        val fakeTool = object : ToolPort {
            override val declaration = ToolDeclaration(
                name = "workspace_fs",
                description = "ملفات مساحة العمل"
            )
            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> =
                Outcome.Success(ToolOutput(content = "ok"))
        }
        registry.registerTool(fakeTool)

        val declarations = registry.runtimeAdapterResolver.listToolDeclarations()
        assertTrue(declarations.any { it.name == "workspace_fs" })
    }

    // ====================================================================
    // F-11 — extension manager honesty
    // ====================================================================

    @Test
    fun `extension manager no longer fabricates plugins (F-11)`() {
        val manager = ExtensionManager(
            componentRegistry = com.example.application.registry.ComponentRegistry(
                resourceRegistry = com.example.application.resource.DurableResourceRegistryService(repository = null)
            ),
            extensionConfigDao = null,
            executableSkills = emptyList()
        )
        // The two fabricated plugin manifests are gone.
        assertTrue("no fabricated plugin manifests should remain", manager.plugins.value.isEmpty())
        // Unknown-health MCP servers must NOT have their tools registered.
        val unverified = manager.mcpServers.value.first { it.health == com.example.domain.core.provider.HealthStatus.UNKNOWN }
        assertTrue(unverified.exposedTools.isEmpty())
        // The local bridge (genuinely healthy, in-process) keeps its real tools.
        val bridge = manager.mcpServers.value.first { it.endpointUri.startsWith("inprocess://") }
        assertTrue(bridge.exposedTools.isNotEmpty())
    }
}
