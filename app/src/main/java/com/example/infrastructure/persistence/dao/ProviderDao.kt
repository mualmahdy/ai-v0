package com.example.infrastructure.persistence.dao

import androidx.room.*
import com.example.infrastructure.persistence.entities.ProviderConfigEntity

@Dao
interface ProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProviderConfigEntity): Long

    @Update
    suspend fun update(entity: ProviderConfigEntity)

    @Delete
    suspend fun delete(entity: ProviderConfigEntity)

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    suspend fun getById(id: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs")
    suspend fun getAll(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE name = :name")
    suspend fun getByName(name: String): ProviderConfigEntity?
}
