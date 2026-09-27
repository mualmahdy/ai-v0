package com.example.closure

import com.example.application.operation.OperationPhase
import com.example.application.operation.OperationRegistry
import com.example.application.outcome.OutcomeService
import com.example.application.outcome.VerificationGapKind
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.execution.ScopeSnapshot
import com.example.domain.core.execution.toScopeSnapshot
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.Outcome
import com.example.domain.core.task.AcceptanceCriterion
import com.example.domain.core.task.AgentId
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskSuccessCriteria
import com.example.domain.core.task.VerificationStrategy
import com.example.domain.ports.llm.LlmProviderPort
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.llm.gemini.GeminiThinkingCapability
import com.example.infrastructure.llm.gemini.GeminiThinkingSupport
import com.example.infrastructure.validation.GenerationProbe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * ============================================================================
 * ClosureInvariantTests — CLOSURE P0 (audit step 5, §5.14 layer 1)
 * ============================================================================
 *
 * ARCHITECTURAL INVARIANTS, not feature tests. These pin the audit's
 * §5.14-1 list — the properties that must hold for the system to be
 * structurally sound, independent of any feature working:
 *
 *   1. A scoped operation cannot read (or delete) another scope's truth
 *      (ViewModel-level pins live in SessionsViewModelTest; the scope
 *      structure pin lives here).
 *   2. UI projection never advances ahead of durable truth (pinned in
 *      SessionsViewModelTest's deletion contract; the registry-side
 *      monotonicity pin lives here as invariant 7).
 *   3. A transient session never becomes a historical record by mere
 *      existence (covered in StudioViewModelTest — the composer open
 *      writes nothing durable).
 *   4. Unverified output cannot become VERIFIED.
 *   5. A non-admissible action cannot enter the TD target.
 *   6. UCB exploration counts VISITS, not cells.
 *   7. Operation lifecycle is monotonic (FAILED never revives; projection
 *      follows success).
 *   8. HEALTHY for an LLM requires a REAL generation round-trip (probe
 *      classification), and thinkingConfig is attached ONLY for
 *      capability-verified model families.
 */
class ClosureInvariantTests {

    // ==================================================================
    // 5 / 6 — CBR-MDP TD target + UCB (CLOSURE P0-7, audit §5.7/D2+D4)
    // ==================================================================

    /**
     * INVARIANT 5 — a non-admissible action cannot enter the TD target.
     *
     * Seeds the NEXT region with two cells: an admissible EXECUTE_STEP
     * (Q=0.2) and an INADMISSIBLE high-Q cell (Q=0.9). The Q learned for
     * the taken action must bootstrap from the ADMISSIBLE max (0.2), never
     * from the inadmissible 0.9 — the legacy all-cells sweep is proven
     * wrong by the control run.
     */
    @Test
    fun `non-admissible actions cannot enter the TD target`() = runBlocking {
        val admissible = DecisionAction(DecisionActionType.EXECUTE_STEP)
        val inadmissible = DecisionAction(DecisionActionType.SEARCH)

        fun seedNextRegion(engine: CbrMdpEngine) {
            // A state one step ahead — its region is the TD target's r'.
            val nextState = testState(step = 1)
            engine.processObservationAndUpdateBelief(
                nextState,
                observation(inadmissible, success = true, reward = 0.9f)
            )
            engine.processObservationAndUpdateBelief(
                nextState,
                observation(admissible, success = true, reward = 0.2f)
            )
        }

        // Constrained run: only EXECUTE_STEP is admissible in the next state.
        val constrained = CbrMdpEngine()
        seedNextRegion(constrained)
        constrained.processObservationAndUpdateBelief(
            testState(step = 0),
            observation(admissible, success = true, reward = 0f),
            nextStateAdmissibleActions = setOf(DecisionActionType.EXECUTE_STEP)
        )
        val constrainedQ = constrained.getQEntry(
            constrained.stateRegionKey(testState(step = 0)),
            constrained.resourceAxisKey(admissible),
            DecisionActionType.EXECUTE_STEP
        )!!.qValue

        // Control run (legacy semantics — the caller cannot constrain):
        // every cell in the next region sweeps into the target.
        val unconstrained = CbrMdpEngine()
        seedNextRegion(unconstrained)
        unconstrained.processObservationAndUpdateBelief(
            testState(step = 0),
            observation(admissible, success = true, reward = 0f),
            nextStateAdmissibleActions = null
        )
        val unconstrainedQ = unconstrained.getQEntry(
            unconstrained.stateRegionKey(testState(step = 0)),
            unconstrained.resourceAxisKey(admissible),
            DecisionActionType.EXECUTE_STEP
        )!!.qValue

        // Bootstrapped from reward + gamma*maxAdmissibleQ = 0 + 0.9*0.2.
        assertEquals(0.18f, constrainedQ, 0.001f)
        // Bootstrapped from reward + gamma*maxAllQ = 0 + 0.9*0.9.
        assertEquals(0.81f, unconstrainedQ, 0.001f)
        assertTrue(
            "the inadmissible high-Q cell must NOT inflate the TD target",
            constrainedQ < unconstrainedQ
        )
    }

    /**
     * INVARIANT 6 — UCB exploration total counts VISITS, not cells.
     * One cell visited 7 times means a region exploration total of 7
     * (the legacy cell-count implementation would have said 1).
     */
    @Test
    fun `UCB exploration counts visits, not cells`() = runBlocking {
        val engine = CbrMdpEngine()
        val regionKey = engine.stateRegionKey(testState(step = 1))
        val action = DecisionAction(DecisionActionType.EXECUTE_STEP)
        val state = testState(step = 1)
        repeat(7) {
            engine.processObservationAndUpdateBelief(
                state,
                observation(action, success = true, reward = 0.5f)
            )
        }
        assertEquals(
            "7 visits in ONE cell — the region's exploration total is the VISIT SUM",
            7,
            engine.regionExplorationVisitTotal(regionKey)
        )
    }

    // ==================================================================
    // 4 — Semantic verification (CLOSURE P0-6, audit §5.5/C3)
    // ==================================================================

    /**
     * INVARIANT 4a — unverified output cannot become VERIFIED: an execution
     * that ended with unrecovered failures is barred from verification even
     * when the accumulated text reads perfectly and evidence keys exist.
     */
    @Test
    fun `terminal errors bar verification - unverified output cannot become VERIFIED`() {
        val outcomeService = OutcomeService()
        val task = TaskDefinition(
            id = TaskId("task-inv-err"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("اكتب ملخصاً"),
            successCriteria = TaskSuccessCriteria(
                requiredEvidenceKeys = listOf("summary"),
                verificationStrategy = VerificationStrategy.EVIDENCE_BASED
            )
        )
        val evidence = mapOf<String, Any?>("summary" to "ملخص كامل ومفصل")
        val completedAction = DecisionAction(DecisionActionType.COMPLETE)

        val clean = outcomeService.verifyTaskCompletion(
            task = task,
            accumulatedEvidence = evidence,
            finalOutputText = "ملخص نهائي شامل",
            lastAction = completedAction,
            terminalErrorObserved = false
        )
        assertTrue("clean execution verifies", clean.isSatisfied)
        assertEquals(
            com.example.application.outcome.VerificationOutcomeStatus.VERIFIED,
            clean.status
        )

        val failedSteps = outcomeService.verifyTaskCompletion(
            task = task,
            accumulatedEvidence = evidence,
            finalOutputText = "ملخص نهائي شامل",
            lastAction = completedAction,
            terminalErrorObserved = true
        )
        assertFalse(
            "an execution with unrecovered failures CANNOT verify — regardless of text",
            failedSteps.isSatisfied
        )
        assertTrue(
            "the barrier is the TYPED gap, not message wording",
            failedSteps.gaps.any { it.kind == VerificationGapKind.UNRECOVERED_EXECUTION_ERROR }
        )
    }

    /**
     * INVARIANT 4b — message wording cannot flip the verdict: under
     * CRITERIA_MATCH, an UNMET criterion whose description is written in
     * ARABIC (contains neither "Criterion" nor "evidence") must still block
     * satisfaction. The legacy `contains("Criterion")` implementation let
     * such a reworded gap slip through to VERIFIED.
     */
    @Test
    fun `message wording cannot flip the verification verdict`() {
        val outcomeService = OutcomeService()
        val task = TaskDefinition(
            id = TaskId("task-inv-wording"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("نفّذ المهمة"),
            successCriteria = TaskSuccessCriteria(
                // The criterion's DESCRIPTION carries no "Criterion"/"evidence"
                // substring — the legacy string-matcher would MISS this gap.
                acceptanceCriteria = listOf(
                    AcceptanceCriterion(
                        id = "crit_1",
                        description = "يجب أن يحتوي المخرج على مرجع موثّق",
                        validatorType = "NOT_BLANK",
                        requiredKey = "documentedRef"
                    )
                ),
                verificationStrategy = VerificationStrategy.CRITERIA_MATCH
            )
        )

        val report = outcomeService.verifyTaskCompletion(
            task = task,
            accumulatedEvidence = emptyMap<String, Any?>(),
            finalOutputText = "مخرج بدون مرجع",
            lastAction = DecisionAction(DecisionActionType.COMPLETE)
        )

        assertFalse(
            "the TYPED criterion gap blocks verification regardless of message language",
            report.isSatisfied
        )
        assertTrue(
            "the gap is recorded as ACCEPTANCE_CRITERION_NOT_MET",
            report.gaps.any { it.kind == VerificationGapKind.ACCEPTANCE_CRITERION_NOT_MET }
        )
        assertFalse(
            "the per-criterion typed verdict is present and honest",
            report.criterionResults.single().met
        )
    }

    // ==================================================================
    // 7 — Operation registry monotonicity (CLOSURE P0-1)
    // ==================================================================

    /** INVARIANT 7 — the operation lifecycle is monotonic. */
    @Test
    fun `operation lifecycle is monotonic - FAILED never revives, projection follows success`() {
        val registry = OperationRegistry()
        val record = registry.register(
            type = "TEST_OP",
            scope = ScopeSnapshot.capture("op_inv_1", "ws_a", projectId = 5L, sessionId = "s1"),
            owner = "invariant-test"
        )

        // CREATED → RUNNING → FAILED is legal.
        assertNotNull(registry.transition(record.operationId, OperationPhase.RUNNING))
        assertNotNull(registry.fail(record.operationId, "durable write refused"))

        // FAILED → SUCCEEDED is REFUSED (a failed mutation never resurrects).
        assertNull(
            "FAILED → SUCCEEDED must be refused",
            registry.transition(record.operationId, OperationPhase.SUCCEEDED)
        )
        // FAILED → PROJECTED is REFUSED (UI projection may not follow failure).
        assertNull(
            "FAILED → PROJECTED must be refused",
            registry.transition(record.operationId, OperationPhase.PROJECTED)
        )
        // FAILED → FINALIZED is the only exit.
        assertNotNull(registry.transition(record.operationId, OperationPhase.FINALIZED))
        // FINALIZED is terminal.
        assertNull(
            "FINALIZED is terminal",
            registry.transition(record.operationId, OperationPhase.RUNNING)
        )

        // PROJECTED only from SUCCEEDED.
        val record2 = registry.register(
            type = "TEST_OP_2",
            scope = ScopeSnapshot.capture("op_inv_2", "ws_a", projectId = null, sessionId = null),
            owner = "invariant-test"
        )
        registry.transition(record2.operationId, OperationPhase.RUNNING)
        assertNull(
            "RUNNING → PROJECTED must be refused (projection follows success)",
            registry.transition(record2.operationId, OperationPhase.PROJECTED)
        )
        assertNotNull(registry.transition(record2.operationId, OperationPhase.SUCCEEDED))
        assertNotNull(registry.transition(record2.operationId, OperationPhase.PROJECTED))
        assertNotNull(registry.transition(record2.operationId, OperationPhase.FINALIZED))
    }

    /**
     * INVARIANT 1 (structure side) — the scope snapshot is the immutable
     * acceptance-time capture and projects into the execution scope without
     * ever re-reading a live one.
     */
    @Test
    fun `scope snapshot is the immutable acceptance capture and projects faithfully`() {
        val snapshot = ScopeSnapshot.capture(
            operationId = "op_inv_scope",
            workspaceId = "ws_a",
            projectId = 7L,
            sessionId = "sess_x"
        )
        assertEquals("ws_a", snapshot.workspaceId)
        assertEquals(7L, snapshot.projectId)
        assertEquals("sess_x", snapshot.sessionId)

        val executionScope = snapshot.toExecutionScope()
        assertEquals("op_inv_scope", executionScope.executionId)
        assertEquals("ws_a", executionScope.workspaceId)
        assertEquals(7L, executionScope.projectId)
        assertEquals("sess_x", executionScope.sessionId)

        // The round-trip preserves the pinned tuple.
        val recaptured = executionScope.toScopeSnapshot()
        assertEquals(snapshot.workspaceId, recaptured.workspaceId)
        assertEquals(snapshot.projectId, recaptured.projectId)
        assertEquals(snapshot.sessionId, recaptured.sessionId)
    }

    // ==================================================================
    // 8 — Generation probe + thinking gating (CLOSURE P0-4 / P0-5)
    // ==================================================================

    /**
     * INVARIANT 8a — the generation probe classifies failures HONESTLY:
     * rate-limit stays transient-classified; a hard model failure is
     * PROTOCOL_FAILURE (→ UNAVAILABLE: HEALTHY is unreachable without a
     * real generation round-trip).
     */
    @Test
    fun `generation probe maps failures honestly and success to HEALTHY`() = runBlocking {
        val ok = GenerationProbe.probe(FakeLlm { _ ->
            Outcome.Success(LlmResponse(text = "ok", usage = com.example.domain.core.llm.TokenUsage()))
        })
        assertTrue("a responding model proves generation", ok.isSuccess)

        val rateLimited = GenerationProbe.probe(FakeLlm { _ ->
            Outcome.Error(
                LlmFailure.RateLimitExceeded("gemini", retryAfterMs = 1000L, message = "429"),
                "rate limited"
            )
        })
        assertFalse(rateLimited.isSuccess)
        assertEquals(
            com.example.domain.core.provider.ServiceHealthClassification.RATE_LIMITED,
            rateLimited.classification
        )

        val modelBroken = GenerationProbe.probe(FakeLlm { _ ->
            Outcome.Error(
                LlmFailure.InvalidResponse("gemini", rawResponse = null, reason = "model not found"),
                "404 model"
            )
        })
        assertFalse(modelBroken.isSuccess)
        assertEquals(
            "a model that cannot generate is PROTOCOL_FAILURE — never HEALTHY",
            com.example.domain.core.provider.ServiceHealthClassification.PROTOCOL_FAILURE,
            modelBroken.classification
        )
    }

    /**
     * INVARIANT 8b — thinkingConfig is attached ONLY for capability-verified
     * model families; the advertised `reasoning` capability follows the same
     * verdict (no capability fabrication).
     */
    @Test
    fun `thinkingConfig is attached only for capability-verified model families`() {
        // A thinking-capable family: config attached, reasoning advertised.
        val modern = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash", apiKeyProvider = { null })
        val modernBody = JSONObject(modern.buildRequestBody(minimalRequest(), stream = false))
            .getJSONObject("generationConfig")
        assertTrue(
            "gemini-2.5-flash carries thinkingConfig",
            modernBody.has("thinkingConfig")
        )
        assertTrue(
            "reasoning is advertised for thinking-capable models",
            modern.metadata.supportedCapabilities.contains("reasoning")
        )

        // A NON-thinking family: NO config attached, reasoning NOT advertised.
        val legacy = GeminiLlmAdapter(defaultModelName = "gemini-1.5-flash", apiKeyProvider = { null })
        val legacyBody = JSONObject(legacy.buildRequestBody(minimalRequest(), stream = false))
            .getJSONObject("generationConfig")
        assertFalse(
            "gemini-1.5-flash must NOT carry thinkingConfig (the provider would reject the request)",
            legacyBody.has("thinkingConfig")
        )
        assertFalse(
            "reasoning is NOT advertised for non-thinking models",
            legacy.metadata.supportedCapabilities.contains("reasoning")
        )

        // UNKNOWN family: the safe default — config omitted, no fabrication.
        val unknown = GeminiLlmAdapter(defaultModelName = "some-future-model", apiKeyProvider = { null })
        val unknownBody = JSONObject(unknown.buildRequestBody(minimalRequest(), stream = false))
            .getJSONObject("generationConfig")
        assertFalse(
            "an unproven model gets NO thinkingConfig (no capability fabrication)",
            unknownBody.has("thinkingConfig")
        )
        assertEquals(
            GeminiThinkingSupport.UNKNOWN,
            GeminiThinkingCapability.forModel("some-future-model")
        )
    }

    // ==================================================================
    // Harness
    // ==================================================================

    private fun testState(step: Int): DecisionState = DecisionState(
        taskId = TaskId("task_invariant"),
        currentStep = step,
        networkPolicy = NetworkPolicy.HYBRID,
        isNetworkAvailable = true
    )

    private fun observation(
        action: DecisionAction,
        success: Boolean,
        reward: Float
    ): EnvironmentObservation = EnvironmentObservation(
        action = action,
        isSuccess = success,
        actualLatencyMs = 100L,
        feedbackReward = reward
    )

    private fun minimalRequest(): LlmRequest = LlmRequest(
        messages = listOf(
            LlmMessage(role = MessageRole.USER, content = "ping")
        )
    )

    /** Minimal scripted LLM port for the generation-probe invariants. */
    private class FakeLlm(
        private val behavior: suspend (LlmRequest) -> Outcome<LlmResponse, LlmFailure>
    ) : LlmProviderPort {
        override val providerId: String = "fake_probe_llm"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = providerId,
            name = "Fake Probe LLM",
            providerType = "FAKE",
            defaultModel = "fake-model",
            isConfigured = true,
            isOnline = true,
            isLocal = true,
            supportedCapabilities = listOf("llm_generation")
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
            behavior(request)

        override fun stream(
            request: LlmRequest,
            executionId: String
        ): Flow<com.example.domain.core.events.ExecutionEvent> = emptyFlow()
    }
}
