package com.example.infrastructure.persistence.repository

import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.MdpLearningStore
import com.example.domain.core.decision.MdpQEntry
import com.example.infrastructure.persistence.dao.MdpQValueDao
import com.example.infrastructure.persistence.entities.MdpQValueEntity

/**
 * FIX D-1 / D-4 (audit c03919d): Room-backed implementation of the tabular
 * MDP learning store — one row per (state-region, action) pair in the
 * `mdp_q_values` table. The CBR-MDP engine loads the full table once at
 * startup and persists updated cells asynchronously after every learning
 * update, so learned Q values and transition rates survive app restarts.
 */
class RoomMdpLearningStore(
    private val dao: MdpQValueDao
) : MdpLearningStore {

    override suspend fun loadAll(): List<MdpQEntry> =
        dao.getAll().mapNotNull { entity ->
            val actionType = try {
                DecisionActionType.valueOf(entity.actionType)
            } catch (_: IllegalArgumentException) {
                // Unknown/legacy action type — skip honestly rather than crash.
                null
            }
            actionType?.let {
                MdpQEntry(
                    regionKey = entity.regionKey,
                    actionType = it,
                    qValue = entity.qValue,
                    visitCount = entity.visitCount,
                    successCount = entity.successCount,
                    lastUpdatedEpochMs = entity.lastUpdatedEpochMs
                )
            }
        }

    override suspend fun persist(entries: List<MdpQEntry>) {
        if (entries.isEmpty()) return
        dao.upsertAll(
            entries.map { entry ->
                MdpQValueEntity(
                    regionKey = entry.regionKey,
                    actionType = entry.actionType.name,
                    qValue = entry.qValue,
                    visitCount = entry.visitCount,
                    successCount = entry.successCount,
                    lastUpdatedEpochMs = entry.lastUpdatedEpochMs
                )
            }
        )
    }
}
