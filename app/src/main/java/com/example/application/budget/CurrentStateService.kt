package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.CurrentResourceStateEntity
import com.example.infrastructure.persistence.entities.CurrentResourceStateSourceEntity
import com.example.infrastructure.persistence.entities.FreshnessPolicyEntity
import androidx.room.withTransaction

/**
 * ============================================================================
 * CurrentStateService — Stage 5: إسقاط الحالة المشتق (the derived projection)
 * ============================================================================
 * The DERIVED half of Group D: «إسقاط مشتق قابل لإعادة البناء؛ فهارس
 * جزئية تمنع أكثر من حالة نشطة للموضوع/المقياس/النطاق».
 *
 * Derivation, per (subject, metric, scope) key:
 *   * the input is the latest VALID observations of the key, newest first;
 *   * the PRIMARY source is the newest one; earlier valid ones inside the
 *     evaluation window are SUPPORTING sources;
 *   * freshness comes from the derivation policy (freshness_policy):
 *     expected_update_interval_seconds / staleness_threshold_seconds /
 *     source_delay_seconds. Effective age = now − (observed_at + source
 *     delay); FRESH within the interval, AGING within the threshold, STALE
 *     beyond it. When the policy says unknown_when_expired and the state is
 *     STALE, the value is honestly dropped (state_status UNKNOWN — never a
 *     guessed zero);
 *   * a latest valid observation whose knowledge_status is not KNOWN
 *     yields state_status UNKNOWN (the fact is explicitly unknown);
 *   * zero valid observations yield NOT_DERIVABLE (nothing is guessed);
 *   * CONFLICT is a legal vocabulary value reserved by the schema; v1 does
 *     not emit it (a deterministic conflict rule needs a metric-kind
 *     definition — documented in the Stage-5 interpretations).
 *
 * Supersede: writing a new active state deactivates the previous active
 * rows of the SAME key inside one transaction; the DB-level uniqueness
 * triggers (trg_current_state_unique_active_insert/_update) enforce
 * at-most-one-active per key no matter who writes.
 *
 * Rebuildable: [rebuild] retires every active row and re-derives from the
 * observations alone — the projection never carries information the
 * observations do not.
 */
class CurrentStateService(
    private val database: AppDatabase,
    private val idGenerator: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {

    object Vocab {
        const val FRESH = "FRESH"
        const val AGING = "AGING"
        const val STALE = "STALE"
        const val FRESHNESS_UNKNOWN = "UNKNOWN"
        val FRESHNESS_STATES = setOf(FRESH, AGING, STALE, FRESHNESS_UNKNOWN)

        const val STATUS_OK = "OK"
        const val STATUS_CONFLICT = "CONFLICT"
        const val STATUS_UNKNOWN = "UNKNOWN"
        const val STATUS_STALE = "STALE"
        const val STATUS_NOT_DERIVABLE = "NOT_DERIVABLE"
        val STATE_STATUSES = setOf(STATUS_OK, STATUS_CONFLICT, STATUS_UNKNOWN, STATUS_STALE, STATUS_NOT_DERIVABLE)

        const val SOURCE_PRIMARY = "PRIMARY"
        const val SOURCE_SUPPORTING = "SUPPORTING"
    }

    /** The (subject, metric, scope) key to derive for. Exactly one subject. */
    data class DeriveSpec(
        val metricId: String,
        val scopeId: String? = null,
        val subjectProviderId: String? = null,
        val subjectAccessContextId: String? = null,
        val subjectProviderScopeNodeId: String? = null,
        val subjectProviderModelRecordId: String? = null,
        val subjectModelId: String? = null,
        val subjectOfferingId: String? = null,
        val subjectRequestAttemptId: String? = null,
        val derivationPolicyId: String,
        val nowEpochMs: Long? = null
    )

    sealed class DeriveOutcome {
        /** A new active state row was written; the previous ones retired. */
        data class Derived(
            val stateId: String,
            val supersededStateIds: List<String>,
            val stateStatus: String,
            val freshnessState: String,
            val primarySourceObservationId: String?,
            val supportingSourceObservationIds: List<String>
        ) : DeriveOutcome()

        /** Nothing derivable — no valid observations for the key. */
        data class NotDerivable(val metricId: String, val reason: String) : DeriveOutcome()
    }

    /** How many newest observations participate in one derivation. */
    private val evaluationWindow = 8

    suspend fun derive(spec: DeriveSpec): DeriveOutcome {
        val subjects = listOf(
            spec.subjectProviderId, spec.subjectAccessContextId,
            spec.subjectProviderScopeNodeId, spec.subjectProviderModelRecordId,
            spec.subjectModelId, spec.subjectOfferingId, spec.subjectRequestAttemptId
        )
        val nonNull = subjects.count { it != null }
        require(nonNull == 1) { "exactly one state subject is required (got $nonNull)" }
        require(spec.metricId.isNotBlank()) { "metric is required" }

        val now = spec.nowEpochMs ?: clock()
        val policy: FreshnessPolicyEntity? =
            database.observationDao().freshnessPolicyById(spec.derivationPolicyId)
        requireNotNull(policy) { "unknown derivation policy ${spec.derivationPolicyId}" }

        val observations = database.currentStateDao().latestValidObservations(
            metricId = spec.metricId,
            scopeId = spec.scopeId,
            subjectProviderId = spec.subjectProviderId,
            subjectAccessContextId = spec.subjectAccessContextId,
            subjectProviderScopeNodeId = spec.subjectProviderScopeNodeId,
            subjectProviderModelRecordId = spec.subjectProviderModelRecordId,
            subjectModelId = spec.subjectModelId,
            subjectOfferingId = spec.subjectOfferingId,
            subjectRequestAttemptId = spec.subjectRequestAttemptId,
            limit = evaluationWindow
        )
        if (observations.isEmpty()) {
            return DeriveOutcome.NotDerivable(
                metricId = spec.metricId,
                reason = "no VALID observations for this subject/metric/scope"
            )
        }

        val primary = observations.first()
        val supporting = observations.drop(1)

        // ---- freshness from the policy ----
        val effectiveAgeSeconds = (
            (now - primary.observedAtEpochMs) / 1000L
            ) + policy.sourceDelaySeconds
        val freshness: String = when {
            effectiveAgeSeconds <= policy.expectedUpdateIntervalSeconds -> Vocab.FRESH
            effectiveAgeSeconds <= policy.stalenessThresholdSeconds -> Vocab.AGING
            else -> Vocab.STALE
        }

        // ---- the state row ----
        val known = primary.knowledgeStatus == ObservationWriterService.Vocab.KNOWLEDGE_KNOWN
        val dropValueWhenStale = freshness == Vocab.STALE && policy.unknownWhenExpired == 1
        val stateStatus: String = when {
            !known -> Vocab.STATUS_UNKNOWN
            dropValueWhenStale -> Vocab.STATUS_UNKNOWN
            freshness == Vocab.STALE -> Vocab.STATUS_STALE
            else -> Vocab.STATUS_OK
        }
        // A dropped value (policy unknown_when_expired) also drops the
        // freshness to UNKNOWN — the row says "the current value is
        // unknown", not "stale but here anyway".
        val freshnessState = if (dropValueWhenStale) Vocab.FRESHNESS_UNKNOWN else freshness

        val stateId = idGenerator()
        val sourceRecords = buildList {
            add(
                CurrentResourceStateSourceEntity(
                    stateSourceId = idGenerator(),
                    stateId = stateId,
                    observationId = primary.observationId,
                    sourceRole = Vocab.SOURCE_PRIMARY,
                    includedAtEpochMs = now
                )
            )
            supporting.forEach { obs ->
                add(
                    CurrentResourceStateSourceEntity(
                        stateSourceId = idGenerator(),
                        stateId = stateId,
                        observationId = obs.observationId,
                        sourceRole = Vocab.SOURCE_SUPPORTING,
                        includedAtEpochMs = now
                    )
                )
            }
        }

        // Capture the incumbent BEFORE retiring it — at most one active row
        // per key exists (the invariant the uniqueness triggers guard).
        val previous = database.currentStateDao().activeStateFor(
            metricId = spec.metricId,
            scopeId = spec.scopeId,
            subjectProviderId = spec.subjectProviderId,
            subjectAccessContextId = spec.subjectAccessContextId,
            subjectProviderScopeNodeId = spec.subjectProviderScopeNodeId,
            subjectProviderModelRecordId = spec.subjectProviderModelRecordId,
            subjectModelId = spec.subjectModelId,
            subjectOfferingId = spec.subjectOfferingId,
            subjectRequestAttemptId = spec.subjectRequestAttemptId
        )
        val supersededIds = listOfNotNull(previous?.stateId)

        // The supersede is ATOMIC: retire + write + sources in one
        // transaction (the Stage-1 discipline — a crash mid-way must never
        // leave the key without its active row or with sources that point
        // nowhere).
        database.withTransaction {
            database.currentStateDao().deactivateActiveForKey(
                nowEpochMs = now,
                metricId = spec.metricId,
                scopeId = spec.scopeId,
                subjectProviderId = spec.subjectProviderId,
                subjectAccessContextId = spec.subjectAccessContextId,
                subjectProviderScopeNodeId = spec.subjectProviderScopeNodeId,
                subjectProviderModelRecordId = spec.subjectProviderModelRecordId,
                subjectModelId = spec.subjectModelId,
                subjectOfferingId = spec.subjectOfferingId,
                subjectRequestAttemptId = spec.subjectRequestAttemptId
            )
            database.currentStateDao().insertState(
                CurrentResourceStateEntity(
                    stateId = stateId,
                    subjectProviderId = spec.subjectProviderId,
                    subjectAccessContextId = spec.subjectAccessContextId,
                    subjectProviderScopeNodeId = spec.subjectProviderScopeNodeId,
                    subjectProviderModelRecordId = spec.subjectProviderModelRecordId,
                    subjectModelId = spec.subjectModelId,
                    subjectOfferingId = spec.subjectOfferingId,
                    subjectRequestAttemptId = spec.subjectRequestAttemptId,
                    metricId = spec.metricId,
                    currentValueType = primary.valueType,
                    currentNumericValuePrecise = if (stateStatus == Vocab.STATUS_UNKNOWN) null else primary.numericValuePrecise,
                    currentTextValue = if (stateStatus == Vocab.STATUS_UNKNOWN) null else primary.textValue,
                    currentBooleanValue = if (stateStatus == Vocab.STATUS_UNKNOWN) null else primary.booleanValue,
                    currentStateValue = if (stateStatus == Vocab.STATUS_UNKNOWN) null else primary.stateValue,
                    unitId = primary.unitId,
                    scopeId = spec.scopeId,
                    stateAsOfEpochMs = primary.observedAtEpochMs,
                    freshnessState = freshnessState,
                    derivationPolicyId = spec.derivationPolicyId,
                    stateStatus = stateStatus,
                    isActive = 1,
                    updatedAtEpochMs = now
                )
            )
            database.currentStateDao().insertSources(sourceRecords)
        }

        return DeriveOutcome.Derived(
            stateId = stateId,
            supersededStateIds = supersededIds,
            stateStatus = stateStatus,
            freshnessState = freshnessState,
            primarySourceObservationId = primary.observationId,
            supportingSourceObservationIds = supporting.map { it.observationId }
        )
    }

    /**
     * The projection is rebuildable: retire every active row, then
     * re-derive each key that has derivable observations. Returns the
     * number of active rows after the rebuild.
     */
    suspend fun rebuild(): Int {
        val now = clock()
        val before = database.currentStateDao().activeStates()
        database.currentStateDao().deactivateAll(now)
        var live = 0
        for (state in before) {
            val outcome = derive(
                DeriveSpec(
                    metricId = state.metricId,
                    scopeId = state.scopeId,
                    subjectProviderId = state.subjectProviderId,
                    subjectAccessContextId = state.subjectAccessContextId,
                    subjectProviderScopeNodeId = state.subjectProviderScopeNodeId,
                    subjectProviderModelRecordId = state.subjectProviderModelRecordId,
                    subjectModelId = state.subjectModelId,
                    subjectOfferingId = state.subjectOfferingId,
                    subjectRequestAttemptId = state.subjectRequestAttemptId,
                    derivationPolicyId = state.derivationPolicyId,
                    nowEpochMs = now
                )
            )
            if (outcome is DeriveOutcome.Derived) live++
        }
        return database.currentStateDao().countActiveStates()
    }

    /** The active states, newest update first — the monitoring read path. */
    suspend fun activeStates(): List<CurrentResourceStateEntity> =
        database.currentStateDao().activeStates()

    suspend fun sourcesForState(stateId: String): List<CurrentResourceStateSourceEntity> =
        database.currentStateDao().sourcesForState(stateId)
}
