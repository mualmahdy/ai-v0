package com.example.infrastructure.persistence.dao

import androidx.room.*

@Dao
interface ServiceConfigurationDao {
    @Query("SELECT * FROM service_configurations")
    suspend fun getAll(): List<*>

    @Query("SELECT * FROM service_configurations WHERE id = :id")
    suspend fun getById(id: Long): Any?

    @Query("SELECT * FROM service_configurations WHERE serviceId = :serviceId")
    suspend fun getByServiceId(serviceId: Long): List<*>
}
