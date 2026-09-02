package com.example.resource

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionService
import com.example.application.observation.ObservationService
import com.example.application.orchestration.ClosedLoopTaskRunner
import com.example.application.registry.ComponentRegistry
import com.example.application.resource.InMemoryResourceHealthService
import com.example.application.resource.ResourceContractMigration
import com.example.application.security.SecurityGuardService
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.ResourceCapabilityGraph
import com.example.domain.core.resource.ResourceCategory
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecordInput
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.ServiceId
import com.example.domain.core.resource.ResourceExecutionInput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.resource.RuntimeAdapterBinding
import com.example.domain.ports.resource.RuntimeSupportToken
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.resource.RoomDecisionRecordStore
import com.example.infrastructure.resource.RoomEvidenceStore
import com.example.infrastructure.resource.RoomExecutionStateStore
import com.example.infrastructure.resource.RoomResourceRegistryService
import com.example.infrastructure.resource.RoomVerificationOutcomeStore
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Section N acceptance tests — Closed Loop (P0.8, Section I — LOCKED).
 * Covers matrix rows 6 (graph nodes), 10 (re-decision versioning), 15 (full loop +
 * OBS-1 ordering), 15b (single failure: degraded health, no cooldown, still selectable).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClosedLoopAcceptanceTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var registry: RoomResourceRegistryService
    private lateinit var health: InMemoryResourceHealthService
    private lateinit var decisionStore: RoomDecisionRecordStore
    private lateinit var executionStore: RoomExecutionStateStore
    private lateinit var evidenceStore: RoomEvidenceStore
    private lateinit var verificationStore: RoomVerificationOutcomeStore
    private lateinit var decisionService: DecisionService
    private lateinit var observationService: ObservationService
    private lateinit var graph: ResourceCapabilityGraph
    private val token: RuntimeSupportToken = RuntimeSupportToken.issueForControlPlane()

    /** Port that fails the first N calls at the TRANSPORT layer, then succeeds. */
    private class FlakyLlmPort(private val failuresBeforeSuccess: Int) : LlmProviderPort {
        var calls = 0
        val failureLog = mutableListOf<String>()
        override val providerId: String = "flaky_llm"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = providerId, name = "Flaky", providerType = "TEST",
            defaultModel = "flaky-model", isConfigured = true, isOnline = true, isLocal = false
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            calls++
            return if (calls <= failuresBeforeSuccess) {
                failureLog.add("transport failure #$calls")
                Outcome.Error(
                    LlmFailure.ProviderUnavailable(providerId, "HTTP 503 transient"),
                    diagnosticMessage = "ProviderUnavailable: HTTP 503 transient"
                )
            } else {
                Outcome.Success(
                    LlmResponse(
                        text = "synthesized answer",
                        usage = TokenUsage(promptTokens = 2, completionTokens = 3)
                    )
                )
            }
        }

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = emptyFlow()
    }

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        health = InMemoryResourceHealthService()
        registry = RoomResourceRegistryService(
            dao = database.resourceRecordDao(),
            cooldownChecker = { id -> health.isInCooldown(id) }
        ).apply { bindControlPlaneToken(token) }
        decisionStore = RoomDecisionRecordStore(database.decisionRecordDao())
        executionStore = RoomExecutionStateStore(database.executionRecordDao())
        evidenceStore = RoomEvidenceStore(database.evidenceRecordDao())
        verificationStore = RoomVerificationOutcomeStore(database.verificationOutcomeDao())
        graph = ResourceCapabilityGraph(registry)

        decisionService = DecisionService(
            cbrMdpEngine = CbrMdpEngine(caseBase = CaseBase()),
            componentRegistry = ComponentRegistry(),
            securityGuard = SecurityGuardService(),
            resourceRegistry = registry,
            resourceHealthService = health,
            decisionRecordStore = decisionStore
        )
        observationService = ObservationService(
            resourceHealthService = health,
            executionStateStore = executionStore,
            evidenceStore = evidenceStore,
            verificationOutcomeStore = verificationStore
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun seedHealthyLlmResource(
        provider: String,
        service: String,
        port: LlmProviderPort
    ): ResourceId {
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId(provider),
                serviceId = ServiceId(service),
                resourceType = ResourceType.LLM,
                category = ResourceCategory.REMOTE,
                capabilities = listOf(CapabilityType.LLM_GENERATION.code, CapabilityType.REASONING.code)
            )
        )
        val id = ResourceId("res_${provider}__$service")
        registry.setLifecycleState(id, ResourceLifecycleState.VALIDATING)
        registry.setRuntimeSupported(id, true, token)
        registry.setLifecycleState(id, ResourceLifecycleState.HEALTHY)

        // Note: ExecutionService's adapterResolver is injected per-test.
        return id
    }

    private fun runnerWithPort(port: LlmProviderPort): ClosedLoopTaskRunner {
        val executionService = ExecutionService(
            componentRegistry = ComponentRegistry(),
            securityGuard = SecurityGuardService(),
            resourceRegistry = registry,
            adapterResolver = { resourceId, version ->
                RuntimeAdapterBinding.Llm(port, resourceId, version)
            }
        )
        return ClosedLoopTaskRunner(
            decisionService = decisionService,
            executionService = executionService,
            observationService = observationService
        )
    }

    /**
     * Test 6 — graph nodes are ALL keyed by ResourceId; no provider-name keys.
     */
    @Test
    fun test6_graphNodes_keyedByResourceIdOnly() = runBlocking {
        val port = FlakyLlmPort(0)
        seedHealthyLlmResource("openai", "gpt-4o", port)
        seedHealthyLlmResource("ollama", "llama3.2", port)

        graph.rebuildFromRegistry()

        val nodeIds = graph.nodeResourceIds().map { it.value }.toSet()
        // ResourceId keys ONLY (derived deterministically) — provider/model names like
        // "openai", "gpt-4o" are NOT keys.
        assertEquals(setOf("res_openai__gpt-4o", "res_ollama__llama3.2"), nodeIds)
        assertFalse(nodeIds.contains("openai"))
        assertFalse(nodeIds.contains("gpt-4o"))
        assertEquals(2, graph.resourceIdsForCapability(CapabilityType.LLM_GENERATION.code).size)
        // Capability lookups return ResourceIds too.
        assertTrue(
            graph.resourceIdsForCapability(CapabilityType.LLM_GENERATION.code)
                .all { it.value.startsWith("res_") }
        )
    }

    /**
     * Test 10 + 15 + 15b — full loop: decide -> execute -> observe -> verify ->
     * state update -> re-decide. A single transport failure (15b) degrades health
     * WITHOUT cooldown, the re-decision (version 2, test 10) can still select the
     * resource, and every state store reflects the loop (test 15, OBS-1 ordering).
     */
    @Test
    fun test10_15_15b_fullLoop_failureThenRecovery() = runBlocking {
        val id = seedHealthyLlmResource("openai", "gpt-4o", FlakyLlmPort(0))
        val flakyPort = FlakyLlmPort(1) // fails once at transport, then succeeds
        val runner = runnerWithPort(flakyPort)

        val result = runner.runStep(
            taskId = "task-loop",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code),
            input = ResourceExecutionInput(prompt = "Say something synthesized.")
        )

        // Test 10 — a new DecisionRecord (version 2) was created for the re-decision.
        assertEquals(2, result.attempts.size)
        assertEquals(1, result.attempts[0].decision.decisionVersion)
        assertEquals(2, result.attempts[1].decision.decisionVersion)
        assertEquals("res_openai__gpt-4o", result.attempts[1].executedResourceId)
        assertTrue(result.succeeded)

        // Test 15b — health degraded (1 failure), cooldown NOT triggered (needs 3
        // consecutive), and the next decision COULD still select the resource.
        val healthAfter = health.getHealth(id)
        assertTrue(healthAfter.successRate < 1.0)
        assertEquals(1, result.attempts.count { it.outcome == com.example.domain.core.resource.ExecutionOutcome.FAILURE })
        assertEquals(0, health.consecutiveFailures(id)) // cleared by the eventual success
        assertFalse(health.isInCooldown(id))

        // Test 15 — state dimensions updated and observable (OBS-1 ordering):
        // (a) execution state persisted for BOTH attempts, in order.
        val executions = executionStore.getForTask("task-loop")
        assertEquals(2, executions.size)
        assertTrue(executions[0].timestamp <= executions[1].timestamp)
        assertEquals("FAILURE", executions[0].outcome)
        assertEquals("SUCCESS", executions[1].outcome)

        // (b) evidence persisted from the successful execution.
        val evidence = evidenceStore.getForTask("task-loop")
        assertTrue(evidence.isNotEmpty())
        assertTrue(evidence.any { it.evidenceKeys.contains("synthesizedText") })

        // (c) verification outcome recorded: first attempt rejected, second verified.
        val verifications = verificationStore.getForStep("step-1")
        assertEquals(2, verifications.size)
        assertFalse(verifications[0].verified)
        assertTrue(verifications[1].verified)

        // (d) OBS-1 ordering: the version-2 decision record was created AFTER the
        //     first attempt's execution state was persisted (decision input built
        //     after previous state update completed).
        val decisions = decisionStore.getForTask("task-loop")
        assertEquals(2, decisions.size)
        val firstExecution = executions.first { it.decisionId == decisions[0].decisionId }
        assertTrue(decisions[1].timestamp >= firstExecution.timestamp)
    }

    /**
     * Test 15b (negative branch) — three consecutive failures trigger cooldown; the
     * registry usable conjunction then EXCLUDES the resource, so the next decision
     * has no candidates and the loop returns the explicit no-capable-resource state
     * (never a silent substitution).
     */
    @Test
    fun test15b_threeConsecutiveFailures_cooldownExcludesResource() = runBlocking {
        seedHealthyLlmResource("solo", "solo-model", FlakyLlmPort(0))
        val alwaysFailPort = object : LlmProviderPort {
            override val providerId = "always_fail"
            override val metadata = SafeProviderMetadata(
                id = providerId, name = "AlwaysFail", providerType = "TEST",
                defaultModel = "fail-model", isConfigured = true, isOnline = true, isLocal = false
            )
            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Error(
                    LlmFailure.ProviderUnavailable(providerId, "HTTP 503"),
                    diagnosticMessage = "ProviderUnavailable: HTTP 503"
                )
            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = emptyFlow()
        }
        val runner = runnerWithPort(alwaysFailPort)

        val result = runner.runStep(
            taskId = "task-cooldown",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code),
            input = ResourceExecutionInput(prompt = "This will always fail."),
            maxAttempts = 3
        )

        assertFalse(result.succeeded)
        assertEquals(3, result.attempts.size) // three real transport failures
        val id = ResourceId("res_solo__solo-model")
        assertTrue(health.isInCooldown(id)) // 3 consecutive -> cooldown
        // The usable conjunction now excludes the resource.
        assertTrue(registry.queryUsableByCapability(CapabilityType.LLM_GENERATION.code).isEmpty())
    }

    /** AD-4 + test 13b support: migration registers the local embedding as its own record. */
    @Test
    fun migration_registersLocalEmbeddingAsOwnResource() = runBlocking {
        // Provider repository backed by the (empty) Room DB — seeds 3 legacy defaults
        // (gemini_default, tavily_search, local_embedding) on first access.
        val secretStorage = com.example.infrastructure.security.EncryptedSecretStorageAdapter(context)
        val repository: com.example.domain.ports.provider.ProviderRepositoryPort =
            com.example.infrastructure.provider.RoomProviderRepositoryAdapter(
                providerDao = database.providerConfigDao(),
                secureCredentialStorage = secretStorage
            )
        val migration = ResourceContractMigration(repository, registry)
        // 3 legacy providers (RULE REG-3) + 1 AD-4 local embedding resource = 4.
        val registered = migration.migrateIfNeeded()
        assertEquals(4, registered)

        // RULE AD-4: the local embedding engine exists as its OWN ResourceRecord.
        val record = registry.getByLogicalKey(
            ProviderId(ResourceContractMigration.LOCAL_EMBEDDING_PROVIDER_ID),
            ServiceId(ResourceContractMigration.LOCAL_EMBEDDING_SERVICE_ID)
        )
        assertNotNull(record)
        assertTrue(record!!.isFallback) // decision-time fallback semantics (Section J)
        assertEquals(ResourceCategory.LOCAL, record.category)
        assertFalse(record.runtimeSupported) // no adapter assumed (RULE AD-1)

        // Legacy local-embedding-flavored provider registered as fallback too.
        val legacyLocal = registry.getByLogicalKey(
            ProviderId("local_embedding"),
            ServiceId("dense-semantic-128")
        )
        assertNotNull(legacyLocal)
        assertTrue(legacyLocal!!.isFallback)

        // Idempotent: second run registers nothing new.
        assertEquals(0, migration.migrateIfNeeded())
    }
}
