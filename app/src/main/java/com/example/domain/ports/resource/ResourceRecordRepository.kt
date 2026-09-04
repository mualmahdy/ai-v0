package com.example.domain.ports.resource

import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceRecord
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * ResourceRecordRepository — Phase 4 canonical port
 * ============================================================================
 *
 * Persistence port for authoritative ResourceRecords. Operates on the RICH
 * domain model (`domain.core.resource.ResourceRecord` with String ResourceId
 * identity) — NOT the deleted Long-id shadow model.
 *
 * Implementations: `RoomResourceRecordRepository` (direct Room persistence)
 * and `RegistryBackedResourceRecordRepository` (routes every write through
 * the single authoritative `DurableResourceRegistryService` so in-memory
 * state and persistence can never diverge — Section 21: single write path).
 */
interface ResourceRecordRepository {

    /** Insert-or-replace keyed by the stable ResourceId. */
    suspend fun saveResource(record: ResourceRecord)

    suspend fun getResourceById(resourceId: ResourceId): ResourceRecord?

    suspend fun getAllResources(): List<ResourceRecord>

    /** Continuous flow of all persisted resources (UI + control plane observation). */
    fun observeAllResources(): Flow<List<ResourceRecord>>

    /** Updates ONLY runtime-truth fields (lifecycle, runtimeSupported, health). */
    suspend fun updateRuntimeState(
        resourceId: ResourceId,
        lifecycleState: ResourceLifecycleState,
        runtimeSupported: Boolean,
        healthStatus: HealthStatus
    )

    suspend fun deleteResource(resourceId: ResourceId)

    /** Cascade delete for all resources materialized from a service. */
    suspend fun deleteResourcesForService(serviceId: String)
}
