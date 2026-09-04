package com.example.domain.core.resource

import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceHealthClassification
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.ServiceValidationResult
import com.example.domain.core.provider.ProviderService

/**
 * ============================================================================
 * Resource validation contract — Phase 4 (Correction #4)
 * ============================================================================
 *
 * Validators run REAL protocol operations against a materialized resource
 * adapter (or its configuration) and produce a machine-classified
 * [ServiceValidationResult]. No validator may fabricate success: an unprobed
 * resource is UNKNOWN, not HEALTHY.
 *
 * The interface lives in the domain layer; concrete implementations (which
 * perform HTTP calls / SDK calls) live in the infrastructure layer and are
 * registered by `defaultResourceValidatorRegistry()`.
 */
interface ResourceValidator {
    suspend fun validate(
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        adapter: Any?,
        apiKeyProvider: suspend () -> String?
    ): ServiceValidationResult
}

/**
 * Registry of validators keyed by [ResourceType]. The control plane resolves
 * the validator for a service's resource type and rejects validation when no
 * validator is registered (explicit failure, no silent pass-through).
 */
class ResourceValidatorRegistry {

    private val validators = mutableMapOf<ResourceType, ResourceValidator>()

    fun register(resourceType: ResourceType, validator: ResourceValidator) {
        validators[resourceType] = validator
    }

    fun get(resourceType: ResourceType): ResourceValidator? = validators[resourceType]

    suspend fun validate(
        resourceType: ResourceType,
        service: ProviderService,
        protocolId: ServiceProtocolId,
        config: ServiceConfiguration,
        adapter: Any?,
        apiKeyProvider: suspend () -> String?
    ): ServiceValidationResult {
        val validator = validators[resourceType]
            ?: return ServiceValidationResult.failure(
                ServiceHealthClassification.UNKNOWN,
                0L,
                "No validator registered for resource type $resourceType"
            )
        return validator.validate(service, protocolId, config, adapter, apiKeyProvider)
    }

    companion object {
        /**
         * Maps a provider ServiceType to the runtime ResourceType of the
         * resource materialized from it. Types without a runtime execution path
         * (image/speech for now) map to INTEGRATION which has no registered
         * validator — so validation fails explicitly rather than pretending.
         */
        fun serviceTypeToResourceType(serviceType: ServiceType): ResourceType {
            return when (serviceType) {
                ServiceType.LLM -> ResourceType.LLM
                ServiceType.EMBEDDING -> ResourceType.EMBEDDING
                ServiceType.SEARCH -> ResourceType.SEARCH
                ServiceType.MCP -> ResourceType.TOOL
                ServiceType.VECTOR_STORE -> ResourceType.STORAGE
                ServiceType.IMAGE_GENERATION -> ResourceType.INTEGRATION
                ServiceType.SPEECH -> ResourceType.INTEGRATION
            }
        }
    }
}
