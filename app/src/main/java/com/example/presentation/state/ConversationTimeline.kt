package com.example.presentation.state

import com.example.domain.core.events.ExecutionEvent

/**
 * ============================================================================
 * ConversationTimeline — the CONVERSATION-FIRST presentation model (Chat
 * Workspace redesign, Task 1)
 * ============================================================================
 *
 * The Studio screen moved from "execution console with a transcript" to a
 * conversation-first Chat Workspace: the timeline of messages IS the core
 * surface, and the execution lifecycle is part of the message stream — not a
 * separate telemetry card stacked above it.
 *
 * This file owns three PURE presentation pieces (no ViewModel logic, no
 * Compose dependencies — all unit-testable on the JVM):
 *
 *  1. [ChatEntry] — the message-stream entries the timeline renders
 *     (user message / assistant result), projected from the DURABLE
 *     [StudioTurn] truth by StudioViewModel;
 *  2. [LiveExecutionState] + [ExecutionLifecycleProjection] — the honest
 *     lifecycle projection of the REAL [ExecutionEvent]s the governed kernel
 *     already emits (no invented stages);
 *  3. [ChatAutoScrollPolicy] — the follow/hold scrolling contract for a
 *     streaming conversation list.
 */
sealed interface ChatEntry {

    /** Stable identity for LazyColumn keys (unique within one screen life). */
    val id: String

    /**
     * One USER message. Appended to the timeline IMMEDIATELY when a send is
     * accepted (P0-C: the user's message never waits for the execution to
     * complete — it must not look like it vanished while the engine thinks).
     *
     * CHAT CAPABILITIES (Task 2 §5): a user message can carry ATTACHMENT
     * references (picked through SAF, imported through the real transfer
     * path, persisted with the durable turn — §16).
     */
    data class User(
        override val id: String,
        val text: String,
        val attachments: List<ChatEntryAttachment> = emptyList()
    ) : ChatEntry

    /**
     * One ASSISTANT result — the SINGLE display path of a finished (or
     * failed) execution's text (P0-D: the final answer appears exactly once;
     * the live-stream block is cleared when this entry lands).
     * The footer fields (tokens / duration / events) are the execution
     * summary the old standalone "LiveExecutionCard" used to duplicate.
     *
     * CHAT CAPABILITIES (Task 2 §11): when the execution used search, the
     * entry carries the REAL citation chains collected from the kernel's
     * ActionCompleted observations — rendered as a collapsible sources block.
     *
     * FRONTIER REASONING: [reasoning] carries the model's OWN streamed
     * thinking when the provider reported one — runtime-only (the durable
     * turn stores the answer; a restored session honestly shows no thinking).
     */
    data class Assistant(
        override val id: String,
        val text: String,
        val agentName: String,
        val agentRole: String,
        val isSuccessful: Boolean,
        val modelResourceId: String? = null,
        val tokensConsumed: Int = 0,
        val durationMs: Long = 0L,
        val eventCount: Int = 0,
        val isDegraded: Boolean = false,
        val sources: List<ChatSourceRef> = emptyList(),
        val artifacts: List<ChatArtifactRef> = emptyList(),
        val reasoning: String = ""
    ) : ChatEntry

    /**
     * CHAT CAPABILITIES (Task 2 §9–§12): one STRUCTURED capability result
     * block in the message stream — the outcome of a user-invoked tool,
     * skill, MCP tool, search-intelligence run, or knowledge retrieval. The
     * conversation stays a conversation (§15: visually distinct, never a
     * dashboard) — this is the block the user reads instead of raw
     * orchestration output.
     *
     * FUNCTIONAL CLOSURE (Phase 1 §22): the block's lifecycle starts as
     * PENDING ([isPending]) the moment the user runs the capability (the
     * sheet closes, the conversation keeps the trace), then resolves with
     * the real outcome. PENDING is runtime-only — only the RESOLVED outcome
     * is durable conversation history.
     */
    data class CapabilityResult(
        override val id: String,
        val kind: CapabilityKind,
        /** The concrete thing that ran (tool/skill/server name, or the query). */
        val title: String,
        /** One-line human outcome ("تم"، "فشل: …"، "اكتمل بنمط تراجعي…"). */
        val summary: String,
        /** Optional longer content (tool output, ranked results…) — markdown. */
        val detail: String? = null,
        val sources: List<ChatSourceRef> = emptyList(),
        val artifacts: List<ChatArtifactRef> = emptyList(),
        val isSuccessful: Boolean = true,
        val isDegraded: Boolean = false,
        val degradedMessage: String? = null,
        val timestampMs: Long = 0L,
        /** FUNCTIONAL CLOSURE (§22): true while the invocation is running. */
        val isPending: Boolean = false
    ) : ChatEntry

    /**
     * CHAT CAPABILITIES (Task 2 §13 — MANDATORY): the INLINE human-approval
     * block tied to the execution that needs consent. The approval itself is
     * the REAL HumanApprovalGate backend (the same authority the governance
     * screen uses); this entry only mirrors its state into the conversation
     * — with the real description, the requested action, and the decision
     * buttons while PENDING. No bypass: Approve/Reject go through the gate.
     */
    data class ApprovalBlock(
        override val id: String,
        val approvalId: String,
        val executionId: String,
        val toolName: String,
        val riskLevel: String,
        val description: String,
        val requestedAction: String,
        val justification: String,
        val state: ApprovalBlockState = ApprovalBlockState.PENDING
    ) : ChatEntry
}

/** The capability families a [ChatEntry.CapabilityResult] can come from. */
enum class CapabilityKind {
    TOOL,
    SKILL,
    MCP,
    SEARCH,
    KNOWLEDGE_RETRIEVAL
}

/** The user-facing lifecycle of one inline approval block. */
enum class ApprovalBlockState {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}

/**
 * One attachment reference on a user message (the presentation twin of the
 * durable domain [com.example.domain.core.session.TurnAttachment] — kept
 * separate so presentation models never leak into persistence).
 */
data class ChatEntryAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storageUri: String,
    val artifactId: String? = null
)

/** One citation/source reference (search intelligence, knowledge retrieval). */
data class ChatSourceRef(
    val title: String,
    val url: String? = null,
    val providerId: String? = null,
    val confidenceScore: Float? = null
)

/** One artifact card reference rendered inside the conversation (§15). */
data class ChatArtifactRef(
    val artifactId: String,
    val name: String,
    val type: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storageUri: String
)

/**
 * The user-facing execution lifecycle phases, projected STRICTLY from the
 * real [ExecutionEvent]s the governed kernel emits. Every phase below maps to
 * at least one real event type — nothing invented, no chain-of-thought.
 */
enum class ExecutionPhase {
    /** Accepted by the send, not yet observed by the kernel. */
    QUEUED,

    /** A CBR-MDP decision is being made / a replan happened. */
    PLANNING,

    /** The kernel started the task / is running actions or tools. */
    EXECUTING,

    /** The economic gate requires a human approval. */
    AWAITING_APPROVAL,

    /**
     * FRONTIER REASONING: the model is streaming its thinking tokens
     * (ReasoningChunk events) — a REAL provider-reported phase, distinct
     * from answer streaming.
     */
    THINKING,

    /** Answer tokens are streaming. */
    STREAMING,

    /** Terminal: the execution finished (collapsed into the assistant entry). */
    COMPLETED,

    /** Terminal: the execution failed (collapsed into the failed assistant entry). */
    FAILED,

    /** Terminal: cancelled by the user or system (kept as a visible strip). */
    CANCELLED
}

/**
 * UI POLISH §10: one REAL execution step in the live block's expandable
 * details — a short honest label (the action/tool/verdict the kernel
 * actually reported) + its timestamp. Steps are APPEND-ONLY facts from
 * the [ExecutionEvent] stream — never chain-of-thought, never invented.
 */
data class ExecutionStep(
    val label: String,
    val timestampMs: Long
)

/**
 * The LIVE execution state attached to the user message that STARTED it
 * (FUNCTIONAL CLOSURE §8/§11: [originUserEntryId] anchors the block to its
 * originating user entry — the timeline renders it EXACTLY there instead
 * of always at the end, so capability results that land during/after the
 * execution keep their true chronology). Present from send until the
 * terminal event lands; a CANCELLED execution stays visible (with its
 * partial stream) until the next send/session action.
 */
data class LiveExecutionState(
    /** The ExecutionHost task key this block belongs to (internal guard). */
    val executionId: String,
    val phase: ExecutionPhase = ExecutionPhase.QUEUED,
    /** Short honest detail of the current step (tool name, verdict, reason). */
    val phaseDetail: String? = null,
    val startedAtMs: Long = 0L,
    val actionCount: Int = 0,
    val toolCount: Int = 0,
    /** UI POLISH §10: the REAL step history for the expandable details. */
    val steps: List<ExecutionStep> = emptyList(),
    val isDegraded: Boolean = false,
    val degradedMessage: String? = null,
    /** FUNCTIONAL CLOSURE (§8/§11): the user entry this execution answers. */
    val originUserEntryId: String? = null
)

/**
 * PURE projection: real [ExecutionEvent] → [LiveExecutionState] updates.
 * Terminal COMPLETED/FAILED updates are returned as well (the ViewModel
 * decides to collapse the block into the assistant entry), so the whole
 * lifecycle contract is testable in isolation from coroutines.
 */
object ExecutionLifecycleProjection {

    /** The verdict of [ExecutionEvent.BudgetGateDecision] that awaits a human. */
    private const val APPROVAL_DECISION = "APPROVAL_REQUIRED"

    /** UI POLISH §10: the step-history cap (the LAST steps stay visible). */
    private const val STEP_HISTORY_LIMIT = 14

    /**
     * UI POLISH §10: appends one REAL step to the history (capped). A blank
     * CONTENT is dropped — a step the kernel did not actually describe is
     * noise, not a detail (the label never invents content).
     *
     * CHAT FINAL CLOSURE (§15): the step timestamp comes from the caller-
     * supplied [nowMs] (defaulting to the wall clock) so the projection is
     * DETERMINISTICALLY testable — a small, architecture-compatible seam,
     * not a refactor.
     */
    private fun LiveExecutionState.withStep(
        prefix: String?,
        content: String?,
        nowMs: Long
    ): LiveExecutionState {
        val clean = content?.take(60)?.trim().orEmpty()
        if (clean.isBlank()) return this
        val label = if (prefix.isNullOrBlank()) clean else "$prefix $clean"
        return copy(
            steps = (steps + ExecutionStep(label, nowMs))
                .takeLast(STEP_HISTORY_LIMIT)
        )
    }

    /**
     * Applies one event to [current]. ContentChunk text accumulation and
     * terminal collapse are the ViewModel's job — this projection only owns
     * the lifecycle shape.
     *
     * CHAT FINAL CLOSURE (§15): [nowMs] parameterizes the projection's clock
     * (default: the wall clock) — tests pass a fixed value for deterministic
     * step timestamps; production callers are unchanged.
     */
    fun apply(
        current: LiveExecutionState,
        event: ExecutionEvent,
        nowMs: Long = System.currentTimeMillis()
    ): LiveExecutionState {
        var next = current
        when (event) {
            is ExecutionEvent.Started -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                phaseDetail = null
            )

            is ExecutionEvent.DecisionMade -> next = current.copy(
                phase = ExecutionPhase.PLANNING,
                phaseDetail = event.decision.chosenAction.type.displayName
            ).withStep(prefix = null, content = event.decision.chosenAction.type.displayName, nowMs = nowMs)

            is ExecutionEvent.Replanned -> next = current.copy(
                phase = ExecutionPhase.PLANNING,
                phaseDetail = event.reason.take(48)
            ).withStep(prefix = "إعادة تخطيط:", content = event.reason, nowMs = nowMs)

            is ExecutionEvent.ActionStarted -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                // CHAT CAPABILITIES (Task 2 §14): the label comes from the
                // REAL decision action the kernel started ("استعلام شبكي
                // موثوق", "تنفيذ أداة برمجية مباشرة", "استرجاع المعرفة
                // والوثائق (RAG)"…) — actual events, no chain-of-thought.
                phaseDetail = event.action.type.displayName.take(48)
            ).withStep(prefix = null, content = event.action.type.displayName, nowMs = nowMs)

            is ExecutionEvent.ActionCompleted -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                actionCount = current.actionCount + 1,
                phaseDetail = event.outputSummary.take(48)
            ).withStep(prefix = "تم:", content = event.outputSummary, nowMs = nowMs)

            is ExecutionEvent.ActionFailed -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                actionCount = current.actionCount + 1,
                phaseDetail = event.errorDescription.take(48)
            ).withStep(prefix = "فشل إجراء:", content = event.errorDescription, nowMs = nowMs)

            is ExecutionEvent.ToolRequested -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                toolCount = current.toolCount + 1,
                phaseDetail = event.toolName
            ).withStep(prefix = "أداة:", content = event.toolName, nowMs = nowMs)

            is ExecutionEvent.ToolResult -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                phaseDetail = event.toolName
            ).withStep(prefix = "نتيجة أداة:", content = event.toolName, nowMs = nowMs)

            is ExecutionEvent.ContentChunk -> next = current.copy(
                phase = ExecutionPhase.STREAMING,
                phaseDetail = null
            )

            // FRONTIER REASONING: thinking tokens are a real, distinct phase —
            // the model works BEFORE it answers. No step-history entry (the
            // thinking text gets its own collapsible surface in the live
            // block, never chain-of-thought soup in the steps list).
            is ExecutionEvent.ReasoningChunk -> next = current.copy(
                phase = ExecutionPhase.THINKING,
                phaseDetail = null
            )

            is ExecutionEvent.BudgetGateDecision ->
                next = if (event.decision == APPROVAL_DECISION) {
                    current.copy(phase = ExecutionPhase.AWAITING_APPROVAL, phaseDetail = null)
                } else {
                    current.copy(phaseDetail = event.decision)
                }

            is ExecutionEvent.RateLimitEncountered -> next = current.copy(
                phaseDetail = "حد معدل مؤقت"
            ).withStep(prefix = null, content = "حد معدل مؤقت", nowMs = nowMs)

            is ExecutionEvent.Degraded -> next = current.copy(
                isDegraded = true,
                degradedMessage = event.message
            ).withStep(prefix = "نمط تراجعي:", content = event.message, nowMs = nowMs)

            is ExecutionEvent.Error -> next = current.copy(
                phase = ExecutionPhase.FAILED,
                phaseDetail = event.message.take(48)
            ).withStep(prefix = "فشل:", content = event.message, nowMs = nowMs)

            is ExecutionEvent.Completed -> next = current.copy(
                phase = ExecutionPhase.COMPLETED
            )

            is ExecutionEvent.Cancelled -> next = current.copy(
                phase = ExecutionPhase.CANCELLED,
                phaseDetail = event.reason.take(48)
            )

            // Pure telemetry — deliberately NO lifecycle effect (the token
            // gauges own these numbers; the user never sees raw telemetry).
            is ExecutionEvent.UsageBudgetUpdate,
            is ExecutionEvent.CostRecorded,
            is ExecutionEvent.ObservationRecorded -> Unit
        }
        return next
    }
}

/**
 * PURE auto-scroll contract for a streaming conversation timeline (Task 1
 * §9): follow the conversation while the user is at the bottom; never yank a
 * reading user; expose an unread affordance; an explicit user send always
 * scrolls intentionally.
 */
object ChatAutoScrollPolicy {

    /**
     * TRUE when the list is considered "at the bottom": the last visible item
     * is within [threshold] positions of the end (or the list is empty).
     */
    fun isNearBottom(lastVisibleIndex: Int, totalItems: Int, threshold: Int = 2): Boolean {
        if (totalItems <= 0) return true
        return lastVisibleIndex >= totalItems - 1 - threshold
    }

    /**
     * Whether a content update should follow (scroll to bottom):
     * following users keep following; a just-sent message forces it.
     */
    fun shouldFollow(following: Boolean, afterUserSend: Boolean): Boolean =
        following || afterUserSend

    /** Whether the "new messages" affordance should be visible. */
    fun shouldShowUnreadAffordance(following: Boolean, hasNewContent: Boolean): Boolean =
        !following && hasNewContent
}
