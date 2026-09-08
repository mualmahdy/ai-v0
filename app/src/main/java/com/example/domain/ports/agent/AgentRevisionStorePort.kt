package com.example.domain.ports.agent

/**
 * ============================================================================
 * AGENT REVISION STORE — domain-owned persistence port (defect family 4)
 * ============================================================================
 *
 * Durable agent revision ledger: one record per registered revision of an
 * agent. The full version chain (major.minor.patch, previousVersionId,
 * snapshot, author) survives process death, so the agent lifecycle's
 * version history is DURABLE and REPRODUCIBLE after restart — previously
 * the chain lived only in an in-memory map and evaporated on process death.
 */
interface AgentRevisionStorePort {

    /** Persists (upserts) one revision record. */
    suspend fun record(revision: AgentRevisionRecord)

    /** The full revision chain of an agent (implementations order by
     *  [AgentRevisionRecord.createdAtEpochMs]). */
    suspend fun revisionsFor(agentId: String): List<AgentRevisionRecord>

    /** The latest revision of an agent (null = never registered durably). */
    suspend fun latestFor(agentId: String): AgentRevisionRecord?
}

/**
 * One durable revision row.
 */
data class AgentRevisionRecord(
    val agentId: String,
    val revisionId: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    val previousVersionId: String?,
    /** Full definition snapshot (JSON) at this revision. */
    val snapshotJson: String,
    val createdBy: String,
    val createdAtEpochMs: Long
) {
    val versionString: String get() = "$major.$minor.$patch-$revisionId"
}
