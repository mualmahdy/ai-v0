package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.domain.core.task.AutonomyPolicy
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
 * 2, Design Closure 2026 UI-redesign track; Chat Workspace Task 1)
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
 *    into [executePrompt] / [startNewSession] as a parameter.
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
    private val signalBus: StudioSignalBus
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

    // --- Prompt & session input surfaces ---

    fun updatePromptInput(input: String) {
        _state.update { it.copy(promptInput = input) }
    }

    /**
     * MESSAGE ACTION (Task 1 §10): loads a past user message's text into the
     * composer as an editable draft (re-send as a new message). The original
     * message is NOT mutated — history stays append-only.
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
                sessionTurnStartMs = 0L
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
        _state.update {
            it.copy(
                activeSessionId = null,
                timeline = emptyList(),
                studioSession = emptyList(),
                streamText = "",
                executionLog = emptyList(),
                liveExecution = null,
                sessionTurnStartMs = 0L
            )
        }
    }

    /**
     * USER-FACING MODEL PICKER (report gap): selects the exact LLM resource
     * the conversation binds to. `resourceId == null` → the runtime decision
     * layer picks (previous behaviour). The choice is persisted onto the
     * active durable session (exact runtime binding survives restarts).
     */
    fun selectModel(resourceId: String?, displayName: String?) {
        _state.update {
            it.copy(selectedModelResourceId = resourceId, selectedModelDisplayName = displayName)
        }
        val sessionId = _state.value.activeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                conversationSessionService.setSessionModel(
                    sessionId = ConversationSessionId(sessionId),
                    modelResourceId = resourceId,
                    modelDisplayName = displayName
                )
            }
        }
    }

    /**
     * Starts a NEW durable conversation session (bound to the active
     * workspace + current mode/model) and clears the transcript. The agent
     * binding is passed by the screen (the catalog selection is shared
     * state — ADR-6 slice 2 seam).
     */
    fun startNewSession(agent: AgentDefinition?) {
        viewModelScope.launch {
            runCatching {
                val current = _state.value
                val session = conversationSessionService.createSession(
                    mode = current.chatMode,
                    agentId = agent?.identity?.id?.value,
                    agentName = agent?.identity?.name,
                    modelResourceId = current.selectedModelResourceId,
                    modelDisplayName = current.selectedModelDisplayName,
                    // GAP-14: new sessions are project-scoped from creation —
                    // bound to the ACTIVE workspace's active project (null =
                    // shared workspace session when no project is bound).
                    projectId = workspaceRuntimeService.activeProjectIdOrNull()
                )
                _state.update {
                    it.copy(
                        activeSessionId = session.id.value,
                        studioSession = emptyList(),
                        timeline = emptyList(),
                        executionLog = emptyList(),
                        streamText = "",
                        liveExecution = null
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
     * full turn history into the Studio transcript, restores the session's
     * mode / model binding, and continues the conversation with the loaded
     * turns as LLM history. (The browser sheet itself is SessionsViewModel
     * state — the screen closes it.)
     */
    fun openSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                val loaded = conversationSessionService.getSessionWithTurns(
                    ConversationSessionId(sessionId)
                ) ?: return@launch
                _state.update { state ->
                    state.copy(
                        activeSessionId = loaded.session.id.value,
                        chatMode = loaded.session.mode,
                        selectedModelResourceId = loaded.session.modelResourceId,
                        selectedModelDisplayName = loaded.session.modelDisplayName,
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
                        timeline = loaded.turns.flatMap { turn ->
                            buildTimelineFromTurn(turn, loaded.session.agentName)
                        },
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
            ChatEntry.User(id = "u_${turn.id}", text = turn.prompt),
            ChatEntry.Assistant(
                id = "a_${turn.id}",
                text = turn.answer,
                agentName = agentName,
                agentRole = turn.agentRole ?: "",
                isSuccessful = turn.isSuccessful,
                modelResourceId = turn.modelResourceId,
                tokensConsumed = turn.tokensConsumed,
                durationMs = turn.durationMs,
                eventCount = turn.eventCount
            )
        )
    }

    /**
     * SESSIONS FEATURE → studio-side effect of a deleted session: clears
     * THIS conversation's binding + transcript when the deleted session was
     * the active one (the deletion itself is owned by SessionsViewModel).
     */
    fun onSessionDeleted(sessionId: String) {
        if (_state.value.activeSessionId == sessionId) {
            _state.update {
                it.copy(
                    activeSessionId = null,
                    studioSession = emptyList(),
                    timeline = emptyList(),
                    streamText = "",
                    liveExecution = null
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
     * Ensures the ACTIVE durable session exists (creating it bound to the
     * current workspace / mode / agent / model on first use), then sets it
     * active. First-turn titling policy: the session is renamed to the
     * prompt when it still carries the default title.
     */
    private suspend fun ensureActiveSession(
        mode: ChatMode,
        agent: AgentDefinition,
        modelResourceId: String?,
        modelDisplayName: String?
    ): ConversationSessionId? {
        return runCatching {
            val existingId = _state.value.activeSessionId
            if (existingId != null) {
                val existing = conversationSessionService.getSessionWithTurns(
                    ConversationSessionId(existingId)
                )?.session
                if (existing != null) return existing.id
            }
            val session = conversationSessionService.createSession(
                mode = mode,
                agentId = if (mode == ChatMode.AGENT) agent.identity.id.value else null,
                agentName = if (mode == ChatMode.AGENT) agent.identity.name else null,
                modelResourceId = modelResourceId,
                modelDisplayName = modelDisplayName,
                // GAP-14: bind to the active project (null = shared session).
                projectId = workspaceRuntimeService.activeProjectIdOrNull()
            )
            _state.update { it.copy(activeSessionId = session.id.value) }
            session.id
        }.getOrNull()
    }

    /**
     * Persists one finished turn to the durable session (fire-and-forget —
     * failures surface as an honest diagnostic banner, never swallowed).
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
        eventCount: Int
    ) {
        val id = sessionId ?: return
        viewModelScope.launch {
            runCatching {
                conversationSessionService.appendTurn(
                    sessionId = id,
                    prompt = prompt,
                    answer = answer,
                    agentName = agentName,
                    agentRole = agentRole,
                    modelResourceId = modelResourceId,
                    tokensConsumed = tokensConsumed,
                    durationMs = durationMs,
                    isSuccessful = isSuccessful,
                    eventCount = eventCount
                )
                // First-turn titling: the default title becomes the prompt.
                val session = conversationSessionService.getSessionWithTurns(id)?.session
                if (session != null && session.title == ConversationSessionService.DEFAULT_TITLE) {
                    conversationSessionService.titleFromPrompt(id, prompt)
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(diagnosticBanner = "تعذر حفظ دورة المحادثة بشكل دائم: ${e.localizedMessage}")
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
        turnId: String
    ): List<StudioTurn> {
        val turn = StudioTurn(
            id = turnId,
            prompt = prompt,
            agentName = agentName,
            agentRole = agentRole,
            answer = answer,
            eventCount = state.executionLog.size,
            tokensConsumed = state.currentTokensConsumed,
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
     */
    fun executePrompt(agent: AgentDefinition?) {
        val current = _state.value
        val prompt = current.promptInput.trim()
        if (prompt.isEmpty() || current.isExecuting) return

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
                return
            }
        }

        executeText(prompt, resolvedAgent, appendUserEntry = true)
    }

    /**
     * MESSAGE ACTION (Task 1 §10): re-executes the LAST user message
     * ("Regenerate" on an assistant message / "Retry" on a failed result).
     * The user message is NOT duplicated — the existing entry anchors the
     * new execution; only the lifecycle block and the new result follow it.
     */
    fun regenerateLast(agent: AgentDefinition?) {
        val current = _state.value
        if (current.isExecuting) return
        val lastUserText = current.timeline.lastOrNull { it is ChatEntry.User }
            ?.let { (it as ChatEntry.User).text } ?: return

        val resolvedAgent: AgentDefinition = when (current.chatMode) {
            ChatMode.QUICK_CHAT -> resolveQuickChatAgent()
            ChatMode.AGENT -> agent ?: run {
                _state.update {
                    it.copy(errorMessage = "وضع الوكيل يتطلب اختيار وكيلاً من الكتالوج أولاً — أو بدّل إلى «محادثة سريعة».")
                }
                return
            }
        }

        executeText(lastUserText, resolvedAgent, appendUserEntry = false)
    }

    /**
     * The shared execution core used by both [executePrompt] (fresh prompt,
     * draft cleared, user entry appended) and [regenerateLast] (existing
     * user entry anchors the conversation).
     */
    private fun executeText(
        prompt: String,
        resolvedAgent: AgentDefinition,
        appendUserEntry: Boolean
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
        // floating preference.
        // ------------------------------------------------------------------
        val selectedModelId = current.selectedModelResourceId

        val executionTaskId = java.util.UUID.randomUUID().toString()
        val sentAtMs = System.currentTimeMillis()
        val userEntryId = "user_$executionTaskId"

        // ONE atomic update: draft cleared (P0-B), user message visible
        // (P0-C), live execution opened, previous lifecycle/stream closed.
        _state.update {
            it.copy(
                promptInput = if (appendUserEntry) "" else it.promptInput,
                timeline = if (appendUserEntry) {
                    it.timeline + ChatEntry.User(id = userEntryId, text = prompt)
                } else {
                    it.timeline
                },
                isExecuting = true,
                streamText = "",
                executionLog = emptyList(),
                liveExecution = LiveExecutionState(
                    executionId = executionTaskId,
                    phase = ExecutionPhase.QUEUED,
                    startedAtMs = sentAtMs
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
        // DURABLE SESSION (report gap: sessions were deleted without a
        // durable replacement): a workspace-scoped session is ensured BEFORE
        // execution; every completed/failed turn is appended to it, so the
        // transcript survives process death and can be browsed/resumed.
        // ------------------------------------------------------------------
        val turnStartedAt = System.currentTimeMillis()
        // P1-14 (audit 2026 — no execution drain before workspace deletion):
        // the execution is ATTRIBUTED to the workspace whose scope it runs
        // in, so deleting that workspace cancels+drains exactly these jobs
        // instead of orphaning them.
        val executionWorkspaceId = runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }.getOrNull()
        com.example.application.execution.ExecutionHost.launch(executionTaskId, executionWorkspaceId) {
            var sessionId: ConversationSessionId? = null
            // Terminal-sequence counter: one execution can emit MULTIPLE
            // terminal events (the documented provider-error → fallback
            // quirk) — each lands as its own assistant entry with a stable,
            // collision-free id (the millisecond-based ids could collide
            // and crash LazyColumn keys).
            var terminalSeq = 0
            try {
                sessionId = ensureActiveSession(
                    mode = current.chatMode,
                    agent = resolvedAgent,
                    modelResourceId = selectedModelId,
                    modelDisplayName = current.selectedModelDisplayName
                )
                // FAIL-CLOSED NETWORK DEFAULT (report gap: "missing monitor =
                // network available is fail-open"): when no monitor is wired
                // we assume OFFLINE, so OFFLINE/degraded policies engage
                // honestly instead of silently attempting remote calls.
                val netAvailable = networkMonitorProvider?.isNetworkAvailable?.value ?: false
                executeAgentTaskUseCase(
                    agent = resolvedAgent,
                    prompt = prompt,
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
                    constraints = com.example.domain.core.task.TaskConstraints(
                        autonomyPolicy = workspaceRuntimeService.activeWorkspace.value
                            ?.settings?.get("autonomyPolicy")
                            ?.let { name -> runCatching { AutonomyPolicy.valueOf(name) }.getOrNull() }
                            ?: AutonomyPolicy.SUPERVISED
                    )
                ).collect { event ->
                    // CROSS-FEATURE PROJECTIONS (ADR-6 slice 2): the events
                    // other features mirror (activity trace, decision
                    // case-base/uncertainty) are published on the signal bus
                    // instead of being written into a shared UiState.
                    when (event) {
                        is ExecutionEvent.Started,
                        is ExecutionEvent.DecisionMade,
                        is ExecutionEvent.ObservationRecorded,
                        is ExecutionEvent.Completed,
                        is ExecutionEvent.Error ->
                            signalBus.emit(StudioSignal.ExecutionEvent(event))
                        else -> Unit
                    }
                    _state.update { state ->
                        val updatedLogs = state.executionLog + event
                        val updatedLive = state.liveExecution?.let {
                            ExecutionLifecycleProjection.apply(it, event)
                        }
                        when (event) {
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
                                val answer = if (event.finalText.isNotBlank()) event.finalText else state.streamText
                                persistTurnDurably(
                                    sessionId = sessionId,
                                    prompt = prompt,
                                    answer = answer,
                                    agentName = resolvedAgent.identity.name,
                                    agentRole = resolvedAgent.identity.role.displayName,
                                    modelResourceId = selectedModelId,
                                    tokensConsumed = state.currentTokensConsumed,
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                    isSuccessful = true,
                                    eventCount = updatedLogs.size
                                )
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
                                        modelResourceId = selectedModelId,
                                        tokensConsumed = state.currentTokensConsumed,
                                        durationMs = System.currentTimeMillis() - sentAtMs,
                                        eventCount = updatedLogs.size,
                                        isDegraded = event.isDegraded
                                    ),
                                    studioSession = appendStudioTurn(
                                        state = state,
                                        prompt = prompt,
                                        agentName = resolvedAgent.identity.name,
                                        agentRole = resolvedAgent.identity.role.displayName,
                                        answer = answer,
                                        isSuccessful = true,
                                        modelResourceId = selectedModelId,
                                        turnId = "turn_${executionTaskId}_$terminalSeq"
                                    )
                                )
                            }
                            is ExecutionEvent.Error -> {
                                terminalSeq++
                                val answer = state.streamText.ifBlank { event.message }
                                persistTurnDurably(
                                    sessionId = sessionId,
                                    prompt = prompt,
                                    answer = answer,
                                    agentName = resolvedAgent.identity.name,
                                    agentRole = resolvedAgent.identity.role.displayName,
                                    modelResourceId = selectedModelId,
                                    tokensConsumed = state.currentTokensConsumed,
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                    isSuccessful = false,
                                    eventCount = updatedLogs.size
                                )
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
                                        modelResourceId = selectedModelId,
                                        tokensConsumed = state.currentTokensConsumed,
                                        durationMs = System.currentTimeMillis() - sentAtMs,
                                        eventCount = updatedLogs.size
                                    ),
                                    studioSession = appendStudioTurn(
                                        state = state,
                                        prompt = prompt,
                                        agentName = resolvedAgent.identity.name,
                                        agentRole = resolvedAgent.identity.role.displayName,
                                        answer = answer,
                                        isSuccessful = false,
                                        modelResourceId = selectedModelId,
                                        turnId = "turn_${executionTaskId}_$terminalSeq"
                                    )
                                )
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
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isExecuting = false,
                        errorMessage = "حدث خطأ غير متوقع أثناء المعالجة: ${e.localizedMessage}"
                    )
                }
            } finally {
                // DEFENSIVE HONESTY: if this execution is still shown as
                // live WITHOUT a terminal event ever arriving (an unexpected
                // kernel gap), free the composer instead of leaving a
                // permanently-cancel-only UI — and say so honestly.
                if (currentExecutionTaskId == executionTaskId) currentExecutionTaskId = null
                _state.update { state ->
                    val stillLive = state.liveExecution?.executionId == executionTaskId &&
                        state.liveExecution?.phase != ExecutionPhase.CANCELLED
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

    companion object {
        /** Conversation history replay window (LLM messages per turn). */
        const val CONVERSATION_HISTORY_WINDOW = 8
    }
}
