package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ServiceHealthRecordEntity

@Dao
interface ServiceHealthRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServiceHealthRecordEntity)

    @Query(
        "SELECT * FROM service_health_records WHERE serviceConfigurationId = :configId " +
            "ORDER BY validatedAtEpochMs DESC LIMIT 1"
    )
    suspend fun getLatestForConfiguration(configId: String): ServiceHealthRecordEntity?

    @Query(
        "SELECT * FROM service_health_records WHERE serviceConfigurationId = :configId " +
            "ORDER BY validatedAtEpochMs DESC LIMIT :limit"
    )
    suspend fun getHistoryForConfiguration(configId: String, limit: Int): List<ServiceHealthRecordEntity>
}
