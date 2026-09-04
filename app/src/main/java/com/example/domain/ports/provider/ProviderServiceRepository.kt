package com.example.domain.ports.provider

import com.example.domain.core.provider.ProviderService

interface ProviderServiceRepository {
    suspend fun saveService(service: ProviderService): Long
    suspend fun getServiceById(id: Long): ProviderService?
    suspend fun getServicesByProviderId(providerId: Long): List<ProviderService>
    suspend fun getAllServices(): List<ProviderService>
    suspend fun updateService(service: ProviderService)
    suspend fun deleteService(id: Long)
}
