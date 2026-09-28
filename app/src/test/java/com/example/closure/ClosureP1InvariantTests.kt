package com.example.closure

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.AdmissibleActionSet
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.StandardActionSpace
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.resource.CapabilityVerdict
import com.example.domain.core.resource.OperationalResourceSnapshot
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskContracts
import com.example.domain.ports.llm.CapabilityProbeOutcome
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.llm.gemini.GeminiThinkingCapability
import com.example.infrastructure.llm.gemini.GeminiThinkingSupport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * ClosureP1InvariantTests — CLOSURE STAGE P1 (audit 2026 §5.16 step 1)
 * ============================================================================
 *
 * Invariant tests for the P1 stage:
 *
 *  P1-1 — OperationalResourceSnapshot (audit §5/D1 + item 8):
 *   - the runtime thinking-probe verdict OVERRIDES the static capability
 *     table in BOTH directions (and INCONCLUSIVE registers nothing);
 *   - the adapter's request gate + advertised metadata follow the EFFECTIVE
 *     (runtime-first) verdict — the single seam P0-5 promised;
 *   - the probe's HTTP verdict mapping is honest (feature-caused rejections
 *     only; auth/quota/server/transport are never verdicts);
 *   - the snapshot never claims more than was probed: declaration =
 *     floor ∪ verified − runtime-rejected, and UNPROBED never fabricates;
 *   - snapshot merge keeps prior non-UNPROBED verdicts (a probe that could
 *     not run must not erase earlier runtime evidence).
 *
 *  P1-2 — unified candidate builder (audit §5 item 5):
 *   - the canonical standard action space covers every control action and
 *     is the single home of the admissibility family sets;
 *   - the PRODUCTION admissible filter never returns an empty action space
 *     and excludes the sensitive family under ASSISTED for every canonical
 *     candidate;
 *   - typed family credit is decided by [StandardActionSpace.familyOf], not
 *     by string prefixes (the metric the audit's startsWith finding).
 */
class ClosureP1InvariantTests {

    @Before
    fun setUp() {
        // The runtime override registry is GLOBAL static state — every test
        // starts from the pure static floor so ordering can never leak.
        GeminiThinkingCapability.clearRuntimeVerdicts()
    }

    @After
    fun tearDown() {
        GeminiThinkingCapability.clearRuntimeVerdicts()
    }

    // ==================================================================
    // P1-1 — runtime override beats the static table
    // ==================================================================

    /**
     * INVARIANT P1-1a — a registered runtime verdict overrides the curated
     * table in BOTH directions, and clearing restores the static floor.
     */
    @Test
    fun `runtime probe verdict overrides the static capability table in both directions`() {
        // Static floor: 2.5 family is SUPPORTED.
        assertEquals(GeminiThinkingSupport.SUPPORTED, GeminiThinkingCapability.forModel("gemini-2.5-flash"))

        // Runtime evidence: this endpoint REJECTED thinkingConfig → override.
        GeminiThinkingCapability.registerRuntimeVerdict("gemini-2.5-flash", GeminiThinkingSupport.UNSUPPORTED)
        assertEquals(
            "a runtime REJECTED verdict must beat the documented SUPPORTED family",
            GeminiThinkingSupport.UNSUPPORTED,
            GeminiThinkingCapability.forModel("gemini-2.5-flash")
        )

        // Runtime evidence: an UNKNOWN family got runtime-ACCEPTED → upgrade.
        assertEquals(GeminiThinkingSupport.UNKNOWN, GeminiThinkingCapability.forModel("some-proxy-model"))
        GeminiThinkingCapability.registerRuntimeVerdict("some-proxy-model", GeminiThinkingSupport.SUPPORTED)
        assertEquals(
            "a runtime ACCEPTED verdict must beat the UNKNOWN default",
            GeminiThinkingSupport.SUPPORTED,
            GeminiThinkingCapability.forModel("some-proxy-model")
        )

        // Clearing the registry restores the pure static floor.
        GeminiThinkingCapability.clearRuntimeVerdicts()
        assertEquals(GeminiThinkingSupport.SUPPORTED, GeminiThinkingCapability.forModel("gemini-2.5-flash"))
        assertEquals(GeminiThinkingSupport.UNKNOWN, GeminiThinkingCapability.forModel("some-proxy-model"))
    }

    /**
     * INVARIANT P1-1b — the adapter's request gate follows the EFFECTIVE
     * verdict (runtime first): an override flips the request body AND the
     * advertised metadata. This is the seam P0-5 documented.
     */
    @Test
    fun `adapter request gate and metadata follow the runtime override`() {
        val request = com.example.domain.core.llm.LlmRequest(
            messages = listOf(
                com.example.domain.core.llm.LlmMessage(
                    role = com.example.domain.core.llm.MessageRole.USER,
                    content = "ping"
                )
            )
        )

        // A statically-SUPPORTED family whose endpoint rejected the probe:
        // the runtime UNSUPPORTED override removes thinkingConfig + reasoning.
        val rejectedModern = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash", apiKeyProvider = { null })
        GeminiThinkingCapability.registerRuntimeVerdict("gemini-2.5-flash", GeminiThinkingSupport.UNSUPPORTED)
        assertFalse(
            "runtime-rejected model must NOT carry thinkingConfig",
            JSONObject(rejectedModern.buildRequestBody(request, stream = false))
                .getJSONObject("generationConfig").has("thinkingConfig")
        )
        assertFalse(
            "runtime-rejected model must NOT advertise reasoning",
            rejectedModern.metadata.supportedCapabilities.contains("reasoning")
        )

        // A statically-UNSUPPORTED family whose endpoint accepted the probe:
        // the runtime SUPPORTED override attaches thinkingConfig + reasoning.
        GeminiThinkingCapability.clearRuntimeVerdicts()
        val upgradedLegacy = GeminiLlmAdapter(defaultModelName = "gemini-2.0-flash", apiKeyProvider = { null })
        assertFalse(
            "precondition: gemini-2.0-flash is statically UNSUPPORTED",
            JSONObject(upgradedLegacy.buildRequestBody(request, stream = false))
                .getJSONObject("generationConfig").has("thinkingConfig")
        )
        GeminiThinkingCapability.registerRuntimeVerdict("gemini-2.0-flash", GeminiThinkingSupport.SUPPORTED)
        assertTrue(
            "runtime-accepted model MUST carry thinkingConfig",
            JSONObject(upgradedLegacy.buildRequestBody(request, stream = false))
                .getJSONObject("generationConfig").has("thinkingConfig")
        )
        assertTrue(
            "runtime-accepted model MUST advertise reasoning",
            upgradedLegacy.metadata.supportedCapabilities.contains("reasoning")
        )
    }

    /**
     * INVARIANT P1-1c — the probe's HTTP verdict mapping is honest: only the
     * invalid-argument family REJECTS (the feature itself was refused);
     * auth / quota / server / model-resolution conditions are INCONCLUSIVE —
     * they must never flip a capability verdict.
     */
    @Test
    fun `probe HTTP mapping is honest - only feature-caused rejections are verdicts`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash", apiKeyProvider = { null })
        assertEquals(CapabilityProbeOutcome.ACCEPTED, adapter.probeOutcomeFor(200))
        assertEquals(CapabilityProbeOutcome.REJECTED, adapter.probeOutcomeFor(400))
        assertEquals(CapabilityProbeOutcome.REJECTED, adapter.probeOutcomeFor(422))
        // Transient/auth/server/model-resolution — never verdicts.
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, adapter.probeOutcomeFor(401))
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, adapter.probeOutcomeFor(403))
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, adapter.probeOutcomeFor(404))
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, adapter.probeOutcomeFor(429))
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, adapter.probeOutcomeFor(500))
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, adapter.probeOutcomeFor(503))
    }

    /**
     * INVARIANT P1-1d — an INCONCLUSIVE probe (no key / transport failure)
     * registers NO override: transient failures must not manufacture
     * capability truth, in either direction.
     */
    @Test
    fun `inconclusive probe registers no override`() = kotlinx.coroutines.runBlocking {
        val adapter = GeminiLlmAdapter(
            defaultModelName = "gemini-2.5-flash",
            apiKeyProvider = { null } // no key → probe cannot run
        )
        val outcome = adapter.probeOptionalFeatureAcceptance()
        assertEquals(CapabilityProbeOutcome.INCONCLUSIVE, outcome)
        assertEquals(
            "no override may be registered from an inconclusive probe",
            null,
            GeminiThinkingCapability.runtimeVerdict("gemini-2.5-flash")
        )
    }

    // ==================================================================
    // P1-1 — the snapshot never claims more than was probed
    // ==================================================================

    private fun snapshot(
        thinking: CapabilityVerdict,
        streaming: CapabilityVerdict = CapabilityVerdict.UNPROBED
    ): OperationalResourceSnapshot = OperationalResourceSnapshot(
        resourceId = "res_p1",
        modelName = "gemini-test",
        protocolId = ServiceProtocolId.GEMINI_NATIVE,
        generation = CapabilityVerdict.VERIFIED,
        thinking = thinking,
        streaming = streaming,
        probedAtEpochMs = 1_000L
    )

    private val reasoningFloor: Set<CapabilityType> = setOf(
        CapabilityType.LLM_GENERATION,
        CapabilityType.STREAMING,
        CapabilityType.REASONING
    )

    private val bareFloor: Set<CapabilityType> = setOf(
        CapabilityType.LLM_GENERATION,
        CapabilityType.STREAMING
    )

    /**
     * INVARIANT P1-1e — declaration = floor ∪ verified − runtime-rejected.
     * The four honest combinations, including the audit's exact complaint
     * (an unprobed OpenAI-compatible preset must NOT declare REASONING).
     */
    @Test
    fun `snapshot declarations are floor plus verified minus runtime-rejected`() {
        // UNPROBED + floor WITHOUT reasoning → no REASONING (the wizard's
        // honest registration-time declaration for unknown families).
        assertFalse(
            snapshot(CapabilityVerdict.UNPROBED).declaredCapabilities(bareFloor)
                .contains(CapabilityType.REASONING)
        )

        // UNPROBED + floor WITH reasoning (documented Gemini family) →
        // REASONING stays declared (documentation is evidence).
        assertTrue(
            snapshot(CapabilityVerdict.UNPROBED).declaredCapabilities(reasoningFloor)
                .contains(CapabilityType.REASONING)
        )

        // VERIFIED → REASONING declared even without documentation (the
        // runtime upgrade path for unknown families).
        assertTrue(
            snapshot(CapabilityVerdict.VERIFIED).declaredCapabilities(bareFloor)
                .contains(CapabilityType.REASONING)
        )

        // UNSUPPORTED → REASONING removed even though documented (the
        // runtime override beats the floor — the proxy-stripping case).
        assertFalse(
            snapshot(CapabilityVerdict.UNSUPPORTED).declaredCapabilities(reasoningFloor)
                .contains(CapabilityType.REASONING)
        )

        // LLM_GENERATION is the admission floor in every combination.
        for (verdict in CapabilityVerdict.entries) {
            assertTrue(
                "LLM_GENERATION must be declared for verdict $verdict",
                snapshot(verdict).declaredCapabilities(bareFloor)
                    .contains(CapabilityType.LLM_GENERATION)
            )
        }
    }

    /**
     * INVARIANT P1-1f — snapshot merge keeps prior non-UNPROBED verdicts:
     * a re-validation whose probe could not run must not ERASE the previous
     * run's runtime evidence.
     */
    @Test
    fun `snapshot merge keeps prior non-unprobed verdicts`() {
        val first = snapshot(CapabilityVerdict.UNSUPPORTED)
        val second = snapshot(CapabilityVerdict.UNPROBED)

        val merged = second.mergedWith(first)
        assertEquals(
            "an unprobed re-run inherits the previous REJECTED verdict",
            CapabilityVerdict.UNSUPPORTED,
            merged.thinking
        )
        assertEquals(
            "generation evidence from the new run is kept",
            CapabilityVerdict.VERIFIED,
            merged.generation
        )

        // A new verdict WINS over a previous one (latest probe evidence).
        val third = snapshot(CapabilityVerdict.VERIFIED)
        assertEquals(CapabilityVerdict.VERIFIED, third.mergedWith(first).thinking)

        // No previous snapshot → the merge is the identity.
        assertEquals(CapabilityVerdict.UNPROBED, second.mergedWith(null).thinking)
    }

    // ==================================================================
    // P1-2 — the unified candidate builder / action space
    // ==================================================================

    /**
     * INVARIANT P1-2a — the canonical standard space carries the control CORE
     * (the terminal + fallback actions the never-empty guarantee relies on)
     * and hands out fresh instances. RETRY is deliberately NOT in the static
     * skeleton: it is a failure-conditioned candidate the context-aware
     * planner generates dynamically (consecutiveFailures >= 1) — its absence
     * from the CONTEXT-FREE measurement space is the honest shape, not a gap.
     */
    @Test
    fun `canonical standard space covers the control core`() {
        val candidateTypes = StandardActionSpace.candidates().map { it.type }.toSet()
        // The fallback action that guarantees a never-empty admissible
        // space (AdmissibleActionSet's injection target) is IN the skeleton.
        assertTrue(
            "ASK_USER (the never-empty fallback) must be in the canonical skeleton",
            DecisionActionType.ASK_USER in candidateTypes
        )
        // The terminal + replan control core.
        assertTrue(DecisionActionType.COMPLETE in candidateTypes)
        assertTrue(DecisionActionType.STOP in candidateTypes)
        assertTrue(DecisionActionType.REPLAN in candidateTypes)
        // Identity: the skeleton matches its declared type set.
        assertEquals(StandardActionSpace.standardCandidateTypes, candidateTypes)
        // Defensive copies — mutating/re-reading never corrupts the skeleton.
        val first = StandardActionSpace.candidates()
        val second = StandardActionSpace.candidates()
        assertTrue(first.isNotEmpty())
        assertTrue(first !== second)
        assertTrue(first.zip(second).all { (a, b) -> a.type == b.type && a.targetId == b.targetId })
    }

    /**
     * INVARIANT P1-2b — the PRODUCTION admissible filter (the same object
     * DecisionService delegates to) never returns an empty action space,
     * even for a candidate list stripped of every control action.
     */
    @Test
    fun `production admissible filter never yields an empty action space`() {
        val executionOnly = listOf(
            DecisionAction(DecisionActionType.EXECUTE_STEP, targetId = "current"),
            DecisionAction(DecisionActionType.SEARCH, targetId = "web")
        )
        for (policy in AutonomyPolicy.entries) {
            val filtered = AdmissibleActionSet.filter(
                candidates = executionOnly,
                contract = TaskContracts.QUICK_CHAT,
                agentAllowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
                effectiveAutonomyPolicy = policy
            )
            assertTrue(
                "filter must never return an empty space (policy=${policy.name})",
                filtered.isNotEmpty()
            )
            assertTrue(
                "the injected fallback must be a control action (policy=${policy.name})",
                filtered.any { it.type in StandardActionSpace.ALWAYS_ADMISSIBLE_CONTROL_ACTIONS }
            )
        }
    }

    /**
     * INVARIANT P1-2c — under ASSISTED autonomy the sensitive family is
     * excluded from the action space for EVERY canonical candidate (the
     * consent-first semantics, now asserted against the production filter
     * over the canonical space itself).
     */
    @Test
    fun `ASSISTED policy excludes the sensitive family from the canonical space`() {
        val filtered = AdmissibleActionSet.filter(
            candidates = StandardActionSpace.candidates(),
            contract = TaskContracts.COMPLEX_TASK,
            agentAllowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.TOOL_EXECUTION
            ),
            effectiveAutonomyPolicy = AutonomyPolicy.ASSISTED
        )
        assertTrue(filtered.isNotEmpty())
        assertFalse(
            "no sensitive action may survive ASSISTED filtering",
            filtered.any { it.type in StandardActionSpace.SENSITIVE_ACTION_FAMILY }
        )
        // Non-sensitive generation stays admissible under ASSISTED.
        assertTrue(filtered.any { it.type == DecisionActionType.EXECUTE_STEP })
    }

    /**
     * INVARIANT P1-2d — typed family credit replaces prefix matching: two
     * actions of the same family earn degraded credit regardless of their
     * names' lexical shape, and no cross-family pair does.
     */
    @Test
    fun `typed family classification decides semantic credit not prefixes`() {
        // Same family (EXECUTION), no shared name prefix — the OLD
        // startsWith("...") metric would score this as a FAILURE.
        assertEquals(
            StandardActionSpace.familyOf(DecisionActionType.EXECUTE_STEP),
            StandardActionSpace.familyOf(DecisionActionType.SEARCH)
        )
        // Same family with a shared prefix — still same family (parity).
        assertEquals(
            StandardActionSpace.familyOf(DecisionActionType.EXECUTE_TOOL),
            StandardActionSpace.familyOf(DecisionActionType.EXECUTE_MCP)
        )
        // Cross-family with a shared prefix — the OLD metric scored these
        // as degraded credit by lexical accident; the typed one refuses.
        assertNotEqualsByFamily(
            DecisionActionType.SELECT_MODEL,
            DecisionActionType.SELECT_TOOL
        )
        // Retrieval pair.
        assertEquals(
            StandardActionSpace.familyOf(DecisionActionType.RETRIEVE_MEMORY),
            StandardActionSpace.familyOf(DecisionActionType.RETRIEVE_KNOWLEDGE)
        )
        // Terminal pair.
        assertEquals(
            StandardActionSpace.familyOf(DecisionActionType.COMPLETE),
            StandardActionSpace.familyOf(DecisionActionType.STOP)
        )
    }

    private fun assertNotEqualsByFamily(a: DecisionActionType, b: DecisionActionType) {
        assertTrue(
            "familyOf($a)=${StandardActionSpace.familyOf(a)} must differ from familyOf($b)=${StandardActionSpace.familyOf(b)}",
            StandardActionSpace.familyOf(a) != StandardActionSpace.familyOf(b)
        )
    }
}
