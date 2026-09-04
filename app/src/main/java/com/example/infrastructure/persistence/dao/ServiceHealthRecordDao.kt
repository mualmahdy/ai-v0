package com.example.infrastructure.persistence.dao

import androidx.room.*

@Dao
interface ServiceHealthRecordDao {
    @Query("SELECT * FROM service_health_records")
    suspend fun getAll(): List<*>

    @Query("SELECT * FROM service_health_records WHERE id = :id")
    suspend fun getById(id: Long): Any?

    @Query("SELECT * FROM service_health_records WHERE serviceId = :serviceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByServiceId(serviceId: Long): Any?

    @Query("SELECT * FROM service_health_records WHERE serviceId = :serviceId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getHistoryByServiceId(serviceId: Long, limit: Int): List<*>
}
