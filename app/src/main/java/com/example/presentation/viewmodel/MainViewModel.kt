package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.presentation.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * MainViewModel — the APP SHELL (ADR-6 option-ج terminal state, slice 7)
 * ============================================================================
 *
 * After seven extraction slices (Files+Settings / Studio+Sessions /
 * Knowledge / Governance / Providers / Radar+Decision+Workflows /
 * Agents+Extensions+Activity) every FEATURE surface lives in its own
 * feature ViewModel. What remains here is the honest SHELL:
 *
 *  - the REPAIR ORDER §3A bootstrap state machine gate (phase label +
 *    failure message + retry) that MainAppScreen renders full-screen;
 *  - the workspace-scoped DISPLAY mirrors (activeProject +
 *    autonomyPolicy) synced from the authoritative WorkspaceRuntimeService
 *    on workspace switches;
 *  - the shell's own transient error + diagnostic-banner channels
 *    (the global snackbar + the app-shell banner);
 *  - the active-workspace / all-workspaces projections the shell's top
 *    bar composes on.
 *
 * (ADR-6 slice 1) the workspace management mutations + the files feature
 * → SettingsViewModel / FilesViewModel. (slice 2) the conversation
 * runtime + the session registry → StudioViewModel / SessionsViewModel.
 * (slice 3) knowledge, RAG, the semantic engine + the memory browser →
 * KnowledgeViewModel. (slice 4) the governance observatory + the human
 * approval surface → GovernanceViewModel. (slice 5) the provider &
 * resource control room → ProvidersViewModel. (slice 6) the decision
 * cockpit, the radar & evolution observatory and the workflow feature →
 * DecisionViewModel / RadarViewModel / WorkflowsViewModel.
 * (slice 7) the AGENT CATALOG (with the durable registry + the runtime
 * registration seam), the EXTENSIONS ECOSYSTEM (MCP + skills + plugins +
 * integrations) and the UNIFIED ACTIVITY FEED (with the telemetry port +
 * the studio bus's Started stake) → AgentsViewModel / ExtensionsViewModel
 * / ActivityViewModel.
 *
 * Two whole constructor dependencies this ViewModel carried were DEAD
 * (declared, never read): executeAgentTaskUseCase and telemetryService —
 * removed with this slice rather than moved.
 */
class MainViewModel(
    // Phase 2 — workspace runtime service for multi-workspace support
    // (the shell's workspace mirrors + the bootstrap state machine).
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    /** REPAIR ORDER §3A — observable bootstrap state machine. */
    private val bootstrapStateProvider: kotlinx.coroutines.flow.StateFlow<com.example.application.bootstrap.BootstrapState>? = null
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
        // (ADR-6 slices 2–7) the studio signal bus, the subsystem observers,
        // the agent catalog, the initial loads and every feature collector
        // moved to their feature ViewModels — each collects its own stake.
        observeWorkspace()
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
     * Phase 2 — Observes the active workspace and reacts to workspace
     * switches: updates the shell's DISPLAY mirrors (activeProject + the
     * persisted autonomy-policy column). The feature re-scopes (RAG index,
     * library listings, governance observatory, the activity feed …) live
     * in their own feature ViewModels.
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
                        // AUTONOMY DISPLAY SYNC (ADR-6 slice 1): the policy
                        // mutations live in SettingsViewModel (routed to the
                        // AUTHORITATIVE service). This collector mirrors the
                        // persisted column back into the shared display state
                        // so the Studio badge stays live without this
                        // ViewModel owning the mutation.
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

    /** Clears the shell's honest error channel (after the global snackbar). */
    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the transient diagnostic banner shown in the app shell. */
    fun dismissDiagnosticBanner() {
        _uiState.update { it.copy(diagnosticBanner = null) }
    }
}
