package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ServiceOfferingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServiceOfferingDao {
    /** REPLACE on composite PK (id, serviceId) — same model id on a different service stays distinct. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServiceOfferingEntity)

    @Query("SELECT * FROM service_offerings")
    fun observeAll(): Flow<List<ServiceOfferingEntity>>

    @Query("SELECT * FROM service_offerings WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServiceOfferingEntity?

    @Query("SELECT * FROM service_offerings WHERE serviceId = :serviceId")
    suspend fun getByServiceId(serviceId: String): List<ServiceOfferingEntity>

    @Query("DELETE FROM service_offerings WHERE serviceId = :serviceId")
    suspend fun deleteByServiceId(serviceId: String)
}
