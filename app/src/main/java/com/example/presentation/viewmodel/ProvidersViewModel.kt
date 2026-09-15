package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.ProviderPreset
import com.example.application.usecases.ConnectProviderUseCase
import com.example.domain.core.Outcome
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ProvidersViewModel — ADR-6 slice 5 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The provider & resource control room (the biggest remaining screen, 1231
 * lines) left the MainViewModel per ADR-6 option-ج, with its whole dependency
 * set: providerControlPlaneService + connectProviderUseCase.
 *
 * STATE: the 19 provider UiState fields (generalizedProviders /
 * generalizedServices / generalizedConfigurations / discoveredOfferings /
 * materializedResources / isDiscoveringModels / isTestingProvider /
 * testingProviderId / the 6 wizard fields / the 5 credential-dialog fields)
 * moved to this feature-owned [ProvidersUiState] with its own error + banner
 * channels.
 *
 * BEHAVIOR (moved verbatim): the four control-plane flow collectors, the
 * first-run bootstrap seeding (launchBootstrapDefaults — local embedding +
 * multi-source search + Gemini records, idempotent, no network), the service
 * connection test, offerings discovery, resource materialize/validate/
 * enable/disable, provider delete/toggle, the FULL "Connect Provider" wizard
 * (the guided 7-step chain through the REAL ConnectProviderUseCase), and the
 * credential input dialog (F-4: store the key in the real encrypted vault,
 * then immediately re-test the connection and promote the service's
 * materialized resources — entering a key ACTIVATES the provider).
 */
class ProvidersViewModel(
    private val providerControlPlaneService: ProviderControlPlaneService,
    private val connectProviderUseCase: ConnectProviderUseCase? = null
) : ViewModel() {

    data class ProvidersUiState(
        // Phase 4 — Generalized Provider Architecture (feature-owned since
        // ADR-6 slice 5; the flows are the control plane's Room-backed
        // backend truth).
        val generalizedProviders: List<Provider> = emptyList(),
        val generalizedServices: List<ProviderService> = emptyList(),
        val generalizedConfigurations: List<ServiceConfiguration> = emptyList(),
        val discoveredOfferings: List<ServiceOffering> = emptyList(),
        val materializedResources: List<ResourceRecord> = emptyList(),
        val isDiscoveringModels: Boolean = false,
        val isTestingProvider: Boolean = false,
        val testingProviderId: String? = null,

        // "Connect Provider" wizard — the guided full-chain path that ends
        // with a usable ENABLED resource (fix: user could add a provider but
        // never use it).
        val isConnectWizardOpen: Boolean = false,
        val wizardRunning: Boolean = false,
        val wizardStep: Int = 0,
        val wizardStepLabel: String? = null,
        val wizardResult: String? = null,
        val wizardResultIsSuccess: Boolean = true,

        // FIX F-4 (audit c03919d): credential input dialog state — a real
        // user path to store an API key for a service configuration.
        val credentialDialogServiceId: String? = null,
        val credentialDialogServiceName: String = "",
        val credentialDialogAuthAlias: String? = null,
        val credentialInput: String = "",
        val isSavingCredential: Boolean = false,

        /** The feature's own transient diagnostic channel (global banner). */
        val diagnosticBanner: String? = null,
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    init {
        // The control-plane live projections: providers / services /
        // configurations / materialized resources flow straight from Room.
        viewModelScope.launch {
            providerControlPlaneService.allProvidersFlow.collect { providers ->
                _state.update { it.copy(generalizedProviders = providers) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allServicesFlow.collect { services ->
                _state.update { it.copy(generalizedServices = services) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allConfigurationsFlow.collect { configs ->
                _state.update { it.copy(generalizedConfigurations = configs) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allResourcesFlow.collect { resources ->
                _state.update { it.copy(materializedResources = resources) }
            }
        }
        // Phase 4 — first-run bootstrap: seeds local embedding + multi-source
        // search + Gemini provider records (idempotent, no network for
        // in-process). Moved from MainViewModel's init with the feature.
        providerControlPlaneService.launchBootstrapDefaults()
    }

    /**
     * Test the connection for a ServiceConfiguration. Real protocol probe
     * via the resource validators — for LLM services this is a lightweight
     * GET /models reachability + authentication check (NOT a generation
     * call; POST /chat/completions is never issued by validation).
     * GAP-25 (Design Closure 2026): the previous KDoc claimed a POST
     * /chat/completions probe, which never matched the implementation.
     */
    fun testServiceConnection(configId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isTestingProvider = true, testingProviderId = configId) }
            try {
                when (val outcome = providerControlPlaneService.testServiceConnection(configId)) {
                    is Outcome.Success -> {
                        _state.update {
                            it.copy(
                                isTestingProvider = false,
                                diagnosticBanner = outcome.value.message
                            )
                        }
                    }
                    is Outcome.Error -> {
                        _state.update {
                            it.copy(
                                isTestingProvider = false,
                                errorMessage = outcome.diagnosticMessage
                            )
                        }
                    }
                    else -> _state.update { it.copy(isTestingProvider = false) }
                }
            } finally {
                _state.update { it.copy(isTestingProvider = false) }
            }
        }
    }

    /**
     * Discover offerings for a service. Explicit network discovery — produces
     * `ServiceOffering`s but does NOT materialize ResourceRecords.
     */
    fun discoverOfferings(serviceId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isDiscoveringModels = true) }
            try {
                when (val outcome = providerControlPlaneService.discoverOfferings(serviceId)) {
                    is Outcome.Success -> {
                        _state.update {
                            it.copy(discoveredOfferings = outcome.value)
                        }
                    }
                    is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                    else -> Unit
                }
            } finally {
                _state.update { it.copy(isDiscoveringModels = false) }
            }
        }
    }

    /**
     * Materialize a ServiceOffering into a ResourceRecord. The record starts
     * at REGISTERED/runtimeSupported=false/UNKNOWN. The user must call
     * `validateResource(resourceId)` to promote it to ENABLED/true/HEALTHY.
     */
    fun materializeResource(providerId: String, serviceId: String, offeringId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.materializeResource(providerId, serviceId, offeringId)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Validate a materialized ResourceRecord. Runs the appropriate
     * ResourceValidator and updates lifecycle/runtimeSupported/health.
     */
    fun validateResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.validateResource(ResourceId(resourceId))) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(diagnosticBanner = outcome.value.message)
                    }
                }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Enable a previously-validated resource.
     */
    fun enableResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.enableResource(ResourceId(resourceId))) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Disable a materialized resource (lifecycle → DISABLED).
     */
    fun disableResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.disableResource(ResourceId(resourceId))) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.deleteProvider(id)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun toggleProvider(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.toggleProvider(id, isEnabled)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------
    // "Connect Provider" wizard — the FULL CHAIN in one guided action.
    // Fix (user feedback: adding a provider left it unusable): previously the
    // add-dialog persisted a bare Provider row with no Service/Config/Offering,
    // so no resource could ever be materialized from it. The wizard walks:
    //
    //   1 Provider → 2 Service → 3 Configuration + vault key →
    //   4 Offering → 5 Materialize → 6 Validate → (auto-ENABLED on success)
    //
    // Every step reports honest progress; a failure stops the chain and
    // surfaces the real diagnostic (no fabricated success).
    // ------------------------------------------------------------------

    fun openConnectWizard() {
        _state.update {
            it.copy(
                isConnectWizardOpen = true,
                wizardRunning = false,
                wizardStep = 0,
                wizardStepLabel = null,
                wizardResult = null
            )
        }
    }

    fun closeConnectWizard() {
        if (_state.value.wizardRunning) return // no canceling mid-chain from the dialog
        _state.update {
            it.copy(
                isConnectWizardOpen = false,
                wizardStep = 0,
                wizardStepLabel = null,
                wizardResult = null,
                wizardResultIsSuccess = true
            )
        }
    }

    fun connectProviderFullChain(
        preset: ProviderPreset,
        providerName: String,
        endpointUrl: String,
        modelName: String,
        apiKey: String?
    ) {
        // GAP-19 (ADR-6 step 2): the 7-step connection chain (id scheme,
        // domain construction, capability map, materialize+validate) lives
        // in ConnectProviderUseCase; the VM projects progress + result into
        // the wizard UiState only.
        val useCase = connectProviderUseCase ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(wizardRunning = true, wizardStep = 1, wizardStepLabel = "إنشاء المزوّد…", wizardResult = null)
            }
            when (
                val result = useCase(
                    preset = preset,
                    providerName = providerName,
                    endpointUrl = endpointUrl,
                    modelName = modelName,
                    apiKey = apiKey,
                    onStep = { step, label ->
                        _state.update { it.copy(wizardStep = step, wizardStepLabel = label) }
                    }
                )
            ) {
                is ConnectProviderUseCase.Result.Rejected -> failWizard(result.message)
                is ConnectProviderUseCase.Result.Failed -> failWizard(result.message)
                is ConnectProviderUseCase.Result.SavedUnverified -> _state.update {
                    it.copy(
                        wizardRunning = false,
                        wizardStep = 6,
                        wizardStepLabel = null,
                        wizardResult = result.message,
                        wizardResultIsSuccess = false
                    )
                }
                is ConnectProviderUseCase.Result.Connected -> _state.update {
                    it.copy(
                        wizardRunning = false,
                        wizardStep = 7,
                        wizardStepLabel = null,
                        wizardResult = result.message,
                        wizardResultIsSuccess = true,
                        diagnosticBanner = "تم تفعيل ${preset.displayName} بنجاح"
                    )
                }
            }
        }
    }

    private fun failWizard(message: String) {
        _state.update {
            it.copy(
                wizardRunning = false,
                wizardStepLabel = null,
                wizardResult = message,
                wizardResultIsSuccess = false
            )
        }
    }

    // ------------------------------------------------------------------
    // FIX F-4 (audit c03919d): credential input dialog — a real user path to
    // store an API key for a service configuration. Previously there was NO
    // way to enter a key (the flag existed but nothing read it), so every
    // remote provider stayed unusable.
    // ------------------------------------------------------------------

    fun openCredentialDialog(serviceId: String, serviceName: String, authAlias: String?) {
        _state.update {
            it.copy(
                credentialDialogServiceId = serviceId,
                credentialDialogServiceName = serviceName,
                credentialDialogAuthAlias = authAlias,
                credentialInput = ""
            )
        }
    }

    fun updateCredentialInput(value: String) {
        _state.update { it.copy(credentialInput = value) }
    }

    fun closeCredentialDialog() {
        _state.update {
            it.copy(
                credentialDialogServiceId = null,
                credentialDialogServiceName = "",
                credentialDialogAuthAlias = null,
                credentialInput = "",
                isSavingCredential = false
            )
        }
    }

    /**
     * Stores the entered secret under the service's authAlias (or the service
     * id as the storage key) and immediately runs a real connection test so
     * the user gets honest feedback that the key works.
     */
    fun submitCredential() {
        val state = _state.value
        val serviceId = state.credentialDialogServiceId ?: return
        val authAlias = state.credentialDialogAuthAlias ?: serviceId
        val secret = state.credentialInput.trim()
        if (secret.isEmpty()) return

        _state.update { it.copy(isSavingCredential = true) }
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.storeSecret(authAlias, secret)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            isSavingCredential = false,
                            diagnosticBanner = "تم حفظ المفتاح بنجاح. جاري التحقق من الاتصال..."
                        )
                    }
                    closeCredentialDialog()
                    // Explicit validation right after storing the key —
                    // honest feedback instead of silent "saved".
                    val config = providerControlPlaneService.getCurrentConfigurationForService(serviceId)
                    if (config != null) {
                        testServiceConnection(config.id)
                    }
                    // FIX (usable-provider flow): also promote the materialized
                    // resource(s) of this service — validateResource runs the real
                    // protocol check and flips the record to ENABLED/HEALTHY, so
                    // entering the key on the seeded Gemini provider ACTIVATES it
                    // for the Studio instead of leaving it at REGISTERED.
                    val resourcesForService = _state.value.materializedResources
                        .filter { it.serviceId == serviceId }
                    for (resource in resourcesForService) {
                        validateResource(resource.resourceId.value)
                    }
                }
                is Outcome.Error -> {
                    _state.update {
                        it.copy(isSavingCredential = false, errorMessage = outcome.diagnosticMessage)
                    }
                }
                else -> _state.update { it.copy(isSavingCredential = false) }
            }
        }
    }

    fun clearErrorMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the feature's transient diagnostic banner. */
    fun dismissDiagnosticBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }
}
