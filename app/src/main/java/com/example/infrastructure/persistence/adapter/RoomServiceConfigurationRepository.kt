package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.ServiceConfigurationRepository
import com.example.domain.core.provider.ServiceConfiguration
import com.example.infrastructure.persistence.dao.ServiceConfigurationDao
import com.example.infrastructure.persistence.dao.ProviderServiceDao

class RoomServiceConfigurationRepository(
    private val dao: ServiceConfigurationDao,
    private val serviceDao: ProviderServiceDao
) : ServiceConfigurationRepository {
    
    override suspend fun saveConfiguration(config: ServiceConfiguration): Long {
        return 0L
    }

    override suspend fun getConfigurationById(id: Long): ServiceConfiguration? {
        return null
    }

    override suspend fun getConfigurationsByServiceId(serviceId: Long): List<ServiceConfiguration> {
        return emptyList()
    }

    override suspend fun getAllConfigurations(): List<ServiceConfiguration> {
        return emptyList()
    }

    override suspend fun updateConfiguration(config: ServiceConfiguration) {
    }

    override suspend fun deleteConfiguration(id: Long) {
    }
}
