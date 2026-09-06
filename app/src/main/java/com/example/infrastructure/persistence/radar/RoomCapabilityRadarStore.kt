package com.example.infrastructure.persistence.radar

import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.CapabilityChangeType
import com.example.domain.core.radar.CapabilityEvaluationDimensions
import com.example.domain.core.radar.CapabilityEvidence
import com.example.domain.core.radar.CapabilityHealth
import com.example.domain.core.radar.CapabilityTrend
import com.example.domain.core.radar.EvidenceOutcome
import com.example.domain.core.radar.EvidenceSource
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.domain.core.radar.RadarRecommendationType
import com.example.domain.core.radar.RecommendationPriority
import com.example.domain.ports.radar.CapabilityRadarPersistencePort
import com.example.infrastructure.persistence.dao.CapabilityChangeDao
import com.example.infrastructure.persistence.dao.CapabilityEvidenceDao
import com.example.infrastructure.persistence.dao.RadarCapabilityStateDao
import com.example.infrastructure.persistence.dao.RadarRecommendationDao
import com.example.infrastructure.persistence.entities.CapabilityChangeEntity
import com.example.infrastructure.persistence.entities.CapabilityEvidenceEntity
import com.example.infrastructure.persistence.entities.RadarCapabilityStateEntity
import com.example.infrastructure.persistence.entities.RadarRecommendationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room-backed persistence for the Capability & Evolution Radar.
 * Implements the domain [CapabilityRadarPersistencePort]; JSON encoding is
 * private to this adapter (domain models stay pure).
 */
class RoomCapabilityRadarStore(
    private val evidenceDao: CapabilityEvidenceDao,
    private val stateDao: RadarCapabilityStateDao,
    private val changeDao: CapabilityChangeDao,
    private val recommendationDao: RadarRecommendationDao
) : CapabilityRadarPersistencePort {

    override suspend fun insertEvidence(evidence: CapabilityEvidence) {
        evidenceDao.insert(evidence.toEntity())
    }

    override suspend fun insertEvidenceAll(evidence: List<CapabilityEvidence>) {
        if (evidence.isEmpty()) return
        evidenceDao.insertAll(evidence.map { it.toEntity() })
    }

    override suspend fun recentEvidenceForCapability(
        capabilityKey: String,
        workspaceId: String?,
        limit: Int
    ): List<CapabilityEvidence> =
        evidenceDao.recentForCapability(capabilityKey, workspaceId, limit).map { it.toDomain() }

    override suspend fun evidenceForExecution(executionId: String): List<CapabilityEvidence> =
        evidenceDao.forExecution(executionId).map { it.toDomain() }

    override suspend fun countEvidenceForCapability(capabilityKey: String, workspaceId: String?): Int =
        evidenceDao.countForCapability(capabilityKey)

    override suspend fun pruneEvidenceOlderThan(epochMs: Long) {
        evidenceDao.pruneOlderThan(epochMs)
    }

    override suspend fun upsertCapabilityStatus(status: RadarCapabilityStatus) {
        stateDao.upsert(status.toEntity())
    }

    override suspend fun upsertCapabilityStatuses(statuses: List<RadarCapabilityStatus>) {
        if (statuses.isEmpty()) return
        stateDao.upsertAll(statuses.map { it.toEntity() })
    }

    override suspend fun getCapabilityStatus(
        capabilityKey: String,
        workspaceId: String?
    ): RadarCapabilityStatus? = stateDao.get(capabilityKey, scopeKey(workspaceId))?.toDomain()

    override suspend fun capabilityStatusesForWorkspace(workspaceId: String?): List<RadarCapabilityStatus> =
        stateDao.forWorkspace(scopeKey(workspaceId)).map { it.toDomain() }

    override fun observeCapabilityStatuses(workspaceId: String?): Flow<List<RadarCapabilityStatus>> =
        stateDao.observeForWorkspace(scopeKey(workspaceId)).map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun insertChange(change: CapabilityChangeRecord) {
        changeDao.insert(change.toEntity())
    }

    override suspend fun recentChangesForWorkspace(workspaceId: String?, limit: Int): List<CapabilityChangeRecord> =
        changeDao.recentForWorkspace(workspaceId, limit).map { it.toDomain() }

    override fun observeChanges(workspaceId: String?, limit: Int): Flow<List<CapabilityChangeRecord>> =
        changeDao.observeForWorkspace(workspaceId, limit).map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun upsertRecommendations(recommendations: List<RadarRecommendation>) {
        if (recommendations.isEmpty()) return
        recommendationDao.upsertAll(recommendations.map { it.toEntity() })
    }

    override suspend fun activeRecommendationsForWorkspace(workspaceId: String?): List<RadarRecommendation> =
        recommendationDao.activeForWorkspace(workspaceId).map { it.toDomain() }

    override fun observeRecommendations(workspaceId: String?): Flow<List<RadarRecommendation>> =
        recommendationDao.observeForWorkspace(workspaceId).map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun dismissRecommendation(id: String) {
        recommendationDao.dismiss(id)
    }

    // ---- Mappers ----

    private fun CapabilityEvidence.toEntity() = CapabilityEvidenceEntity(
        id = id,
        capabilityKey = capabilityKey,
        source = source.name,
        outcome = outcome.name,
        confidence = confidence,
        providerId = providerId,
        serviceId = serviceId,
        modelId = modelId,
        resourceId = resourceId,
        executionId = executionId,
        workspaceId = workspaceId,
        agentId = agentId,
        detail = detail,
        timestampEpochMs = timestampEpochMs
    )

    private fun CapabilityEvidenceEntity.toDomain() = CapabilityEvidence(
        id = id,
        capabilityKey = capabilityKey,
        source = runCatching { EvidenceSource.valueOf(source) }.getOrDefault(EvidenceSource.PLATFORM_RUNTIME),
        outcome = runCatching { EvidenceOutcome.valueOf(outcome) }.getOrDefault(EvidenceOutcome.NEUTRAL),
        timestampEpochMs = timestampEpochMs,
        confidence = confidence,
        providerId = providerId,
        serviceId = serviceId,
        modelId = modelId,
        resourceId = resourceId,
        executionId = executionId,
        workspaceId = workspaceId,
        agentId = agentId,
        detail = detail
    )

    private fun RadarCapabilityStatus.toEntity() = RadarCapabilityStateEntity(
        capabilityKey = capabilityKey,
        workspaceId = scopeKey(workspaceId),
        state = state.name,
        dimensionsJson = encodeDimensions(dimensions),
        health = health.name,
        trend = trend.name,
        evidenceCount = evidenceCount,
        lastEvidenceEpochMs = lastEvidenceEpochMs,
        contributingResourceIdsJson = encodeStringList(contributingResourceIds),
        rationale = rationale,
        derivedAtEpochMs = derivedAtEpochMs
    )

    private fun RadarCapabilityStateEntity.toDomain() = RadarCapabilityStatus(
        capabilityKey = capabilityKey,
        workspaceId = workspaceId.takeIf { it != RadarCapabilityStateEntity.GLOBAL_SCOPE_KEY },
        state = runCatching { OperationalCapabilityState.valueOf(state) }.getOrDefault(OperationalCapabilityState.UNKNOWN),
        dimensions = decodeDimensions(dimensionsJson),
        health = runCatching { CapabilityHealth.valueOf(health) }.getOrDefault(CapabilityHealth.UNKNOWN),
        trend = runCatching { CapabilityTrend.valueOf(trend) }.getOrDefault(CapabilityTrend.UNKNOWN),
        evidenceCount = evidenceCount,
        lastEvidenceEpochMs = lastEvidenceEpochMs,
        contributingResourceIds = decodeStringList(contributingResourceIdsJson),
        rationale = rationale,
        derivedAtEpochMs = derivedAtEpochMs
    )

    private fun CapabilityChangeRecord.toEntity() = CapabilityChangeEntity(
        id = id,
        capabilityKey = capabilityKey,
        workspaceId = workspaceId,
        fromState = fromState.name,
        toState = toState.name,
        changeType = changeType.name,
        evidenceId = evidenceId,
        detail = detail,
        detectedAtEpochMs = detectedAtEpochMs
    )

    private fun CapabilityChangeEntity.toDomain() = CapabilityChangeRecord(
        id = id,
        capabilityKey = capabilityKey,
        workspaceId = workspaceId,
        fromState = runCatching { OperationalCapabilityState.valueOf(fromState) }.getOrDefault(OperationalCapabilityState.UNKNOWN),
        toState = runCatching { OperationalCapabilityState.valueOf(toState) }.getOrDefault(OperationalCapabilityState.UNKNOWN),
        changeType = runCatching { CapabilityChangeType.valueOf(changeType) }.getOrDefault(CapabilityChangeType.RELIABILITY_CHANGED),
        evidenceId = evidenceId,
        detail = detail,
        detectedAtEpochMs = detectedAtEpochMs
    )

    private fun RadarRecommendation.toEntity() = RadarRecommendationEntity(
        id = id,
        capabilityKey = capabilityKey,
        workspaceId = workspaceId,
        type = type.name,
        priority = priority.name,
        message = message,
        actionHint = actionHint,
        supportingEvidenceIdsJson = encodeStringList(supportingEvidenceIds),
        createdAtEpochMs = createdAtEpochMs,
        isDismissed = isDismissed
    )

    private fun RadarRecommendationEntity.toDomain() = RadarRecommendation(
        id = id,
        capabilityKey = capabilityKey,
        workspaceId = workspaceId,
        type = runCatching { RadarRecommendationType.valueOf(type) }.getOrDefault(RadarRecommendationType.CAPABILITY_DEGRADED_ACTION),
        priority = runCatching { RecommendationPriority.valueOf(priority) }.getOrDefault(RecommendationPriority.MEDIUM),
        message = message,
        actionHint = actionHint,
        supportingEvidenceIds = decodeStringList(supportingEvidenceIdsJson),
        createdAtEpochMs = createdAtEpochMs,
        isDismissed = isDismissed
    )

    private fun encodeDimensions(d: CapabilityEvaluationDimensions): String {
        val obj = JSONObject()
        fun putIfKnown(name: String, v: Boolean?) {
            if (v != null) obj.put(name, v)
        }
        putIfKnown("declared", d.declared)
        putIfKnown("implemented", d.implemented)
        putIfKnown("configured", d.configured)
        putIfKnown("provisioned", d.provisioned)
        putIfKnown("dependencyAvailable", d.dependencyAvailable)
        putIfKnown("runtimeAvailable", d.runtimeAvailable)
        putIfKnown("runtimeValidated", d.runtimeValidated)
        putIfKnown("resourceUsable", d.resourceUsable)
        putIfKnown("policyAllowed", d.policyAllowed)
        putIfKnown("healthConfirmed", d.healthConfirmed)
        putIfKnown("uiExposed", d.uiExposed)
        putIfKnown("evidenceFresh", d.evidenceFresh)
        return obj.toString()
    }

    private fun decodeDimensions(json: String): CapabilityEvaluationDimensions {
        return runCatching {
            val obj = JSONObject(json)
            fun bool(name: String): Boolean? = if (obj.has(name)) obj.getBoolean(name) else null
            CapabilityEvaluationDimensions(
                declared = bool("declared"),
                implemented = bool("implemented"),
                configured = bool("configured"),
                provisioned = bool("provisioned"),
                dependencyAvailable = bool("dependencyAvailable"),
                runtimeAvailable = bool("runtimeAvailable"),
                runtimeValidated = bool("runtimeValidated"),
                resourceUsable = bool("resourceUsable"),
                policyAllowed = bool("policyAllowed"),
                healthConfirmed = bool("healthConfirmed"),
                uiExposed = bool("uiExposed"),
                evidenceFresh = bool("evidenceFresh")
            )
        }.getOrDefault(CapabilityEvaluationDimensions.NONE)
    }

    private fun encodeStringList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStringList(json: String): List<String> {
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** Maps the domain null (global scope) to the Room sentinel key. */
    private fun scopeKey(workspaceId: String?): String =
        workspaceId ?: RadarCapabilityStateEntity.GLOBAL_SCOPE_KEY
}
