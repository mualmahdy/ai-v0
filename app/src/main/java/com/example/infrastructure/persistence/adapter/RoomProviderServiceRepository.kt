package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.ProviderServiceRepository
import com.example.domain.core.provider.ProviderService
import com.example.infrastructure.persistence.dao.ProviderServiceDao

class RoomProviderServiceRepository(private val serviceDao: ProviderServiceDao) : ProviderServiceRepository {
    
    override suspend fun saveService(service: ProviderService): Long {
        return 0L
    }

    override suspend fun getServiceById(id: Long): ProviderService? {
        return null
    }

    override suspend fun getServicesByProviderId(providerId: Long): List<ProviderService> {
        return emptyList()
    }

    override suspend fun getAllServices(): List<ProviderService> {
        return emptyList()
    }

    override suspend fun updateService(service: ProviderService) {
    }

    override suspend fun deleteService(id: Long) {
    }
}
