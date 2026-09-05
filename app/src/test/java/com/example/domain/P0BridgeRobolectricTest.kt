package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.registry.ComponentRegistry
import com.example.application.resource.DurableResourceRegistryService
import com.example.application.resource.RegistryBackedResourceRecordRepository
import com.example.domain.core.Outcome
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.resource.ResourceIdScheme
import com.example.domain.ports.provider.OfferingRepository
import com.example.domain.ports.provider.ProviderRepository
import com.example.domain.ports.provider.ProviderServiceRepository
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.domain.ports.provider.ServiceConfigurationRepository
import com.example.domain.ports.provider.ServiceHealthRepository
import com.example.domain.ports.resource.ResourceRecordRepository
import com.example.domain.ports.provider.UserPreferenceRepository
import com.example.domain.core.provider.offering.OfferingType
import com.example.infrastructure.mcp.McpClient
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
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolOutput
import com.example.infrastructure.validation.defaultResourceValidatorRegistry
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.McpTransportType
import com.example.domain.core.provider.HealthStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ============================================================================
 * P0 Remediation Robolectric Verification (audit c03919d)
 * ============================================================================
 *
 * Verifies the P0 acceptance criteria that need the Android runtime:
 *
 *   F-1/F-3: materialize/validate BRIDGE adapters into the authoritative
 *            RuntimeAdapterResolver — execution can finally resolve them.
 *   S-2:     the credential vault refuses to silently downgrade to a
 *            software key when the Android Keystore is unavailable.
 *   F-8:     the in-process MCP bridge is honest (no canned tools without a
 *            real executor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P0BridgeRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var secureStorage: SecureCredentialStoragePort
    private lateinit var providerRepo: ProviderRepository
    private lateinit var serviceRepo: ProviderServiceRepository
    private lateinit var configRepo: ServiceConfigurationRepository
    private lateinit var healthRepo: ServiceHealthRepository
    private lateinit var offeringRepo: OfferingRepository
    private lateinit var userPrefRepo: UserPreferenceRepository
    private lateinit var resourceRepo: ResourceRecordRepository
    private lateinit var durableRegistry: DurableResourceRegistryService
    private lateinit var registry: ComponentRegistry
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
        userPrefRepo = RoomUserPreferenceRepository(database.userResourcePreferenceDao())

        // Production wiring: the control plane writes through the SAME durable
        // registry the ComponentRegistry/resolver reads from (single authority),
        // and materialized adapters are bridged INTO the resolver (FIX F-1).
        // The durable registry is backed by Room so the restart-survival test
        // mirrors production persistence exactly.
        durableRegistry = DurableResourceRegistryService(
            repository = RoomResourceRecordRepository(database.resourceRecordDao())
        )
        registry = ComponentRegistry(durableRegistry)
        resourceRepo = RegistryBackedResourceRecordRepository(durableRegistry)

        val adapterFactory = ProtocolAdapterFactory(
            geminiBootstrap = com.example.infrastructure.llm.gemini.GeminiBootstrap(context)
        )

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
            validatorRegistry = defaultResourceValidatorRegistry(),
            runtimeAdapterResolver = registry.runtimeAdapterResolver
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * F-1/F-3 END-TO-END: bootstrap seeds the local embedding + search
     * resources, validates them locally (zero network), and — the fix —
     * their adapters become RESOLVABLE through the RuntimeAdapterResolver
     * that ExecutionService actually uses. Previously every resolution
     * failed with AdapterNotFound.
     */
    @Test
    fun `bootstrap bridges local adapters into the runtime resolver (F-1)`() = runBlocking {
        controlPlane.ensureBootstrapDefaults()

        val embeddingId = ResourceIdScheme.forOffering(
            "local", "local_embedding", OfferingType.MODEL, "local-128"
        )
        when (val resolved = registry.runtimeAdapterResolver.resolveEmbeddingAdapter(embeddingId)) {
            is Outcome.Success -> {
                // Real probe: the resolved adapter actually embeds.
                val embeddings = resolved.value.generateEmbeddings(listOf("probe"))
                assertTrue(embeddings is Outcome.Success)
            }
            else -> fail("Embedding adapter must resolve after bootstrap (F-1): $resolved")
        }

        val searchId = ResourceIdScheme.forOffering(
            "multi_source", "multi_source_search", OfferingType.ENDPOINT, "multi_source"
        )
        assertTrue(
            "Search adapter must resolve after bootstrap (F-1)",
            registry.runtimeAdapterResolver.resolveSearchAdapter(searchId) is Outcome.Success
        )
    }

    /**
     * F-1: disabling a resource UNREGISTERS its runtime adapter — no stale
     * adapter remains resolvable after disable.
     */
    @Test
    fun `disabling a resource unregisters its adapter (F-1)`() = runBlocking {
        controlPlane.ensureBootstrapDefaults()
        val searchId = ResourceIdScheme.forOffering(
            "multi_source", "multi_source_search", OfferingType.ENDPOINT, "multi_source"
        )
        assertTrue(registry.runtimeAdapterResolver.resolveSearchAdapter(searchId) is Outcome.Success)

        controlPlane.disableResource(searchId)
        assertTrue(
            "disabled resource must no longer resolve",
            registry.runtimeAdapterResolver.resolveSearchAdapter(searchId) is Outcome.Error
        )
    }

    /**
     * F-1 (restart survival): persisted ENABLED resources get their adapters
     * re-created and re-bridged into a FRESH resolver after a "restart" —
     * mirroring production: Room persistence + eagerLoad + restore.
     */
    @Test
    fun `restore adapters for persisted resources after restart (F-1)`() = runBlocking {
        controlPlane.ensureBootstrapDefaults()
        val embeddingId = ResourceIdScheme.forOffering(
            "local", "local_embedding", OfferingType.MODEL, "local-128"
        )
        assertTrue(registry.runtimeAdapterResolver.resolveEmbeddingAdapter(embeddingId) is Outcome.Success)

        // Simulate process death: a FRESH durable registry + resolver whose
        // memory is reloaded from the SAME Room persistence (eagerLoad).
        val roomRepo = RoomResourceRecordRepository(database.resourceRecordDao())
        val freshDurable = DurableResourceRegistryService(repository = roomRepo)
        freshDurable.eagerLoad()
        val freshRegistry = ComponentRegistry(freshDurable)
        val freshResolver = freshRegistry.runtimeAdapterResolver

        // A new control plane instance (empty in-memory adapters) restores the
        // adapters for every persisted ENABLED resource into the fresh resolver.
        val restoringControlPlane = ProviderControlPlaneService(
            providerRepository = providerRepo,
            serviceRepository = serviceRepo,
            configurationRepository = configRepo,
            healthRepository = healthRepo,
            offeringRepository = offeringRepo,
            resourceRecordRepository = RegistryBackedResourceRecordRepository(freshDurable),
            userPreferenceRepository = userPrefRepo,
            secureCredentialStorage = secureStorage,
            adapterFactory = ProtocolAdapterFactory(
                geminiBootstrap = com.example.infrastructure.llm.gemini.GeminiBootstrap(context)
            ),
            validatorRegistry = defaultResourceValidatorRegistry(),
            runtimeAdapterResolver = freshResolver
        )
        restoringControlPlane.restoreAdaptersForPersistedResources()
        assertTrue(
            "adapter must be restored into the fresh resolver after restart (F-1)",
            freshResolver.resolveEmbeddingAdapter(embeddingId) is Outcome.Success
        )
    }

    /**
     * S-2: with no Android Keystore available (Robolectric), storing a secret
     * must FAIL EXPLICITLY — the old code silently fell back to a software key
     * stored next to the ciphertext (critical vulnerability).
     */
    @Test
    fun `credential vault refuses insecure software-key fallback (S-2)`() = runBlocking {
        val outcome = secureStorage.storeSecret("gemini_api_key", "AIza-test-secret-value")
        assertTrue(
            "storeSecret must fail explicitly when the Keystore is unavailable (S-2), got: $outcome",
            outcome is Outcome.Error
        )
    }

    /**
     * F-8: the in-process MCP bridge only advertises tools it can actually
     * execute — no canned "workspace_summary" without a real executor.
     */
    @Test
    fun `in-process mcp bridge is honest without real executors (F-8)`() = runBlocking {
        val bareClient = McpClient()
        val server = McpServerDescriptor(
            id = "mcp_local_bridge",
            name = "Local Bridge",
            endpointUri = "inprocess://local-bridge",
            transportType = McpTransportType.STDIO,
            health = HealthStatus.HEALTHY,
            isEnabled = true
        )
        val discovered = bareClient.discoverServer(server)
        assertTrue(discovered is Outcome.Success)
        val tools = (discovered as Outcome.Success).value.exposedTools.map { it.name }
        assertTrue("system_diagnostics (real local data) is exposed", tools.contains("system_diagnostics"))
        assertTrue("canned workspace_summary must NOT be exposed without a real executor", !tools.contains("workspace_summary"))

        // Calling the unavailable tool fails explicitly (no canned text).
        val callOutcome = bareClient.callTool(server, "workspace_summary", emptyMap())
        assertTrue("unavailable tool must fail honestly", callOutcome is Outcome.Error)

        // With a REAL executor wired, the tool becomes available and returns
        // the executor's actual output.
        val realClient = McpClient(
            inProcessTools = mapOf(
                "workspace_summary" to { _ ->
                    Outcome.Success(ToolOutput(content = "REAL: 3 files, 120 bytes"))
                }
            )
        )
        val discoveredReal = realClient.discoverServer(server)
        assertTrue(discoveredReal is Outcome.Success)
        assertTrue((discoveredReal as Outcome.Success).value.exposedTools.any { it.name == "workspace_summary" })
        val realCall = realClient.callTool(server, "workspace_summary", emptyMap())
        assertTrue(realCall is Outcome.Success)
        assertTrue((realCall as Outcome.Success).value.content.contains("REAL"))
    }
}
