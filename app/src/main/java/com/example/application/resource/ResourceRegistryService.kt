package com.example.application.resource

import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative system registry managing the identity, lifecycle, configuration
 * versions, and health of all runtime resources.
 *
 * Per the architectural plan (Section 11 + Phase 4): `ResourceRegistryService`
 * is the single operational authority for resource identity and lifecycle.
 *
 * This base class is the in-memory implementation. `DurableResourceRegistryService`
 * extends it and adds persistence (Room-backed). There is exactly one instance
 * in the application graph (the durable one) — both `RuntimeAdapterResolver` and
 * `ResourceCapabilityGraph` reference it via this base type.
 */
open class ResourceRegistryService {

    protected val resources = ConcurrentHashMap<ResourceId, ResourceRecord>()
    protected val _resourcesFlow = MutableStateFlow<List<ResourceRecord>>(emptyList())
    val resourcesFlow: StateFlow<List<ResourceRecord>> = _resourcesFlow.asStateFlow()

    open fun registerResource(record: ResourceRecord) {
        resources[record.resourceId] = record
        _resourcesFlow.value = resources.values.toList()
    }

    open fun updateResource(record: ResourceRecord) {
        resources[record.resourceId] = record
        _resourcesFlow.value = resources.values.toList()
    }

    open fun updateHealth(resourceId: ResourceId, healthStatus: HealthStatus) {
        val existing = resources[resourceId] ?: return
        val updated = existing.copy(healthStatus = healthStatus)
        resources[resourceId] = updated
        _resourcesFlow.value = resources.values.toList()
    }

    open fun updateRuntimeState(
        resourceId: ResourceId,
        lifecycleState: ResourceLifecycleState,
        runtimeSupported: Boolean,
        healthStatus: HealthStatus
    ) {
        val existing = resources[resourceId] ?: return
        val updated = existing.copy(
            lifecycleState = lifecycleState,
            runtimeSupported = runtimeSupported,
            healthStatus = healthStatus
        )
        resources[resourceId] = updated
        _resourcesFlow.value = resources.values.toList()
    }

    open fun unregisterResource(resourceId: ResourceId) {
        resources.remove(resourceId)
        _resourcesFlow.value = resources.values.toList()
    }

    open fun getResource(resourceId: ResourceId): ResourceRecord? = resources[resourceId]

    open fun listResources(): List<ResourceRecord> = resources.values.toList()

    open fun clear() {
        resources.clear()
        _resourcesFlow.value = emptyList()
    }
}
