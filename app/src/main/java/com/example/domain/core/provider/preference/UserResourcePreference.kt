package com.example.domain.core.provider.preference

import com.example.domain.core.resource.ResourceId
import com.example.domain.core.provider.ServiceType

/**
 * UserResourcePreference — Phase 4 (per Section 17)
 * 
 * User's preferred resource for a given service type.
 * This is a PLANNING HINT only — it does NOT:
 *   - execute a resource
 *   - bypass DecisionService
 *   - bypass ResourceRegistry
 *   - bypass governance
 *   - bypass health checks
 *   - substitute for DecisionRecord
 */
data class UserResourcePreference(
    val id: Long? = null,
    val serviceType: ServiceType,
    val preferredResourceId: ResourceId,
    val preferredResourceName: String = "",
    val reason: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
