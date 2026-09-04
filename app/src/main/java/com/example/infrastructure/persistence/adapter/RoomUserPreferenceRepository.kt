package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.UserPreferenceRepository
import com.example.domain.core.provider.preference.UserResourcePreference
import com.example.domain.core.provider.ServiceType
import com.example.infrastructure.persistence.dao.UserResourcePreferenceDao

class RoomUserPreferenceRepository(private val dao: UserResourcePreferenceDao) : UserPreferenceRepository {
    
    override suspend fun savePreference(preference: UserResourcePreference): Long {
        return 0L
    }

    override suspend fun getPreference(serviceType: ServiceType): UserResourcePreference? {
        return null
    }

    override suspend fun getAllPreferences(): List<UserResourcePreference> {
        return emptyList()
    }

    override suspend fun updatePreference(preference: UserResourcePreference) {
    }

    override suspend fun deletePreference(serviceType: ServiceType) {
    }
}
