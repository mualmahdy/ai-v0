package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.provider.ProviderControlPlaneService
import com.example.domain.core.Outcome
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.offering.OfferingType
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.resource.ResourceId
import com.example.domain.ports.provider.OfferingRepository
import com.example.domain.ports.provider.ProviderRepository
import com.example.domain.ports.provider.ProviderServiceRepository
import com.example.domain.ports.provider.ServiceConfigurationRepository
import com.example.domain.ports.provider.ServiceHealthRepository
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.domain.ports.resource.ResourceRecordRepository
import com.example.domain.core.provider.preference.UserPreferenceRepository
import com.example.infrastructure.llm.gemini.GeminiBootstrap
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.repository.RoomOfferingRepository
import com.example.infrastructure.persistence.repository.RoomProviderRepository
import com.example.infrastructure.persistence.repository.RoomProviderServiceRepository
import com.example.infrastructure.persistence.repository.RoomResourceRecordRepository
import com.example.infrastructure.persistence.repository.RoomServiceConfigurationRepository
import com.example.infrastructure.persistence.repository.RoomServiceHealthRepository
import com.example.infrastructure.persistence.repository.RoomUserPreferenceRepository
import com.example.infrastructure.provider.ProtocolAdapterFactory
import com.example.infrastructure.security.EncryptedSecretStorageAdapter
import com.example.infrastructure.validation.defaultResourceValidatorRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * ProviderControlPlaneTest — Phase 4 (rewritten for generalized architecture)
 * ============================================================================
 *
 * Verifies the new generalized control plane service operates on:
 *   Provider → ProviderService → ServiceProtocol → ServiceConfiguration
 *
 * The legacy `ProviderConfiguration → ProviderCategory → ProviderFlavor`
 * surface is no longer used by the control plane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderControlPlaneTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var secureStorage: SecureCredentialStoragePort
    private lateinit var providerRepo: ProviderRepository
    private lateinit var serviceRepo: ProviderServiceRepository
    private lateinit var configRepo: ServiceConfigurationRepository
    private lateinit var healthRepo: ServiceHealthRepository
    private lateinit var offeringRepo: OfferingRepository
    private lateinit var resourceRepo: ResourceRecordRepository
    private lateinit var userPrefRepo: UserPreferenceRepository
    private lateinit var controlPlane: ProviderControlPlaneService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        secureStorage = EncryptedSecretStorageAdapter(context)
        providerRepo = RoomProviderRepository(database.providerDao())
        serviceRepo = RoomProviderServiceRepository(database.providerServiceDao())
        configRepo = RoomServiceConfigurationRepository(database.serviceConfigurationDao(), database.providerServiceDao())
        healthRepo = RoomServiceHealthRepository(database.serviceHealthRecordDao())
        offeringRepo = RoomOfferingRepository(database.serviceOfferingDao())
        resourceRepo = RoomResourceRecordRepository(database.resourceRecordDao())
        userPrefRepo = RoomUserPreferenceRepository(database.userResourcePreferenceDao())

        val geminiBootstrap = GeminiBootstrap(context)
        val adapterFactory = ProtocolAdapterFactory(geminiBootstrap = geminiBootstrap)
        val validatorRegistry = defaultResourceValidatorRegistry()

        controlPlane = ProviderControlPlaneService(
            providerRepository = providerRepo,
            serviceRepository = serviceRepo,
            configurationRepository = configRepo,
            healthRepository = healthRepo,
            offeringRepository = offeringRepo,
            resourceRecordRepository = resourceRepo,
            userPreferenceRepository = userPrefRepo,
            secureCredentialStorage = secureStorage,
            adapterFactory = adapterFactory,
            validatorRegistry = validatorRegistry
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `create and persist a Provider`() = runBlocking {
        val provider = Provider(
            id = "test_provider",
            name = "Test Provider",
            description = "Test",
            isLocal = false,
            isEnabled = true
        )
        val outcome = controlPlane.createProvider(provider)
        assertTrue(outcome is Outcome.Success)

        val retrieved = controlPlane.getProviderById("test_provider")
        assertNotNull(retrieved)
        assertEquals("Test Provider", retrieved?.name)
    }

    @Test
    fun `add a Service to a Provider`() = runBlocking {
        val provider = Provider(
            id = "p1",
            name = "P1",
            isLocal = false,
            isEnabled = true
        )
        controlPlane.createProvider(provider)

        val service = com.example.domain.core.provider.ProviderService(
            id = "p1_llm",
            providerId = "p1",
            name = "LLM Service",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
            isEnabled = true
        )
        val outcome = controlPlane.addService(service)
        assertTrue(outcome is Outcome.Success)

        val services = controlPlane.getServicesForProvider("p1")
        assertEquals(1, services.size)
        assertEquals(ServiceType.LLM, services[0].serviceType)
    }

    @Test
    fun `addService rejects incompatible protocol`() = runBlocking {
        val provider = Provider(id = "p1", name = "P1", isEnabled = true)
        controlPlane.createProvider(provider)

        // LLM service with TAVILY_NATIVE protocol — must be rejected
        val service = com.example.domain.core.provider.ProviderService(
            id = "p1_llm",
            providerId = "p1",
            name = "Bad Service",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.TAVILY_NATIVE.code),
            isEnabled = true
        )
        val outcome = controlPlane.addService(service)
        assertTrue(outcome is Outcome.Error)
    }

    @Test
    fun `save ServiceConfiguration bumps version monotonically`() = runBlocking {
        val provider = Provider(id = "p1", name = "P1", isEnabled = true)
        controlPlane.createProvider(provider)

        val service = com.example.domain.core.provider.ProviderService(
            id = "p1_llm",
            providerId = "p1",
            name = "LLM",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
            isEnabled = true
        )
        controlPlane.addService(service)

        // Save config v1
        val cfg1 = ServiceConfiguration(
            id = "cfg1",
            serviceId = "p1_llm",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "https://example.com/v1",
            isEnabled = true
        )
        controlPlane.saveConfiguration(cfg1)
        val v1 = controlPlane.getCurrentConfigurationForService("p1_llm")
        assertNotNull(v1)
        assertEquals(1L, v1?.configurationVersion)

        // Save config v2 (same id, update in place)
        val cfg2 = cfg1.copy(endpointUrl = "https://example.com/v2")
        controlPlane.saveConfiguration(cfg2)
        val v2 = controlPlane.getCurrentConfigurationForService("p1_llm")
        assertNotNull(v2)
        assertTrue(v2?.configurationVersion!! > v1?.configurationVersion!!)
    }

    @Test
    fun `materializeResource creates ResourceRecord at REGISTERED_false_UNKNOWN`() = runBlocking {
        val provider = Provider(id = "p1", name = "P1", isEnabled = true)
        controlPlane.createProvider(provider)

        val service = com.example.domain.core.provider.ProviderService(
            id = "p1_llm",
            providerId = "p1",
            name = "LLM",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
            isEnabled = true
        )
        controlPlane.addService(service)

        val cfg = ServiceConfiguration(
            id = "cfg1",
            serviceId = "p1_llm",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "https://example.com/v1",
            isEnabled = true
        )
        controlPlane.saveConfiguration(cfg)

        // Register an offering manually (no discovery in test)
        val offering = ServiceOffering(
            id = "gpt-4o-mini",
            serviceId = "p1_llm",
            offeringType = OfferingType.MODEL,
            name = "GPT-4o mini",
            supportedCapabilities = setOf(com.example.domain.core.capability.CapabilityType.LLM_GENERATION),
            isAvailable = true
        )
        offeringRepo.registerOffering(offering)

        // Materialize
        val outcome = controlPlane.materializeResource("p1", "p1_llm", "gpt-4o-mini")
        assertTrue(outcome is Outcome.Success)
        val record = (outcome as Outcome.Success).value
        assertEquals(com.example.domain.core.resource.ResourceLifecycleState.REGISTERED, record.lifecycleState)
        assertEquals(false, record.runtimeSupported)
        assertEquals(com.example.domain.core.provider.HealthStatus.UNKNOWN, record.healthStatus)
    }

    @Test
    fun `disableResource sets lifecycle to DISABLED`() = runBlocking {
        // Set up a provider+service+config+offering+resource
        val provider = Provider(id = "p1", name = "P1", isEnabled = true)
        controlPlane.createProvider(provider)
        val service = com.example.domain.core.provider.ProviderService(
            id = "p1_llm", providerId = "p1", name = "LLM",
            serviceType = ServiceType.LLM,
            supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
            isEnabled = true
        )
        controlPlane.addService(service)
        val cfg = ServiceConfiguration(
            id = "cfg1", serviceId = "p1_llm",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "https://example.com/v1", isEnabled = true
        )
        controlPlane.saveConfiguration(cfg)
        val offering = ServiceOffering(
            id = "gpt-4o", serviceId = "p1_llm",
            offeringType = OfferingType.MODEL, name = "GPT-4o",
            supportedCapabilities = setOf(com.example.domain.core.capability.CapabilityType.LLM_GENERATION),
            isAvailable = true
        )
        offeringRepo.registerOffering(offering)
        val outcome = controlPlane.materializeResource("p1", "p1_llm", "gpt-4o")
        assertTrue(outcome is Outcome.Success)
        val resourceId = (outcome as Outcome.Success).value.resourceId

        // Disable
        val disableOutcome = controlPlane.disableResource(resourceId)
        assertTrue(disableOutcome is Outcome.Success)

        val updated = resourceRepo.getResourceById(resourceId)
        assertEquals(com.example.domain.core.resource.ResourceLifecycleState.DISABLED, updated?.lifecycleState)
    }
}
