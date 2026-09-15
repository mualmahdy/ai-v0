package com.example.presentation.state

import com.example.domain.core.DegradedReason
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.evolution.EvolutionCandidate
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.extension.IntegrationDescriptor
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.PluginManifest
import com.example.domain.core.extension.SkillManifest
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.radar.RadarItem
import com.example.domain.core.storage.ProjectMetadata
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.WorkflowExecutionReport

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
    val activeProject: ProjectMetadata? = null,
    val activeAgent: AgentDefinition? = null,
    val availableAgents: List<AgentDefinition> = emptyList(),
    // ------------------------------------------------------------------
    // ADR-6 SLICE 2 (Design Closure 2026 UI-redesign track): the ENTIRE
    // conversation-runtime block (promptInput, isExecuting, executionLog,
    // streamText, studioSession, sessionTurnStartMs, chatMode,
    // activeSessionId, sessions, isSessionBrowserOpen,
    // selectedModelResourceId, selectedModelDisplayName, isDegraded,
    // degradedReason, currentTokensConsumed, sessionTotalTokens,
    // remainingBudget) moved to StudioViewModel's own StudioUiState. The
    // execution-event decision projections (latestDecision,
    // decisionUncertainty, caseBaseList) stay here as DISPLAY mirrors fed
    // by the studio signal bus (see MainViewModel.observeStudioSignals).
    // ------------------------------------------------------------------
    val isDegraded: Boolean = false,
    val degradedReason: DegradedReason? = null,
    val diagnosticBanner: String? = null,
    /**
     * ADR-6 slice 2 — DISPLAY MIRROR ONLY: the SESSION network policy is
     * owned by StudioViewModel (an execution-time input of the
     * conversation); this mirror is synced from the studio signal bus so
     * the decision preview (simulateDecision) and the governance radar
     * snapshot keep reading ONE shared value. The persisted WORKSPACE
     * policy (egress authority) lives on the workspace row — a different,
     * unrelated field.
     */
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
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
    val workflowReport: WorkflowExecutionReport? = null,
    val isExecutingWorkflow: Boolean = false,
    // WORKFLOW LIBRARY (report gap: durable, re-editable authored assets).
    val workflowBuilder: WorkflowBuilderState = WorkflowBuilderState(),
    val workflowLibrary: List<com.example.domain.core.workflow.WorkflowDefinitionSummary> = emptyList(),
    val resumableWorkflows: List<com.example.domain.core.workflow.ResumableWorkflow> = emptyList(),

    // Decision Intelligence (CBR-MDP)
    val latestDecision: DecisionResult? = null,
    val caseBaseList: List<DecisionCase> = emptyList(),
    val isSimulatingDecision: Boolean = false,
    val decisionTaskComplexity: Float = 0.6f,
    val decisionUncertainty: Float = 0.2f,

    // Intelligence Radar & Evolution Pipeline (GAP-23: isRadarRefreshing was
    // previously a NO-WRITER field — a spinner that could never appear.
    // refreshRadar() now sets it honestly around the pipeline call.)
    val radarItems: List<RadarItem> = emptyList(),
    val evolutionCandidates: List<EvolutionCandidate> = emptyList(),
    val isRadarRefreshing: Boolean = false,

    // GOVERNANCE OBSERVATORY — REMOVED (ADR-6 slice 4, Design Closure 2026
    // UI-redesign track): radarCapabilityStatuses / radarRecommendations /
    // radarChanges / workspaceBudgetStatus / costLedgerRecent /
    // workspaceTokensConsumed / budgetAllocationInputUsd /
    // isSavingBudgetAllocation / pendingApprovals / measurementHealth now
    // live in GovernanceViewModel's own GovernanceUiState. The governance
    // screen renders from the feature VM (its owner).

    // Extensions & Ecosystem
    val skills: List<SkillManifest> = emptyList(),
    val plugins: List<PluginManifest> = emptyList(),
    val mcpServers: List<McpServerDescriptor> = emptyList(),
    val integrations: List<IntegrationDescriptor> = emptyList(),

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
