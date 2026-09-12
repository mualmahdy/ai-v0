package com.example.application

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionService
import com.example.domain.core.decision.CbrMdpEngine
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.memory.EmbeddingFailure
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskContracts
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.ports.memory.EmbeddingProviderPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * GAP-07 (Design Closure 2026, ADR-4) — ChatKnowledgeGroundingTest
 * ============================================================================
 *
 * The audit finding: ordinary CHAT conversations never retrieved from the
 * knowledge base unless the prompt happened to contain memory/rag keywords
 * (Tier-4 keyword heuristics), so answers were ungrounded in the common
 * case while the product advertised local RAG.
 *
 * ADR-4 near-term policy: a CHAT contract with a NON-EMPTY corpus always
 * nominates a RETRIEVE_KNOWLEDGE candidate (keyword-free prompts included);
 * QUICK_CHAT stays structurally retrieval-free.
 *
 * This test pins:
 *   1. CHAT + corpus=true → RETRIEVE_KNOWLEDGE nominated for a
 *      keyword-free prompt;
 *   2. CHAT + corpus=false (the honest unknown) → NO retrieval nomination;
 *   3. QUICK_CHAT + corpus → the contract filter still structurally blocks
 *      retrieval from the chosen action;
 *   4. the ProtocolAdapterFactory router seam: IN_PROCESS embedding
 *      resources bind to the composition-root ROUTER (ONNX-when-provisioned)
 *      instead of a bare lexical adapter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatKnowledgeGroundingTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var decisionService: DecisionService

    private val fakeEmbedding = object : EmbeddingProviderPort {
        override val providerId: String = "grounding_embed"
        override val dimension: Int = 128
        override val metadata: SafeEmbeddingProviderMetadata = SafeEmbeddingProviderMetadata(
            id = "grounding_embed", name = "Grounding", providerType = "LOCAL",
            dimension = 128, isLocal = true, isEnabled = true
        )

        override suspend fun generateEmbeddings(texts: List<String>): Outcome<List<EmbeddingVector>, EmbeddingFailure> =
            Outcome.Success(texts.map { EmbeddingVector(values = FloatArray(128) { 0.01f }) })
    }

    @Before
    fun setup() {
        registry = ComponentRegistry()
        TestResourceRegistration.registerEmbeddingProvider(registry, fakeEmbedding)
        decisionService = DecisionService(
            cbrMdpEngine = CbrMdpEngine(),
            resourceCapabilityGraph = registry.resourceCapabilityGraph,
            securityGuard = SecurityGuardService()
        )
    }

    /** A keyword-free chat prompt — pre-GAP-07 this NEVER retrieved. */
    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent-ground"),
            name = "Ground Agent",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "grounding test",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.MEMORY_RETRIEVAL, CapabilityType.EMBEDDING),
        budget = AgentBudget(maxTokens = 30000)
    )

    private fun chatTask(prompt: String = "مرحبا، كيف حالك اليوم؟"): TaskDefinition = TaskDefinition(
        id = TaskId("t-grounding"),
        assignedAgentId = testAgent.identity.id,
        input = TaskInput(rawPrompt = prompt),
        budget = TaskBudget(tokenLimit = 30000)
    )

    @Test
    fun `chat contract with a corpus nominates retrieval for keyword-free prompts`() = runBlocking {
        val context = decisionService.buildDecisionContext(
            task = chatTask(),
            taskContract = TaskContracts.CHAT,
            hasKnowledgeCorpus = true
        )
        val candidates = decisionService.generateCandidateActions(context)
        assertTrue(
            "GAP-07: CHAT + corpus must nominate RETRIEVE_KNOWLEDGE (keyword-free prompt), got: ${candidates.map { it.type }}",
            candidates.any { it.type == DecisionActionType.RETRIEVE_KNOWLEDGE }
        )
    }

    @Test
    fun `chat contract without a corpus stays honestly ungrounded`() = runBlocking {
        val context = decisionService.buildDecisionContext(
            task = chatTask(),
            taskContract = TaskContracts.CHAT,
            hasKnowledgeCorpus = false
        )
        val candidates = decisionService.generateCandidateActions(context)
        assertFalse(
            "GAP-07: CHAT without corpus must NOT claim grounding",
            candidates.any { it.type == DecisionActionType.RETRIEVE_KNOWLEDGE }
        )
    }

    @Test
    fun `quick chat never chooses retrieval even with a corpus`() = runBlocking {
        val context = decisionService.buildDecisionContext(
            task = chatTask(),
            taskContract = TaskContracts.QUICK_CHAT,
            hasKnowledgeCorpus = true
        )
        val result = decisionService.evaluate(context)
        assertFalse(
            "GAP-07: QUICK_CHAT must stay structurally retrieval-free, chose: ${result.chosenAction.type}",
            result.chosenAction.type == DecisionActionType.RETRIEVE_KNOWLEDGE
        )
    }

    @Test
    fun `in-process embedding resources bind to the composition-root router`() {
        // GAP-07 seam: the factory must hand out the ROUTER for IN_PROCESS
        // embedding services when the composition root provides one, so
        // provisioning ONNX upgrades the real resource adapter. The ONNX
        // adapter's constructor is lazy (no model load) — Robolectric's
        // context suffices.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.infrastructure.memory.semantic.LocalSemanticEmbeddingRouter(
            semanticAdapter = com.example.infrastructure.memory.semantic.OnnxSemanticEmbeddingAdapter(
                appContext = context
            ),
            lexicalFallback = com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter()
        )
        val factoryWithRouter = com.example.infrastructure.provider.ProtocolAdapterFactory(
            inProcessEmbeddingRouter = router
        )
        val service = com.example.domain.core.provider.ProviderService(
            id = "local_embedding",
            providerId = "local",
            name = "Local Embedding",
            description = "",
            serviceType = com.example.domain.core.provider.ServiceType.EMBEDDING
        )
        val config = com.example.domain.core.provider.ServiceConfiguration(
            id = "cfg_local_embedding",
            serviceId = "local_embedding",
            protocolId = com.example.domain.core.provider.ServiceProtocolId.IN_PROCESS,
            endpointUrl = "",
            authAlias = null,
            isEnabled = true,
            defaultOfferingId = "local-128"
        )
        val adapter = factoryWithRouter.createEmbeddingAdapter(
            service = service,
            protocolId = config.protocolId,
            config = config,
            apiKeyProvider = { null },
            offeringModelId = "local-128"
        )
        assertSame("GAP-07: IN_PROCESS embedding must bind the router", router, adapter)

        // Without the router: the previous behavior (bare lexical adapter).
        val factoryBare = com.example.infrastructure.provider.ProtocolAdapterFactory()
        val bare = factoryBare.createEmbeddingAdapter(
            service = service,
            protocolId = config.protocolId,
            config = config,
            apiKeyProvider = { null },
            offeringModelId = "local-128"
        )
        assertTrue("GAP-07: without a router the lexical adapter remains", bare is com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter)
    }
}
