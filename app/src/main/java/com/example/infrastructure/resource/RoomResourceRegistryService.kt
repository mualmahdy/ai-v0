package com.example.infrastructure.resource

import com.example.domain.core.resource.ConfigurationVersion
import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.RegistryResult
import com.example.domain.core.resource.ResourceCategory
import com.example.domain.core.resource.ResourceChangeEvent
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceRecordInput
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.ServiceId
import com.example.domain.core.resource.UsableResource
import com.example.domain.ports.resource.ResourceCooldownChecker
import com.example.domain.ports.resource.ResourceRegistryService
import com.example.domain.ports.resource.RuntimeSupportToken
import com.example.infrastructure.persistence.dao.ResourceRecordDao
import com.example.infrastructure.persistence.entities.ResourceRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * P0.2 — Room-backed ResourceRegistryService implementation
 * (APPROVED-BASELINE v2.1, Section D — LOCKED).
 *
 * Contract compliance:
 * - RULE REG-1 / REG-4 / ID-7: (providerId, serviceId) is the logical resource key.
 *   One ResourceRecord per logical key. Duplicates are REJECTED — enforced by a
 *   registry-level mutex + lookup and by a UNIQUE database index (no overwrite,
 *   no silent merge).
 * - RULE ID-1/2/3: bumpConfigurationVersion changes version only, never resourceId.
 * - RULE ID-8: ResourceId is never mutated after registration.
 * - setRuntimeSupported: callable only with the single control-plane
 *   RuntimeSupportToken (P0 "simple check", Section K / P0.2).
 * - queryUsableByCapability: exact LOCKED conjunction — lifecycle == HEALTHY AND
 *   runtimeSupported == true AND NOT in cooldown (cooldown consulted via
 *   [cooldownChecker]; the registry never computes health itself).
 * - Emits ResourceChangeEvent flow for CapabilityResourceGraph and listeners.
 */
class RoomResourceRegistryService(
    private val dao: ResourceRecordDao,
    private val cooldownChecker: ResourceCooldownChecker,
    private val now: () -> Long = { System.currentTimeMillis() }
) : ResourceRegistryService {

    private val mutex = Mutex()

    private val _changes = MutableSharedFlow<ResourceChangeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override fun observeResourceChanges(): Flow<ResourceChangeEvent> = _changes.asSharedFlow()

    override suspend fun register(record: ResourceRecordInput): RegistryResult {
        // Logical identity normalization: logical key comparisons are case-sensitive
        // by contract silence, but provider ids in this codebase are lower-cased by
        // ComponentRegistry; we keep the raw values and rely on exact matching.
        return mutex.withLock {
            val existing = dao.getByLogicalKey(record.providerId.value, record.serviceId.value)
            if (existing != null) {
                return@withLock RegistryResult.RejectedDuplicate(
                    ResourceId(existing.resourceId)
                )
            }
            val timestamp = now()
            val resourceId = deriveResourceId(record)
            if (dao.getByResourceId(resourceId.value) != null) {
                // Same derived id exists under a different logical key — treat as duplicate
                // registration attempt (identity collision), never overwrite (RULE ID-8).
                return@withLock RegistryResult.RejectedDuplicate(resourceId)
            }
            val entity = ResourceRecordEntity(
                resourceId = resourceId.value,
                providerId = record.providerId.value,
                serviceId = record.serviceId.value,
                resourceType = record.resourceType.code,
                category = record.category.code,
                capabilitiesJson = JSONArray(record.capabilities).toString(),
                lifecycleState = ResourceLifecycleState.CONFIGURED.code,
                runtimeSupported = false,
                configurationVersion = ConfigurationVersion.INITIAL.value,
                isFallback = record.isFallback,
                registeredAt = timestamp,
                lastStateChangeAt = timestamp,
                metadataJson = JSONObject(record.metadata).toString()
            )
            return@withLock try {
                dao.insert(entity)
                val persisted = requireNotNull(dao.getByResourceId(resourceId.value))
                val domainRecord = persisted.toDomain()
                _changes.tryEmit(ResourceChangeEvent.Registered(domainRecord))
                RegistryResult.Success
            } catch (e: Exception) {
                // Unique index violation => duplicate logical key (REG-1/REG-4).
                RegistryResult.RejectedDuplicate(resourceId)
            }
        }
    }

    override suspend fun get(resourceId: ResourceId): ResourceRecord? =
        dao.getByResourceId(resourceId.value)?.toDomain()

    override suspend fun getByLogicalKey(providerId: ProviderId, serviceId: ServiceId): ResourceRecord? =
        dao.getByLogicalKey(providerId.value, serviceId.value)?.toDomain()

    override suspend fun getAll(): List<ResourceRecord> =
        dao.getAll().map { it.toDomain() }

    override suspend fun bumpConfigurationVersion(resourceId: ResourceId): RegistryResult = mutex.withLock {
        val existing = dao.getByResourceId(resourceId.value)
            ?: return@withLock RegistryResult.NotFound(resourceId)
        // RULE ID-1/2/3: version++ with ResourceId unchanged (the WHERE clause keys on resourceId).
        val updatedRows = dao.bumpConfigurationVersion(resourceId.value, now())
        if (updatedRows == 0) return@withLock RegistryResult.NotFound(resourceId)
        val newVersion = ConfigurationVersion(existing.configurationVersion + 1)
        _changes.tryEmit(ResourceChangeEvent.ConfigurationBumped(resourceId, newVersion))
        RegistryResult.Success
    }

    override suspend fun setLifecycleState(
        resourceId: ResourceId,
        newState: ResourceLifecycleState
    ): RegistryResult = mutex.withLock {
        val existing = dao.getByResourceId(resourceId.value)
            ?: return@withLock RegistryResult.NotFound(resourceId)
        val currentState = ResourceLifecycleState.fromCode(existing.lifecycleState)
            ?: return@withLock RegistryResult.Error(IllegalStateException("Corrupted lifecycle state: ${existing.lifecycleState}"))
        if (currentState == newState) {
            return@withLock RegistryResult.Success // no-op transition
        }
        if (!isTransitionAllowed(currentState, newState)) {
            return@withLock RegistryResult.RejectedInvalidTransition
        }
        val updatedRows = dao.updateLifecycleState(resourceId.value, newState.code, now())
        if (updatedRows == 0) return@withLock RegistryResult.NotFound(resourceId)
        _changes.tryEmit(ResourceChangeEvent.LifecycleChanged(resourceId, currentState, newState))
        RegistryResult.Success
    }

    override suspend fun queryUsableByCapability(capabilityId: String): List<UsableResource> {
        // LOCKED usable conjunction (Section E):
        //   lifecycle == HEALTHY AND runtimeSupported == true AND NOT in cooldown.
        // The first two conjuncts are enforced by the DAO query below.
        val candidates = dao.getByLifecycleAndRuntimeSupport(ResourceLifecycleState.HEALTHY.code)
        val usable = mutableListOf<UsableResource>()
        for (entity in candidates) {
            val record = entity.toDomain()
            if (!record.capabilities.any { it.equals(capabilityId, ignoreCase = true) }) continue
            if (cooldownChecker.isInCooldown(record.resourceId)) continue
            usable.add(record.toUsable())
        }
        return usable
    }

    override suspend fun setRuntimeSupported(
        resourceId: ResourceId,
        supported: Boolean,
        token: RuntimeSupportToken
    ): RegistryResult {
        // P0 simple-check enforcement: only the token instance issued to the control
        // plane at composition time is accepted.
        if (token !== controlPlaneToken) {
            return RegistryResult.Error(
                SecurityException("setRuntimeSupported is restricted to the ProviderControlPlaneService pathway")
            )
        }
        return mutex.withLock {
            val existing = dao.getByResourceId(resourceId.value)
                ?: return@withLock RegistryResult.NotFound(resourceId)
            if (existing.runtimeSupported == supported) return@withLock RegistryResult.Success
            val updatedRows = dao.updateRuntimeSupported(resourceId.value, supported, now())
            if (updatedRows == 0) return@withLock RegistryResult.NotFound(resourceId)
            _changes.tryEmit(ResourceChangeEvent.RuntimeSupportChanged(resourceId, supported))
            RegistryResult.Success
        }
    }

    /**
     * Locks this registry instance to a specific control-plane token.
     * Called exactly once by the composition root right after construction and
     * before any control-plane call. A second call is rejected (defensive).
     */
    @Volatile
    private var controlPlaneToken: RuntimeSupportToken? = null
    fun bindControlPlaneToken(token: RuntimeSupportToken) {
        check(controlPlaneToken == null) { "Control-plane token already bound for this registry" }
        controlPlaneToken = token
    }

    /**
     * RULE ID-6: the ResourceId string format is an implementation detail.
     * Derived deterministically from the logical key so migration (RULE REG-3) is
     * idempotent across restarts. It NEVER encodes endpoint, key, or adapter details.
     */
    private fun deriveResourceId(record: ResourceRecordInput): ResourceId {
        val sanitized = "${record.providerId.value}__${record.serviceId.value}"
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return ResourceId("res_$sanitized")
    }

    /**
     * Section E — Lifecycle Transition Rules (LOCKED table) plus the wildcard
     * re-validation row (* -> CONFIGURED). DISABLED resources can only return
     * through explicit re-validation (DISABLED -> CONFIGURED).
     */
    private fun isTransitionAllowed(from: ResourceLifecycleState, to: ResourceLifecycleState): Boolean {
        return when (from) {
            ResourceLifecycleState.CONFIGURED ->
                to == ResourceLifecycleState.VALIDATING || to == ResourceLifecycleState.DISABLED
            ResourceLifecycleState.VALIDATING ->
                to == ResourceLifecycleState.HEALTHY ||
                    to == ResourceLifecycleState.CONFIGURED ||
                    to == ResourceLifecycleState.DISABLED
            ResourceLifecycleState.HEALTHY ->
                to == ResourceLifecycleState.UNAVAILABLE ||
                    to == ResourceLifecycleState.DISABLED ||
                    to == ResourceLifecycleState.CONFIGURED
            ResourceLifecycleState.UNAVAILABLE ->
                to == ResourceLifecycleState.HEALTHY ||
                    to == ResourceLifecycleState.DISABLED ||
                    to == ResourceLifecycleState.CONFIGURED
            ResourceLifecycleState.DISABLED ->
                to == ResourceLifecycleState.CONFIGURED // re-enable via re-validation request
        }
    }
}

/** Entity -> domain mapper. */
internal fun ResourceRecordEntity.toDomain(): ResourceRecord {
    val capabilities = mutableListOf<String>()
    runCatching {
        val array = JSONArray(this.capabilitiesJson)
        for (i in 0 until array.length()) capabilities.add(array.getString(i))
    }
    val metadata = mutableMapOf<String, String>()
    runCatching {
        val obj = JSONObject(this.metadataJson)
        for (key in obj.keys()) metadata[key] = obj.optString(key)
    }
    return ResourceRecord(
        resourceId = ResourceId(this.resourceId),
        providerId = ProviderId(this.providerId),
        serviceId = ServiceId(this.serviceId),
        resourceType = ResourceType.fromCode(this.resourceType) ?: ResourceType.LLM,
        category = ResourceCategory.fromCode(this.category) ?: ResourceCategory.REMOTE,
        capabilities = capabilities,
        lifecycleState = ResourceLifecycleState.fromCode(this.lifecycleState)
            ?: ResourceLifecycleState.CONFIGURED,
        runtimeSupported = this.runtimeSupported,
        configurationVersion = ConfigurationVersion(this.configurationVersion),
        isFallback = this.isFallback,
        registeredAt = this.registeredAt,
        lastStateChangeAt = this.lastStateChangeAt,
        metadata = metadata
    )
}

/** Domain -> usable projection mapper. */
internal fun ResourceRecord.toUsable(): UsableResource = UsableResource(
    resourceId = resourceId,
    providerId = providerId,
    serviceId = serviceId,
    resourceType = resourceType,
    category = category,
    capabilities = capabilities,
    configurationVersion = configurationVersion,
    isFallback = isFallback
)
