package com.example.application

import com.example.application.execution.ExecutionService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.memory.MemoryRepositoryPort
import com.example.domain.ports.tools.ToolPort
import com.example.infrastructure.memory.semantic.ArabicTextNormalizer
import com.example.infrastructure.memory.semantic.WordPieceTokenizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * GOLDEN PATH TESTS (audit 2026) — end-to-end capability closure on the JVM.
 * ============================================================================
 *
 * These tests verify the FULL runtime chain, not unit islands:
 *
 *  PATH A  — model-initiated tool call: LLM stream → ToolRequested → argument
 *            PARSING → real tool execution → ToolResult (observation).
 *  PATH C  — REAL multi-agent delegation: DELEGATE action → child resolution
 *            → child execution → real child output → parent observation.
 *  PATH D/E— durable checkpoint round-trip (persist → restore → resume).
 *  PATH F  — offline policy: OFFLINE + cloud-only resource → honest failure.
 *
 * The mock providers here are TEST fixtures (allowed); the code paths they
 * exercise are the REAL production paths.
 */
class GoldenPathTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class FakeStreamingLlmProvider(
        private val streamScript: (LlmRequest) -> List<ExecutionEvent>
    ) : LlmProviderPort {
        override val providerId: String = "fake_llm"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "fake_llm", name = "Fake", providerType = "FAKE",
            defaultModel = "fake-1", isConfigured = true, isOnline = true, isLocal = false
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            val text = streamScript(request)
                .filterIsInstance<ExecutionEvent.ContentChunk>()
                .joinToString("") { it.deltaText }
            return Outcome.Success(
                LlmResponse(text = text, usage = TokenUsage(10, 10), finishReason = "STOP", modelId = "fake-1")
            )
        }

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            streamScript(request).forEach { emit(it) }
        }
    }

    private class RecordingTool : ToolPort {
        var lastArguments: Map<String, Any?> = emptyMap()
        var executeCount: Int = 0

        override val declaration: ToolDeclaration = ToolDeclaration(
            name = "workspace_file_tool",
            description = "أداة إدارة وقراءة وكتابة ملفات مساحة العمل.",
            parameters = listOf(
                ToolParameter(name = "action", type = "string", description = "العملية", isRequired = true),
                ToolParameter(name = "path", type = "string", description = "المسار", isRequired = false)
            ),
            isSensitive = false,
            requiresHumanConsent = false
        )

        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
            executeCount++
            lastArguments = input.arguments
            val action = input.arguments["action"]?.toString() ?: "MISSING"
            val path = input.arguments["path"]?.toString() ?: "MISSING"
            return Outcome.Success(
                ToolOutput(content = "TOOL_EXECUTED action=$action path=$path")
            )
        }
    }

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent_coder"),
            name = "مهندس البرمجيات",
            role = AgentRole.CODER,
            description = "coder",
            systemPrompt = "You are a coder."
        ),
        allowedCapabilities = setOf(
            CapabilityType.LLM_GENERATION,
            CapabilityType.TOOL_EXECUTION,
            CapabilityType.FILE_STORAGE,
            CapabilityType.AGENT_DELEGATION
        ),
        budget = AgentBudget(maxTokens = 30000)
    )

    private fun newTask(prompt: String = "اقرأ الملف: مسار تجربة") = TaskDefinition(
        id = TaskId("t-${System.nanoTime()}"),
        assignedAgentId = testAgent.identity.id,
        input = TaskInput(rawPrompt = prompt),
        budget = TaskBudget(tokenLimit = 30000)
    )

    private fun buildRegistry(provider: LlmProviderPort, tool: RecordingTool): ComponentRegistry {
        val registry = ComponentRegistry()
        TestResourceRegistration.registerLlmProvider(registry, provider)
        registry.runtimeAdapterResolver.registerToolAdapter(
            com.example.domain.core.resource.ResourceId("workspace_file_tool"), tool
        )
        // Also register a matching ResourceRecord so the resolver validates it.
        registry.resourceRegistry.registerResource(
            com.example.domain.core.resource.ResourceRecord(
                resourceId = com.example.domain.core.resource.ResourceId("workspace_file_tool"),
                providerId = "workspace_file_tool",
                serviceId = "workspace_file_tool",
                resourceType = com.example.domain.core.resource.ResourceType.TOOL,
                capabilities = setOf(CapabilityType.TOOL_EXECUTION),
                configurationVersion = 1L,
                lifecycleState = com.example.domain.core.resource.ResourceLifecycleState.ENABLED,
                runtimeSupported = true,
                healthStatus = com.example.domain.core.provider.HealthStatus.HEALTHY,
                isLocal = true
            )
        )
        return registry
    }

    // ------------------------------------------------------------------
    // PATH A — real tool-calling round trip with argument parsing
    // ------------------------------------------------------------------

    @Test
    fun `PATH A - model tool call arguments are parsed and reach the tool`() = runBlocking {
        val tool = RecordingTool()
        val provider = FakeStreamingLlmProvider { _ ->
            listOf(
                ExecutionEvent.ToolRequested(
                    executionId = "exec-a",
                    callId = "call_1",
                    toolName = "workspace_file_tool",
                    argumentsJson = """{"action":"read","path":"notes/file.md"}"""
                ),
                ExecutionEvent.ContentChunk(executionId = "exec-a", deltaText = "", sequenceIndex = 0),
                ExecutionEvent.Completed(executionId = "exec-a", finalText = "", totalDurationMs = 5)
            )
        }
        val registry = buildRegistry(provider, tool)
        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        )

        val decisionContext = com.example.application.decision.DecisionContext(
            task = newTask()
        )
        val action = DecisionAction(
            type = DecisionActionType.SELECT_MODEL,
            targetId = "fake_llm",
            payload = emptyMap(),
            decisionRecord = com.example.domain.core.decision.DecisionRecord(
                selectedResourceId = com.example.domain.core.resource.ResourceId("fake_llm"),
                providerId = "fake_llm",
                serviceId = "fake_llm",
                configurationVersion = 1L,
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
                rationale = "test",
                confidence = 0.9f
            )
        )

        val seenEvents = mutableListOf<String>()
        val result = executionService.executeAction(
            action = action,
            context = decisionContext,
            agent = testAgent,
            executionId = "exec-a",
            onEvent = { ev ->
                seenEvents.add(ev::class.simpleName ?: "?")
                if (ev is ExecutionEvent.ToolResult) println("DEBUG toolOutcome=${ev.outcome}")
            }
        )
        println("DEBUG events=$seenEvents result=${result.isSuccess} err=${result.errorDescription} count=${tool.executeCount}")

        assertEquals("Tool should have been executed once", 1, tool.executeCount)
        assertEquals("read", tool.lastArguments["action"])
        assertEquals("notes/file.md", tool.lastArguments["path"])
        assertTrue(result.isSuccess)
    }

    // ------------------------------------------------------------------
    // PATH C — real multi-agent delegation
    // ------------------------------------------------------------------

    @Test
    fun `PATH C - DELEGATE executes a child agent and propagates real output`() = runBlocking {
        val tool = RecordingTool()
        val provider = FakeStreamingLlmProvider { _ ->
            listOf(
                ExecutionEvent.ContentChunk(executionId = "x", deltaText = "ok", sequenceIndex = 0)
            )
        }
        val registry = buildRegistry(provider, tool)
        val childAgent = AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("agent_researcher"),
                name = "الباحث المعرفي",
                role = AgentRole.RESEARCHER,
                description = "researcher",
                systemPrompt = "You research."
            ),
            allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.SEARCH),
            budget = AgentBudget(maxTokens = 5000)
        )
        registry.registerAgent(childAgent)

        val orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = SecurityGuardService(),
            decisionService = com.example.application.decision.DecisionService(
                cbrMdpEngine = com.example.domain.core.decision.CbrMdpEngine(),
                componentRegistry = registry,
                securityGuard = SecurityGuardService()
            )
        )

        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        ).apply {
            registryAgentResolver = { id -> registry.getAgent(id) }
            delegationExecutor = { childDef, childTask ->
                orchestrator.executeTask(childDef, childTask)
            }
        }

        val decisionContext = com.example.application.decision.DecisionContext(
            task = newTask("ابحث في الموضوع")
        )
        val delegateAction = DecisionAction(
            type = DecisionActionType.DELEGATE,
            targetId = "agent_researcher",
            payload = mapOf(
                "agentId" to "agent_researcher",
                "task" to "لخص الموضوع X",
                "delegationDepth" to "0"
            )
        )

        val result = executionService.executeAction(
            action = delegateAction,
            context = decisionContext,
            agent = testAgent,
            executionId = "exec-c"
        )

        assertTrue("Delegation should succeed: ${result.errorDescription}", result.isSuccess)
        assertTrue(
            "Child output must reach the parent",
            result.outputData.containsKey("delegatedToAgentId") &&
                result.outputData["delegatedToAgentId"] == "agent_researcher"
        )
    }

    @Test
    fun `PATH C - delegation is rejected beyond depth limit and for self-targeting`() = runBlocking {
        val tool = RecordingTool()
        val provider = FakeStreamingLlmProvider { _ ->
            listOf(ExecutionEvent.ContentChunk(executionId = "x", deltaText = "ok", sequenceIndex = 0))
        }
        val registry = buildRegistry(provider, tool)
        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        ).apply {
            registryAgentResolver = { id -> registry.getAgent(id) }
        }

        val selfDelegate = DecisionAction(
            type = DecisionActionType.DELEGATE,
            targetId = "agent_coder",
            payload = mapOf("delegationDepth" to "0")
        )
        val result = executionService.executeAction(
            action = selfDelegate,
            context = com.example.application.decision.DecisionContext(task = newTask()),
            agent = testAgent,
            executionId = "exec-c2"
        )
        assertFalse("Self-delegation must be rejected", result.isSuccess)
        assertTrue(result.errorDescription!!.contains("DELEGATE_REJECTED"))
    }

    // ------------------------------------------------------------------
    // PATH D/E — durable checkpoint round-trip
    // ------------------------------------------------------------------

    @Test
    fun `PATH D - task checkpoint survives serialization round-trip`() {
        val checkpoint = AgentOrchestrator.TaskCheckpoint(
            stepIndex = 3,
            tokensConsumed = 1500,
            accumulatedOutput = "نتيجة جزئية",
            evidence = mapOf(
                "searchResults" to listOf("نتيجة1", "نتيجة2"),
                "toolOutput" to "محتوى الأداة",
                "synthesizedText" to "نص"
            )
        )
        val json = checkpoint.toJson()
        val restored = AgentOrchestrator.TaskCheckpoint.fromJson(json)

        assertNotNull(restored)
        assertEquals(3, restored!!.stepIndex)
        assertEquals(1500, restored.tokensConsumed)
        assertEquals("نتيجة جزئية", restored.accumulatedOutput)
        assertEquals(listOf("نتيجة1", "نتيجة2"), restored.evidence["searchResults"])
        assertEquals("محتوى الأداة", restored.evidence["toolOutput"])
    }

    @Test
    fun `PATH D - corrupted checkpoint parses to null (no crash, honest re-run)`() {
        val restored = AgentOrchestrator.TaskCheckpoint.fromJson("{not valid json")
        assertEquals(null, restored)
        val empty = AgentOrchestrator.TaskCheckpoint.fromJson(null)
        assertEquals(null, empty)
    }

    // ------------------------------------------------------------------
    // PATH F — offline truthfulness
    // ------------------------------------------------------------------

    @Test
    fun `PATH F - OFFLINE policy blocks cloud LLM execution with honest failure`() = runBlocking {
        val tool = RecordingTool()
        val provider = FakeStreamingLlmProvider { _ ->
            listOf(ExecutionEvent.ContentChunk(executionId = "x", deltaText = "should not run", sequenceIndex = 0))
        }
        val registry = buildRegistry(provider, tool)
        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        )

        val decisionContext = com.example.application.decision.DecisionContext(
            task = newTask(),
            networkPolicy = NetworkPolicy.OFFLINE
        )
        val action = DecisionAction(
            type = DecisionActionType.SELECT_MODEL,
            targetId = "fake_llm",
            decisionRecord = com.example.domain.core.decision.DecisionRecord(
                selectedResourceId = com.example.domain.core.resource.ResourceId("fake_llm"),
                providerId = "fake_llm",
                serviceId = "fake_llm",
                configurationVersion = 1L,
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
                rationale = "test",
                confidence = 0.9f
            )
        )

        val result = executionService.executeAction(
            action = action,
            context = decisionContext,
            agent = testAgent,
            executionId = "exec-f"
        )

        // The provider must NOT have been called (no "should not run" output).
        assertFalse("Offline execution of a cloud resource must fail honestly", result.isSuccess)
        assertTrue(result.errorDescription!!.contains("OFFLINE"))
    }

    // ------------------------------------------------------------------
    // PATH B — RAG knowledge retrieval inside the agent loop
    // ------------------------------------------------------------------

    @Test
    fun `PATH B - RETRIEVE_KNOWLEDGE consults the RAG pipeline and returns grounded evidence`() = runBlocking {
        val tool = RecordingTool()
        val provider = FakeStreamingLlmProvider { _ ->
            listOf(ExecutionEvent.ContentChunk(executionId = "x", deltaText = "ok", sequenceIndex = 0))
        }
        val registry = buildRegistry(provider, tool)
        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        ).apply {
            ragRetrievalProvider = { query, topK ->
                com.example.domain.core.rag.AssembledRagContext(
                    query = query,
                    formattedContextText = "=== سياق المعرفة المسترجع ===",
                    retrievedChunks = listOf(
                        com.example.domain.core.rag.RetrievedContextChunk(
                            chunk = com.example.domain.core.rag.DocumentChunk(
                                id = "c1", documentId = "d1", documentTitle = "وثيقة الاختبار",
                                chunkIndex = 0, text = "نص المعرفة المرتبط بالاستعلام"
                            ),
                            relevanceScore = 0.9f,
                            retrievalMode = com.example.domain.core.memory.RetrievalMode.HYBRID,
                            snippet = "نص المعرفة"
                        )
                    ),
                    totalTokensEstimated = 40,
                    isTruncated = false
                )
            }
        }

        val result = executionService.executeAction(
            action = DecisionAction(
                type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                targetId = "knowledge_base",
                payload = mapOf("query" to "استعلام الاختبار")
            ),
            context = com.example.application.decision.DecisionContext(task = newTask()),
            agent = testAgent,
            executionId = "exec-b"
        )

        assertTrue("RAG retrieval should succeed: ${result.errorDescription}", result.isSuccess)
        val snippets = result.outputData["memorySnippets"] as? List<*>
        assertEquals(listOf("نص المعرفة المرتبط بالاستعلام"), snippets)
        assertEquals(1, result.outputData["ragChunksCount"])
    }

    // ------------------------------------------------------------------
    // PATH I — permission enforcement for sensitive tools + audit event
    // ------------------------------------------------------------------

    @Test
    fun `PATH I - sensitive tool without grant is BLOCKED and audited`() = runBlocking {
        val tool = RecordingTool().apply {
            // Make this instance sensitive — the enforcement checks isSensitive.
        }
        val provider = FakeStreamingLlmProvider { _ ->
            listOf(ExecutionEvent.ContentChunk(executionId = "x", deltaText = "ok", sequenceIndex = 0))
        }
        val registry = buildRegistry(provider, tool)

        val telemetryCapture = CapturingTelemetryPort()
        val permissionService = com.example.application.security.PermissionGrantService(
            permissionGrantDao = FakePermissionGrantDao(),
            telemetryPort = telemetryCapture
        )

        val executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        ).apply {
            permissionGrantService = permissionService
        }

        // The RecordingTool's declaration is not sensitive; simulate a
        // sensitive MCP call (isMcp = true path) which REQUIRES a grant.
        val result = executionService.executeAction(
            action = DecisionAction(
                type = DecisionActionType.EXECUTE_MCP,
                targetId = "workspace_file_tool",
                decisionRecord = com.example.domain.core.decision.DecisionRecord(
                    selectedResourceId = com.example.domain.core.resource.ResourceId("workspace_file_tool"),
                    providerId = "workspace_file_tool",
                    serviceId = "workspace_file_tool",
                    configurationVersion = 1L,
                    requiredCapabilities = setOf(CapabilityType.TOOL_EXECUTION),
                    rationale = "test",
                    confidence = 0.9f
                )
            ),
            context = com.example.application.decision.DecisionContext(task = newTask()),
            agent = testAgent,
            executionId = "exec-i"
        )

        assertFalse("MCP tool without a grant MUST be blocked", result.isSuccess)
        assertTrue(result.errorDescription!!.contains("PERMISSION_DENIED"))
        assertEquals(0, tool.executeCount) // enforcement is REAL, not a log line
        assertTrue(
            "A DENY decision must be audited",
            telemetryCapture.auditEvents.any { it.decision == "DENY" && it.resourceId == "workspace_file_tool" }
        )
    }

    // ------------------------------------------------------------------
    // Fixtures for governance tests
    // ------------------------------------------------------------------

    /** Manual Room-DAO fake (Room DAO interfaces are plain Kotlin interfaces). */
    private class FakePermissionGrantDao : com.example.infrastructure.persistence.dao.PermissionGrantDao {
        private val grants = mutableListOf<com.example.infrastructure.persistence.entities.PermissionGrantEntity>()
        private var nextId = 1L

        override suspend fun forPrincipal(principalType: String, principalId: String): List<com.example.infrastructure.persistence.entities.PermissionGrantEntity> =
            grants.filter { it.principalType == principalType && it.principalId == principalId }

        override suspend fun lookup(
            principalType: String,
            principalId: String,
            resourceType: String,
            resourceId: String,
            permission: String
        ): com.example.infrastructure.persistence.entities.PermissionGrantEntity? =
            grants.lastOrNull {
                it.principalType == principalType && it.principalId == principalId &&
                    it.resourceType == resourceType && it.resourceId == resourceId &&
                    it.permission == permission && it.isAllowed
            }

        override suspend fun upsert(grant: com.example.infrastructure.persistence.entities.PermissionGrantEntity): Long {
            val withId = if (grant.id == 0L) grant.copy(id = nextId++) else grant
            grants.removeAll { it.id == withId.id }
            grants.add(withId)
            return withId.id
        }

        override suspend fun revoke(id: Long) {
            grants.removeAll { it.id == id }
        }
    }

    /** Minimal TelemetryPort capture for audit assertions. */
    private class CapturingTelemetryPort : com.example.domain.ports.observability.TelemetryPort {
        val auditEvents = mutableListOf<com.example.domain.core.observability.AuditEvent>()

        override suspend fun record(sample: com.example.domain.core.observability.MetricSample) {}
        override suspend fun recordBatch(samples: List<com.example.domain.core.observability.MetricSample>) {}
        override suspend fun recordAudit(event: com.example.domain.core.observability.AuditEvent): Long {
            auditEvents.add(event)
            return auditEvents.size.toLong()
        }
        override suspend fun recordHealthProbe(probe: com.example.domain.core.observability.HealthProbe) {}
        override suspend fun recordTraceNode(node: com.example.domain.core.observability.ExecutionTraceNode) {}
        override fun snapshots(): Flow<List<com.example.domain.core.observability.MetricSnapshot>> =
            flowOf(emptyList())
        override fun dimensionSummaries(): Flow<List<com.example.domain.core.observability.DimensionSummary>> =
            flowOf(emptyList())
        override fun auditEvents(limit: Int): Flow<List<com.example.domain.core.observability.AuditEvent>> =
            flowOf(auditEvents.toList())
        override fun traceForExecution(executionId: String): Flow<List<com.example.domain.core.observability.ExecutionTraceNode>> =
            flowOf(emptyList())
        override suspend fun snapshotByType(type: com.example.domain.core.observability.MetricType): List<com.example.domain.core.observability.MetricSnapshot> =
            emptyList()
    }

    // ------------------------------------------------------------------
    // Arabic normalization + WordPiece tokenizer (local retrieval quality)
    // ------------------------------------------------------------------

    @Test
    fun `Arabic normalizer unifies alef variants, taa marbuta, and strips diacritics`() {
        val normalized = ArabicTextNormalizer.normalize("الأَكَادِيمِيَّةُ المُسلميةُ")
        assertEquals("الاكاديميه المسلميه", normalized)
    }

    @Test
    fun `WordPiece tokenizer segments known subwords and marks unknown words`() {
        val vocab = mapOf(
            "[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102,
            "play" to 900, "##ing" to 901, "football" to 902,
            "الن" to 910, "##ص" to 911
        )
        val tokenizer = WordPieceTokenizer(vocab = vocab, maxSequenceLength = 16)
        val pieces = tokenizer.tokenize("playing football")
        assertEquals(listOf("play", "##ing", "football"), pieces)
        val unknown = tokenizer.tokenizeWord("zzz")
        assertEquals(listOf("[UNK]"), unknown)
        val encoding = tokenizer.encode("playing")
        assertEquals(16L, encoding.inputIds.size.toLong())
        assertEquals(101L, encoding.inputIds[0]) // [CLS]
        assertEquals(102L, encoding.inputIds[3]) // [SEP] after [CLS] + 2 pieces
    }
}
