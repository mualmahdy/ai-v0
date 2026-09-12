package com.example.gapclosure

import com.example.application.decision.DecisionContext
import com.example.application.execution.ActionIdempotencyService
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
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultSet
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.infrastructure.persistence.dao.ActionIntentDao
import com.example.infrastructure.persistence.entities.ActionIntentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * GAP-13 + GAP-17 (Design Closure 2026) — HonestDegradationEventTest
 * ============================================================================
 *
 * GAP-13 (the silent-swallow set): the audit found fallback branches that
 * degraded WITHOUT any event — search-intelligence → plain path, RAG →
 * memory path, idempotency outcome writes, audit writes. The ONE policy:
 * every degradation is EMITTED (execution bus event or observable failure
 * record), never invisible.
 *
 * GAP-17 (circuit breaker): the gate check swallowed enforcement failures
 * with `catch(_){true}` while every OTHER gate fails closed. Declared
 * policy now: DOCUMENTED fail-open (the breaker optimizes resilience, it
 * does not govern) + the enforcement failure is EMITTED as a Degraded
 * event.
 */
class HonestDegradationEventTest {

    private lateinit var registry: ComponentRegistry

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent-honest"),
            name = "Honest Agent",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "honest",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.SEARCH),
        budget = AgentBudget(maxTokens = 30000)
    )

    private class CountingLlmProvider : LlmProviderPort {
        var callCount = 0
        override val providerId: String = "honest_llm"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "honest_llm", name = "Honest", providerType = "FAKE",
            defaultModel = "honest-1", isConfigured = true, isOnline = true, isLocal = false
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            callCount++
            return Outcome.Success(
                LlmResponse(text = "ok", usage = TokenUsage(10, 10), finishReason = "STOP", modelId = "honest-1")
            )
        }

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            callCount++
            emit(ExecutionEvent.ContentChunk(executionId = executionId, deltaText = "ok", sequenceIndex = 0))
            emit(ExecutionEvent.Completed(executionId = executionId, finalText = "ok", totalDurationMs = 1))
        }
    }

    private class StubSearchProvider : SearchProviderPort {
        override val providerId: String = "honest_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "honest_search", name = "Honest Search", providerType = "STUB",
            isConfigured = true, isEnabled = true, priority = 1
        )

        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> =
            Outcome.Success(
                SearchResultSet(
                    query = query.query,
                    items = listOf(
                        SearchResultItem(
                            title = "Result",
                            url = "https://example.com/r",
                            snippet = "a result"
                        )
                    ),
                    providerId = providerId
                )
            )
    }

    @Before
    fun setup() {
        registry = ComponentRegistry()
    }

    private fun buildExecutionService(): ExecutionService =
        ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        )

    private fun newTask(): TaskDefinition = TaskDefinition(
        id = TaskId("task-honest"),
        assignedAgentId = testAgent.identity.id,
        input = TaskInput(rawPrompt = "query"),
        budget = TaskBudget(tokenLimit = 30000)
    )

    private fun searchAction(): DecisionAction = DecisionAction(
        type = DecisionActionType.SEARCH,
        targetId = "honest_search",
        payload = mapOf("query" to "test query"),
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId("honest_search"),
            providerId = "honest_search",
            serviceId = "honest_search-search-service",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.SEARCH),
            rationale = "test",
            confidence = 0.9f
        )
    )

    private fun llmAction(): DecisionAction = DecisionAction(
        type = DecisionActionType.SELECT_MODEL,
        targetId = "honest_llm",
        payload = emptyMap(),
        decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId("honest_llm"),
            providerId = "honest_llm",
            serviceId = "honest_llm",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            rationale = "test",
            confidence = 0.9f
        )
    )

    // ------------------------------------------------------------------
    // GAP-13 — search-intelligence fallback is EMITTED
    // ------------------------------------------------------------------

    @Test
    fun `search intelligence failure degrades to the plain path WITH a Degraded event`() = runBlocking {
        com.example.application.testing.TestResourceRegistration.registerSearchProvider(registry, StubSearchProvider())
        val es = buildExecutionService()
        es.searchIntelligence = { _, _ -> throw IllegalStateException("intelligence layer exploded") }

        val events = mutableListOf<ExecutionEvent>()
        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-gap13-search", "ws-x")) {
            es.executeAction(
                action = searchAction(),
                context = DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-gap13-search",
                onEvent = { events.add(it) }
            )
        }

        assertTrue(
            "GAP-13: the plain search path must still succeed (honest degradation, not a crash)",
            result.isSuccess
        )
        val degraded = events.filterIsInstance<ExecutionEvent.Degraded>()
        assertTrue(
            "GAP-13: the intelligence→plain fallback must emit a Degraded event, got: " +
                    events.map { it::class.simpleName },
            degraded.any { it.message.contains("SEARCH_INTELLIGENCE_UNAVAILABLE") }
        )
    }

    // ------------------------------------------------------------------
    // GAP-13 — RAG → memory fallback is EMITTED
    // ------------------------------------------------------------------

    @Test
    fun `rag retrieval failure falls back to memory WITH a Degraded event`() = runBlocking {
        val es = buildExecutionService()
        es.ragRetrievalProvider = { _, _ -> throw IllegalStateException("rag pipeline exploded") }
        // No memory repository wired — the fallback returns an honest error
        // AFTER the degradation is emitted; both must be visible.

        val events = mutableListOf<ExecutionEvent>()
        withContext(com.example.domain.core.execution.ExecutionScope("exec-gap13-rag", "ws-x")) {
            es.executeAction(
                action = DecisionAction(
                    type = DecisionActionType.RETRIEVE_KNOWLEDGE,
                    targetId = null,
                    payload = mapOf("query" to "knowledge query")
                ),
                context = DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-gap13-rag",
                onEvent = { events.add(it) }
            )
        }

        val degraded = events.filterIsInstance<ExecutionEvent.Degraded>()
        assertTrue(
            "GAP-13: the RAG→memory fallback must emit a Degraded event",
            degraded.any { it.message.contains("RAG_RETRIEVAL_FAILED") }
        )
    }

    // ------------------------------------------------------------------
    // GAP-17 — circuit breaker enforcement failure: EMITTED + documented fail-open
    // ------------------------------------------------------------------

    @Test
    fun `circuit breaker enforcement failure is emitted and the call proceeds (declared fail-open)`() = runBlocking {
        val provider = CountingLlmProvider()
        com.example.application.testing.TestResourceRegistration.registerLlmProvider(registry, provider)
        val es = buildExecutionService()

        // A breaker whose enforcement ALWAYS throws (declared test seam on
        // CircuitBreakerService.allowCall — see its KDoc).
        var consultations = 0
        val explodingGate = object : com.example.application.resilience.CircuitBreakerService() {
            override suspend fun allowCall(resourceId: String): Boolean {
                consultations++
                throw IllegalStateException("breaker internals unavailable")
            }
        }
        es.circuitBreakerGate = explodingGate

        val events = mutableListOf<ExecutionEvent>()
        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-gap17", "ws-x")) {
            es.executeAction(
                action = llmAction(),
                context = DecisionContext(task = newTask()),
                agent = testAgent,
                executionId = "exec-gap17",
                onEvent = { events.add(it) }
            )
        }

        assertTrue(
            "GAP-17 (declared fail-open): a broken breaker must NOT brick the LLM call",
            result.isSuccess
        )
        assertEquals("the provider must actually have been called", 1, provider.callCount)
        val degraded = events.filterIsInstance<ExecutionEvent.Degraded>()
        assertTrue(
            "GAP-17: the enforcement failure must be EMITTED (was silently swallowed), got: " +
                    events.map { it::class.simpleName },
            degraded.any { it.message.contains("CIRCUIT_BREAKER_ENFORCEMENT_FAILED") }
        )
        assertEquals("the broken gate must have been consulted", 1, consultations)
    }

    // ------------------------------------------------------------------
    // GAP-13 — idempotency outcome-write failure is OBSERVABLE
    // ------------------------------------------------------------------

    /** Fake ledger whose updateIntentOutcome always fails (write failure). */
    private class FailingIntentDao : ActionIntentDao {
        override suspend fun insertIntent(intent: ActionIntentEntity): Long = 1L
        override suspend fun getIntent(executionId: String, actionKey: String): ActionIntentEntity? = null
        override suspend fun getIntentsForExecution(executionId: String): List<ActionIntentEntity> = emptyList()
        override suspend fun updateIntentOutcome(
            executionId: String, actionKey: String, state: String,
            fingerprint: String?, summary: String?, now: Long
        ) {
            throw IllegalStateException("ledger write failed (injected)")
        }
        override suspend fun clearIntentsForExecution(executionId: String) { }
        override suspend fun completedIntentCount(executionId: String): Int = 0
    }

    @Test
    fun `idempotency outcome-write failure returns false and records the reason`() = runBlocking {
        val service = ActionIdempotencyService(FailingIntentDao())
        val action = DecisionAction(
            type = DecisionActionType.EXECUTE_TOOL,
            targetId = "some_tool",
            payload = emptyMap()
        )

        val written = service.complete("exec-gap13-ais", 0, action, "the output")

        assertEquals(
            "GAP-13: a failed outcome write must return false (the caller emits the degradation)",
            false,
            written
        )
        assertNotNull(
            "GAP-13: the failure reason must be recorded observably",
            service.lastOutcomeWriteFailure
        )
        assertTrue(
            service.lastOutcomeWriteFailure?.contains("INTENT_OUTCOME_WRITE_FAILED") == true
        )
    }
}
