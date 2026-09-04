package com.example.architecture

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionResult
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.application.testing.TestResourceRegistration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * NoSilentFallbackTest — Phase 4 architectural invariant
 * ============================================================================
 *
 * Per the architectural plan (Section 6):
 *
 * An execution requiring a provider/resource CANNOT proceed without an
 * authoritative `DecisionRecord`. The execution path is:
 *
 *   1. DecisionService produces DecisionRecord.
 *   2. ExecutionService resolves the exact ResourceId via
 *      RuntimeAdapterResolver.
 *   3. Adapter executes.
 *
 * FORBIDDEN:
 *   - resolving by provider name
 *   - resolving by model name
 *   - selecting "default provider"
 *   - selecting first available provider
 *   - selecting first model
 *   - ComponentRegistry fallback
 *   - silently creating a DecisionRecord
 *   - silently substituting another ResourceId
 *
 * If no valid DecisionRecord exists:
 *   return an explicit planning/execution failure requiring replanning.
 */
class NoSilentFallbackTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var executionService: ExecutionService

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("test_agent"),
            name = "Test Agent",
            role = AgentRole.CODER,
            description = "Test",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
        budget = AgentBudget(maxTokens = 30000)
    )

    @Before
    fun setup() {
        registry = ComponentRegistry()
        val securityGuard = SecurityGuardService()
        executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = securityGuard
        )
    }

    @Test
    fun `executeAction with SELECT_MODEL and no DecisionRecord fails explicitly`() = runBlocking {
        val action = DecisionAction(
            type = DecisionActionType.SELECT_MODEL,
            targetId = "anything",
            payload = mapOf("resourceId" to "anything", "providerId" to "anything"),
            decisionRecord = null  // ← Phase 4: this MUST cause explicit failure
        )
        val task = TaskDefinition(
            id = TaskId("t1"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Hello")
        )
        val context = com.example.application.decision.DecisionContext(task = task)
        val result = executionService.executeAction(action, context, testAgent)
        assertTrue("Execution must fail without a DecisionRecord", !result.isSuccess)
        assertTrue(
            "Error message must indicate DecisionRecord is required",
            result.errorDescription?.contains("DECISION_RECORD_REQUIRED") == true
        )
    }

    @Test
    fun `executeAction with SEARCH and no DecisionRecord fails explicitly`() = runBlocking {
        val action = DecisionAction(
            type = DecisionActionType.SEARCH,
            targetId = "anything",
            payload = mapOf("query" to "test"),
            decisionRecord = null
        )
        val task = TaskDefinition(
            id = TaskId("t2"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Search test")
        )
        val context = com.example.application.decision.DecisionContext(task = task)
        val result = executionService.executeAction(action, context, testAgent)
        assertTrue("Search execution must fail without a DecisionRecord", !result.isSuccess)
        assertTrue(
            "Error message must indicate DecisionRecord is required",
            result.errorDescription?.contains("DECISION_RECORD_REQUIRED") == true
        )
    }

    @Test
    fun `executeAction with EXECUTE_TOOL and no DecisionRecord fails explicitly`() = runBlocking {
        val action = DecisionAction(
            type = DecisionActionType.EXECUTE_TOOL,
            targetId = "anything",
            payload = mapOf(),
            decisionRecord = null
        )
        val task = TaskDefinition(
            id = TaskId("t3"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Tool test")
        )
        val context = com.example.application.decision.DecisionContext(task = task)
        val result = executionService.executeAction(action, context, testAgent)
        assertTrue("Tool execution must fail without a DecisionRecord", !result.isSuccess)
        assertTrue(
            "Error message must indicate DecisionRecord is required",
            result.errorDescription?.contains("DECISION_RECORD_REQUIRED") == true
        )
    }
}
