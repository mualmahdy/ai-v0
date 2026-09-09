package com.example.gapclosure

import com.example.application.decision.DecisionContext
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.tools.ToolPort
import com.example.application.governed.GovernedPipelineFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P0-1 / P1-2 / P1-11 (audit 2026 §15/§10/§21) — Universal Admission
 * Boundary enforcement proofs on the REAL agent-loop execution path.
 * ============================================================================
 *
 * The audit demanded tests that prove "component CANNOT be bypassed", not
 * "component works". This file proves exactly that for the execution kernel:
 *
 *  1. An UNWIRED admission gate FAILS CLOSED (no ungoverned tool execution).
 *  2. A REVOKED tool cannot execute through the agent-loop path even though
 *     its adapter is still registered (P1-2 — lifecycle is an execution
 *     authority).
 *  3. An admission DENY (rate limit) blocks the tool call at the boundary
 *     (P1-11 — one pipeline governs every path).
 *  4. Positive control: the SAME wiring admits a healthy tool (the gate is
 *     not "deny everything").
 */
class UniversalAdmissionEnforcementTest {

    private class OkLlm : LlmProviderPort {
        override val providerId = "fake_llm"
        override val metadata = SafeProviderMetadata(
            id = "fake_llm", name = "Fake", providerType = "FAKE",
            defaultModel = "fake-1", isConfigured = true, isOnline = true, isLocal = false
        )
        override suspend fun generate(request: LlmRequest): com.example.domain.core.Outcome<LlmResponse, LlmFailure> =
            com.example.domain.core.Outcome.Success(
                LlmResponse(text = "ok", usage = TokenUsage(1, 1), finishReason = "STOP", modelId = "fake-1")
            )
        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            emit(ExecutionEvent.Completed(executionId, "ok", 5))
        }
    }

    private class CountingTool(private val name: String = "plain_tool") : ToolPort {
        var executeCount = 0
        override val declaration = ToolDeclaration(
            name = name,
            description = "test tool",
            parameters = emptyList(),
            isSensitive = false,
            requiresHumanConsent = false
        )
        override suspend fun execute(input: ToolInput): com.example.domain.core.Outcome<ToolOutput, ToolFailure> {
            executeCount++
            return com.example.domain.core.Outcome.Success(ToolOutput(content = "done"))
        }
    }

    private val testAgent = com.example.domain.core.agent.AgentDefinition(
        identity = com.example.domain.core.agent.AgentIdentity(
            id = com.example.domain.core.agent.AgentId("agent_admission"),
            name = "وكيل الاختبار",
            role = com.example.domain.core.agent.AgentRole.CODER,
            description = "test",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
        budget = com.example.domain.core.agent.AgentBudget(maxTokens = 30000)
    )

    private fun newContext() = DecisionContext(
        task = TaskDefinition(
            id = TaskId("t-${System.nanoTime()}"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput(rawPrompt = "test"),
            budget = TaskBudget(tokenLimit = 30000)
        )
    )

    private fun toolAction(toolName: String) = DecisionAction(
        type = DecisionActionType.EXECUTE_TOOL,
        targetId = toolName,
        payload = mapOf("x" to "y"),
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId(toolName),
            providerId = "in_app",
            serviceId = "in_app_tools",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.TOOL_EXECUTION),
            rationale = "test",
            confidence = 0.9f
        )
    )

    private fun buildService(tool: ToolPort): Pair<ExecutionService, ComponentRegistry> {
        val registry = ComponentRegistry()
        TestResourceRegistration.registerLlmProvider(registry, OkLlm())
        registry.runtimeAdapterResolver.registerToolAdapter(ResourceId(tool.declaration.name), tool)
        registry.resourceRegistry.registerResource(
            com.example.domain.core.resource.ResourceRecord(
                resourceId = ResourceId(tool.declaration.name),
                providerId = tool.declaration.name,
                serviceId = tool.declaration.name,
                resourceType = com.example.domain.core.resource.ResourceType.TOOL,
                capabilities = setOf(CapabilityType.TOOL_EXECUTION),
                configurationVersion = 1L,
                lifecycleState = com.example.domain.core.resource.ResourceLifecycleState.ENABLED,
                runtimeSupported = true,
                healthStatus = com.example.domain.core.provider.HealthStatus.HEALTHY,
                isLocal = true
            )
        )
        return ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        ) to registry
    }

    // ------------------------------------------------------------------
    // 1. UNWIRED gate FAILS CLOSED (no ungoverned execution path).
    // ------------------------------------------------------------------

    @Test
    fun `an unwired admission gate fails CLOSED - the tool never runs`() = runBlocking {
        val tool = CountingTool()
        val (executionService, _) = buildService(tool)
        // admissionControl deliberately NOT wired.

        val result = executionService.executeAction(toolAction("plain_tool"), newContext(), testAgent)

        assertTrue("Unwired gate must fail closed: ${result.errorDescription}", !result.isSuccess)
        assertTrue(
            "Must cite the missing gate explicitly: ${result.errorDescription}",
            result.errorDescription!!.contains("ADMISSION_ENFORCEMENT_UNAVAILABLE")
        )
        assertEquals("The tool must NOT have executed", 0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 2. REVOKED tool cannot execute from ANY path (P1-2).
    // ------------------------------------------------------------------

    @Test
    fun `a REVOKED tool is refused even though its adapter is still registered`() = runBlocking {
        val tool = CountingTool()
        val (executionService, _) = buildService(tool)
        executionService.admissionControl =
            GovernedPipelineFactory.admissionForTools(tool.declaration)

        // The lifecycle authority says the tool is revoked (adapter still
        // registered in the resolver above — the bypass the audit found).
        val revokedTools = setOf("plain_tool")
        executionService.toolLifecycleEnforcer = { toolName -> toolName !in revokedTools }

        val result = executionService.executeAction(toolAction("plain_tool"), newContext(), testAgent)

        assertTrue("Revoked tool must be refused: ${result.errorDescription}", !result.isSuccess)
        assertTrue(
            "Must cite the lifecycle refusal: ${result.errorDescription}",
            result.errorDescription!!.contains("TOOL_REVOKED")
        )
        assertEquals("The revoked tool must NOT have executed", 0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 3. Admission DENY (rate limit) blocks the tool at the boundary.
    // ------------------------------------------------------------------

    @Test
    fun `an admission rate-limit DENY blocks the execution path`() = runBlocking {
        val tool = CountingTool()
        val (executionService, _) = buildService(tool)

        // A DENYING rate-limit gate inside the admission pipeline.
        val rateLimit = com.example.application.governed.ScriptableRateLimit().apply { deny() }
        val admission = com.example.application.governed.AdmissionControlService(
            toolDeclarations = com.example.application.governed.ToolDeclarationResolver {
                if (it == "plain_tool") tool.declaration else null
            },
            principalAuthorization = com.example.application.governed.FakePrincipalAuthorization(),
            securityGuard = SecurityGuardService(),
            budgetAuthorization = com.example.application.governed.ScriptableBudgetGate {
                com.example.application.governed.BudgetAuthorizationOutcome(
                    com.example.domain.core.security.governance.BudgetAuthorizationVerdict.ALLOWED, "ok"
                )
            },
            rateLimitCheck = rateLimit,
            approvalGate = com.example.application.governed.HumanApprovalGate(
                store = com.example.infrastructure.governed.InMemoryHumanApprovalStore()
            ),
            sandboxService = com.example.application.governed.SandboxLifecycleService(
                hostIsolationLevel = com.example.domain.core.runtime.IsolationLevel.APP_SANDBOX_BEST_EFFORT
            ),
            auditSink = com.example.application.governed.RecordingAuditSink(),
            workspaceRootResolver = { null }
        )
        executionService.admissionControl = admission

        val result = executionService.executeAction(toolAction("plain_tool"), newContext(), testAgent)

        assertTrue("Rate-limited admission must block: ${result.errorDescription}", !result.isSuccess)
        assertTrue(
            "Must cite the admission denial: ${result.errorDescription}",
            result.errorDescription!!.contains("ADMISSION_DENIED")
        )
        assertEquals("The rate-limited tool must NOT have executed", 0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 4. Positive control — the SAME wiring admits a healthy tool.
    // ------------------------------------------------------------------

    @Test
    fun `a properly admitted tool executes (positive control)`() = runBlocking {
        val tool = CountingTool()
        val (executionService, _) = buildService(tool)
        executionService.admissionControl =
            GovernedPipelineFactory.admissionForTools(tool.declaration)

        val result = kotlinx.coroutines.withContext(
            com.example.domain.core.execution.ExecutionScope("exec_ok", "ws_admission")
        ) {
            executionService.executeAction(toolAction("plain_tool"), newContext(), testAgent)
        }

        assertTrue("Healthy admitted tool must execute: ${result.errorDescription}", result.isSuccess)
        assertEquals("The tool must have executed exactly once", 1, tool.executeCount)
    }
}
