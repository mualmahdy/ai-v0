package com.example.application

import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.tools.ToolPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentOrchestratorTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var orchestrator: AgentOrchestrator

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("test_agent"),
            name = "Test Agent",
            role = AgentRole.CODER,
            description = "Test Description",
            systemPrompt = "You are a test coder."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
        budget = AgentBudget(maxTokens = 30000)
    )

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
        orchestrator = AgentOrchestrator(registry, securityGuard)
    }

    @Test
    fun `test executeTaskStream returns Error event when provider not found`() = runBlocking {
        val task = TaskDefinition(
            id = TaskId("t-1"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Hello")
        )

        val events = orchestrator.executeTaskStream(testAgent, task).toList()
        assertTrue(events.any { it is ExecutionEvent.Error && (it as ExecutionEvent.Error).failureCode == "PROVIDER_NOT_FOUND" })
    }

    @Test
    fun `test executeTaskStream streams content and completes`() = runBlocking {
        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_provider"
            override val metadata: SafeProviderMetadata = testMetadata

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
                return Outcome.Success(LlmResponse(text = "Generated response", toolCalls = emptyList(), usage = TokenUsage(10, 20), finishReason = "STOP", modelId = "mock-v1"))
            }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                emit(ExecutionEvent.ContentChunk(executionId, "Hello ", sequenceIndex = 0))
                emit(ExecutionEvent.ContentChunk(executionId, "World!", sequenceIndex = 1))
                emit(ExecutionEvent.Completed(executionId, "Hello World!", totalDurationMs = 50))
            }
        }

        registry.registerLlmProvider(mockProvider, isDefault = true)

        val task = TaskDefinition(
            id = TaskId("t-2"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Greet me")
        )

        val events = orchestrator.executeTaskStream(testAgent, task).toList()
        assertTrue(events.any { it is ExecutionEvent.Started })
        assertTrue(events.any { it is ExecutionEvent.ContentChunk })
        val completed = events.firstOrNull { it is ExecutionEvent.Completed } as? ExecutionEvent.Completed
        assertEquals("Hello World!", completed?.finalText)
    }

    @Test
    fun `test tool execution inside orchestrator stream`() = runBlocking {
        val mockTool = object : ToolPort {
            override val declaration: ToolDeclaration = ToolDeclaration(
                name = "test_calculator",
                description = "Calculator tool"
            )

            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                return Outcome.Success(ToolOutput(content = "Result: 42"))
            }
        }

        registry.registerTool(mockTool)

        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_provider"
            override val metadata: SafeProviderMetadata = testMetadata

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
                return Outcome.Success(LlmResponse(text = "Result: 42", toolCalls = emptyList(), usage = TokenUsage(10, 20), finishReason = "STOP", modelId = "mock-v1"))
            }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                emit(ExecutionEvent.ToolRequested(executionId, "call_1", "test_calculator", "{}"))
                emit(ExecutionEvent.Completed(executionId, "Tool completed", totalDurationMs = 100))
            }
        }

        registry.registerLlmProvider(mockProvider, isDefault = true)

        val task = TaskDefinition(
            id = TaskId("t-3"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Calculate")
        )

        val events = orchestrator.executeTaskStream(testAgent, task).toList()
        val toolResult = events.firstOrNull { it is ExecutionEvent.ToolResult } as? ExecutionEvent.ToolResult
        assertTrue(toolResult != null)
        assertEquals("test_calculator", toolResult?.toolName)
        assertTrue(toolResult?.outcome is Outcome.Success)
    }
}
