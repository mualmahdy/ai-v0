package com.example.application.resource

import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative system registry managing the identity, lifecycle, configuration versions,
 * and health of all runtime resources.
 */
class ResourceRegistryService {

    private val resources = ConcurrentHashMap<ResourceId, ResourceRecord>()
    private val _resourcesFlow = MutableStateFlow<List<ResourceRecord>>(emptyList())
    val resourcesFlow: StateFlow<List<ResourceRecord>> = _resourcesFlow.asStateFlow()

    fun registerResource(record: ResourceRecord) {
        resources[record.resourceId] = record
        _resourcesFlow.value = resources.values.toList()
    }

    fun updateResource(record: ResourceRecord) {
        resources[record.resourceId] = record
        _resourcesFlow.value = resources.values.toList()
    }

    fun updateHealth(resourceId: ResourceId, healthStatus: com.example.domain.core.provider.HealthStatus) {
        val existing = resources[resourceId] ?: return
        val updated = existing.copy(healthStatus = healthStatus)
        resources[resourceId] = updated
        _resourcesFlow.value = resources.values.toList()
    }

    fun unregisterResource(resourceId: ResourceId) {
        resources.remove(resourceId)
        _resourcesFlow.value = resources.values.toList()
    }

    fun getResource(resourceId: ResourceId): ResourceRecord? = resources[resourceId]

    fun listResources(): List<ResourceRecord> = resources.values.toList()

    fun clear() {
        resources.clear()
        _resourcesFlow.value = emptyList()
    }
}
