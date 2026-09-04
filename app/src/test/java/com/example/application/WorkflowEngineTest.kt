package com.example.application

import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.registry.ComponentRegistry
import com.example.application.testing.TestResourceRegistration
import com.example.application.security.SecurityGuardService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowFailure
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkflowEngineTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var workflowEngine: WorkflowEngine

    private val testMetadata = SafeProviderMetadata(
        id = "mock_provider",
        name = "Mock",
        providerType = "MOCK",
        defaultModel = "mock-v1",
        isConfigured = true,
        isOnline = true,
        isLocal = false,
        supportedCapabilities = listOf("mock-v1")
    )

    @Before
    fun setup() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        val cbrMdpEngine = com.example.domain.core.decision.CbrMdpEngine()
        val decisionService = com.example.application.decision.DecisionService(cbrMdpEngine, registry, securityGuard)
        orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
        workflowEngine = WorkflowEngine(orchestrator)
    }

    @Test
    fun `test detect cyclic dependency in workflow plan`() {
        val stepA = StepNode("step_a", TaskId("t-a"), AgentRole.PLANNER, "Step A", dependencies = setOf("step_b"))
        val stepB = StepNode("step_b", TaskId("t-b"), AgentRole.CODER, "Step B", dependencies = setOf("step_a"))

        val cyclicPlan = WorkflowPlan(
            id = WorkflowId("wf-cyclic"),
            goal = "Cyclic Goal",
            steps = listOf(stepA, stepB)
        )

        val validation = workflowEngine.validatePlan(cyclicPlan)
        assertTrue(validation is Outcome.Error)
        assertTrue((validation as Outcome.Error).failure is WorkflowFailure.CyclicDependencyDetected)
    }

    @Test
    fun `test valid linear DAG execution succeeds`() = runBlocking {
        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_provider"
            override val metadata: SafeProviderMetadata = testMetadata

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
                return Outcome.Success(LlmResponse(text = "Completed step response", toolCalls = emptyList(), usage = TokenUsage(10, 10), finishReason = "STOP", modelId = "mock-v1"))
            }

            override fun stream(request: LlmRequest, executionId: String) = emptyFlow<com.example.domain.core.events.ExecutionEvent>()
        }

        TestResourceRegistration.registerLlmProvider(registry, mockProvider)

        val step1 = StepNode("s1", TaskId("t-1"), AgentRole.PLANNER, "Plan architecture", dependencies = emptySet())
        val step2 = StepNode("s2", TaskId("t-2"), AgentRole.CODER, "Implement models", dependencies = setOf("s1"))

        val validPlan = WorkflowPlan(
            id = WorkflowId("wf-valid"),
            goal = "Build Clean Module",
            steps = listOf(step1, step2)
        )

        val report = workflowEngine.executePlan(validPlan)
        assertTrue(report.overallOutcome is Outcome.Success)
        assertEquals(StepStatus.COMPLETED, report.stepStatuses["s1"])
        assertEquals(StepStatus.COMPLETED, report.stepStatuses["s2"])
    }
}
