package com.example.infrastructure.persistence.repository

import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceHealthClassification
import com.example.domain.core.provider.ServiceHealthRecord
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.offering.OfferingType
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.provider.preference.UserResourcePreference
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.ports.provider.OfferingRepository
import com.example.domain.ports.provider.ProviderRepository
import com.example.domain.ports.provider.ProviderServiceRepository
import com.example.domain.ports.provider.ServiceConfigurationRepository
import com.example.domain.ports.provider.ServiceHealthRepository
import com.example.domain.ports.provider.UserPreferenceRepository
import com.example.domain.ports.resource.ResourceRecordRepository
import com.example.infrastructure.persistence.dao.ProviderDao
import com.example.infrastructure.persistence.dao.ProviderServiceDao
import com.example.infrastructure.persistence.dao.ResourceRecordDao
import com.example.infrastructure.persistence.dao.ServiceConfigurationDao
import com.example.infrastructure.persistence.dao.ServiceHealthRecordDao
import com.example.infrastructure.persistence.dao.ServiceOfferingDao
import com.example.infrastructure.persistence.dao.UserResourcePreferenceDao
import com.example.infrastructure.persistence.entities.ProviderEntity
import com.example.infrastructure.persistence.entities.ProviderServiceEntity
import com.example.infrastructure.persistence.entities.ResourceRecordEntity
import com.example.infrastructure.persistence.entities.ServiceConfigurationEntity
import com.example.infrastructure.persistence.entities.ServiceHealthRecordEntity
import com.example.infrastructure.persistence.entities.ServiceOfferingEntity
import com.example.infrastructure.persistence.entities.UserResourcePreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * Phase 4 — REAL Room repository implementations
 * ============================================================================
 *
 * These replace the empty stubs shipped with the WIP branch. Every method
 * performs actual persistence through Room DAOs and maps between the rich
 * domain models and the Phase 4 entities. Collections/enums are serialized as
 * JSON / enum names — see ProviderServiceEntities.
 */

/* ------------------------------ JSON helpers ------------------------------ */

private fun jsonArrayToStrings(json: String): List<String> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())
}

private fun stringsToJsonArray(values: List<String>): String {
    val arr = JSONArray()
    values.forEach { arr.put(it) }
    return arr.toString()
}

private fun jsonStringsToSet(json: String): Set<String> = jsonArrayToStrings(json).toSet()

private fun setToJsonArray(values: Set<String>): String = stringsToJsonArray(values.toList())

private fun jsonObjectToStringMap(json: String): Map<String, String> {
    return runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { key -> obj.optString(key) }
    }.getOrDefault(emptyMap())
}

private fun stringMapToJson(map: Map<String, String>): String {
    val obj = JSONObject()
    map.forEach { (k, v) -> obj.put(k, v) }
    return obj.toString()
}

private fun parseEnumOrNull(name: String): CapabilityType? =
    runCatching { CapabilityType.valueOf(name) }.getOrNull()

/* ------------------------------ Provider ---------------------------------- */

class RoomProviderRepository(
    private val dao: ProviderDao
) : ProviderRepository {

    override fun observeProviders(): Flow<List<Provider>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getProviderById(id: String): Provider? =
        dao.getById(id)?.toDomain()

    override suspend fun saveProvider(provider: Provider): Outcome<Unit, String> = try {
        dao.upsert(provider.toEntity())
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Error("PROVIDER_SAVE_FAILED", e.message ?: "Unknown error saving provider")
    }

    override suspend fun deleteProvider(id: String): Outcome<Unit, String> = try {
        dao.deleteById(id)
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Error("PROVIDER_DELETE_FAILED", e.message ?: "Unknown error deleting provider")
    }

    override suspend fun toggleProvider(id: String, isEnabled: Boolean): Outcome<Unit, String> {
        val existing = dao.getById(id)
            ?: return Outcome.Error("PROVIDER_NOT_FOUND", "Provider $id not found")
        return saveProvider(
            existing.toDomain().copy(isEnabled = isEnabled, updatedAtEpochMs = System.currentTimeMillis())
        )
    }
}

private fun ProviderEntity.toDomain() = Provider(
    id = id,
    name = name,
    description = description,
    websiteUrl = websiteUrl,
    isLocal = isLocal,
    isEnabled = isEnabled,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

private fun Provider.toEntity() = ProviderEntity(
    id = id,
    name = name,
    description = description,
    websiteUrl = websiteUrl,
    isLocal = isLocal,
    isEnabled = isEnabled,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

/* ------------------------------ ProviderService --------------------------- */

class RoomProviderServiceRepository(
    private val dao: ProviderServiceDao
) : ProviderServiceRepository {

    override fun observeAllServices(): Flow<List<ProviderService>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getServiceById(id: String): ProviderService? =
        dao.getById(id)?.toDomain()

    override suspend fun getServicesForProvider(providerId: String): List<ProviderService> =
        dao.getByProviderId(providerId).map { it.toDomain() }

    override suspend fun saveService(service: ProviderService): Outcome<Unit, String> = try {
        dao.upsert(service.toEntity())
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Error("SERVICE_SAVE_FAILED", e.message ?: "Unknown error saving service")
    }

    override suspend fun deleteService(id: String): Outcome<Unit, String> = try {
        dao.deleteById(id)
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Error("SERVICE_DELETE_FAILED", e.message ?: "Unknown error deleting service")
    }

    override suspend fun toggleService(id: String, isEnabled: Boolean): Outcome<Unit, String> {
        val existing = dao.getById(id)
            ?: return Outcome.Error("SERVICE_NOT_FOUND", "Service $id not found")
        return saveService(existing.toDomain().copy(isEnabled = isEnabled))
    }
}

private fun ProviderServiceEntity.toDomain() = ProviderService(
    id = id,
    providerId = providerId,
    name = name,
    description = description,
    serviceType = runCatching { ServiceType.valueOf(serviceType) }.getOrDefault(ServiceType.LLM),
    supportedProtocolIds = jsonArrayToStrings(supportedProtocolIdsJson),
    isEnabled = isEnabled
)

private fun ProviderService.toEntity() = ProviderServiceEntity(
    id = id,
    providerId = providerId,
    name = name,
    description = description,
    serviceType = serviceType.name,
    supportedProtocolIdsJson = stringsToJsonArray(supportedProtocolIds),
    isEnabled = isEnabled
)

/* ------------------------------ ServiceConfiguration ---------------------- */

class RoomServiceConfigurationRepository(
    private val dao: ServiceConfigurationDao,
    private val serviceDao: ProviderServiceDao
) : ServiceConfigurationRepository {

    override fun observeAllConfigurations(): Flow<List<ServiceConfiguration>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getConfigurationById(id: String): ServiceConfiguration? =
        dao.getById(id)?.toDomain()

    override suspend fun getCurrentConfigurationForService(serviceId: String): ServiceConfiguration? =
        dao.getLatestByServiceId(serviceId)?.toDomain()

    /**
     * Correction #3: monotonic version bump. When the row already exists the
     * saved copy gets `configurationVersion = existing + 1` so any decision
     * record created against the old version becomes stale and is rejected by
     * the RuntimeAdapterResolver.
     */
    override suspend fun saveConfiguration(config: ServiceConfiguration): Outcome<Unit, String> {
        val service = serviceDao.getById(config.serviceId)
            ?: return Outcome.Error(
                "SERVICE_NOT_FOUND",
                "Cannot save configuration for unknown service ${config.serviceId}"
            )
        return try {
            val existing = dao.getById(config.id)
            val toSave = if (existing != null) {
                val fromVersion = maxOf(existing.configurationVersion, config.configurationVersion)
                config.copy(
                    configurationVersion = fromVersion + 1L,
                    createdAtEpochMs = existing.createdAtEpochMs,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            } else {
                config
            }
            dao.upsert(toSave.toEntity())
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("CONFIG_SAVE_FAILED", e.message ?: "Unknown error saving configuration")
        }
    }

    override suspend fun deleteConfiguration(id: String): Outcome<Unit, String> = try {
        dao.deleteById(id)
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Error("CONFIG_DELETE_FAILED", e.message ?: "Unknown error deleting configuration")
    }

    override suspend fun toggleConfiguration(id: String, isEnabled: Boolean): Outcome<Unit, String> {
        val existing = dao.getById(id)
            ?: return Outcome.Error("CONFIG_NOT_FOUND", "Configuration $id not found")
        return saveConfiguration(existing.toDomain().copy(isEnabled = isEnabled))
    }

    override suspend fun deleteConfigurationsForService(serviceId: String) {
        dao.deleteByServiceId(serviceId)
    }
}

private fun ServiceConfigurationEntity.toDomain() = ServiceConfiguration(
    id = id,
    serviceId = serviceId,
    protocolId = runCatching { ServiceProtocolId.valueOf(protocolId) }
        .getOrDefault(ServiceProtocolId.OPENAI_COMPATIBLE),
    endpointUrl = endpointUrl,
    defaultOfferingId = defaultOfferingId,
    isEnabled = isEnabled,
    isDefault = isDefault,
    healthStatus = runCatching { HealthStatus.valueOf(healthStatus) }.getOrDefault(HealthStatus.UNKNOWN),
    lastValidatedEpochMs = lastValidatedEpochMs,
    lastLatencyMs = lastLatencyMs,
    lastErrorMessage = lastErrorMessage,
    extraHeaders = jsonObjectToStringMap(extraHeadersJson),
    timeoutSeconds = timeoutSeconds,
    hasSecretKey = hasSecretKey,
    authAlias = authAlias,
    configurationVersion = configurationVersion,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

private fun ServiceConfiguration.toEntity() = ServiceConfigurationEntity(
    id = id,
    serviceId = serviceId,
    protocolId = protocolId.name,
    endpointUrl = endpointUrl,
    defaultOfferingId = defaultOfferingId,
    isEnabled = isEnabled,
    isDefault = isDefault,
    healthStatus = healthStatus.name,
    lastValidatedEpochMs = lastValidatedEpochMs,
    lastLatencyMs = lastLatencyMs,
    lastErrorMessage = lastErrorMessage,
    extraHeadersJson = stringMapToJson(extraHeaders),
    timeoutSeconds = timeoutSeconds,
    hasSecretKey = hasSecretKey,
    authAlias = authAlias,
    configurationVersion = configurationVersion,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

/* ------------------------------ ServiceHealthRecord ----------------------- */

class RoomServiceHealthRepository(
    private val dao: ServiceHealthRecordDao
) : ServiceHealthRepository {

    override suspend fun saveHealthRecord(record: ServiceHealthRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun getLatestForConfiguration(serviceConfigurationId: String): ServiceHealthRecord? =
        dao.getLatestForConfiguration(serviceConfigurationId)?.toDomain()

    override suspend fun getHistoryForConfiguration(
        serviceConfigurationId: String,
        limit: Int
    ): List<ServiceHealthRecord> =
        dao.getHistoryForConfiguration(serviceConfigurationId, limit).map { it.toDomain() }
}

private fun ServiceHealthRecordEntity.toDomain() = ServiceHealthRecord(
    id = id,
    serviceConfigurationId = serviceConfigurationId,
    healthStatus = runCatching { HealthStatus.valueOf(healthStatus) }.getOrDefault(HealthStatus.UNKNOWN),
    lastHealthClassification = runCatching {
        ServiceHealthClassification.valueOf(lastHealthClassification)
    }.getOrDefault(ServiceHealthClassification.UNKNOWN),
    lastValidatedEpochMs = lastValidatedEpochMs,
    lastLatencyMs = lastLatencyMs,
    lastErrorMessage = lastErrorMessage,
    validatedAtEpochMs = validatedAtEpochMs
)

private fun ServiceHealthRecord.toEntity() = ServiceHealthRecordEntity(
    id = id,
    serviceConfigurationId = serviceConfigurationId,
    healthStatus = healthStatus.name,
    lastHealthClassification = lastHealthClassification.name,
    lastValidatedEpochMs = lastValidatedEpochMs,
    lastLatencyMs = lastLatencyMs,
    lastErrorMessage = lastErrorMessage,
    validatedAtEpochMs = validatedAtEpochMs
)

/* ------------------------------ Offering ---------------------------------- */

class RoomOfferingRepository(
    private val dao: ServiceOfferingDao
) : OfferingRepository {

    override suspend fun registerOffering(offering: ServiceOffering) {
        dao.upsert(offering.toEntity())
    }

    override suspend fun getOffering(offeringId: String): ServiceOffering? =
        dao.getById(offeringId)?.toDomain()

    override suspend fun findOfferingsForService(serviceId: String): List<ServiceOffering> =
        dao.getByServiceId(serviceId).map { it.toDomain() }

    override suspend fun getAllOfferings(): List<ServiceOffering> =
        dao.observeAll().first().map { it.toDomain() }

    override suspend fun clearForService(serviceId: String) {
        dao.deleteByServiceId(serviceId)
    }
}

private fun ServiceOfferingEntity.toDomain() = ServiceOffering(
    id = id,
    serviceId = serviceId,
    offeringType = runCatching { OfferingType.valueOf(offeringType) }.getOrDefault(OfferingType.MODEL),
    name = name,
    description = description,
    contextWindowTokens = contextWindowTokens,
    supportedCapabilities = jsonStringsToSet(supportedCapabilitiesJson)
        .mapNotNull { parseEnumOrNull(it) }.toSet(),
    isLocal = isLocal,
    isAvailable = isAvailable,
    pricingInputTokensPerMillion = pricingInputTokensPerMillion,
    pricingOutputTokensPerMillion = pricingOutputTokensPerMillion,
    latencyScoreMs = latencyScoreMs,
    discoveredEpochMs = discoveredEpochMs,
    discoverySource = discoverySource
)

private fun ServiceOffering.toEntity() = ServiceOfferingEntity(
    id = id,
    serviceId = serviceId,
    offeringType = offeringType.name,
    name = name,
    description = description,
    contextWindowTokens = contextWindowTokens,
    supportedCapabilitiesJson = setToJsonArray(supportedCapabilities.map { it.name }.toSet()),
    isLocal = isLocal,
    isAvailable = isAvailable,
    pricingInputTokensPerMillion = pricingInputTokensPerMillion,
    pricingOutputTokensPerMillion = pricingOutputTokensPerMillion,
    latencyScoreMs = latencyScoreMs,
    discoveredEpochMs = discoveredEpochMs,
    discoverySource = discoverySource
)

/* ------------------------------ UserPreference ---------------------------- */

class RoomUserPreferenceRepository(
    private val dao: UserResourcePreferenceDao
) : UserPreferenceRepository {

    override suspend fun setPreference(preference: UserResourcePreference) {
        dao.upsert(preference.toEntity())
    }

    override suspend fun getPreference(serviceType: ServiceType): UserResourcePreference? =
        dao.getByServiceType(serviceType.name)?.toDomain()

    override suspend fun getAllPreferences(): List<UserResourcePreference> =
        dao.getAll().map { it.toDomain() }

    override suspend fun deletePreference(serviceType: ServiceType) {
        dao.deleteByServiceType(serviceType.name)
    }
}

private fun UserResourcePreferenceEntity.toDomain() = UserResourcePreference(
    serviceType = runCatching { ServiceType.valueOf(serviceType) }.getOrDefault(ServiceType.LLM),
    preferredResourceId = ResourceId(preferredResourceId),
    preferredResourceName = preferredResourceName,
    reason = reason,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

private fun UserResourcePreference.toEntity() = UserResourcePreferenceEntity(
    serviceType = serviceType.name,
    preferredResourceId = preferredResourceId.value,
    preferredResourceName = preferredResourceName,
    reason = reason,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = System.currentTimeMillis()
)

/* ------------------------------ ResourceRecord ---------------------------- */

class RoomResourceRecordRepository(
    private val dao: ResourceRecordDao
) : ResourceRecordRepository {

    override suspend fun saveResource(record: ResourceRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun getResourceById(resourceId: ResourceId): ResourceRecord? =
        dao.getById(resourceId.value)?.toDomain()

    override suspend fun getAllResources(): List<ResourceRecord> =
        dao.getAll().map { it.toDomain() }

    override fun observeAllResources(): Flow<List<ResourceRecord>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun updateRuntimeState(
        resourceId: ResourceId,
        lifecycleState: ResourceLifecycleState,
        runtimeSupported: Boolean,
        healthStatus: HealthStatus
    ) {
        dao.updateRuntimeState(resourceId.value, lifecycleState.name, runtimeSupported, healthStatus.name)
    }

    override suspend fun deleteResource(resourceId: ResourceId) {
        dao.deleteById(resourceId.value)
    }

    override suspend fun deleteResourcesForService(serviceId: String) {
        dao.deleteByServiceId(serviceId)
    }
}

private fun ResourceRecordEntity.toDomain() = ResourceRecord(
    resourceId = ResourceId(resourceId),
    providerId = providerId,
    serviceId = serviceId,
    resourceType = runCatching { ResourceType.valueOf(resourceType) }.getOrDefault(ResourceType.LLM),
    capabilities = jsonStringsToSet(capabilitiesJson).mapNotNull { parseEnumOrNull(it) }.toSet(),
    configurationVersion = configurationVersion,
    lifecycleState = runCatching {
        ResourceLifecycleState.valueOf(lifecycleState)
    }.getOrDefault(ResourceLifecycleState.REGISTERED),
    runtimeSupported = runtimeSupported,
    healthStatus = runCatching { HealthStatus.valueOf(healthStatus) }.getOrDefault(HealthStatus.UNKNOWN),
    isLocal = isLocal,
    metadata = jsonObjectToStringMap(metadataJson)
)

private fun ResourceRecord.toEntity() = ResourceRecordEntity(
    resourceId = resourceId.value,
    providerId = providerId,
    serviceId = serviceId,
    resourceType = resourceType.name,
    capabilitiesJson = setToJsonArray(capabilities.map { it.name }.toSet()),
    configurationVersion = configurationVersion,
    lifecycleState = lifecycleState.name,
    runtimeSupported = runtimeSupported,
    healthStatus = healthStatus.name,
    isLocal = isLocal,
    metadataJson = stringMapToJson(metadata)
)
