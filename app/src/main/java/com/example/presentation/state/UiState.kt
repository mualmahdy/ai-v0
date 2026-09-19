package com.example.presentation.state

import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.workflow.ExecutionMode

/**
 * One conversational turn in the Studio session transcript: the user prompt,
 * the agent that ran it, the final streamed answer, the event count and the
 * outcome. Turns accumulate for the lifetime of the ViewModel so the Studio
 * behaves like a real conversation console instead of a one-shot prompt box.
 *
 * DURABLE SESSIONS (report gap-closure): every turn is now ALSO persisted to
 * the workspace-scoped `chat_turns` table, so the transcript survives process
 * death, can be browsed and reopened from the session browser, and resumed as
 * conversation history.
 */
data class StudioTurn(
    val id: String,
    val prompt: String,
    val agentName: String,
    val agentRole: String,
    val answer: String,
    val eventCount: Int,
    val tokensConsumed: Int,
    val durationMs: Long,
    val isSuccessful: Boolean,
    /** The durable model-resource this turn was pinned to (user choice). */
    val modelResourceId: String? = null
)

/**
 * WORKFLOW BUILDER STATE (report gap: "workflow definition must be a
 * re-editable durable asset"): the authoring state lives in the ViewModel —
 * not in Compose `remember` — so saving, loading, editing and re-saving
 * library definitions is possible without losing the builder on navigation.
 */
data class WorkflowBuilderStep(
    val id: String,
    val description: String,
    val role: com.example.domain.core.agent.AgentRole,
    val dependencies: Set<String>,
    /** Canonical durable agent binding (report gap: synthetic agents). */
    val assignedAgentId: String? = null
)

data class WorkflowBuilderState(
    val name: String = "",
    val goal: String = "بناء ونشر وحدة معمارية متكاملة",
    val executionMode: ExecutionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
    val steps: List<WorkflowBuilderStep> = listOf(
        WorkflowBuilderStep("step_1_plan", "تحليل المتطلبات والتخطيط المعماري للوحدة", com.example.domain.core.agent.AgentRole.PLANNER, emptySet()),
        WorkflowBuilderStep("step_2_code", "كتابة الشيفرات ونماذج النطاق ومنافذ Ports", com.example.domain.core.agent.AgentRole.CODER, setOf("step_1_plan")),
        WorkflowBuilderStep("step_3_security", "التدقيق الأمني وفحص تنقيح البيانات والسياسات", com.example.domain.core.agent.AgentRole.SECURITY_GUARD, setOf("step_2_code"))
    ),
    /** Library definition being edited (null = new unsaved plan). */
    val editingDefinitionId: String? = null
)

data class ExecutionStepItem(
    val id: String,
    val title: String,
    val detail: String,
    val isSuccess: Boolean = false,
    val isError: Boolean = false,
    val isDegraded: Boolean = false
)

/**
 * ============================================================================
 * UiState — Phase 4 (generalized provider architecture)
 * ============================================================================
 *
 * The provider management fields use the new generalized types:
 *   - `generalizedProviders` — List<Provider>
 *   - `generalizedServices` — List<ProviderService>
 *   - `generalizedConfigurations` — List<ServiceConfiguration>
 *   - `discoveredOfferings` — List<ServiceOffering>
 *   - `materializedResources` — List<ResourceRecord>
 *
 * The legacy `providerConfigurations` field is REMOVED. The
 * `ModelsCapabilitiesScreen` has been replaced by `ProviderServiceManagerScreen`
 * (Phase 4 follow-up commit will add the screen).
 */
data class UiState(
    // ACTIVE-PROJECT MIRROR — REMOVED (UI Design Closure, phase B — D-02):
    // the old field was WORKSPACE data relabeled as a project (name = the
    // workspace's name), which fed the TopBar's conflated title/subtitle.
    // The REAL active-project mirror (the project row behind the
    // activeProjectId binding) now lives in ProjectsViewModel — the
    // projects feature's own state — and the TopBar subtitle / Home work
    // center compose on it (owner-VM composition). Nothing in the shell
    // reads this field anymore, so per the D-13 precedent (writerless /
    // readerless display state is dead state, not a latent feature) it is
    // DELETED rather than left unwired.
    // AGENT CATALOG — REMOVED (ADR-6 slice 7, Design Closure 2026
    // UI-redesign track): availableAgents / activeAgent now live in
    // AgentsViewModel's own AgentsUiState (the durable-registry authority
    // + the runtime registration seam). The Studio picker, the Tasks
    // builder's step-agent assignment and the Explorer's agents row read
    // the feature VM's own flow (owner-VM composition).
    // ------------------------------------------------------------------
    // ADR-6 SLICE 2 (Design Closure 2026 UI-redesign track): the ENTIRE
    // conversation-runtime block moved to StudioViewModel's own
    // StudioUiState. The execution-event DECISION projections that used to
    // stay here as display mirrors left with the decision feature in
    // SLICE 6: DecisionViewModel collects its own share of the studio
    // signal bus (see DecisionViewModel.observeStudioSignals).
    // ------------------------------------------------------------------
    // SHELL DEGRADATION MIRROR + SHELL BANNER — REMOVED (ADR-6 slice 8,
    // Design Closure 2026 UI-redesign track — the track's final cleanup,
    // D-13): isDegraded / degradedReason had NO WRITER since the slice-2
    // extraction (the only reader styled a banner whose degradation leg
    // was permanently false), and diagnosticBanner itself had NO WRITER
    // either (only its dismiss function existed — the shell banner block
    // in MainAppScreen could never render anything). Degradation display
    // lives where it is REAL: the Studio feature's own execution-
    // degradation banner, the workflow report's DEGRADED outcome row and
    // the durable execution rows. If a shell-scope diagnostic ever gains
    // a real source, it gets a real channel — never a writerless hook.
    // ------------------------------------------------------------------
    /**
     * ADR-6 SLICE 6 — the session network-policy DISPLAY MIRROR left the
     * shared UiState with the decision feature (its only consumer): the
     * simulation input mirror now lives in DecisionViewModel (synced from
     * the studio signal bus), and the Settings surface reads the policy
     * directly from StudioViewModel (its owner, value + mutation).
     */
    val autonomyPolicy: AutonomyPolicy = AutonomyPolicy.SUPERVISED,
    /**
     * REPAIR ORDER §3A + GAP-23 (Design Closure 2026) — the bootstrap
     * state machine phase label (BOOTSTRAPPING / WORKSPACE_READY /
     * PROJECT_RESOLVED / CONTEXT_READY / READY / FAILED) and its explicit
     * failure message. The MainAppScreen startup gate RENDERS the failure
     * message as a full-screen honest gate (with retry) — previously the
     * message was collected here but never displayed, so a failed bootstrap
     * still showed a fully-rendered app where every project-dependent action
     * popped an error snackbar.
     */
    val bootstrapPhase: String = "BOOTSTRAPPING",
    val bootstrapFailureMessage: String? = null,
    // Tasks & Workflows (GAP-23: the write-only UiState.activeTasks field was
    // removed — TasksScreen reads the LIVE board from TasksViewModel →
    // TaskBoardService → TaskDao, which is the single task-list authority.)
    // WORKFLOW FEATURE — REMOVED (ADR-6 slice 6, Design Closure 2026
    // UI-redesign track): workflowReport / isExecutingWorkflow /
    // workflowBuilder / workflowLibrary / resumableWorkflows now live in
    // WorkflowsViewModel's own WorkflowsUiState. The builder state TYPES
    // (WorkflowBuilderStep / WorkflowBuilderState) stay in this package as
    // the feature's authored-asset state (the StudioTurn precedent, slice 2);
    // TasksScreen composes on the workflows feature VM (its owner).

    // Decision Intelligence (CBR-MDP) — REMOVED (ADR-6 slice 6, Design
    // Closure 2026 UI-redesign track): latestDecision / caseBaseList /
    // isSimulatingDecision / decisionTaskComplexity / decisionUncertainty
    // (and the networkPolicy display mirror) now live in DecisionViewModel's
    // own DecisionUiState. The decision feature COLLECTS its share of the
    // studio signal bus itself (decision mirrors + policy re-simulation).

    // Intelligence Radar & Evolution — REMOVED (ADR-6 slice 6, Design
    // Closure 2026 UI-redesign track): radarItems / evolutionCandidates /
    // isRadarRefreshing now live in RadarViewModel's own RadarUiState. The
    // radar screen composes on the feature VM (its owner).

    // GOVERNANCE OBSERVATORY — REMOVED (ADR-6 slice 4, Design Closure 2026
    // UI-redesign track): radarCapabilityStatuses / radarRecommendations /
    // radarChanges / workspaceBudgetStatus / costLedgerRecent /
    // workspaceTokensConsumed / budgetAllocationInputUsd /
    // isSavingBudgetAllocation / pendingApprovals / measurementHealth now
    // live in GovernanceViewModel's own GovernanceUiState. The governance
    // screen renders from the feature VM (its owner).

    // EXTENSIONS & ECOSYSTEM — REMOVED (ADR-6 slice 7, Design Closure 2026
    // UI-redesign track): skills / plugins / mcpServers / integrations now
    // live in ExtensionsViewModel's own ExtensionsUiState (the
    // Room-backed extension control room). The extensions screen composes
    // on the feature VM and the Explorer's tools row reads its own flow.

    // Provider & Resource Control Plane — REMOVED (ADR-6 slice 5, Design
    // Closure 2026 UI-redesign track): generalizedProviders /
    // generalizedServices / generalizedConfigurations / discoveredOfferings /
    // materializedResources / isDiscoveringModels / isTestingProvider /
    // testingProviderId / the "Connect Provider" wizard fields
    // (isConnectWizardOpen / wizardRunning / wizardStep / wizardStepLabel /
    // wizardResult / wizardResultIsSuccess) and the credential-dialog fields
    // (credentialDialogServiceId / credentialDialogServiceName /
    // credentialDialogAuthAlias / credentialInput / isSavingCredential) now
    // live in ProvidersViewModel's own ProvidersUiState. The providers
    // screen, the Dashboard/Studio/Explorer provider reads and the top-bar
    // status chip read the feature VM's own flow (owner-VM composition).

    // Memory & Knowledge RAG — REMOVED (ADR-6 slice 3, Design Closure 2026
    // UI-redesign track): memoryQuery / retrievedMemories / allMemories /
    // isSearchingMemory / newMemoryContent / semanticModelReady /
    // isProvisioningSemanticModel / knowledgeDocuments / assembledRagContext /
    // newDocTitle / newDocContent now live in KnowledgeViewModel's own
    // KnowledgeUiState. The Explorer/Settings surfaces read the knowledge
    // feature's own flow (value+lambda / owner-VM composition).

    // Files feature — REMOVED (ADR-6 slice 1, Design Closure 2026
    // UI-redesign track): workspaceFiles / selectedFileContent /
    // selectedFilePath / isFileLoading now live in FilesViewModel's own
    // FilesUiState. The explorer count reads the FilesViewModel flow.

    // Measurement-health snapshot — REMOVED (ADR-6 slice 4): the GAP-24
    // "صحة القياس" card now reads GovernanceViewModel's own state (the
    // fetch runs with its refreshGovernance).

    // Error notification
    val errorMessage: String? = null
)
