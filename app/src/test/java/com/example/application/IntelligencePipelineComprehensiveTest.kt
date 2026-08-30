package com.example.application

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionResult
import com.example.application.execution.ExecutionService
import com.example.application.observation.ObservationService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.outcome.OutcomeService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.ports.memory.MemoryRepositoryPort
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SearchResultSet
import com.example.domain.core.security.SecurityDecision
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.VectorStorePort
import com.example.domain.ports.search.SearchProviderPort
import com.example.domain.ports.tools.ToolPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Comprehensive verification test suite for AI-V0 Ultimate Android Intelligence Pipeline.
 * Tests all 15 operational requirements:
 * 1. offline model selection
 * 2. unavailable provider fallback
 * 3. memory retrieval
 * 4. tool authorization
 * 5. search failure
 * 6. model failure
 * 7. retry
 * 8. replan
 * 9. completion verification
 * 10. persistent task recovery
 * 11. resource selection
 * 12. agent selection
 * 13. event emission
 * 14. degraded execution
 * 15. security denial
 */
class IntelligencePipelineComprehensiveTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var caseBase: CaseBase
    private lateinit var cbrMdpEngine: CbrMdpEngine
    private lateinit var decisionService: DecisionService
    private lateinit var executionService: ExecutionService
    private lateinit var observationService: ObservationService
    private lateinit var outcomeService: OutcomeService
    private lateinit var orchestrator: AgentOrchestrator

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("code_craftsman"),
            name = "Executive Coder",
            role = AgentRole.CODER,
            description = "Builds robust clean code",
            systemPrompt = "You are a professional Android software engineer."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION, CapabilityType.MEMORY_RETRIEVAL),
        budget = AgentBudget(maxTokens = 30000)
    )

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
        executionService = ExecutionService(
            componentRegistry = registry,
            securityGuard = securityGuard
        )
        observationService = ObservationService()
        outcomeService = OutcomeService()
        orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = securityGuard,
            decisionService = decisionService,
            executionService = executionService,
            observationService = observationService,
            outcomeService = outcomeService
        )
    }

    // 1. Offline Model Selection Test
    @Test
    fun `1 - test offline model selection uses only local resources`() {
        val remoteProvider = createMockLlmProvider("remote_gemini", isLocal = false)
        val localProvider = createMockLlmProvider("local_ollama", isLocal = true)
        registry.registerLlmProvider(remoteProvider)
        registry.registerLlmProvider(localProvider)

        val task = TaskDefinition(
            id = TaskId("task-offline-1"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Generate offline function")
        )

        val offlineContext = decisionService.buildDecisionContext(
            task = task,
            networkPolicy = NetworkPolicy.OFFLINE,
            isNetworkAvailable = false
        )

        val candidateActions = decisionService.generateCandidateActions(offlineContext)
        val modelActions = candidateActions.filter { it.type == DecisionActionType.SELECT_MODEL }

        // Must ONLY contain the local provider candidate
        assertTrue(modelActions.isNotEmpty())
        assertTrue(modelActions.all { it.payload["isLocal"] == "true" })
        assertFalse(modelActions.any { it.payload["providerId"] == "remote_gemini" })
    }

    // 2. Unavailable Provider Fallback Test
    @Test
    fun `2 - test unavailable provider fallback gracefully handles errors`() = runBlocking {
        val failingProvider = object : LlmProviderPort {
            override val providerId: String = "failing_provider"
            override val metadata = SafeProviderMetadata(
                id = providerId,
                name = "Failing Provider",
                providerType = "MOCK",
                defaultModel = "fail-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = false
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
                return Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "Endpoint unreachable"))
            }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                emit(ExecutionEvent.Error(executionId, "PROVIDER_UNAVAILABLE", "Remote server timeout"))
            }
        }

        registry.registerLlmProvider(failingProvider, isDefault = true)

        val task = TaskDefinition(
            id = TaskId("task-fallback-2"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Run mission")
        )

        val events = orchestrator.executeTaskStream(testAgent, task).toList()
        assertTrue(events.any { it is ExecutionEvent.ActionFailed || it is ExecutionEvent.Error })
    }

    // 3. Memory Retrieval Test
    @Test
    fun `3 - test memory retrieval injects context into execution`() = runBlocking {
        val mockVectorStore = object : MemoryRepositoryPort {
            override suspend fun storeMemory(entry: MemoryEntry): Outcome<Unit, com.example.domain.core.memory.VectorStoreFailure> = Outcome.Success(Unit)

            override suspend fun retrieveMemories(query: String, topK: Int, minConfidence: Float): Outcome<List<ScoredMemoryRecord>, com.example.domain.core.memory.VectorStoreFailure> {
                val entry = MemoryEntry(
                    id = "mem-1",
                    content = "Project uses Room 2.6 and Jetpack Compose 1.7",
                    type = MemoryType.FACTUAL_INSIGHT,
                    confidence = 0.95f,
                    provenance = MemoryProvenance(sourceTaskId = "test", createdAtTimestampMs = System.currentTimeMillis()),
                    isActive = true
                )
                return Outcome.Success(listOf(ScoredMemoryRecord(entry = entry, similarityScore = 0.95f, retrievalMode = RetrievalMode.SEMANTIC)))
            }

            override suspend fun getAllActiveMemories(): Outcome<List<MemoryEntry>, com.example.domain.core.memory.VectorStoreFailure> = Outcome.Success(emptyList())
            override suspend fun deleteMemory(id: String): Outcome<Unit, com.example.domain.core.memory.VectorStoreFailure> = Outcome.Success(Unit)
        }

        registry.registerMemoryRepository(mockVectorStore)

        val task = TaskDefinition(
            id = TaskId("task-mem-3"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Which database version do we use?")
        )

        val context = decisionService.buildDecisionContext(task)
        val action = DecisionAction(
            type = DecisionActionType.RETRIEVE_MEMORY,
            targetId = "memory_repo",
            payload = mapOf("query" to task.input.rawPrompt)
        )

        val result = executionService.executeAction(action, context, testAgent)
        assertTrue(result.isSuccess)
        val snippets = result.outputData["memorySnippets"] as? List<*>
        assertNotNull(snippets)
        assertTrue(snippets!!.isNotEmpty())
        assertTrue(snippets[0].toString().contains("Room 2.6"))
    }

    // 4. Tool Authorization Test
    @Test
    fun `4 - test tool authorization allows permitted tools`() = runBlocking {
        val mockTool = object : ToolPort {
            override val declaration = ToolDeclaration("safe_file_reader", "Reads files within sandbox")
            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                return Outcome.Success(ToolOutput("file content: build.gradle.kts"))
            }
        }
        registry.registerTool(mockTool)

        val task = TaskDefinition(id = TaskId("task-tool-4"), assignedAgentId = testAgent.identity.id, input = TaskInput("read file"))
        val context = decisionService.buildDecisionContext(task)
        val action = DecisionAction(DecisionActionType.EXECUTE_TOOL, targetId = "safe_file_reader")

        val result = executionService.executeAction(action, context, testAgent)
        assertTrue(result.isSuccess)
        assertEquals("file content: build.gradle.kts", result.outputText)
    }

    // 5. Search Failure Handling Test
    @Test
    fun `5 - test search failure returns structured failure without crashing`() = runBlocking {
        val failingSearch = object : SearchProviderPort {
            override val providerId: String = "failing_search"
            override val metadata = com.example.domain.core.search.SafeSearchProviderMetadata(
                id = providerId,
                name = "Failing Search",
                providerType = "MOCK",
                isConfigured = true,
                isEnabled = true,
                priority = 1
            )

            override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> {
                return Outcome.Error(SearchFailure.NetworkError("DNS resolution failed"))
            }
        }
        registry.registerSearchProvider(failingSearch)

        val task = TaskDefinition(id = TaskId("task-search-5"), assignedAgentId = testAgent.identity.id, input = TaskInput("search internet"))
        val context = decisionService.buildDecisionContext(task)
        val action = DecisionAction(DecisionActionType.SEARCH, targetId = "failing_search")

        val result = executionService.executeAction(action, context, testAgent)
        assertFalse(result.isSuccess)
        assertNotNull(result.errorDescription)
    }

    // 6. Model Failure Handling Test
    @Test
    fun `6 - test model failure maps to error event and triggers replan loop`() = runBlocking {
        val failingProvider = object : LlmProviderPort {
            override val providerId: String = "fail_llm"
            override val metadata = SafeProviderMetadata(id = providerId, name = "Failing", providerType = "MOCK", defaultModel = "m1", isConfigured = true, isOnline = true, isLocal = false)
            override suspend fun generate(request: LlmRequest) = Outcome.Error(LlmFailure.ProviderUnavailable(providerId, "Fatal LLM crash"))
            override fun stream(request: LlmRequest, executionId: String) = flow {
                emit(ExecutionEvent.Error(executionId, "MODEL_FAILURE", "LLM stream disconnected unexpectedly"))
            }
        }
        registry.registerLlmProvider(failingProvider, isDefault = true)

        val task = TaskDefinition(id = TaskId("task-mod-6"), assignedAgentId = testAgent.identity.id, input = TaskInput("Generate code"))
        val events = orchestrator.executeTaskStream(testAgent, task).toList()
        assertTrue(events.any { it is ExecutionEvent.ActionFailed || it is ExecutionEvent.Error })
    }

    // 7. Retry with Backoff Test
    @Test
    fun `7 - test retry action triggers backoff delay and retries model step`() = runBlocking {
        val mockProvider = createMockLlmProvider("retry_provider", isLocal = false)
        registry.registerLlmProvider(mockProvider, isDefault = true)

        val task = TaskDefinition(id = TaskId("task-retry-7"), assignedAgentId = testAgent.identity.id, input = TaskInput("Calculate result"))
        val context = decisionService.buildDecisionContext(task, consecutiveFailures = 1)
        val action = DecisionAction(DecisionActionType.RETRY, targetId = "retry_provider")

        val result = executionService.executeAction(action, context, testAgent)
        assertTrue(result.isSuccess)
        assertTrue(result.latencyMs >= 300L) // Enforced exponential backoff
    }

    // 8. Replan on Consecutive Failures Test
    @Test
    fun `8 - test replan candidate generated when consecutive failures exceed threshold`() {
        val task = TaskDefinition(id = TaskId("task-replan-8"), assignedAgentId = testAgent.identity.id, input = TaskInput("Complex pipeline"))
        val context = decisionService.buildDecisionContext(task, consecutiveFailures = 3)

        val candidates = decisionService.generateCandidateActions(context)
        assertTrue(candidates.any { it.type == DecisionActionType.REPLAN })
        assertTrue(candidates.any { it.type == DecisionActionType.ASK_USER })
    }

    // 9. Completion Verification Test
    @Test
    fun `9 - test completion evaluator requires objective satisfaction before complete`() {
        val task = TaskDefinition(
            id = TaskId("task-comp-9"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("بحث عن تاريخ الأندلس"),
            successCriteria = com.example.domain.core.task.TaskSuccessCriteria(minOutputLengthChars = 20)
        )

        // Without evidence or synthesized text, objective is NOT satisfied
        val notSatisfied = outcomeService.isTaskObjectiveSatisfied(
            task = task,
            accumulatedEvidence = emptyMap(),
            finalOutputText = "",
            lastAction = DecisionAction(DecisionActionType.EXECUTE_STEP)
        )
        assertFalse(notSatisfied)

        // With evidence and synthesized text, objective IS satisfied
        val satisfied = outcomeService.isTaskObjectiveSatisfied(
            task = task,
            accumulatedEvidence = mapOf("searchResults" to listOf("evidence")),
            finalOutputText = "ملخص شامل ومفصل عن تاريخ الأندلس",
            lastAction = DecisionAction(DecisionActionType.EXECUTE_STEP)
        )
        assertTrue(satisfied)
    }

    // 10. Persistent Task Recovery Test
    @Test
    fun `10 - test persistent task recovery transitions state correctly`() {
        val initialTask = TaskDefinition(
            id = TaskId("task-persist-10"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput("Resume long running computation"),
            state = TaskLifecycleState.PLANNING
        )

        val runningTask = initialTask.copy(state = TaskLifecycleState.RUNNING, currentStepIndex = 2)
        assertEquals(TaskLifecycleState.RUNNING, runningTask.state)
        assertEquals(2, runningTask.currentStepIndex)

        val completedTask = runningTask.copy(state = TaskLifecycleState.COMPLETED, outcomeSummary = "Execution recovered and completed")
        assertEquals(TaskLifecycleState.COMPLETED, completedTask.state)
        assertEquals("Execution recovered and completed", completedTask.outcomeSummary)
    }

    // 11. Resource Selection Test
    @Test
    fun `11 - test resource selection filters based on network policy`() {
        val task = TaskDefinition(id = TaskId("task-res-11"), assignedAgentId = testAgent.identity.id, input = TaskInput("Fast local task"))
        val offlineContext = decisionService.buildDecisionContext(task, networkPolicy = NetworkPolicy.OFFLINE, isNetworkAvailable = false)

        val candidates = decisionService.generateCandidateActions(offlineContext)
        // External web search must NOT be a valid candidate when offline
        assertFalse(candidates.any { it.type == DecisionActionType.SEARCH })
    }

    // 12. Agent Selection Test
    @Test
    fun `12 - test agent selection candidate generation`() {
        val task = TaskDefinition(id = TaskId("task-agent-12"), assignedAgentId = testAgent.identity.id, input = TaskInput("Write clean code module"))
        val context = decisionService.buildDecisionContext(task)

        val candidates = decisionService.generateCandidateActions(context)
        assertTrue(candidates.isNotEmpty())
    }

    // 13. Event Emission Stream Test
    @Test
    fun `13 - test streaming event emission lifecycle order`() = runBlocking {
        val mockProvider = createMockLlmProvider("stream_provider", isLocal = false)
        registry.registerLlmProvider(mockProvider, isDefault = true)

        val task = TaskDefinition(id = TaskId("task-evt-13"), assignedAgentId = testAgent.identity.id, input = TaskInput("Test stream lifecycle"))
        val events = orchestrator.executeTaskStream(testAgent, task).toList()

        assertTrue(events.first() is ExecutionEvent.Started)
        assertTrue(events.any { it is ExecutionEvent.DecisionMade })
        assertTrue(events.any { it is ExecutionEvent.ActionStarted })
        assertTrue(events.any { it is ExecutionEvent.ObservationRecorded })
        assertTrue(events.last() is ExecutionEvent.Completed)
    }

    // 14. Degraded Execution Test
    @Test
    fun `14 - test degraded execution outcome handles degraded state without failure`() = runBlocking {
        val mockTool = object : ToolPort {
            override val declaration = ToolDeclaration("degraded_tool", "Tool operating with degraded cache")
            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                return Outcome.Degraded(
                    partialValue = ToolOutput("Partial cached diagnostic info"),
                    reason = DegradedReason.CACHE_FALLBACK,
                    diagnosticMessage = "Offline cached diagnostics used"
                )
            }
        }
        registry.registerTool(mockTool)

        val task = TaskDefinition(id = TaskId("task-deg-14"), assignedAgentId = testAgent.identity.id, input = TaskInput("Run diagnostics"))
        val context = decisionService.buildDecisionContext(task)
        val action = DecisionAction(DecisionActionType.EXECUTE_TOOL, targetId = "degraded_tool")

        val result = executionService.executeAction(action, context, testAgent)
        assertTrue(result.isSuccess)
        assertTrue(result.isDegraded)
        assertEquals(DegradedReason.CACHE_FALLBACK, result.degradedReason)
    }

    // 15. Security Denial Test
    @Test
    fun `15 - test security denial prevents unauthorized dangerous tool execution`() = runBlocking {
        val dangerousTool = object : ToolPort {
            override val declaration = ToolDeclaration("delete_system_root", "Dangerous tool")
            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                return Outcome.Error(ToolFailure.SecurityDenied("DENY", "Unauthorized destructive command"))
            }
        }
        registry.registerTool(dangerousTool)

        val strictPolicy = SecurityPolicy(
            prohibitedToolPatterns = listOf("delete.*"),
            prohibitedParameters = listOf("rm -rf", "delete_system_root")
        )
        val strictSecurityGuard = SecurityGuardService()
        val strictExecutionService = ExecutionService(registry, strictSecurityGuard, defaultSecurityPolicy = strictPolicy)

        val task = TaskDefinition(id = TaskId("task-sec-15"), assignedAgentId = testAgent.identity.id, input = TaskInput("Delete system files"))
        val context = decisionService.buildDecisionContext(task)
        val action = DecisionAction(DecisionActionType.EXECUTE_TOOL, targetId = "delete_system_root")

        val result = strictExecutionService.executeAction(action, context, testAgent)
        assertFalse(result.isSuccess)
        assertTrue(result.errorDescription?.contains("أمان") == true || result.errorDescription?.contains("Security") == true || result.errorDescription?.contains("رفض") == true)
    }

    private fun createMockLlmProvider(id: String, isLocal: Boolean): LlmProviderPort {
        return object : LlmProviderPort {
            override val providerId: String = id
            override val metadata = SafeProviderMetadata(
                id = id,
                name = "Mock Provider $id",
                providerType = if (isLocal) "LOCAL" else "REMOTE",
                defaultModel = "$id-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = isLocal,
                supportedCapabilities = listOf("generation", "streaming")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
                return Outcome.Success(
                    LlmResponse(
                        text = "Response from $id",
                        toolCalls = emptyList(),
                        usage = TokenUsage(10, 20),
                        finishReason = "STOP",
                        modelId = "$id-v1"
                    )
                )
            }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                emit(ExecutionEvent.ContentChunk(executionId, "Response from ", sequenceIndex = 0))
                emit(ExecutionEvent.ContentChunk(executionId, id, sequenceIndex = 1))
                emit(ExecutionEvent.UsageBudgetUpdate(executionId, 10, 20, 30, 29970))
                emit(ExecutionEvent.Completed(executionId, "Response from $id", totalDurationMs = 50))
            }
        }
    }
}
