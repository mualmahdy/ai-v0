package com.example.application.provider

import com.example.application.registry.ComponentRegistry
import com.example.domain.core.Outcome
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderConfiguration
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.core.provider.ProviderValidationResult
import com.example.domain.core.resource.ConfigurationVersion
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import com.example.domain.ports.provider.ProviderRepositoryPort
import com.example.domain.ports.resource.RuntimeAdapterBinding
import com.example.domain.ports.resource.RuntimeSupportToken
import com.example.infrastructure.provider.ProviderAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider & Resource Management Control Plane Service.
 *
 * Bridges persistent Provider Configurations (Room) to live runtime ports in ComponentRegistry.
 * Manages runtime lifecycle: ADD -> CONFIGURE -> VALIDATE -> ENABLE -> REGISTER -> USE -> MONITOR -> DISABLE -> REMOVE.
 *
 * P0 RESOURCE CONTRACT (APPROVED-BASELINE v2.1, Sections C/J — LOCKED):
 * - Implements [RuntimeAdapterResolver]: the LOCKED binding chain
 *   DecisionRecord.selectedResourceId -> ResourceRegistryService.get() ->
 *   [resolveAdapter] -> RuntimeBinding (adapter bound to a specific configurationVersion).
 * - [validateResourceLifecycle] implements the Section J runtime-support chain:
 *   validate() -> canCreate -> create -> minimal real invocation ->
 *   registry.setRuntimeSupported(resourceId, true) -> lifecycle HEALTHY (RULE AD-1).
 *   If canCreate() is false the resource stays runtimeSupported=false and MUST NOT
 *   appear in the usable graph (RULE AD-2).
 */
class ProviderControlPlaneService(
    private val providerRepository: ProviderRepositoryPort,
    private val adapterFactory: ProviderAdapterFactory,
    private val componentRegistry: ComponentRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    // P0 RESOURCE CONTRACT collaborators (composition-root wired):
    private val resourceRegistry: com.example.domain.ports.resource.ResourceRegistryService? = null,
    private val runtimeSupportToken: RuntimeSupportToken? = null,
    /**
     * P0.6 / RULE AD-4: the registered LOCAL embedding ResourceRecord (providerId="local",
     * isFallback=true) binds to the REAL built-in local adapter through this provider.
     * This is the resource's OWN adapter binding — never a substitution for a configured
     * external embedding provider (which fails explicitly per RULE AD-3).
     */
    private val localEmbeddingAdapterProvider: (() -> com.example.domain.ports.memory.EmbeddingProviderPort)? = null
) : com.example.domain.ports.resource.RuntimeAdapterResolver {

    val allProvidersFlow: Flow<List<ProviderConfiguration>> = providerRepository.observeProviders()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var syncJob: Job? = null

    fun initialize() {
        if (syncJob != null) return
        syncJob = scope.launch {
            allProvidersFlow.collectLatest { providers ->
                syncRegistryWithConfigurations(providers)
            }
        }
    }

    private suspend fun syncRegistryWithConfigurations(configs: List<ProviderConfiguration>) = withContext(Dispatchers.Default) {
        _isSyncing.value = true
        try {
            val configuredIds = configs.map { it.id.lowercase() }.toSet()

            // 1. Process each configuration
            for (config in configs) {
                if (config.isEnabled) {
                    when (config.category) {
                        ProviderCategory.LLM -> {
                            val adapter = adapterFactory.createLlmAdapter(config) {
                                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                            }
                            componentRegistry.registerLlmProvider(adapter, isDefault = config.isDefault)
                            if (config.isDefault) {
                                componentRegistry.setDefaultLlmProvider(config.id)
                            }
                        }
                        ProviderCategory.SEARCH -> {
                            val adapter = adapterFactory.createSearchAdapter(config) {
                                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                            }
                            componentRegistry.registerSearchProvider(adapter, isDefault = config.isDefault)
                            if (config.isDefault) {
                                componentRegistry.setDefaultSearchProvider(config.id)
                            }
                        }
                        ProviderCategory.EMBEDDING -> {
                            val adapter = adapterFactory.createEmbeddingAdapter(config) {
                                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                            }
                            componentRegistry.registerEmbeddingProvider(adapter, isDefault = config.isDefault)
                            if (config.isDefault) {
                                componentRegistry.setDefaultEmbeddingProvider(config.id)
                            }
                        }
                        else -> Unit
                    }
                } else {
                    // Disabled: unregister from runtime
                    when (config.category) {
                        ProviderCategory.LLM -> componentRegistry.unregisterLlmProvider(config.id)
                        ProviderCategory.SEARCH -> componentRegistry.unregisterSearchProvider(config.id)
                        ProviderCategory.EMBEDDING -> componentRegistry.unregisterEmbeddingProvider(config.id)
                        else -> Unit
                    }
                }
            }

            // 2. Clean up any runtime instances whose config was deleted
            componentRegistry.listLlmProviders().forEach { llm ->
                if (!configuredIds.contains(llm.providerId.lowercase())) {
                    componentRegistry.unregisterLlmProvider(llm.providerId)
                }
            }
            componentRegistry.listSearchProviders().forEach { search ->
                if (!configuredIds.contains(search.providerId.lowercase())) {
                    componentRegistry.unregisterSearchProvider(search.providerId)
                }
            }
            componentRegistry.listEmbeddingProviders().forEach { emb ->
                if (!configuredIds.contains(emb.providerId.lowercase())) {
                    componentRegistry.unregisterEmbeddingProvider(emb.providerId)
                }
            }
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Executes real health and connection validation for a provider and updates persistence.
     */
    suspend fun validateAndRecordHealth(providerId: String): Outcome<ProviderValidationResult, String> {
        val config = providerRepository.getProviderById(providerId)
            ?: return Outcome.Error("المزود غير موجود بالمعرف: $providerId")

        val secret = providerRepository.getSecretForProvider(providerId)
        val resultOutcome = adapterFactory.validateProvider(config, secret)

        if (resultOutcome is Outcome.Success) {
            val result = resultOutcome.value
            providerRepository.updateProviderHealth(
                id = providerId,
                health = result.health,
                lastValidatedMs = System.currentTimeMillis(),
                latencyMs = result.latencyMs,
                error = if (result.isSuccess) null else result.message
            )
        }
        return resultOutcome
    }

    suspend fun saveProvider(config: ProviderConfiguration, secretApiKey: String?): Outcome<Unit, String> {
        return providerRepository.saveProvider(config, secretApiKey)
    }

    suspend fun deleteProvider(id: String): Outcome<Unit, String> {
        return providerRepository.deleteProvider(id)
    }

    suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String> {
        return providerRepository.toggleProvider(id, isEnabled)
    }

    suspend fun setAsDefaultProvider(id: String, category: ProviderCategory): Outcome<Unit, String> {
        return providerRepository.setAsDefaultProvider(id, category)
    }

    // =========================================================================
    // P0 RESOURCE CONTRACT — Adapter Architecture (Section J, LOCKED)
    // =========================================================================

    /**
     * RuntimeAdapterResolver implementation: resolves a ResourceId + configurationVersion
     * to an adapter binding. Returns null when no binding exists (ExecutionService maps
     * this to the explicit FAILURE "adapter_binding_failed" — never to a substitute).
     *
     * The binding is tied to the resource's CURRENT configurationVersion; a decision
     * carrying a stale version has no valid binding (the old revision no longer exists).
     */
    override suspend fun resolveAdapter(
        resourceId: ResourceId,
        configurationVersion: ConfigurationVersion
    ): RuntimeAdapterBinding? {
        val registry = resourceRegistry ?: return null
        val record = registry.get(resourceId) ?: return null
        // Binding is version-specific (Section C: RuntimeBinding bound to a specific
        // configurationVersion). Stale decisions must re-decide, not silently execute
        // against a newer revision.
        if (record.configurationVersion != configurationVersion) return null

        // P0.6 / RULE AD-4: the explicitly registered local embedding resource binds to
        // its own real adapter without requiring a ProviderConfiguration entity.
        if (record.providerId.value == com.example.application.resource.ResourceContractMigration.LOCAL_EMBEDDING_PROVIDER_ID) {
            val localPort = localEmbeddingAdapterProvider?.invoke() ?: return null
            return RuntimeAdapterBinding.Embedding(
                port = localPort,
                resourceId = resourceId,
                configurationVersion = configurationVersion
            )
        }

        val config = providerRepository.getProviderById(record.providerId.value) ?: return null
        if (!config.isEnabled) return null

        return when (record.resourceType) {
            ResourceType.LLM -> RuntimeAdapterBinding.Llm(
                port = adapterFactory.createLlmAdapter(config) {
                    kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                },
                resourceId = resourceId,
                configurationVersion = configurationVersion
            )
            ResourceType.SEARCH -> RuntimeAdapterBinding.Search(
                port = adapterFactory.createSearchAdapter(config) {
                    kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                },
                resourceId = resourceId,
                configurationVersion = configurationVersion
            )
            ResourceType.EMBEDDING -> RuntimeAdapterBinding.Embedding(
                port = adapterFactory.createEmbeddingAdapter(config) {
                    kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider(config.id) }
                },
                resourceId = resourceId,
                configurationVersion = configurationVersion
            )
            else -> null // TOOL/AGENT/STORAGE runtime bindings are beyond P0 scope
        }
    }

    /**
     * Section J — Runtime Support Chain (LOCKED, RULE AD-1 / AD-2):
     * validate() -> canCreate -> create -> minimal real invocation ->
     * registry.setRuntimeSupported(true) -> lifecycle HEALTHY.
     *
     * RULE AD-1: runtimeSupported=true ONLY after a real adapter was created AND a
     * minimal real invocation succeeded. Configuration existence alone never sets it.
     * RULE AD-2: canCreate() == false keeps runtimeSupported=false; the resource MUST
     * NOT appear in the usable graph regardless of lifecycle state.
     * RULE LC-1: validation failure lands back on CONFIGURED ("configured but not
     * currently working") — not an error state, and distinct from UNAVAILABLE.
     */
    suspend fun validateResourceLifecycle(resourceId: ResourceId): Outcome<ProviderValidationResult, String> {
        val registry = requireNotNull(resourceRegistry) {
            "ResourceRegistryService must be wired for the P0 resource contract"
        }
        val token = requireNotNull(runtimeSupportToken) {
            "RuntimeSupportToken must be wired for the control plane"
        }

        val record = registry.get(resourceId)
            ?: return Outcome.Error("المورد غير موجود بالمعرف: ${resourceId.value}")
        val config = providerRepository.getProviderById(record.providerId.value)
            ?: return Outcome.Error("تكوين المزود غير موجود بالمعرف: ${record.providerId.value}")

        // Section J: control plane begins validation.
        registry.setLifecycleState(resourceId, ResourceLifecycleState.VALIDATING)

        try {
            // RULE AD-2: adapter existence is determined ONLY by the factory path.
            if (!adapterFactory.canCreate(config.category, config.flavor)) {
                registry.setRuntimeSupported(resourceId, supported = false, token = token)
                registry.setLifecycleState(resourceId, ResourceLifecycleState.CONFIGURED)
                return Outcome.Success(
                    ProviderValidationResult(
                        isSuccess = false,
                        health = HealthStatus.UNAVAILABLE,
                        latencyMs = 0L,
                        message = "لا توجد فئة محول (adapter) تدعم هذه الفئة/النكهة — runtimeSupported يبقى false (RULE AD-2)."
                    )
                )
            }

            // Minimal real invocation via the existing real validation path.
            val secret = providerRepository.getSecretForProvider(config.id)
            val result = when (val outcome = adapterFactory.validateProvider(config, secret)) {
                is Outcome.Success -> outcome.value
                else -> ProviderValidationResult(
                    isSuccess = false,
                    health = HealthStatus.UNAVAILABLE,
                    latencyMs = 0L,
                    message = "فشل التحقق الحقيقي من المورد."
                )
            }

            providerRepository.updateProviderHealth(
                id = config.id,
                health = result.health,
                lastValidatedMs = System.currentTimeMillis(),
                latencyMs = result.latencyMs,
                error = if (result.isSuccess) null else result.message
            )

            if (result.isSuccess) {
                // RULE AD-1: real adapter + successful minimal invocation.
                registry.setRuntimeSupported(resourceId, supported = true, token = token)
                registry.setLifecycleState(resourceId, ResourceLifecycleState.HEALTHY)
            } else {
                registry.setRuntimeSupported(resourceId, supported = false, token = token)
                // RULE LC-1: back to CONFIGURED — configured but not currently working.
                registry.setLifecycleState(resourceId, ResourceLifecycleState.CONFIGURED)
            }
            return Outcome.Success(result)
        } catch (e: Exception) {
            registry.setRuntimeSupported(resourceId, supported = false, token = token)
            registry.setLifecycleState(resourceId, ResourceLifecycleState.CONFIGURED)
            return Outcome.Error("استثناء أثناء التحقق من المورد: ${e.localizedMessage}")
        }
    }
}
