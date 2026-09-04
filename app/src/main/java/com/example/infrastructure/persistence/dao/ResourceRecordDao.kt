package com.example.infrastructure.persistence.dao

import androidx.room.*

@Dao
interface ResourceRecordDao {
    @Query("SELECT * FROM resource_records")
    suspend fun getAll(): List<*>

    @Query("SELECT * FROM resource_records WHERE id = :id")
    suspend fun getById(id: Long): Any?

    @Query("SELECT * FROM resource_records WHERE resourceType = :type")
    suspend fun getByType(type: String): List<*>

    @Query("SELECT * FROM resource_records WHERE isActive = 1")
    suspend fun getActive(): List<*>
}
