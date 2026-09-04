package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ServiceConfigurationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServiceConfigurationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServiceConfigurationEntity)

    @Query("SELECT * FROM service_configurations")
    fun observeAll(): Flow<List<ServiceConfigurationEntity>>

    @Query("SELECT * FROM service_configurations WHERE id = :id")
    suspend fun getById(id: String): ServiceConfigurationEntity?

    @Query(
        "SELECT * FROM service_configurations WHERE serviceId = :serviceId " +
            "ORDER BY updatedAtEpochMs DESC LIMIT 1"
    )
    suspend fun getLatestByServiceId(serviceId: String): ServiceConfigurationEntity?

    @Query("DELETE FROM service_configurations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM service_configurations WHERE serviceId = :serviceId")
    suspend fun deleteByServiceId(serviceId: String)
}
