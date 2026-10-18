package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ObservationEntity

/**
 * ============================================================================
 * ObservationWriterService — Stage 5: كتّاب المراقبة (the observation writers)
 * ============================================================================
 * The typed landing surface for the observation domain (Group D, factual
 * half — the schema shipped with Stage 4). I-37 deferred model/agent
 * attribution to "the writers": this is that surface. Observations are
 * historical facts — append-only, never updated, never deleted
 * (trg_no_update_observation / trg_no_delete_observation, Stage 4).
 *
 * Honest-scope note (the Stage-4 evidence-network precedent): the writers
 * land the SURFACE; production data arrives with the flows that produce it
 * (the acquisition registry is populated by ingestion, which the roadmap
 * delivers cumulatively). Tests prove the full chain end to end.
 *
 * The vocabulary mirrors the DB CHECKs in Stage5/4MigrationSql — callers
 * never pass a raw string (the EvidenceService.Vocab precedent).
 */
class ObservationWriterService(
    private val database: AppDatabase,
    private val idGenerator: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {

    object Vocab {
        const val KNOWLEDGE_KNOWN = "KNOWN"
        const val KNOWLEDGE_UNKNOWN = "UNKNOWN"
        const val KNOWLEDGE_UNSUPPORTED = "UNSUPPORTED"
        const val KNOWLEDGE_UNAVAILABLE = "UNAVAILABLE"
        const val KNOWLEDGE_NOT_APPLICABLE = "NOT_APPLICABLE"
        val KNOWLEDGE_STATUSES = setOf(
            KNOWLEDGE_KNOWN, KNOWLEDGE_UNKNOWN, KNOWLEDGE_UNSUPPORTED,
            KNOWLEDGE_UNAVAILABLE, KNOWLEDGE_NOT_APPLICABLE
        )

        const val TYPE_NUMERIC = "NUMERIC"
        const val TYPE_TEXT = "TEXT"
        const val TYPE_BOOLEAN = "BOOLEAN"
        const val TYPE_STATE = "STATE"
        val VALUE_TYPES = setOf(TYPE_NUMERIC, TYPE_TEXT, TYPE_BOOLEAN, TYPE_STATE)

        const val VALIDITY_VALID = "VALID"
        const val VALIDITY_INVALIDATED = "INVALIDATED"
        val VALIDITY_STATUSES = setOf(VALIDITY_VALID, VALIDITY_INVALIDATED)
    }

    /**
     * What to write. Exactly one subject field must be non-null (the
     * exactly-one-subject CHECK); a KNOWN observation must carry its typed
     * value and a non-KNOWN one must carry none (the value/knowledge shape
     * CHECK) — both validated HERE for a clear message and AGAIN at the DB
     * level (defense in depth, the I-25 precedent).
     */
    data class ObservationSpec(
        val observationId: String? = null,
        val providerId: String,
        val subjectProviderId: String? = null,
        val subjectAccessContextId: String? = null,
        val subjectProviderScopeNodeId: String? = null,
        val subjectProviderModelRecordId: String? = null,
        val subjectModelId: String? = null,
        val subjectOfferingId: String? = null,
        val subjectRequestAttemptId: String? = null,
        val metricId: String,
        val valueType: String,
        val numericValuePrecise: String? = null,
        val textValue: String? = null,
        val booleanValue: Boolean? = null,
        val stateValue: String? = null,
        val unitId: String? = null,
        val scopeId: String? = null,
        val observedAtEpochMs: Long,
        val validFromEpochMs: Long? = null,
        val validUntilEpochMs: Long? = null,
        val validityStatus: String = Vocab.VALIDITY_VALID,
        val sourceAuthority: String,
        val knowledgeStatus: String = Vocab.KNOWLEDGE_KNOWN,
        val unknownReasonCode: String? = null,
        val freshnessPolicyId: String? = null,
        val acquisitionRunId: String? = null,
        val derivationRuleId: String? = null,
        val confidenceScore: String? = null,
        val correctionOfObservationId: String? = null
    )

    sealed class WriteOutcome {
        data class Written(val observationId: String) : WriteOutcome()
    }

    suspend fun record(spec: ObservationSpec): WriteOutcome {
        val subjects = listOf(
            spec.subjectProviderId, spec.subjectAccessContextId,
            spec.subjectProviderScopeNodeId, spec.subjectProviderModelRecordId,
            spec.subjectModelId, spec.subjectOfferingId, spec.subjectRequestAttemptId
        )
        val nonNull = subjects.count { it != null }
        require(nonNull == 1) {
            "exactly one observation subject is required (got $nonNull)"
        }
        require(spec.metricId.isNotBlank()) { "metric is required" }
        require(spec.valueType in Vocab.VALUE_TYPES) { "unknown value type ${spec.valueType}" }
        require(spec.validityStatus in Vocab.VALIDITY_STATUSES) { "unknown validity status ${spec.validityStatus}" }
        require(spec.knowledgeStatus in Vocab.KNOWLEDGE_STATUSES) { "unknown knowledge status ${spec.knowledgeStatus}" }
        require(spec.sourceAuthority.isNotBlank()) { "source authority is required" }
        if (spec.validUntilEpochMs != null && spec.validFromEpochMs != null) {
            require(spec.validFromEpochMs < spec.validUntilEpochMs) { "validity window is empty" }
        }

        val known = spec.knowledgeStatus == Vocab.KNOWLEDGE_KNOWN
        if (known) {
            when (spec.valueType) {
                Vocab.TYPE_NUMERIC -> {
                    require(!spec.numericValuePrecise.isNullOrBlank()) { "a KNOWN NUMERIC observation requires numeric_value_precise" }
                    require(spec.textValue == null && spec.booleanValue == null && spec.stateValue == null) {
                        "a NUMERIC observation carries no other typed value"
                    }
                    require(spec.numericValuePrecise!!.toBigDecimalOrNull() != null) {
                        "numeric_value_precise must be a plain decimal (got ${spec.numericValuePrecise})"
                    }
                }
                Vocab.TYPE_TEXT -> {
                    require(!spec.textValue.isNullOrBlank()) { "a KNOWN TEXT observation requires text_value" }
                    require(spec.numericValuePrecise == null && spec.booleanValue == null && spec.stateValue == null) {
                        "a TEXT observation carries no other typed value"
                    }
                }
                Vocab.TYPE_BOOLEAN -> {
                    require(spec.booleanValue != null) { "a KNOWN BOOLEAN observation requires boolean_value" }
                    require(spec.numericValuePrecise == null && spec.textValue == null && spec.stateValue == null) {
                        "a BOOLEAN observation carries no other typed value"
                    }
                }
                Vocab.TYPE_STATE -> {
                    require(!spec.stateValue.isNullOrBlank()) { "a KNOWN STATE observation requires state_value" }
                    require(spec.numericValuePrecise == null && spec.textValue == null && spec.booleanValue == null) {
                        "a STATE observation carries no other typed value"
                    }
                }
            }
        } else {
            require(
                spec.numericValuePrecise == null && spec.textValue == null &&
                    spec.booleanValue == null && spec.stateValue == null
            ) { "a non-KNOWN observation carries no value" }
        }

        val observationId = spec.observationId ?: idGenerator()
        val now = clock()
        database.observationDao().insertObservation(
            ObservationEntity(
                observationId = observationId,
                providerId = spec.providerId,
                subjectProviderId = spec.subjectProviderId,
                subjectAccessContextId = spec.subjectAccessContextId,
                subjectProviderScopeNodeId = spec.subjectProviderScopeNodeId,
                subjectProviderModelRecordId = spec.subjectProviderModelRecordId,
                subjectModelId = spec.subjectModelId,
                subjectOfferingId = spec.subjectOfferingId,
                subjectRequestAttemptId = spec.subjectRequestAttemptId,
                metricId = spec.metricId,
                valueType = spec.valueType,
                numericValuePrecise = spec.numericValuePrecise,
                textValue = spec.textValue,
                booleanValue = spec.booleanValue?.let { if (it) 1 else 0 },
                stateValue = spec.stateValue,
                unitId = spec.unitId,
                scopeId = spec.scopeId,
                observedAtEpochMs = spec.observedAtEpochMs,
                validFromEpochMs = spec.validFromEpochMs,
                validUntilEpochMs = spec.validUntilEpochMs,
                validityStatus = spec.validityStatus,
                resetAtEpochMs = null,
                resetAfterEpochMs = null,
                sourceAuthority = spec.sourceAuthority,
                knowledgeStatus = spec.knowledgeStatus,
                unknownReasonCode = spec.unknownReasonCode,
                freshnessPolicyId = spec.freshnessPolicyId,
                acquisitionRunId = spec.acquisitionRunId,
                derivationRuleId = spec.derivationRuleId,
                confidenceScore = spec.confidenceScore,
                recordedAtEpochMs = now,
                correctionOfObservationId = spec.correctionOfObservationId
            )
        )
        return WriteOutcome.Written(observationId)
    }
}
