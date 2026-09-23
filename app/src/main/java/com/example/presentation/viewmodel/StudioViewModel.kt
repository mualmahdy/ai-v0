package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.attachment.ChatAttachmentCoordinator
import com.example.application.governed.HumanApprovalGate
import com.example.application.registry.ComponentRegistry
import com.example.application.session.ConversationSessionService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.DegradedReason
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationTimelineEvent
import com.example.domain.core.session.TimelineEventKind
import com.example.domain.core.session.TurnAttachment
import com.example.domain.core.session.TurnSourceRef
import com.example.domain.core.task.AutonomyPolicy
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ExecutionLifecycleProjection
import com.example.presentation.state.ExecutionPhase
import com.example.presentation.state.LiveExecutionState
import com.example.presentation.state.StudioTurn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * ============================================================================
 * StudioViewModel — the STUDIO conversation runtime ViewModel (ADR-6 slice
 * 2, Design Closure 2026 UI-redesign track; Chat Workspace Task 1 + Task 2 +
 * FUNCTIONAL CLOSURE Phase 1)
 * ============================================================================
 *
 * GAP-19/21 (ADR-6 "تفكيك تدريجي متزامن"): the conversation runtime — the
 * heaviest single block of the old MainViewModel — leaves it and gets its
 * OWN feature ViewModel, following the TasksViewModel (GAP-11),
 * FilesViewModel and SettingsViewModel (slice 1) precedents.
 *
 *  - STATE: the prompt, the conversation mode (Quick Chat / Agent), the
 *    durable-session binding of THIS conversation, the transcript, the
 *    live stream + execution log, the token gauges, the session network
 *    policy, and this feature's own error/banner channels. Nothing here
 *    is shared with other features — the shared DISPLAY mirrors
 *    (decision case-base, activity trace) stay in their owner feature
 *    ViewModels and are fed through the [StudioSignal] bus.
 *  - BEHAVIOR: prompt execution via [ExecuteAgentTaskUseCase] on the
 *    governed execution kernel (ExecutionHost, workspace-attributed),
 *    durable-session ensure/persist (survives process death), model
 *    pinning, mode switching, and honest cancellation.
 *  - CROSS-FEATURE SEAM: the AGENT binding is NOT owned here — the agent
 *    catalog is shared state (Tasks/Explorer read it); the screen reads
 *    the selection from the shared state and passes the resolved agent
 *    into [executePrompt] / [startNewSession] as a parameter. On
 *    [openSession] the session's OWN agent is exposed as
 *    [StudioUiState.restoredAgentId] — the screen routes it into the
 *    agent catalog owner (FUNCTIONAL CLOSURE §4: the ACTUAL agent is
 *    restored into the state responsible for it, not just a display name).
 *
 * CHAT WORKSPACE (Task 1) state model on top of the above:
 *  - [StudioUiState.timeline] — the conversation-first message stream
 *    ([ChatEntry.User] appears IMMEDIATELY on send; [ChatEntry.Assistant]
 *    is the SINGLE display path of a finished result);
 *  - [StudioUiState.liveExecution] — the honest execution-lifecycle
 *    projection of the REAL kernel events (see
 *    [ExecutionLifecycleProjection]); no raw log is user-facing;
 *  - session-integrity semantics: a mode change is a semantic session
 *    boundary; "reset view" never claims durable deletion.
 *
 * FUNCTIONAL CLOSURE (Phase 1) semantics owned here:
 *  - §1 SCOPE ISOLATION: a workspace/project switch releases the session
 *    binding and CLEARS the transcript (Project A's transcript can never
 *    stay visible while the UI shows Project B). A running execution is
 *    DETACHED, not killed: it keeps its pinned persistence context.
 *  - §2 EXECUTION-PINNED CONTEXT: the workspace/project the execution
 *    started in are captured AT SEND TIME and used for session
 *    creation/turn persistence — a mid-execution scope switch can never
 *    save the turn into the wrong workspace or lose it entirely.
 *  - §3 SESSION SEMANTICS: [ensureActiveSession] reuses a session only
 *    when it is still compatible (scope + mode); an AGENT change UPDATES
 *    the session's agent binding explicitly (documented boundary, via the
 *    session service) — Agent B never executes silently inside a session
 *    bound to Agent A.
 *  - §5 MODEL HONESTY: the assistant entry records the model the DECISION
 *    LAYER ACTUALLY SELECTED (harvested from the real SELECT_MODEL
 *    decision event), falling back to the user's pin — a fallback never
 *    masquerades as the pinned model.
 *  - §6 ACTION TARGETING: Regenerate/Retry target the SPECIFIC message
 *    (by entry id), not "the last user message".
 *  - §8 EXECUTION LIFECYCLE: a consent-blocked execution (HUMAN_
 *    APPROVAL_REQUIRED) surfaces as an AWAITING_APPROVAL lifecycle + an
 *    inline approval block — NEVER as a failed assistant entry.
 *  - §9/§10/§11 CAPABILITY PERSISTENCE + CHRONOLOGY: capability results
 *    and approval blocks are durable (timeline-events store) and the
 *    reopened timeline is rebuilt by TIMESTAMP (turns and events merged).
 *  - §22 CAPABILITY LIFECYCLE: a direct invocation first lands as a
 *    PENDING block, then resolves — the user never loses track of what
 *    ran, whether it is pending, and what the result was.
 *
 * FUNCTIONAL RESIDUAL CLOSURE semantics owned here:
 *  - P1 EXECUTION-IDENTITY GATE: a DETACHED execution (scope change, view
 *    release, or a replaced current execution) keeps its pinned §2
 *    persistence but can NEVER mutate this view — no stream text, log,
 *    gauges, lifecycle phase, isExecuting, error/degraded state, or
 *    approval blocks. The durable turn reads execution-OWNED mirrors
 *    (stream/usage/event count), never the live view state; terminal
 *    persistence runs OUTSIDE the state-update lambda (no CAS-retry
 *    duplication); a user/system cancellation is never surfaced as an
 *    unexpected error.
 *  - P2/P3 CAPABILITY SCOPE PINNING: every capability invocation captures
 *    its (workspace, project, session) at START; the resolved outcome
 *    SAVES TO THE ORIGINATING SESSION (explicit policy — never
 *    auto-redirected into the live scope, never lost). With no session
 *    yet, the RESOLVED result creates and pins its own durable session
 *    (a pending block alone creates nothing).
 *  - P4 TARGETED APPROVAL RETRY: the retry targets the tapped block's
 *    approvalId — with approvals A and B both resolved, retrying A
 *    re-executes A's message, never "the last approved one".
 *  - P5 EXECUTION-PINNED GROUNDING: the send pins its scope SYNCHRONOUSLY
 *    at acceptance; the grounding digest and the execution share ONE
 *    captured context (the digest reads the pinned project's sandbox).
 *  - P6 PINNED TITLING: the first-turn rename is workspace-authorized
 *    under the execution's OWN pinned workspace (a detached execution
 *    still titles its own session).
 *
 * Honesty contract (carried over verbatim from the MainViewModel code it
 * replaces): AGENT mode without a selection fails with an actionable
 * message (never a silent no-op); a missing network monitor means
 * OFFLINE (fail-closed); failed turn persistence surfaces as a
 * diagnostic banner; a missing session service is impossible by
 * construction (non-null dependency).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModel(
    private val executeAgentTaskUseCase: ExecuteAgentTaskUseCase,
    private val componentRegistry: ComponentRegistry,
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    /** The durable conversation session authority (sessions, turns, pins). */
    private val conversationSessionService: ConversationSessionService,
    /** Real connectivity state — null means OFFLINE (fail-closed). */
    private val networkMonitorProvider: com.example.infrastructure.network.NetworkMonitor? = null,
    /** App context for the foreground execution shell (null in JVM tests). */
    private val appContext: android.content.Context? = null,
    /** Outbound signal bus: cross-feature projections of this feature. */
    private val signalBus: StudioSignalBus,
    /**
     * CHAT CAPABILITIES (Task 2 §13): the REAL human-approval authority —
     * the same gate the governance surface resolves consent through. Null in
     * compositions without the governed backend (JVM unit tests may pass a
     * real gate over the in-memory store when they assert the flow).
     */
    private val humanApprovalGate: HumanApprovalGate? = null,
    /**
     * CHAT CAPABILITIES (Task 2 §5/§6): attachment grounding digest builder
     * (SAF → sandbox → turn reference chain owner is the capability VM; this
     * ViewModel only CONSUMES built references + digest at send time).
     */
    private val attachmentCoordinator: ChatAttachmentCoordinator? = null,
    /** The device-user identity that resolves approvals (governance convention). */
    private val localPrincipalId: String = "local-device-user",
    /**
     * CHAT CAPABILITIES (Task 2 §13): "السماح دائماً" — the standing-grant
     * authority (the SAME service the governance surface grants through).
     * Null ⇒ the affordance reports itself unavailable, honestly.
     */
    private val permissionGrantService: com.example.application.security.PermissionGrantService? = null,
    /**
     * RESIDUAL CLOSURE (persistence-failure leak): the honest NON-UI sink for
     * durable-persistence failures raised by DETACHED executions/invocations —
     * their scope is gone, so the diagnostic banner belongs to a view they
     * must no longer touch. Production keeps the SAME convention
     * AuditTrailService uses for its own write failures (stderr — logcat on
     * Android); tests observe the messages without capturing stderr.
     */
    private val detachedPersistenceFailureSink: (String) -> Unit = { System.err.println(it) },
    /**
     * CHAT FINAL CLOSURE (session-binding race probe — the
     * detachedPersistenceFailureSink convention): invoked at the START of
     * every durable-session establishment so the race between send
     * acceptance and the kernel coroutine's session read is DETERMINISTICALLY
     * testable (production default: no-op — the establishment itself is
     * unchanged).
     */
    private val sessionEstablishmentProbe: suspend () -> Unit = {}
) : ViewModel() {

    /** The conversation feature's own slice of UI state (was 18 fields of UiState). */
    data class StudioUiState(
        val promptInput: String = "",
        val isExecuting: Boolean = false,
        val executionLog: List<ExecutionEvent> = emptyList(),
        val streamText: String = "",
        // Session transcript (Studio as a real conversation console).
        val studioSession: List<StudioTurn> = emptyList(),
        val sessionTurnStartMs: Long = 0L,
        // CHAT WORKSPACE (Task 1): the conversation-first message stream +
        // the live execution lifecycle block attached to the last user turn.
        val timeline: List<ChatEntry> = emptyList(),
        val liveExecution: LiveExecutionState? = null,
        // DURABLE SESSIONS + QUICK CHAT + MODEL PICKER (report gap-closure):
        // the conversation mode, the active durable session id, and the
        // user-facing exact model selection.
        val chatMode: ChatMode = ChatMode.QUICK_CHAT,
        val activeSessionId: String? = null,
        val selectedModelResourceId: String? = null,
        val selectedModelDisplayName: String? = null,
        /**
         * FUNCTIONAL CLOSURE (§4): the agent a reopened session is bound to —
         * the SCREEN routes it into the agent-catalog owner's selection so
         * the ACTUAL agent executes continuations (not just a display name).
         * Null = nothing to restore.
         */
        val restoredAgentId: String? = null,
        val isDegraded: Boolean = false,
        val degradedReason: DegradedReason? = null,
        val diagnosticBanner: String? = null,
        val currentTokensConsumed: Int = 0,
        val sessionTotalTokens: Int = 0,
        val remainingBudget: Int = 30000,
        val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = _state.asStateFlow()

    /** Gap-closure P0-01: the ExecutionHost key of the task launched by the Studio screen. */
    private var currentExecutionTaskId: String? = null

    /**
     * RESIDUAL CLOSURE (P2): the scope each pending capability invocation
     * started in (pending block id → captured workspace/project/session).
     * Consumed at resolve time; entries whose invocation never resolves die
     * with this ViewModel (a PENDING block is runtime-only by design).
     */
    private val capabilityInvocationScopes = mutableMapOf<String, CapabilityInvocationScope>()

    /**
     * RESIDUAL CLOSURE (P2): the invocation-time captured context a
     * capability result resolves under (save-to-originating-session policy).
     */
    private data class CapabilityInvocationScope(
        val workspaceId: String?,
        val projectId: Long?,
        val sessionId: String?
    )

    /**
     * FUNCTIONAL CLOSURE (§1/§2): the last (workspace, project) scope this
     * conversation was bound to. A change observed on the runtime service
     * releases the session binding and clears the transcript — the stale
     * transcript defect (Project A's messages visible while Project B is
     * active) becomes impossible.
     */
    private var lastSeenScope: Pair<String, Long?>? = null

    init {
        // --------------------------------------------------------------
        // FUNCTIONAL CLOSURE (§1): the scope-change sentinel. Collecting the
        // workspace runtime's OWN state (not a UI callback) means the chat
        // reacts even while its screen sits in the back stack.
        // --------------------------------------------------------------
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    if (workspace == null) return@collect
                    val scope = workspace.id to workspace.activeProjectId.takeIf { it > 0L }
                    val previous = lastSeenScope
                    lastSeenScope = scope
                    if (previous != null && previous != scope) {
                        onScopeChanged(previous, scope)
                    }
                }
            }
        }
    }

    /**
     * FUNCTIONAL CLOSURE (§1): the scope (workspace and/or project) changed
     * under this conversation. The VIEW is released (binding + transcript +
     * live state) — the durable sessions stay intact and browsable in their
     * own scope. A running execution is DETACHED: it keeps its pinned
     * persistence context (§2), completes under ExecutionHost, and its turn
     * lands in ITS session — it just no longer mutates this view.
     */
    private fun onScopeChanged(previous: Pair<String, Long?>, current: Pair<String, Long?>) {
        val workspaceChanged = previous.first != current.first
        val projectChanged = previous.second != current.second
        val wasExecuting = _state.value.isExecuting
        detachRunningExecution(
            banner = buildString {
                if (workspaceChanged) append("تمت تبديل مساحة العمل.")
                if (workspaceChanged && projectChanged) append(" ")
                if (projectChanged) append("تمت تبديل المشروع النشط.")
                if (wasExecuting) {
                    append(" فُصل العرض عن التنفيذ الجاري — سيُحفظ في جلسته الأصلية عند اكتماله.")
                }
                append(" بدأت محادثة جديدة ضمن النطاق الحالي.")
            }
        )
        // CHAT FINAL CLOSURE: the released conversation's approval-mirror
        // registrations die with it (a reopened session re-registers its
        // OWN blocks from its durable events).
        approvalMirrorScopes.clear()
        _state.update {
            it.copy(
                activeSessionId = null,
                timeline = emptyList(),
                studioSession = emptyList(),
                streamText = "",
                executionLog = emptyList(),
                liveExecution = null,
                sessionTurnStartMs = 0L,
                restoredAgentId = null
            )
        }
    }

    /**
     * FUNCTIONAL CLOSURE (§1/§2): detaches the view from a running execution
     * WITHOUT killing it — the execution's persistence is pinned (§2), so it
     * completes and lands in its own session; this view simply stops
     * mirroring it (the composer is freed honestly).
     */
    private fun detachRunningExecution(banner: String?) {
        currentExecutionTaskId = null
        _state.update {
            it.copy(
                isExecuting = false,
                diagnosticBanner = banner ?: it.diagnosticBanner
            )
        }
    }

    // --- Prompt & session input surfaces ---

    fun updatePromptInput(input: String) {
        _state.update { it.copy(promptInput = input) }
    }

    /**
     * MESSAGE ACTION (Task 1 §10 / FUNCTIONAL CLOSURE §7 — honest semantics):
     * loads a past user message's text into the composer as an editable
     * draft. The original message is NOT mutated — history stays
     * append-only, and the send creates a NEW message ("edit and re-send",
     * exactly what the UI affordance now says — never a claim that the
     * original was edited in place).
     */
    fun editUserMessage(text: String) {
        _state.update { it.copy(promptInput = text) }
    }

    /**
     * Sets the conversation mode: QUICK_CHAT (agent-independent, binds to the
     * selected model) or AGENT (canonical agent catalog).
     *
     * SESSION INTEGRITY (Task 1 §8): a mode change is a SEMANTIC SESSION
     * BOUNDARY — the durable session row records exactly ONE mode, so the
     * old binding is released (the session stays durable and browsable) and
     * the visible timeline resets. The next send ensures a NEW session with
     * the new mode; the visible transcript always matches the durable
     * session it belongs to. Switching mid-execution is refused honestly
     * (the running turn belongs to its own session).
     */
    fun setChatMode(mode: ChatMode) {
        val current = _state.value
        if (current.chatMode == mode) return
        if (current.isExecuting) {
            _state.update {
                it.copy(errorMessage = "لا يمكن تبديل وضع المحادثة أثناء تنفيذ جارٍ — ألغِ التنفيذ أو انتظر اكتماله.")
            }
            return
        }
        if (current.activeSessionId == null && current.timeline.isEmpty()) {
            _state.update { it.copy(chatMode = mode) }
            return
        }
        _state.update {
            it.copy(
                chatMode = mode,
                activeSessionId = null,
                timeline = emptyList(),
                studioSession = emptyList(),
                streamText = "",
                executionLog = emptyList(),
                liveExecution = null,
                sessionTurnStartMs = 0L,
                restoredAgentId = null
            )
        }
    }

    /**
     * Sets the SESSION network policy — an execution-time input of THIS
     * conversation. The change is also published on the signal bus so the
     * decision preview and the governance snapshot re-derive with the new
     * policy (they keep display mirrors in their owner ViewModels).
     */
    fun setNetworkPolicy(policy: NetworkPolicy) {
        _state.update { it.copy(networkPolicy = policy) }
        viewModelScope.launch { signalBus.emit(StudioSignal.NetworkPolicyChanged(policy)) }
    }

    /**
     * SESSION INTEGRITY (Task 1 §8): resets the IN-MEMORY conversation view
     * and releases the durable-session binding — WITHOUT claiming any
     * durable deletion. The old session row (and its turns) remain fully
     * intact and browsable in the session browser; the next sent message
     * starts a NEW durable session. This replaces the old
     * `clearStudioSession()` whose UI copy ("تفريغ سجل الجلسة") implied a
     * permanent deletion that never happened.
     */
    fun resetTranscriptView() {
        // RESIDUAL CLOSURE (P1): the fresh view is released from ANY running
        // execution (detached, not killed — §2 keeps its pinned persistence).
        // Without this, a running execution kept mutating the RESET view:
        // its terminal result landed in the fresh conversation.
        detachRunningExecution(banner = null)
        // CHAT FINAL CLOSURE: same release contract as a scope change — the
        // reset view no longer owns any approval mirror.
        approvalMirrorScopes.clear()
        _state.update {
            it.copy(
                activeSessionId = null,
                timeline = emptyList(),
                studioSession = emptyList(),
                streamText = "",
                executionLog = emptyList(),
                liveExecution = null,
                sessionTurnStartMs = 0L,
                restoredAgentId = null
            )
        }
    }

    /**
     * User-FACING MODEL PICKER (report gap): selects the exact LLM resource
     * the conversation binds to. `resourceId == null` → the runtime decision
     * layer picks (previous behaviour).
     *
     * CHAT FINAL CLOSURE (§7 model persistence): the durable binding is now
     * PERSIST-FIRST — capture scope → persist (workspace-authorized) →
     * verify the result → update the UI accordingly. A failed/ineffective
     * persistence leaves the PREVIOUS selection in place (never a saved-
     * looking lie) and surfaces the honest error. With NO active session
     * there is nothing durable to pin yet — the choice is UI-only and the
     * NEXT execution's session creation carries it durably (nothing is
     * claimed as persisted).
     */
    fun selectModel(resourceId: String?, displayName: String?) {
        val current = _state.value
        val sessionId = current.activeSessionId
        if (sessionId == null) {
            _state.update {
                it.copy(selectedModelResourceId = resourceId, selectedModelDisplayName = displayName)
            }
            return
        }
        // Scope captured SYNCHRONOUSLY at tap time (a mid-persist workspace
        // switch can neither redirect the write nor fake its result).
        val pinnedWorkspaceId = runCatching {
            workspaceRuntimeService.activeWorkspaceIdOrNull()
        }.getOrNull()
        viewModelScope.launch {
            runCatching {
                conversationSessionService.setSessionModel(
                    sessionId = ConversationSessionId(sessionId),
                    modelResourceId = resourceId,
                    modelDisplayName = displayName,
                    workspaceId = pinnedWorkspaceId
                )
            }.fold(
                onSuccess = { persisted ->
                    if (persisted) {
                        _state.update {
                            it.copy(
                                selectedModelResourceId = resourceId,
                                selectedModelDisplayName = displayName
                            )
                        }
                    } else {
                        // The write was a no-op (workspace-authorized refusal) —
                        // the displayed selection must NOT move.
                        _state.update {
                            it.copy(
                                errorMessage = "تعذر حفظ اختيار النموذج في الجلسة الدائمة — الاختيار المعروض بقي كما هو."
                            )
                        }
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            errorMessage = "تعذر حفظ اختيار النموذج: ${failure.localizedMessage} — الاختيار المعروض بقي كما هو."
                        )
                    }
                }
            )
        }
    }

    /**
     * Starts a NEW durable conversation session (bound to the active
     * workspace + current mode/model) and clears the transcript. The agent
     * binding is passed by the screen (the catalog selection is shared
     * state — ADR-6 slice 2 seam).
     *
     * FUNCTIONAL CLOSURE (§1): any execution still running for the previous
     * binding is DETACHED (not killed) — its pinned persistence (§2) is
     * untouched, but it can no longer mutate this fresh view.
     */
    fun startNewSession(agent: AgentDefinition?) {
        detachRunningExecution(banner = null)
        // CHAT FINAL CLOSURE (§6 scope race): the workspace/project AND the
        // conversation shape are captured SYNCHRONOUSLY at acceptance — the
        // coroutine never re-reads the live active scope (a rapid switch
        // between tap and coroutine dispatch can neither bind the session to
        // the wrong scope nor record the wrong mode/model).
        val accepted = _state.value
        val acceptedChatMode = accepted.chatMode
        val acceptedModelId = accepted.selectedModelResourceId
        val acceptedModelName = accepted.selectedModelDisplayName
        val pinnedWorkspaceId = runCatching {
            workspaceRuntimeService.activeWorkspaceIdOrNull()
        }.getOrNull()
        val pinnedProjectId = runCatching {
            workspaceRuntimeService.activeProjectIdOrNull()
        }.getOrNull()
        viewModelScope.launch {
            runCatching {
                val session = conversationSessionService.createSession(
                    mode = acceptedChatMode,
                    agentId = agent?.identity?.id?.value,
                    agentName = agent?.identity?.name,
                    modelResourceId = acceptedModelId,
                    modelDisplayName = acceptedModelName,
                    // GAP-14: new sessions are project-scoped from creation —
                    // bound to the PINNED project (null = shared workspace
                    // session when no project is bound).
                    projectId = pinnedProjectId,
                    // CHAT FINAL CLOSURE (§6): the pinned workspace — passed
                    // EXPLICITLY so the service's live workspaceIdProvider is
                    // never consulted after a scope switch.
                    workspaceId = pinnedWorkspaceId
                )
                _state.update {
                    it.copy(
                        activeSessionId = session.id.value,
                        studioSession = emptyList(),
                        timeline = emptyList(),
                        executionLog = emptyList(),
                        streamText = "",
                        liveExecution = null,
                        restoredAgentId = null
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(errorMessage = "تعذر إنشاء جلسة جديدة: ${failure.localizedMessage}") }
            }
        }
    }

    /**
     * SESSION BROWSER → REOPEN (report gaps: "Session retrieval after
     * restart" + "Resume conversation"): loads a durable session with its
     * full turn history INTO the Studio transcript, restores the session's
     * mode / model / AGENT binding, and continues the conversation with the
     * loaded turns as LLM history. (The browser sheet itself is
     * SessionsViewModel state — the screen closes it.)
     *
     * FUNCTIONAL CLOSURE (§1): the open is authorized against workspace AND
     * the ACTIVE PROJECT scope — a sibling project's private session is
     * refused with the honest reason (it is indistinguishable from
     * nonexistent to this scope, mirroring the repository's workspace
     * boundary). §4: the session's agent is exposed as
     * [StudioUiState.restoredAgentId] for the screen to route into the
     * agent-catalog owner. §9/§11: the timeline is REBUILT from turns AND
     * the durable capability/approval events, merged by timestamp — the
     * conversation the user reopens is the conversation they saw.
     */
    fun openSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                val workspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull()
                val activeProjectId = workspaceRuntimeService.activeProjectIdOrNull()
                // Distinguish "does not exist in this workspace" from
                // "belongs to a sibling project" — both honest, both scoped.
                val session = conversationSessionService.getSession(
                    ConversationSessionId(sessionId),
                    workspaceId
                ) ?: return@launch
                val sessionProject = session.projectId
                if (sessionProject != null && sessionProject != activeProjectId) {
                    _state.update {
                        it.copy(
                            errorMessage = "هذه الجلسة تخص مشروعاً آخر — بدّل إلى مشروعها لفتحها (عزل المشروعات)."
                        )
                    }
                    return@launch
                }
                val loaded = conversationSessionService.getSessionWithTurns(
                    ConversationSessionId(sessionId),
                    workspaceId,
                    expectedProjectId = activeProjectId
                ) ?: return@launch
                detachRunningExecution(banner = null)
                // CHAT FINAL CLOSURE (approval durable-state invariant): the
                // reopened conversation's approval blocks register their OWN
                // durable mirror location — a decision the user makes while
                // THIS conversation is open updates the event in THIS session,
                // even if the live scope has moved on by then.
                approvalMirrorScopes.clear()
                loaded.timelineEvents
                    .filter { it.kind == TimelineEventKind.APPROVAL_BLOCK }
                    .forEach { event ->
                        event.approvalId?.let { approvalId ->
                            approvalMirrorScopes[approvalId] = ApprovalMirrorScope(
                                sessionId = loaded.session.id.value,
                                workspaceId = workspaceId
                            )
                        }
                    }
                _state.update { state ->
                    state.copy(
                        activeSessionId = loaded.session.id.value,
                        chatMode = loaded.session.mode,
                        selectedModelResourceId = loaded.session.modelResourceId,
                        selectedModelDisplayName = loaded.session.modelDisplayName,
                        restoredAgentId = loaded.session.agentId,
                        studioSession = loaded.turns.map { turn ->
                            StudioTurn(
                                id = turn.id,
                                prompt = turn.prompt,
                                agentName = turn.agentName ?: loaded.session.agentName ?: "المساعد",
                                agentRole = turn.agentRole ?: "",
                                answer = turn.answer,
                                eventCount = turn.eventCount,
                                tokensConsumed = turn.tokensConsumed,
                                durationMs = turn.durationMs,
                                isSuccessful = turn.isSuccessful,
                                modelResourceId = turn.modelResourceId
                            )
                        },
                        timeline = rebuildTimeline(loaded.turns, loaded.timelineEvents),
                        executionLog = emptyList(),
                        streamText = "",
                        liveExecution = null,
                        isExecuting = false
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(errorMessage = "تعذر فتح الجلسة: ${failure.localizedMessage}") }
            }
        }
    }

    /**
     * FUNCTIONAL CLOSURE (§9/§11): rebuilds the reopened timeline by
     * TIMESTAMP — every durable turn expands to its user + assistant entries
     * and every timeline event to its capability/approval block, then all
     * are merged on [com.example.domain.core.session.ConversationTurn.
     * createdAtEpochMs] / event timestamp (turns win ties: they are the
     * conversation's backbone). Chronology is derived from when things
     * actually happened, never from a fixed placement rule.
     */
    private fun rebuildTimeline(
        turns: List<com.example.domain.core.session.ConversationTurn>,
        events: List<ConversationTimelineEvent>
    ): List<ChatEntry> {
        data class MergeItem(val ts: Long, val order: Int, val entries: List<ChatEntry>)

        val items = mutableListOf<MergeItem>()
        for (turn in turns) {
            items += MergeItem(
                ts = turn.createdAtEpochMs,
                order = 0,
                entries = buildTimelineFromTurn(turn, null)
            )
        }
        for (event in events) {
            val entry = event.toChatEntry() ?: continue
            items += MergeItem(
                ts = event.createdAtEpochMs,
                order = 1,
                entries = listOf(entry)
            )
        }
        return items.sortedWith(compareBy({ it.ts }, { it.order }))
            .flatMap { it.entries }
    }

    /** One durable timeline event → its presentation twin (null when unknowable). */
    private fun ConversationTimelineEvent.toChatEntry(): ChatEntry? {
        return when (kind) {
            TimelineEventKind.CAPABILITY_RESULT -> {
                val capabilityKind = runCatching {
                    com.example.presentation.state.CapabilityKind.valueOf(capabilityKind ?: "TOOL")
                }.getOrNull() ?: return null
                ChatEntry.CapabilityResult(
                    id = id,
                    kind = capabilityKind,
                    title = title,
                    summary = summary,
                    detail = detail,
                    sources = sources.map { it.toChatSourceRef() },
                    isSuccessful = isSuccessful,
                    isDegraded = isDegraded,
                    degradedMessage = degradedMessage,
                    timestampMs = createdAtEpochMs
                )
            }
            TimelineEventKind.APPROVAL_BLOCK -> ChatEntry.ApprovalBlock(
                id = id,
                approvalId = approvalId ?: return null,
                executionId = executionId ?: "",
                toolName = toolName ?: "",
                riskLevel = riskLevel ?: "",
                description = detail ?: "",
                requestedAction = title,
                justification = justification ?: "",
                state = runCatching {
                    ApprovalBlockState.valueOf(approvalState ?: "PENDING")
                }.getOrDefault(ApprovalBlockState.PENDING)
            )
        }
    }

    /** Durable source reference → its presentation twin. */
    private fun TurnSourceRef.toChatSourceRef() =
        com.example.presentation.state.ChatSourceRef(
            title = title,
            url = url,
            providerId = providerId,
            confidenceScore = confidenceScore
        )

    /**
     * One durable turn → the two timeline entries it represents (the user
     * message + the assistant result). Failed turns keep their assistant
     * entry (it carries the retry affordance).
     */
    private fun buildTimelineFromTurn(
        turn: com.example.domain.core.session.ConversationTurn,
        sessionAgentName: String?
    ): List<ChatEntry> {
        val agentName = turn.agentName ?: sessionAgentName ?: "المساعد"
        return listOf(
            ChatEntry.User(
                id = "u_${turn.id}",
                text = turn.prompt,
                // CHAT CAPABILITIES (Task 2 §16): reopened sessions re-render
                // their attachment chips from the durable references.
                attachments = turn.attachments.map { it.toEntryAttachment() }
            ),
            ChatEntry.Assistant(
                id = "a_${turn.id}",
                text = turn.answer,
                agentName = agentName,
                agentRole = turn.agentRole ?: "",
                isSuccessful = turn.isSuccessful,
                modelResourceId = turn.modelResourceId,
                tokensConsumed = turn.tokensConsumed,
                durationMs = turn.durationMs,
                eventCount = turn.eventCount,
                // FUNCTIONAL CLOSURE (§9): the turn's durable citations
                // re-render as the collapsible sources block.
                sources = turn.sources.map { it.toChatSourceRef() }
            )
        )
    }

    /** Durable reference → its presentation twin. */
    private fun TurnAttachment.toEntryAttachment() =
        com.example.presentation.state.ChatEntryAttachment(
            id = id,
            name = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            storageUri = storageUri,
            artifactId = artifactId
        )

    /**
     * SESSIONS FEATURE → studio-side effect of a deleted session: clears
     * THIS conversation's binding + transcript when the deleted session was
     * the active one (the deletion itself is owned by SessionsViewModel).
     */
    fun onSessionDeleted(sessionId: String) {
        if (_state.value.activeSessionId == sessionId) {
            // RESIDUAL CLOSURE (P1): same view-release contract as
            // resetTranscriptView — the cleared conversation is detached from
            // any execution still running into the deleted session.
            detachRunningExecution(banner = null)
            // CHAT FINAL CLOSURE: the deleted session's approval mirrors are
            // gone with it (their durable events were deleted too).
            approvalMirrorScopes.clear()
            _state.update {
                it.copy(
                    activeSessionId = null,
                    studioSession = emptyList(),
                    timeline = emptyList(),
                    streamText = "",
                    liveExecution = null,
                    restoredAgentId = null
                )
            }
        }
    }

    /**
     * Resolves the canonical QUICK-CHAT agent: the durable registry agent
     * (seeded from CanonicalAgentCatalog) — so even agent-independent chat
     * keeps the SAME governed execution kernel (single execution authority).
     * Falls back to the in-memory catalog entry when the registry is not yet
     * available (first milliseconds of a cold start).
     */
    private fun resolveQuickChatAgent(): AgentDefinition {
        val fromRegistry = componentRegistry.getAgent(
            ConversationSessionService.QUICK_CHAT_AGENT_ID
        )
        if (fromRegistry != null) return fromRegistry
        val fromCatalog = com.example.application.agent.CanonicalAgentCatalog.defaults.firstOrNull {
            it.identity.id.value == ConversationSessionService.QUICK_CHAT_AGENT_ID
        }
        if (fromCatalog != null) {
            componentRegistry.registerAgent(fromCatalog)
            return fromCatalog
        }
        // Last-resort honest fallback: the general assistant canonical agent.
        return componentRegistry.listAgents().firstOrNull()
            ?: com.example.application.agent.CanonicalAgentCatalog.defaults.first()
    }

    /**
     * The explicit outcome of establishing the DURABLE session an execution
     * will persist into. CHAT FINAL CLOSURE: `Failed` is NOT silently
     * convertible to "run anyway" — the caller MUST fail closed.
     */
    private sealed interface DurableSessionEstablishment {
        data class Established(val sessionId: ConversationSessionId) : DurableSessionEstablishment
        data class Failed(val reason: String) : DurableSessionEstablishment
    }

    /**
     * The (session, workspace) an approval block's durable mirror lives in —
     * CHAT FINAL CLOSURE (approval durable-state invariant): captured when
     * the block's timeline event is written (or when a session is reopened
     * and rebuilt from its durable events), so a later decision mirrors onto
     * the event's OWN session instead of whatever session is live then.
     */
    private data class ApprovalMirrorScope(
        val sessionId: String,
        val workspaceId: String?
    )

    /**
     * approvalId → the durable mirror location of its approval block.
     */
    private val approvalMirrorScopes = mutableMapOf<String, ApprovalMirrorScope>()

    /**
     * Ensures the durable session an EXECUTION persists into — bound to the
     * EXECUTION-PINNED workspace/project (§2) and the mode/model accepted
     * with the send.
     *
     * CHAT FINAL CLOSURE (P1 session binding): the reuse candidate is the
     * SESSION CAPTURED AT SEND ACCEPTANCE ([sessionCandidateId]) — NEVER
     * the live activeSessionId. An execution accepted while session A was
     * active can therefore never slide into session B that the user opened
     * mid-execution: the candidate is either still valid (reused under the
     * pinned scope) or released/created under the execution's OWN pinned
     * scope.
     *
     * CHAT FINAL CLOSURE (P1 fail-closed): failures return [DurableSessionEstablishment.Failed]
     * — the caller refuses the execution (no durable session ⇒ no normal
     * persisted execution); nothing is swallowed into a silent null.
     *
     * FUNCTIONAL CLOSURE (§3): an existing session is reused ONLY when it is
     * still COMPATIBLE with what is being executed:
     *  - scope: the session belongs to the pinned workspace and its project
     *    matches the pinned project (a shared null-project session is
     *    usable from any project — its documented semantics);
     *  - mode: the session's recorded mode equals the executing mode;
     *  - agent (AGENT mode): when the conversation moved to a DIFFERENT
     *    agent, the session's binding is UPDATED EXPLICITLY through the
     *    session service (the documented boundary this architecture chose
     *    instead of silently executing Agent B inside Agent A's session —
     *    the durable row always names the agent that really executes).
     * Incompatible sessions are released (they stay durable and browsable)
     * and a NEW session is created for this execution.
     */
    private suspend fun ensureActiveSession(
        mode: ChatMode,
        agent: AgentDefinition,
        modelResourceId: String?,
        modelDisplayName: String?,
        pinnedWorkspaceId: String?,
        pinnedProjectId: Long?,
        executionTaskId: String,
        sessionCandidateId: String?
    ): DurableSessionEstablishment {
        // CHAT FINAL CLOSURE (race probe): parks BEFORE any session read —
        // the session-binding regression tests hold the establishment open
        // while the live active session is switched underneath it.
        sessionEstablishmentProbe()
        return try {
            val existingId = sessionCandidateId
            if (existingId != null) {
                val existing = conversationSessionService.getSessionWithTurns(
                    ConversationSessionId(existingId),
                    pinnedWorkspaceId,
                    // §1/§2: the reuse check runs under the EXECUTION-PINNED
                    // project scope (a session of another project's scope is
                    // indistinguishable from nonexistent — the same boundary
                    // the browser honors).
                    expectedProjectId = pinnedProjectId
                )?.session
                if (existing != null) {
                    val scopeCompatible =
                        existing.projectId == null || existing.projectId == pinnedProjectId
                    val modeCompatible = existing.mode == mode
                    if (scopeCompatible && modeCompatible) {
                        if (mode == ChatMode.AGENT &&
                            existing.agentId != agent.identity.id.value
                        ) {
                            // §3: EXPLICIT binding update — the durable row
                            // names the agent that really executes now.
                            conversationSessionService.setSessionAgent(
                                sessionId = existing.id,
                                agentId = agent.identity.id.value,
                                agentName = agent.identity.name,
                                workspaceId = pinnedWorkspaceId
                            )
                        }
                        return DurableSessionEstablishment.Established(existing.id)
                    }
                    // Incompatible → release the binding (the session stays
                    // durable + browsable in its own scope) and create a new
                    // one for THIS execution.
                    if (currentExecutionTaskId == executionTaskId) {
                        _state.update { it.copy(activeSessionId = null) }
                    }
                }
            }
            val session = conversationSessionService.createSession(
                mode = mode,
                agentId = if (mode == ChatMode.AGENT) agent.identity.id.value else null,
                agentName = if (mode == ChatMode.AGENT) agent.identity.name else null,
                modelResourceId = modelResourceId,
                modelDisplayName = modelDisplayName,
                // GAP-14: bind to the pinned project (null = shared session).
                projectId = pinnedProjectId,
                // FUNCTIONAL CLOSURE (§2): the pinned workspace — NOT the
                // current provider (a mid-execution switch must not hijack
                // the new session into another workspace).
                workspaceId = pinnedWorkspaceId
            )
            if (currentExecutionTaskId == executionTaskId) {
                _state.update { it.copy(activeSessionId = session.id.value) }
            }
            DurableSessionEstablishment.Established(session.id)
        } catch (failure: Exception) {
            DurableSessionEstablishment.Failed(
                failure.localizedMessage ?: failure::class.simpleName ?: "فشل غير معروف"
            )
        }
    }

    /**
     * Persists one finished turn to the durable session — with the
     * EXECUTION-PINNED workspace (§2: a mid-execution workspace switch can
     * neither lose the turn nor write it into the wrong workspace).
     * Fire-and-forget with an honest diagnostic banner on failure.
     *
     * RESIDUAL CLOSURE (persistence-failure leak): the FAILURE side-effect is
     * now bound to the execution's OWN identity too — a DETACHED execution
     * still persists (and may still fail) under its pinned scope, but its
     * failure may NEVER mutate the current view (scope B's diagnostic banner
     * stays clean); it is recorded through the non-UI sink instead.
     */
    private fun persistTurnDurably(
        sessionId: ConversationSessionId?,
        prompt: String,
        answer: String,
        agentName: String,
        agentRole: String,
        modelResourceId: String?,
        tokensConsumed: Int,
        durationMs: Long,
        isSuccessful: Boolean,
        eventCount: Int,
        attachments: List<TurnAttachment> = emptyList(),
        sources: List<TurnSourceRef> = emptyList(),
        pinnedWorkspaceId: String? = null,
        pinnedProjectId: Long? = null,
        executionTaskId: String
    ) {
        val id = sessionId ?: return
        viewModelScope.launch {
            try {
                // CHAT FINAL CLOSURE (P1 appendTurn result): the service's
                // null return is an AUTHORIZATION/SESSION FAILURE (nothing
                // written) — it is treated EXACTLY like a thrown failure:
                // the turn is NOT considered saved, the user sees the honest
                // banner, and the first-turn titling never runs against a
                // turn that was not persisted.
                val appended = conversationSessionService.appendTurn(
                    sessionId = id,
                    prompt = prompt,
                    answer = answer,
                    agentName = agentName,
                    agentRole = agentRole,
                    modelResourceId = modelResourceId,
                    tokensConsumed = tokensConsumed,
                    durationMs = durationMs,
                    isSuccessful = isSuccessful,
                    eventCount = eventCount,
                    // FUNCTIONAL CLOSURE (§2): the execution's OWN workspace —
                    // the workspace-authorized write targets it even after a
                    // mid-execution switch.
                    workspaceId = pinnedWorkspaceId,
                    attachments = attachments,
                    sources = sources
                )
                if (appended == null) {
                    throw IllegalStateException(
                        "appendTurn did not persist the turn (authorization/missing-session under the pinned workspace)"
                    )
                }
                // First-turn titling: the default title becomes the prompt.
                // §1/§2: the read is project-scope-authorized under the
                // execution's OWN pinned project.
                // RESIDUAL CLOSURE (P6): the RENAME is workspace-authorized
                // under the pinned workspace too — a detached execution
                // completing while another workspace is active still titles
                // its OWN session (the provider's live workspace would make
                // the rename an authorized no-op and the session would keep
                // the default title forever).
                val session = conversationSessionService.getSessionWithTurns(
                    id,
                    pinnedWorkspaceId,
                    expectedProjectId = pinnedProjectId
                )?.session
                if (session != null && session.title == ConversationSessionService.DEFAULT_TITLE) {
                    conversationSessionService.titleFromPrompt(
                        sessionId = id,
                        prompt = prompt,
                        workspaceId = pinnedWorkspaceId
                    )
                }
            } catch (e: Exception) {
                // RESIDUAL CLOSURE (persistence-failure leak): the banner is
                // written ONLY while THIS execution is still the view's current
                // one — a detached execution's persistence failure is recorded
                // through the non-UI sink, never onto another scope's view.
                if (currentExecutionTaskId == executionTaskId) {
                    _state.update {
                        it.copy(diagnosticBanner = "تعذر حفظ دورة المحادثة بشكل دائم: ${e.localizedMessage ?: e.message}")
                    }
                } else {
                    detachedPersistenceFailureSink(
                        "durable turn persistence failed for detached execution $executionTaskId " +
                                "(workspace=${pinnedWorkspaceId}, session=${id.value}): " +
                                "${e::class.simpleName}: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Appends a finished conversational turn to the Studio session transcript.
     * Called from the terminal execution events (Completed / Error).
     */
    private fun appendStudioTurn(
        state: StudioUiState,
        prompt: String,
        agentName: String,
        agentRole: String,
        answer: String,
        isSuccessful: Boolean,
        modelResourceId: String? = null,
        /** P1: the execution's OWN usage/count (never the live view gauges). */
        tokensConsumed: Int = state.currentTokensConsumed,
        eventCount: Int = state.executionLog.size,
        turnId: String
    ): List<StudioTurn> {
        val turn = StudioTurn(
            id = turnId,
            prompt = prompt,
            agentName = agentName,
            agentRole = agentRole,
            answer = answer,
            eventCount = eventCount,
            tokensConsumed = tokensConsumed,
            durationMs = state.sessionTurnStartMs.takeIf { it > 0L }
                ?.let { System.currentTimeMillis() - it } ?: 0L,
            isSuccessful = isSuccessful,
            modelResourceId = modelResourceId
        )
        return state.studioSession + turn
    }

    /**
     * Cancels THIS conversation's execution (user action). The workflow
     * execution flag is deliberately NOT touched here any more: the studio
     * cancel never actually cancelled the workflow job (it runs in its own
     * scope), so resetting its flag while the workflow continued was a
     * display lie — each feature now resets only its own flag.
     *
     * CHAT WORKSPACE (Task 1): the cancelled execution stays visible as a
     * CANCELLED lifecycle block (with its partial stream, if any) attached to
     * the user message that started it — cancelled work leaves an honest
     * trace, and is never persisted as a durable turn (the kernel never
     * emitted a terminal result for it).
     */
    fun cancelExecution() {
        val taskId = currentExecutionTaskId
        // ORDER MATTERS (the race the full-suite load exposed): the state
        // must reach its terminal CANCELLED shape BEFORE the job is killed —
        // the collector's finally-block defensive check reads the phase, and
        // a cancel-first ordering lets it clear the block between the job's
        // death and this update (the block vanished instead of staying as
        // the cancelled trace).
        _state.update {
            it.copy(
                isExecuting = false,
                liveExecution = it.liveExecution?.copy(phase = ExecutionPhase.CANCELLED),
                diagnosticBanner = "تم إلغاء العملية بواسطة المستخدم."
            )
        }
        if (taskId != null) {
            currentExecutionTaskId = null
            com.example.application.execution.ExecutionHost.cancel(taskId)
        }
        appContext?.let {
            com.example.application.execution.AgentExecutionForegroundService.stop(it)
        }
    }

    /**
     * Executes the current prompt. [agent] is the shared-catalog selection
     * resolved by the screen (null is only honest in QUICK_CHAT mode).
     *
     * P0-B/P0-C (Chat Workspace Task 1): on acceptance the draft is cleared
     * and the USER MESSAGE becomes visible IMMEDIATELY — one atomic state
     * update, so no intermediate frame can show a cleared composer without
     * the sent message, and no path writes the old text back while the
     * execution runs.
     *
     * CHAT CAPABILITIES (Task 2 §5/§6): [attachments] are the durable
     * references built by the capability layer (SAF-picked, sandbox-imported,
     * artifact-registered). Text-like attachments ride the LLM request as a
     * bounded evidence digest; the USER MESSAGE displays clean text + chips.
     *
     * FUNCTIONAL CLOSURE (§14): a failed/blocked grounding build ABORTS the
     * send with a visible error — the prompt draft is kept and the caller's
     * [onSendAborted] hands the attachments back (a send is never silently
     * degraded to "no evidence" while the UI still shows attachment chips).
     * §15: an ATTACHMENT-ONLY send (empty prompt) is refused up front — the
     * current text-only pipeline cannot honor it (Vision is not operational),
     * so a meaningless request never reaches the LLM.
     */
    fun executePrompt(
        agent: AgentDefinition?,
        attachments: List<TurnAttachment> = emptyList(),
        onSendAborted: ((List<TurnAttachment>) -> Unit)? = null
    ): Boolean {
        val current = _state.value
        val prompt = current.promptInput.trim()
        if (current.isExecuting) return false
        if (prompt.isEmpty() && attachments.isEmpty()) return false

        // FUNCTIONAL CLOSURE (§15): attachment-only prompts are blocked
        // honestly — without an operational Vision capability there is no
        // meaningful request to send (the text-grounding digest is EVIDENCE
        // for a question, never a substitute for one).
        if (prompt.isEmpty() && attachments.isNotEmpty()) {
            _state.update {
                it.copy(
                    errorMessage = "أضف نصاً يوضح المطلوب مع المرفقات — تحليل الصور (Vision) غير مفعّل في هذا الإصدار، " +
                            "ولا يُرسل طلب بلا تعليمات."
                )
            }
            return false
        }

        // ------------------------------------------------------------------
        // QUICK CHAT vs AGENT MODE (report gap: "Quick Chat missing — the
        // chat path was hard-gated on `activeAgent ?: return`"). QUICK_CHAT
        // is AGENT-INDEPENDENT: the conversation binds to the canonical
        // quick-chat agent + the user-selected model — the user never has to
        // pick an agent. AGENT mode keeps the canonical agent binding (and
        // fails HONESTLY with an actionable message instead of a silent
        // no-op return). Validated BEFORE any timeline mutation so a gated
        // send never leaves a phantom user message.
        // ------------------------------------------------------------------
        val resolvedAgent: AgentDefinition = when (current.chatMode) {
            ChatMode.QUICK_CHAT -> resolveQuickChatAgent()
            ChatMode.AGENT -> agent ?: run {
                _state.update {
                    it.copy(errorMessage = "وضع الوكيل يتطلب اختيار وكيلاً من الكتالوج أولاً — أو بدّل إلى «محادثة سريعة».")
                }
                return false
            }
        }

        // RESIDUAL CLOSURE (P5 — execution-pinned grounding): the scope is
        // pinned SYNCHRONOUSLY at send acceptance. The grounding digest AND
        // the execution's pinned context (§2) are the SAME capture — a
        // workspace/project switch landing between acceptance and the send
        // coroutine can neither ground another scope's sandbox nor pin the
        // execution into the wrong scope.
        val pinnedWorkspaceId = runCatching {
            workspaceRuntimeService.activeWorkspaceIdOrNull()
        }.getOrNull()
        val pinnedProjectId = runCatching {
            workspaceRuntimeService.activeProjectIdOrNull()
        }.getOrNull()
        // The attachment grounding digest is built BEFORE the send is
        // accepted (bounded sandbox reads) so the atomic user-message update
        // is never delayed by IO.
        viewModelScope.launch {
            if (attachments.isEmpty()) {
                executeText(
                    prompt = prompt,
                    resolvedAgent = resolvedAgent,
                    appendUserEntry = true,
                    attachments = attachments,
                    groundingDigest = "",
                    anchorUserEntryId = null,
                    scopeWorkspaceId = pinnedWorkspaceId,
                    scopeProjectId = pinnedProjectId
                )
            } else {
                // FUNCTIONAL CLOSURE (§14): the grounding outcome carries its
                // failures — a blocked read aborts the send VISIBLY (the
                // drafts are handed back through the caller's callback), it
                // never silently degrades to "no digest". The ONE honest
                // exception: a composition with NO attachment coordinator
                // (the documented JVM-test seam) has no grounding capability
                // at all — the attachment rides as a display-only reference
                // with an empty digest, exactly the Task-2 contract.
                val coordinator = attachmentCoordinator
                val outcome = if (coordinator == null) {
                    null
                } else {
                    runCatching {
                        // P5: the PINNED project — the digest reads the scope
                        // the send was accepted in, never the live one.
                        coordinator.buildGroundingDigest(
                            workspaceId = pinnedWorkspaceId ?: "",
                            projectId = pinnedProjectId,
                            attachments = attachments
                        )
                    }.getOrNull()
                }
                if (coordinator != null && (outcome == null || outcome.isFailed)) {
                    val reason = when {
                        outcome == null ->
                            "فشل تجهيز أدلة المرفقات — لم يُرسل الطلب. أعد المحاولة أو أزل المرفقات المعطلة."
                        else -> outcome.failures.joinToString("\n")
                    }
                    _state.update { it.copy(errorMessage = reason) }
                    onSendAborted?.invoke(attachments)
                    return@launch
                }
                executeText(
                    prompt = prompt,
                    resolvedAgent = resolvedAgent,
                    appendUserEntry = true,
                    attachments = attachments,
                    groundingDigest = outcome?.digest ?: "",
                    anchorUserEntryId = null,
                    scopeWorkspaceId = pinnedWorkspaceId,
                    scopeProjectId = pinnedProjectId
                )
            }
        }
        // Synchronous acceptance — the screen clears ITS OWN draft state
        // (the capability layer's chips) only when the send was really taken.
        return true
    }

    /**
     * MESSAGE ACTION (FUNCTIONAL CLOSURE §6 — targeted regenerate): re-
     * executes the USER MESSAGE that the tapped assistant entry answers —
     * identified by scanning BACK from [assistantEntryId] to the nearest
     * preceding user message (capability results and approval blocks in
     * between are transparently skipped). The user message is NOT
     * duplicated — the existing entry anchors the new execution; only the
     * lifecycle block and the new result follow it. This replaces the old
     * "lastOrNull { it is ChatEntry.User }" targeting that silently
     * regenerated whatever happened to be LAST.
     *
     * CHAT CAPABILITIES (Task 2 §17): regeneration is NON-DESTRUCTIVE — the
     * previous result stays in the transcript (append-only history); the new
     * execution re-rides the SAME user message including its attachments.
     */
    fun regenerateFromAssistant(assistantEntryId: String, agent: AgentDefinition?) {
        val current = _state.value
        if (current.isExecuting) return
        val assistantIndex = current.timeline.indexOfFirst { it.id == assistantEntryId }
        if (assistantIndex < 0) return
        val targetUser = current.timeline.take(assistantIndex)
            .lastOrNull { it is ChatEntry.User } as? ChatEntry.User ?: return

        launchExecutionForUserEntry(
            targetUser = targetUser,
            resolvedAgent = resolveAgentForExecution(agent) ?: return
        )
    }

    /**
     * FUNCTIONAL CLOSURE (§6): resolves the executing agent for a
     * regenerate/retry path (same honest gating as [executePrompt] — a null
     * selection in AGENT mode surfaces the actionable error instead of a
     * silent no-op). Returns null when gated.
     */
    private fun resolveAgentForExecution(agent: AgentDefinition?): AgentDefinition? {
        return when (_state.value.chatMode) {
            ChatMode.QUICK_CHAT -> resolveQuickChatAgent()
            ChatMode.AGENT -> agent ?: run {
                _state.update {
                    it.copy(errorMessage = "وضع الوكيل يتطلب اختيار وكيلاً من الكتالوج أولاً — أو بدّل إلى «محادثة سريعة».")
                }
                null
            }
        }
    }

    /** Shared launch path for targeted regenerate / approval retry. */
    private fun launchExecutionForUserEntry(
        targetUser: ChatEntry.User,
        resolvedAgent: AgentDefinition
    ) {
        val attachments = targetUser.attachments.map { it.toTurnAttachment() }
        // P5: the scope is pinned SYNCHRONOUSLY at action time — the retry's
        // grounding and its execution share ONE captured context.
        val pinnedWorkspaceId = runCatching {
            workspaceRuntimeService.activeWorkspaceIdOrNull()
        }.getOrNull()
        val pinnedProjectId = runCatching {
            workspaceRuntimeService.activeProjectIdOrNull()
        }.getOrNull()
        viewModelScope.launch {
            if (attachments.isEmpty()) {
                executeText(
                    prompt = targetUser.text,
                    resolvedAgent = resolvedAgent,
                    appendUserEntry = false,
                    attachments = attachments,
                    groundingDigest = "",
                    anchorUserEntryId = targetUser.id,
                    scopeWorkspaceId = pinnedWorkspaceId,
                    scopeProjectId = pinnedProjectId
                )
            } else {
                // §14: the same honest grounding contract as executePrompt —
                // real failures BLOCK the retry (visible error); the
                // coordinator-less composition keeps its documented seam.
                val coordinator = attachmentCoordinator
                val outcome = if (coordinator == null) {
                    null
                } else {
                    runCatching {
                        // P5: the PINNED project (the action-time capture).
                        coordinator.buildGroundingDigest(
                            workspaceId = pinnedWorkspaceId ?: "",
                            projectId = pinnedProjectId,
                            attachments = attachments
                        )
                    }.getOrNull()
                }
                if (coordinator != null && (outcome == null || outcome.isFailed)) {
                    val reason = when {
                        outcome == null ->
                            "فشل تجهيز أدلة المرفقات — لم تُعِد المحاولة. أعد المحاولة أو أزل المرفقات المعطلة."
                        else -> outcome.failures.joinToString("\n")
                    }
                    _state.update { it.copy(errorMessage = reason) }
                    return@launch
                }
                executeText(
                    prompt = targetUser.text,
                    resolvedAgent = resolvedAgent,
                    appendUserEntry = false,
                    attachments = attachments,
                    groundingDigest = outcome?.digest ?: "",
                    anchorUserEntryId = targetUser.id,
                    scopeWorkspaceId = pinnedWorkspaceId,
                    scopeProjectId = pinnedProjectId
                )
            }
        }
    }

    /** Presentation attachment → the durable reference it mirrors. */
    private fun com.example.presentation.state.ChatEntryAttachment.toTurnAttachment() =
        TurnAttachment(
            id = id,
            name = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            storageUri = storageUri,
            artifactId = artifactId
        )

    /**
     * The shared execution core used by [executePrompt] (fresh prompt, draft
     * cleared, user entry appended) and the targeted regenerate paths
     * (existing user entry anchors the conversation).
     *
     * CHAT CAPABILITIES (Task 2):
     *  - [attachments] ride the user entry as chips and persist with the
     *    durable turn (§16); [groundingDigest] is the bounded evidence block
     *    appended to the LLM-side prompt (§6) — the DISPLAYED text stays the
     *    user's own words, unmodified.
     *
     * FUNCTIONAL CLOSURE:
     *  - §2: the workspace/project are PINNED at launch — session creation,
     *    turn persistence, and the workspace-scoped task constraints all
     *    read the pinned values, never the current provider.
     *  - §8: the live block is ANCHORED to its originating user entry
     *    ([anchorUserEntryId] / the freshly appended user entry) so the
     *    timeline renders it exactly there — no more "always at the end"
     *    chronology (§11).
     *  - §5: the model the DECISION LAYER actually selected is harvested
     *    from the real SELECT_MODEL decision event and recorded on the
     *    assistant entry + the durable turn.
     */
    private fun executeText(
        prompt: String,
        resolvedAgent: AgentDefinition,
        appendUserEntry: Boolean,
        attachments: List<TurnAttachment> = emptyList(),
        groundingDigest: String = "",
        anchorUserEntryId: String? = null,
        /** P5: the send-time pinned workspace (null → read current, internal calls only). */
        scopeWorkspaceId: String? = null,
        /** P5: the send-time pinned project (null → read current, internal calls only). */
        scopeProjectId: Long? = null
    ) {
        val current = _state.value

        // ------------------------------------------------------------------
        // CONVERSATION HISTORY (report gap: "Resume conversation / unified
        // continuity"): the recent transcript is replayed as LLM history so
        // the model keeps the thread of THIS session across turns and after
        // reopening a durable session.
        // ------------------------------------------------------------------
        val history = current.studioSession.takeLast(CONVERSATION_HISTORY_WINDOW)
            .flatMap { turn ->
                listOf(
                    com.example.domain.core.llm.LlmMessage(
                        role = com.example.domain.core.llm.MessageRole.USER,
                        content = turn.prompt
                    ),
                    com.example.domain.core.llm.LlmMessage(
                        role = com.example.domain.core.llm.MessageRole.ASSISTANT,
                        content = turn.answer
                    )
                )
            }

        // ------------------------------------------------------------------
        // USER-FACING EXACT MODEL SELECTION (report gap: "direct Model
        // Picker missing"): the selected model resource becomes a DURABLE
        // binding (assignedModelId) honoured by the decision layer — not a
        // floating preference. (When null, the decision layer picks — §5
        // harvests WHICH model it actually picked.)
        // ------------------------------------------------------------------
        val selectedModelId = current.selectedModelResourceId

        // CHAT CAPABILITIES (Task 2 §6): the LLM-side prompt is the user's
        // text plus the clearly-marked attachment evidence (when present).
        // The USER MESSAGE itself and the durable turn keep the clean text.
        val effectivePrompt = if (groundingDigest.isBlank()) {
            prompt
        } else {
            "$prompt\n\n$groundingDigest"
        }

        val executionTaskId = java.util.UUID.randomUUID().toString()
        val sentAtMs = System.currentTimeMillis()
        val userEntryId = "user_$executionTaskId"
        // CHAT FINAL CLOSURE (P1 session binding): the SESSION CANDIDATE is
        // captured AT ACCEPTANCE (with the rest of the execution context).
        // The execution's persistence lifecycle (reuse check, turn append,
        // approval events, titling) uses THIS candidate end-to-end — never
        // the live activeSessionId, which the user may have switched to
        // another session by the time the kernel coroutine reaches its
        // session-establishment step.
        val sessionCandidateId = current.activeSessionId
        // §8/§11: the live block's anchor — the user entry this execution
        // belongs to (a regenerate anchors to the EXISTING entry).
        val liveAnchorId = when {
            appendUserEntry -> userEntryId
            else -> anchorUserEntryId
        } ?: "user_$executionTaskId"

        // ONE atomic update: draft cleared (P0-B), user message visible
        // (P0-C) with its attachment chips, live execution opened, previous
        // lifecycle/stream closed.
        _state.update {
            it.copy(
                promptInput = if (appendUserEntry) "" else it.promptInput,
                timeline = if (appendUserEntry) {
                    it.timeline + ChatEntry.User(
                        id = userEntryId,
                        text = prompt,
                        attachments = attachments.map { attachment ->
                            com.example.presentation.state.ChatEntryAttachment(
                                id = attachment.id,
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                                sizeBytes = attachment.sizeBytes,
                                storageUri = attachment.storageUri,
                                artifactId = attachment.artifactId
                            )
                        }
                    )
                } else {
                    it.timeline
                },
                isExecuting = true,
                streamText = "",
                executionLog = emptyList(),
                liveExecution = LiveExecutionState(
                    executionId = executionTaskId,
                    phase = ExecutionPhase.QUEUED,
                    startedAtMs = sentAtMs,
                    originUserEntryId = liveAnchorId
                ),
                sessionTurnStartMs = sentAtMs,
                isDegraded = false,
                degradedReason = null,
                diagnosticBanner = null,
                errorMessage = null
            )
        }

        // Audit 2026 fix: the execution now runs in the APPLICATION scope
        // (ExecutionHost) instead of viewModelScope — leaving the screen no
        // longer kills a live task, and the foreground service shell keeps
        // the process priority high while the agent loop is running.
        // GAP-CLOSURE P0-01: the execution is keyed by its taskId — launching
        // a second task no longer cancels the first, and cancel() targets
        // exactly THIS execution.
        currentExecutionTaskId = executionTaskId
        // P1-08 hardening: the selected agent IS the executing agent —
        // idempotent registration into the runtime registry.
        componentRegistry.registerAgent(resolvedAgent)

        // ------------------------------------------------------------------
        // FUNCTIONAL CLOSURE (§2): EXECUTION-PINNED CONTEXT — everything the
        // execution persists or attributes is captured NOW, before the kernel
        // runs: the workspace (ExecutionHost attribution + authorization),
        // the project (session creation), and the workspace-scoped task
        // constraints. A mid-execution workspace/project switch (§1 detaches
        // the view) can neither hijack this execution's persistence nor lose
        // its turn.
        // ------------------------------------------------------------------
        val turnStartedAt = System.currentTimeMillis()
        val pinnedWorkspaceId = scopeWorkspaceId
            ?: runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }.getOrNull()
        val pinnedProjectId = scopeProjectId
            ?: runCatching { workspaceRuntimeService.activeProjectIdOrNull() }.getOrNull()
        val pinnedConstraints = com.example.domain.core.task.TaskConstraints(
            autonomyPolicy = pinnedWorkspaceId?.let { wsId ->
                runCatching { workspaceRuntimeService.activeWorkspace.value }
                    .getOrNull()
                    ?.takeIf { it.id == wsId }
                    ?.settings?.get("autonomyPolicy")
                    ?.let { name -> runCatching { AutonomyPolicy.valueOf(name) }.getOrNull() }
            } ?: AutonomyPolicy.SUPERVISED
        )
        com.example.application.execution.ExecutionHost.launch(executionTaskId, pinnedWorkspaceId) {
            var sessionId: ConversationSessionId? = null
            // Terminal-sequence counter: one execution can emit MULTIPLE
            // terminal events (the documented provider-error → fallback
            // quirk) — each lands as its own assistant entry with a stable,
            // collision-free id (the millisecond-based ids could collide
            // and crash LazyColumn keys).
            var terminalSeq = 0
            // RESIDUAL CLOSURE (P1): execution-OWNED mirrors — the values the
            // durable turn needs (stream text, token usage, event count) are
            // accumulated per EXECUTION, never read back from the VIEW state
            // (a detached execution completing while another scope's state is
            // live would otherwise persist THAT scope's values — a cross-
            // scope leak into durable storage).
            val executionStream = StringBuilder()
            var executionTokens = 0
            var executionEventCount = 0
            // CHAT CAPABILITIES (Task 2 §11): the REAL citation chains the
            // execution collected (search intelligence) — projected onto the
            // assistant entry as collapsible sources at completion time.
            val collectedSources = mutableListOf<com.example.presentation.state.ChatSourceRef>()
            // FUNCTIONAL CLOSURE (§5): the model the decision layer ACTUALLY
            // selected (harvested from the real SELECT_MODEL decision).
            var effectiveModelId: String? = null
            // FUNCTIONAL CLOSURE (§8): once THIS execution hit a consent
            // request, any FOLLOW-UP kernel error is a cascade consequence of
            // the denial — it must NOT become a failed assistant entry nor
            // clear the honest AWAITING_APPROVAL lifecycle.
            var approvalRequested = false
            try {
                when (val establishment = ensureActiveSession(
                    mode = current.chatMode,
                    agent = resolvedAgent,
                    modelResourceId = selectedModelId,
                    modelDisplayName = current.selectedModelDisplayName,
                    pinnedWorkspaceId = pinnedWorkspaceId,
                    pinnedProjectId = pinnedProjectId,
                    executionTaskId = executionTaskId,
                    sessionCandidateId = sessionCandidateId
                )) {
                    is DurableSessionEstablishment.Established ->
                        sessionId = establishment.sessionId
                    is DurableSessionEstablishment.Failed -> {
                        // --------------------------------------------------
                        // CHAT FINAL CLOSURE (P1 fail-closed): NO durable
                        // session ⇒ NO normal persisted execution. The LLM
                        // is never invoked as if persistence existed; the
                        // user sees an explicit failure state (the message
                        // stays with its FAILED lifecycle block so the
                        // retry affordances remain honest).
                        // --------------------------------------------------
                        if (currentExecutionTaskId == executionTaskId) {
                            _state.update {
                                it.copy(
                                    isExecuting = false,
                                    liveExecution = it.liveExecution?.copy(
                                        phase = ExecutionPhase.FAILED,
                                        phaseDetail = null
                                    ),
                                    errorMessage = "تعذر إنشاء/استعادة الجلسة الدائمة للتنفيذ" +
                                            " (${establishment.reason}) — لم يُنفَّذ الطلب حفاظاً على دوام المحادثة. أعد المحاولة."
                                )
                            }
                        } else {
                            detachedPersistenceFailureSink(
                                "durable session establishment failed for detached execution " +
                                        "$executionTaskId (workspace=$pinnedWorkspaceId): ${establishment.reason}"
                            )
                        }
                        return@launch
                    }
                }
                // FAIL-CLOSED NETWORK DEFAULT (report gap: "missing monitor =
                // network available is fail-open"): when no monitor is wired
                // we assume OFFLINE, so OFFLINE/degraded policies engage
                // honestly instead of silently attempting remote calls.
                val netAvailable = networkMonitorProvider?.isNetworkAvailable?.value ?: false
                executeAgentTaskUseCase(
                    agent = resolvedAgent,
                    prompt = effectivePrompt,
                    taskId = executionTaskId,
                    history = history,
                    assignedModelId = selectedModelId,
                    networkPolicy = current.networkPolicy,
                    isNetworkAvailable = netAvailable,
                    // REPAIR ORDER §3B — the execution mode drives the TASK
                    // CONTRACT (Quick Chat = legitimate generation-only mode).
                    chatMode = current.chatMode.name,
                    // REPAIR ORDER §20 — task constraints sourced from the
                    // AUTHORITATIVE workspace policy (never UI-local state).
                    // FUNCTIONAL CLOSURE (§2): the PINNED policy (captured at
                    // launch), never the current workspace state.
                    constraints = pinnedConstraints
                ).collect { event ->
                    // RESIDUAL CLOSURE (P1): execution-OWNED mirrors first —
                    // they advance for EVERY event of THIS execution,
                    // attached or detached.
                    executionEventCount++
                    if (event is ExecutionEvent.ContentChunk) {
                        executionStream.append(event.deltaText)
                    }
                    if (event is ExecutionEvent.UsageBudgetUpdate) {
                        executionTokens = event.promptTokens + event.completionTokens
                    }
                    // CROSS-FEATURE PROJECTIONS (ADR-6 slice 2): the events
                    // other features mirror (activity trace, decision
                    // case-base/uncertainty) are published on the signal bus
                    // instead of being written into a shared UiState. These
                    // mirrors are PROCESS-WIDE by design (executions are
                    // process-wide — ExecutionHost), so they keep receiving
                    // detached executions' events too.
                    when (event) {
                        is ExecutionEvent.Started,
                        is ExecutionEvent.DecisionMade,
                        is ExecutionEvent.ObservationRecorded,
                        is ExecutionEvent.Completed,
                        is ExecutionEvent.Error ->
                            signalBus.emit(StudioSignal.ExecutionEvent(event))
                        else -> Unit
                    }
                    // §5: harvest the model the decision layer actually
                    // SELECTED (the payload's resourceId of a SELECT_MODEL
                    // action) — the honest runtime binding.
                    if (event is ExecutionEvent.DecisionMade &&
                        event.decision.chosenAction.type ==
                        com.example.domain.core.decision.DecisionActionType.SELECT_MODEL
                    ) {
                        (event.decision.chosenAction.payload["resourceId"] as? String)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { effectiveModelId = it }
                    }

                    // ----------------------------------------------------------------
                    // RESIDUAL CLOSURE (P1): EXECUTION-IDENTITY GATE.
                    // A detached execution (scope change §1, view release,
                    // or a replaced current execution) may still PERSIST its
                    // terminal outcome under its pinned context (§2) — but
                    // it may NEVER mutate this view: no stream text, no log
                    // lines, no token gauges, no lifecycle phase, no
                    // isExecuting, no error/degraded state, no approval
                    // blocks. The gate compares EXECUTION IDENTITY (the
                    // task key), not a global boolean.
                    // ----------------------------------------------------------------
                    if (currentExecutionTaskId != executionTaskId) {
                        when (event) {
                            is ExecutionEvent.Completed -> if (!approvalRequested) {
                                // §2: the detached turn lands in ITS OWN pinned
                                // session — with ITS OWN stream/usage/count.
                                persistTurnDurably(
                                    sessionId = sessionId,
                                    prompt = prompt,
                                    answer = event.finalText.ifBlank { executionStream.toString() },
                                    agentName = resolvedAgent.identity.name,
                                    agentRole = resolvedAgent.identity.role.displayName,
                                    modelResourceId = effectiveModelId ?: selectedModelId,
                                    tokensConsumed = executionTokens,
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                    isSuccessful = true,
                                    eventCount = executionEventCount,
                                    attachments = attachments,
                                    sources = collectedSources.map { it.toTurnSourceRef() },
                                    pinnedWorkspaceId = pinnedWorkspaceId,
                                    pinnedProjectId = pinnedProjectId,
                                    executionTaskId = executionTaskId
                                )
                            }
                            is ExecutionEvent.Error -> when {
                                // §8/§9: the consent request is durable in the
                                // execution's OWN session (save-to-originating);
                                // it NEVER surfaces in another scope's view.
                                event.failureCode == APPROVAL_REQUIRED_CODE -> {
                                    approvalRequested = true
                                    requestApprovalBlock(
                                        kernelExecutionId = event.executionId,
                                        executionTaskId = executionTaskId,
                                        sessionId = sessionId,
                                        pinnedWorkspaceId = pinnedWorkspaceId
                                    )
                                }
                                approvalRequested -> Unit // §8 cascade — nothing further
                                else -> persistTurnDurably(
                                    sessionId = sessionId,
                                    prompt = prompt,
                                    answer = executionStream.toString().ifBlank { event.message },
                                    agentName = resolvedAgent.identity.name,
                                    agentRole = resolvedAgent.identity.role.displayName,
                                    modelResourceId = effectiveModelId ?: selectedModelId,
                                    tokensConsumed = executionTokens,
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                    isSuccessful = false,
                                    eventCount = executionEventCount,
                                    attachments = attachments,
                                    pinnedWorkspaceId = pinnedWorkspaceId,
                                    pinnedProjectId = pinnedProjectId,
                                    executionTaskId = executionTaskId
                                )
                            }
                            else -> Unit
                        }
                        return@collect
                    }

                    // ATTACHED path: terminal side effects run BEFORE the
                    // state-update lambda (RESIDUAL CLOSURE P1: a persist
                    // call inside MutableStateFlow.update could re-run under
                    // CAS contention and duplicate durable turns; the update
                    // lambda below is PURE).
                    if (event is ExecutionEvent.Completed && !approvalRequested) {
                        persistTurnDurably(
                            sessionId = sessionId,
                            prompt = prompt,
                            answer = event.finalText.ifBlank { executionStream.toString() },
                            agentName = resolvedAgent.identity.name,
                            agentRole = resolvedAgent.identity.role.displayName,
                            modelResourceId = effectiveModelId ?: selectedModelId,
                            tokensConsumed = executionTokens,
                            durationMs = System.currentTimeMillis() - turnStartedAt,
                            isSuccessful = true,
                            eventCount = executionEventCount,
                            attachments = attachments,
                            sources = collectedSources.map { it.toTurnSourceRef() },
                            pinnedWorkspaceId = pinnedWorkspaceId,
                            pinnedProjectId = pinnedProjectId,
                            executionTaskId = executionTaskId
                        )
                    }
                    if (event is ExecutionEvent.Error &&
                        event.failureCode != APPROVAL_REQUIRED_CODE && !approvalRequested
                    ) {
                        persistTurnDurably(
                            sessionId = sessionId,
                            prompt = prompt,
                            answer = executionStream.toString().ifBlank { event.message },
                            agentName = resolvedAgent.identity.name,
                            agentRole = resolvedAgent.identity.role.displayName,
                            modelResourceId = effectiveModelId ?: selectedModelId,
                            tokensConsumed = executionTokens,
                            durationMs = System.currentTimeMillis() - turnStartedAt,
                            isSuccessful = false,
                            eventCount = executionEventCount,
                            attachments = attachments,
                            pinnedWorkspaceId = pinnedWorkspaceId,
                            pinnedProjectId = pinnedProjectId,
                            executionTaskId = executionTaskId
                        )
                    }
                    // CHAT FINAL CLOSURE (nested-update purity — the CAS-retry
                    // race): the consent request's side effects (the durable
                    // event write + the view block) run BEFORE the state-update
                    // lambda, exactly like the persist calls above. Calling
                    // requestApprovalBlock INSIDE the update lambda nested a
                    // second _state.update inside the CAS-retried lambda — under
                    // contention the retry re-ran the side effects and raced the
                    // execution's terminal finally-guard (the AWAITING_APPROVAL
                    // block could be transiently erased and the retry path then
                    // saw a live-less state). The update lambda below stays PURE.
                    if (event is ExecutionEvent.Error &&
                        event.failureCode == APPROVAL_REQUIRED_CODE
                    ) {
                        approvalRequested = true
                        requestApprovalBlock(
                            kernelExecutionId = event.executionId,
                            executionTaskId = executionTaskId,
                            sessionId = sessionId,
                            pinnedWorkspaceId = pinnedWorkspaceId
                        )
                    }

                    _state.update { state ->
                        val updatedLogs = state.executionLog + event
                        val updatedLive = state.liveExecution?.let {
                            ExecutionLifecycleProjection.apply(it, event)
                        }
                        when (event) {
                            is ExecutionEvent.ActionCompleted -> {
                                // CHAT CAPABILITIES (Task 2 §11): harvest the
                                // REAL citation chains a search action carried
                                // in its observation — they become the
                                // assistant entry's collapsible sources.
                                (event.observation.outputData["searchCitations"] as? List<*>)
                                    ?.forEach { chain ->
                                        (chain as? com.example.domain.core.search.intelligence.CitationChain)
                                            ?.let {
                                                collectedSources += com.example.presentation.state.ChatSourceRef(
                                                    title = it.itemTitle,
                                                    url = it.itemUrl,
                                                    providerId = it.providerId,
                                                    confidenceScore = it.confidenceScore
                                                )
                                            }
                                    }
                                state.copy(executionLog = updatedLogs, liveExecution = updatedLive)
                            }
                            is ExecutionEvent.DecisionMade -> {
                                state.copy(executionLog = updatedLogs, liveExecution = updatedLive)
                            }
                            is ExecutionEvent.ObservationRecorded -> {
                                state.copy(executionLog = updatedLogs, liveExecution = updatedLive)
                            }
                            is ExecutionEvent.ContentChunk -> {
                                state.copy(
                                    streamText = state.streamText + event.deltaText,
                                    executionLog = updatedLogs,
                                    liveExecution = updatedLive
                                )
                            }
                            is ExecutionEvent.Degraded -> {
                                state.copy(
                                    isDegraded = true,
                                    degradedReason = event.reason,
                                    diagnosticBanner = event.message,
                                    executionLog = updatedLogs,
                                    liveExecution = updatedLive
                                )
                            }
                            is ExecutionEvent.UsageBudgetUpdate -> {
                                state.copy(
                                    currentTokensConsumed = event.promptTokens + event.completionTokens,
                                    sessionTotalTokens = event.totalSessionTokens,
                                    remainingBudget = event.remainingBudgetTokens,
                                    executionLog = updatedLogs,
                                    liveExecution = updatedLive
                                )
                            }
                            is ExecutionEvent.Completed -> {
                                terminalSeq++
                                if (approvalRequested) {
                                    // §8 CASCADE: a synthesized completion
                                    // arriving after a consent request is NOT
                                    // the answer the user asked for — the
                                    // conversation waits at AWAITING_APPROVAL
                                    // for the resolution + the honest retry.
                                    // No assistant entry, no turn fabricated.
                                    state.copy(
                                        isExecuting = false,
                                        executionLog = updatedLogs,
                                        liveExecution = if (currentExecutionTaskId == executionTaskId) {
                                            state.liveExecution?.copy(phase = ExecutionPhase.AWAITING_APPROVAL)
                                        } else {
                                            state.liveExecution
                                        }
                                    )
                                } else {
                                    // P1: the persist ran ABOVE (outside the
                                    // state-update lambda); the update itself
                                    // is PURE view mutation.
                                    val answer = event.finalText.ifBlank { executionStream.toString() }
                                    val honestModelId = effectiveModelId ?: selectedModelId
                                    // P0-D: the finished text has EXACTLY ONE
                                    // display path — the assistant entry. The
                                    // stream text and the live block collapse;
                                    // their numbers survive as the entry's
                                    // execution summary.
                                    state.copy(
                                        isExecuting = false,
                                        streamText = "",
                                        liveExecution = null,
                                        executionLog = updatedLogs,
                                        timeline = state.timeline + ChatEntry.Assistant(
                                            id = "asst_${executionTaskId}_$terminalSeq",
                                            text = answer,
                                            agentName = resolvedAgent.identity.name,
                                            agentRole = resolvedAgent.identity.role.displayName,
                                            isSuccessful = true,
                                            // §5: the model that ACTUALLY served
                                            // the request (decision-layer truth),
                                            // falling back to the user's pin.
                                            modelResourceId = honestModelId,
                                            tokensConsumed = executionTokens,
                                            durationMs = System.currentTimeMillis() - sentAtMs,
                                            eventCount = executionEventCount,
                                            isDegraded = event.isDegraded,
                                            // §11: the real search citations collected
                                            // during this execution ride the entry.
                                            sources = collectedSources.toList()
                                        ),
                                        studioSession = appendStudioTurn(
                                            state = state,
                                            prompt = prompt,
                                            agentName = resolvedAgent.identity.name,
                                            agentRole = resolvedAgent.identity.role.displayName,
                                            answer = answer,
                                            isSuccessful = true,
                                            modelResourceId = honestModelId,
                                            tokensConsumed = executionTokens,
                                            eventCount = executionEventCount,
                                            turnId = "turn_${executionTaskId}_$terminalSeq"
                                        )
                                    )
                                }
                            }
                            is ExecutionEvent.Error -> {
                                terminalSeq++
                                // --------------------------------------------------
                                // FUNCTIONAL CLOSURE (§8): a consent-blocked
                                // execution is an AWAITING_APPROVAL state, NOT
                                // an assistant failure. The failed assistant
                                // entry is NOT appended; the live lifecycle
                                // block stays visible in the honest
                                // AWAITING_APPROVAL phase; the inline approval
                                // block carries the consent path. No turn is
                                // fabricated — the exchange is durable through
                                // its approval block (§9) and completes when
                                // the user resolves the consent and retries.
                                // --------------------------------------------------
                                if (event.failureCode == APPROVAL_REQUIRED_CODE) {
                                    // CHAT FINAL CLOSURE: the consent request's
                                    // side effects (durable write + view block)
                                    // ran ABOVE, OUTSIDE this PURE lambda — only
                                    // the honest lifecycle mutation remains here.
                                    state.copy(
                                        isExecuting = false,
                                        executionLog = updatedLogs,
                                        liveExecution = (updatedLive ?: state.liveExecution)
                                            ?.copy(phase = ExecutionPhase.AWAITING_APPROVAL)
                                    )
                                } else if (approvalRequested) {
                                    // §8 CASCADE: a follow-up error after the
                                    // consent request is a CONSEQUENCE of the
                                    // denial — keep the honest AWAITING_APPROVAL
                                    // lifecycle (the block + retry path), never a
                                    // fabricated failed assistant entry.
                                    state.copy(
                                        isExecuting = false,
                                        executionLog = updatedLogs,
                                        liveExecution = state.liveExecution?.copy(
                                            phase = ExecutionPhase.AWAITING_APPROVAL
                                        )
                                    )
                                } else {
                                    // P1: the persist ran ABOVE (outside the
                                    // state-update lambda).
                                    val answer = executionStream.toString().ifBlank { event.message }
                                    state.copy(
                                        isExecuting = false,
                                        errorMessage = event.message,
                                        executionLog = updatedLogs,
                                        liveExecution = null,
                                        streamText = "",
                                        timeline = state.timeline + ChatEntry.Assistant(
                                            id = "asst_${executionTaskId}_$terminalSeq",
                                            text = answer,
                                            agentName = resolvedAgent.identity.name,
                                            agentRole = resolvedAgent.identity.role.displayName,
                                            isSuccessful = false,
                                            modelResourceId = effectiveModelId ?: selectedModelId,
                                            tokensConsumed = executionTokens,
                                            durationMs = System.currentTimeMillis() - sentAtMs,
                                            eventCount = executionEventCount
                                        ),
                                        studioSession = appendStudioTurn(
                                            state = state,
                                            prompt = prompt,
                                            agentName = resolvedAgent.identity.name,
                                            agentRole = resolvedAgent.identity.role.displayName,
                                            answer = answer,
                                            isSuccessful = false,
                                            modelResourceId = effectiveModelId ?: selectedModelId,
                                            tokensConsumed = executionTokens,
                                            eventCount = executionEventCount,
                                            turnId = "turn_${executionTaskId}_$terminalSeq"
                                        )
                                    )
                                }
                            }
                            is ExecutionEvent.Cancelled -> {
                                // System-side cancellation event: the
                                // lifecycle block stays visible with its
                                // partial stream (the user message keeps its
                                // context); no durable turn is fabricated.
                                state.copy(
                                    isExecuting = false,
                                    executionLog = updatedLogs,
                                    liveExecution = updatedLive
                                )
                            }
                            else -> state.copy(executionLog = updatedLogs, liveExecution = updatedLive)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // RESIDUAL CLOSURE (P1): an honest cancellation (the user's
                // cancelExecution kills this job; a workspace drain may too)
                // is NOT an unexpected error — the cancel path already set
                // the honest CANCELLED trace. Rethrow so the job completes
                // as cancelled and the finally-block runs its guards.
                throw e
            } catch (e: Exception) {
                // P1: only the CURRENT view's execution surfaces pipeline
                // exceptions — a detached execution's failure never writes
                // an error into another scope's conversation.
                if (currentExecutionTaskId == executionTaskId) {
                    _state.update {
                        it.copy(
                            isExecuting = false,
                            errorMessage = "حدث خطأ غير متوقع أثناء المعالجة: ${e.localizedMessage}"
                        )
                    }
                }
            } finally {
                // DEFENSIVE HONESTY: if this execution is still shown as
                // live WITHOUT a terminal event ever arriving (an unexpected
                // kernel gap), free the composer instead of leaving a
                // permanently-cancel-only UI — and say so honestly.
                if (currentExecutionTaskId == executionTaskId) currentExecutionTaskId = null
                _state.update { state ->
                    val stillLive = state.liveExecution?.executionId == executionTaskId &&
                        state.liveExecution?.phase != ExecutionPhase.CANCELLED &&
                        // FUNCTIONAL CLOSURE (§8): AWAITING_APPROVAL is a
                        // legitimate resting state (the consent request is
                        // still open) — the defensive guard must NOT clear it
                        // as if the execution ended without a signal.
                        state.liveExecution?.phase != ExecutionPhase.AWAITING_APPROVAL &&
                        // CHAT FINAL CLOSURE (P1 fail-closed): a FAILED block
                        // the fail-closed path itself raised is ALREADY the
                        // honest terminal state — the guard must not erase it.
                        state.liveExecution?.phase != ExecutionPhase.FAILED
                    if (stillLive) {
                        state.copy(
                            isExecuting = false,
                            liveExecution = null,
                            diagnosticBanner = state.diagnosticBanner
                                ?: "انتهى التنفيذ دون إشارة ختامية — راجع النتيجة المعروضة."
                        )
                    } else {
                        state
                    }
                }
            }
        }

        // Raise the process to foreground priority for the duration of the
        // execution (durability aid — no-ops when the platform disallows it).
        appContext?.let {
            com.example.application.execution.AgentExecutionForegroundService.start(it)
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }

    // ------------------------------------------------------------------
    // CHAT CAPABILITIES + FUNCTIONAL CLOSURE: capability results, their
    // PENDING lifecycle (§22), and their durable persistence (§9/§10).
    // ------------------------------------------------------------------

    /**
     * FUNCTIONAL CLOSURE (§22): lands a PENDING capability block in the
     * conversation the moment the user runs a capability — the sheet can
     * close immediately without the user losing track of WHAT ran. Returns
     * the block's id (the caller resolves it later with
     * [resolveCapabilityResult]).
     */
    fun appendPendingCapability(
        kind: com.example.presentation.state.CapabilityKind,
        title: String
    ): String {
        val pendingId = "cap_${java.util.UUID.randomUUID()}"
        // RESIDUAL CLOSURE (P2): the invocation's scope is captured AT START
        // — the result later resolves into THIS session under THIS workspace/
        // project, whatever the live scope is at completion time.
        capabilityInvocationScopes[pendingId] = CapabilityInvocationScope(
            workspaceId = runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }.getOrNull(),
            projectId = runCatching { workspaceRuntimeService.activeProjectIdOrNull() }.getOrNull(),
            sessionId = _state.value.activeSessionId
        )
        _state.update {
            it.copy(
                timeline = it.timeline + ChatEntry.CapabilityResult(
                    id = pendingId,
                    kind = kind,
                    title = title,
                    summary = "قيد التنفيذ…",
                    isSuccessful = true,
                    isPending = true,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
        return pendingId
    }

    /**
     * FUNCTIONAL CLOSURE (§22): resolves a pending capability block with
     * its real outcome (success / failure / degraded + the result). The
     * block keeps the pending entry's ID (stable LazyColumn key) and the
     * resolved outcome is PERSISTED (§9/§10) — capability results are
     * conversation history and survive the session reopen.
     */
    fun resolveCapabilityResult(pendingEntryId: String, resolved: ChatEntry.CapabilityResult) {
        val resolvedEntry = resolved.copy(id = pendingEntryId)
        // P2: the ORIGINATING scope (captured at invocation start) — the
        // explicit policy chosen for capability results: SAVE-TO-ORIGINATING-
        // SESSION. A result completing after a scope switch is neither lost
        // nor re-routed into the new scope's conversation; it lands in the
        // session the invocation belongs to (and only the view of THAT
        // conversation shows it).
        val scope = capabilityInvocationScopes.remove(pendingEntryId)
        val currentSessionId = _state.value.activeSessionId
        val pendingStillInView = _state.value.timeline.any { it.id == pendingEntryId }
        // The same conversation reopened (pending is runtime-only): a late
        // result still appears in ITS OWN conversation's view.
        val sameConversation = scope?.sessionId != null && currentSessionId == scope.sessionId
        if (pendingStillInView || sameConversation) {
            _state.update { state ->
                state.copy(
                    timeline = if (pendingStillInView) {
                        state.timeline.map { entry ->
                            if (entry.id == pendingEntryId && entry is ChatEntry.CapabilityResult) {
                                resolvedEntry
                            } else {
                                entry
                            }
                        }
                    } else {
                        state.timeline + resolvedEntry
                    }
                )
            }
        }
        persistCapabilityEvent(resolvedEntry, scope)
    }

    /**
     * CHAT CAPABILITIES (Task 2 §9–§12) + FUNCTIONAL CLOSURE (§9/§10):
     * appends one STRUCTURED capability result block to the conversation
     * AND persists it as durable timeline history (it re-renders after a
     * session reopen). The invocations live in the capabilities feature;
     * the TIMELINE stays single-owner — blocks land here in conversation
     * order.
     */
    fun appendCapabilityResult(entry: ChatEntry.CapabilityResult) {
        _state.update { it.copy(timeline = it.timeline + entry) }
        // P2: the DIRECT (already-resolved) path captures the scope at call
        // time — the caller is acting on the CURRENT conversation by
        // definition (documented current-scope operation).
        val scope = CapabilityInvocationScope(
            workspaceId = runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }.getOrNull(),
            projectId = runCatching { workspaceRuntimeService.activeProjectIdOrNull() }.getOrNull(),
            sessionId = _state.value.activeSessionId
        )
        persistCapabilityEvent(entry, scope)
    }

    /**
     * Persists one capability-result block into the ORIGINATING session's
     * durable timeline.
     *
     * RESIDUAL CLOSURE (P2): [scope] is the invocation's captured context —
     * the event is written under the CAPTURED workspace, into the CAPTURED
     * session (never the live one at completion time).
     * RESIDUAL CLOSURE (P3): when the invocation started BEFORE any session
     * existed, a durable session is CREATED here and pinned to the captured
     * scope — the result is conversation history by design (§9) and must
     * survive a reopen. No session is created for invocations that never
     * resolve (a pending block alone creates nothing).
     *
     * RESIDUAL CLOSURE (persistence-failure leak): the FAILURE side-effect and
     * the created-session VIEW BINDING are both bound to the invocation's
     * captured scope — a capability that started in scope A may fail
     * durably (and bind its created session) only under A; the visible UI
     * state of scope B is never mutated by A's persistence path.
     */
    private fun persistCapabilityEvent(
        entry: ChatEntry.CapabilityResult,
        scope: CapabilityInvocationScope?
    ) {
        // P3: the mode/model captured at RESOLVE time (the conversation's
        // shape when the result lands) — read before the launch so the
        // coroutine's body is deterministic.
        val resolveTimeMode = _state.value.chatMode
        val resolveTimeModelId = _state.value.selectedModelResourceId
        val resolveTimeModelName = _state.value.selectedModelDisplayName
        viewModelScope.launch {
            runCatching {
                var targetSessionId = scope?.sessionId
                if (targetSessionId == null) {
                    // P3: no session existed at invocation time — create one
                    // pinned to the CAPTURED scope (mode = the conversation's
                    // mode at resolve time; the agent binding is set by the
                    // next execution's §3 explicit update).
                    val created = conversationSessionService.createSession(
                        mode = resolveTimeMode,
                        modelResourceId = resolveTimeModelId,
                        modelDisplayName = resolveTimeModelName,
                        projectId = scope?.projectId,
                        workspaceId = scope?.workspaceId
                    )
                    targetSessionId = created.id.value
                }
                conversationSessionService.appendTimelineEvent(
                    ConversationTimelineEvent(
                        id = entry.id,
                        sessionId = ConversationSessionId(targetSessionId),
                        kind = TimelineEventKind.CAPABILITY_RESULT,
                        capabilityKind = entry.kind.name,
                        title = entry.title,
                        summary = entry.summary,
                        detail = entry.detail,
                        sources = entry.sources.map { it.toTurnSourceRef() },
                        isSuccessful = entry.isSuccessful,
                        isDegraded = entry.isDegraded,
                        degradedMessage = entry.degradedMessage,
                        createdAtEpochMs = if (entry.timestampMs > 0) entry.timestampMs else System.currentTimeMillis()
                    ),
                    workspaceId = scope?.workspaceId
                )
                // The view binds to the created session ONLY when the view
                // STILL lives in the captured scope AT THE MOMENT OF THE WRITE
                // (a switched-away view never receives a foreign binding —
                // the pre-write check may have raced a scope switch).
                if (scope != null && scope.sessionId == null &&
                    invocationScopeStillAttached(scope) &&
                    _state.value.activeSessionId == null
                ) {
                    _state.update { it.copy(activeSessionId = targetSessionId) }
                }
            }.onFailure { e ->
                // RESIDUAL CLOSURE (capability persistence leak): the banner
                // is written ONLY when the CURRENT view still lives in the
                // invocation's ORIGINATING scope — a result belonging to
                // scope A never mutates scope B's visible UI state; it is
                // recorded through the non-UI sink instead.
                if (invocationScopeStillAttached(scope)) {
                    _state.update {
                        it.copy(
                            diagnosticBanner = "تعذر حفظ نتيجة القدرة في سجل الجلسة: ${e.localizedMessage}"
                        )
                    }
                } else {
                    detachedPersistenceFailureSink(
                        "capability event persistence failed for detached invocation " +
                                "(workspace=${scope?.workspaceId}, project=${scope?.projectId}, " +
                                "session=${scope?.sessionId}, entry=${entry.id}): " +
                                "${e::class.simpleName}: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * RESIDUAL CLOSURE (persistence-failure leak): whether the CURRENT view
     * still lives in the invocation's ORIGINATING scope — the only view that
     * may see this invocation's persistence diagnostics or receive its
     * created-session binding. A session captured at invocation time must
     * still be the active one; a sessionless invocation matches by
     * (workspace, project). An UNKNOWN provenance (null scope) never
     * qualifies (fail-closed — the current scope cannot be proven to be the
     * originating one).
     */
    private fun invocationScopeStillAttached(scope: CapabilityInvocationScope?): Boolean {
        if (scope == null) return false
        // Fail-closed runtime reads (a broken scope service can never prove
        // the current view is the originating one — the OLD binding check
        // behaved the same way via getOrDefault(false)).
        val currentWorkspaceId = runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }
        val currentProjectId = runCatching { workspaceRuntimeService.activeProjectIdOrNull() }
        if (currentWorkspaceId.isFailure || currentProjectId.isFailure) return false
        val sameScope = currentWorkspaceId.getOrNull() == scope.workspaceId &&
                currentProjectId.getOrNull() == scope.projectId
        val sessionAligned = scope.sessionId == null ||
                _state.value.activeSessionId == scope.sessionId
        return sameScope && sessionAligned
    }

    /** Presentation source ref → the durable twin. */
    private fun com.example.presentation.state.ChatSourceRef.toTurnSourceRef() =
        TurnSourceRef(
            title = title,
            url = url,
            providerId = providerId,
            confidenceScore = confidenceScore
        )

    /**
     * CHAT CAPABILITIES (Task 2 §13) + FUNCTIONAL CLOSURE (§9): surfaces the
     * REAL pending approval of [executionId] as an inline block in the
     * conversation (idempotent — a block for the same approval never
     * duplicates) AND persists it as durable timeline history — an approval
     * the user resolved (or left pending) re-renders after a reopen with
     * its honest state.
     */
    private fun requestApprovalBlock(
        kernelExecutionId: String,
        executionTaskId: String,
        sessionId: ConversationSessionId?,
        pinnedWorkspaceId: String?
    ) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            val pending = runCatching { gate.pendingApprovals() }.getOrDefault(emptyList())
                .firstOrNull { it.executionId == kernelExecutionId } ?: return@launch
            // §9 + P1: the approval block is conversation history — the
            // DURABLE write happens FIRST, in the EXECUTION'S OWN pinned
            // session under the pinned workspace (save-to-originating-session;
            // the live activeSessionId/workspace is no longer consulted).
            // RESIDUAL CLOSURE (ordering fix): "durable from the moment it
            // appears" is now literally true — the view block below can only
            // be seen AFTER the event exists, so a resolution mirroring onto
            // the event can never race (and silently no-op against) an event
            // that has not landed yet.
            //
            // CHAT FINAL CLOSURE (P1 approval persistence): the write RESULT
            // is now part of the state machine — a PENDING block is shown as
            // durable history ONLY when the event was actually written. A
            // failed write (returned false or threw) surfaces the explicit
            // failure and NO inline block masquerades as durable state; the
            // user is routed to the governance surface where the SAME gate
            // request is resolvable.
            var durableWriteSucceeded = false
            if (sessionId != null) {
                durableWriteSucceeded = runCatching {
                    conversationSessionService.appendTimelineEvent(
                        ConversationTimelineEvent(
                            id = "apv_${pending.approvalId}",
                            sessionId = sessionId,
                            kind = TimelineEventKind.APPROVAL_BLOCK,
                            title = "تنفيذ الأداة «${pending.toolName}»",
                            summary = pending.prompt,
                            detail = pending.prompt,
                            approvalId = pending.approvalId,
                            executionId = pending.executionId,
                            toolName = pending.toolName,
                            riskLevel = pending.riskLevel,
                            justification = pending.justification,
                            approvalState = ApprovalBlockState.PENDING.name,
                            createdAtEpochMs = System.currentTimeMillis()
                        ),
                        workspaceId = pinnedWorkspaceId
                    )
                }.getOrDefault(false)
            }
            if (!durableWriteSucceeded) {
                if (currentExecutionTaskId == executionTaskId) {
                    _state.update {
                        it.copy(
                            errorMessage = "تعذر تسجيل طلب الموافقة في سجل الجلسة الدائم — لم تُعرض كتلة الموافقة في المحادثة. " +
                                    "يمكنك حل الطلب من شاشة الحوكمة."
                        )
                    }
                } else {
                    detachedPersistenceFailureSink(
                        "approval event persistence failed for detached execution $executionTaskId " +
                                "(workspace=$pinnedWorkspaceId, approval=${pending.approvalId}): no durable block written"
                    )
                }
                return@launch
            }
            // The mirror location of this approval's durable event — used by
            // every later decision update (never the live activeSessionId).
            approvalMirrorScopes[pending.approvalId] = ApprovalMirrorScope(
                sessionId = sessionId!!.value,
                workspaceId = pinnedWorkspaceId
            )
            // RESIDUAL CLOSURE (P1): the VIEW block appears only while THIS
            // execution is still the view's current one — a detached consent
            // request never lands in another scope's conversation.
            if (currentExecutionTaskId == executionTaskId) {
                _state.update { state ->
                    val exists = state.timeline.any {
                        it is ChatEntry.ApprovalBlock && it.approvalId == pending.approvalId
                    }
                    if (exists) {
                        state
                    } else {
                        val block = ChatEntry.ApprovalBlock(
                            id = "apv_${pending.approvalId}",
                            approvalId = pending.approvalId,
                            executionId = pending.executionId,
                            toolName = pending.toolName,
                            riskLevel = pending.riskLevel,
                            description = pending.prompt,
                            requestedAction = "تنفيذ الأداة «${pending.toolName}»",
                            justification = pending.justification
                        )
                        state.copy(timeline = state.timeline + block)
                    }
                }
            }
        }
    }

    /**
     * CHAT CAPABILITIES (Task 2 §13): approves through the REAL
     * HumanApprovalGate — the same authorization path the governance
     * surface uses (no chat-side bypass exists).
     */
    fun approveApproval(approvalId: String) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            runCatching { gate.approve(approvalId, localPrincipalId) }
                .onSuccess { resolution ->
                    // The gate is idempotent: an already-resolved request
                    // returns ITS OWN resolution — the block mirrors THAT,
                    // never a flipped decision.
                    val resolvedState = when (resolution) {
                        com.example.domain.core.security.governance.ApprovalResolution.APPROVED ->
                            ApprovalBlockState.APPROVED
                        com.example.domain.core.security.governance.ApprovalResolution.EXPIRED ->
                            ApprovalBlockState.EXPIRED
                        com.example.domain.core.security.governance.ApprovalResolution.REJECTED ->
                            ApprovalBlockState.REJECTED // already rejected — stays
                        else -> ApprovalBlockState.APPROVED
                    }
                    updateApprovalBlock(approvalId, resolvedState)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تسجيل الموافقة: ${e.localizedMessage}")
                    }
                }
        }
    }

    /** CHAT CAPABILITIES (Task 2 §13): rejects through the REAL gate. */
    fun rejectApproval(approvalId: String) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            runCatching { gate.reject(approvalId, localPrincipalId) }
                .onSuccess { resolution ->
                    // Idempotent mirror: an already-APPROVED request returns
                    // APPROVED — the block keeps the real decision.
                    val resolvedState = when (resolution) {
                        com.example.domain.core.security.governance.ApprovalResolution.REJECTED ->
                            ApprovalBlockState.REJECTED
                        com.example.domain.core.security.governance.ApprovalResolution.APPROVED ->
                            ApprovalBlockState.APPROVED // already approved — stays
                        else -> ApprovalBlockState.REJECTED
                    }
                    updateApprovalBlock(approvalId, resolvedState)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تسجيل الرفض: ${e.localizedMessage}")
                    }
                }
        }
    }

    /**
     * CHAT CAPABILITIES (Task 2 §13): "السماح دائماً لهذه الأداة" — a
     * standing EXECUTE grant for the tool (recorded consent: future
     * admissions of this tool pass without a new request) plus resolving
     * the CURRENT pending request — the exact same path
     * GovernanceViewModel.grantAlwaysForApproval uses (one authority).
     *
     * FUNCTIONAL CLOSURE (§12): the grant's REAL scope is explicit — a
     * GLOBAL (device-profile-wide, all workspaces/sessions) standing
     * permission for this tool, NOT a one-time approval. The UI shows a
     * confirmation dialog with exactly this scope before calling here.
     */
    fun grantAlwaysForApproval(approvalId: String) {
        val gate = humanApprovalGate ?: return
        val grants = permissionGrantService
        viewModelScope.launch {
            if (grants == null) {
                _state.update {
                    it.copy(errorMessage = "خدمة منح الأذونات غير متاحة في هذا التكوين.")
                }
                return@launch
            }
            // CHAT FINAL CLOSURE (P1 grantAlways false-success): the pending
            // request is verified FIRST, OUTSIDE the success path — a missing
            // (already-resolved / expired / unknown) request can NEVER end in
            // "APPROVED + تم السماح دائماً…". The grant, the gate resolution,
            // and their RESULTS are each verified before any UI mutation.
            val pending = runCatching { gate.pendingApprovals() }.getOrDefault(emptyList())
                .firstOrNull { it.approvalId == approvalId }
            if (pending == null) {
                _state.update {
                    it.copy(
                        errorMessage = "لا يوجد طلب موافقة قائم بهذا المعرف — ربما حُلّ سابقاً أو انتهت صلاحيته؛ لم يُسجَّل أي منح دائم."
                    )
                }
                return@launch
            }
            runCatching {
                grants.grant(
                    principalType = com.example.domain.core.security.governance.PrincipalType.USER,
                    principalId = localPrincipalId,
                    // GLOBAL grant (workspaceId = null): standing consent for
                    // the tool across the single-user device profile.
                    resourceType = com.example.domain.core.security.governance.SecurableResourceType.TOOL,
                    resourceId = pending.toolName,
                    permission = com.example.domain.core.security.governance.Permission.EXECUTE,
                    grantedBy = localPrincipalId
                )
                gate.approve(approvalId, localPrincipalId)
            }.onSuccess { resolution ->
                when (resolution) {
                    com.example.domain.core.security.governance.ApprovalResolution.APPROVED -> {
                        updateApprovalBlock(approvalId, ApprovalBlockState.APPROVED)
                        _state.update {
                            it.copy(
                                diagnosticBanner = "تم السماح دائماً بهذه الأداة: منح EXECUTE دائم على مستوى الجهاز " +
                                        "(كل الجلسات) — يمكنك سحبه من شاشة الحوكمة."
                            )
                        }
                    }
                    com.example.domain.core.security.governance.ApprovalResolution.REJECTED -> {
                        // The gate holds a REJECTED decision — the block keeps
                        // the REAL state; no grant-success messaging.
                        updateApprovalBlock(approvalId, ApprovalBlockState.REJECTED)
                        _state.update {
                            it.copy(
                                errorMessage = "الطلب بالفعل مرفوض — لم يُسجَّل المنح الدائم."
                            )
                        }
                    }
                    else -> {
                        updateApprovalBlock(approvalId, ApprovalBlockState.EXPIRED)
                        _state.update {
                            it.copy(
                                errorMessage = "انتهت صلاحية طلب الموافقة قبل إتمام المنح الدائم — أعد المحاولة على طلب قائم."
                            )
                        }
                    }
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(errorMessage = "تعذر تسجيل المنح الدائم: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun updateApprovalBlock(approvalId: String, blockState: ApprovalBlockState) {
        _state.update { state ->
            state.copy(
                timeline = state.timeline.map { entry ->
                    if (entry is ChatEntry.ApprovalBlock && entry.approvalId == approvalId) {
                        entry.copy(state = blockState)
                    } else {
                        entry
                    }
                }
            )
        }
        // FUNCTIONAL CLOSURE (§9): the resolved state is mirrored onto the
        // durable timeline event — a reopened session shows the decision,
        // not a stale PENDING block.
        //
        // CHAT FINAL CLOSURE (approval durable-state invariant): the mirror
        // targets the session the APPROVAL BLOCK was durably written in (the
        // tracked [approvalMirrorScopes]) — NEVER the live activeSessionId
        // (which may belong to another conversation by decision time). The
        // UI above shows the GATE's decision (the authorization authority);
        // a failed durable mirror surfaces an EXPLICIT error instead of
        // silently claiming the persisted state moved.
        val mirrorScope = approvalMirrorScopes[approvalId]
        if (mirrorScope == null) {
            // The block was never durably written (its request already
            // failed persistence honestly) — nothing to mirror, and the
            // decision remains gate-truth only.
            return
        }
        val mirrorSessionId = mirrorScope.sessionId
        val mirrorWorkspaceId = mirrorScope.workspaceId
        viewModelScope.launch {
            try {
                val mirrored = conversationSessionService.updateTimelineEventApprovalState(
                    sessionId = ConversationSessionId(mirrorSessionId),
                    approvalId = approvalId,
                    state = blockState.name,
                    workspaceId = mirrorWorkspaceId
                )
                if (!mirrored) {
                    throw IllegalStateException(
                        "the durable approval event did not change (missing row in its own session)"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = "تم تسجيل قرارك في بوابة الموافقة لكن تعذر تحديث السجل الدائم للجلسة " +
                                "(${e.localizedMessage ?: e.message}) — قد تظهر الحالة القديمة بعد إعادة فتح الجلسة."
                    )
                }
            }
        }
    }

    /**
     * CHAT CAPABILITIES (Task 2 §13) + FUNCTIONAL CLOSURE (§6): retry-after-
     * approval — re-executes the message that was blocked by consent. The
     * retry targets the USER MESSAGE the APPROVED block belongs to (the
     * nearest preceding user entry — §6's targeting rule), history stays
     * append-only (the new result is a NEW entry; the old ones remain). The
     * consent itself was resolved through the REAL gate; a future admission
     * of the same tool still passes the same authorization boundary
     * honestly — with "السماح دائماً" (a standing EXECUTE grant) it passes
     * WITHOUT a new request.
     *
     * CHAT FINAL CLOSURE: returns the id of the RETARGETED user message (the
     * tapped block's own message) — a deterministic witness for the
     * targeting invariant (a transitory live-block emission can conflate
     * under fast kernel completions); null when the retry was honestly
     * refused (with the actionable error already surfaced).
     */
    fun retryAfterApproval(approvalId: String, agent: AgentDefinition?): String? {
        val current = _state.value
        if (current.isExecuting) {
            // Honest refusal (never a silent no-op): the composer is locked
            // by a running execution — retry when it completes.
            _state.update {
                it.copy(errorMessage = "هناك تنفيذ جارٍ بالفعل — أعد المحاولة عند اكتماله.")
            }
            return null
        }
        // RESIDUAL CLOSURE (P4): the retry targets the SPECIFIC approval
        // block the user tapped (explicit approvalId identity) — NEVER
        // "the last approved block". With approvals A and B both resolved,
        // retrying A re-executes A's message even when B was approved or
        // rejected afterwards.
        val targetBlock = current.timeline.firstOrNull {
            it is ChatEntry.ApprovalBlock && it.approvalId == approvalId
        } as? ChatEntry.ApprovalBlock ?: run {
            _state.update {
                it.copy(errorMessage = "لا توجد موافقة بهذا المعرف في المحادثة الحالية.")
            }
            return null
        }
        if (targetBlock.state != ApprovalBlockState.APPROVED) {
            _state.update {
                it.copy(errorMessage = "هذه الموافقة لم تُمنح بعد — وافق على الطلب أولاً ثم أعد المحاولة.")
            }
            return null
        }
        val blockIndex = current.timeline.indexOfFirst { it.id == targetBlock.id }
        val blockedUser = if (blockIndex > 0) {
            current.timeline.take(blockIndex)
                .lastOrNull { it is ChatEntry.User } as? ChatEntry.User
        } else {
            null
        } ?: run {
            // P4 (documented scope policy): the retry only re-executes a
            // message present in the CURRENT conversation's timeline — it
            // never fabricates a prompt from durable state elsewhere.
            _state.update {
                it.copy(errorMessage = "تعذر العثور على الرسالة المرتبطة بهذه الموافقة في المحادثة الحالية.")
            }
            return null
        }

        val resolvedAgent = resolveAgentForExecution(agent) ?: return null
        launchExecutionForUserEntry(
            targetUser = blockedUser,
            resolvedAgent = resolvedAgent
        )
        // CHAT FINAL CLOSURE: the deterministic targeting witness — THIS
        // block's own message id.
        return blockedUser.id
    }

    companion object {
        /** Conversation history replay window (LLM messages per turn). */
        const val CONVERSATION_HISTORY_WINDOW = 8

        /** The kernel's honest failure code for a consent-blocked execution. */
        const val APPROVAL_REQUIRED_CODE = "HUMAN_APPROVAL_REQUIRED"
    }
}
