package com.example.domain.ports.session

import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationSessionWithTurns
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * CONVERSATION SESSION REPOSITORY — domain-owned persistence port
 * ============================================================================
 *
 * Durable conversation sessions (report gap: "durable Sessions / Session
 * browser / Session retrieval after restart / Resume conversation").
 *
 * The application layer depends on this port; the Room implementation
 * (`RoomConversationSessionRepository`) lives in infrastructure and is
 * injected at the composition root — the domain never references Room.
 *
 * Semantics contract for implementors:
 *  - Sessions are workspace-scoped: every query is keyed by workspaceId.
 *  - [observeSessions] emits on every session mutation (bumped aggregates,
 *    renames, deletes) so UI lists stay live.
 *  - [appendTurn] persists the turn AND bumps the session's aggregate
 *    counters (turnCount, totalTokensConsumed, lastActiveAtEpochMs) in one
 *    transaction.
 *  - [deleteSession] cascades to the session's turns.
 */
interface ConversationSessionRepositoryPort {

    /** Live, most-recent-first session list for a workspace. */
    fun observeSessions(workspaceId: String): Flow<List<ConversationSession>>

    /** Live, oldest-first turn list of one session. */
    fun observeTurns(sessionId: ConversationSessionId): Flow<List<com.example.domain.core.session.ConversationTurn>>

    /** Loads one session by id (any workspace — caller enforces scoping). */
    suspend fun getSession(id: ConversationSessionId): ConversationSession?

    /** Loads one session with its full turn history (for resume). */
    suspend fun getSessionWithTurns(id: ConversationSessionId): ConversationSessionWithTurns?

    /** Creates or updates a session row. */
    suspend fun upsertSession(session: ConversationSession)

    /**
     * Appends one durable turn and bumps the session aggregates
     * (turnCount, totalTokensConsumed, lastActiveAtEpochMs) atomically.
     */
    suspend fun appendTurn(turn: com.example.domain.core.session.ConversationTurn)

    /** Deletes a session and all of its turns. */
    suspend fun deleteSession(id: ConversationSessionId)

    /** Updates a session's pinned model (exact runtime binding surface). */
    suspend fun updateSessionModel(
        id: ConversationSessionId,
        modelResourceId: String?,
        modelDisplayName: String?
    )

    /** Renames a session (title) — the user-editable library surface. */
    suspend fun renameSession(id: ConversationSessionId, title: String)
}
