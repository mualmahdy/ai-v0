package com.example.application.decision

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.ScoredActionCandidate
import com.example.domain.core.task.TaskContract
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskIntentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * DecisionExplanationServiceTest — §31 SAFE DECISION EXPLANATION coverage
 * ============================================================================
 *
 * REPAIR ORDER §31's surface had ZERO direct tests: the explanation is the
 * user-facing diagnosis channel for incorrect agent behavior (structured
 * FACTS — admissible set, exclusions, selected action vs alternatives,
 * policy constraints — never hidden chain-of-thought). This suite pins:
 *
 *   - the full-contract projection (category, rationale, capabilities,
 *     admissible/prohibited codes, selected action + target, score =
 *     confidence, high-level reason = the decision's own rationale);
 *   - the null-contract LEGACY path (honest UNKNOWN intent, empty action
 *     sets, the explicit "no contract" rationale);
 *   - alternatives: TOP-5 only, sorted by finalScore DESCENDING;
 *   - the policy constraint note (present when a policy applies, absent
 *     when none does);
 *   - the Arabic-first render: every fact family reaches the human surface.
 */
class DecisionExplanationServiceTest {

    private fun alternative(
        type: DecisionActionType,
        target: String?,
        score: Float
    ) = ScoredActionCandidate(
        action = DecisionAction(type = type, targetId = target),
        cbrScore = score * 0.5f,
        mdpValue = score * 0.5f,
        finalScore = score,
        confidence = 0.5f,
        reason = "alt-${type.code}"
    )

    private fun decisionResult(
        chosen: DecisionAction,
        alternatives: List<ScoredActionCandidate> = emptyList(),
        confidence: Float = 0.87f
    ) = DecisionResult(
        chosenAction = chosen,
        confidence = confidence,
        rationale = "النموذج الأنسب للمهمة ضمن قيود الشبكة",
        stateSnapshot = DecisionState(taskId = TaskId("task-1")),
        evaluatedAlternatives = alternatives,
        matchedHistoricalCasesCount = 3
    )

    @Test
    fun `a full contract projects every fact family into the explanation`() {
        val contract = TaskContract(
            category = TaskIntentCategory.CODING_TASK,
            requiredCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.CODE_ANALYSIS
            ),
            admissibleActions = setOf(
                DecisionActionType.SELECT_MODEL,
                DecisionActionType.EXECUTE_STEP,
                DecisionActionType.COMPLETE
            ),
            prohibitedActions = setOf(DecisionActionType.EXECUTE_TOOL),
            rationale = "مهمة برمجية: توليد + تحليل ساكن، دون أدوات حساسة."
        )
        val chosen = DecisionAction(
            type = DecisionActionType.EXECUTE_STEP,
            targetId = "gemini-2.5-flash"
        )

        val explanation = DecisionExplanationService.explain(
            contract = contract,
            decisionResult = decisionResult(chosen),
            effectiveAutonomyPolicy = "SUPERVISED"
        )

        assertEquals("CODING_TASK", explanation.intentCategory)
        assertEquals(contract.rationale, explanation.contractRationale)
        assertEquals(
            listOf("LLM_GENERATION", "CODE_ANALYSIS"),
            explanation.requiredCapabilities
        )
        assertEquals(
            setOf("select_model", "execute_step", "complete"),
            explanation.admissibleActionTypes.toSet()
        )
        assertEquals(listOf("execute_tool"), explanation.excludedActionTypes)
        assertEquals("execute_step", explanation.selectedAction)
        assertEquals("gemini-2.5-flash", explanation.selectedTarget)
        assertEquals(0.87f, explanation.score)
        assertEquals("النموذج الأنسب للمهمة ضمن قيود الشبكة", explanation.highLevelReason)
        assertTrue(explanation.policyConstraint!!.contains("SUPERVISED"))
    }

    @Test
    fun `a null contract takes the honest legacy path`() {
        val chosen = DecisionAction(type = DecisionActionType.COMPLETE)

        val explanation = DecisionExplanationService.explain(
            contract = null,
            decisionResult = decisionResult(chosen),
            effectiveAutonomyPolicy = null
        )

        // The legacy path is HONEST: unknown intent, empty sets, and an
        // explicit "no contract" rationale — never fabricated facts.
        assertEquals("UNKNOWN", explanation.intentCategory)
        assertEquals("لا يوجد عقد مهمة (مسار قديم).", explanation.contractRationale)
        assertTrue(explanation.requiredCapabilities.isEmpty())
        assertTrue(explanation.admissibleActionTypes.isEmpty())
        assertTrue(explanation.excludedActionTypes.isEmpty())
        assertNull(explanation.policyConstraint)
        assertEquals("complete", explanation.selectedAction)
        assertNull(explanation.selectedTarget)
    }

    @Test
    fun `alternatives are capped at five and sorted by final score descending`() {
        // SEVEN candidates with deliberately shuffled scores — only the
        // top-5 by finalScore may appear, strongest first.
        val alternatives = listOf(
            alternative(DecisionActionType.SELECT_MODEL, "model-a", 0.30f),
            alternative(DecisionActionType.SELECT_AGENT, "agent-b", 0.91f),
            alternative(DecisionActionType.SEARCH, null, 0.55f),
            alternative(DecisionActionType.RETRIEVE_MEMORY, null, 0.42f),
            alternative(DecisionActionType.SELECT_TOOL, "tool-c", 0.77f),
            alternative(DecisionActionType.CREATE_PLAN, null, 0.10f),
            alternative(DecisionActionType.RETRIEVE_KNOWLEDGE, null, 0.66f)
        )
        val chosen = DecisionAction(type = DecisionActionType.EXECUTE_STEP)

        val explanation = DecisionExplanationService.explain(
            contract = null,
            decisionResult = decisionResult(chosen, alternatives),
            effectiveAutonomyPolicy = null
        )

        assertEquals(5, explanation.alternatives.size)
        val scores = explanation.alternatives.mapNotNull { it.score }
        assertEquals(scores, scores.sortedDescending())
        // The weakest two (0.30, 0.10) are cut; the strongest (0.91) leads.
        assertEquals(0.91f, explanation.alternatives.first().score)
        assertEquals("select_agent", explanation.alternatives.first().action)
        assertTrue(explanation.alternatives.none { it.score == 0.30f || it.score == 0.10f })
    }

    @Test
    fun `the render carries every fact family to the human surface`() {
        val contract = TaskContract(
            category = TaskIntentCategory.KNOWLEDGE_TASK,
            requiredCapabilities = setOf(CapabilityType.EMBEDDING),
            admissibleActions = setOf(DecisionActionType.RETRIEVE_KNOWLEDGE),
            prohibitedActions = setOf(DecisionActionType.EXECUTE_TOOL),
            rationale = "استرجاع المعرفة أولاً."
        )
        val chosen = DecisionAction(
            type = DecisionActionType.RETRIEVE_KNOWLEDGE,
            targetId = "kb-main"
        )

        val rendered = DecisionExplanationService.render(
            DecisionExplanationService.explain(
                contract = contract,
                decisionResult = decisionResult(chosen),
                effectiveAutonomyPolicy = "ASSISTED"
            )
        )

        assertTrue(rendered.contains("تفسير القرار"))
        assertTrue(rendered.contains("KNOWLEDGE_TASK"))
        assertTrue(rendered.contains("EMBEDDING"))
        assertTrue(rendered.contains("retrieve_knowledge"))
        assertTrue(rendered.contains("kb-main"))
        assertTrue(rendered.contains("execute_tool"))
        assertTrue(rendered.contains("ASSISTED"))
        assertTrue(rendered.contains("0.87"))
        assertTrue(rendered.contains("السبب العام"))
    }

    @Test
    fun `the render omits empty fact families instead of printing blanks`() {
        val chosen = DecisionAction(type = DecisionActionType.COMPLETE)

        val rendered = DecisionExplanationService.render(
            DecisionExplanationService.explain(
                contract = null,
                decisionResult = decisionResult(chosen),
                effectiveAutonomyPolicy = null
            )
        )

        // No capabilities, no admissible set, no exclusions, no policy, no
        // alternatives — none of those headers may print.
        assertTrue(!rendered.contains("القدرات المطلوبة"))
        assertTrue(!rendered.contains("الأفعال المسموحة"))
        assertTrue(!rendered.contains("الأفعال المستبعدة"))
        assertTrue(!rendered.contains("قيد السياسة"))
        assertTrue(!rendered.contains("بدائل مرتبة"))
        // But the selected action and the general reason always print.
        assertTrue(rendered.contains("complete"))
        assertTrue(rendered.contains("السبب العام"))
    }
}
