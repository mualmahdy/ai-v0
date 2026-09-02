package com.example.infrastructure.resource

import com.example.domain.core.resource.CandidateEvaluation
import com.example.domain.core.resource.ConfigurationVersion
import com.example.domain.core.resource.DecisionRecord
import com.example.domain.core.resource.ExecutionOutcome
import com.example.domain.core.resource.FallbackPolicy
import com.example.domain.core.resource.GovernanceResult
import com.example.domain.core.resource.GovernanceState
import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.SecurityResult
import com.example.domain.core.resource.ServiceId
import com.example.domain.ports.resource.DecisionRecordStorePort
import com.example.domain.ports.resource.EvidenceStorePort
import com.example.domain.ports.resource.ExecutionStateStorePort
import com.example.domain.ports.resource.EvidenceRecord
import com.example.domain.ports.resource.ExecutionStateRecord
import com.example.domain.ports.resource.VerificationOutcomeRecord
import com.example.domain.ports.resource.VerificationOutcomeStorePort
import com.example.infrastructure.persistence.dao.DecisionRecordDao
import com.example.infrastructure.persistence.dao.EvidenceRecordDao
import com.example.infrastructure.persistence.dao.ExecutionRecordDao
import com.example.infrastructure.persistence.dao.VerificationOutcomeDao
import com.example.infrastructure.persistence.entities.DecisionRecordEntity
import com.example.infrastructure.persistence.entities.EvidenceRecordEntity
import com.example.infrastructure.persistence.entities.ExecutionRecordEntity
import com.example.infrastructure.persistence.entities.VerificationOutcomeEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * P0.4 — Room-backed DecisionRecord store (Section F: "persisted via Room
 * `decisions` table"). JSON serialization uses org.json, consistent with the
 * codebase's existing persistence adapters.
 */
class RoomDecisionRecordStore(
    private val dao: DecisionRecordDao
) : DecisionRecordStorePort {

    override suspend fun save(record: DecisionRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun get(decisionId: String): DecisionRecord? =
        dao.getById(decisionId)?.toDomain()

    override suspend fun getForTask(taskId: String): List<DecisionRecord> =
        dao.getByTask(taskId).map { it.toDomain() }

    override suspend fun latestVersionFor(taskId: String, stepId: String): Int =
        dao.latestVersion(taskId, stepId) ?: 0
}

internal fun DecisionRecord.toEntity(): DecisionRecordEntity {
    val fallbackType: String
    val fallbackPayload = JSONObject()
    when (val policy = this.fallbackPolicy) {
        is FallbackPolicy.Fail -> fallbackType = "Fail"
        is FallbackPolicy.Replan -> {
            fallbackType = "Replan"
            fallbackPayload.put("reason", policy.reason)
        }
        is FallbackPolicy.PreferAlternative -> {
            fallbackType = "PreferAlternative"
            fallbackPayload.put("candidateResourceIds", JSONArray(policy.candidateResourceIds))
        }
    }
    val evaluations = JSONArray()
    for (candidate in this.candidateEvaluations) {
        evaluations.put(
            JSONObject()
                .put("resourceId", candidate.resourceId.value)
                .put("providerId", candidate.providerId.value)
                .put("serviceId", candidate.serviceId.value)
                .put("capabilityFit", candidate.capabilityFit)
                .put("healthScore", candidate.healthScore)
                .put("estimatedLatencyMs", candidate.estimatedLatencyMs)
                .put("estimatedCost", candidate.estimatedCost)
                .put("finalScore", candidate.finalScore)
                .put("isSelected", candidate.isSelected)
                .put("rationale", candidate.rationale)
        )
    }
    return DecisionRecordEntity(
        decisionId = decisionId,
        taskId = taskId,
        stepId = stepId,
        timestamp = timestamp,
        decisionVersion = decisionVersion,
        selectedResourceId = selectedResourceId.value,
        selectedProviderId = selectedProviderId.value,
        selectedServiceId = selectedServiceId.value,
        selectedConfigurationVersion = selectedConfigurationVersion.value,
        selectedAgentId = selectedAgentId,
        selectedToolIdsJson = JSONArray(selectedToolIds).toString(),
        requiredCapabilitiesJson = JSONArray(requiredCapabilities.toList()).toString(),
        candidateEvaluationsJson = evaluations.toString(),
        decisionRationale = decisionRationale,
        confidence = confidence,
        securityPermitted = securityResult.permitted,
        securityRuleId = securityResult.ruleId,
        securityReason = securityResult.reason,
        governanceState = governanceResult.state.name,
        governancePolicyId = governanceResult.policyId,
        governanceReason = governanceResult.reason,
        fallbackPolicyType = fallbackType,
        fallbackPolicyPayloadJson = fallbackPayload.toString(),
        createdAt = System.currentTimeMillis()
    )
}

internal fun DecisionRecordEntity.toDomain(): DecisionRecord {
    val fallbackPolicy: FallbackPolicy = when (this.fallbackPolicyType) {
        "Replan" -> FallbackPolicy.Replan(
            reason = runCatching { JSONObject(this.fallbackPolicyPayloadJson).optString("reason") }.getOrDefault("")
        )
        "PreferAlternative" -> FallbackPolicy.PreferAlternative(
            candidateResourceIds = runCatching {
                val array = JSONObject(this.fallbackPolicyPayloadJson).optJSONArray("candidateResourceIds")
                (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
            }.getOrDefault(emptyList())
        )
        else -> FallbackPolicy.Fail
    }
    val evaluations = mutableListOf<CandidateEvaluation>()
    runCatching {
        val array = JSONArray(this.candidateEvaluationsJson)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            evaluations.add(
                CandidateEvaluation(
                    resourceId = ResourceId(obj.getString("resourceId")),
                    providerId = ProviderId(obj.getString("providerId")),
                    serviceId = ServiceId(obj.getString("serviceId")),
                    capabilityFit = obj.getDouble("capabilityFit"),
                    healthScore = obj.getDouble("healthScore"),
                    estimatedLatencyMs = obj.getLong("estimatedLatencyMs"),
                    estimatedCost = obj.getDouble("estimatedCost"),
                    finalScore = obj.getDouble("finalScore"),
                    isSelected = obj.getBoolean("isSelected"),
                    rationale = obj.optString("rationale")
                )
            )
        }
    }
    return DecisionRecord(
        decisionId = decisionId,
        taskId = taskId,
        stepId = stepId,
        timestamp = timestamp,
        decisionVersion = decisionVersion,
        selectedResourceId = ResourceId(selectedResourceId),
        selectedProviderId = ProviderId(selectedProviderId),
        selectedServiceId = ServiceId(selectedServiceId),
        selectedConfigurationVersion = ConfigurationVersion(selectedConfigurationVersion),
        selectedAgentId = selectedAgentId,
        selectedToolIds = runCatching {
            val array = JSONArray(this.selectedToolIdsJson)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList()),
        requiredCapabilities = runCatching {
            val array = JSONArray(this.requiredCapabilitiesJson)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet()),
        candidateEvaluations = evaluations,
        decisionRationale = decisionRationale,
        confidence = confidence,
        securityResult = SecurityResult(
            permitted = securityPermitted,
            ruleId = securityRuleId,
            reason = securityReason
        ),
        governanceResult = GovernanceResult(
            state = runCatching { GovernanceState.valueOf(governanceState) }.getOrDefault(GovernanceState.NOT_APPLICABLE),
            policyId = governancePolicyId,
            reason = governanceReason
        ),
        fallbackPolicy = fallbackPolicy
    )
}

/**
 * P0.8 — Room-backed execution state store (Section I: ExecutionStateEvent ->
 * execution state store (Room)).
 */
class RoomExecutionStateStore(
    private val dao: ExecutionRecordDao
) : ExecutionStateStorePort {

    override suspend fun save(record: ExecutionStateRecord) {
        dao.insert(
            ExecutionRecordEntity(
                executionId = record.executionId,
                decisionId = record.decisionId,
                taskId = record.taskId,
                stepId = record.stepId,
                resourceId = record.resourceId,
                outcome = record.outcome,
                transportError = record.transportError,
                latencyMs = record.latencyMs,
                timestamp = record.timestamp
            )
        )
    }

    override suspend fun get(executionId: String): ExecutionStateRecord? =
        dao.getById(executionId)?.toDomainRecord()

    override suspend fun getForTask(taskId: String): List<ExecutionStateRecord> =
        dao.getByTask(taskId).map { it.toDomainRecord() }
}

private fun ExecutionRecordEntity.toDomainRecord() = ExecutionStateRecord(
    executionId = executionId,
    decisionId = decisionId,
    taskId = taskId,
    stepId = stepId,
    resourceId = resourceId,
    outcome = outcome,
    transportError = transportError,
    latencyMs = latencyMs,
    timestamp = timestamp
)

/** P0.8 — Room-backed evidence store (Section I: EvidenceEvent -> evidence store). */
class RoomEvidenceStore(
    private val dao: EvidenceRecordDao
) : EvidenceStorePort {

    override suspend fun save(record: EvidenceRecord) {
        dao.insert(
            EvidenceRecordEntity(
                evidenceId = record.evidenceId,
                taskId = record.taskId,
                stepId = record.stepId,
                decisionId = record.decisionId,
                resourceId = record.resourceId,
                evidenceKeysJson = JSONArray(record.evidenceKeys).toString(),
                summary = record.summary,
                payloadJson = record.payloadJson,
                createdAt = record.createdAt
            )
        )
    }

    override suspend fun getForStep(stepId: String): List<EvidenceRecord> =
        dao.getByStep(stepId).map { it.toDomainRecord() }

    override suspend fun getForTask(taskId: String): List<EvidenceRecord> =
        dao.getByTask(taskId).map { it.toDomainRecord() }
}

private fun EvidenceRecordEntity.toDomainRecord(): EvidenceRecord {
    val keys = runCatching {
        val array = JSONArray(this.evidenceKeysJson)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
    return EvidenceRecord(
        evidenceId = evidenceId,
        taskId = taskId,
        stepId = stepId,
        decisionId = decisionId,
        resourceId = resourceId,
        evidenceKeys = keys,
        summary = summary,
        payloadJson = payloadJson,
        createdAt = createdAt
    )
}

/** P0.8 — Room-backed verification outcome store (Section I). */
class RoomVerificationOutcomeStore(
    private val dao: VerificationOutcomeDao
) : VerificationOutcomeStorePort {

    override suspend fun save(record: VerificationOutcomeRecord) {
        dao.insert(
            VerificationOutcomeEntity(
                id = record.stepId + "_" + UUID.randomUUID().toString(),
                taskId = record.taskId,
                stepId = record.stepId,
                verified = record.verified,
                confidence = record.confidence,
                summary = record.summary,
                createdAt = record.createdAt
            )
        )
    }

    override suspend fun getForStep(stepId: String): List<VerificationOutcomeRecord> =
        dao.getByStep(stepId).map {
            VerificationOutcomeRecord(
                stepId = it.stepId,
                taskId = it.taskId,
                verified = it.verified,
                confidence = it.confidence,
                summary = it.summary,
                createdAt = it.createdAt
            )
        }
}
