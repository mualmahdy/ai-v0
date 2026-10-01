package com.example.application.decision

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.AdmissibleActionSet
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskContracts
import com.example.domain.core.task.TaskId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * EMERGENCY HOTFIX R2 — REGRESSION SUITE: the permanent, non-isolated
 * provider-failure state ("لا توجد أفعال مسموحة … مطلوب إرشاد المستخدم"
 * answering every message after a Google 400 streak — surviving restarts,
 * session/project switches and role changes).
 * ============================================================================
 *
 * These tests pin the two root-cause fixes that break that state:
 *
 *  1. AdmissibleActionSet.filter never injects the guidance fallback while a
 *     viable action survives filtering (ordinary chat = generation actions,
 *     which are NOT control actions — the old "no control action" trigger
 *     made EVERY chat decision carry a synthetic ASK_USER that a poisoned
 *     CBR score could outrank).
 *
 *  2. CbrMdpEngine failure credit is RESOURCE-SCOPED: a failed case from one
 *     provider never zeroes the CBR score of another provider's candidate.
 */
class HotfixRound2DecisionIsolationTest {

    // ------------------------------------------------------------------
    // 1. Fallback discipline
    // ------------------------------------------------------------------

    @Test
    fun `a viable generation set never carries the injected guidance fallback`() {
        val candidates = listOf(
            DecisionAction(
                type = DecisionActionType.SELECT_MODEL,
                targetId = "res:provider-a:llm:model-x"
            ),
            DecisionAction(
                type = DecisionActionType.EXECUTE_STEP,
                targetId = "res:provider-a:llm:model-x"
            )
        )
        val filtered = AdmissibleActionSet.filter(
            candidates = candidates,
            contract = TaskContracts.CHAT,
            agentAllowedCapabilities = null,
            effectiveAutonomyPolicy = AutonomyPolicy.SUPERVISED
        )
        // The two generation actions survive untouched — NO synthetic
        // ASK_USER competes with them.
        assertEquals(2, filtered.size)
        assertFalse(filtered.any { it.type == DecisionActionType.ASK_USER })
        assertTrue(filtered.all { it.type != DecisionActionType.ASK_USER })
    }

    @Test
    fun `a truly empty admissible set still gets the never-empty fallback`() {
        val filtered = AdmissibleActionSet.filter(
            candidates = emptyList(),
            contract = TaskContracts.CHAT,
            agentAllowedCapabilities = null,
            effectiveAutonomyPolicy = null
        )
        assertEquals(1, filtered.size)
        assertEquals(DecisionActionType.ASK_USER, filtered.first().type)
        assertEquals("empty_admissible_action_set", filtered.first().targetId)
    }

    @Test
    fun `an inadmissible-only candidate list still gets the fallback (not silence)`() {
        // Tool actions under a CHAT contract are all filtered out — the
        // fallback is the honest escape hatch, and the planner's own ASK_USER
        // (when present among the candidates) wins over the synthetic one.
        val candidates = listOf(
            DecisionAction(type = DecisionActionType.EXECUTE_TOOL, targetId = "tool"),
            DecisionAction(
                type = DecisionActionType.ASK_USER,
                targetId = "no_llm_resource_available",
                payload = mapOf("reason" to "لا يوجد مورد LLM متاح.")
            )
        )
        val filtered = AdmissibleActionSet.filter(
            candidates = candidates,
            contract = TaskContracts.CHAT,
            agentAllowedCapabilities = setOf(CapabilityType.TOOL_EXECUTION),
            effectiveAutonomyPolicy = null
        )
        assertEquals(1, filtered.size)
        assertEquals("no_llm_resource_available", filtered.first().targetId)
    }

    // ------------------------------------------------------------------
    // 2. Resource-scoped failure credit in the CBR engine
    // ------------------------------------------------------------------

    private fun failedActionOn(resourceId: String): DecisionAction = DecisionAction(
        type = DecisionActionType.EXECUTE_STEP,
        targetId = resourceId,
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId(resourceId),
            providerId = "google",
            serviceId = "llm",
            configurationVersion = 1L,
            requiredCapabilities = emptySet(),
            rationale = "test",
            confidence = 0.9f
        )
    )

    @Test
    fun `a failed provider case does not poison another provider's candidate`() = runBlocking {
        val caseBase = CaseBase()
        val engine = CbrMdpEngine(caseBase = caseBase)

        // The 400 streak on the Google resource: one hard failure recorded
        // into the durable case base (feedbackReward -1.0).
        engine.processObservationAndUpdateBelief(
            state = DecisionState(taskId = TaskId("t-poisoned")),
            observation = EnvironmentObservation(
                action = failedActionOn("res:google:llm:gemini"),
                isSuccess = false,
                actualLatencyMs = 500L,
                tokensConsumed = 0,
                outputSummary = "HTTP 400",
                errorDescription = "HTTP 400",
                feedbackReward = -1.0f
            ),
            nextStateAdmissibleActions = setOf(
                DecisionActionType.EXECUTE_STEP,
                DecisionActionType.SELECT_MODEL,
                DecisionActionType.ASK_USER
            )
        )
        assertTrue(caseBase.getAllCases().isNotEmpty())

        // A LATER turn on a DIFFERENT provider: the engine must choose the
        // new provider's EXECUTE_STEP — not the guidance ASK_USER. (Before
        // the fix, the type-level failure credit zeroed the new candidate's
        // CBR score and the injected ASK_USER won — permanently.)
        val decision = engine.evaluateAndSelectAction(
            state = DecisionState(taskId = TaskId("t-recovery")),
            candidateActions = listOf(
                DecisionAction(
                    type = DecisionActionType.EXECUTE_STEP,
                    targetId = "res:other-provider:llm:model",
                    estimatedLatencyMs = 750L
                ),
                DecisionAction(
                    type = DecisionActionType.ASK_USER,
                    targetId = "guidance",
                    payload = mapOf("reason" to "مطلوب إرشاد المستخدم.")
                )
            )
        )
        assertEquals(
            "a different provider's execution step must not inherit the Google failure",
            DecisionActionType.EXECUTE_STEP,
            decision.chosenAction.type
        )
    }

    @Test
    fun `the SAME provider keeps its honest failure credit`() = runBlocking {
        val caseBase = CaseBase()
        val engine = CbrMdpEngine(caseBase = caseBase)
        engine.processObservationAndUpdateBelief(
            state = DecisionState(taskId = TaskId("t-poisoned-2")),
            observation = EnvironmentObservation(
                action = failedActionOn("res:google:llm:gemini"),
                isSuccess = false,
                actualLatencyMs = 500L,
                tokensConsumed = 0,
                outputSummary = "HTTP 400",
                errorDescription = "HTTP 400",
                feedbackReward = -1.0f
            ),
            nextStateAdmissibleActions = setOf(
                DecisionActionType.EXECUTE_STEP,
                DecisionActionType.SELECT_MODEL,
                DecisionActionType.ASK_USER
            )
        )

        // The CBR score of the SAME resource's candidate is honestly low:
        // the failed case still counts against its own resource.
        val sameResourceCandidate = DecisionAction(
            type = DecisionActionType.EXECUTE_STEP,
            targetId = "res:google:llm:gemini",
            estimatedLatencyMs = 750L
        )
        val cleanCandidate = DecisionAction(
            type = DecisionActionType.EXECUTE_STEP,
            targetId = "res:fresh-provider:llm:model",
            estimatedLatencyMs = 750L
        )
        val decision = engine.evaluateAndSelectAction(
            state = DecisionState(taskId = TaskId("t-compare")),
            candidateActions = listOf(sameResourceCandidate, cleanCandidate)
        )
        assertEquals(
            "a clean provider outranks the resource that actually failed",
            "res:fresh-provider:llm:model",
            decision.chosenAction.targetId
        )
    }
}
