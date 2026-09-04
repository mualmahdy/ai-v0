package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ProviderServiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderServiceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderServiceEntity)

    @Query("SELECT * FROM provider_services")
    fun observeAll(): Flow<List<ProviderServiceEntity>>

    @Query("SELECT * FROM provider_services WHERE id = :id")
    suspend fun getById(id: String): ProviderServiceEntity?

    @Query("SELECT * FROM provider_services WHERE providerId = :providerId")
    suspend fun getByProviderId(providerId: String): List<ProviderServiceEntity>

    @Query("DELETE FROM provider_services WHERE id = :id")
    suspend fun deleteById(id: String)
}
