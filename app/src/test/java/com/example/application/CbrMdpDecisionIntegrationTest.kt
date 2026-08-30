package com.example.application

import com.example.application.decision.DecisionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.agent.AgentId
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CbrMdpDecisionIntegrationTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var cbrMdpEngine: CbrMdpEngine
    private lateinit var decisionService: DecisionService
    private lateinit var caseBase: CaseBase

    @Before
    fun setup() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        caseBase = CaseBase()
        cbrMdpEngine = CbrMdpEngine(caseBase = caseBase)
        decisionService = DecisionService(
            cbrMdpEngine = cbrMdpEngine,
            componentRegistry = registry,
            securityGuard = securityGuard
        )
    }

    @Test
    fun `test decision service generates candidate actions and selects best action`() {
        val task = TaskDefinition(
            id = TaskId("task-eval-1"),
            assignedAgentId = AgentId("code_craftsman"),
            input = TaskInput("اكتب كود دالة لحساب الأعداد الأولية في Kotlin")
        )

        val context = decisionService.buildDecisionContext(
            task = task,
            networkPolicy = NetworkPolicy.HYBRID,
            isNetworkAvailable = true,
            complexity = 0.6f
        )

        val candidates = decisionService.generateCandidateActions(context)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it.type == DecisionActionType.EXECUTE_STEP || it.type == DecisionActionType.SELECT_TOOL || it.type == DecisionActionType.EXECUTE_SKILL || it.type == DecisionActionType.SELECT_AGENT })

        val decisionResult = decisionService.evaluate(context)
        assertNotNull(decisionResult.chosenAction)
        assertTrue(decisionResult.confidence > 0.0f)
        assertTrue(decisionResult.rationale.isNotBlank())
        assertTrue(decisionResult.evaluatedAlternatives.isNotEmpty())
    }

    @Test
    fun `test offline policy governance constrains external search action`() {
        val task = TaskDefinition(
            id = TaskId("task-eval-2"),
            assignedAgentId = AgentId("architect_orchestrator"),
            input = TaskInput("ابحث عن أحدث أخبار الذكاء الاصطناعي على الإنترنت")
        )

        val offlineContext = decisionService.buildDecisionContext(
            task = task,
            networkPolicy = NetworkPolicy.OFFLINE,
            isNetworkAvailable = false,
            complexity = 0.8f
        )

        val decisionResult = decisionService.evaluate(offlineContext)
        // Governance rule ensures external search falls back to local knowledge retrieval when offline
        assertTrue(
            decisionResult.chosenAction.type != DecisionActionType.SEARCH ||
                    decisionResult.chosenAction.payload["offline_fallback"] == "true"
        )
    }

    @Test
    fun `test observation recording updates uncertainty and expands case base`() {
        val task = TaskDefinition(
            id = TaskId("task-eval-3"),
            assignedAgentId = AgentId("code_craftsman"),
            input = TaskInput("قم بإنشاء مشروع جديد مع هندسة برمجية نظيفة")
        )

        val context = decisionService.buildDecisionContext(
            task = task,
            uncertaintyScore = 0.5f
        )

        val decisionResult = decisionService.evaluate(context)
        val initialCasesCount = caseBase.getAllCases().size
        val initialState = context.toDecisionState()

        val observation = EnvironmentObservation(
            action = decisionResult.chosenAction,
            isSuccess = true,
            actualLatencyMs = 450L,
            tokensConsumed = 120,
            outputSummary = "Code generated successfully",
            feedbackReward = 1.0f
        )

        val updatedState = decisionService.recordObservation(initialState, observation)

        // Uncertainty should decrease on success
        assertTrue(updatedState.uncertaintyScore < 0.5f)
        assertEquals(0, updatedState.consecutiveFailures)
        // Case Base should have stored the newly learned case
        assertEquals(initialCasesCount + 1, caseBase.getAllCases().size)
    }
}
