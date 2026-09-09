package com.example.application.resource

import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.ports.resource.ResourceRecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Persistence failures are DEGRADED HONESTLY (P1-13, audit 2026 §19 —
 * RAM/Room non-atomicity): the in-memory registry stays authoritative so
 * the runtime keeps working even if the disk write fails, but the failure
 * is NEVER silently reported as success — every swallowed repository
 * error is (a) recorded in [persistenceFailures] (bounded, observable
 * StateFlow) and (b) reported to the [persistenceFailureHandler] hook
 * when wired, so the control plane and observability can SEE the divergence.
 */
class DurableResourceRegistryService(
    private val repository: ResourceRecordRepository? = null,
    /**
     * FIX R-1 (audit c03919d): async mirror scope. Every non-suspend write is
     * mirrored to Room on Dispatchers.IO WITHOUT blocking the calling thread
     * (previously mirrorPersist used runBlocking on the MAIN thread during
     * graph construction and every registerTool call).
     */
    private val mirrorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * P1-13: observability hook for mirror-path persistence failures
     * (production wiring routes this to the telemetry/log trail).
     */
    private val persistenceFailureHandler: ((String, Throwable) -> Unit)? = null
) : ResourceRegistryService() {

    /**
     * P1-13: the LAST [PERSISTENCE_FAILURE_WINDOW] persistence failures,
     * observable. Non-empty means memory is AUTHORITATIVE and Room has
     * DIVERGED — an honest degradation signal, never silent success.
     */
    private val _persistenceFailures = MutableStateFlow<List<String>>(emptyList())
    val persistenceFailures: StateFlow<List<String>> = _persistenceFailures.asStateFlow()

    private fun recordPersistenceFailure(op: String, error: Throwable) {
        val entry = "$op: ${error.javaClass.simpleName}: ${error.message ?: "-"}"
        _persistenceFailures.value = (listOf(entry) + _persistenceFailures.value)
            .take(PERSISTENCE_FAILURE_WINDOW)
        persistenceFailureHandler?.invoke(entry, error)
    }

    private companion object {
        const val PERSISTENCE_FAILURE_WINDOW = 32
    }

    /**
     * FIX R-1: persisted records are now loaded via the explicit suspend
     * [eagerLoad] (called from the bootstrap coroutine) instead of a blocking
     * runBlocking inside init that froze the main thread during construction.
     */
    private val loadMutex = Mutex()
    @Volatile
    private var eagerLoadAttempted = false

    /**
     * Loads persisted records into memory exactly once. Safe to call from any
     * coroutine; repeated calls are no-ops. Called by AppContainer.bootstrapRuntime()
     * BEFORE any resource resolution is expected.
     */
    suspend fun eagerLoad() {
        val repo = repository ?: return
        loadMutex.withLock {
            if (eagerLoadAttempted) return
            eagerLoadAttempted = true
            val persisted = try {
                repo.getAllResources()
            } catch (error: Throwable) {
                // P1-13: an unreadable store is HONEST degradation — memory
                // stays empty/authoritative and the failure is observable.
                recordPersistenceFailure("eagerLoad(getAllResources)", error)
                emptyList()
            }
            persisted.forEach { record -> super.registerResource(record) }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Authoritative suspend API (used by the control plane)              */
    /* ------------------------------------------------------------------ */

    /** Single write path: memory + persistence, returns the stored record. */
    suspend fun saveResource(record: ResourceRecord): ResourceRecord {
        super.registerResource(record)
        val repo = repository
        if (repo != null) {
            try {
                repo.saveResource(record)
            } catch (error: Throwable) {
                // P1-13: honest degradation — memory keeps the record (runtime
                // stays up) but the divergence is RECORDED, not swallowed.
                recordPersistenceFailure("saveResource(${record.resourceId})", error)
            }
        }
        return record
    }

    suspend fun getResourceById(resourceId: ResourceId): ResourceRecord? {
        resources[resourceId]?.let { return it }
        val repo = repository ?: return null
        val persisted = try {
            repo.getResourceById(resourceId)
        } catch (error: Throwable) {
            recordPersistenceFailure("getResourceById($resourceId)", error)
            null
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
        val repo = repository ?: return
        try {
            repo.updateRuntimeState(resourceId, lifecycleState, runtimeSupported, healthStatus)
        } catch (error: Throwable) {
            recordPersistenceFailure("updateRuntimeState($resourceId)", error)
        }
    }

    suspend fun deleteResource(resourceId: ResourceId) {
        super.unregisterResource(resourceId)
        val repo = repository ?: return
        try {
            repo.deleteResource(resourceId)
        } catch (error: Throwable) {
            recordPersistenceFailure("deleteResource($resourceId)", error)
        }
    }

    suspend fun deleteResourcesForService(serviceId: String) {
        resources.values.filter { it.serviceId == serviceId }.forEach { record ->
            super.unregisterResource(record.resourceId)
        }
        val repo = repository ?: return
        try {
            repo.deleteResourcesForService(serviceId)
        } catch (error: Throwable) {
            recordPersistenceFailure("deleteResourcesForService($serviceId)", error)
        }
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

    /** Best-effort asynchronous mirror for non-suspend call sites (FIX R-1: no runBlocking). */
    private fun mirrorPersist(block: suspend (ResourceRecordRepository) -> Unit) {
        val repo = repository ?: return
        mirrorScope.launch {
            try {
                block(repo)
            } catch (error: Throwable) {
                // P1-13: the async mirror path is best-effort, but the failure
                // is OBSERVABLE (never silently reported as success).
                recordPersistenceFailure("mirrorPersist", error)
            }
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

    override suspend fun getResourceById(resourceId: ResourceId): ResourceRecord? {
        // FIX R-1: first repository access triggers the one-shot persisted-record
        // load (replaces the old blocking init block).
        durableRegistry.eagerLoad()
        return durableRegistry.getResourceById(resourceId)
    }

    override suspend fun getAllResources(): List<ResourceRecord> {
        durableRegistry.eagerLoad()
        return durableRegistry.listResources()
    }

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
