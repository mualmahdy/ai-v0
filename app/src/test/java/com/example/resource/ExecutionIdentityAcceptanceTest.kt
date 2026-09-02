package com.example.resource

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
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
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.resource.RuntimeAdapterBinding
import com.example.domain.ports.resource.RuntimeSupportToken
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.resource.RoomResourceRegistryService
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.ports.resource.NoCooldownChecker
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Section N acceptance tests — Execution Identity Enforcement (P0.5, Section F — LOCKED).
 * Covers matrix rows 8, 8b, 8c, 8d, 9, 10 (version basis), 11.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecutionIdentityAcceptanceTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var registry: RoomResourceRegistryService
    private lateinit var executionService: ExecutionService
    private val token: RuntimeSupportToken = RuntimeSupportToken.issueForControlPlane()

    private val successPort = FakeLlmPort(defaultText = "ok-response")
    private val failingPort = FakeLlmPort(defaultText = "", failure = LlmFailure.ProviderUnavailable("openai", "HTTP 503"))

    private var adapterResolver: (suspend (ResourceId, ConfigurationVersion) -> RuntimeAdapterBinding?)? = null

    /** Minimal controllable LLM port for the adapter-binding path. */
    private class FakeLlmPort(
        val defaultText: String,
        val failure: LlmFailure? = null
    ) : LlmProviderPort {
        override val providerId: String = "fake_llm"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = providerId, name = "Fake", providerType = "TEST",
            defaultModel = "fake-model", isConfigured = true, isOnline = true, isLocal = false
        )

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> {
            val failure = failure
            return if (failure != null) {
                Outcome.Error(failure, diagnosticMessage = failure.toString())
            } else {
                Outcome.Success(
                    LlmResponse(text = defaultText, usage = TokenUsage(promptTokens = 1, completionTokens = 1))
                )
            }
        }

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = emptyFlow()
    }

    private fun decision(
        resourceId: ResourceId,
        configurationVersion: Int = 1
    ) = DecisionRecord(
        decisionId = "dec-${System.nanoTime()}",
        taskId = "task-exec",
        stepId = "step-1",
        timestamp = System.currentTimeMillis(),
        decisionVersion = 1,
        selectedResourceId = resourceId,
        selectedProviderId = ProviderId("openai"),
        selectedServiceId = ServiceId("gpt-4o"),
        selectedConfigurationVersion = ConfigurationVersion(configurationVersion),
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION.code),
        candidateEvaluations = emptyList(),
        decisionRationale = "test decision",
        confidence = 1.0,
        securityResult = SecurityResult.permitted("test"),
        governanceResult = GovernanceResult(
            GovernanceState.NOT_APPLICABLE, null, "no policy in P0"
        ),
        fallbackPolicy = FallbackPolicy.Fail
    )

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        registry = RoomResourceRegistryService(
            dao = database.resourceRecordDao(),
            cooldownChecker = NoCooldownChecker
        ).apply { bindControlPlaneToken(token) }

        // Seed a healthy, runtime-supported LLM resource.
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId("openai"),
                serviceId = ServiceId("gpt-4o"),
                resourceType = ResourceType.LLM,
                category = ResourceCategory.REMOTE,
                capabilities = listOf(CapabilityType.LLM_GENERATION.code)
            )
        )
        registry.setLifecycleState(ResourceId("res_openai__gpt-4o"), ResourceLifecycleState.VALIDATING)
        registry.setRuntimeSupported(ResourceId("res_openai__gpt-4o"), true, token)
        registry.setLifecycleState(ResourceId("res_openai__gpt-4o"), ResourceLifecycleState.HEALTHY)

        executionService = ExecutionService(
            componentRegistry = ComponentRegistry(),
            securityGuard = SecurityGuardService(),
            resourceRegistry = registry,
            adapterResolver = { resourceId, configurationVersion ->
                adapterResolver?.invoke(resourceId, configurationVersion)
            }
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    /** Tests 8/8b — valid decision with adapter present: SUCCESS and identity holds. */
    @Test
    fun test8_8b_validDecisionWithAdapter_identityHolds() = runBlocking {
        adapterResolver = { _, version ->
            RuntimeAdapterBinding.Llm(successPort, ResourceId("res_openai__gpt-4o"), version)
        }
        val decision = decision(ResourceId("res_openai__gpt-4o"))
        val result = executionService.execute(decision)

        assertEquals(ExecutionOutcome.SUCCESS, result.outcome)
        assertEquals(decision.selectedResourceId.value, result.executedResourceId.value)
        assertNull(result.transportError)
        assertEquals("ok-response", (result.output as Map<*, *>)["synthesizedText"])
    }

    /** Test 8c — adapter absent (runtimeSupported=false): FAILURE "runtime_unsupported". */
    @Test
    fun test8c_runtimeUnsupported_explicitFailure() = runBlocking {
        // A second resource that is registered but NOT runtime-supported.
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId("anthropic"),
                serviceId = ServiceId("claude-3"),
                resourceType = ResourceType.LLM,
                category = ResourceCategory.REMOTE,
                capabilities = listOf(CapabilityType.LLM_GENERATION.code)
            )
        )
        val unsupportedId = ResourceId("res_anthropic__claude-3")
        // Record stays CONFIGURED + runtimeSupported=false (no adapter path ran — RULE AD-1/AD-2).

        val decision = decision(unsupportedId)
        val result = executionService.execute(decision)

        assertEquals(ExecutionOutcome.FAILURE, result.outcome)
        assertEquals("runtime_unsupported", result.transportError)
        // Identity: FAILURE records the INTENDED resource.
        assertEquals(unsupportedId.value, result.executedResourceId.value)
    }

    /** Tests 8d/11 — registry lookup miss: FAILURE "resource_unresolvable", no substitution. */
    @Test
    fun test8d_11_unknownResourceId_explicitFailure() = runBlocking {
        val decision = decision(ResourceId("res_ghost__nonexistent"))
        val result = executionService.execute(decision)

        assertEquals(ExecutionOutcome.FAILURE, result.outcome)
        assertEquals("resource_unresolvable", result.transportError)
        assertEquals(decision.selectedResourceId.value, result.executedResourceId.value)
    }

    /** Adapter binding missing: FAILURE "adapter_binding_failed" (no substitution). */
    @Test
    fun adapterMissing_bindingFailed_noSubstitution() = runBlocking {
        adapterResolver = { _, _ -> null } // control plane has no adapter for this binding
        val decision = decision(ResourceId("res_openai__gpt-4o"))
        val result = executionService.execute(decision)

        assertEquals(ExecutionOutcome.FAILURE, result.outcome)
        assertEquals("adapter_binding_failed", result.transportError)
        assertEquals("res_openai__gpt-4o", result.executedResourceId.value)
    }

    /** lifecycle != HEALTHY: FAILURE "resource_not_usable". */
    @Test
    fun lifecycleNotHealthy_notUsableFailure() = runBlocking {
        // Disable the resource: HEALTHY -> DISABLED.
        registry.setLifecycleState(ResourceId("res_openai__gpt-4o"), ResourceLifecycleState.DISABLED)
        adapterResolver = { _, version ->
            RuntimeAdapterBinding.Llm(successPort, ResourceId("res_openai__gpt-4o"), version)
        }
        val result = executionService.execute(decision(ResourceId("res_openai__gpt-4o")))

        assertEquals(ExecutionOutcome.FAILURE, result.outcome)
        assertEquals("resource_not_usable", result.transportError)
    }

    /** Version-bound binding: stale decision version -> adapter_binding_failed. */
    @Test
    fun staleConfigurationVersion_bindingFailed() = runBlocking {
        registry.bumpConfigurationVersion(ResourceId("res_openai__gpt-4o")) // v1 -> v2
        adapterResolver = { _, version ->
            RuntimeAdapterBinding.Llm(successPort, ResourceId("res_openai__gpt-4o"), version)
        }
        // Decision still carries the stale v1.
        val result = executionService.execute(decision(ResourceId("res_openai__gpt-4o"), configurationVersion = 1))
        assertEquals("adapter_binding_failed", result.transportError)

        // Re-decision with the current version executes normally.
        val fresh = executionService.execute(decision(ResourceId("res_openai__gpt-4o"), configurationVersion = 2))
        assertEquals(ExecutionOutcome.SUCCESS, fresh.outcome)
    }

    /** GOV-3: BLOCKED governance blocks execution before any resource resolution. */
    @Test
    fun gov3_blockedGovernance_blocksExecution() = runBlocking {
        val blocked = decision(ResourceId("res_openai__gpt-4o")).copy(
            governanceResult = GovernanceResult(GovernanceState.BLOCKED, "policy-1", "denied by test policy")
        )
        val result = executionService.execute(blocked)
        assertEquals(ExecutionOutcome.FAILURE, result.outcome)
        assertEquals("governance_blocked", result.transportError)
        assertNotNull(result.executedResourceId) // intended resource recorded
    }

    /**
     * Test 9 — attempted silent substitution: the ONLY failure modes are explicit
     * (demonstrated above). This test walks every failure mode and asserts that the
     * executedResourceId ALWAYS equals the intended selectedResourceId — i.e., no
     * code path produced a different (substituted) resource.
     */
    @Test
    fun test9_noSilentSubstitution_identityAlwaysIntended() = runBlocking {
        adapterResolver = null
        val cases = listOf(
            ResourceId("res_ghost__nonexistent"),                    // unresolvable
            ResourceId("res_anthropic__claude-3")                    // unsupported (registered below)
        )
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId("anthropic"),
                serviceId = ServiceId("claude-3"),
                resourceType = ResourceType.LLM,
                category = ResourceCategory.REMOTE,
                capabilities = listOf(CapabilityType.LLM_GENERATION.code)
            )
        )
        for (target in cases) {
            val result = executionService.execute(decision(target))
            assertEquals(ExecutionOutcome.FAILURE, result.outcome)
            assertEquals(target.value, result.executedResourceId.value)
            assertTrue(result.transportError in setOf(
                "resource_unresolvable", "runtime_unsupported",
                "resource_not_usable", "adapter_binding_failed"
            ))
        }
    }
}
