package com.example.infrastructure.persistence.dao

import androidx.room.*

@Dao
interface ProviderServiceDao {
    @Query("SELECT * FROM provider_services")
    suspend fun getAll(): List<*>

    @Query("SELECT * FROM provider_services WHERE id = :id")
    suspend fun getById(id: Long): Any?

    @Query("SELECT * FROM provider_services WHERE providerId = :providerId")
    suspend fun getByProviderId(providerId: Long): List<*>
}
