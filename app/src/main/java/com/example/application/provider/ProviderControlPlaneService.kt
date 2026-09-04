package com.example.application.provider

import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceHealthRecord
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.ServiceValidationResult
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceIdScheme
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.ResourceValidatorRegistry
import com.example.domain.ports.provider.OfferingRepository
import com.example.domain.ports.provider.ProviderRepository
import com.example.domain.ports.provider.ProviderServiceRepository
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.domain.ports.provider.ServiceConfigurationRepository
import com.example.domain.ports.provider.ServiceHealthRepository
import com.example.domain.ports.resource.ResourceRecordRepository
import com.example.domain.core.provider.preference.UserPreferenceRepository
import com.example.infrastructure.provider.ProtocolAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ProviderControlPlaneService — Authoritative Runtime Path (Phase 4)
 * ============================================================================
 *
 * This class IS the generalized provider control plane. The legacy
 * ProviderConfiguration → ProviderCategory → ProviderFlavor path has been
 * removed. All operations go through:
 *
 *   Provider → ProviderService → ServiceProtocol → ServiceConfiguration
 *           → ProtocolAdapterFactory → Discovery → ServiceOffering
 *           → Materialize → Validate → ResourceRecord → ResourceRegistry
 *
 * Lifecycle operations (all EXPLICIT user actions — no automatic network
 * calls on save per Correction #10):
 *
 *   1. createProvider(provider)
 *   2. addService(service)
 *   3. saveConfiguration(config)             — pure persistence, no network
 *   4. testServiceConnection(configId)       — explicit POST/real protocol op
 *   5. discoverOfferings(serviceId)          — explicit network discovery
 *   6. materializeResource(providerId, serviceId, offeringId) — creates
 *      ResourceRecord at REGISTERED/false/UNKNOWN
 *   7. validateResource(resourceId)         — real validation, promotes to
 *      ENABLED/true/HEALTHY on success
 *   8. enableResource(resourceId) / disableResource(resourceId)
 *
 * Backwards-compatibility note: the legacy `ProviderConfiguration`-typed
 * methods (saveProvider(config, secret), etc.) are NOT exposed. The
 * MainViewModel has been migrated to use the generalized types directly.
 */
class ProviderControlPlaneService(
    private val providerRepository: ProviderRepository,
    private val serviceRepository: ProviderServiceRepository,
    private val configurationRepository: ServiceConfigurationRepository,
    private val healthRepository: ServiceHealthRepository,
    private val offeringRepository: OfferingRepository,
    private val resourceRecordRepository: ResourceRecordRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
    private val secureCredentialStorage: SecureCredentialStoragePort,
    private val adapterFactory: ProtocolAdapterFactory,
    private val validatorRegistry: ResourceValidatorRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** Adapters currently registered in memory, keyed by ResourceId. */
    private val adapters = mutableMapOf<ResourceId, Any>()
    /** McpAdapterPort sessions, keyed by serviceId (one session per MCP service). */
    private val mcpSessions = mutableMapOf<String, com.example.infrastructure.mcp.McpAdapterPort>()

    // ========================================================================
    // Provider / Service / Configuration persistence (no network)
    // ========================================================================

    /** Continuous flow of all providers (used by UI to render the providers list). */
    val allProvidersFlow: Flow<List<Provider>> = providerRepository.observeProviders()

    /** Continuous flow of all services. */
    val allServicesFlow: Flow<List<ProviderService>> = serviceRepository.observeAllServices()

    /** Continuous flow of all configurations. */
    val allConfigurationsFlow: Flow<List<ServiceConfiguration>> = configurationRepository.observeAllConfigurations()

    /** Continuous flow of all materialized resources. */
    val allResourcesFlow: Flow<List<ResourceRecord>> = resourceRecordRepository.observeAllResources()

    suspend fun getProviderById(id: String): Provider? = providerRepository.getProviderById(id)

    suspend fun getServicesForProvider(providerId: String): List<ProviderService> =
        serviceRepository.getServicesForProvider(providerId)

    suspend fun getServiceById(id: String): ProviderService? = serviceRepository.getServiceById(id)

    suspend fun getCurrentConfigurationForService(serviceId: String): ServiceConfiguration? =
        configurationRepository.getCurrentConfigurationForService(serviceId)

    suspend fun getOfferingsForService(serviceId: String): List<ServiceOffering> =
        offeringRepository.findOfferingsForService(serviceId)

    suspend fun listResources(): List<ResourceRecord> = resourceRecordRepository.getAllResources()

    /**
     * Persist a new or updated Provider. Pure persistence — no network calls.
     */
    suspend fun createProvider(provider: Provider): Outcome<Unit, String> =
        providerRepository.saveProvider(provider)

    suspend fun renameProvider(id: String, newName: String): Outcome<Unit, String> {
        val existing = providerRepository.getProviderById(id)
            ?: return Outcome.Error("PROVIDER_NOT_FOUND", "Provider $id not found")
        val updated = existing.copy(name = newName, updatedAtEpochMs = System.currentTimeMillis())
        return providerRepository.saveProvider(updated)
    }

    suspend fun deleteProvider(id: String): Outcome<Unit, String> {
        // Cascade: delete all services, configurations, offerings, resources.
        val services = serviceRepository.getServicesForProvider(id)
        for (svc in services) {
            deleteService(svc.id)
        }
        return providerRepository.deleteProvider(id)
    }

    suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String> =
        providerRepository.toggleProvider(id, isEnabled)

    /**
     * Persist a new ProviderService with explicit ServiceType↔Protocol compatibility check
     * (Correction #5). Saving a service is pure persistence — no network calls.
     */
    suspend fun addService(service: ProviderService): Outcome<Unit, String> {
        for (protoCode in service.supportedProtocolIds) {
            val proto = ServiceProtocolId.fromCode(protoCode)
                ?: return Outcome.Error(
                    "UNKNOWN_PROTOCOL",
                    "Unknown protocol id '$protoCode' in service ${service.id}"
                )
            if (!com.example.domain.core.provider.ServiceProtocolCompatibility
                    .isCompatible(service.serviceType, proto)) {
                return Outcome.Error(
                    "INCOMPATIBLE_PROTOCOL",
                    "Protocol $proto is not compatible with ${service.serviceType} services"
                )
            }
        }
        return serviceRepository.saveService(service)
    }

    suspend fun deleteService(serviceId: String): Outcome<Unit, String> {
        // Cascade: delete configurations, health records, offerings, resources.
        configurationRepository.deleteConfigurationsForService(serviceId)
        offeringRepository.clearForService(serviceId)
        resourceRecordRepository.deleteResourcesForService(serviceId)
        mcpSessions.remove(serviceId)
        return serviceRepository.deleteService(serviceId)
    }

    suspend fun toggleService(id: String, isEnabled: Boolean): Outcome<Unit, String> =
        serviceRepository.toggleService(id, isEnabled)

    /**
     * Save (insert-or-replace) a ServiceConfiguration. Pure persistence — no network
     * calls (Correction #10). The repository bumps `configurationVersion` atomically
     * (Correction #3) and verifies ServiceType↔Protocol compatibility (Correction #5).
     */
    suspend fun saveConfiguration(config: ServiceConfiguration): Outcome<Unit, String> =
        configurationRepository.saveConfiguration(config)

    suspend fun deleteConfiguration(id: String): Outcome<Unit, String> =
        configurationRepository.deleteConfiguration(id)

    suspend fun toggleConfiguration(id: String, isEnabled: Boolean): Outcome<Unit, String> =
        configurationRepository.toggleConfiguration(id, isEnabled)

    /**
     * Store the secret API key for a ServiceConfiguration via the
     * `SecureCredentialStoragePort`. The `authAlias` on the configuration
     * is the storage key.
     */
    suspend fun storeSecret(authAlias: String, secret: String): Outcome<Unit, String> =
        secureCredentialStorage.storeSecret(authAlias, secret)

    suspend fun getSecret(authAlias: String): String? = when (
        val outcome = secureCredentialStorage.getSecret(authAlias)
    ) {
        is Outcome.Success -> outcome.value
        else -> null
    }

    // ========================================================================
    // Test / Discover / Materialize / Validate (explicit user actions)
    // ========================================================================

    /**
     * Test a ServiceConfiguration by running the appropriate ResourceValidator
     * (Correction #4) against the corresponding adapter. Saves the result as a
     * `ServiceHealthRecord` (Correction #2). Does NOT create or update any
     * `ResourceRecord` — the test is at the *service-configuration* level, not
     * the *materialized-resource* level.
     */
    suspend fun testServiceConnection(configId: String): Outcome<ServiceValidationResult, String> {
        val config = configurationRepository.getConfigurationById(configId)
            ?: return Outcome.Error("CONFIG_NOT_FOUND", "Configuration $configId not found")
        val service = serviceRepository.getServiceById(config.serviceId)
            ?: return Outcome.Error("SERVICE_NOT_FOUND", "Service ${config.serviceId} not found")
        val apiKeyProvider = { getSecret(config.authAlias ?: config.id) }
        val adapter = createAdapterFor(service, config, apiKeyProvider)
            ?: return Outcome.Error(
                "ADAPTER_CREATION_FAILED",
                "Failed to create adapter for (${service.serviceType}, ${config.protocolId})"
            )
        val resourceType = ResourceValidatorRegistry.serviceTypeToResourceType(service.serviceType)
        val validator = validatorRegistry.get(resourceType)
            ?: return Outcome.Error("VALIDATOR_NOT_FOUND", "No validator registered for $resourceType")
        val result = validator.validate(service, config.protocolId, config, adapter)

        // Persist as ServiceHealthRecord (Correction #2)
        val healthRecord = ServiceHealthRecord(
            id = "hrec_${config.id}_${System.currentTimeMillis()}",
            serviceConfigurationId = config.id,
            healthStatus = if (result.isSuccess) HealthStatus.HEALTHY
                else if (result.classification == com.example.domain.core.provider.ServiceHealthClassification.TIMEOUT
                    || result.classification == com.example.domain.core.provider.ServiceHealthClassification.RATE_LIMITED
                    || result.classification == com.example.domain.core.provider.ServiceHealthClassification.TRANSPORT_FAILURE)
                    HealthStatus.DEGRADED
                else HealthStatus.UNAVAILABLE,
            lastHealthClassification = result.classification,
            lastValidatedEpochMs = System.currentTimeMillis(),
            lastLatencyMs = result.latencyMs,
            lastErrorMessage = if (result.isSuccess) null else result.message,
            validatedAtEpochMs = System.currentTimeMillis()
        )
        healthRepository.saveHealthRecord(healthRecord)

        return Outcome.Success(result)
    }

    /**
     * Discover offerings for a service. Per Correction #9: this produces
     * `ServiceOffering`s but does NOT create `ResourceRecord`s. The user must
     * separately call `materializeResource(...)` to create a runtime resource.
     *
     * Currently supported discovery:
     *   - LLM + OPENAI_COMPATIBLE/OPENAI_NATIVE/OLLAMA_NATIVE → GET /v1/models → ModelDescriptor[]
     *     → ServiceOffering[] (OfferingType.MODEL)
     *   - LLM + GEMINI_NATIVE → GET /v1beta/models → ModelDescriptor[]
     *     → ServiceOffering[] (OfferingType.MODEL)
     *   - MCP → MCP tools/list → McpDiscoveredTool[] → ServiceOffering[] (OfferingType.TOOL)
     *   - SEARCH / EMBEDDING / others → no discovery; user adds offerings manually
     */
    suspend fun discoverOfferings(serviceId: String): Outcome<List<ServiceOffering>, String> {
        val service = serviceRepository.getServiceById(serviceId)
            ?: return Outcome.Error("SERVICE_NOT_FOUND", "Service $serviceId not found")
        val config = configurationRepository.getCurrentConfigurationForService(serviceId)
            ?: return Outcome.Error("NO_CONFIG", "No configuration for service $serviceId")
        val apiKeyProvider = { getSecret(config.authAlias ?: config.id) }

        return when (service.serviceType) {
            ServiceType.LLM, ServiceType.EMBEDDING -> discoverLlmOrEmbeddingOfferings(service, config, apiKeyProvider)
            ServiceType.MCP -> discoverMcpToolOfferings(service, config)
            else -> Outcome.Success(emptyList())
        }
    }

    private suspend fun discoverLlmOrEmbeddingOfferings(
        service: ProviderService,
        config: ServiceConfiguration,
        apiKeyProvider: () -> String?
    ): Outcome<List<ServiceOffering>, String> {
        // Dynamic discovery via the appropriate discovery adapter.
        // Per Correction #9 / Phase 4: this produces ServiceOfferings but NOT ResourceRecords.
        return try {
            val discovery = com.example.infrastructure.provider.DiscoveryAdapterFactory
                .discover(service, config, apiKeyProvider)
            when (discovery) {
                is Outcome.Success -> {
                    val offerings = discovery.value
                    for (o in offerings) {
                        offeringRepository.registerOffering(o)
                    }
                    Outcome.Success(offerings)
                }
                is Outcome.Degraded -> Outcome.Success(discovery.partialValue ?: emptyList())
                is Outcome.Error -> Outcome.Error(discovery.failure.toString(), discovery.diagnosticMessage)
            }
        } catch (e: Exception) {
            Outcome.Error("DISCOVERY_FAILED", "Discovery failed: ${e.message}")
        }
    }

    private suspend fun discoverMcpToolOfferings(
        service: ProviderService,
        config: ServiceConfiguration
    ): Outcome<List<ServiceOffering>, String> {
        val session = getOrCreateMcpSession(service, config)
        return when (val outcome = session.discoverTools()) {
            is Outcome.Success -> {
                val tools = outcome.value
                val offerings = tools.map { tool ->
                    ServiceOffering(
                        id = tool.name,
                        serviceId = service.id,
                        offeringType = com.example.domain.core.provider.offering.OfferingType.TOOL,
                        name = tool.name,
                        description = tool.description,
                        supportedCapabilities = setOf(
                            com.example.domain.core.capability.CapabilityType.MCP_INVOCATION,
                            com.example.domain.core.capability.CapabilityType.TOOL_EXECUTION
                        ),
                        isLocal = false,
                        isAvailable = true,
                        discoveredEpochMs = System.currentTimeMillis(),
                        discoverySource = "MCP_TOOLS_LIST"
                    )
                }
                for (o in offerings) {
                    offeringRepository.registerOffering(o)
                }
                Outcome.Success(offerings)
            }
            is Outcome.Degraded -> Outcome.Success(outcome.partialValue ?: emptyList())
            is Outcome.Error -> Outcome.Error(outcome.failure.toString(), outcome.diagnosticMessage)
        }
    }

    /**
     * Materialize a ServiceOffering into a ResourceRecord (Correction #9).
     * Creates a ResourceRecord at REGISTERED/runtimeSupported=false/UNKNOWN
     * state. The user must then call `validateResource(resourceId)` to
     * promote it to ENABLED/true/HEALTHY.
     *
     * Per Correction #2/#3 (Phase 3): ResourceId is built from stable immutable IDs
     * (providerId/serviceId/offeringId) via `ResourceIdScheme`.
     */
    suspend fun materializeResource(
        providerId: String,
        serviceId: String,
        offeringId: String
    ): Outcome<ResourceRecord, String> {
        val provider = providerRepository.getProviderById(providerId)
            ?: return Outcome.Error("PROVIDER_NOT_FOUND", "Provider $providerId not found")
        val service = serviceRepository.getServiceById(serviceId)
            ?: return Outcome.Error("SERVICE_NOT_FOUND", "Service $serviceId not found")
        val offering = offeringRepository.getOffering(offeringId)
            ?: return Outcome.Error("OFFERING_NOT_FOUND", "Offering $offeringId not found")
        val config = configurationRepository.getCurrentConfigurationForService(serviceId)
            ?: return Outcome.Error("NO_CONFIG", "No configuration for service $serviceId")

        val resourceType = ResourceValidatorRegistry.serviceTypeToResourceType(service.serviceType)
        val resourceId = ResourceIdScheme.forOffering(
            providerId = provider.id,
            serviceId = service.id,
            offeringType = offering.offeringType,
            offeringId = offering.id
        )

        // If a ResourceRecord already exists for this resourceId, return it as-is.
        // The user can re-validate by calling validateResource(resourceId) again.
        val existing = resourceRecordRepository.getResourceById(resourceId)
        if (existing != null) {
            return Outcome.Success(existing)
        }

        // Create the adapter and register it in memory (so validateResource can use it)
        val apiKeyProvider = { getSecret(config.authAlias ?: config.id) }
        val adapter = createAdapterFor(service, config, apiKeyProvider, offering.id)
            ?: return Outcome.Error(
                "ADAPTER_CREATION_FAILED",
                "Failed to create adapter for (${service.serviceType}, ${config.protocolId})"
            )
        adapters[resourceId] = adapter

        // Build the ResourceRecord at REGISTERED/false/UNKNOWN — never fabricate (Correction #1)
        val record = offering.toResourceRecord(
            resourceId = resourceId,
            providerId = provider.id,
            resourceType = resourceType,
            configurationVersion = config.configurationVersion
        )
        resourceRecordRepository.saveResource(record)
        return Outcome.Success(record)
    }

    /**
     * Validate a materialized ResourceRecord (Correction #9, Correction #4).
     * Runs the appropriate ResourceValidator against the corresponding adapter
     * and updates the record's `runtimeSupported`, `healthStatus`, and
     * `lifecycleState`. On success, the record becomes
     * ENABLED/true/HEALTHY and is usable by DecisionService.
     */
    suspend fun validateResource(resourceId: ResourceId): Outcome<ServiceValidationResult, String> {
        val record = resourceRecordRepository.getResourceById(resourceId)
            ?: return Outcome.Error("RESOURCE_NOT_FOUND", "Resource $resourceId not found")
        val service = serviceRepository.getServiceById(record.serviceId)
            ?: return Outcome.Error("SERVICE_NOT_FOUND", "Service ${record.serviceId} not found")
        val config = configurationRepository.getCurrentConfigurationForService(record.serviceId)
            ?: return Outcome.Error("NO_CONFIG", "No configuration for service ${record.serviceId}")

        val adapter = adapters[resourceId]
            ?: return Outcome.Error("ADAPTER_NOT_FOUND", "No adapter registered for $resourceId")

        val validator = validatorRegistry.get(record.resourceType)
            ?: return Outcome.Error("VALIDATOR_NOT_FOUND", "No validator for ${record.resourceType}")

        val result = validator.validate(service, config.protocolId, config, adapter)

        // Update ResourceRecord per validation result
        val newLifecycle = if (result.isSuccess) ResourceLifecycleState.ENABLED
            else ResourceLifecycleState.REGISTERED
        val newHealth = if (result.isSuccess) HealthStatus.HEALTHY
            else if (result.classification == com.example.domain.core.provider.ServiceHealthClassification.TIMEOUT
                || result.classification == com.example.domain.core.provider.ServiceHealthClassification.RATE_LIMITED
                || result.classification == com.example.domain.core.provider.ServiceHealthClassification.TRANSPORT_FAILURE)
                HealthStatus.DEGRADED
            else HealthStatus.UNAVAILABLE
        val newRuntimeSupported = result.isSuccess

        resourceRecordRepository.updateRuntimeState(
            resourceId = resourceId,
            lifecycleState = newLifecycle,
            runtimeSupported = newRuntimeSupported,
            healthStatus = newHealth
        )

        return Outcome.Success(result)
    }

    /**
     * Disable a materialized resource: set lifecycle to DISABLED. Does not
     * delete the ResourceRecord — it can be re-validated later.
     */
    suspend fun disableResource(resourceId: ResourceId): Outcome<Unit, String> {
        val record = resourceRecordRepository.getResourceById(resourceId)
            ?: return Outcome.Error("RESOURCE_NOT_FOUND", "Resource $resourceId not found")
        resourceRecordRepository.updateRuntimeState(
            resourceId = resourceId,
            lifecycleState = ResourceLifecycleState.DISABLED,
            runtimeSupported = record.runtimeSupported,
            healthStatus = record.healthStatus
        )
        return Outcome.Success(Unit)
    }

    /**
     * Enable a previously-validated (or materialized) resource.
     */
    suspend fun enableResource(resourceId: ResourceId): Outcome<Unit, String> {
        val record = resourceRecordRepository.getResourceById(resourceId)
            ?: return Outcome.Error("RESOURCE_NOT_FOUND", "Resource $resourceId not found")
        // Only allow enable if the resource was previously runtimeSupported
        if (!record.runtimeSupported) {
            return Outcome.Error(
                "NOT_VALIDATED",
                "Resource $resourceId must be validated before enabling"
            )
        }
        resourceRecordRepository.updateRuntimeState(
            resourceId = resourceId,
            lifecycleState = ResourceLifecycleState.ENABLED,
            runtimeSupported = record.runtimeSupported,
            healthStatus = record.healthStatus
        )
        return Outcome.Success(Unit)
    }

    /**
     * Permanently delete a materialized resource.
     */
    suspend fun deleteResource(resourceId: ResourceId): Outcome<Unit, String> {
        adapters.remove(resourceId)
        resourceRecordRepository.deleteResource(resourceId)
        return Outcome.Success(Unit)
    }

    /**
     * Get the in-memory adapter for a resourceId. Used by ExecutionService /
     * RuntimeAdapterResolver to execute a DecisionRecord.
     */
    fun getAdapter(resourceId: ResourceId): Any? = adapters[resourceId]

    /**
     * Set a user resource preference (planning hint only — never execution authority).
     */
    suspend fun setResourcePreference(serviceType: ServiceType, resourceId: ResourceId): Outcome<Unit, String> {
        userPreferenceRepository.setPreference(
            com.example.domain.core.provider.preference.UserResourcePreference(
                serviceType = serviceType,
                preferredResourceId = resourceId
            )
        )
        return Outcome.Success(Unit)
    }

    // ========================================================================
    // Adapter creation helpers
    // ========================================================================

    private fun createAdapterFor(
        service: ProviderService,
        config: ServiceConfiguration,
        apiKeyProvider: () -> String?,
        offeringId: String? = null
    ): Any? {
        return when (service.serviceType) {
            ServiceType.LLM -> adapterFactory.createLlmAdapter(
                service = service,
                protocolId = config.protocolId,
                config = config,
                apiKeyProvider = apiKeyProvider,
                offeringModelId = offeringId
            )
            ServiceType.SEARCH -> adapterFactory.createSearchAdapter(
                service = service,
                protocolId = config.protocolId,
                config = config,
                apiKeyProvider = apiKeyProvider
            )
            ServiceType.EMBEDDING -> adapterFactory.createEmbeddingAdapter(
                service = service,
                protocolId = config.protocolId,
                config = config,
                apiKeyProvider = apiKeyProvider
            )
            ServiceType.MCP -> getOrCreateMcpSession(service, config)
            ServiceType.IMAGE_GENERATION, ServiceType.SPEECH, ServiceType.VECTOR_STORE -> null
        }
    }

    private fun getOrCreateMcpSession(
        service: ProviderService,
        config: ServiceConfiguration
    ): com.example.infrastructure.mcp.McpAdapterPort {
        return mcpSessions.getOrPut(service.id) {
            com.example.infrastructure.mcp.McpAdapter(
                serviceId = service.id,
                config = config,
                transportType = com.example.domain.core.extension.McpTransportType.SSE,
                mcpClient = com.example.infrastructure.mcp.McpClient()
            )
        }
    }
}
