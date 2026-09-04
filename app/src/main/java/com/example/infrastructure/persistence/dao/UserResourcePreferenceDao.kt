package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.UserResourcePreferenceEntity

@Dao
interface UserResourcePreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserResourcePreferenceEntity)

    @Query("SELECT * FROM user_resource_preferences")
    suspend fun getAll(): List<UserResourcePreferenceEntity>

    @Query("SELECT * FROM user_resource_preferences WHERE serviceType = :serviceType")
    suspend fun getByServiceType(serviceType: String): UserResourcePreferenceEntity?

    @Query("DELETE FROM user_resource_preferences WHERE serviceType = :serviceType")
    suspend fun deleteByServiceType(serviceType: String)
}
