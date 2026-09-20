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
     */
    data class User(
        override val id: String,
        val text: String
    ) : ChatEntry

    /**
     * One ASSISTANT result — the SINGLE display path of a finished (or
     * failed) execution's text (P0-D: the final answer appears exactly once;
     * the live-stream block is cleared when this entry lands).
     * The footer fields (tokens / duration / events) are the execution
     * summary the old standalone "LiveExecutionCard" used to duplicate.
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
        val isDegraded: Boolean = false
    ) : ChatEntry
}

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
 * The LIVE execution state attached to the LAST user message in the timeline.
 * Present from send until the terminal event lands; a CANCELLED execution
 * stays visible (with its partial stream) until the next send/session action.
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
    val isDegraded: Boolean = false,
    val degradedMessage: String? = null
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

    /**
     * Applies one event to [current]. ContentChunk text accumulation and
     * terminal collapse are the ViewModel's job — this projection only owns
     * the lifecycle shape.
     */
    fun apply(current: LiveExecutionState, event: ExecutionEvent): LiveExecutionState {
        var next = current
        when (event) {
            is ExecutionEvent.Started -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                phaseDetail = null
            )

            is ExecutionEvent.DecisionMade -> next = current.copy(
                phase = ExecutionPhase.PLANNING,
                phaseDetail = event.decision.chosenAction.type.displayName
            )

            is ExecutionEvent.Replanned -> next = current.copy(
                phase = ExecutionPhase.PLANNING,
                phaseDetail = event.reason.take(48)
            )

            is ExecutionEvent.ActionStarted -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                phaseDetail = "خطوة ${event.stepIndex + 1}"
            )

            is ExecutionEvent.ActionCompleted -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                actionCount = current.actionCount + 1,
                phaseDetail = event.outputSummary.take(48)
            )

            is ExecutionEvent.ActionFailed -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                actionCount = current.actionCount + 1,
                phaseDetail = event.errorDescription.take(48)
            )

            is ExecutionEvent.ToolRequested -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                toolCount = current.toolCount + 1,
                phaseDetail = event.toolName
            )

            is ExecutionEvent.ToolResult -> next = current.copy(
                phase = ExecutionPhase.EXECUTING,
                phaseDetail = event.toolName
            )

            is ExecutionEvent.ContentChunk -> next = current.copy(
                phase = ExecutionPhase.STREAMING,
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
            )

            is ExecutionEvent.Degraded -> next = current.copy(
                isDegraded = true,
                degradedMessage = event.message
            )

            is ExecutionEvent.Error -> next = current.copy(
                phase = ExecutionPhase.FAILED,
                phaseDetail = event.message.take(48)
            )

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
