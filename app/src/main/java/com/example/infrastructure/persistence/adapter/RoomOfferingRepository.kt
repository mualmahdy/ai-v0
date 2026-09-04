package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.OfferingRepository
import com.example.domain.core.provider.ServiceOffering
import com.example.infrastructure.persistence.dao.ServiceOfferingDao

class RoomOfferingRepository(private val dao: ServiceOfferingDao) : OfferingRepository {
    
    override suspend fun saveOffering(offering: ServiceOffering): Long {
        return 0L
    }

    override suspend fun getOfferingById(id: Long): ServiceOffering? {
        return null
    }

    override suspend fun getOfferingsByServiceId(serviceId: Long): List<ServiceOffering> {
        return emptyList()
    }

    override suspend fun getAllOfferings(): List<ServiceOffering> {
        return emptyList()
    }

    override suspend fun updateOffering(offering: ServiceOffering) {
    }

    override suspend fun deleteOffering(id: Long) {
    }
}
