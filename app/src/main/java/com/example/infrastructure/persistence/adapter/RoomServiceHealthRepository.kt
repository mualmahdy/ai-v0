package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.ServiceHealthRepository
import com.example.domain.core.provider.ServiceHealth
import com.example.infrastructure.persistence.dao.ServiceHealthRecordDao

class RoomServiceHealthRepository(private val dao: ServiceHealthRecordDao) : ServiceHealthRepository {
    
    override suspend fun saveHealthRecord(health: ServiceHealth): Long {
        return 0L
    }

    override suspend fun getLatestHealthByServiceId(serviceId: Long): ServiceHealth? {
        return null
    }

    override suspend fun getHealthHistoryByServiceId(serviceId: Long, limit: Int): List<ServiceHealth> {
        return emptyList()
    }

    override suspend fun getAllHealthRecords(): List<ServiceHealth> {
        return emptyList()
    }

    override suspend fun updateHealthRecord(health: ServiceHealth) {
    }

    override suspend fun deleteHealthRecord(id: Long) {
    }
}
