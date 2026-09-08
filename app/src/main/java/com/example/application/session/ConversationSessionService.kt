package com.example.application.session

import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationSessionWithTurns
import com.example.domain.core.session.ConversationTurn
import com.example.domain.ports.session.ConversationSessionRepositoryPort
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * ============================================================================
 * ConversationSessionService — the durable conversation session authority
 * ============================================================================
 *
 * REPORT GAPS CLOSED:
 *  - "Durable Sessions NOT FIXED": sessions are Room-persisted, workspace-
 *    scoped, and survive process death.
 *  - "Session browser / Session retrieval after restart / Resume
 *    conversation": the service exposes live session lists, full-turn
 *    reload, rename, delete.
 *  - "Quick Chat missing": sessions carry a QUICK_CHAT mode in which the
 *    conversation binds to a model, not to a user-selected agent.
 *
 * The service owns session lifecycle policy (titling, id generation, the
 * canonical QUICK_CHAT agent resolution contract); the repository port owns
 * persistence. [QUICK_CHAT_AGENT_ID] is the canonical agent every
 * agent-independent conversation executes through — it is seeded into the
 * durable agent registry exactly like the other canonical agents, so even
 * Quick Chat executions keep a single governed execution authority (report
 * #16) instead of spawning a parallel un-governed generation path.
 */
class ConversationSessionService(
    private val repository: ConversationSessionRepositoryPort,
    private val workspaceIdProvider: suspend () -> String
) {

    suspend fun activeWorkspaceId(): String = workspaceIdProvider()

    /** Live, most-recent-first session list for the active workspace. */
    fun observeSessions(workspaceId: String): Flow<List<ConversationSession>> =
        repository.observeSessions(workspaceId)

    /** Live turn stream of one session. */
    fun observeTurns(sessionId: ConversationSessionId): Flow<List<ConversationTurn>> =
        repository.observeTurns(sessionId)

    /** Full session + turns (for resume / reopen). */
    suspend fun getSessionWithTurns(sessionId: ConversationSessionId): ConversationSessionWithTurns? =
        repository.getSessionWithTurns(sessionId)

    /**
     * Creates a new durable session bound to the ACTIVE workspace.
     * QUICK_CHAT sessions are agent-independent; AGENT sessions record the
     * canonical agent they are bound to.
     */
    suspend fun createSession(
        mode: ChatMode,
        agentId: String? = null,
        agentName: String? = null,
        modelResourceId: String? = null,
        modelDisplayName: String? = null,
        title: String = defaultTitle()
    ): ConversationSession {
        val now = System.currentTimeMillis()
        val session = ConversationSession(
            id = ConversationSessionId("sess_${UUID.randomUUID().toString().take(12)}"),
            workspaceId = workspaceIdProvider(),
            title = title,
            mode = mode,
            agentId = agentId,
            agentName = agentName,
            modelResourceId = modelResourceId,
            modelDisplayName = modelDisplayName,
            createdAtEpochMs = now,
            lastActiveAtEpochMs = now
        )
        repository.upsertSession(session)
        return session
    }

    /**
     * Appends a REAL executed turn (prompt, answer, measured tokens,
     * duration, outcome) to a durable session.
     */
    suspend fun appendTurn(
        sessionId: ConversationSessionId,
        prompt: String,
        answer: String,
        agentName: String?,
        agentRole: String?,
        modelResourceId: String?,
        tokensConsumed: Int,
        durationMs: Long,
        isSuccessful: Boolean,
        eventCount: Int
    ): ConversationTurn {
        val turn = ConversationTurn(
            id = "turn_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            sessionId = sessionId,
            prompt = prompt,
            answer = answer,
            agentName = agentName,
            agentRole = agentRole,
            modelResourceId = modelResourceId,
            tokensConsumed = tokensConsumed,
            durationMs = durationMs,
            isSuccessful = isSuccessful,
            eventCount = eventCount,
            createdAtEpochMs = System.currentTimeMillis()
        )
        repository.appendTurn(turn)
        return turn
    }

    /** First-turn titling policy: use the prompt itself (bounded). */
    suspend fun titleFromPrompt(sessionId: ConversationSessionId, prompt: String) {
        if (prompt.isBlank()) return
        val title = prompt.trim().take(60).let { if (it.length >= 60) "$it…" else it }
        repository.renameSession(sessionId, title)
    }

    suspend fun deleteSession(sessionId: ConversationSessionId) =
        repository.deleteSession(sessionId)

    suspend fun renameSession(sessionId: ConversationSessionId, title: String) {
        if (title.isNotBlank()) repository.renameSession(sessionId, title.take(80))
    }

    /** Pins the exact model resource for the session (durable user choice). */
    suspend fun setSessionModel(
        sessionId: ConversationSessionId,
        modelResourceId: String?,
        modelDisplayName: String?
    ) = repository.updateSessionModel(sessionId, modelResourceId, modelDisplayName)

    suspend fun setSessionAgent(sessionId: ConversationSessionId, agentId: String?, agentName: String?) {
        val session = repository.getSession(sessionId) ?: return
        repository.upsertSession(
            session.copy(agentId = agentId, agentName = agentName, lastActiveAtEpochMs = System.currentTimeMillis())
        )
    }

    companion object {
        /**
         * The canonical agent every QUICK_CHAT conversation executes through.
         * Seeded like the other canonical agents (see CanonicalAgentCatalog)
         * so Quick Chat keeps the SAME governed execution kernel — token
         * budgets, security guard, telemetry, admission control — instead of
         * a parallel ungoverned direct-generation path.
         */
        const val QUICK_CHAT_AGENT_ID = "agent_quick_chat"

        /** Default Arabic session title before the first turn renames it. */
        const val DEFAULT_TITLE = "محادثة جديدة"

        fun defaultTitle(): String = DEFAULT_TITLE
    }
}
