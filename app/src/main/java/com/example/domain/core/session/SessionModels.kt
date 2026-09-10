package com.example.domain.core.session

/**
 * ============================================================================
 * DURABLE CONVERSATION SESSION MODELS (report gap: "Sessions NOT FIXED —
 * legacy session infrastructure was deleted without a durable replacement")
 * ============================================================================
 *
 * The P0-convergence commit removed the legacy `sessions` table (project-scoped,
 * implicit 1L bootstrap, zero production callers) and replaced conversations
 * with an in-ViewModel transcript (`UiState.studioSession`) that dies with the
 * process. This subsystem restores REAL durable sessions — born workspace-owned
 * — so the user's conversations survive restarts, can be browsed, reopened and
 * resumed, while remaining scoped to the workspace they belong to.
 *
 * Design decisions:
 *  - A session is WORKSPACE-scoped from birth (workspaceId is mandatory).
 *  - A session carries a CHAT MODE: QUICK_CHAT conversations are
 *    agent-independent (the user never selects an agent — only a model),
 *    AGENT conversations are bound to a canonical agent from the durable
 *    agent registry.
 *  - A session optionally pins a MODEL RESOURCE (exact runtime binding via
 *    ResourceId), mirroring the backend's exact-model-pinning capability at
 *    the user surface.
 *  - Turns are append-only records of real executions (answer, tokens,
 *    duration, outcome) — never fabricated.
 */
@JvmInline
value class ConversationSessionId(val value: String) {
    override fun toString(): String = value
}

/** How a conversation is driven (report gaps: Quick Chat + Model Picker). */
enum class ChatMode {
    /** Agent-independent conversation: prompt → session → selected model → generation. */
    QUICK_CHAT,

    /** Canonical agent-bound conversation through the governed agent loop. */
    AGENT
}

/** One durable conversation session (workspace-owned). */
data class ConversationSession(
    val id: ConversationSessionId,
    val workspaceId: String,
    val title: String,
    val mode: ChatMode = ChatMode.QUICK_CHAT,
    val agentId: String? = null,
    val agentName: String? = null,
    val modelResourceId: String? = null,
    val modelDisplayName: String? = null,
    val turnCount: Int = 0,
    val totalTokensConsumed: Int = 0,
    val createdAtEpochMs: Long = 0L,
    val lastActiveAtEpochMs: Long = 0L,
    /**
     * REPAIR ORDER §5/§15 — project-scoped sessions: NULL = workspace-scoped
     * (shared) session, non-null = project-private session. Sessions of
     * project A are invisible to project B (sibling isolation).
     */
    val projectId: Long? = null
)

/** One durable conversational turn (the real executed outcome). */
data class ConversationTurn(
    val id: String,
    val sessionId: ConversationSessionId,
    val prompt: String,
    val answer: String,
    val agentName: String? = null,
    val agentRole: String? = null,
    val modelResourceId: String? = null,
    val tokensConsumed: Int = 0,
    val durationMs: Long = 0L,
    val isSuccessful: Boolean = true,
    val eventCount: Int = 0,
    val createdAtEpochMs: Long = 0L
)

/** A session with its full turn history (for reopening / resuming). */
data class ConversationSessionWithTurns(
    val session: ConversationSession,
    val turns: List<ConversationTurn>
)
