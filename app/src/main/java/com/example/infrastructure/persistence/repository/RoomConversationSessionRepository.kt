package com.example.infrastructure.persistence.repository

import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationSessionWithTurns
import com.example.domain.core.session.ConversationTurn
import com.example.domain.ports.session.ConversationSessionRepositoryPort
import com.example.infrastructure.persistence.TurnAttachmentJsonCodec
import com.example.infrastructure.persistence.TurnSourceJsonCodec
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
    private val turnDao: ConversationTurnDao,
    /** FUNCTIONAL CLOSURE (Phase 1 §9, DB v19): the timeline-events store. */
    private val timelineEventDao: com.example.infrastructure.persistence.dao.ChatTimelineEventDao? = null
) : ConversationSessionRepositoryPort {

    override fun observeSessions(workspaceId: String): Flow<List<ConversationSession>> {
        return sessionDao.forWorkspace(workspaceId).map { rows ->
            rows.map { it.toDomain() }
        }
    }

    /**
     * GAP-14 (Design Closure 2026): project-scoped session list. NULL
     * [projectId] = workspace-scoped (shared) sessions only; non-null = that
     * project's private sessions only. A sibling project's sessions are
     * NEVER returned (SQL predicate in [ConversationSessionDao.forWorkspaceAndProject]).
     */
    override fun observeSessionsForProject(
        workspaceId: String,
        projectId: Long?
    ): Flow<List<ConversationSession>> {
        return sessionDao.forWorkspaceAndProject(workspaceId, projectId).map { rows ->
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

    override suspend fun getSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): ConversationSession? {
        return sessionDao.byIdAndWorkspace(id.value, workspaceId)?.toDomain()
    }

    override suspend fun getSessionWithTurnsForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): ConversationSessionWithTurns? {
        val session = sessionDao.byIdAndWorkspace(id.value, workspaceId)?.toDomain() ?: return null
        val turns = turnDao.forSessionOnce(id.value).map { it.toDomain() }
        // FUNCTIONAL CLOSURE (Phase 1 §9): the durable capability-result and
        // approval blocks ride along — the caller rebuilds the exact
        // conversation the user saw (merged by timestamp).
        val events = timelineEventDao?.forSessionOnce(id.value)?.map { it.toDomain() } ?: emptyList()
        return ConversationSessionWithTurns(session = session, turns = turns, timelineEvents = events)
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
                lastActiveAtEpochMs = session.lastActiveAtEpochMs,
                // GAP-14: projectId round-trips — the mapper previously DROPPED
                // it both ways, so project-scoped sessions could never exist and
                // the purge cascade was a latent no-op.
                projectId = session.projectId
            )
        )
    }

    override suspend fun appendTurnForWorkspace(
        turn: ConversationTurn,
        workspaceId: String
    ): Boolean {
        // WORKSPACE AUTHORIZATION (defect family 1): turns can only be
        // appended to a session owned by the authorized workspace — an
        // id-only append can no longer write into another workspace's
        // conversation.
        val owner = sessionDao.byIdAndWorkspace(turn.sessionId.value, workspaceId) ?: return false
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
            createdAtEpochMs = turn.createdAtEpochMs,
            // CHAT CAPABILITIES (Task 2 §16): attachment references persist
            // WITH the turn — they survive session reopen (DB v18).
            attachmentsJson = TurnAttachmentJsonCodec.encode(turn.attachments),
            // FUNCTIONAL CLOSURE (Phase 1 §9): citation chains persist with
            // the turn (DB v19).
            sourcesJson = TurnSourceJsonCodec.encode(turn.sources)
        )
        // Durable-turn write + session-aggregate bump in ONE Room
        // transaction: the session counters can never drift from the
        // persisted turns.
        database.withTransaction {
            turnDao.insert(entity)
            sessionDao.bumpAggregates(
                id = owner.sessionId,
                tokens = entity.tokensConsumed,
                now = entity.createdAtEpochMs
            )
        }
        return true
    }

    override suspend fun deleteSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): Boolean {
        // WORKSPACE AUTHORIZATION (defect family 1): deletes cascade to turns
        // ONLY for the owning workspace — a cross-workspace delete is a
        // no-op that deletes nothing.
        val existing = sessionDao.byIdAndWorkspace(id.value, workspaceId) ?: return false
        // GAP-16 (Design Closure 2026): turns + session row are removed in ONE
        // Room transaction — a crash between the two writes previously left
        // orphaned turn rows above a deleted session forever.
        database.withTransaction {
            turnDao.deleteForSession(existing.sessionId)
            // FUNCTIONAL CLOSURE (Phase 1 §9): the session's timeline events
            // cascade with its turns — no orphaned blocks above a deleted
            // session (same transaction as the row delete).
            timelineEventDao?.deleteForSession(existing.sessionId)
            sessionDao.deleteForWorkspace(existing.sessionId, workspaceId)
        }
        return true
    }

    override suspend fun updateSessionModelForWorkspace(
        id: ConversationSessionId,
        workspaceId: String,
        modelResourceId: String?,
        modelDisplayName: String?
    ): Boolean {
        return sessionDao.updateModelForWorkspace(
            id = id.value,
            workspaceId = workspaceId,
            modelId = modelResourceId,
            modelDisplayName = modelDisplayName,
            now = System.currentTimeMillis()
        ) > 0
    }

    override suspend fun renameSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String,
        title: String
    ): Boolean {
        return sessionDao.renameForWorkspace(
            id = id.value,
            workspaceId = workspaceId,
            title = title,
            now = System.currentTimeMillis()
        ) > 0
    }

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (Phase 1 §9): durable conversational timeline events
    // ------------------------------------------------------------------

    override suspend fun appendTimelineEventForWorkspace(
        event: com.example.domain.core.session.ConversationTimelineEvent,
        workspaceId: String
    ): Boolean {
        val dao = timelineEventDao ?: return false
        // WORKSPACE AUTHORIZATION: an event can only be appended to a session
        // owned by the authorized workspace — the same boundary turns honor.
        val owner = sessionDao.byIdAndWorkspace(event.sessionId.value, workspaceId) ?: return false
        dao.insert(
            com.example.infrastructure.persistence.entities.ChatTimelineEventEntity(
                eventId = event.id,
                sessionId = owner.sessionId,
                kind = event.kind.name,
                capabilityKind = event.capabilityKind,
                title = event.title,
                summary = event.summary,
                detail = event.detail,
                sourcesJson = TurnSourceJsonCodec.encode(event.sources),
                isSuccessful = event.isSuccessful,
                isDegraded = event.isDegraded,
                degradedMessage = event.degradedMessage,
                createdAtEpochMs = event.createdAtEpochMs,
                approvalId = event.approvalId,
                executionId = event.executionId,
                toolName = event.toolName,
                riskLevel = event.riskLevel,
                justification = event.justification,
                approvalState = event.approvalState
            )
        )
        return true
    }

    override suspend fun timelineEventsForSession(
        sessionId: ConversationSessionId
    ): List<com.example.domain.core.session.ConversationTimelineEvent> {
        return timelineEventDao?.forSessionOnce(sessionId.value)?.map { it.toDomain() } ?: emptyList()
    }

    override suspend fun updateTimelineEventApprovalStateForWorkspace(
        sessionId: ConversationSessionId,
        approvalId: String,
        state: String,
        workspaceId: String
    ): Boolean {
        val dao = timelineEventDao ?: return false
        // WORKSPACE AUTHORIZATION: the state mirror only updates for a session
        // owned by the authorized workspace.
        val owner = sessionDao.byIdAndWorkspace(sessionId.value, workspaceId) ?: return false
        // RESIDUAL CLOSURE (integrity): the FINAL SQL predicate binds the
        // update to the ORIGINATING session as well — an approvalId that
        // happens to exist in ANOTHER session's timeline can never flip that
        // session's block, no matter how the id reached this call.
        val rows = dao.updateApprovalState(
            sessionId = owner.sessionId,
            approvalId = approvalId,
            state = state,
            isSuccessful = state != "REJECTED"
        )
        return rows > 0
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
            lastActiveAtEpochMs = lastActiveAtEpochMs,
            // GAP-14: projectId round-trips (was silently dropped).
            projectId = projectId
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
            createdAtEpochMs = createdAtEpochMs,
            // CHAT CAPABILITIES (Task 2 §16): references round-trip (legacy
            // rows decode as an honest empty list).
            attachments = TurnAttachmentJsonCodec.decode(attachmentsJson),
            // FUNCTIONAL CLOSURE (Phase 1 §9): citation chains round-trip
            // (legacy rows decode as an honest empty list).
            sources = TurnSourceJsonCodec.decode(sourcesJson)
        )
    }

    private fun com.example.infrastructure.persistence.entities.ChatTimelineEventEntity.toDomain():
            com.example.domain.core.session.ConversationTimelineEvent {
        return com.example.domain.core.session.ConversationTimelineEvent(
            id = eventId,
            sessionId = ConversationSessionId(sessionId),
            kind = runCatching {
                com.example.domain.core.session.TimelineEventKind.valueOf(kind)
            }.getOrDefault(com.example.domain.core.session.TimelineEventKind.CAPABILITY_RESULT),
            capabilityKind = capabilityKind,
            title = title,
            summary = summary,
            detail = detail,
            sources = TurnSourceJsonCodec.decode(sourcesJson),
            isSuccessful = isSuccessful,
            isDegraded = isDegraded,
            degradedMessage = degradedMessage,
            createdAtEpochMs = createdAtEpochMs,
            approvalId = approvalId,
            executionId = executionId,
            toolName = toolName,
            riskLevel = riskLevel,
            justification = justification,
            approvalState = approvalState
        )
    }
}
