package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.extension.ExtensionManager
import com.example.application.registry.ComponentRegistry
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.task.TaskId
import com.example.presentation.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val executeAgentTaskUseCase: ExecuteAgentTaskUseCase,
    // (ADR-6 slice 3) the knowledge feature — RAG documents, semantic-model
    // provisioning/readiness, and the long-term memory browser — left this
    // ViewModel for KnowledgeViewModel (with manageMemoryUseCase and
    // ragPipelineService, its whole dependency set).
    // (ADR-6 slice 4) the governance observatory — capability radar,
    // economic budget, and the human approval surface — left for
    // GovernanceViewModel (with capabilityRadarService,
    // economicGovernanceService, humanApprovalGate,
    // permissionGrantService, manageWorkspaceBudgetUseCase,
    // networkMonitorProvider and the local principal id).
    // (ADR-6 slice 5) the provider & resource control room — the four
    // control-plane flow collectors, the first-run provider bootstrap
    // seeding, the service test/discovery, the resource lifecycle ops, the
    // "Connect Provider" wizard and the credential dialog — left for
    // ProvidersViewModel (with providerControlPlaneService and
    // connectProviderUseCase, its whole dependency set).
    // (ADR-6 slice 6) the DECISION feature (cbrMdpEngine +
    // decisionSimulationUseCase + the decision display mirrors), the RADAR
    // & evolution observatory (intelligenceRadarPipeline + its two
    // collectors + the six lifecycle actions) and the WORKFLOW feature
    // (executeWorkflowUseCase, workflowLibraryService,
    // workflowPersistenceService + the builder/library/resume surface)
    // left for DecisionViewModel, RadarViewModel and WorkflowsViewModel
    // with their whole dependency sets.
    // GAP-19 (Design Closure 2026, ADR-6 step 2): extracted use-cases —
    // the simulation/provider-chain/budget business logic left the VM.
    private val componentRegistry: ComponentRegistry,
    private val extensionManager: ExtensionManager,
    // Phase 2 — workspace runtime service for multi-workspace support
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    // Phase 5 — intelligence services for the Unified Activity Feed
    private val telemetryService: com.example.application.observability.TelemetryService? = null,
    // (ADR-7 fate: workspaceContextEngine param removed with the deleted
    // engine — its suggestions flow was permanently empty.)
    private val telemetryPort: com.example.domain.ports.observability.TelemetryPort? = null,
    /**
     * GAP-CLOSURE P1-08/P1-10: canonical durable agent registry — the SAME
     * authority the runtime ComponentRegistry syncs from. Nullable for
     * source compatibility with existing call sites.
     */
    private val agentRegistryService: com.example.application.agent.AgentRegistryService? = null,
    /** REPAIR ORDER §3A — observable bootstrap state machine. */
    private val bootstrapStateProvider: kotlinx.coroutines.flow.StateFlow<com.example.application.bootstrap.BootstrapState>? = null,
    /**
     * ADR-6 SLICE 2 (Design Closure 2026 UI-redesign track): the STUDIO
     * signal bus — the conversation runtime moved to StudioViewModel. After
     * SLICE 6 this ViewModel collects ONLY the activity-feed stake (the
     * live execution id from ExecutionEvent.Started); the DECISION mirrors
     * and the network-policy re-simulation are collected by
     * DecisionViewModel, and the policy display mirror by GovernanceViewModel
     * — each feature its own collector. Created once per Activity in
     * MainActivity; nullable keeps the constructor source-compatible with
     * test constructions.
     */
    private val studioSignals: com.example.presentation.viewmodel.StudioSignalSource? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Phase 2 — expose active workspace and workspace list as StateFlows
    val activeWorkspace: StateFlow<com.example.domain.core.workspace.Workspace?> =
        workspaceRuntimeService.activeWorkspace
    val allWorkspaces: StateFlow<List<com.example.domain.core.workspace.Workspace>> =
        workspaceRuntimeService.allWorkspaces

    /** REPAIR ORDER §3A — the startup state machine, observable by the UI. */
    val bootstrapState: kotlinx.coroutines.flow.StateFlow<com.example.application.bootstrap.BootstrapState> =
        bootstrapStateProvider ?: workspaceRuntimeService.bootstrapState

    init {
        // REPAIR ORDER §3A — project-dependent UI gates on the bootstrap state
        // machine (BOOTSTRAPPING → … → READY). Failures surface EXPLICITLY
        // (with the failure phase + actionable message) instead of the raw
        // "No project is associated…" error that previously greeted users on
        // every degraded startup.
        viewModelScope.launch {
            bootstrapState.collect { state ->
                _uiState.update {
                    it.copy(
                        bootstrapPhase = state.phase.label,
                        bootstrapFailureMessage = (state.phase as? com.example.application.bootstrap.BootstrapPhase.Failed)?.message
                    )
                }
            }
        }
    }

    /**
     * GAP-23 (Design Closure 2026): re-run the bootstrap state machine from
     * the honest startup-failure gate. [WorkspaceRuntimeService.retryBootstrap]
     * is idempotent; the bootstrapState collector above updates
     * [UiState.bootstrapFailureMessage] — a successful retry therefore
     * dismisses the gate through the SAME flow that opened it.
     */
    fun retryBootstrap() {
        viewModelScope.launch {
            workspaceRuntimeService.retryBootstrap()
        }
    }

    /**
     * UNIFIED ACTIVITY TRACE WIRING FIX (report: "traceForExecution(\"\")" —
     * broken/partial wiring): the execution id of the CURRENTLY RUNNING
     * (or most recently finished) Studio execution. `ExecutionEvent.Started`
     * carries the REAL execution id; the activity feed switches to the
     * per-execution trace stream when set, and falls back to the recent
     * trace window otherwise — never to an empty-id query that matches
     * nothing.
     */
    private val activeExecutionId = MutableStateFlow<String?>(null)

    // Phase 5 — Unified Activity Feed state flows. These power the new
    // UnifiedActivityFeedScreen which renders a single timeline of
    // execution trace + audit events.
    // (ADR-7 fate: the suggestions flow was removed with its dead engine —
    // WorkspaceContextEngine had zero live inputs, so the flow was
    // permanently empty.)
    val activeExecutionTrace: StateFlow<List<com.example.domain.core.observability.ExecutionTraceNode>> =
        telemetryPort?.let { port ->
            // GAP-04 (Design Closure 2026): the feed reflects ONLY the active
            // (or executing) workspace. Mid-execution the trace stays bound
            // to the live execution; otherwise it follows the ACTIVE
            // workspace — traces from other workspaces are invisible.
            combine(flowOf(port), activeExecutionId, workspaceRuntimeService.activeWorkspace) { p, id, ws -> Triple(p, id, ws?.id) }
                .flatMapLatest { (p, id, wsId) ->
                    if (!id.isNullOrBlank()) {
                        // A live execution → that execution's trace (the
                        // execution pins its own workspace at launch).
                        p.traceForExecution(id)
                    } else {
                        // No live execution → the workspace-scoped recent window.
                        p.recentTraceNodes(wsId, 50)
                    }
                }
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        } ?: MutableStateFlow(emptyList<com.example.domain.core.observability.ExecutionTraceNode>()).asStateFlow()

    val recentAuditEvents: StateFlow<List<com.example.domain.core.observability.AuditEvent>> =
        telemetryPort?.let { port ->
            // GAP-04: audit events are workspace-scoped too — the global
            // read previously leaked other workspaces' audit rows into the
            // Unified Activity Feed.
            workspaceRuntimeService.activeWorkspace
                .flatMapLatest { ws -> port.auditEvents(ws?.id, 100) }
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        } ?: MutableStateFlow(emptyList<com.example.domain.core.observability.AuditEvent>()).asStateFlow()

    init {
        initializeAgents()
        observeSubsystems()
        loadInitialData()
        observeWorkspace()
        // (ADR-6 slice 4) GOVERNANCE PHASE: the observatory subscription
        // (observeGovernance) + the on-demand refresh moved to
        // GovernanceViewModel with the whole governance feature state.
        // (ADR-6 slice 5) PROVIDERS PHASE: the first-run provider bootstrap
        // seeding (launchBootstrapDefaults) + the four control-plane flow
        // collectors moved to ProvidersViewModel with the provider feature.
        // (ADR-6 slice 6) the workspace-scoped WORKFLOW assets observer
        // (library + resumable) moved to WorkflowsViewModel — its whole
        // dependency set left with it.
        // ADR-6 SLICE 2: collect the STUDIO feature's outbound signals — the
        // conversation runtime (execution, session binding, prompt state)
        // lives in StudioViewModel now; after slice 6 only the ACTIVITY-FEED
        // stake (the live execution id) remains here.
        observeStudioSignals()
    }

    /**
     * ADR-6 SLICES 2+6 — the studio signal bus collector. The conversation
     * runtime moved to StudioViewModel; after the slice-6 extraction this
     * ViewModel keeps only the ACTIVITY-FEED stake:
     *
     *  - Started → the live execution id (the activity feed's per-execution
     *    trace binding — same semantics as the pre-slice collector).
     *
     * The DECISION mirrors (DecisionMade / ObservationRecorded /
     * Completed / Error → latest decision, uncertainty, case base) and the
     * network-policy display mirror + re-simulation are collected by
     * DecisionViewModel from the SAME bus — each feature its own stake, no
     * shared mutable UiState between the ViewModels.
     */
    private fun observeStudioSignals() {
        val signals = studioSignals ?: return
        viewModelScope.launch {
            signals.collect { signal ->
                when (signal) {
                    is com.example.presentation.viewmodel.StudioSignal.ExecutionEvent -> {
                        when (val event = signal.event) {
                            is ExecutionEvent.Started -> activeExecutionId.value = event.executionId
                            else -> Unit
                        }
                    }
                    is com.example.presentation.viewmodel.StudioSignal.NetworkPolicyChanged -> Unit
                }
            }
        }
    }

    /*
     * (ADR-6 slice 4, Design Closure 2026 UI-redesign track) the ENTIRE
     * governance observatory moved to GovernanceViewModel: the Room-backed
     * radar observers (statuses / recommendations / changes), the on-demand
     * refresh (snapshot + budget/ledger summary + measurement health + the
     * approval queue), the budget allocation editor, the recommendation
     * dismissal, and the FULL approval loop (approve / reject /
     * "allow always" standing grant) — with its whole dependency set
     * (capabilityRadarService, economicGovernanceService,
     * humanApprovalGate, permissionGrantService,
     * manageWorkspaceBudgetUseCase, networkMonitorProvider,
     * localPrincipalId).
     */

    /**
     * Phase 2 — Observes the active workspace and reacts to workspace switches:
     *   - Updates UiState.activeProject to the active workspace's lastActiveProjectId
     *   - Reloads the RAG in-memory index from the new workspace's persisted knowledge
     *   - Refreshes files for the new project
     */
    private fun observeWorkspace() {
        viewModelScope.launch {
            // FIX R-5 (audit c03919d): unhandled exception in an init-path
            // collector previously crashed the app (no catch on launch).
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    if (workspace != null) {
                        _uiState.update {
                            it.copy(
                                activeProject = com.example.domain.core.storage.ProjectMetadata(
                                    // P0-04: honest project binding — 0 = no
                                    // project bound yet (never a silent 1L).
                                    id = workspace.activeProjectId.takeIf { id -> id > 0 } ?: 0L,
                                    name = workspace.name,
                                    description = workspace.description,
                                    isDefault = workspace.id == "default",
                                    createdAtTimestampMs = workspace.createdAtTimestampMs
                                )
                            )
                        }
                        // (ADR-6 slice 3) the RAG index reload on workspace
                        // re-scope now lives in KnowledgeViewModel — it owns
                        // the workspace-scoped knowledge feature.
                        // AUTONOMY DISPLAY SYNC (ADR-6 slice 1): the policy
                        // mutations now live in SettingsViewModel (routed to
                        // the AUTHORITATIVE service). This collector mirrors
                        // the persisted column back into the shared display
                        // state so the Studio badge stays live without the
                        // MainViewModel owning the mutation. The sandbox file
                        // listing ALSO no longer refreshes here — the new
                        // FilesViewModel observes the active project itself.
                        val autonomy = workspaceRuntimeService
                            .autonomyPolicyForWorkspace(workspace.id)
                        if (autonomy != null) {
                            _uiState.update { it.copy(autonomyPolicy = autonomy) }
                        }
                    }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر تحميل مساحة العمل النشطة: ${e.localizedMessage}") }
            }
        }
    }

    /*
     * ADR-6 slice 1 (Design Closure 2026 UI-redesign track) — the workspace
     * management mutations (switchWorkspace / createWorkspace /
     * updateWorkspaceNetworkPolicy / setAutonomyPolicy) and the ENTIRE files
     * feature (refreshFiles / openFile / closeFileEditor / saveFile /
     * createWorkspaceFile / deleteWorkspaceFile + the four UiState fields
     * + currentProjectIdOrInform) moved to SettingsViewModel and
     * FilesViewModel. MainViewModel keeps only the shared DISPLAY mirrors.
     */

    // (ADR-6 slice 3) provisionLocalSemanticModel / refreshSemanticModelStatus
    // — the semantic engine — moved to KnowledgeViewModel, the owner of the
    // previously-shared semanticModelReady / isProvisioningSemanticModel
    // state. Settings receives them as value+lambda; Explorer reads the
    // knowledge feature's own flow.

    private fun observeSubsystems() {
        viewModelScope.launch {
            extensionManager.skills.collect { skills ->
                _uiState.update { it.copy(skills = skills) }
            }
        }
        viewModelScope.launch {
            extensionManager.plugins.collect { plugins ->
                _uiState.update { it.copy(plugins = plugins) }
            }
        }
        viewModelScope.launch {
            extensionManager.mcpServers.collect { servers ->
                _uiState.update { it.copy(mcpServers = servers) }
            }
        }
        viewModelScope.launch {
            extensionManager.integrations.collect { integ ->
                _uiState.update { it.copy(integrations = integ) }
            }
        }
        // (ADR-6 slice 3) the knowledge documents collector moved to
        // KnowledgeViewModel — the listing's owner.
        // (ADR-6 slice 6) the radar items + evolution candidates collectors
        // moved to RadarViewModel — the radar observatory's owner.
    }

    /**
     * GAP-CLOSURE P1-08: the agent catalog is NO LONGER a UI-invented list —
     * it comes from the canonical durable registry (the same authority the
     * runtime ComponentRegistry syncs from). The agent the user selects IS
     * the agent that executes. Falls back to the runtime registry's live
     * list when the durable service is not wired (source-compat).
     */
    private fun initializeAgents() {
        // Synchronous cold-start catalog: the SAME definitions the durable
        // seed uses (no IO on the main thread, instant UX, no empty Studio).
        val initial = com.example.application.agent.CanonicalAgentCatalog.defaults
        _uiState.update {
            it.copy(
                availableAgents = initial,
                activeAgent = initial.firstOrNull()
            )
        }
        // Asynchronous authoritative refresh: once the durable registry is
        // loaded (first launch bootstrap or user-created agents), it replaces
        // the cold-start catalog (P1-08: one durable source of truth).
        agentRegistryService?.let { registry ->
            viewModelScope.launch {
                runCatching {
                    val agents = registry.listAgents().ifEmpty { componentRegistry.listAgents() }
                    if (agents.isNotEmpty()) {
                        _uiState.update { state ->
                            val stillPresent = state.activeAgent?.takeIf { a ->
                                agents.any { it.identity.id == a.identity.id }
                            }
                            state.copy(
                                availableAgents = agents,
                                activeAgent = stillPresent ?: agents.firstOrNull()
                            )
                        }
                    }
                }
            }
        }
    }

    /** Refreshes the agent catalog from the durable registry. */
    fun refreshAgentCatalog() {
        agentRegistryService?.let { registry ->
            viewModelScope.launch {
                runCatching {
                    val agents = registry.listAgents()
                    _uiState.update { state ->
                        state.copy(
                            availableAgents = agents,
                            activeAgent = state.activeAgent?.takeIf { a ->
                                agents.any { it.identity.id == a.identity.id }
                            } ?: agents.firstOrNull()
                        )
                    }
                }
            }
        }
    }

    /**
     * GAP-CLOSURE P1-10 (Agent Builder): creates a NEW agent through the
     * canonical durable registry and makes it immediately selectable +
     * executable (registered into the runtime ComponentRegistry).
     */
    fun createAgent(
        name: String,
        role: com.example.domain.core.agent.AgentRole,
        description: String,
        systemPrompt: String,
        capabilities: Set<com.example.domain.core.capability.CapabilityType>
    ) {
        val registry = agentRegistryService ?: run {
            _uiState.update { it.copy(errorMessage = "سجل الوكلاء الدائم غير متاح في هذا التكوين.") }
            return
        }
        viewModelScope.launch {
            runCatching {
                val created = registry.createAgent(
                    name = name,
                    role = role,
                    description = description,
                    systemPrompt = systemPrompt,
                    capabilities = capabilities
                )
                // Immediately executable (P1-10: create → configure → run).
                componentRegistry.registerAgent(created)
                created
            }.onSuccess { created ->
                _uiState.update { state ->
                    state.copy(
                        availableAgents = state.availableAgents + created,
                        activeAgent = created,
                        diagnosticBanner = "تم إنشاء الوكيل «${created.identity.name}» وحفظه في السجل الدائم — جاهز للتنفيذ."
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر إنشاء الوكيل: ${e.localizedMessage}") }
            }
        }
    }

    /** Deletes an agent from the durable catalog (P1-10 builder loop). */
    fun deleteAgent(agentId: String) {
        val registry = agentRegistryService ?: return
        viewModelScope.launch {
            runCatching {
                registry.deleteAgent(agentId)
                refreshAgentCatalog()
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            // FIX R-5: guarded initial load (previously an exception here — e.g.
            // corrupt DB row — crashed the app during ViewModel init).
            runCatching {
                // (ADR-6 slice 1) the initial sandbox listing is now loaded
                // by FilesViewModel's own workspace collector; (ADR-6 slice 3)
                // the initial memory listing is now loaded by
                // KnowledgeViewModel's init; (ADR-6 slice 6) the initial
                // decision simulation is now run by DecisionViewModel's own
                // init — no decision responsibility remains here.
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر تحميل البيانات الأولية: ${e.localizedMessage}") }
            }
        }
    }

    // --- Navigation ---
    // --- Agent Selection ---
    fun selectAgent(agent: AgentDefinition) {
        _uiState.update { it.copy(activeAgent = agent) }
    }

    /*
     * ADR-6 slice 2 (Design Closure 2026 UI-redesign track) — the ENTIRE
     * conversation runtime moved to StudioViewModel: prompt input + the
     * execution kernel (executePrompt / cancelExecution on the governed
     * ExecutionHost), Quick Chat + the model picker, the session network
     * policy (setNetworkPolicy — now published on the studio signal bus so
     * the decision preview and governance snapshot re-derive), the durable
     * turn persistence, and the active-session binding + transcript.
     * MainViewModel keeps only the shared DISPLAY mirrors: networkPolicy
     * (synced from the bus — see the studioSignals collector in init) and
     * the decision/activity projections of the execution events.
     */

    /*
     * ADR-6 slice 2 — the durable-session REGISTRY surface (the browser
     * list with GAP-14 project scoping, the sheet flag, deletion) moved to
     * SessionsViewModel, which observes the active workspace itself.
     *
     * (ADR-6 slice 6) the workspace-scoped WORKFLOW assets observer — the
     * library collector + the resumable refresh — moved to
     * WorkflowsViewModel, rewritten with flatMapLatest (slice-4 precedent:
     * the stacked inner collector per workspace emission is gone; the
     * library reflects ONLY the active workspace).
     */

    /*
     * (ADR-6 slice 6, Design Closure 2026 UI-redesign track) the ENTIRE
     * decision feature moved to DecisionViewModel: the state-vector
     * sliders (updateDecisionComplexity / updateDecisionUncertainty with
     * live re-simulation), simulateDecision / simulateDecisionInternal
     * (through the REAL DecisionSimulationUseCase, GAP-19) with the honest
     * no-use-case fallback, and the DECISION share of the studio signal bus
     * (DecisionMade / ObservationRecorded / Completed / Error mirrors + the
     * NetworkPolicyChanged re-simulation) — with its whole dependency set
     * (cbrMdpEngine, decisionSimulationUseCase).
     */

    // --- Extensibility Management ---
    fun toggleSkill(skillId: String) {
        extensionManager.toggleSkill(skillId)
    }

    fun executeSkillDirectly(skillId: String, parameters: Map<String, Any?>) {
        viewModelScope.launch {
            when (val outcome = extensionManager.executeSkill(skillId, parameters)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(diagnosticBanner = outcome.value) }
                    // (ADR-6 slice 1) the sandbox listing refresh moved with
                    // the Files feature — FilesViewModel re-lists on every
                    // visit to the Files screen and on project switches, so
                    // files a skill generated appear when the user opens the
                    // explorer.
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun togglePlugin(pluginId: String) {
        extensionManager.togglePlugin(pluginId)
    }

    fun toggleMcpServer(serverId: String) {
        extensionManager.toggleMcpServer(serverId)
    }

    fun pingMcpServer(serverId: String) {
        viewModelScope.launch {
            extensionManager.pingAndDiscoverMcpServer(serverId)
        }
    }

    fun registerMcpServer(name: String, endpointUri: String) {
        extensionManager.registerNewMcpServer(name, endpointUri)
    }

    fun connectIntegration(integrationId: String, token: String) {
        viewModelScope.launch {
            extensionManager.verifyAndConnectIntegration(integrationId, token)
        }
    }

    /*
     * (ADR-6 slice 6, Design Closure 2026 UI-redesign track) the ENTIRE
     * intelligence radar & evolution observatory moved to RadarViewModel:
     * the two pipeline observers (radarItems / evolutionCandidates), the
     * honest refresh (refreshRadar with the GAP-23 spinner flag), and the
     * FULL promotion lifecycle (advanceCandidateStage with the F-10 honest
     * governance-gate verdict, recordCandidateSecurityAudit,
     * recordCandidateGovernanceApproval, measureRegisteredCapability,
     * retireRegisteredCapability — GAP-CLOSURE P1-17) — with its whole
     * dependency set (intelligenceRadarPipeline).
     */

    // (ADR-6 slice 3) Knowledge & RAG operations — updateDocTitle /
    // updateDocContent / ingestNewDocument / queryKnowledgeRag /
    // deleteKnowledgeDocument — moved to KnowledgeViewModel with the whole
    // knowledge feature state.

    /*
     * (ADR-6 slice 6, Design Closure 2026 UI-redesign track) the ENTIRE
     * workflow feature moved to WorkflowsViewModel: the execution with
     * durable-resume seeding (executeWorkflow + the private completed-steps
     * overload), the FULL builder (updateWorkflowName / Goal / Mode /
     * addWorkflowStep / updateWorkflowStep / removeWorkflowStep /
     * moveWorkflowStep / toggleWorkflowStepDependency /
     * assignWorkflowStepAgent — canonical durable-agent binding — /
     * applyWorkflowTemplate), the library lifecycle (saveWorkflowDefinition
     * / loadWorkflowDefinitionIntoBuilder / runWorkflowDefinition /
     * cloneWorkflowDefinition / deleteWorkflowDefinition) and
     * resumeWorkflow — with its whole dependency set
     * (executeWorkflowUseCase, workflowLibraryService,
     * workflowPersistenceService). The TASK BOARD remains in
     * TasksViewModel (its owner since GAP-11).
     */

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the transient diagnostic banner shown in the app shell. */
    fun dismissDiagnosticBanner() {
        _uiState.update { it.copy(diagnosticBanner = null) }
    }

    /*
     * (ADR-6 slice 4) the APPROVAL SURFACE (REPAIR ORDER §3B/§2.2 + GAP-02
     * consent loop): refreshPendingApprovals / listPendingApprovals /
     * approveSensitiveAction / rejectSensitiveAction /
     * grantAlwaysForApproval — moved to GovernanceViewModel, the owner of
     * the approval queue and the standing-grant flow.
     */

    private companion object {
        // (CONVERSATION_HISTORY_WINDOW moved to StudioViewModel with the
        // conversation runtime — ADR-6 slice 2.)
    }
}
