package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.PROVIDER_PRESETS
import com.example.application.usecases.ConnectProviderUseCase
import com.example.domain.core.Outcome
import com.example.domain.core.resource.ResourceLifecycleState
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
import com.example.infrastructure.security.EncryptedSecretStorageAdapter
import com.example.infrastructure.validation.defaultResourceValidatorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * ProvidersViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the PROVIDERS feature ViewModel (ADR-6 slice 5)
 * ============================================================================
 *
 * Drives the REAL services the control room reads — no service-seam
 * stubbing; only a PORT-level fake (the in-memory vault) where the REAL
 * adapter is impossible under Robolectric:
 *
 *  - REAL ProviderControlPlaneService over REAL Room repositories
 *    (in-memory database under Robolectric) with the REAL protocol adapter
 *    factory (GeminiBootstrap) and the REAL validator registry — the four
 *    feature flows, the bootstrap seeding, the lifecycle ops, the service
 *    test and the wizard chain all run against the same production path;
 *  - REAL EgressControl (a PRIVATE instance with no pinned workspace
 *    policy): every outbound validation ping is DENIED fail-closed BEFORE
 *    any socket — deterministic "transport failure" outcomes with no
 *    network dependence and no cross-suite pollution of the shared default;
 *  - REAL EncryptedSecretStorageAdapter for the HONEST-FAILURE contract:
 *    under Robolectric the Android Keystore is unavailable and the adapter
 *    REFUSES to fall back to a software key (S-2) — the credential dialog
 *    must surface that failure instead of pretending the key was stored;
 *  - REAL ConnectProviderUseCase over the same control plane — the wizard
 *    test drives the whole 7-step chain (persistence real, validation
 *    egress-denied), asserting the honest SavedUnverified terminal state.
 *
 * Asserted feature contract (extracted from MainViewModel):
 *  - the four control-plane flows project into the feature state on init,
 *    and the first-run bootstrap seeding (moved from MainViewModel's init)
 *    seeds local + multi-source + Gemini with the Gemini resource honestly
 *    REGISTERED (no key → never fabricated ENABLED);
 *  - the wizard: Rejected without the required key (nothing persisted);
 *    the full chain persists Provider→Service→Config(+key in the
 *    vault)→Offering→Resource and ends SavedUnverified when the real
 *    validation is egress-denied; with the REAL vault under Robolectric it
 *    fails honestly at the vault step (S-2) — the chain stops, nothing is
 *    fabricated;
 *  - the credential dialog: blank secret is a no-op; storing through the
 *    fake vault closes the dialog, keeps the trimmed key retrievable and
 *    immediately re-tests the connection with the STORED key (the honest
 *    no-key message is gone; the ping is egress-denied); storing through
 *    the REAL vault surfaces the S-2 failure and the dialog stays open;
 *  - testServiceConnection / validateResource / enableResource /
 *    discoverOfferings surface their honest outcomes (banner for results,
 *    error channel for failures); enableResource refuses an unvalidated
 *    resource (NOT_VALIDATED); toggle/delete flow through the real cascade;
 *  - the banner + error channel are the feature's own state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProvidersViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: AppDatabase
    /** PRIVATE egress authority: no pinned policy → every ping DENIED pre-socket. */
    private lateinit var egress: EgressControl
    private lateinit var controlPlane: ProviderControlPlaneService
    private lateinit var controlPlaneFakeVault: ProviderControlPlaneService
    private lateinit var viewModel: ProvidersViewModel
    private lateinit var viewModelFakeVault: ProvidersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        egress = EgressControl()
        controlPlane = newPlane(secureVault = EncryptedSecretStorageAdapter(context))
        viewModel = ProvidersViewModel(
            providerControlPlaneService = controlPlane,
            connectProviderUseCase = ConnectProviderUseCase(controlPlane)
        )
        controlPlaneFakeVault = newPlane(secureVault = InMemoryVaultFake())
        viewModelFakeVault = ProvidersViewModel(
            providerControlPlaneService = controlPlaneFakeVault,
            connectProviderUseCase = ConnectProviderUseCase(controlPlaneFakeVault)
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newPlane(secureVault: SecureCredentialStoragePort): ProviderControlPlaneService {
        val geminiBootstrap = GeminiBootstrap(context = ApplicationProvider.getApplicationContext())
        val adapterFactory = ProtocolAdapterFactory(geminiBootstrap = geminiBootstrap)
        val validatorRegistry = defaultResourceValidatorRegistry(
            geminiBootstrap = geminiBootstrap,
            egressControl = egress
        )
        return ProviderControlPlaneService(
            providerRepository = RoomProviderRepository(db.providerDao()),
            serviceRepository = RoomProviderServiceRepository(db.providerServiceDao()),
            configurationRepository = RoomServiceConfigurationRepository(
                db.serviceConfigurationDao(), db.providerServiceDao()
            ),
            healthRepository = RoomServiceHealthRepository(db.serviceHealthRecordDao()),
            offeringRepository = RoomOfferingRepository(db.serviceOfferingDao()),
            resourceRecordRepository = RoomResourceRecordRepository(db.resourceRecordDao()),
            userPreferenceRepository = RoomUserPreferenceRepository(db.userResourcePreferenceDao()),
            secureCredentialStorage = secureVault,
            adapterFactory = adapterFactory,
            validatorRegistry = validatorRegistry,
            egressControl = egress
        )
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the real services
     * hop to Dispatchers.IO internally (Room queries, vault writes, validator
     * pings), so outcomes settle asynchronously even under the Unconfined
     * Main dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 25L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(intervalMs)
        }
    }

    // ------------------------------------------------------------------
    // Init: the control-plane flows + the first-run bootstrap seeding
    // ------------------------------------------------------------------

    @Test
    fun `bootstrap defaults seed the control room state on init`() = runBlocking {
        // The seeding moved from MainViewModel's init to the feature VM —
        // constructing it triggers the same idempotent bootstrap. The wait is
        // on the FULL seeded set (the seeding lands provider-by-provider).
        awaitUntil {
            val ids = viewModel.state.value.generalizedProviders.map { it.id }
            "local" in ids && "multi_source" in ids && "google" in ids
        }

        awaitUntil { viewModel.state.value.materializedResources.size >= 3 }
        val resources = viewModel.state.value.materializedResources
        // The in-process resources validate for real (no network) → ENABLED.
        val local = resources.first { it.serviceId == "local_embedding" }
        assertEquals(ResourceLifecycleState.ENABLED, local.lifecycleState)
        // The Gemini resource is honestly REGISTERED — no key stored, no
        // fabricated ENABLED (Correction #1).
        val gemini = resources.first { it.serviceId == "google_gemini" }
        assertEquals(ResourceLifecycleState.REGISTERED, gemini.lifecycleState)
        assertFalse(gemini.runtimeSupported)
    }

    @Test
    fun `service and configuration flows project into the feature state`() = runBlocking {
        awaitUntil {
            viewModel.state.value.generalizedServices.any { it.id == "google_gemini" }
        }
        awaitUntil {
            viewModel.state.value.generalizedConfigurations.any { it.id == "cfg_google_gemini" }
        }
    }

    // ------------------------------------------------------------------
    // "Connect Provider" wizard — the REAL 7-step chain
    // ------------------------------------------------------------------

    @Test
    fun `wizard full chain persists the chain and ends SavedUnverified when egress denies validation`() = runBlocking {
        awaitUntil {
            viewModelFakeVault.state.value.generalizedProviders.any { it.id == "google" }
        }
        val providersBefore = viewModelFakeVault.state.value.generalizedProviders.size

        viewModelFakeVault.openConnectWizard()
        assertTrue(viewModelFakeVault.state.value.isConnectWizardOpen)
        assertEquals(0, viewModelFakeVault.state.value.wizardStep)

        val groq = PROVIDER_PRESETS.first { it.id == "groq" }
        viewModelFakeVault.connectProviderFullChain(
            preset = groq,
            providerName = "Groq Test",
            endpointUrl = "https://api.groq.com/openai",
            modelName = "llama-3.3-70b-versatile",
            apiKey = "gq_test-key-42"
        )

        awaitUntil(
            timeoutMs = 15_000L
        ) { !viewModelFakeVault.state.value.wizardRunning && viewModelFakeVault.state.value.wizardResult != null }
        val state = viewModelFakeVault.state.value
        // Honest terminal: the real validation was egress-denied → the
        // resource was SAVED but stays unverified (no fabricated success).
        assertFalse(state.wizardResultIsSuccess)
        assertEquals(6, state.wizardStep)
        assertTrue(
            "expected the honest unverified diagnostic, got: ${state.wizardResult}",
            state.wizardResult!!.contains("التحقق فشل")
        )

        // The chain itself persisted through the real Room repositories.
        awaitUntil { viewModelFakeVault.state.value.generalizedProviders.size == providersBefore + 1 }
        val newProvider = viewModelFakeVault.state.value.generalizedProviders.last { it.name == "Groq Test" }
        val newService = viewModelFakeVault.state.value.generalizedServices
            .first { it.providerId == newProvider.id }
        val newConfig = viewModelFakeVault.state.value.generalizedConfigurations
            .first { it.serviceId == newService.id }
        val newResource = viewModelFakeVault.state.value.materializedResources
            .first { it.serviceId == newService.id }

        // The key is in the (fake) vault under the config's alias — a real
        // storeSecret roundtrip through the use case's step 3.
        assertEquals("gq_test-key-42", controlPlaneFakeVault.getSecret(newConfig.authAlias!!))

        // Egress-denied validation → the record stays REGISTERED, never
        // fabricated ENABLED.
        assertEquals(ResourceLifecycleState.REGISTERED, newResource.lifecycleState)
    }

    @Test
    fun `wizard without the required key is rejected before the chain starts`() = runBlocking {
        awaitUntil {
            viewModelFakeVault.state.value.generalizedProviders.any { it.id == "google" }
        }
        val providersBefore = viewModelFakeVault.state.value.generalizedProviders.size

        viewModelFakeVault.openConnectWizard()
        val gemini = PROVIDER_PRESETS.first { it.id == "gemini" }
        viewModelFakeVault.connectProviderFullChain(
            preset = gemini,
            providerName = "No Key",
            endpointUrl = "https://generativelanguage.googleapis.com",
            modelName = "gemini-2.5-flash",
            apiKey = null
        )

        awaitUntil { viewModelFakeVault.state.value.wizardResult != null }
        val state = viewModelFakeVault.state.value
        assertFalse(state.wizardResultIsSuccess)
        assertTrue(
            "expected the guard rejection diagnostic, got: ${state.wizardResult}",
            state.wizardResult!!.contains("يتطلب مفتاح API")
        )
        // Nothing was persisted — the guard fired before step 1.
        assertEquals(providersBefore, viewModelFakeVault.state.value.generalizedProviders.size)
    }

    @Test
    fun `wizard fails honestly at the vault step when the device keystore is unavailable`() = runBlocking {
        awaitUntil {
            viewModel.state.value.generalizedProviders.any { it.id == "google" }
        }
        val providersBefore = viewModel.state.value.generalizedProviders.size

        val gemini = PROVIDER_PRESETS.first { it.id == "gemini" }
        viewModel.connectProviderFullChain(
            preset = gemini,
            providerName = "Real Vault",
            endpointUrl = "https://generativelanguage.googleapis.com",
            modelName = "gemini-2.5-flash",
            apiKey = "real-vault-key"
        )

        awaitUntil { viewModel.state.value.wizardResult != null }
        val state = viewModel.state.value
        // S-2: the REAL adapter refuses the insecure software-key fallback
        // under Robolectric — the chain stops at the vault step and says so.
        assertFalse(state.wizardResultIsSuccess)
        assertTrue(
            "expected the honest vault-failure diagnostic, got: ${state.wizardResult}",
            state.wizardResult!!.contains("القبو المشفّر")
        )
        // Steps 1–2 persisted (provider + service), but the chain stopped
        // before any resource existed.
        assertEquals(providersBefore + 1, viewModel.state.value.generalizedProviders.size)
        val newService = viewModel.state.value.generalizedServices
            .first { it.providerId == viewModel.state.value.generalizedProviders.last { it.name == "Real Vault" }.id }
        assertTrue(
            viewModel.state.value.materializedResources.none { it.serviceId == newService.id }
        )
    }

    @Test
    fun `open and close wizard reset the wizard state`() = runBlocking {
        viewModel.openConnectWizard()
        assertEquals(0, viewModel.state.value.wizardStep)
        assertNull(viewModel.state.value.wizardStepLabel)
        assertNull(viewModel.state.value.wizardResult)

        viewModel.closeConnectWizard()
        assertFalse(viewModel.state.value.isConnectWizardOpen)
        assertEquals(0, viewModel.state.value.wizardStep)
        assertNull(viewModel.state.value.wizardResult)
        assertTrue(viewModel.state.value.wizardResultIsSuccess)
    }

    // ------------------------------------------------------------------
    // Service connection test + discovery — honest outcomes
    // ------------------------------------------------------------------

    @Test
    fun `testServiceConnection without a stored key reports the honest no-key failure`() = runBlocking {
        awaitUntil {
            viewModel.state.value.generalizedConfigurations.any { it.id == "cfg_google_gemini" }
        }

        viewModel.testServiceConnection("cfg_google_gemini")

        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        val banner = viewModel.state.value.diagnosticBanner!!
        assertTrue(
            "expected the honest no-key diagnostic, got: $banner",
            banner.contains("لا يوجد مفتاح Gemini")
        )
        // The in-flight flag resets honestly after the probe (the id stays
        // for the tested-configuration display — moved-verbatim behavior).
        awaitUntil { !viewModel.state.value.isTestingProvider }
    }

    @Test
    fun `testServiceConnection surfaces the error channel for an unknown configuration`() = runBlocking {
        viewModel.testServiceConnection("cfg_does_not_exist")

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(
            viewModel.state.value.errorMessage!!.contains("cfg_does_not_exist")
        )
        awaitUntil { !viewModel.state.value.isTestingProvider }
    }

    @Test
    fun `discoverOfferings without a configuration surfaces the honest error`() = runBlocking {
        // Seed a provider + service with NO configuration through the real
        // plane, then ask the feature to discover.
        controlPlane.createProvider(
            com.example.domain.core.provider.Provider(
                id = "p_nocfg", name = "NoCfg", isEnabled = true
            )
        )
        controlPlane.addService(
            com.example.domain.core.provider.ProviderService(
                id = "p_nocfg_llm",
                providerId = "p_nocfg",
                name = "LLM",
                serviceType = com.example.domain.core.provider.ServiceType.LLM,
                supportedProtocolIds = listOf(
                    com.example.domain.core.provider.ServiceProtocolId.OPENAI_COMPATIBLE.code
                ),
                isEnabled = true
            )
        )

        viewModel.discoverOfferings("p_nocfg_llm")

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(
            "expected the NO_CONFIG diagnostic, got: ${viewModel.state.value.errorMessage}",
            viewModel.state.value.errorMessage!!.contains("No configuration")
        )
        awaitUntil { !viewModel.state.value.isDiscoveringModels }
    }

    @Test
    fun `discoverOfferings with a real configuration reports the egress-denied discovery honestly`() = runBlocking {
        awaitUntil {
            viewModel.state.value.generalizedConfigurations.any { it.id == "cfg_google_gemini" }
        }

        // The private egress authority denies the discovery dial BEFORE any
        // socket — the honest error channel carries the real diagnostic.
        viewModel.discoverOfferings("google_gemini")

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(
            "expected a real discovery diagnostic, got: ${viewModel.state.value.errorMessage}",
            viewModel.state.value.errorMessage!!.isNotBlank()
        )
        awaitUntil { !viewModel.state.value.isDiscoveringModels }
    }

    // ------------------------------------------------------------------
    // Credential dialog (F-4) — store, re-test, promote
    // ------------------------------------------------------------------

    @Test
    fun `submitCredential stores the trimmed key, closes the dialog and re-tests with the stored key`() = runBlocking {
        awaitUntil {
            viewModelFakeVault.state.value.materializedResources.any { it.serviceId == "google_gemini" }
        }

        viewModelFakeVault.openCredentialDialog(
            serviceId = "google_gemini",
            serviceName = "خدمة Gemini للتوليد",
            authAlias = "gemini_api_key"
        )
        assertEquals("google_gemini", viewModelFakeVault.state.value.credentialDialogServiceId)
        assertEquals("", viewModelFakeVault.state.value.credentialInput)

        viewModelFakeVault.updateCredentialInput("  real-key-42  ")
        viewModelFakeVault.submitCredential()

        // The trimmed key is retrievable from the (fake) vault — a real
        // storeSecret roundtrip under the config's authAlias.
        awaitUntil {
            viewModelFakeVault.state.value.credentialDialogServiceId == null
        }
        assertEquals("real-key-42", controlPlaneFakeVault.getSecret("gemini_api_key"))
        awaitUntil { !viewModelFakeVault.state.value.isSavingCredential }

        // The immediate re-test used the STORED key: the honest no-key
        // message is gone, and the real ping was denied by the (fresh)
        // egress authority before any socket — deterministic. Wait for the
        // TERMINAL diagnostic (the transient save banner appears first).
        awaitUntil(
            timeoutMs = 15_000L
        ) {
            viewModelFakeVault.state.value.diagnosticBanner
                ?.contains("EGRESS_BLOCKED") == true
        }
        // The gemini record stays honestly REGISTERED (validation denied).
        val gemini = viewModelFakeVault.state.value.materializedResources
            .first { it.serviceId == "google_gemini" }
        assertEquals(ResourceLifecycleState.REGISTERED, gemini.lifecycleState)
    }

    @Test
    fun `submitCredential surfaces the honest vault failure and keeps the dialog open`() = runBlocking {
        awaitUntil {
            viewModel.state.value.generalizedProviders.any { it.id == "google" }
        }

        viewModel.openCredentialDialog(
            serviceId = "google_gemini",
            serviceName = "خدمة Gemini للتوليد",
            authAlias = "gemini_api_key"
        )
        viewModel.updateCredentialInput("a-real-key")
        viewModel.submitCredential()

        // S-2: the REAL adapter refuses the insecure software-key fallback
        // (no Android Keystore under Robolectric) — the user sees the
        // explicit failure, the dialog STAYS open, the flag resets.
        awaitUntil { viewModel.state.value.errorMessage != null }
        // The S-2 CONTRACT under Robolectric (keystore absent): the store
        // fails EXPLICITLY with a non-empty honest diagnostic — the slice-5
        // display-honesty repair routes it into diagnosticMessage so it
        // actually reaches the snackbar (previously the message landed in
        // the failure slot and the user saw an EMPTY error). Which catch
        // fires depends on how the keystore absence surfaces (SecurityException
        // or a generic KeyStoreException) — both are the honest refusal.
        val vaultError = viewModel.state.value.errorMessage!!
        assertTrue(
            "expected the honest vault-failure diagnostic, got: $vaultError",
            vaultError.contains("فشل")
        )
        // No insecure software-key fallback: nothing was stored at all.
        assertNull(controlPlane.getSecret("gemini_api_key"))
        assertEquals("google_gemini", viewModel.state.value.credentialDialogServiceId)
        awaitUntil { !viewModel.state.value.isSavingCredential }
    }

    @Test
    fun `submitCredential with a blank secret is a no-op`() = runBlocking {
        viewModelFakeVault.openCredentialDialog(
            serviceId = "google_gemini",
            serviceName = "خدمة Gemini للتوليد",
            authAlias = "gemini_api_key"
        )
        viewModelFakeVault.updateCredentialInput("    ")
        viewModelFakeVault.submitCredential()

        // Nothing stored, dialog still open (synchronous early-return guard).
        assertNull(controlPlaneFakeVault.getSecret("gemini_api_key"))
        assertEquals("google_gemini", viewModelFakeVault.state.value.credentialDialogServiceId)
        assertFalse(viewModelFakeVault.state.value.isSavingCredential)
    }

    @Test
    fun `credential dialog open, update and close lifecycle`() = runBlocking {
        viewModel.openCredentialDialog(
            serviceId = "svc",
            serviceName = "Service",
            authAlias = "alias"
        )
        assertEquals("svc", viewModel.state.value.credentialDialogServiceId)
        assertEquals("Service", viewModel.state.value.credentialDialogServiceName)
        assertEquals("alias", viewModel.state.value.credentialDialogAuthAlias)

        viewModel.updateCredentialInput("typed")
        assertEquals("typed", viewModel.state.value.credentialInput)

        viewModel.closeCredentialDialog()
        assertNull(viewModel.state.value.credentialDialogServiceId)
        assertEquals("", viewModel.state.value.credentialDialogServiceName)
        assertNull(viewModel.state.value.credentialDialogAuthAlias)
        assertEquals("", viewModel.state.value.credentialInput)
        assertFalse(viewModel.state.value.isSavingCredential)
    }

    // ------------------------------------------------------------------
    // Resource lifecycle + provider management (real cascade)
    // ------------------------------------------------------------------

    @Test
    fun `toggleProvider flips the enabled flag through the real plane`() = runBlocking {
        awaitUntil {
            viewModel.state.value.generalizedProviders.any { it.id == "google" }
        }

        viewModel.toggleProvider("google", false)

        awaitUntil {
            viewModel.state.value.generalizedProviders
                .firstOrNull { it.id == "google" }?.isEnabled == false
        }
        viewModel.toggleProvider("google", true)
        awaitUntil {
            viewModel.state.value.generalizedProviders
                .firstOrNull { it.id == "google" }?.isEnabled == true
        }
    }

    @Test
    fun `deleteProvider cascades the services and resources away`() = runBlocking {
        awaitUntil {
            viewModel.state.value.materializedResources.any { it.serviceId == "google_gemini" }
        }

        viewModel.deleteProvider("google")

        awaitUntil {
            viewModel.state.value.generalizedProviders.none { it.id == "google" }
        }
        awaitUntil {
            viewModel.state.value.materializedResources.none { it.serviceId == "google_gemini" }
        }
    }

    @Test
    fun `enableResource refuses an unvalidated resource honestly`() = runBlocking {
        awaitUntil {
            viewModel.state.value.materializedResources.any { it.serviceId == "google_gemini" }
        }
        val gemini = viewModel.state.value.materializedResources
            .first { it.serviceId == "google_gemini" }
        assertFalse(gemini.runtimeSupported)

        viewModel.enableResource(gemini.resourceId.value)

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(
            "expected the NOT_VALIDATED diagnostic, got: ${viewModel.state.value.errorMessage}",
            viewModel.state.value.errorMessage!!.contains("must be validated")
        )
    }

    @Test
    fun `disableResource flips an enabled resource to DISABLED`() = runBlocking {
        awaitUntil {
            viewModel.state.value.materializedResources.any {
                it.serviceId == "local_embedding" && it.lifecycleState == ResourceLifecycleState.ENABLED
            }
        }
        val local = viewModel.state.value.materializedResources
            .first { it.serviceId == "local_embedding" }
        assertEquals(ResourceLifecycleState.ENABLED, local.lifecycleState)

        viewModel.disableResource(local.resourceId.value)

        awaitUntil {
            viewModel.state.value.materializedResources
                .first { it.resourceId == local.resourceId }
                .lifecycleState == ResourceLifecycleState.DISABLED
        }
    }

    @Test
    fun `validateResource on an unknown id surfaces the error channel`() = runBlocking {
        viewModel.validateResource("res_does_not_exist")

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(viewModel.state.value.errorMessage!!.contains("not found"))
    }

    // ------------------------------------------------------------------
    // The feature's own channels
    // ------------------------------------------------------------------

    @Test
    fun `banner and error channel are dismissible feature state`() = runBlocking {
        viewModel.validateResource("res_does_not_exist")
        awaitUntil { viewModel.state.value.errorMessage != null }

        viewModel.clearErrorMessage()
        assertNull(viewModel.state.value.errorMessage)

        awaitUntil {
            viewModel.state.value.generalizedConfigurations.any { it.id == "cfg_google_gemini" }
        }
        viewModel.testServiceConnection("cfg_google_gemini")
        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
    }

    // ------------------------------------------------------------------
    // Port-level fake (the REAL adapter is impossible under Robolectric —
    // no Android Keystore; the fake stands in for the PORT only, every
    // service above it stays real).
    // ------------------------------------------------------------------

    private class InMemoryVaultFake : SecureCredentialStoragePort {
        private val secrets = mutableMapOf<String, String>()
        override suspend fun storeSecret(alias: String, secret: String): Outcome<Unit, String> {
            secrets[alias] = secret
            return Outcome.Success(Unit)
        }

        override suspend fun getSecret(alias: String): Outcome<String?, String> =
            Outcome.Success(secrets[alias])

        override suspend fun deleteSecret(alias: String): Outcome<Unit, String> {
            secrets.remove(alias)
            return Outcome.Success(Unit)
        }

        override fun hasSecret(alias: String): Boolean = alias in secrets
    }
}
