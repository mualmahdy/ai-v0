package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.ProviderRepository
import com.example.domain.core.provider.Provider
import com.example.infrastructure.persistence.dao.ProviderDao

class RoomProviderRepository(private val providerDao: ProviderDao) : ProviderRepository {
    
    override suspend fun saveProvider(provider: Provider): Long {
        // Map Provider domain model to ProviderConfigEntity and persist
        return 0L
    }

    override suspend fun getProviderById(id: Long): Provider? {
        // Query and map to domain model
        return null
    }

    override suspend fun getAllProviders(): List<Provider> {
        return emptyList()
    }

    override suspend fun updateProvider(provider: Provider) {
        // Update logic
    }

    override suspend fun deleteProvider(id: Long) {
        // Delete logic
    }

    override suspend fun getProviderByName(name: String): Provider? {
        return null
    }
}
