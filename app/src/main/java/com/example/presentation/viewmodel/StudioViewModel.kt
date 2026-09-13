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
import com.example.domain.core.task.TaskConstraints
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
 * 2, Design Closure 2026 UI-redesign track)
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
 *    (decision case-base, activity trace) stay in MainViewModel and are
 *    fed through the [StudioSignal] bus.
 *  - BEHAVIOR: prompt execution via [ExecuteAgentTaskUseCase] on the
 *    governed execution kernel (ExecutionHost, workspace-attributed),
 *    durable-session ensure/persist (survives process death), model
 *    pinning, mode switching, and honest cancellation.
 *  - CROSS-FEATURE SEAM: the AGENT binding is NOT owned here — the agent
 *    catalog is shared state (Tasks/Explorer read it); the screen reads
 *    the selection from the shared state and passes the resolved agent
 *    into [executePrompt] / [startNewSession] as a parameter.
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
     * Sets the conversation mode: QUICK_CHAT (agent-independent, binds to the
     * selected model) or AGENT (canonical agent catalog).
     */
    fun setChatMode(mode: ChatMode) {
        _state.update { it.copy(chatMode = mode) }
    }

    /**
     * Sets the SESSION network policy — an execution-time input of THIS
     * conversation. The change is also published on the signal bus so the
     * decision preview and the governance snapshot re-derive with the new
     * policy (they keep display mirrors in MainViewModel).
     */
    fun setNetworkPolicy(policy: NetworkPolicy) {
        _state.update { it.copy(networkPolicy = policy) }
        viewModelScope.launch { signalBus.emit(StudioSignal.NetworkPolicyChanged(policy)) }
    }

    /** Clears the in-memory Studio conversation transcript (session only). */
    fun clearStudioSession() {
        _state.update { it.copy(studioSession = emptyList()) }
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
                        executionLog = emptyList(),
                        streamText = ""
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
                        executionLog = emptyList(),
                        streamText = "",
                        isExecuting = false
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(errorMessage = "تعذر فتح الجلسة: ${failure.localizedMessage}") }
            }
        }
    }

    /**
     * SESSIONS FEATURE → studio-side effect of a deleted session: clears
     * THIS conversation's binding + transcript when the deleted session was
     * the active one (the deletion itself is owned by SessionsViewModel).
     */
    fun onSessionDeleted(sessionId: String) {
        if (_state.value.activeSessionId == sessionId) {
            _state.update { it.copy(activeSessionId = null, studioSession = emptyList()) }
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
        modelResourceId: String? = null
    ): List<StudioTurn> {
        val turn = StudioTurn(
            id = "turn_${System.currentTimeMillis()}",
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
     */
    fun cancelExecution() {
        currentExecutionTaskId?.let { com.example.application.execution.ExecutionHost.cancel(it) }
        currentExecutionTaskId = null
        appContext?.let {
            com.example.application.execution.AgentExecutionForegroundService.stop(it)
        }
        _state.update {
            it.copy(
                isExecuting = false,
                diagnosticBanner = "تم إلغاء العملية بواسطة المستخدم."
            )
        }
    }

    /**
     * Executes the current prompt. [agent] is the shared-catalog selection
     * resolved by the screen (null is only honest in QUICK_CHAT mode).
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
        // no-op return).
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

        _state.update {
            it.copy(
                isExecuting = true,
                streamText = "",
                executionLog = emptyList(),
                sessionTurnStartMs = System.currentTimeMillis(),
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
        val executionTaskId = java.util.UUID.randomUUID().toString()
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
                        when (event) {
                            is ExecutionEvent.DecisionMade -> {
                                state.copy(executionLog = updatedLogs)
                            }
                            is ExecutionEvent.ObservationRecorded -> {
                                state.copy(executionLog = updatedLogs)
                            }
                            is ExecutionEvent.ContentChunk -> {
                                state.copy(
                                    streamText = state.streamText + event.deltaText,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.Degraded -> {
                                state.copy(
                                    isDegraded = true,
                                    degradedReason = event.reason,
                                    diagnosticBanner = event.message,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.UsageBudgetUpdate -> {
                                state.copy(
                                    currentTokensConsumed = event.promptTokens + event.completionTokens,
                                    sessionTotalTokens = event.totalSessionTokens,
                                    remainingBudget = event.remainingBudgetTokens,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.Completed -> {
                                persistTurnDurably(
                                    sessionId = sessionId,
                                    prompt = prompt,
                                    answer = event.finalText.ifBlank { _state.value.streamText },
                                    agentName = resolvedAgent.identity.name,
                                    agentRole = resolvedAgent.identity.role.displayName,
                                    modelResourceId = selectedModelId,
                                    tokensConsumed = _state.value.currentTokensConsumed,
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                    isSuccessful = true,
                                    eventCount = _state.value.executionLog.size
                                )
                                state.copy(
                                    isExecuting = false,
                                    streamText = if (event.finalText.isNotBlank()) event.finalText else state.streamText,
                                    executionLog = updatedLogs,
                                    studioSession = appendStudioTurn(
                                        state = state,
                                        prompt = prompt,
                                        agentName = resolvedAgent.identity.name,
                                        agentRole = resolvedAgent.identity.role.displayName,
                                        answer = if (event.finalText.isNotBlank()) event.finalText else state.streamText,
                                        isSuccessful = true,
                                        modelResourceId = selectedModelId
                                    )
                                )
                            }
                            is ExecutionEvent.Error -> {
                                persistTurnDurably(
                                    sessionId = sessionId,
                                    prompt = prompt,
                                    answer = state.streamText.ifBlank { event.message },
                                    agentName = resolvedAgent.identity.name,
                                    agentRole = resolvedAgent.identity.role.displayName,
                                    modelResourceId = selectedModelId,
                                    tokensConsumed = _state.value.currentTokensConsumed,
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                    isSuccessful = false,
                                    eventCount = _state.value.executionLog.size
                                )
                                state.copy(
                                    isExecuting = false,
                                    errorMessage = event.message,
                                    executionLog = updatedLogs,
                                    studioSession = appendStudioTurn(
                                        state = state,
                                        prompt = prompt,
                                        agentName = resolvedAgent.identity.name,
                                        agentRole = resolvedAgent.identity.role.displayName,
                                        answer = state.streamText.ifBlank { event.message },
                                        isSuccessful = false,
                                        modelResourceId = selectedModelId
                                    )
                                )
                            }
                            else -> state.copy(executionLog = updatedLogs)
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
                if (currentExecutionTaskId == executionTaskId) currentExecutionTaskId = null
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
