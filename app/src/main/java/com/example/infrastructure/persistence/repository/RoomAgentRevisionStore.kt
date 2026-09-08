package com.example.infrastructure.persistence.repository

import com.example.domain.ports.agent.AgentRevisionRecord
import com.example.domain.ports.agent.AgentRevisionStorePort
import com.example.infrastructure.persistence.dao.AgentRevisionDao
import com.example.infrastructure.persistence.entities.AgentRevisionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room implementation of the domain-owned [AgentRevisionStorePort] — the
 * durable agent revision ledger (`agent_revisions`, DB v14).
 */
class RoomAgentRevisionStore(
    private val dao: AgentRevisionDao
) : AgentRevisionStorePort {

    override suspend fun record(revision: AgentRevisionRecord) = withContext(Dispatchers.IO) {
        dao.upsert(
            AgentRevisionEntity(
                agentId = revision.agentId,
                revisionId = revision.revisionId,
                major = revision.major,
                minor = revision.minor,
                patch = revision.patch,
                previousVersionId = revision.previousVersionId,
                snapshotJson = revision.snapshotJson,
                createdBy = revision.createdBy,
                createdAtEpochMs = revision.createdAtEpochMs
            )
        )
    }

    override suspend fun revisionsFor(agentId: String): List<AgentRevisionRecord> =
        withContext(Dispatchers.IO) {
            dao.forAgent(agentId).map { it.toRecord() }
        }

    override suspend fun latestFor(agentId: String): AgentRevisionRecord? =
        withContext(Dispatchers.IO) {
            dao.latestForAgent(agentId)?.toRecord()
        }

    private fun AgentRevisionEntity.toRecord(): AgentRevisionRecord =
        AgentRevisionRecord(
            agentId = agentId,
            revisionId = revisionId,
            major = major,
            minor = minor,
            patch = patch,
            previousVersionId = previousVersionId,
            snapshotJson = snapshotJson,
            createdBy = createdBy,
            createdAtEpochMs = createdAtEpochMs
        )
}
