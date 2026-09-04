package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ResourceRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ResourceRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ResourceRecordEntity)

    @Query("SELECT * FROM resource_records")
    fun observeAll(): Flow<List<ResourceRecordEntity>>

    @Query("SELECT * FROM resource_records")
    suspend fun getAll(): List<ResourceRecordEntity>

    @Query("SELECT * FROM resource_records WHERE resourceId = :resourceId")
    suspend fun getById(resourceId: String): ResourceRecordEntity?

    @Query(
        "UPDATE resource_records SET lifecycleState = :lifecycleState, " +
            "runtimeSupported = :runtimeSupported, healthStatus = :healthStatus " +
            "WHERE resourceId = :resourceId"
    )
    suspend fun updateRuntimeState(
        resourceId: String,
        lifecycleState: String,
        runtimeSupported: Boolean,
        healthStatus: String
    )

    @Query("DELETE FROM resource_records WHERE resourceId = :resourceId")
    suspend fun deleteById(resourceId: String)

    @Query("DELETE FROM resource_records WHERE serviceId = :serviceId")
    suspend fun deleteByServiceId(serviceId: String)
}
