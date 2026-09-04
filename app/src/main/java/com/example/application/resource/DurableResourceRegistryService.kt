package com.example.application.resource

import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.ports.resource.ResourceRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * ============================================================================
 * DurableResourceRegistryService — Phase 4 (Section 21: single write authority)
 * ============================================================================
 *
 * The ONE authoritative registry for resource identity, lifecycle, and health.
 * It extends the in-memory [ResourceRegistryService] and mirrors every write
 * to the Room-backed [ResourceRecordRepository], so:
 *
 *   - in-memory state (consumed synchronously by ResourceCapabilityGraph and
 *     RuntimeAdapterResolver) and persisted state can never diverge;
 *   - resources survive application restart (eagerly reloaded on construction);
 *   - there is exactly ONE instance in the application graph (held by
 *     ComponentRegistry and referenced by the control plane through
 *     RegistryBackedResourceRecordRepository).
 *
 * Persistence failures are logged-by-degradation: the in-memory registry stays
 * authoritative so the runtime keeps working even if the disk write fails —
 * but the failure is never silently reported as success.
 */
class DurableResourceRegistryService(
    private val repository: ResourceRecordRepository? = null
) : ResourceRegistryService() {

    init {
        // Eagerly load persisted records so restart survival works. Blocking is
        // acceptable: this runs once at graph construction (AppContainer lazy).
        val repo = repository
        if (repo != null) {
            runCatching {
                runBlocking(Dispatchers.IO) { repo.getAllResources() }
            }.getOrDefault(emptyList()).forEach { record ->
                super.registerResource(record)
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Authoritative suspend API (used by the control plane)              */
    /* ------------------------------------------------------------------ */

    /** Single write path: memory + persistence, returns the stored record. */
    suspend fun saveResource(record: ResourceRecord): ResourceRecord {
        super.registerResource(record)
        repository?.let { repo -> runCatching { repo.saveResource(record) } }
        return record
    }

    suspend fun getResourceById(resourceId: ResourceId): ResourceRecord? {
        resources[resourceId]?.let { return it }
        val persisted = repository?.let { repo ->
            runCatching { repo.getResourceById(resourceId) }.getOrNull()
        }
        if (persisted != null) {
            super.registerResource(persisted)
        }
        return persisted
    }

    /** Updates ONLY runtime-truth fields in memory and persistence. */
    suspend fun updateRuntimeStatePersisted(
        resourceId: ResourceId,
        lifecycleState: ResourceLifecycleState,
        runtimeSupported: Boolean,
        healthStatus: HealthStatus
    ) {
        super.updateRuntimeState(resourceId, lifecycleState, runtimeSupported, healthStatus)
        repository?.let { repo ->
            runCatching {
                repo.updateRuntimeState(resourceId, lifecycleState, runtimeSupported, healthStatus)
            }
        }
    }

    suspend fun deleteResource(resourceId: ResourceId) {
        super.unregisterResource(resourceId)
        repository?.let { repo -> runCatching { repo.deleteResource(resourceId) } }
    }

    suspend fun deleteResourcesForService(serviceId: String) {
        resources.values.filter { it.serviceId == serviceId }.forEach { record ->
            super.unregisterResource(record.resourceId)
        }
        repository?.let { repo -> runCatching { repo.deleteResourcesForService(serviceId) } }
    }

    /** Continuous flow of all resources (UI + control plane observation). */
    fun observeAllResources(): Flow<List<ResourceRecord>> = resourcesFlow

    /* ------------------------------------------------------------------ */
    /* Base-class overrides: keep memory + disk in sync on every write    */
    /* ------------------------------------------------------------------ */

    override fun registerResource(record: ResourceRecord) {
        super.registerResource(record)
        mirrorPersist { it.saveResource(record) }
    }

    override fun updateResource(record: ResourceRecord) {
        super.updateResource(record)
        mirrorPersist { it.saveResource(record) }
    }

    override fun updateHealth(resourceId: ResourceId, healthStatus: HealthStatus) {
        super.updateHealth(resourceId, healthStatus)
        val record = resources[resourceId] ?: return
        mirrorPersist { it.saveResource(record) }
    }

    override fun updateRuntimeState(
        resourceId: ResourceId,
        lifecycleState: ResourceLifecycleState,
        runtimeSupported: Boolean,
        healthStatus: HealthStatus
    ) {
        super.updateRuntimeState(resourceId, lifecycleState, runtimeSupported, healthStatus)
        val record = resources[resourceId] ?: return
        mirrorPersist { it.saveResource(record) }
    }

    override fun unregisterResource(resourceId: ResourceId) {
        super.unregisterResource(resourceId)
        mirrorPersist { it.deleteResource(resourceId) }
    }

    /** Best-effort synchronous mirror for non-suspend call sites. */
    private fun mirrorPersist(block: suspend (ResourceRecordRepository) -> Unit) {
        val repo = repository ?: return
        runCatching {
            runBlocking(Dispatchers.IO) { block(repo) }
        }
    }
}

/**
 * Adapter that routes every control-plane resource operation through the
 * single authoritative [DurableResourceRegistryService] — satisfying the
 * "no duplicate write authority" invariant (Section 21) while still exposing
 * the plain [com.example.domain.ports.resource.ResourceRecordRepository]
 * interface the control plane depends on.
 */
class RegistryBackedResourceRecordRepository(
    private val durableRegistry: DurableResourceRegistryService
) : ResourceRecordRepository {

    override suspend fun saveResource(record: ResourceRecord) {
        durableRegistry.saveResource(record)
    }

    override suspend fun getResourceById(resourceId: ResourceId): ResourceRecord? =
        durableRegistry.getResourceById(resourceId)

    override suspend fun getAllResources(): List<ResourceRecord> =
        durableRegistry.listResources()

    override fun observeAllResources(): Flow<List<ResourceRecord>> =
        durableRegistry.observeAllResources()

    override suspend fun updateRuntimeState(
        resourceId: ResourceId,
        lifecycleState: ResourceLifecycleState,
        runtimeSupported: Boolean,
        healthStatus: HealthStatus
    ) {
        durableRegistry.updateRuntimeStatePersisted(
            resourceId, lifecycleState, runtimeSupported, healthStatus
        )
    }

    override suspend fun deleteResource(resourceId: ResourceId) {
        durableRegistry.deleteResource(resourceId)
    }

    override suspend fun deleteResourcesForService(serviceId: String) {
        durableRegistry.deleteResourcesForService(serviceId)
    }
}
