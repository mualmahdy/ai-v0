package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.extension.ExtensionManager
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.usecases.ConnectProviderUseCase
import com.example.application.usecases.DecisionSimulationUseCase
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceBudgetUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.ServiceValidationResult
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.workflow.WorkflowPlan
import com.example.presentation.state.UiState
import com.example.application.provider.ProviderPreset
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val executeAgentTaskUseCase: ExecuteAgentTaskUseCase,
    private val executeWorkflowUseCase: ExecuteWorkflowUseCase,
    private val manageMemoryUseCase: ManageMemoryUseCase,
    // GAP-19 (Design Closure 2026, ADR-6 step 2): extracted use-cases —
    // the simulation/provider-chain/budget business logic left the VM.
    private val decisionSimulationUseCase: DecisionSimulationUseCase? = null,
    private val connectProviderUseCase: ConnectProviderUseCase? = null,
    private val manageWorkspaceBudgetUseCase: ManageWorkspaceBudgetUseCase? = null,
    private val componentRegistry: ComponentRegistry,
    private val cbrMdpEngine: CbrMdpEngine,
    private val extensionManager: ExtensionManager,
    private val intelligenceRadarPipeline: IntelligenceRadarPipeline,
    private val ragPipelineService: RagPipelineService,
    private val providerControlPlaneService: ProviderControlPlaneService,
    // Phase 2 — workspace runtime service for multi-workspace support
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    // Phase 5 — intelligence services for the Unified Activity Feed
    private val telemetryService: com.example.application.observability.TelemetryService? = null,
    // (ADR-7 fate: workspaceContextEngine param removed with the deleted
    // engine — its suggestions flow was permanently empty.)
    private val telemetryPort: com.example.domain.ports.observability.TelemetryPort? = null,
    /**
     * Real connectivity state (audit 2026 fix): replaces the previous
     * hardcoded `isNetworkAvailable = true` that made the OFFLINE policy
     * unreachable in practice. Injected from the AppContainer; nullable so
     * existing constructor call sites remain source-compatible.
     */
    private val networkMonitorProvider: com.example.infrastructure.network.NetworkMonitor? = null,
    /**
     * GAP-CLOSURE P1-08/P1-10: canonical durable agent registry — the SAME
     * authority the runtime ComponentRegistry syncs from. Nullable for
     * source compatibility with existing call sites.
     */
    private val agentRegistryService: com.example.application.agent.AgentRegistryService? = null,
    /**
     * GOVERNANCE PHASE — the operational capability radar (evidence-derived,
     * persisted). Nullable keeps existing constructor call sites compatible.
     */
    private val capabilityRadarService: com.example.application.radar.CapabilityRadarService? = null,
    /**
     * GOVERNANCE PHASE — economic governance facade (pricing, ledger,
     * budgets, rate limits). Nullable keeps existing call sites compatible.
     */
    private val economicGovernanceService: com.example.application.budget.EconomicGovernanceService? = null,
    /**
     * WORKFLOW LIBRARY (report gap-closure): user-authored workflow assets
     * (save / load / edit / clone / run history).
     */
    private val workflowLibraryService: com.example.application.workflow.WorkflowLibraryService? = null,
    /**
     * WORKFLOW EXECUTION PERSISTENCE — resumable workflows surface.
     */
    private val workflowPersistenceService: com.example.application.workflow.WorkflowPersistenceService? = null,
    /** REPAIR ORDER §3A — observable bootstrap state machine. */
    private val bootstrapStateProvider: kotlinx.coroutines.flow.StateFlow<com.example.application.bootstrap.BootstrapState>? = null,
    /** REPAIR ORDER §3B/§2.2 — human approval surface (consent loop). */
    private val humanApprovalGate: com.example.application.governed.HumanApprovalGate? = null,
    /**
     * GAP-02 (Design Closure 2026, ADR-2): the EXPLICIT local principal
     * identity — resolves approvals/grants with a durable device-local user
     * id instead of the anonymous "user" constant.
     */
    private val localPrincipalId: String = "local-device-user",
    /** GAP-02 (ADR-2c): "allow always" grants for sensitive tools. */
    private val permissionGrantService: com.example.application.security.PermissionGrantService? = null,
    /**
     * ADR-6 SLICE 2 (Design Closure 2026 UI-redesign track): the STUDIO
     * signal bus — the conversation runtime moved to StudioViewModel, and
     * this ViewModel COLLECTS the cross-feature projections of its
     * execution events (activity-trace execution id, decision case-base /
     * uncertainty mirrors) plus the session network-policy display mirror.
     * Created once per Activity in MainActivity; nullable keeps the
     * constructor source-compatible with test constructions.
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
        // DURABLE SESSIONS + WORKFLOW LIBRARY + RESUMABLE (report
        // gap-closure): workspace-scoped durable assets follow the active
        // workspace (continuity across switches).
        observeWorkspaceScopedAssets()
        // GOVERNANCE PHASE: subscribe the observatory to the backend-truth
        // radar flows (Room-backed, survive process death).
        observeGovernance()
        refreshGovernance()
        // Phase 4 — first-run bootstrap: seeds local embedding + multi-source
        // search + Gemini provider records (idempotent, no network for in-process).
        providerControlPlaneService.launchBootstrapDefaults()
        // ADR-6 SLICE 2: collect the STUDIO feature's outbound signals — the
        // conversation runtime (execution, session binding, prompt state)
        // lives in StudioViewModel now; these are the cross-feature
        // projections of its execution events + the session network-policy
        // display mirror (decision preview + governance snapshot inputs).
        observeStudioSignals()
    }

    /**
     * ADR-6 SLICE 2 — the studio signal bus collector. The conversation
     * runtime moved to StudioViewModel; the shared display mirrors it used
     * to write directly are updated HERE, from the feature's explicit
     * signals (no shared mutable UiState between the two ViewModels):
     *
     *  - Started → the live execution id (the activity feed's per-execution
     *    trace binding — same semantics as the pre-slice collector);
     *  - DecisionMade / ObservationRecorded / Completed / Error → the
     *    decision-display mirrors (latest decision, uncertainty, case base);
     *  - NetworkPolicyChanged → the session-policy display mirror + the
     *    decision preview re-simulation (the old setNetworkPolicy behaviour
     *    — policy change re-derives the preview — preserved exactly).
     */
    private fun observeStudioSignals() {
        val signals = studioSignals ?: return
        viewModelScope.launch {
            signals.collect { signal ->
                when (signal) {
                    is com.example.presentation.viewmodel.StudioSignal.ExecutionEvent -> {
                        when (val event = signal.event) {
                            is ExecutionEvent.Started -> activeExecutionId.value = event.executionId
                            is ExecutionEvent.DecisionMade -> _uiState.update {
                                it.copy(latestDecision = event.decision)
                            }
                            is ExecutionEvent.ObservationRecorded -> _uiState.update {
                                it.copy(
                                    decisionUncertainty = event.updatedUncertainty,
                                    caseBaseList = cbrMdpEngine.getCaseBase().getAllCases()
                                )
                            }
                            is ExecutionEvent.Completed, is ExecutionEvent.Error -> _uiState.update {
                                it.copy(caseBaseList = cbrMdpEngine.getCaseBase().getAllCases())
                            }
                            else -> Unit
                        }
                    }
                    is com.example.presentation.viewmodel.StudioSignal.NetworkPolicyChanged -> {
                        _uiState.update { it.copy(networkPolicy = signal.policy) }
                        simulateDecision()
                    }
                }
            }
        }
    }

    /**
     * GOVERNANCE PHASE — observatory data sources: radar statuses /
     * recommendations / changes flow straight from Room; budget + ledger
     * refresh on demand (suspend queries). All backend truth, no fabrication.
     */
    private fun observeGovernance() {
        val radar = capabilityRadarService ?: return
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    val wsId = workspace?.id
                    radar.observeCapabilityStatuses(wsId).collect { statuses ->
                        _uiState.update { it.copy(radarCapabilityStatuses = statuses) }
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    val wsId = workspace?.id
                    radar.observeRecommendations(wsId).collect { recos ->
                        _uiState.update { it.copy(radarRecommendations = recos) }
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    val wsId = workspace?.id
                    radar.observeChanges(wsId).collect { changes ->
                        _uiState.update { it.copy(radarChanges = changes) }
                    }
                }
            }
        }
    }

    /**
     * GOVERNANCE PHASE — on-demand refresh: derives a fresh radar snapshot
     * (evidence + registry facts) and reloads the budget/ledger summaries
     * for the active workspace.
     */
    fun refreshGovernance() {
        // GAP-02: the approval surface refreshes with the observatory.
        refreshPendingApprovals()
        viewModelScope.launch {
            // P0-03: bootstrap-aware — skip honestly when no workspace yet.
            val wsId = workspaceRuntimeService.awaitActiveWorkspaceId() ?: run {
                _uiState.update { it.copy(diagnosticBanner = "مساحة العمل لم تجهز بعد — تعذر تحديث الحوكمة.") }
                return@launch
            }
            val netAvailable = networkMonitorProvider?.isNetworkAvailable?.value ?: false
            runCatching {
                val snapshot = capabilityRadarService?.deriveSnapshot(
                    workspaceId = wsId,
                    networkPolicy = _uiState.value.networkPolicy,
                    isNetworkAvailable = netAvailable
                )
                if (snapshot != null) {
                    _uiState.update {
                        it.copy(
                            radarCapabilityStatuses = snapshot.capabilities,
                            radarRecommendations = snapshot.recommendations,
                            radarChanges = snapshot.changes
                        )
                    }
                }
            }.onFailure { failure ->
                // GAP-13: the refresh degradations are surfaced, not swallowed.
                _uiState.update {
                    it.copy(diagnosticBanner = "تعذر تحديث لقطة قدرات الحوكمة: ${failure.localizedMessage}")
                }
            }
            runCatching {
                val economics = economicGovernanceService ?: return@launch
                val status = economics.workspaceBudgetSummary(wsId)
                val recent = economics.recentLedgerForWorkspace(wsId, limit = 25)
                val tokens = economics.tokensConsumedForWorkspace(wsId)
                _uiState.update {
                    it.copy(
                        workspaceBudgetStatus = status,
                        costLedgerRecent = recent,
                        workspaceTokensConsumed = tokens
                    )
                }
            }.onFailure { failure ->
                // GAP-13: economic-summary degradation is surfaced.
                _uiState.update {
                    it.copy(diagnosticBanner = "تعذر تحديث ملخص الميزانية: ${failure.localizedMessage}")
                }
            }
            // GAP-24 (Design Closure 2026, ADR-8): honest measurement-health
            // snapshot — the observatory shows the telemetry persistence
            // layer's own failure counters, not just the data it persisted.
            runCatching {
                val health = telemetryPort?.measurementHealth()
                if (health != null) {
                    _uiState.update { it.copy(measurementHealth = health) }
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(diagnosticBanner = "تعذر قياس صحة طبقة القياس: ${failure.localizedMessage}")
                }
            }
        }
    }

    /**
     * GOVERNANCE PHASE — set the workspace monetary budget allocation (USD).
     * Policy: HARD_LIMIT + warn at 80% (enforced at BOTH the decide-time
     * gate in DecisionService and the pre-execution gate in
     * ExecutionService.executeLlmStep — GAP-05/ADR-5). Local tools carry no
     * cash cost and are honestly not cash-denied.
     */
    fun setWorkspaceBudgetAllocationUsd(amountUsd: Double) {
        val economics = economicGovernanceService ?: return
        val budgetUseCase = manageWorkspaceBudgetUseCase ?: return
        _uiState.update { it.copy(isSavingBudgetAllocation = true) }
        viewModelScope.launch {
            runCatching {
                // GAP-19 (ADR-6 step 2): the budget POLICY (HARD_LIMIT +
                // AUTO_LOCAL_FALLBACK, warn 80%, micro-USD conversion) lives
                // in ManageWorkspaceBudgetUseCase.
                budgetUseCase(
                    workspaceId = workspaceRuntimeService.requireActiveWorkspaceId(),
                    amountUsd = amountUsd
                )
                economics // (service presence guard retained above)
            }
            _uiState.update {
                it.copy(
                    isSavingBudgetAllocation = false,
                    budgetAllocationInputUsd = "%.2f".format(amountUsd)
                )
            }
            refreshGovernance()
        }
    }

    /** Dismisses a radar recommendation (persisted). */
    fun dismissRadarRecommendation(id: String) {
        viewModelScope.launch {
            runCatching { capabilityRadarService?.dismissRecommendation(id) }
        }
    }

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
                        // Reload RAG knowledge for the new workspace scope.
                        ragPipelineService.loadFromPersistence()
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

    /**
     * Local-first semantic RAG provisioning (audit 2026 fix): downloads the
     * on-device sentence-transformer ONCE (~23MB int8). Idempotent — no-ops
     * when already provisioned, and fails honestly (offline, network error)
     * without ever fabricating a "semantic" mode.
     */
    fun provisionLocalSemanticModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProvisioningSemanticModel = true) }
            runCatching {
                when (val r = ragPipelineService.provisionSemanticModel()) {
                    is Outcome.Success -> _uiState.update {
                        it.copy(
                            semanticModelReady = true,
                            isProvisioningSemanticModel = false,
                            // GAP-07: honest — new ingest + queries are semantic;
                            // pre-provisioning chunks stay lexical (boundary).
                            diagnosticBanner = "النموذج الدلالي المحلي جاهز — الاسترجاع والدمج الجديد دلالي على الجهاز؛ المتجهات القديمة تبقى معجمية (حد التوافق)."
                        )
                    }
                    is Outcome.Error -> _uiState.update {
                        it.copy(
                            isProvisioningSemanticModel = false,
                            diagnosticBanner = "تعذر تجهيز النموذج الدلالي المحلي: ${r.failure}"
                        )
                    }
                    else -> _uiState.update { it.copy(isProvisioningSemanticModel = false) }
                }
            }.onFailure {
                _uiState.update { it.copy(isProvisioningSemanticModel = false) }
            }
        }
    }

    /** Refreshes the honest on-device semantic-model readiness flag. */
    fun refreshSemanticModelStatus() {
        _uiState.update { it.copy(semanticModelReady = ragPipelineService.isLocalSemanticModelReady) }
    }

    private fun observeSubsystems() {
        viewModelScope.launch {
            providerControlPlaneService.allProvidersFlow.collect { providers ->
                _uiState.update { it.copy(generalizedProviders = providers) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allServicesFlow.collect { services ->
                _uiState.update { it.copy(generalizedServices = services) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allConfigurationsFlow.collect { configs ->
                _uiState.update { it.copy(generalizedConfigurations = configs) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allResourcesFlow.collect { resources ->
                _uiState.update { it.copy(materializedResources = resources) }
            }
        }
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
        viewModelScope.launch {
            intelligenceRadarPipeline.radarItems.collect { items ->
                _uiState.update { it.copy(radarItems = items) }
            }
        }
        viewModelScope.launch {
            intelligenceRadarPipeline.evolutionCandidates.collect { cand ->
                _uiState.update { it.copy(evolutionCandidates = cand) }
            }
        }
        viewModelScope.launch {
            ragPipelineService.documents.collect { docs ->
                _uiState.update { it.copy(knowledgeDocuments = docs) }
            }
        }
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
                refreshMemories()
                simulateDecision()
                // (ADR-6 slice 1) the initial sandbox listing is now loaded
                // by FilesViewModel's own workspace collector — no files
                // responsibility remains in this ViewModel.
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
     */

    /**
     * Subscribes the workflow library (+ resumable list) to the ACTIVE
     * WORKSPACE (workspace continuity): switching workspaces repoints the
     * workflow library and the resumable list. (The session browser list
     * used to be observed here too — it moved to SessionsViewModel with a
     * flatMapLatest re-scope on workspace/project change, fixing the
     * stacked-collector last-writer race the old per-emission launch had.)
     */
    private fun observeWorkspaceScopedAssets() {
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    val wsId = workspace?.id ?: return@collect

                    // Workflow library (user-authored assets).
                    workflowLibraryService?.let { library ->
                        launch {
                            runCatching {
                                library.observeLibrary(wsId).collect { defs ->
                                    _uiState.update { it.copy(workflowLibrary = defs) }
                                }
                            }
                        }
                    }

                    // Resumable workflow executions (durable resume surface).
                    loadResumableWorkflows()
                }
            }
        }
    }

    /** Refreshes the resumable (RUNNING/PAUSED/COMPENSATING) workflows list —
     *  WORKSPACE-SCOPED (defect family 1): only the ACTIVE workspace's runs. */
    fun loadResumableWorkflows() {
        val persistence = workflowPersistenceService ?: return
        viewModelScope.launch {
            runCatching {
                val workspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull()
                _uiState.update { it.copy(resumableWorkflows = persistence.resumable(workspaceId)) }
            }
        }
    }

    // --- Decision Intelligence (CBR-MDP) ---
    fun updateDecisionComplexity(value: Float) {
        _uiState.update { it.copy(decisionTaskComplexity = value) }
        simulateDecision()
    }

    fun updateDecisionUncertainty(value: Float) {
        _uiState.update { it.copy(decisionUncertainty = value) }
        simulateDecision()
    }

    fun simulateDecision() {
        // Readiness-gated engine evaluation (defect family 5): the decision
        // may suspend on case-base load readiness, so it runs scoped.
        // GAP-23 (Design Closure 2026): the spinner flag is now WRITTEN
        // honestly around the engine call — it was previously a no-writer
        // field, so DecisionScreen's progress indicator could never appear.
        viewModelScope.launch {
            _uiState.update { it.copy(isSimulatingDecision = true) }
            try {
                simulateDecisionInternal()
            } finally {
                _uiState.update { it.copy(isSimulatingDecision = false) }
            }
        }
    }

    private suspend fun simulateDecisionInternal() {
        // GAP-19 (ADR-6 step 2): the simulation business rules (state
        // construction + candidate set + engine evaluation) live in
        // DecisionSimulationUseCase; the VM only projects the result.
        val useCase = decisionSimulationUseCase
        if (useCase == null) {
            // Honest fallback for legacy constructions without the use-case:
            // the preview is unavailable rather than fabricated.
            return
        }
        val current = _uiState.value
        val outcome = useCase(
            taskComplexity = current.decisionTaskComplexity,
            uncertaintyScore = current.decisionUncertainty,
            networkPolicy = current.networkPolicy
        )
        _uiState.update {
            it.copy(
                latestDecision = outcome.decision,
                caseBaseList = outcome.caseBase
            )
        }
    }

    // --- Provider & Resource Control Plane (Phase 4 — generalized API) ---

    /**
     * Test the connection for a ServiceConfiguration. Real protocol probe
     * via the resource validators — for LLM services this is a lightweight
     * GET /models reachability + authentication check (NOT a generation
     * call; POST /chat/completions is never issued by validation).
     * GAP-25 (Design Closure 2026): the previous KDoc claimed a POST
     * /chat/completions probe, which never matched the implementation.
     */
    fun testServiceConnection(configId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingProvider = true, testingProviderId = configId) }
            try {
                when (val outcome = providerControlPlaneService.testServiceConnection(configId)) {
                    is Outcome.Success -> {
                        _uiState.update {
                            it.copy(
                                isTestingProvider = false,
                                diagnosticBanner = outcome.value.message
                            )
                        }
                    }
                    is Outcome.Error -> {
                        _uiState.update {
                            it.copy(
                                isTestingProvider = false,
                                errorMessage = outcome.diagnosticMessage
                            )
                        }
                    }
                    else -> _uiState.update { it.copy(isTestingProvider = false) }
                }
            } finally {
                _uiState.update { it.copy(isTestingProvider = false) }
            }
        }
    }

    /**
     * Discover offerings for a service. Explicit network discovery — produces
     * `ServiceOffering`s but does NOT materialize ResourceRecords.
     */
    fun discoverOfferings(serviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringModels = true) }
            try {
                when (val outcome = providerControlPlaneService.discoverOfferings(serviceId)) {
                    is Outcome.Success -> {
                        _uiState.update {
                            it.copy(discoveredOfferings = outcome.value)
                        }
                    }
                    is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                    else -> Unit
                }
            } finally {
                _uiState.update { it.copy(isDiscoveringModels = false) }
            }
        }
    }

    /**
     * Materialize a ServiceOffering into a ResourceRecord. The record starts
     * at REGISTERED/runtimeSupported=false/UNKNOWN. The user must call
     * `validateResource(resourceId)` to promote it to ENABLED/true/HEALTHY.
     */
    fun materializeResource(providerId: String, serviceId: String, offeringId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.materializeResource(providerId, serviceId, offeringId)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Validate a materialized ResourceRecord. Runs the appropriate
     * ResourceValidator and updates lifecycle/runtimeSupported/health.
     */
    fun validateResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.validateResource(ResourceId(resourceId))) {
                is Outcome.Success -> {
                    _uiState.update {
                        it.copy(diagnosticBanner = outcome.value.message)
                    }
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Enable a previously-validated resource.
     */
    fun enableResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.enableResource(ResourceId(resourceId))) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Disable a materialized resource (lifecycle → DISABLED).
     */
    fun disableResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.disableResource(ResourceId(resourceId))) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.deleteProvider(id)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun toggleProvider(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.toggleProvider(id, isEnabled)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------
    // "Connect Provider" wizard — the FULL CHAIN in one guided action.
    // Fix (user feedback: adding a provider left it unusable): previously the
    // add-dialog persisted a bare Provider row with no Service/Config/Offering,
    // so no resource could ever be materialized from it. The wizard walks:
    //
    //   1 Provider → 2 Service → 3 Configuration + vault key →
    //   4 Offering → 5 Materialize → 6 Validate → (auto-ENABLED on success)
    //
    // Every step reports honest progress; a failure stops the chain and
    // surfaces the real diagnostic (no fabricated success).
    // ------------------------------------------------------------------

    fun openConnectWizard() {
        _uiState.update {
            it.copy(
                isConnectWizardOpen = true,
                wizardRunning = false,
                wizardStep = 0,
                wizardStepLabel = null,
                wizardResult = null
            )
        }
    }

    fun closeConnectWizard() {
        if (_uiState.value.wizardRunning) return // no canceling mid-chain from the dialog
        _uiState.update {
            it.copy(
                isConnectWizardOpen = false,
                wizardStep = 0,
                wizardStepLabel = null,
                wizardResult = null,
                wizardResultIsSuccess = true
            )
        }
    }

    fun connectProviderFullChain(
        preset: ProviderPreset,
        providerName: String,
        endpointUrl: String,
        modelName: String,
        apiKey: String?
    ) {
        // GAP-19 (ADR-6 step 2): the 7-step connection chain (id scheme,
        // domain construction, capability map, materialize+validate) lives
        // in ConnectProviderUseCase; the VM projects progress + result into
        // the wizard UiState only.
        val useCase = connectProviderUseCase ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(wizardRunning = true, wizardStep = 1, wizardStepLabel = "إنشاء المزوّد…", wizardResult = null)
            }
            when (
                val result = useCase(
                    preset = preset,
                    providerName = providerName,
                    endpointUrl = endpointUrl,
                    modelName = modelName,
                    apiKey = apiKey,
                    onStep = { step, label ->
                        _uiState.update { it.copy(wizardStep = step, wizardStepLabel = label) }
                    }
                )
            ) {
                is ConnectProviderUseCase.Result.Rejected -> failWizard(result.message)
                is ConnectProviderUseCase.Result.Failed -> failWizard(result.message)
                is ConnectProviderUseCase.Result.SavedUnverified -> _uiState.update {
                    it.copy(
                        wizardRunning = false,
                        wizardStep = 6,
                        wizardStepLabel = null,
                        wizardResult = result.message,
                        wizardResultIsSuccess = false
                    )
                }
                is ConnectProviderUseCase.Result.Connected -> _uiState.update {
                    it.copy(
                        wizardRunning = false,
                        wizardStep = 7,
                        wizardStepLabel = null,
                        wizardResult = result.message,
                        wizardResultIsSuccess = true,
                        diagnosticBanner = "تم تفعيل ${preset.displayName} بنجاح"
                    )
                }
            }
        }
    }

    private fun failWizard(message: String) {
        _uiState.update {
            it.copy(
                wizardRunning = false,
                wizardStepLabel = null,
                wizardResult = message,
                wizardResultIsSuccess = false
            )
        }
    }

    // ------------------------------------------------------------------
    // FIX F-4 (audit c03919d): credential input dialog — a real user path to
    // store an API key for a service configuration. Previously there was NO
    // way to enter a key (the flag existed but nothing read it), so every
    // remote provider stayed unusable.
    // ------------------------------------------------------------------

    fun openCredentialDialog(serviceId: String, serviceName: String, authAlias: String?) {
        _uiState.update {
            it.copy(
                credentialDialogServiceId = serviceId,
                credentialDialogServiceName = serviceName,
                credentialDialogAuthAlias = authAlias,
                credentialInput = ""
            )
        }
    }

    fun updateCredentialInput(value: String) {
        _uiState.update { it.copy(credentialInput = value) }
    }

    fun closeCredentialDialog() {
        _uiState.update {
            it.copy(
                credentialDialogServiceId = null,
                credentialDialogServiceName = "",
                credentialDialogAuthAlias = null,
                credentialInput = "",
                isSavingCredential = false
            )
        }
    }

    /**
     * Stores the entered secret under the service's authAlias (or the service
     * id as the storage key) and immediately runs a real connection test so
     * the user gets honest feedback that the key works.
     */
    fun submitCredential() {
        val state = _uiState.value
        val serviceId = state.credentialDialogServiceId ?: return
        val authAlias = state.credentialDialogAuthAlias ?: serviceId
        val secret = state.credentialInput.trim()
        if (secret.isEmpty()) return

        _uiState.update { it.copy(isSavingCredential = true) }
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.storeSecret(authAlias, secret)) {
                is Outcome.Success -> {
                    _uiState.update {
                        it.copy(
                            isSavingCredential = false,
                            diagnosticBanner = "تم حفظ المفتاح بنجاح. جاري التحقق من الاتصال..."
                        )
                    }
                    closeCredentialDialog()
                    // Explicit validation right after storing the key —
                    // honest feedback instead of silent "saved".
                    val config = providerControlPlaneService.getCurrentConfigurationForService(serviceId)
                    if (config != null) {
                        testServiceConnection(config.id)
                    }
                    // FIX (usable-provider flow): also promote the materialized
                    // resource(s) of this service — validateResource runs the real
                    // protocol check and flips the record to ENABLED/HEALTHY, so
                    // entering the key on the seeded Gemini provider ACTIVATES it
                    // for the Studio instead of leaving it at REGISTERED.
                    val resourcesForService = _uiState.value.materializedResources
                        .filter { it.serviceId == serviceId }
                    for (resource in resourcesForService) {
                        validateResource(resource.resourceId.value)
                    }
                }
                is Outcome.Error -> {
                    _uiState.update {
                        it.copy(isSavingCredential = false, errorMessage = outcome.diagnosticMessage)
                    }
                }
                else -> _uiState.update { it.copy(isSavingCredential = false) }
            }
        }
    }

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

    // --- Intelligence Radar & Evolution ---
    fun refreshRadar() {
        // GAP-23 (Design Closure 2026): the refresh indicator is now WRITTEN
        // honestly around the pipeline call — previously a no-writer field,
        // so RadarScreen's spinner could never appear.
        viewModelScope.launch {
            _uiState.update { it.copy(isRadarRefreshing = true) }
            try {
                intelligenceRadarPipeline.refreshRadarFeed()
            } finally {
                _uiState.update { it.copy(isRadarRefreshing = false) }
            }
        }
    }

    fun advanceCandidateStage(candidateId: String, nextStage: EvolutionStage) {
        viewModelScope.launch {
            // FIX F-10: surface the governance gate's verdict honestly instead
            // of silently ignoring a rejected promotion.
            when (val outcome = intelligenceRadarPipeline.advanceEvolutionStage(candidateId, nextStage)) {
                is Outcome.Success -> {
                    _uiState.update {
                        it.copy(diagnosticBanner = "تمت ترقية المرشح إلى ${nextStage.displayName}.")
                    }
                }
                is Outcome.Error -> {
                    _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                }
                else -> Unit
            }
        }
    }

    /**
     * GAP-CLOSURE P1-17 — the acquisition loop is now COMPLETE and operable:
     * security audit, governance approval, registration measurement, and
     * retirement all have explicit, durable entry points.
     */
    fun recordCandidateSecurityAudit(candidateId: String, passed: Boolean) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.recordSecurityAudit(candidateId, passed, "تدقيق من مرصد التطور")) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _uiState.update {
                    it.copy(diagnosticBanner = "نتيجة التدقيق الأمني: ${if (passed) "ناجح" else "فاشل"}.")
                }
                else -> Unit
            }
        }
    }

    fun recordCandidateGovernanceApproval(candidateId: String, approved: Boolean) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.recordGovernanceApproval(candidateId, approved)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _uiState.update {
                    it.copy(diagnosticBanner = "موافقة الحوكمة: ${if (approved) "ممنوحة" else "مرفوضة"}.")
                }
                else -> Unit
            }
        }
    }

    fun measureRegisteredCapability(candidateId: String) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.measureRegisteredCapability(candidateId)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _uiState.update {
                    it.copy(diagnosticBanner = "تم تسجيل قياس أساسي للقدرة في تدفق أدلة رادار القدرات.")
                }
                else -> Unit
            }
        }
    }

    fun retireRegisteredCapability(candidateId: String, reason: String) {
        viewModelScope.launch {
            when (val r = intelligenceRadarPipeline.retireCapability(candidateId, reason)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = r.diagnosticMessage) }
                is Outcome.Success -> _uiState.update {
                    it.copy(diagnosticBanner = "أُحيلت القدرة المسجلة إلى التقاعد: $reason")
                }
                else -> Unit
            }
        }
    }

    // --- Knowledge & RAG Operations ---
    fun updateDocTitle(title: String) {
        _uiState.update { it.copy(newDocTitle = title) }
    }

    fun updateDocContent(content: String) {
        _uiState.update { it.copy(newDocContent = content) }
    }

    fun ingestNewDocument() {
        val title = _uiState.value.newDocTitle.trim()
        val content = _uiState.value.newDocContent.trim()
        if (title.isEmpty() || content.isEmpty()) return

        viewModelScope.launch {
            // FIX P0-8 (audit c03919d): sanitize the title so it cannot inject
            // path separators into the workspace:// source URI.
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val ingested = ragPipelineService.ingestDocument(safeTitle, content, "workspace://docs/$safeTitle.md")
            // GAP-CLOSURE P1-14: surface honest persistence failure to the user.
            _uiState.update {
                when (ingested.persistenceState) {
                    com.example.domain.core.rag.KnowledgePersistenceState.FAILED -> it.copy(
                        newDocTitle = "",
                        newDocContent = "",
                        diagnosticBanner = ingested.persistenceDiagnostic ?: "تعذر حفظ المستند في قاعدة البيانات."
                    )
                    else -> it.copy(newDocTitle = "", newDocContent = "")
                }
            }
        }
    }

    fun queryKnowledgeRag(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            val assembled = ragPipelineService.retrieveRelevantContext(query)
            _uiState.update { it.copy(assembledRagContext = assembled) }
        }
    }

    /**
     * Deletes a knowledge document from BOTH the in-memory index and the
     * durable Room store (honest outcome surfaced to the user).
     */
    fun deleteKnowledgeDocument(documentId: String) {
        viewModelScope.launch {
            when (val outcome = ragPipelineService.deleteDocument(documentId)) {
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.failure) }
                else -> _uiState.update {
                    it.copy(diagnosticBanner = "تم حذف المستند من قاعدة المعرفة.")
                }
            }
        }
    }

    // --- Workflow & Task ---
    fun executeWorkflow(plan: WorkflowPlan) {
        executeWorkflow(plan, completedStepIds = emptySet())
    }

    /**
     * DURABLE RESUME (report gap: "Resume later"): carries the steps a
     * previous run already finished — the engine seeds them COMPLETED and
     * never re-executes them.
     */
    private fun executeWorkflow(plan: WorkflowPlan, completedStepIds: Set<String>) {
        if (_uiState.value.isExecutingWorkflow) return
        _uiState.update { it.copy(isExecutingWorkflow = true, workflowReport = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val report = executeWorkflowUseCase(plan, completedStepIds)
                _uiState.update { it.copy(isExecutingWorkflow = false, workflowReport = report) }
                // Refresh the resumable surface (a failed run is resumable).
                loadResumableWorkflows()
            } catch (e: Exception) {
                _uiState.update { it.copy(isExecutingWorkflow = false, errorMessage = "فشل تنفيذ خطة العمل: ${e.localizedMessage}") }
                loadResumableWorkflows()
            }
        }
    }

    // ==================================================================
    // WORKFLOW BUILDER (report gap: authoring state lives in the ViewModel,
    // not Compose memory — the definition is a durable, re-editable asset)
    // ==================================================================

    fun updateWorkflowName(name: String) {
        _uiState.update { it.copy(workflowBuilder = it.workflowBuilder.copy(name = name)) }
    }

    fun updateWorkflowGoal(goal: String) {
        _uiState.update { it.copy(workflowBuilder = it.workflowBuilder.copy(goal = goal)) }
    }

    fun updateWorkflowMode(mode: com.example.domain.core.workflow.ExecutionMode) {
        _uiState.update { it.copy(workflowBuilder = it.workflowBuilder.copy(executionMode = mode)) }
    }

    fun addWorkflowStep() {
        _uiState.update { state ->
            val steps = state.workflowBuilder.steps
            val newStep = com.example.presentation.state.WorkflowBuilderStep(
                id = "step_${steps.size + 1}_${System.currentTimeMillis() % 1000}",
                description = "",
                role = com.example.domain.core.agent.AgentRole.GENERAL_ASSISTANT,
                dependencies = emptySet()
            )
            state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps + newStep))
        }
    }

    fun updateWorkflowStep(index: Int, transform: (com.example.presentation.state.WorkflowBuilderStep) -> com.example.presentation.state.WorkflowBuilderStep) {
        _uiState.update { state ->
            val steps = state.workflowBuilder.steps.toMutableList()
            if (index in steps.indices) {
                steps[index] = transform(steps[index])
                state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps))
            } else state
        }
    }

    fun removeWorkflowStep(index: Int) {
        _uiState.update { state ->
            val steps = state.workflowBuilder.steps.toMutableList()
            if (index in steps.indices) steps.removeAt(index)
            state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps))
        }
    }

    fun moveWorkflowStep(index: Int, delta: Int) {
        _uiState.update { state ->
            val steps = state.workflowBuilder.steps.toMutableList()
            val target = index + delta
            if (index in steps.indices && target in steps.indices) {
                val moved = steps.removeAt(index)
                steps.add(target, moved)
                state.copy(workflowBuilder = state.workflowBuilder.copy(steps = steps))
            } else state
        }
    }

    fun toggleWorkflowStepDependency(index: Int, depId: String) {
        updateWorkflowStep(index) { step ->
            step.copy(
                dependencies = if (depId in step.dependencies) step.dependencies - depId
                else step.dependencies + depId
            )
        }
    }

    /**
     * CANONICAL AGENT BINDING (report gap): assigns a DURABLE registry agent
     * to a builder step — the step executes through the real agent (system
     * prompt, capabilities, budget, version, lifecycle), not a synthetic one.
     */
    fun assignWorkflowStepAgent(index: Int, agentId: String?) {
        updateWorkflowStep(index) { it.copy(assignedAgentId = agentId) }
    }

    /** Applies a built-in template to the builder. */
    fun applyWorkflowTemplate(template: com.example.presentation.state.WorkflowBuilderState) {
        _uiState.update { it.copy(workflowBuilder = template) }
    }

    // ==================================================================
    // WORKFLOW LIBRARY (report gap: save → list → load → edit → clone →
    // run — the USER-AUTHORED definition as a durable workspace asset)
    // ==================================================================

    /** Saves the current builder as a library definition (or re-saves it). */
    fun saveWorkflowDefinition() {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            runCatching {
                val builder = _uiState.value.workflowBuilder
                val plan = WorkflowPlan(
                    id = com.example.domain.core.workflow.WorkflowId(
                        builder.editingDefinitionId ?: "wf_builder_${System.currentTimeMillis()}"
                    ),
                    goal = builder.goal.trim(),
                    executionMode = builder.executionMode,
                    steps = builder.steps.map { s ->
                        com.example.domain.core.workflow.StepNode(
                            id = s.id,
                            taskId = com.example.domain.core.task.TaskId("task_${s.id}"),
                            agentRole = s.role,
                            description = s.description.ifBlank { "${s.role.displayName} — خطوة ${s.id}" },
                            dependencies = s.dependencies,
                            assignedAgentId = s.assignedAgentId
                        )
                    }
                )
                val id = library.saveDefinition(
                    existingId = builder.editingDefinitionId?.let { com.example.domain.core.workflow.WorkflowId(it) },
                    name = builder.name,
                    plan = plan
                )
                _uiState.update {
                    it.copy(
                        workflowBuilder = it.workflowBuilder.copy(editingDefinitionId = id.value),
                        diagnosticBanner = "تم حفظ خطة العمل في المكتبة (الإصدار محفوظ ويُحرَّر لاحقاً)."
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر حفظ خطة العمل: ${e.localizedMessage}") }
            }
        }
    }

    /** Loads a library definition into the builder for editing. */
    fun loadWorkflowDefinitionIntoBuilder(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            runCatching {
                val summary = _uiState.value.workflowLibrary.firstOrNull { it.workflowId.value == definitionId }
                val plan = library.loadDefinition(com.example.domain.core.workflow.WorkflowId(definitionId))
                    ?: return@launch
                _uiState.update {
                    it.copy(
                        workflowBuilder = com.example.presentation.state.WorkflowBuilderState(
                            name = summary?.name ?: "خطة عمل",
                            goal = plan.goal,
                            executionMode = plan.executionMode,
                            steps = plan.steps.map { s ->
                                com.example.presentation.state.WorkflowBuilderStep(
                                    id = s.id,
                                    description = s.description,
                                    role = s.agentRole,
                                    dependencies = s.dependencies,
                                    assignedAgentId = s.assignedAgentId
                                )
                            },
                            editingDefinitionId = definitionId
                        )
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر تحميل خطة العمل: ${e.localizedMessage}") }
            }
        }
    }

    /** Runs a library definition directly (records the run). */
    fun runWorkflowDefinition(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            runCatching {
                val plan = library.loadDefinition(com.example.domain.core.workflow.WorkflowId(definitionId)) ?: return@launch
                library.recordRun(com.example.domain.core.workflow.WorkflowId(definitionId))
                executeWorkflow(plan)
            }
        }
    }

    /** Clones a library definition. */
    fun cloneWorkflowDefinition(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            // GAP-13: degradation surfaced (was a bare runCatching).
            runCatching { library.cloneDefinition(com.example.domain.core.workflow.WorkflowId(definitionId)) }
                .onFailure { failure ->
                    _uiState.update { it.copy(diagnosticBanner = "تعذر استنساخ خطة العمل: ${failure.localizedMessage}") }
                }
        }
    }

    /** Deletes a library definition. */
    fun deleteWorkflowDefinition(definitionId: String) {
        val library = workflowLibraryService ?: return
        viewModelScope.launch {
            // GAP-13: degradation surfaced (was a bare runCatching).
            runCatching { library.deleteDefinition(com.example.domain.core.workflow.WorkflowId(definitionId)) }
                .onFailure { failure ->
                    _uiState.update { it.copy(diagnosticBanner = "تعذر حذف خطة العمل: ${failure.localizedMessage}") }
                }
        }
    }

    /**
     * RESUMES a durable interrupted workflow execution (report gap:
     * "Resume later"): the already-completed steps are seeded COMPLETED and
     * never re-executed; remaining steps run with real prior outputs as
     * upstream context.
     */
    fun resumeWorkflow(workflowId: String) {
        val persistence = workflowPersistenceService ?: return
        viewModelScope.launch {
            // GAP-13: degradation surfaced (was a bare runCatching).
            runCatching {
                // WORKSPACE-SCOPED resume (defect family 1): a workflow of
                // ANOTHER workspace cannot be resumed from this surface.
                val workspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull()
                val resumable = persistence.resumable(workspaceId).firstOrNull { it.workflowId.value == workflowId }
                    ?: return@launch
                executeWorkflow(resumable.plan, resumable.completedStepIds)
            }.onFailure { failure ->
                _uiState.update { it.copy(diagnosticBanner = "تعذر استئناف التنفيذ: ${failure.localizedMessage}") }
            }
        }
    }

    // --- Memory Operations ---
    fun updateMemoryQuery(q: String) {
        _uiState.update { it.copy(memoryQuery = q) }
    }

    fun searchMemory() {
        val q = _uiState.value.memoryQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingMemory = true) }
            when (val outcome = manageMemoryUseCase.retrieveContext(q)) {
                is Outcome.Success -> _uiState.update { it.copy(retrievedMemories = outcome.value, isSearchingMemory = false) }
                is Outcome.Degraded -> _uiState.update { it.copy(retrievedMemories = outcome.partialValue ?: emptyList(), isSearchingMemory = false) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage, isSearchingMemory = false) }
            }
        }
    }

    fun updateNewMemoryContent(text: String) {
        _uiState.update { it.copy(newMemoryContent = text) }
    }

    fun addNewMemory() {
        val content = _uiState.value.newMemoryContent.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val entry = MemoryEntry(
                id = UUID.randomUUID().toString(),
                content = content,
                type = MemoryType.FACTUAL_INSIGHT,
                confidence = 1.0f,
                provenance = MemoryProvenance(sourceSessionId = "MANUAL_ENTRY", createdAtTimestampMs = System.currentTimeMillis()),
                isActive = true
            )
            when (val outcome = manageMemoryUseCase.recordInsight(entry)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(newMemoryContent = "") }
                    refreshMemories()
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> refreshMemories()
            }
        }
    }

    fun refreshMemories() {
        viewModelScope.launch {
            when (val outcome = manageMemoryUseCase.getActiveMemories()) {
                is Outcome.Success -> _uiState.update { it.copy(allMemories = outcome.value) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }


    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the transient diagnostic banner shown in the app shell. */
    fun dismissDiagnosticBanner() {
        _uiState.update { it.copy(diagnosticBanner = null) }
    }

    // ====================================================================
    // REPAIR ORDER §3B/§2.2 — APPROVAL SURFACE (the previously unreachable
    // human-consent loop: pending approvals are now visible + resolvable).
    // ====================================================================

    /**
     * GAP-02 (Design Closure 2026, ADR-2): REACTIVE approval surface —
     * pending requests land in [UiState.pendingApprovals] where the
     * governance screen renders them. Previously these functions had ZERO
     * screen callers (the consent loop was a dead end).
     */
    fun refreshPendingApprovals() {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            val pending = runCatching { gate.pendingApprovals() }.getOrDefault(emptyList())
            _uiState.update { it.copy(pendingApprovals = pending) }
        }
    }

    fun listPendingApprovals(
        onResult: (List<com.example.domain.ports.governed.HumanApprovalRequest>) -> Unit
    ) {
        val gate = humanApprovalGate ?: return onResult(emptyList())
        viewModelScope.launch {
            onResult(runCatching { gate.pendingApprovals() }.getOrDefault(emptyList()))
        }
    }

    fun approveSensitiveAction(approvalId: String, resolvedBy: String = localPrincipalId) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            runCatching { gate.approve(approvalId, resolvedBy) }
                .onSuccess { resolution ->
                    val banner = if (resolution.name == "APPROVED") "تمت الموافقة على الإجراء الحساس." else resolution.name
                    _uiState.update { it.copy(diagnosticBanner = banner) }
                    refreshPendingApprovals()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = "تعذر تسجيل الموافقة: ${e.localizedMessage}") }
                }
        }
    }

    fun rejectSensitiveAction(approvalId: String, resolvedBy: String = localPrincipalId) {
        val gate = humanApprovalGate ?: return
        viewModelScope.launch {
            runCatching { gate.reject(approvalId, resolvedBy) }
                .onSuccess {
                    _uiState.update { it.copy(diagnosticBanner = "تم رفض الإجراء الحساس.") }
                    refreshPendingApprovals()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = "تعذر تسجيل الرفض: ${e.localizedMessage}") }
                }
        }
    }

    /**
     * GAP-02 (ADR-2c): "السماح دائماً لهذه الأداة" — an EXECUTE
     * permission-grant for the tool (recorded consent: future admissions of
     * this tool pass without a new request), plus resolving the CURRENT
     * pending request so the in-flight execution can also proceed (its
     * one-shot token remains consumable).
     */
    fun grantAlwaysForApproval(approvalId: String) {
        val gate = humanApprovalGate ?: return
        val grants = permissionGrantService
        viewModelScope.launch {
            if (grants == null) {
                _uiState.update { it.copy(errorMessage = "خدمة منح الأذونات غير متاحة.") }
                return@launch
            }
            runCatching {
                val pending = gate.pendingApprovals().firstOrNull { it.approvalId == approvalId }
                if (pending != null) {
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
                }
            }.onSuccess {
                _uiState.update { it.copy(diagnosticBanner = "تم السماح دائماً بهذه الأداة (منح EXECUTE دائم).") }
                refreshPendingApprovals()
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر تسجيل المنح الدائم: ${e.localizedMessage}") }
            }
        }
    }

    private companion object {
        // (CONVERSATION_HISTORY_WINDOW moved to StudioViewModel with the
        // conversation runtime — ADR-6 slice 2.)
    }
}
