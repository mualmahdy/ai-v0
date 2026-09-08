package com.example.infrastructure.persistence.repository

import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationSessionWithTurns
import com.example.domain.core.session.ConversationTurn
import com.example.domain.ports.session.ConversationSessionRepositoryPort
import com.example.infrastructure.persistence.dao.ConversationSessionDao
import com.example.infrastructure.persistence.dao.ConversationTurnDao
import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ============================================================================
 * RoomConversationSessionRepository — Room implementation of the
 * domain-owned [ConversationSessionRepositoryPort] (durable sessions).
 * ============================================================================
 */
class RoomConversationSessionRepository(
    private val database: RoomDatabase,
    private val sessionDao: ConversationSessionDao,
    private val turnDao: ConversationTurnDao
) : ConversationSessionRepositoryPort {

    override fun observeSessions(workspaceId: String): Flow<List<ConversationSession>> {
        return sessionDao.forWorkspace(workspaceId).map { rows ->
            rows.map { it.toDomain() }
        }
    }

    override fun observeTurns(sessionId: ConversationSessionId): Flow<List<ConversationTurn>> {
        return turnDao.forSession(sessionId.value).map { rows ->
            rows.map { it.toDomain() }
        }
    }

    override suspend fun getSession(id: ConversationSessionId): ConversationSession? {
        return sessionDao.byId(id.value)?.toDomain()
    }

    override suspend fun getSessionWithTurns(id: ConversationSessionId): ConversationSessionWithTurns? {
        val session = sessionDao.byId(id.value)?.toDomain() ?: return null
        val turns = turnDao.forSessionOnce(id.value).map { it.toDomain() }
        return ConversationSessionWithTurns(session = session, turns = turns)
    }

    override suspend fun upsertSession(session: ConversationSession) {
        sessionDao.upsert(
            ConversationSessionEntity(
                sessionId = session.id.value,
                workspaceId = session.workspaceId,
                title = session.title,
                mode = session.mode.name,
                agentId = session.agentId,
                agentName = session.agentName,
                modelResourceId = session.modelResourceId,
                modelDisplayName = session.modelDisplayName,
                turnCount = session.turnCount,
                totalTokensConsumed = session.totalTokensConsumed,
                createdAtEpochMs = session.createdAtEpochMs,
                lastActiveAtEpochMs = session.lastActiveAtEpochMs
            )
        )
    }

    override suspend fun appendTurn(turn: ConversationTurn) {
        val entity = ConversationTurnEntity(
            turnId = turn.id,
            sessionId = turn.sessionId.value,
            prompt = turn.prompt,
            answer = turn.answer,
            agentName = turn.agentName,
            agentRole = turn.agentRole,
            modelResourceId = turn.modelResourceId,
            tokensConsumed = turn.tokensConsumed,
            durationMs = turn.durationMs,
            isSuccessful = turn.isSuccessful,
            eventCount = turn.eventCount,
            createdAtEpochMs = turn.createdAtEpochMs
        )
        // Durable-turn write + session-aggregate bump in ONE Room
        // transaction: the session counters can never drift from the
        // persisted turns.
        database.withTransaction {
            turnDao.insert(entity)
            sessionDao.bumpAggregates(
                id = entity.sessionId,
                tokens = entity.tokensConsumed,
                now = entity.createdAtEpochMs
            )
        }
    }

    override suspend fun deleteSession(id: ConversationSessionId) {
        turnDao.deleteForSession(id.value)
        sessionDao.delete(id.value)
    }

    override suspend fun updateSessionModel(
        id: ConversationSessionId,
        modelResourceId: String?,
        modelDisplayName: String?
    ) {
        sessionDao.updateModel(
            id = id.value,
            modelId = modelResourceId,
            modelDisplayName = modelDisplayName,
            now = System.currentTimeMillis()
        )
    }

    override suspend fun renameSession(id: ConversationSessionId, title: String) {
        sessionDao.rename(id.value, title, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------
    // Entity mapping
    // ------------------------------------------------------------------

    private fun ConversationSessionEntity.toDomain(): ConversationSession {
        val parsedMode = try {
            ChatMode.valueOf(mode)
        } catch (_: Exception) {
            ChatMode.QUICK_CHAT
        }
        return ConversationSession(
            id = ConversationSessionId(sessionId),
            workspaceId = workspaceId,
            title = title,
            mode = parsedMode,
            agentId = agentId,
            agentName = agentName,
            modelResourceId = modelResourceId,
            modelDisplayName = modelDisplayName,
            turnCount = turnCount,
            totalTokensConsumed = totalTokensConsumed,
            createdAtEpochMs = createdAtEpochMs,
            lastActiveAtEpochMs = lastActiveAtEpochMs
        )
    }

    private fun ConversationTurnEntity.toDomain(): ConversationTurn {
        return ConversationTurn(
            id = turnId,
            sessionId = ConversationSessionId(sessionId),
            prompt = prompt,
            answer = answer,
            agentName = agentName,
            agentRole = agentRole,
            modelResourceId = modelResourceId,
            tokensConsumed = tokensConsumed,
            durationMs = durationMs,
            isSuccessful = isSuccessful,
            eventCount = eventCount,
            createdAtEpochMs = createdAtEpochMs
        )
    }
}
