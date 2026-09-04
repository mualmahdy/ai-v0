package com.example.domain.ports.provider

import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.preference.UserResourcePreference

/**
 * ============================================================================
 * UserPreferenceRepository — Phase 4 canonical port (Section 17)
 * ============================================================================
 *
 * Persists the user's preferred resource per ServiceType. Preferences are
 * PLANNING HINTS ONLY — they boost candidate scoring inside DecisionService
 * and must never bypass the decision/execution authority chain.
 */
interface UserPreferenceRepository {
    /** Insert-or-replace keyed by serviceType. */
    suspend fun setPreference(preference: UserResourcePreference)

    suspend fun getPreference(serviceType: ServiceType): UserResourcePreference?

    suspend fun getAllPreferences(): List<UserResourcePreference>

    suspend fun deletePreference(serviceType: ServiceType)
}
