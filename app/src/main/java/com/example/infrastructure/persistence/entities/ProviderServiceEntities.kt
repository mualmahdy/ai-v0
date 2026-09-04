package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Phase 4 — Generalized Provider Architecture persistence entities
 * ============================================================================
 *
 * These 7 tables back the Provider → Service → Protocol → Configuration →
 * Offering → ResourceRecord → UserPreference pipeline. They mirror the RICH
 * domain models (String ids, typed enums stored as name strings, collections
 * stored as JSON).
 *
 * Mapping to/from domain models happens exclusively in
 * `infrastructure.persistence.repository`. Enum/collection columns are plain
 * TEXT so no global Room type converters are needed and migration SQL stays
 * simple and reviewable.
 */

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val websiteUrl: String? = null,
    @ColumnInfo(name = "isLocal") val isLocal: Boolean,
    @ColumnInfo(name = "isEnabled") val isEnabled: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "provider_services",
    indices = [Index("providerId")]
)
data class ProviderServiceEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val name: String,
    val description: String,
    /** ServiceType enum name (e.g. LLM, EMBEDDING, SEARCH, MCP). */
    val serviceType: String,
    /** JSON array of protocol codes (ServiceProtocolId.code). */
    val supportedProtocolIdsJson: String,
    val isEnabled: Boolean
)

@Entity(
    tableName = "service_configurations",
    indices = [Index("serviceId")]
)
data class ServiceConfigurationEntity(
    @PrimaryKey val id: String,
    val serviceId: String,
    /** ServiceProtocolId enum name. */
    val protocolId: String,
    val endpointUrl: String,
    val defaultOfferingId: String,
    val isEnabled: Boolean,
    val isDefault: Boolean,
    /** HealthStatus enum name. */
    val healthStatus: String,
    val lastValidatedEpochMs: Long,
    val lastLatencyMs: Long,
    val lastErrorMessage: String? = null,
    /** JSON object of extra headers. */
    val extraHeadersJson: String,
    val timeoutSeconds: Int,
    val hasSecretKey: Boolean,
    /** Alias under which the secret is stored in EncryptedSecretStorage. */
    val authAlias: String? = null,
    val configurationVersion: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "service_health_records",
    indices = [Index("serviceConfigurationId")]
)
data class ServiceHealthRecordEntity(
    @PrimaryKey val id: String,
    val serviceConfigurationId: String,
    /** HealthStatus enum name. */
    val healthStatus: String,
    /** ServiceHealthClassification enum name. */
    val lastHealthClassification: String,
    val lastValidatedEpochMs: Long,
    val lastLatencyMs: Long,
    val lastErrorMessage: String? = null,
    val validatedAtEpochMs: Long
)

@Entity(
    tableName = "service_offerings",
    primaryKeys = ["id", "serviceId"]
)
data class ServiceOfferingEntity(
    val id: String,
    val serviceId: String,
    /** OfferingType enum name. */
    val offeringType: String,
    val name: String,
    val description: String,
    val contextWindowTokens: Int? = null,
    /** JSON array of CapabilityType enum names. */
    val supportedCapabilitiesJson: String,
    val isLocal: Boolean,
    val isAvailable: Boolean,
    val pricingInputTokensPerMillion: Double? = null,
    val pricingOutputTokensPerMillion: Double? = null,
    val latencyScoreMs: Long,
    val discoveredEpochMs: Long,
    val discoverySource: String
)

@Entity(tableName = "user_resource_preferences")
data class UserResourcePreferenceEntity(
    /** ServiceType enum name — natural key (one preferred resource per type). */
    @PrimaryKey val serviceType: String,
    val preferredResourceId: String,
    val preferredResourceName: String,
    val reason: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "resource_records",
    indices = [Index("serviceId"), Index("providerId")]
)
data class ResourceRecordEntity(
    /** Stable ResourceId string (see domain ResourceIdScheme). */
    @PrimaryKey val resourceId: String,
    val providerId: String,
    val serviceId: String,
    /** ResourceType enum name. */
    val resourceType: String,
    /** JSON array of CapabilityType enum names. */
    val capabilitiesJson: String,
    val configurationVersion: Long,
    /** ResourceLifecycleState enum name. */
    val lifecycleState: String,
    val runtimeSupported: Boolean,
    /** HealthStatus enum name. */
    val healthStatus: String,
    val isLocal: Boolean,
    /** JSON object of String→String metadata. */
    val metadataJson: String
)
