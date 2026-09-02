package com.example.application.resource

import com.example.domain.core.provider.ProviderCategory
import com.example.domain.core.provider.ProviderFlavor
import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.ResourceCategory
import com.example.domain.core.resource.ResourceRecordInput
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.ServiceId
import com.example.domain.ports.provider.ProviderRepositoryPort
import com.example.domain.ports.resource.ResourceRegistryService
import com.example.domain.core.capability.CapabilityType

/**
 * P0.2 — RULE REG-3 migration routine (APPROVED-BASELINE v2.1, Section D).
 *
 * On first app start under the new architecture, every existing persisted
 * provider/service pair is assigned a ResourceId and registered with
 * lifecycleState=CONFIGURED and runtimeSupported=false. No adapter existence
 * is assumed (RULE AD-1: runtime support only via the real adapter path).
 *
 * Additionally implements RULE AD-4 (P0.6): the local embedding engine is
 * registered as its OWN ResourceRecord (category=LOCAL, isFallback=true). It is
 * selectable only via an explicit decision, never via adapter-internal substitution.
 *
 * The routine is idempotent: resources whose logical key already exists are
 * skipped (the registry rejects duplicates), so it is safe to run on every start.
 */
class ResourceContractMigration(
    private val providerRepository: ProviderRepositoryPort,
    private val resourceRegistry: ResourceRegistryService
) {

    /**
     * Runs the idempotent first-start migration. Returns the number of resource
     * records newly registered during this run.
     */
    suspend fun migrateIfNeeded(): Int {
        var registered = 0

        // 1. RULE REG-3: legacy persistent providers -> ResourceRecords.
        val providers = providerRepository.getAllProviders()
        for (config in providers) {
            val providerId = ProviderId(config.id)
            val serviceId = ServiceId(config.defaultModelId.ifBlank { config.flavor.defaultModel.ifBlank { config.id } })
            val existing = resourceRegistry.getByLogicalKey(providerId, serviceId)
            if (existing != null) continue

            val result = resourceRegistry.register(
                ResourceRecordInput(
                    providerId = providerId,
                    serviceId = serviceId,
                    resourceType = config.category.toResourceType(),
                    category = config.flavor.toResourceCategory(),
                    capabilities = config.category.defaultCapabilityIds(),
                    isFallback = config.flavor == ProviderFlavor.LOCAL_EMBEDDING,
                    metadata = mapOf(
                        "providerName" to config.name,
                        "flavor" to config.flavor.code,
                        "endpointUrl" to config.endpointUrl,
                        "migration" to "REG-3"
                    )
                )
            )
            if (result is com.example.domain.core.resource.RegistryResult.Success) registered++
        }

        // 2. RULE AD-4: local embedding engine registered as its own ResourceRecord.
        val localEmbeddingResult = resourceRegistry.register(
            ResourceRecordInput(
                providerId = ProviderId(LOCAL_EMBEDDING_PROVIDER_ID),
                serviceId = ServiceId(LOCAL_EMBEDDING_SERVICE_ID),
                resourceType = ResourceType.EMBEDDING,
                category = ResourceCategory.LOCAL,
                capabilities = listOf(CapabilityType.EMBEDDING.code),
                isFallback = true,
                metadata = mapOf(
                    "providerName" to "Built-in Local Embedding Engine",
                    "migration" to "AD-4"
                )
            )
        )
        if (localEmbeddingResult is com.example.domain.core.resource.RegistryResult.Success) registered++

        return registered
    }

    companion object {
        const val LOCAL_EMBEDDING_PROVIDER_ID = "local"
        const val LOCAL_EMBEDDING_SERVICE_ID = "local_embedding_engine"

        private fun ProviderCategory.toResourceType(): ResourceType = when (this) {
            ProviderCategory.LLM -> ResourceType.LLM
            ProviderCategory.EMBEDDING -> ResourceType.EMBEDDING
            ProviderCategory.SEARCH -> ResourceType.SEARCH
            ProviderCategory.VECTOR_STORE -> ResourceType.STORAGE
        }

        private fun ProviderFlavor.toResourceCategory(): ResourceCategory = when (this) {
            ProviderFlavor.OLLAMA, ProviderFlavor.LOCAL_EMBEDDING -> ResourceCategory.LOCAL
            else -> ResourceCategory.REMOTE
        }

        private fun ProviderCategory.defaultCapabilityIds(): List<String> = when (this) {
            ProviderCategory.LLM -> listOf(
                CapabilityType.LLM_GENERATION.code,
                CapabilityType.REASONING.code
            )
            ProviderCategory.EMBEDDING -> listOf(CapabilityType.EMBEDDING.code)
            ProviderCategory.SEARCH -> listOf(CapabilityType.SEARCH.code)
            ProviderCategory.VECTOR_STORE -> listOf(CapabilityType.VECTOR_STORE.code)
        }
    }
}
