package com.example.domain.ports.radar

import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.CapabilityEvidence
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the Capability & Evolution Radar.
 *
 * The radar's derived state must survive process death / activity
 * recreation — it is a durable domain subsystem, not UI state. Room
 * implements this port; tests use in-memory fakes.
 */
interface CapabilityRadarPersistencePort {

    // ---- Evidence store (append-only) ----
    suspend fun insertEvidence(evidence: CapabilityEvidence)
    suspend fun insertEvidenceAll(evidence: List<CapabilityEvidence>)
    suspend fun recentEvidenceForCapability(
        capabilityKey: String,
        workspaceId: String?,
        limit: Int
    ): List<CapabilityEvidence>
    suspend fun evidenceForExecution(executionId: String): List<CapabilityEvidence>
    suspend fun countEvidenceForCapability(capabilityKey: String, workspaceId: String?): Int
    suspend fun pruneEvidenceOlderThan(epochMs: Long)

    // ---- Derived state store (upsert on re-derivation) ----
    suspend fun upsertCapabilityStatus(status: RadarCapabilityStatus)
    suspend fun upsertCapabilityStatuses(statuses: List<RadarCapabilityStatus>)
    suspend fun getCapabilityStatus(
        capabilityKey: String,
        workspaceId: String?
    ): RadarCapabilityStatus?
    suspend fun capabilityStatusesForWorkspace(workspaceId: String?): List<RadarCapabilityStatus>
    fun observeCapabilityStatuses(workspaceId: String?): Flow<List<RadarCapabilityStatus>>

    // ---- Change log (append-only, drives evolution detection) ----
    suspend fun insertChange(change: CapabilityChangeRecord)
    suspend fun recentChangesForWorkspace(workspaceId: String?, limit: Int): List<CapabilityChangeRecord>
    fun observeChanges(workspaceId: String?, limit: Int): Flow<List<CapabilityChangeRecord>>

    // ---- Recommendations ----
    suspend fun upsertRecommendations(recommendations: List<RadarRecommendation>)
    suspend fun activeRecommendationsForWorkspace(workspaceId: String?): List<RadarRecommendation>
    fun observeRecommendations(workspaceId: String?): Flow<List<RadarRecommendation>>
    suspend fun dismissRecommendation(id: String)
}
