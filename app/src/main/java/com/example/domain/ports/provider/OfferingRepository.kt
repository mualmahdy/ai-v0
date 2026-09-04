package com.example.domain.ports.provider

import com.example.domain.core.provider.ServiceOffering

interface OfferingRepository {
    suspend fun saveOffering(offering: ServiceOffering): Long
    suspend fun getOfferingById(id: Long): ServiceOffering?
    suspend fun getOfferingsByServiceId(serviceId: Long): List<ServiceOffering>
    suspend fun getAllOfferings(): List<ServiceOffering>
    suspend fun updateOffering(offering: ServiceOffering)
    suspend fun deleteOffering(id: Long)
}
