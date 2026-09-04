package com.example.infrastructure.persistence.dao

import androidx.room.*

@Dao
interface UserResourcePreferenceDao {
    @Query("SELECT * FROM user_resource_preferences")
    suspend fun getAll(): List<*>

    @Query("SELECT * FROM user_resource_preferences WHERE serviceType = :serviceType")
    suspend fun getByServiceType(serviceType: String): Any?
}
