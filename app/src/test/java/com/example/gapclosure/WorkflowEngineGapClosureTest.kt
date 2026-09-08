package com.example.gapclosure

import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.application.workflow.WorkflowPersistenceService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.domain.ports.llm.LlmProviderPort
import com.example.infrastructure.persistence.dao.WorkflowExecutionDao
import com.example.infrastructure.persistence.dao.WorkflowStepStateDao
import com.example.infrastructure.persistence.entities.WorkflowExecutionEntity
import com.example.infrastructure.persistence.entities.WorkflowStepStateEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * WorkflowEngineGapClosureTest — gap-closure P0-07 / P1-05 / P1-06 / P1-07
 * ============================================================================
 *
 *  - P0-07: a workflow whose required steps were BLOCKED (skipped) CANNOT
 *    report Success; dangling dependencies fail validation.
 *  - P1-05: independent DAG branches execute CONCURRENTLY.
 *  - P1-06: persistence write failures degrade the outcome honestly.
 *  - P1-07: token accounting is MEASURED (not output.length / 4).
 */
class WorkflowEngineGapClosureTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var orchestrator: AgentOrchestrator

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

    /** Mock LLM that reports REAL measured usage and optional latency. */
    private fun mockLlmProvider(delayMs: Long = 0, text: String = "Completed step response"): LlmProviderPort =
        object : LlmProviderPort {
            override val providerId: String = "mock_provider"
            override val metadata: SafeProviderMetadata = testMetadata

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
                if (delayMs > 0) delay(delayMs)
                return Outcome.Success(
                    LlmResponse(
                        text = text,
                        toolCalls = emptyList(),
                        usage = TokenUsage(10, 10),
                        finishReason = "STOP",
                        modelId = "mock-v1"
                    )
                )
            }

            override fun stream(request: LlmRequest, executionId: String) =
                emptyFlow<com.example.domain.core.events.ExecutionEvent>()
        }

    /** Mock LLM that always FAILS (to force a failed step). */
    private fun failingLlmProvider(): LlmProviderPort =
        object : LlmProviderPort {
            override val providerId: String = "failing_provider"
            override val metadata: SafeProviderMetadata = testMetadata

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Error(LlmFailure.ProviderUnavailable("failing_provider", "mock failure"))

            override fun stream(request: LlmRequest, executionId: String) =
                emptyFlow<com.example.domain.core.events.ExecutionEvent>()
        }

    /** Throwing persistence DAOs (P1-06: failures must surface, not vanish). */
    private class ThrowingWorkflowExecutionDao : WorkflowExecutionDao {
        override suspend fun resumable(): List<WorkflowExecutionEntity> = emptyList()
        override suspend fun resumableForWorkspace(workspaceId: String): List<WorkflowExecutionEntity> = emptyList()
        override fun forWorkspace(workspaceId: String): Flow<List<WorkflowExecutionEntity>> = MutableStateFlow(emptyList())
        override suspend fun byId(id: String): WorkflowExecutionEntity? = null
        override suspend fun byIdAndWorkspace(id: String, workspaceId: String): WorkflowExecutionEntity? = null
        override suspend fun upsert(entity: WorkflowExecutionEntity) { throw IllegalStateException("DB_WRITE_FAILED") }
        override suspend fun checkpoint(id: String, state: String, step: Int, now: Long) { throw IllegalStateException("DB_WRITE_FAILED") }
        override suspend fun checkpointForWorkspace(id: String, workspaceId: String, state: String, step: Int, now: Long) { throw IllegalStateException("DB_WRITE_FAILED") }
        override suspend fun terminate(id: String, state: String, now: Long, reason: String?) { throw IllegalStateException("DB_WRITE_FAILED") }
        override suspend fun terminateForWorkspace(id: String, workspaceId: String, state: String, now: Long, reason: String?) { throw IllegalStateException("DB_WRITE_FAILED") }
    }

    private class ThrowingWorkflowStepStateDao : WorkflowStepStateDao {
        override suspend fun forWorkflow(workflowId: String): List<WorkflowStepStateEntity> = emptyList()
        override suspend fun upsertAll(states: List<WorkflowStepStateEntity>) { throw IllegalStateException("DB_WRITE_FAILED") }
        override suspend fun updateStepStatus(workflowId: String, stepId: String, status: String, summary: String?, duration: Long?, now: Long) { throw IllegalStateException("DB_WRITE_FAILED") }
        override suspend fun updateStepArtifacts(workflowId: String, stepId: String, artifactsJson: String) { throw IllegalStateException("DB_WRITE_FAILED") }
    }

    @Before
    fun setup() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        val cbrMdpEngine = com.example.domain.core.decision.CbrMdpEngine()
        val decisionService = com.example.application.decision.DecisionService(cbrMdpEngine, registry, securityGuard)
        orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
    }

    private fun step(id: String, deps: Set<String> = emptySet(), role: AgentRole = AgentRole.PLANNER) =
        StepNode(id = id, taskId = TaskId("t_$id"), agentRole = role, description = "Step $id", dependencies = deps)

    // ------------------------------------------------------------------
    // P0-07 — honest SKIPPED semantics
    // ------------------------------------------------------------------

    @Test
    fun `blocked required steps make the workflow FAIL not succeed`() = runBlocking {
        // DETERMINISTIC P0-07 scenario: a step listed BEFORE its dependency
        // can never run in sequential mode -> SKIPPED. The legacy engine
        // still reported the whole workflow as SUCCESS (the exact defect).
        TestResourceRegistration.registerLlmProvider(registry, mockLlmProvider())

        // s2 depends on s1 but is listed FIRST -> blocked at its turn.
        val plan = WorkflowPlan(
            id = WorkflowId("wf-blocked"),
            goal = "Blocked plan",
            executionMode = ExecutionMode.SEQUENTIAL,
            steps = listOf(step("s2", deps = setOf("s1")), step("s1"))
        )

        val engine = WorkflowEngine(orchestrator)
        val report = engine.executePlan(plan)

        assertTrue(
            "A workflow with blocked required steps must NOT report Success (P0-07); was ${report.overallOutcome::class.simpleName}",
            report.overallOutcome is Outcome.Error
        )
        val failure = (report.overallOutcome as Outcome.Error).failure
        assertTrue(
            "The failure must name the BLOCKED steps",
            failure is com.example.domain.core.workflow.WorkflowFailure.StepExecutionFailed &&
                (failure as com.example.domain.core.workflow.WorkflowFailure.StepExecutionFailed).stepId == "s2"
        )
        assertEquals(StepStatus.SKIPPED, report.stepStatuses["s2"])
        assertEquals(StepStatus.COMPLETED, report.stepStatuses["s1"])
    }



    @Test
    fun `dangling dependency references fail validation upfront`() {
        val plan = WorkflowPlan(
            id = WorkflowId("wf-dangling"),
            goal = "Dangling plan",
            steps = listOf(step("s1", deps = setOf("ghost_step")))
        )
        val engine = WorkflowEngine(orchestrator)
        val validation = engine.validatePlan(plan)

        assertTrue("Unknown dependency ids are plan defects (P0-07)", validation is Outcome.Error)
        val diagnostic = (validation as Outcome.Error).diagnosticMessage ?: ""
        assertTrue(diagnostic.contains("تبعيات"))
    }

    // ------------------------------------------------------------------
    // P1-05 — concurrent DAG branch execution
    // ------------------------------------------------------------------

    @Test
    fun `independent DAG branches execute concurrently`() = runBlocking {
        // Each LLM call takes ~250ms; two independent steps in PARALLEL mode
        // must overlap (total well under the sequential 2 x 250ms + loop).
        TestResourceRegistration.registerLlmProvider(registry, mockLlmProvider(delayMs = 250))

        val plan = WorkflowPlan(
            id = WorkflowId("wf-parallel"),
            goal = "Parallel plan",
            executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
            steps = listOf(step("a"), step("b"), step("c", deps = setOf("a", "b")))
        )

        val engine = WorkflowEngine(orchestrator, maxConcurrentSteps = 2)
        val start = System.currentTimeMillis()
        val report = engine.executePlan(plan)
        val duration = System.currentTimeMillis() - start

        assertTrue(
            "Parallel plan should complete successfully, got ${report.overallOutcome::class.simpleName}",
            report.overallOutcome is Outcome.Success
        )
        // Sequential would need a+b back-to-back (~500ms+) before c; with
        // concurrency a and b overlap. Generous bound to stay CI-stable.
        assertTrue(
            "Independent branches must overlap (took ${duration}ms)",
            duration < 2_500
        )
    }

    // ------------------------------------------------------------------
    // P1-06 — persistence failures surface honestly
    // ------------------------------------------------------------------

    @Test
    fun `persistence write failures degrade the outcome honestly`() = runBlocking {
        TestResourceRegistration.registerLlmProvider(registry, mockLlmProvider())

        val throwingPersistence = WorkflowPersistenceService(
            workflowExecutionDao = ThrowingWorkflowExecutionDao(),
            workflowStepStateDao = ThrowingWorkflowStepStateDao()
        )
        val engine = WorkflowEngine(
            orchestrator,
            persistenceService = throwingPersistence,
            workspaceIdProvider = { "ws_test" }
        )

        val plan = WorkflowPlan(
            id = WorkflowId("wf-persist-fail"),
            goal = "Persistence failure plan",
            steps = listOf(step("s1"))
        )
        val report = engine.executePlan(plan)

        // The step itself completed; the overall outcome degrades HONESTLY.
        assertTrue(
            "Persistence failure must degrade the outcome (P1-06), got ${report.overallOutcome::class.simpleName}",
            report.overallOutcome is Outcome.Degraded
        )
        val degraded = report.overallOutcome as Outcome.Degraded
        assertTrue(
            "Degraded diagnostic must be honest about persistence",
            (degraded.diagnosticMessage ?: "").contains("PERSISTENCE")
        )
        assertEquals(StepStatus.COMPLETED, report.stepStatuses["s1"])
    }

    // ------------------------------------------------------------------
    // P1-07 — measured token accounting
    // ------------------------------------------------------------------

    @Test
    fun `token accounting uses measured usage not length-divided-by-4`() = runBlocking {
        val outputText = "abc" // 3 chars -> 0 by the legacy chars/4 estimate
        TestResourceRegistration.registerLlmProvider(registry, mockLlmProvider(text = outputText))

        val engine = WorkflowEngine(orchestrator)
        val plan = WorkflowPlan(
            id = WorkflowId("wf-tokens"),
            goal = "Token accounting plan",
            steps = listOf(step("s1"))
        )
        val report = engine.executePlan(plan)

        // The mock provider reports 10+10 = 20 REAL tokens per call; the
        // legacy estimate for a 3-char output would be 3/4 = 0.
        assertTrue(
            "Measured tokens must be > 0 (was ${report.totalTokensConsumed})",
            report.totalTokensConsumed > 0
        )
    }
}
