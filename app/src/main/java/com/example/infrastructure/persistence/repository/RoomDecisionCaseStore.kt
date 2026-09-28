package com.example.infrastructure.persistence.repository

import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionCaseStore
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.resource.ResourceId
import com.example.infrastructure.persistence.dao.DecisionCaseDao
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import org.json.JSONArray

/**
 * ============================================================================
 * RoomDecisionCaseStore — Room implementation of the domain-owned
 * [DecisionCaseStore] port (report fix: "domain → infrastructure violation"
 * in CaseBase)
 * ============================================================================
 *
 * All Room entity mapping (JSON feature-vector serialization, enum parsing)
 * previously lived inside the domain class `CaseBase`; it now lives here in
 * the infrastructure layer where it belongs. The domain sees only domain
 * types.
 */
class RoomDecisionCaseStore(
    private val decisionCaseDao: DecisionCaseDao
) : DecisionCaseStore {

    override suspend fun loadAll(): List<DecisionCase> {
        return decisionCaseDao.getAllCases().map { it.toDomain() }
    }

    override suspend fun append(case: DecisionCase) {
        decisionCaseDao.insertCase(case.toEntity())
    }

    override suspend fun pruneOldest(keepMostRecent: Int) {
        // Room-backed FIFO eviction: the DAO orders by timestamp DESC; loading
        // the recent window and re-inserting is avoided by a direct delete of
        // everything older than the (keepMostRecent)-th newest timestamp.
        val recent = decisionCaseDao.getRecentCases(keepMostRecent)
        if (recent.size < keepMostRecent) return // nothing to prune
        val cutoff = recent.last().timestampEpochMs
        decisionCaseDao.deleteOlderThan(cutoff)
    }

    // ------------------------------------------------------------------
    // Entity mapping (infrastructure concern — moved OUT of the domain)
    // ------------------------------------------------------------------

    private fun DecisionCase.toEntity(): DecisionCaseEntity {
        val arr = JSONArray()
        problemFeatures.forEach { arr.put(it.toDouble()) }
        // CLOSURE P1-3 (DB v21): the decision record's resource-identity
        // projection rides the case row — a reloaded case base can rank by
        // resource identity and compute per-resource priors.
        val record = chosenAction.decisionRecord
        return DecisionCaseEntity(
            id = id,
            featuresJson = arr.toString(),
            actionType = chosenAction.type.name,
            targetId = chosenAction.targetId,
            outcomeReward = outcomeReward,
            taskType = taskType,
            timestampEpochMs = timestampMs,
            selectedResourceId = record?.selectedResourceId?.value,
            providerId = record?.providerId,
            serviceId = record?.serviceId,
            configurationVersion = record?.configurationVersion,
            governanceState = record?.governanceState,
            recordConfidence = record?.confidence
        )
    }

    private fun DecisionCaseEntity.toDomain(): DecisionCase {
        val arr = JSONArray(featuresJson)
        val floats = FloatArray(arr.length())
        for (i in 0 until arr.length()) {
            floats[i] = arr.getDouble(i).toFloat()
        }
        val type = try {
            DecisionActionType.valueOf(actionType)
        } catch (_: Exception) {
            DecisionActionType.EXECUTE_STEP
        }
        // CLOSURE P1-3: reconstruct the record ONLY when the row carries a
        // selected resource — a legacy/record-less row stays honestly
        // UNATTRIBUTED (null record), never a synthetic one.
        val record = if (selectedResourceId != null) {
            DecisionRecord(
                selectedResourceId = ResourceId(selectedResourceId),
                providerId = providerId.orEmpty(),
                serviceId = serviceId.orEmpty(),
                configurationVersion = configurationVersion ?: 0L,
                requiredCapabilities = emptySet(),
                rationale = "سجل مستعاد من تخزين الحالات (إسقاط الهوية، P1-3)",
                confidence = recordConfidence ?: 0.5f,
                governanceState = governanceState ?: "APPROVED"
            )
        } else {
            null
        }
        return DecisionCase(
            id = id,
            problemFeatures = floats,
            chosenAction = DecisionAction(type, targetId, decisionRecord = record),
            outcomeReward = outcomeReward,
            timestampMs = timestampEpochMs,
            taskType = taskType
        )
    }
}
