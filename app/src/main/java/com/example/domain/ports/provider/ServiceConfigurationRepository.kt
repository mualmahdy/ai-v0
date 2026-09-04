package com.example.domain.ports.provider

import com.example.domain.core.provider.ServiceConfiguration

interface ServiceConfigurationRepository {
    suspend fun saveConfiguration(config: ServiceConfiguration): Long
    suspend fun getConfigurationById(id: Long): ServiceConfiguration?
    suspend fun getConfigurationsByServiceId(serviceId: Long): List<ServiceConfiguration>
    suspend fun getAllConfigurations(): List<ServiceConfiguration>
    suspend fun updateConfiguration(config: ServiceConfiguration)
    suspend fun deleteConfiguration(id: Long)
}
