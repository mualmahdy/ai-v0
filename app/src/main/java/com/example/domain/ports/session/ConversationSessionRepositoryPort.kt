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
 *  - REPAIR (defect family 1 — workspace-authorized access): ALL read and
 *    mutation operations are workspace-authorized. An id that belongs to a
 *    DIFFERENT workspace is indistinguishable from "not found" — mutations
 *    targeting it are no-ops. Id-only access can no longer cross workspace
 *    boundaries.
 *  - [observeSessions] emits on every session mutation (bumped aggregates,
 *    renames, deletes) so UI lists stay live.
 *  - [appendTurn] persists the turn AND bumps the session's aggregate
 *    counters (turnCount, totalTokensConsumed, lastActiveAtEpochMs) in one
 *    transaction — ONLY when the session belongs to the given workspace.
 *  - [deleteSession] cascades to the session's turns — ONLY for the owning
 *    workspace.
 */
interface ConversationSessionRepositoryPort {

    /** Live, most-recent-first session list for a workspace. */
    fun observeSessions(workspaceId: String): Flow<List<ConversationSession>>

    /** Live, oldest-first turn list of one session. */
    fun observeTurns(sessionId: ConversationSessionId): Flow<List<com.example.domain.core.session.ConversationTurn>>

    /**
     * Loads one session by id (any workspace — for internal wiring only;
     * authorization decisions MUST use [getSessionForWorkspace]).
     */
    suspend fun getSession(id: ConversationSessionId): ConversationSession?

    /**
     * WORKSPACE-AUTHORIZED load: returns the session only when it belongs to
     * [workspaceId]; a session of another workspace is indistinguishable
     * from nonexistent (authorization boundary, not a leak).
     */
    suspend fun getSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): ConversationSession?

    /**
     * WORKSPACE-AUTHORIZED load with full turn history (for resume) — a
     * session of another workspace returns null.
     */
    suspend fun getSessionWithTurnsForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): ConversationSessionWithTurns?

    /** Creates or updates a session row. */
    suspend fun upsertSession(session: ConversationSession)

    /**
     * WORKSPACE-AUTHORIZED append: persists one durable turn and bumps the
     * session aggregates atomically — ONLY when the session belongs to
     * [workspaceId]; otherwise nothing is written and `false` is returned.
     */
    suspend fun appendTurnForWorkspace(
        turn: com.example.domain.core.session.ConversationTurn,
        workspaceId: String
    ): Boolean

    /**
     * WORKSPACE-AUTHORIZED delete: deletes a session and all of its turns —
     * ONLY when it belongs to [workspaceId]; returns whether anything was
     * deleted.
     */
    suspend fun deleteSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): Boolean

    /**
     * WORKSPACE-AUTHORIZED model pin update — no-op for a session owned by
     * another workspace.
     */
    suspend fun updateSessionModelForWorkspace(
        id: ConversationSessionId,
        workspaceId: String,
        modelResourceId: String?,
        modelDisplayName: String?
    )

    /**
     * WORKSPACE-AUTHORIZED rename — no-op for a session owned by another
     * workspace. Returns whether the rename applied.
     */
    suspend fun renameSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String,
        title: String
    ): Boolean
}
