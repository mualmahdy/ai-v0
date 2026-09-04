package com.example.domain.ports.provider

import com.example.domain.core.provider.ServiceHealth

interface ServiceHealthRepository {
    suspend fun saveHealthRecord(health: ServiceHealth): Long
    suspend fun getLatestHealthByServiceId(serviceId: Long): ServiceHealth?
    suspend fun getHealthHistoryByServiceId(serviceId: Long, limit: Int = 100): List<ServiceHealth>
    suspend fun getAllHealthRecords(): List<ServiceHealth>
    suspend fun updateHealthRecord(health: ServiceHealth)
    suspend fun deleteHealthRecord(id: Long)
}
