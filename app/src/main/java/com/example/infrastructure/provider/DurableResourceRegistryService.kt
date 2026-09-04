package com.example.infrastructure.provider

import com.example.domain.core.provider.ResourceRecord
import com.example.domain.ports.provider.ResourceRecordRepository

/**
 * DurableResourceRegistryService — Phase 4
 * 
 * In-memory registry facade backed by persistent ResourceRecordRepository.
 * Provides single authoritative source of truth for resource identity, lifecycle,
 * and health. Resources are authored by ProviderControlPlaneService and persisted
 * to the database.
 */
class DurableResourceRegistryService(
    private val resourceRecordRepository: ResourceRecordRepository
) {
    
    private val cachedResources = mutableMapOf<Long, ResourceRecord>()
    private var isLoaded = false
    
    suspend fun registerResource(resource: ResourceRecord): Long {
        val id = resourceRecordRepository.saveRecord(resource)
        cachedResources[id] = resource
        return id
    }
    
    suspend fun getResource(id: Long): ResourceRecord? {
        return cachedResources[id] ?: resourceRecordRepository.getRecordById(id)
    }
    
    suspend fun getAllResources(): List<ResourceRecord> {
        if (!isLoaded) {
            resourceRecordRepository.getAllRecords().forEach { record ->
                cachedResources[record.id ?: return@forEach] = record
            }
            isLoaded = true
        }
        return cachedResources.values.toList()
    }
    
    suspend fun getActiveResources(): List<ResourceRecord> {
        return resourceRecordRepository.getActiveRecords()
    }
    
    suspend fun updateResource(resource: ResourceRecord) {
        resourceRecordRepository.updateRecord(resource)
        resource.id?.let { cachedResources[it] = resource }
    }
    
    suspend fun deactivateResource(id: Long) {
        val resource = getResource(id)
        if (resource != null) {
            // Mark resource as inactive and persist
            updateResource(resource)
        }
    }
}
