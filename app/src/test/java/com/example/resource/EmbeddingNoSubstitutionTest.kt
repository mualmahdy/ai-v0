package com.example.resource

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.resource.ResourceContractMigration
import com.example.application.security.SecurityGuardService
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.memory.VectorStoreFailure
import com.example.domain.core.resource.ConfigurationVersion
import com.example.domain.core.resource.DecisionRecord
import com.example.domain.core.resource.ExecutionOutcome
import com.example.domain.core.resource.FallbackPolicy
import com.example.domain.core.resource.GovernanceResult
import com.example.domain.core.resource.GovernanceState
import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.ResourceCategory
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecordInput
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.SecurityResult
import com.example.domain.core.resource.ServiceId
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.resource.RuntimeAdapterBinding
import com.example.domain.ports.resource.RuntimeSupportToken
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.memory.OpenAiCompatibleEmbeddingAdapter
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.provider.ProviderAdapterFactory
import com.example.infrastructure.resource.RoomResourceRegistryService
import com.example.application.rag.RagPipelineService
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.ports.resource.NoCooldownChecker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

/**
 * Section N acceptance tests — Embedding no-substitution mandate (P0.6, RULE AD-3/AD-4).
 * Covers matrix rows 13, 13b, 4 (factory path), plus the RoomVectorStoreAdapter and
 * RagPipelineService de-substitution behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EmbeddingNoSubstitutionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var registry: RoomResourceRegistryService
    private lateinit var executionService: ExecutionService
    private lateinit var localAdapter: LocalDeterministicEmbeddingAdapter
    private val token: RuntimeSupportToken = RuntimeSupportToken.issueForControlPlane()

    private var externalEmbeddingPort: EmbeddingProviderPort? = null

    /** External embedding adapter pointed at a guaranteed-unreachable endpoint. */
    private fun unreachableExternalEmbeddingAdapter(): EmbeddingProviderPort =
        OpenAiCompatibleEmbeddingAdapter(
            providerId = "external_embedding",
            endpointUrl = "http://127.0.0.1:9/v1/embeddings", // port 9 (discard) — connection refused
            modelName = "text-embedding-3-small",
            apiKeyProvider = { "test-key" },
            client = OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
        )

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localAdapter = LocalDeterministicEmbeddingAdapter(providerId = "local_embedding_port", dimension = 128)
        registry = RoomResourceRegistryService(
            dao = database.resourceRecordDao(),
            cooldownChecker = NoCooldownChecker
        ).apply { bindControlPlaneToken(token) }

        executionService = ExecutionService(
            componentRegistry = ComponentRegistry(),
            securityGuard = SecurityGuardService(),
            resourceRegistry = registry,
            adapterResolver = { resourceId, version ->
                when (resourceId.value) {
                    "res_external__text-embedding-3-small" ->
                        RuntimeAdapterBinding.Embedding(
                            port = externalEmbeddingAdapterForTest(),
                            resourceId = resourceId,
                            configurationVersion = version
                        )
                    else -> null
                }
            }
        )
    }

    private fun externalEmbeddingAdapterForTest(): EmbeddingProviderPort =
        externalEmbeddingPort ?: unreachableExternalEmbeddingAdapter()

    @After
    fun teardown() {
        database.close()
    }

    private fun externalEmbeddingDecision(resourceId: ResourceId) = DecisionRecord(
        decisionId = "dec-emb-${System.nanoTime()}",
        taskId = "task-emb",
        stepId = "step-1",
        timestamp = System.currentTimeMillis(),
        selectedResourceId = resourceId,
        selectedProviderId = ProviderId("external"),
        selectedServiceId = ServiceId("text-embedding-3-small"),
        selectedConfigurationVersion = ConfigurationVersion(1),
        requiredCapabilities = setOf(CapabilityType.EMBEDDING.code),
        candidateEvaluations = emptyList(),
        decisionRationale = "external embedding selected",
        confidence = 1.0,
        securityResult = SecurityResult.permitted("test"),
        governanceResult = GovernanceResult(GovernanceState.NOT_APPLICABLE, null, "P0"),
        fallbackPolicy = FallbackPolicy.Fail
    )

    /**
     * Test 13 — external embedding configured then made unavailable: explicit FAILURE
     * with transportError; NO local embedder substitution anywhere on this path.
     */
    @Test
    fun test13_externalEmbeddingUnavailable_explicitFailure_noLocalSubstitution() = runBlocking {
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId("external"),
                serviceId = ServiceId("text-embedding-3-small"),
                resourceType = ResourceType.EMBEDDING,
                category = ResourceCategory.REMOTE,
                capabilities = listOf(CapabilityType.EMBEDDING.code)
            )
        )
        val id = ResourceId("res_external__text-embedding-3-small")
        registry.setLifecycleState(id, ResourceLifecycleState.VALIDATING)
        registry.setRuntimeSupported(id, true, token)
        registry.setLifecycleState(id, ResourceLifecycleState.HEALTHY)

        val result = executionService.execute(externalEmbeddingDecision(id))

        assertEquals(ExecutionOutcome.FAILURE, result.outcome)
        assertNotNull(result.transportError)
        assertTrue(result.transportError!!.startsWith("execution_error:"))
        // Identity: the intended (external) resource is recorded — the local embedder
        // was NOT silently executed in its place.
        assertEquals(id.value, result.executedResourceId.value)
    }

    /**
     * Test 13b — local embedding resource EXISTS (registered, AD-4) and is EXPLICITLY
     * selected by a decision: execution uses the local embedder and identity holds.
     */
    @Test
    fun test13b_localEmbeddingExplicitlySelected_executesWithOwnIdentity() = runBlocking {
        // AD-4 registration path (as the migration does on startup).
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId(ResourceContractMigration.LOCAL_EMBEDDING_PROVIDER_ID),
                serviceId = ServiceId(ResourceContractMigration.LOCAL_EMBEDDING_SERVICE_ID),
                resourceType = ResourceType.EMBEDDING,
                category = ResourceCategory.LOCAL,
                capabilities = listOf(CapabilityType.EMBEDDING.code),
                isFallback = true
            )
        )
        val localId = ResourceId("res_local__local_embedding_engine")
        registry.setLifecycleState(localId, ResourceLifecycleState.VALIDATING)
        registry.setRuntimeSupported(localId, true, token)
        registry.setLifecycleState(localId, ResourceLifecycleState.HEALTHY)

        // Explicit decision selecting the LOCAL embedder (decision-time isFallback semantics).
        var resolverUsedLocalPort = false
        val serviceWithLocal = ExecutionService(
            componentRegistry = ComponentRegistry(),
            securityGuard = SecurityGuardService(),
            resourceRegistry = registry,
            adapterResolver = { resourceId, version ->
                if (resourceId == localId) {
                    resolverUsedLocalPort = true
                    RuntimeAdapterBinding.Embedding(localAdapter, resourceId, version)
                } else null
            }
        )
        val result = serviceWithLocal.execute(externalEmbeddingDecision(localId))

        assertTrue(resolverUsedLocalPort)
        assertEquals(ExecutionOutcome.SUCCESS, result.outcome)
        assertEquals(localId.value, result.executedResourceId.value)
        val vector = (result.output as Map<*, *>)["embeddingVector"]
        assertNotNull(vector)
    }

    /** RULE AD-2: canCreate returns false for flavors without a real embedding adapter. */
    @Test
    fun factory_canCreate_embeddingFlavors() {
        val factory = ProviderAdapterFactory()
        assertTrue(factory.canCreate(ProviderCategory.EMBEDDING, ProviderFlavor.LOCAL_EMBEDDING))
        assertTrue(factory.canCreate(ProviderCategory.EMBEDDING, ProviderFlavor.OPENAI_COMPATIBLE))
        // Previously the factory silently substituted the local embedder for ANY flavor:
        assertFalse(factory.canCreate(ProviderCategory.EMBEDDING, ProviderFlavor.GEMINI))
        assertFalse(factory.canCreate(ProviderCategory.EMBEDDING, ProviderFlavor.TAVILY))
        assertTrue(factory.canCreate(ProviderCategory.LLM, ProviderFlavor.GEMINI))
    }

    /** RoomVectorStoreAdapter: configured-embedding failure -> explicit Error, not lexical. */
    @Test
    fun vectorStore_configuredEmbeddingFails_explicitError() = runBlocking {
        val store = RoomVectorStoreAdapter(
            memoryDao = database.memoryDao(),
            embeddingProvider = unreachableExternalEmbeddingAdapter()
        )
        val result = store.storeMemory(
            MemoryEntry(
                id = "mem-1",
                content = "hello semantic world",
                type = MemoryType.FACTUAL_INSIGHT,
                confidence = 1.0f,
                provenance = MemoryProvenance()
            )
        )
        assertTrue(result is Outcome.Error)
        val message = (result as Outcome.Error).diagnosticMessage
        assertTrue(message.contains("RULE AD-3") || message.contains("بديل صامت"))
    }

    /** RoomVectorStoreAdapter with NO provider configured: honest lexical mode still works. */
    @Test
    fun vectorStore_noProviderConfigured_lexicalHonestMode() = runBlocking {
        val store = RoomVectorStoreAdapter(memoryDao = database.memoryDao(), embeddingProvider = null)
        val result = store.storeMemory(
            MemoryEntry(
                id = "mem-2",
                content = "lexical mode entry",
                type = MemoryType.FACTUAL_INSIGHT,
                confidence = 1.0f,
                provenance = MemoryProvenance()
            )
        )
        assertTrue(result is Outcome.Success)
    }

    /** RagPipelineService: configured-embedding failure rejects chunks — no lexical substitution. */
    @Test
    fun ragPipeline_configuredEmbeddingFails_chunksRejected() = runBlocking {
        val rag = RagPipelineService(embeddingPort = unreachableExternalEmbeddingAdapter())
        val doc = rag.ingestDocument(
            title = "P0.6 test doc",
            content = "This content will NOT be silently lexically embedded. ".repeat(20),
            sourceUri = "workspace://test/p06.md"
        )
        // All chunks rejected: totalChunks reflects ONLY successfully embedded chunks.
        assertEquals(0, doc.totalChunks)
    }

    /** RagPipelineService with a WORKING provider stores real embeddings. */
    @Test
    fun ragPipeline_workingEmbedding_storesSemanticVectors() = runBlocking {
        val rag = RagPipelineService(embeddingPort = localAdapter)
        val doc = rag.ingestDocument(
            title = "P0.6 working doc",
            content = "Semantic embeddings are stored when the provider works. ".repeat(10),
            sourceUri = "workspace://test/ok.md"
        )
        assertTrue(doc.totalChunks > 0)
    }
}
