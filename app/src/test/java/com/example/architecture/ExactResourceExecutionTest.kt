package com.example.architecture

import com.example.application.registry.ComponentRegistry
import com.example.application.resource.RuntimeAdapterResolver
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.resource.ResourceId
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * ExactResourceExecutionTest — Phase 4 architectural invariant
 * ============================================================================
 *
 * Per the architectural plan (Section 6 + Section 20):
 *
 * When a `DecisionRecord` exists, `selectedResourceId + configurationVersion`
 * MUST identify what is executed. The resolver MUST resolve by
 * `ResourceId + configurationVersion`, not by provider name, model name,
 * category, flavor, or default.
 *
 * A stale configuration version must fail explicitly. No silent version
 * substitution.
 */
class ExactResourceExecutionTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var resolver: RuntimeAdapterResolver

    @Before
    fun setup() {
        registry = ComponentRegistry()
        resolver = registry.runtimeAdapterResolver
    }

    @Test
    fun `resolver returns exact adapter for the requested ResourceId`() = runBlocking {
        val providerA = mockLlmProvider("provider_a", "model-a")
        val providerB = mockLlmProvider("provider_b", "model-b")
        val resIdA = TestResourceRegistration.registerLlmProvider(registry, providerA)
        val resIdB = TestResourceRegistration.registerLlmProvider(registry, providerB)

        // Resolve A — must get A, not B
        val outcome = resolver.resolveLlmAdapter(resIdA, expectedVersion = 1L)
        assertTrue(outcome is Outcome.Success)
        val resolved = (outcome as Outcome.Success).value
        assertEquals("provider_a", resolved.providerId)

        // Resolve B — must get B, not A
        val outcomeB = resolver.resolveLlmAdapter(resIdB, expectedVersion = 1L)
        assertTrue(outcomeB is Outcome.Success)
        val resolvedB = (outcomeB as Outcome.Success).value
        assertEquals("provider_b", resolvedB.providerId)
    }

    @Test
    fun `resolver rejects stale configuration version`() = runBlocking {
        val provider = mockLlmProvider("provider_a", "model-a")
        val resIdA = TestResourceRegistration.registerLlmProvider(registry, provider)

        // The ResourceRecord has configurationVersion = 1L. Request version = 5L.
        val outcome = resolver.resolveLlmAdapter(resIdA, expectedVersion = 5L)
        assertTrue("Stale version must be rejected", outcome is Outcome.Error)
        val error = (outcome as Outcome.Error).failure
        assertTrue(
            "Error must be StaleConfigurationVersion, got: $error",
            error is com.example.domain.core.resource.ResourceResolutionFailure.StaleConfigurationVersion
        )
    }

    @Test
    fun `resolver rejects unknown ResourceId`() = runBlocking {
        val outcome = resolver.resolveLlmAdapter(ResourceId("does_not_exist"), expectedVersion = null)
        assertTrue(outcome is Outcome.Error)
        val error = (outcome as Outcome.Error).failure
        assertTrue(error is com.example.domain.core.resource.ResourceResolutionFailure.InvalidResourceId)
    }

    private fun mockLlmProvider(providerId: String, modelName: String): LlmProviderPort {
        return object : LlmProviderPort {
            override val providerId: String = providerId
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = providerId, name = providerId, providerType = "MOCK",
                defaultModel = modelName, isConfigured = true, isOnline = true, isLocal = false
            )
            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(LlmResponse(text = "ok", usage = TokenUsage(), modelId = modelName))
            override fun stream(request: LlmRequest, executionId: String): Flow<com.example.domain.core.events.ExecutionEvent> = flow { }
        }
    }
}
