package com.example.domain.core.provider.offering

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceType
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * Offering types — Phase 4 (canonical package: domain.core.provider.offering)
 * ============================================================================
 *
 * Concrete models, search indexes, or capabilities exposed by a Service.
 * This is the single authoritative declaration of [ServiceOffering],
 * [OfferingType] and [OfferingCatalog].
 */

/**
 * Category of a discovered or explicitly configured offering.
 */
enum class OfferingType(val code: String) {
    MODEL("model"),
    INDEX("index"),
    TOOL("tool"),
    ENDPOINT("endpoint")
}

data class ServiceOffering(
    val id: String,
    val serviceId: String,
    val offeringType: OfferingType = OfferingType.MODEL,
    val name: String,
    val description: String = "",
    val contextWindowTokens: Int? = null,
    val supportedCapabilities: Set<CapabilityType> = emptySet(),
    val isLocal: Boolean = false,
    val isAvailable: Boolean = true,
    val pricingInputTokensPerMillion: Double? = null,
    val pricingOutputTokensPerMillion: Double? = null,
    val latencyScoreMs: Long = 0L,
    val discoveredEpochMs: Long = System.currentTimeMillis(),
    val discoverySource: String = "DISCOVERY_PORT"
) {
    /**
     * Phase 4 (honest materialization): maps an Offering to a ResourceRecord
     * with an explicit stable [ResourceId] and explicit runtime facts taken
     * from the arguments. Defaults are honest:
     *   - lifecycleState = REGISTERED (not yet validated)
     *   - runtimeSupported = false (must be verified)
     *   - healthStatus = UNKNOWN (must be probed)
     *
     * The control plane calls this when materializing a resource; the user
     * then calls `validateResource` to promote the record.
     */
    fun toResourceRecord(
        resourceId: ResourceId,
        providerId: String,
        resourceType: ResourceType,
        configurationVersion: Long
    ): com.example.domain.core.resource.ResourceRecord {
        return com.example.domain.core.resource.ResourceRecord(
            resourceId = resourceId,
            providerId = providerId,
            serviceId = serviceId,
            resourceType = resourceType,
            capabilities = supportedCapabilities,
            configurationVersion = configurationVersion,
            lifecycleState = ResourceLifecycleState.REGISTERED,
            runtimeSupported = false,
            healthStatus = com.example.domain.core.provider.HealthStatus.UNKNOWN,
            isLocal = isLocal,
            metadata = mapOf(
                "offeringId" to id,
                "offeringType" to offeringType.code,
                "serviceId" to serviceId,
                "providerId" to providerId,
                "discoverySource" to discoverySource
            )
        )
    }

    /**
     * Legacy optimistic bridge (kept for the ProviderConfiguration bridge
     * functions and pre-Phase-4 tests): derives the ResourceId from the
     * offering id and assumes the configuration's health/enabled state.
     */
    fun toResourceRecord(
        providerId: String,
        config: ServiceConfiguration,
        resourceType: ResourceType
    ): com.example.domain.core.resource.ResourceRecord {
        return com.example.domain.core.resource.ResourceRecord(
            resourceId = ResourceId("res-$id"),
            providerId = providerId,
            serviceId = serviceId,
            resourceType = resourceType,
            capabilities = supportedCapabilities,
            configurationVersion = config.configurationVersion,
            lifecycleState = if (config.isEnabled && isAvailable) {
                ResourceLifecycleState.ACTIVE
            } else {
                ResourceLifecycleState.DISABLED
            },
            runtimeSupported = true,
            healthStatus = config.healthStatus,
            isLocal = isLocal,
            metadata = mapOf(
                "offeringId" to id,
                "offeringType" to offeringType.code,
                "serviceId" to serviceId,
                "providerId" to providerId,
                "endpointUrl" to config.endpointUrl,
                "discoverySource" to discoverySource
            )
        )
    }
}

/**
 * Authoritative Offering Catalog maintaining live offerings discovered across
 * services. In-memory only — durable persistence goes through
 * [com.example.domain.ports.provider.OfferingRepository].
 */
class OfferingCatalog {
    private val offerings = ConcurrentHashMap<String, ServiceOffering>()

    fun registerOffering(offering: ServiceOffering) {
        offerings[offering.id.lowercase()] = offering
    }

    fun unregisterOffering(offeringId: String) {
        offerings.remove(offeringId.lowercase())
    }

    fun getOffering(offeringId: String): ServiceOffering? {
        return offerings[offeringId.lowercase()]
    }

    fun listOfferings(): List<ServiceOffering> = offerings.values.toList()

    fun findOfferingsForCapability(capability: CapabilityType): List<ServiceOffering> {
        return offerings.values.filter { capability in it.supportedCapabilities && it.isAvailable }
    }

    fun findOfferingsForService(serviceId: String): List<ServiceOffering> {
        return offerings.values.filter { it.serviceId.equals(serviceId, ignoreCase = true) }
    }

    fun clear() {
        offerings.clear()
    }
}
