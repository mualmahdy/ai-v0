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

/**
 * CHAT CAPABILITIES (Task 2 §16) — one durable attachment reference on a
 * conversation turn. The reference is the MINIMAL correct linkage: the bytes
 * live in the project sandbox ([storageUri], imported through the real
 * FileTransferService staging/atomic-promotion path), the artifacts table
 * row ([artifactId], registered through the real ArtifactService) carries
 * the type/scope/audit truth, and the message-level metadata (name, mime,
 * size) is what the conversation surface needs to re-render the chip after
 * a session is reopened — WITHOUT re-reading the file.
 */
data class TurnAttachment(
    /** Stable id, unique within the turn ("attm_…"). */
    val id: String,
    /** User-visible display name (from SAF/ContentResolver). */
    val name: String,
    /** MIME type as reported at pick time. */
    val mimeType: String,
    /** Size in bytes as reported/imported. */
    val sizeBytes: Long,
    /** Sandbox-relative storage URI (project sandbox — never an absolute path). */
    val storageUri: String,
    /** The artifacts-table row registered for this attachment, when it exists. */
    val artifactId: String? = null,
    /**
     * Honest provenance: how this attachment entered the conversation
     * ("SAF_FILE", "SAF_FOLDER_ZIP"…) — shown in diagnostics, not to regular users.
     */
    val provenance: String = "SAF_FILE",
    /**
     * CLOSURE §6 (attachment hierarchy honesty): what the assistant ACTUALLY
     * received from this attachment —
     *   ATTACHMENT_ONLY      — a message-level reference; content NOT sent to
     *                          the model (non-text or not grounded);
     *   GROUNDED             — a bounded digest of the content rode the
     *                          request as marked user evidence;
     *   KNOWLEDGE_IMPORTED   — the content entered the project's knowledge
     *                          corpus (RAG), not the message itself.
     * The UI MUST render this state — a folder is never shown as "analyzed"
     * without actual ingestion/grounding.
     */
    val groundingState: String = "ATTACHMENT_ONLY",
    /**
     * CLOSURE §7 (folder understanding): the serialized honest report of a
     * FOLDER attachment — total/readable/grounded/ingested file counts
     * (see FolderUnderstandingReport). Null for plain file attachments.
     */
    val folderReportJson: String? = null
) {
    enum class GroundingState { ATTACHMENT_ONLY, GROUNDED, KNOWLEDGE_IMPORTED }
}

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
    val createdAtEpochMs: Long = 0L,
    /** CHAT CAPABILITIES (Task 2 §16): attachment references survive with the turn. */
    val attachments: List<TurnAttachment> = emptyList(),
    /**
     * FUNCTIONAL CLOSURE (Phase 1 §9): the REAL citation chains the execution
     * collected (search intelligence) — persisted WITH the turn so a reopened
     * session re-renders its sources instead of losing them.
     */
    val sources: List<TurnSourceRef> = emptyList()
)

/**
 * FUNCTIONAL CLOSURE (Phase 1 §9): one durable citation/source reference on a
 * turn (the domain twin of the runtime-harvested search citations). Kept
 * minimal — the fields the conversation surface renders.
 */
data class TurnSourceRef(
    val title: String,
    val url: String? = null,
    val providerId: String? = null,
    val confidenceScore: Float? = null
)

/**
 * FUNCTIONAL CLOSURE (Phase 1 §9/§10): one durable CONVERSATIONAL TIMELINE
 * EVENT — a capability result block (tool / skill / MCP / search / knowledge
 * retrieval) or an inline approval block that the user saw inside the
 * conversation. These are conversation history (not transient telemetry):
 * they MUST survive a session reopen, so they are persisted in their own
 * session-scoped, timestamp-ordered store (`chat_timeline_events`, DB v19)
 * and merged with the turns by [createdAtEpochMs] when the session is
 * reopened.
 *
 * PENDING capability invocations are deliberately NOT persisted — only the
 * RESOLVED outcome is conversational history (the transient pending block is
 * a runtime affordance of the live conversation).
 */
data class ConversationTimelineEvent(
    /** Stable identity (same id the runtime block carried). */
    val id: String,
    val sessionId: ConversationSessionId,
    /** CAPABILITY_RESULT or APPROVAL_BLOCK (which sub-shape is populated). */
    val kind: TimelineEventKind,
    /** For CAPABILITY_RESULT: which capability family produced it. */
    val capabilityKind: String? = null,
    /** The concrete thing that ran (tool/skill/server name, or the query). */
    val title: String = "",
    /** One-line human outcome. For APPROVAL_BLOCK: the requested action. */
    val summary: String = "",
    /** Optional longer content. For APPROVAL_BLOCK: the request description. */
    val detail: String? = null,
    /** CAPABILITY_RESULT: the real citation chains (search). */
    val sources: List<TurnSourceRef> = emptyList(),
    val isSuccessful: Boolean = true,
    val isDegraded: Boolean = false,
    val degradedMessage: String? = null,
    val createdAtEpochMs: Long = 0L,
    // ---- APPROVAL_BLOCK shape (null for capability results) ----
    val approvalId: String? = null,
    /** The kernel execution id the gate keyed the request to. */
    val executionId: String? = null,
    val toolName: String? = null,
    val riskLevel: String? = null,
    val justification: String? = null,
    /** PENDING / APPROVED / REJECTED / EXPIRED — updated on each resolution. */
    val approvalState: String? = null
)

enum class TimelineEventKind {
    CAPABILITY_RESULT,
    APPROVAL_BLOCK
}

/** A session with its full turn history AND its timeline events (for reopening). */
data class ConversationSessionWithTurns(
    val session: ConversationSession,
    val turns: List<ConversationTurn>,
    /**
     * FUNCTIONAL CLOSURE (Phase 1 §9): the durable capability-result and
     * approval blocks of this session (timestamp-ordered) — merged with the
     * turns by createdAtEpochMs to rebuild the exact conversation the user
     * saw before the reopen.
     */
    val timelineEvents: List<ConversationTimelineEvent> = emptyList()
)
