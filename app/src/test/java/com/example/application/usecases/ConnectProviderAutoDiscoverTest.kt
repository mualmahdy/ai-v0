package com.example.application.usecases

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.PROVIDER_PRESETS
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.infrastructure.llm.gemini.GeminiBootstrap
import com.example.infrastructure.network.EgressControl
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.repository.RoomOfferingRepository
import com.example.infrastructure.persistence.repository.RoomProviderRepository
import com.example.infrastructure.persistence.repository.RoomProviderServiceRepository
import com.example.infrastructure.persistence.repository.RoomResourceRecordRepository
import com.example.infrastructure.persistence.repository.RoomServiceConfigurationRepository
import com.example.infrastructure.persistence.repository.RoomServiceHealthRepository
import com.example.infrastructure.persistence.repository.RoomUserPreferenceRepository
import com.example.infrastructure.provider.ProtocolAdapterFactory
import com.example.infrastructure.validation.defaultResourceValidatorRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * ConnectProviderAutoDiscoverTest — STAGE 7 (the optional-model contract)
 * ============================================================================
 *
 * The wizard's STAGE-7 semantics, tested against a REAL local HTTP discovery
 * endpoint (the same GET /models path production uses) and the REAL control
 * plane over Room — no service-seam stubbing:
 *
 *  1. a BLANK model name WITHOUT auto-discovery is Rejected before anything
 *     persists (the honest guard — the old UI just blocked the button);
 *  2. auto-discovery with a blank name discovers through the SAVED
 *     configuration, registers the real models, picks the preset's curated
 *     default when the endpoint exposes it, pins it as the configuration's
 *     default offering, and materializes a resource on it;
 *  3. an endpoint exposing NOTHING fails honestly at step 4 with the
 *     suggest-manual-entry diagnostic (never a fabricated empty success);
 *  4. probeOfferings (the wizard's pre-creation "show me everything") hits
 *     the SAME discovery path with TRANSIENT instances and persists NOTHING.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectProviderAutoDiscoverTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var egress: EgressControl
    private lateinit var controlPlane: ProviderControlPlaneService
    private lateinit var useCase: ConnectProviderUseCase
    private var server: HttpServer? = null
    private var serverPort: Int = 0

    /** The fake vault (the honest key roundtrip the chain needs). */
    private class InMemoryVaultFake : SecureCredentialStoragePort {
        private val secrets = mutableMapOf<String, String>()
        override suspend fun storeSecret(alias: String, secret: String): com.example.domain.core.Outcome<Unit, String> {
            secrets[alias] = secret
            return com.example.domain.core.Outcome.Success(Unit)
        }

        override suspend fun getSecret(alias: String): com.example.domain.core.Outcome<String?, String> =
            com.example.domain.core.Outcome.Success(secrets[alias])

        override suspend fun deleteSecret(alias: String): com.example.domain.core.Outcome<Unit, String> {
            secrets.remove(alias)
            return com.example.domain.core.Outcome.Success(Unit)
        }

        override fun hasSecret(alias: String): Boolean = secrets.containsKey(alias)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // A permissive PRIVATE egress authority pinned to a test workspace:
        // discovery/validation may dial the LOCAL test server only.
        egress = EgressControl()
        egress.pinWorkspacePolicy("ws_test", com.example.domain.core.network.NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_test")

        val geminiBootstrap = GeminiBootstrap(context = context)
        controlPlane = ProviderControlPlaneService(
            providerRepository = RoomProviderRepository(db.providerDao()),
            serviceRepository = RoomProviderServiceRepository(db.providerServiceDao()),
            configurationRepository = RoomServiceConfigurationRepository(
                db.serviceConfigurationDao(), db.providerServiceDao()
            ),
            healthRepository = RoomServiceHealthRepository(db.serviceHealthRecordDao()),
            offeringRepository = RoomOfferingRepository(db.serviceOfferingDao()),
            resourceRecordRepository = RoomResourceRecordRepository(db.resourceRecordDao()),
            userPreferenceRepository = RoomUserPreferenceRepository(db.userResourcePreferenceDao()),
            secureCredentialStorage = InMemoryVaultFake(),
            adapterFactory = ProtocolAdapterFactory(geminiBootstrap = geminiBootstrap),
            validatorRegistry = defaultResourceValidatorRegistry(
                geminiBootstrap = geminiBootstrap,
                egressControl = egress
            ),
            egressControl = egress
        )
        useCase = ConnectProviderUseCase(controlPlane)
    }

    @After
    fun tearDown() {
        server?.stop(0)
        db.close()
        Dispatchers.resetMain()
    }

    /** Boots a local OpenAI-compatible discovery endpoint serving [modelsJson]. */
    private fun bootModelsServer(modelsJson: String) {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/v1/models") { exchange ->
            val body = modelsJson.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        serverPort = httpServer.address.port
    }

    private fun localEndpoint(): String = "http://127.0.0.1:$serverPort"

    private val groqPreset get() = PROVIDER_PRESETS.first { it.id == "groq" }

    // ------------------------------------------------------------------
    // 1. The honest guard: blank + no auto-discovery → Rejected, nothing persisted
    // ------------------------------------------------------------------

    @Test
    fun `blank model without auto-discovery is rejected before anything persists`() = runBlocking {
        val result = useCase(
            preset = groqPreset,
            providerName = "No Model",
            endpointUrl = "https://api.groq.com/openai",
            modelName = "   ",
            apiKey = "gq_key",
            autoDiscover = false
        )
        assertTrue(result is ConnectProviderUseCase.Result.Rejected)
        assertTrue(
            "the rejection must tell the user the two real options",
            (result as ConnectProviderUseCase.Result.Rejected)
                .message.contains("الاكتشاف التلقائي")
        )
        // Nothing was created — the guard fired before the chain.
        assertTrue(db.providerDao().getAll().none { it.name == "No Model" })
    }

    // ------------------------------------------------------------------
    // 2. The happy auto-discovery path: real discovery through the saved config
    // ------------------------------------------------------------------

    @Test
    fun `auto-discovery discovers registers and materializes the preset default when exposed`() = runBlocking {
        // The endpoint exposes the preset's curated default + one more model.
        bootModelsServer(
            """{"data":[{"id":"${groqPreset.defaultModel}"},{"id":"other-model-x"}]}"""
        )

        val result = useCase(
            preset = groqPreset,
            providerName = "Auto Discover",
            endpointUrl = localEndpoint(),
            modelName = "",
            apiKey = "gq_auto_key",
            autoDiscover = true
        )

        // The chain ran past discovery (validation against the fake endpoint
        // may honestly fail → SavedUnverified; both are real outcomes).
        when (result) {
            is ConnectProviderUseCase.Result.Connected -> Unit
            is ConnectProviderUseCase.Result.SavedUnverified -> Unit
            else -> error("expected a post-discovery terminal, got: $result")
        }

        // The provider chain persisted.
        val provider = db.providerDao().getAll().first { it.name == "Auto Discover" }
        val service = db.providerServiceDao().getByProviderId(provider.id).first()
        val config = db.serviceConfigurationDao().getLatestByServiceId(service.id)!!

        // The discovered models were REGISTERED as offerings (both of them).
        val offerings = db.serviceOfferingDao().getByServiceId(service.id)
        val offeringNames = offerings.map { it.name }
        assertTrue(
            "both discovered models must be registered, got: $offeringNames",
            groqPreset.defaultModel in offeringNames && "other-model-x" in offeringNames
        )

        // The preset's curated default was CHOSEN (pinned as the config's
        // default offering — not the alphabetical first).
        assertEquals(groqPreset.defaultModel, config.defaultOfferingId)

        // A resource was materialized ON the chosen offering.
        val resource = db.resourceRecordDao().getAll().first { it.serviceId == service.id }
        assertNotNull(resource)
    }

    // ------------------------------------------------------------------
    // 3. The honest empty discovery: suggest manual entry
    // ------------------------------------------------------------------

    @Test
    fun `an endpoint exposing nothing fails honestly suggesting manual entry`() = runBlocking {
        bootModelsServer("""{"data":[]}""")

        val result = useCase(
            preset = groqPreset,
            providerName = "Empty Discovery",
            endpointUrl = localEndpoint(),
            modelName = "",
            apiKey = "gq_key",
            autoDiscover = true
        )
        assertTrue(result is ConnectProviderUseCase.Result.Failed)
        val failed = result as ConnectProviderUseCase.Result.Failed
        assertEquals(4, failed.step)
        assertTrue(
            "the diagnostic must suggest manual entry",
            failed.message.contains("يدوياً")
        )
    }

    // ------------------------------------------------------------------
    // 4. probeOfferings: everything available, NOTHING persisted
    // ------------------------------------------------------------------

    @Test
    fun `probe offerings shows every available model without persisting anything`() = runBlocking {
        bootModelsServer(
            """{"data":[{"id":"alpha"},{"id":"beta"},{"id":"gamma"}]}"""
        )

        val providersBefore = db.providerDao().getAll().size
        val offeringsBefore = db.serviceOfferingDao().getByServiceId("probe_transient").size

        val outcome = controlPlane.probeOfferings(
            serviceType = com.example.domain.core.provider.ServiceType.LLM,
            protocolId = com.example.domain.core.provider.ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = localEndpoint(),
            apiKey = "gq_probe_key"
        )

        assertTrue(outcome is com.example.domain.core.Outcome.Success)
        val models = (outcome as com.example.domain.core.Outcome.Success).value
        assertEquals(setOf("alpha", "beta", "gamma"), models.map { it.name }.toSet())
        // Honest capabilities: only what discovery actually implies.
        models.forEach { model ->
            assertEquals(
                setOf(com.example.domain.core.capability.CapabilityType.LLM_GENERATION),
                model.supportedCapabilities
            )
        }

        // PERSISTENCE-FREE: no provider/service/offering rows appeared.
        assertEquals(providersBefore, db.providerDao().getAll().size)
        assertEquals(
            offeringsBefore,
            db.serviceOfferingDao().getByServiceId("probe_transient").size
        )
    }

    @Test
    fun `probe with a blank endpoint is rejected honestly`() = runBlocking {
        val outcome = controlPlane.probeOfferings(
            serviceType = com.example.domain.core.provider.ServiceType.LLM,
            protocolId = com.example.domain.core.provider.ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = "  ",
            apiKey = null
        )
        assertTrue(outcome is com.example.domain.core.Outcome.Error)
        assertFalse((outcome as com.example.domain.core.Outcome.Error).diagnosticMessage.isNullOrBlank())
    }
}
