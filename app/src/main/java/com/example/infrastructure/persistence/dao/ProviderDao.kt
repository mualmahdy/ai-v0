package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderEntity)

    @Query("SELECT * FROM providers")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)
}
