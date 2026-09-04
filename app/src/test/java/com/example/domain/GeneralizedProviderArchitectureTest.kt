package com.example.domain

import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
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
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.provider.AuthenticationType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.OfferingCatalog
import com.example.domain.core.provider.OfferingType
import com.example.domain.core.provider.ProtocolWireFormat
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceOffering
import com.example.domain.core.provider.ServiceProtocol
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.toAuthoritativeResourceRecord
import com.example.domain.core.provider.toDefaultOffering
import com.example.domain.core.provider.toProtocol
import com.example.domain.core.provider.toProvider
import com.example.domain.core.provider.toService
import com.example.domain.core.provider.toServiceConfiguration
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.ports.llm.LlmProviderPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end verification of the Generalized Architecture:
 * Provider -> Service -> Protocol -> Configuration -> Adapter -> Discovery -> Offering -> Resource -> Registry -> CapabilityGraph -> DecisionService -> DecisionRecord -> ExecutionService
 */
class GeneralizedProviderArchitectureTest {

    @Test
    fun testProviderToServiceToProtocolToConfigurationToOfferingChain() {
        // 1. PROVIDER
        val provider = Provider(
            id = "google",
            name = "Google DeepMind",
            description = "Google Gemini models",
            isLocal = false,
            isEnabled = true
        )
        assertEquals("google", provider.id)

        // 2. SERVICE
        val service = ProviderService(
            id = "google-gemini-service",
            providerId = provider.id,
            name = "Gemini Language Service",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf("gemini-rest-protocol")
        )
        assertEquals(provider.id, service.providerId)
        assertEquals(ServiceType.LLM, service.serviceType)

        // 3. PROTOCOL
        val protocol = ServiceProtocol(
            id = "gemini-rest-protocol",
            name = "Gemini REST Protocol",
            wireFormat = ProtocolWireFormat.REST_JSON,
            authType = AuthenticationType.API_KEY_HEADER,
            defaultEndpointTemplate = "https://generativelanguage.googleapis.com"
        )
        assertEquals(ProtocolWireFormat.REST_JSON, protocol.wireFormat)
        assertEquals(AuthenticationType.API_KEY_HEADER, protocol.authType)

        // 4. CONFIGURATION
        val config = ServiceConfiguration(
            id = "cfg-gemini",
            serviceId = service.id,
            protocolId = protocol.id,
            endpointUrl = "https://generativelanguage.googleapis.com",
            defaultOfferingId = "gemini-2.5-flash",
            isEnabled = true,
            isDefault = true,
            healthStatus = HealthStatus.HEALTHY,
            hasSecretKey = true,
            configurationVersion = 1L
        )
        assertEquals(service.id, config.serviceId)
        assertEquals(protocol.id, config.protocolId)

        // 5. OFFERING & OFFERING CATALOG
        val offering = ServiceOffering(
            id = "gemini-2.5-flash",
            serviceId = service.id,
            offeringType = OfferingType.MODEL,
            name = "Gemini 2.5 Flash",
            contextWindowTokens = 1048576,
            supportedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING),
            isLocal = false,
            isAvailable = true
        )

        val catalog = OfferingCatalog()
        catalog.registerOffering(offering)

        val retrievedOffering = catalog.getOffering("gemini-2.5-flash")
        assertNotNull(retrievedOffering)
        assertEquals("Gemini 2.5 Flash", retrievedOffering?.name)

        val reasoningOfferings = catalog.findOfferingsForCapability(CapabilityType.REASONING)
        assertEquals(1, reasoningOfferings.size)

        // 6. RESOURCE RECORD
        val resourceRecord = offering.toResourceRecord(
            providerId = provider.id,
            config = config,
            resourceType = ResourceType.LLM
        )
        assertEquals(ResourceId("res-gemini-2.5-flash"), resourceRecord.resourceId)
        assertEquals(provider.id, resourceRecord.providerId)
        assertEquals(service.id, resourceRecord.serviceId)
        assertEquals(ResourceLifecycleState.ACTIVE, resourceRecord.lifecycleState)
        assertTrue(resourceRecord.capabilities.contains(CapabilityType.LLM_GENERATION))
    }

    @Test
    fun testProviderConfigurationBridgeFunctions() {
        val legacyConfig = ProviderConfiguration(
            id = "gemini-primary",
            name = "Gemini Pro",
            category = ProviderCategory.LLM,
            flavor = ProviderFlavor.GEMINI,
            endpointUrl = ProviderFlavor.GEMINI.defaultEndpoint,
            defaultModelId = "gemini-2.5-pro",
            isEnabled = true,
            isDefault = true,
            healthStatus = HealthStatus.HEALTHY
        )

        val provider = legacyConfig.toProvider()
        assertEquals("gemini", provider.id)
        assertEquals("Google DeepMind", provider.name)

        val service = legacyConfig.toService()
        assertEquals("gemini-primary-service", service.id)
        assertEquals(ServiceType.LLM, service.serviceType)

        val protocol = legacyConfig.toProtocol()
        assertEquals(ProtocolWireFormat.REST_JSON, protocol.wireFormat)

        val svcConfig = legacyConfig.toServiceConfiguration()
        assertEquals("cfg-gemini-primary", svcConfig.id)
        assertEquals(service.id, svcConfig.serviceId)

        val offering = legacyConfig.toDefaultOffering()
        assertEquals("gemini-2.5-pro", offering.id)
        assertTrue(offering.supportedCapabilities.contains(CapabilityType.LLM_GENERATION))

        val resourceRecord = legacyConfig.toAuthoritativeResourceRecord()
        assertEquals(ResourceId("res-gemini-2.5-pro"), resourceRecord.resourceId)
        assertEquals("gemini", resourceRecord.providerId)
        assertEquals(HealthStatus.HEALTHY, resourceRecord.healthStatus)
    }

    @Test
    fun testExecutionServiceWithDecisionRecordAndAuthoritativeAdapter() = runBlocking {
        val registry = ComponentRegistry()
        val securityGuard = SecurityGuardService()
        val cbrMdpEngine = CbrMdpEngine()

        // Mock LLM Adapter
        val mockAdapter = object : LlmProviderPort {
            override val providerId: String = "gemini"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "gemini",
                name = "Gemini Test",
                providerType = "GEMINI",
                defaultModel = "gemini-2.5-flash",
                isConfigured = true,
                isLocal = false,
                isOnline = true
            )

            override suspend fun generate(request: LlmRequest): com.example.domain.core.Outcome<LlmResponse, LlmFailure> {
                return com.example.domain.core.Outcome.Success(
                    LlmResponse(
                        text = "رد ذكي مولد من النموذج المصرح",
                        usage = TokenUsage(10, 20, 30),
                        modelId = "gemini-2.5-flash"
                    )
                )
            }

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> {
                return flowOf()
            }
        }

        registry.registerLlmProvider(mockAdapter, isDefault = true)

        val decisionService = DecisionService(
            cbrMdpEngine = cbrMdpEngine,
            componentRegistry = registry,
            securityGuard = securityGuard
        )

        val executionService = ExecutionService(
            componentRegistry = registry,
            securityGuard = securityGuard
        )

        val agentId = AgentId("agent-general")
        val task = TaskDefinition(
            id = TaskId("task-test-01"),
            assignedAgentId = agentId,
            goal = "اشرح بنية المزود المعممة",
            input = TaskInput(rawPrompt = "اشرح بنية المزود المعممة"),
            constraints = TaskConstraints(autonomyPolicy = AutonomyPolicy.AUTONOMOUS),
            state = TaskLifecycleState.CREATED
        )

        val agent = AgentDefinition(
            identity = AgentIdentity(
                id = agentId,
                name = "المساعد العام",
                role = AgentRole.GENERAL_ASSISTANT,
                description = "مساعد ذكي شامل",
                systemPrompt = "أنت مساعد ذكي"
            ),
            allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
            budget = AgentBudget(maxTokens = 10000)
        )

        val context = decisionService.buildDecisionContext(task = task)

        // Build DecisionRecord for authoritative resource
        val decisionRecord = DecisionRecord(
            selectedResourceId = ResourceId("gemini"),
            providerId = "gemini",
            serviceId = "gemini-2.5-flash",
            configurationVersion = 1L,
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            rationale = "تم اختيار النموذج الأنسب بناء على المعايير المعمارية",
            confidence = 0.95f
        )

        val action = DecisionAction(
            type = DecisionActionType.EXECUTE_STEP,
            targetId = "gemini",
            decisionRecord = decisionRecord
        )

        val result = executionService.executeAction(
            action = action,
            context = context,
            agent = agent,
            conversationHistory = listOf(
                LlmMessage(role = MessageRole.USER, content = "اشرح البنية")
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("رد ذكي مولد من النموذج المصرح", result.outputText)
    }
}
