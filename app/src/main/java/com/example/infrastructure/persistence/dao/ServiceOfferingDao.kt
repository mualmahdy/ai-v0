package com.example.infrastructure.persistence.dao

import androidx.room.*

@Dao
interface ServiceOfferingDao {
    @Query("SELECT * FROM service_offerings")
    suspend fun getAll(): List<*>

    @Query("SELECT * FROM service_offerings WHERE id = :id")
    suspend fun getById(id: Long): Any?

    @Query("SELECT * FROM service_offerings WHERE serviceId = :serviceId")
    suspend fun getByServiceId(serviceId: Long): List<*>
}
