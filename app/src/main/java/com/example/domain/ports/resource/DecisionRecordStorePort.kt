package com.example.domain.ports.resource

import com.example.domain.core.resource.DecisionRecord

/**
 * P0.4 — Persistence port for DecisionRecords (Section F: "persisted via Room
 * `decisions` table").
 *
 * Storage detail: the production implementation is Room-backed. Keeping the store
 * behind the codebase's established port/adapter convention preserves Clean
 * Architecture layering (Section M — Preserve) and enables JVM acceptance tests.
 */
interface DecisionRecordStorePort {
    /** Persists (insert or replace) a DecisionRecord. */
    suspend fun save(record: DecisionRecord)

    /** Fetch by decisionId. */
    suspend fun get(decisionId: String): DecisionRecord?

    /** All decisions for a task, ordered by timestamp ascending. */
    suspend fun getForTask(taskId: String): List<DecisionRecord>

    /**
     * Highest decisionVersion currently persisted for (taskId, stepId). Used to
     * compute decisionVersion increments on re-decisions (acceptance test 10).
     */
    suspend fun latestVersionFor(taskId: String, stepId: String): Int
}
