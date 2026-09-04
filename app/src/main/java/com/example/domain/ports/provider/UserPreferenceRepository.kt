package com.example.domain.ports.provider

import com.example.domain.core.provider.preference.UserResourcePreference
import com.example.domain.core.provider.ServiceType

interface UserPreferenceRepository {
    suspend fun savePreference(preference: UserResourcePreference): Long
    suspend fun getPreference(serviceType: ServiceType): UserResourcePreference?
    suspend fun getAllPreferences(): List<UserResourcePreference>
    suspend fun updatePreference(preference: UserResourcePreference)
    suspend fun deletePreference(serviceType: ServiceType)
}
