package com.example.adversarial

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
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
import com.example.domain.core.Outcome
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.llm.LlmProviderPort
import com.example.application.execution.ExecutionService
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * ============================================================================
 * ADVERSARIAL TEST (defect family 2 & 8) — Fail-Closed Security Governance
 * ============================================================================
 *
 * Verified defects repaired here:
 *
 *  1. Sensitive-tool enforcement became a NO-OP when the
 *     PermissionGrantService was unavailable (`?: return null` BEFORE the
 *     sensitivity check) — sensitive tools executed with NO permission
 *     enforcement at all.
 *  2. REQUIRE_CONSENT verdicts from the SecurityGuard were silently IGNORED
 *     — the tool executed anyway (fail-open hole).
 *  3. Unknown/unclassified tools received a fabricated
 *     DEFAULT_SAFE_TOOL/ALLOW verdict.
 *
 * Every case below must FAIL CLOSED: the tool does NOT execute and the
 * failure is explicit.
 */
class FailClosedSecurityTest {

    private class FakeGrantCheckingService(
        val grantedTools: Set<String> = emptySet(),
        val grantedWorkspaces: Map<String, String> = emptyMap(), // tool -> workspace
        val throwOnCheck: Boolean = false
    ) {
        @Volatile var recordedDecisions: List<String> = emptyList()
            private set

        suspend fun check(
            principalId: String,
            resourceId: String,
            workspaceId: String?
        ): Boolean {
            if (throwOnCheck) throw IllegalStateException("grant store offline")
            val granted = resourceId in grantedTools &&
                (grantedWorkspaces[resourceId] == null || grantedWorkspaces[resourceId] == workspaceId)
            return granted
        }
    }

    private class SensitiveRecordingTool : com.example.domain.ports.tools.ToolPort {
        var executeCount = 0
        override val declaration: ToolDeclaration = ToolDeclaration(
            name = "sensitive_sender",
            description = "Sensitive external sender",
            isSensitive = true,
            requiresHumanConsent = true
        )

        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
            executeCount++
            return Outcome.Success(ToolOutput(content = "SENT"))
        }
    }

    private class UnknownRecordingTool : com.example.domain.ports.tools.ToolPort {
        var executeCount = 0
        override val declaration: ToolDeclaration = ToolDeclaration(
            name = "mystery_tool",
            description = "Unclassified tool with a READ_ONLY declaration",
            // NOTE: sideEffects default = READ_ONLY, not sensitive, not
            // consent-requiring — this exercises the DECLARED_LOCAL_TOOL
            // classification (allowed, LOW risk) via declaration facts.
            networkRequirement = com.example.domain.core.capability.NetworkRequirement.LOCAL_ONLY
        )

        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
            executeCount++
            return Outcome.Success(ToolOutput(content = "MYSTERY_OK"))
        }
    }

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent_adversarial"),
            name = "Adversarial Agent",
            role = AgentRole.CODER,
            description = "test",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
        budget = AgentBudget(maxTokens = 30000)
    )

    private fun buildExecutionService(
        tool: com.example.domain.ports.tools.ToolPort,
        grantServiceLike: FakeGrantCheckingService? = null
    ): Pair<ExecutionService, ComponentRegistry> {
        val registry = ComponentRegistry()
        val provider = object : LlmProviderPort {
            override val providerId: String = "fake_llm"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "fake_llm", name = "Fake", providerType = "FAKE",
                defaultModel = "fake-1", isConfigured = true, isOnline = true, isLocal = false
            )
            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(LlmResponse(text = "ok", usage = TokenUsage(1, 1), finishReason = "STOP", modelId = "fake-1"))

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                emit(ExecutionEvent.Completed(executionId, "ok", 5))
            }
        }
        TestResourceRegistration.registerLlmProvider(registry, provider)
        registry.runtimeAdapterResolver.registerToolAdapter(
            ResourceId(tool.declaration.name), tool
        )
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
        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        )
        // P0-1: the universal admission gate is wired here too — the SAME
        // ordered pipeline as production (allow-all principal fake; sensitive
        // flows stay governed by the grant boundary above).
        executionService.admissionControl =
            com.example.application.governed.GovernedPipelineFactory.admissionForTools(tool.declaration)
        // Wire a permissionGrantService-compatible object when provided
        // (structural compatibility with PermissionGrantService.check).
        if (grantServiceLike != null) {
            executionService.permissionGrantService =
                com.example.application.security.PermissionGrantService(
                    permissionGrantDao = FakeGrantDao(grantServiceLike),
                    telemetryPort = NoopTelemetryPort
                )
        }
        return executionService to registry
    }

    /** Minimal Room-free DAO fake delegating to the checking service. */
    private class FakeGrantDao(private val service: FakeGrantCheckingService) :
        com.example.infrastructure.persistence.dao.PermissionGrantDao {
        override suspend fun forPrincipal(principalType: String, principalId: String) = emptyList<com.example.infrastructure.persistence.entities.PermissionGrantEntity>()
        override suspend fun lookupScoped(
            principalType: String, principalId: String, resourceType: String,
            resourceId: String, permission: String, workspaceId: String?
        ): com.example.infrastructure.persistence.entities.PermissionGrantEntity? =
            if (service.check(principalId, resourceId, workspaceId)) {
                com.example.infrastructure.persistence.entities.PermissionGrantEntity(
                    id = 1L, principalType = principalType, principalId = principalId,
                    resourceType = resourceType, resourceId = resourceId, permission = permission,
                    isAllowed = true, grantedBy = "test", grantedAtEpochMs = 0, expiresAtEpochMs = null,
                    workspaceId = workspaceId
                )
            } else null
        override suspend fun lookup(
            principalType: String, principalId: String, resourceType: String,
            resourceId: String, permission: String
        ): com.example.infrastructure.persistence.entities.PermissionGrantEntity? = null
        override suspend fun upsert(grant: com.example.infrastructure.persistence.entities.PermissionGrantEntity) = 1L
        override suspend fun revoke(id: Long) {}
    }

    private object NoopTelemetryPort : com.example.domain.ports.observability.TelemetryPort {
        override suspend fun record(sample: com.example.domain.core.observability.MetricSample) {}
        override suspend fun recordBatch(samples: List<com.example.domain.core.observability.MetricSample>) {}
        override suspend fun recordAudit(event: com.example.domain.core.observability.AuditEvent): Long = 0L
        override suspend fun recordTraceNode(node: com.example.domain.core.observability.ExecutionTraceNode) {}
        override fun snapshots(): kotlinx.coroutines.flow.Flow<List<com.example.domain.core.observability.MetricSnapshot>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override fun dimensionSummaries(): kotlinx.coroutines.flow.Flow<List<com.example.domain.core.observability.DimensionSummary>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override fun auditEvents(limit: Int): kotlinx.coroutines.flow.Flow<List<com.example.domain.core.observability.AuditEvent>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override fun traceForExecution(executionId: String): kotlinx.coroutines.flow.Flow<List<com.example.domain.core.observability.ExecutionTraceNode>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override fun recentTraceNodes(limit: Int): kotlinx.coroutines.flow.Flow<List<com.example.domain.core.observability.ExecutionTraceNode>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun snapshotByType(type: com.example.domain.core.observability.MetricType): List<com.example.domain.core.observability.MetricSnapshot> =
            emptyList()
    }

    private fun toolAction(toolName: String) = DecisionAction(
        type = DecisionActionType.EXECUTE_TOOL,
        targetId = toolName,
        payload = mapOf("payload" to "x"),
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId(toolName),
            providerId = "in_app",
            serviceId = "in_app_tools",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.TOOL_EXECUTION),
            rationale = "adversarial",
            confidence = 0.9f
        )
    )

    private fun context() = com.example.application.decision.DecisionContext(
        task = TaskDefinition(
            id = TaskId("t-adversarial"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput(rawPrompt = "test")
        )
    )

    // ------------------------------------------------------------------
    // 1. Sensitive tool + NO permission service → FAIL CLOSED
    // ------------------------------------------------------------------

    @Test
    fun `sensitive tool with unavailable permission service is BLOCKED - never a no-op`() = runBlocking {
        val tool = SensitiveRecordingTool()
        val (executionService, _) = buildExecutionService(tool, grantServiceLike = null)

        val result = withContext(ExecutionScope("exec_adv_1", "ws_adv")) {
            executionService.executeAction(toolAction("sensitive_sender"), context(), testAgent)
        }

        assertTrue("Sensitive execution must fail closed", !result.isSuccess)
        assertTrue(
            "Must cite the unavailable enforcement explicitly: ${result.errorDescription}",
            result.errorDescription!!.contains("PERMISSION_ENFORCEMENT_UNAVAILABLE")
        )
        assertEquals("The sensitive tool must NOT have executed", 0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 2. Sensitive tool + NO grant → BLOCKED with PERMISSION_DENIED
    // ------------------------------------------------------------------

    @Test
    fun `sensitive tool without an explicit grant is BLOCKED`() = runBlocking {
        val tool = SensitiveRecordingTool()
        val (executionService, _) = buildExecutionService(
            tool, grantServiceLike = FakeGrantCheckingService(grantedTools = emptySet())
        )

        val result = withContext(ExecutionScope("exec_adv_2", "ws_adv")) {
            executionService.executeAction(toolAction("sensitive_sender"), context(), testAgent)
        }

        assertTrue(!result.isSuccess)
        assertTrue(result.errorDescription!!.contains("PERMISSION_DENIED"))
        assertEquals(0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 3. Grant scoped to ANOTHER workspace does not authorize
    // ------------------------------------------------------------------

    @Test
    fun `a workspace-scoped grant cannot authorize another workspace's execution`() = runBlocking {
        val tool = SensitiveRecordingTool()
        // Grant exists — but scoped to ws_other.
        val (executionService, _) = buildExecutionService(
            tool,
            grantServiceLike = FakeGrantCheckingService(
                grantedTools = setOf("sensitive_sender"),
                grantedWorkspaces = mapOf("sensitive_sender" to "ws_other")
            )
        )

        val result = withContext(ExecutionScope("exec_adv_3", "ws_adv")) {
            executionService.executeAction(toolAction("sensitive_sender"), context(), testAgent)
        }

        assertTrue(!result.isSuccess)
        assertTrue(result.errorDescription!!.contains("PERMISSION_DENIED"))
        assertEquals(0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 4. Grant enforcement EXCEPTION fails CLOSED
    // ------------------------------------------------------------------

    @Test
    fun `grant store exception fails CLOSED`() = runBlocking {
        val tool = SensitiveRecordingTool()
        val (executionService, _) = buildExecutionService(
            tool, grantServiceLike = FakeGrantCheckingService(throwOnCheck = true)
        )

        val result = withContext(ExecutionScope("exec_adv_4", "ws_adv")) {
            executionService.executeAction(toolAction("sensitive_sender"), context(), testAgent)
        }

        assertTrue(!result.isSuccess)
        assertTrue(result.errorDescription!!.contains("PERMISSION_DENIED"))
        assertEquals(0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 5. Correctly-scoped grant ALLOWS the sensitive tool (the positive
    //    control — proving the gate is not simply "deny everything").
    // ------------------------------------------------------------------

    @Test
    fun `an explicitly granted sensitive tool executes`() = runBlocking {
        val tool = SensitiveRecordingTool()
        val (executionService, _) = buildExecutionService(
            tool,
            grantServiceLike = FakeGrantCheckingService(
                grantedTools = setOf("sensitive_sender"),
                grantedWorkspaces = mapOf("sensitive_sender" to "ws_adv")
            )
        )

        val result = withContext(ExecutionScope("exec_adv_5", "ws_adv")) {
            executionService.executeAction(toolAction("sensitive_sender"), context(), testAgent)
        }

        assertTrue("Explicitly granted sensitive tool must execute: ${result.errorDescription}", result.isSuccess)
        assertEquals(1, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 6. REQUIRE_CONSENT verdicts are enforced (consent-requiring
    //    non-sensitive tools are blocked until granted)
    // ------------------------------------------------------------------

    @Test
    fun `consent-requiring tool is blocked until an explicit grant exists`() = runBlocking {
        class ConsentTool : com.example.domain.ports.tools.ToolPort {
            var executeCount = 0
            override val declaration = ToolDeclaration(
                name = "consent_tool",
                description = "requires consent",
                requiresHumanConsent = true,
                sideEffects = com.example.domain.core.capability.SideEffectClassification.STATE_MUTATION
            )
            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                executeCount++
                return Outcome.Success(ToolOutput(content = "DONE"))
            }
        }
        val tool = ConsentTool()
        // No grant → blocked.
        val (executionService, _) = buildExecutionService(
            tool, grantServiceLike = FakeGrantCheckingService(grantedTools = emptySet())
        )
        val blocked = withContext(ExecutionScope("exec_adv_6", "ws_adv")) {
            executionService.executeAction(toolAction("consent_tool"), context(), testAgent)
        }
        assertTrue(!blocked.isSuccess)
        assertEquals(0, tool.executeCount)
    }

    // ------------------------------------------------------------------
    // 7. Unclassified tool names (no declaration facts, no known-safe
    //    category) fail closed at the guard itself.
    // ------------------------------------------------------------------

    @Test
    fun `unclassified tool name is classified REQUIRE_CONSENT by the guard`() {
        val guard = SecurityGuardService()
        val evaluation = guard.evaluateToolExecution(
            input = ToolInput(toolName = "totally_unknown_tool", arguments = emptyMap()),
            policy = com.example.domain.core.security.SecurityPolicy()
        )
        assertEquals(
            "Unknown tools must not receive a fabricated ALLOW",
            com.example.domain.core.security.SecurityDecision.REQUIRE_CONSENT,
            evaluation.decision
        )
        assertEquals("UNCLASSIFIED_TOOL", evaluation.matchedRule)
    }

    // ------------------------------------------------------------------
    // 8. Declared local read-only tools are classified from DECLARATION
    //    facts (positive control for the closed-world classifier).
    // ------------------------------------------------------------------

    @Test
    fun `declared local tool is classified from declaration facts`() {
        val guard = SecurityGuardService()
        val evaluation = guard.evaluateToolExecution(
            input = ToolInput(
                toolName = "anything_at_all",
                arguments = emptyMap(),
                contextAttributes = mapOf(
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_SIDE_EFFECTS to "READ_ONLY",
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_SENSITIVE to "false",
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_REQUIRES_CONSENT to "false",
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_NETWORK_REQUIREMENT to "LOCAL_ONLY"
                )
            ),
            policy = com.example.domain.core.security.SecurityPolicy()
        )
        assertEquals(com.example.domain.core.security.SecurityDecision.ALLOW, evaluation.decision)
        assertEquals("DECLARED_LOCAL_TOOL", evaluation.matchedRule)
    }

    @Test
    fun `declared sensitive tool is classified REQUIRE_CONSENT from declaration facts`() {
        val guard = SecurityGuardService()
        val evaluation = guard.evaluateToolExecution(
            input = ToolInput(
                toolName = "anything_at_all",
                arguments = emptyMap(),
                contextAttributes = mapOf(
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_SIDE_EFFECTS to "IRREVERSIBLE",
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_SENSITIVE to "true",
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_REQUIRES_CONSENT to "true",
                    com.example.application.security.SecurityGuardService.ATTR_DECLARED_NETWORK_REQUIREMENT to "LOCAL_ONLY"
                )
            ),
            policy = com.example.domain.core.security.SecurityPolicy()
        )
        assertEquals(
            com.example.domain.core.security.SecurityDecision.REQUIRE_CONSENT,
            evaluation.decision
        )
        assertEquals("DECLARED_SENSITIVE_TOOL", evaluation.matchedRule)
    }
}
