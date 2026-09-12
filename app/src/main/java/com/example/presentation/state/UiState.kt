package com.example.presentation.state

import com.example.domain.core.DegradedReason
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.evolution.EvolutionCandidate
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.extension.IntegrationDescriptor
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.PluginManifest
import com.example.domain.core.extension.SkillManifest
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.radar.RadarItem
import com.example.domain.core.radar.CapabilityChangeRecord
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.domain.core.budget.BudgetStatus
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.radar.RadarSnapshot
import com.example.domain.core.rag.AssembledRagContext
import com.example.domain.core.rag.KnowledgeDocument
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.storage.ProjectMetadata
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workspace.ResourceGraph

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
    val promptInput: String = "",
    val isExecuting: Boolean = false,
    val executionLog: List<ExecutionEvent> = emptyList(),
    val streamText: String = "",
    // Session transcript (Studio as a real conversation console).
    val studioSession: List<StudioTurn> = emptyList(),
    val sessionTurnStartMs: Long = 0L,
    // ------------------------------------------------------------------
    // DURABLE SESSIONS + QUICK CHAT + MODEL PICKER (report gap-closure):
    // the conversation mode (agent-independent Quick Chat vs canonical
    // agent), the active durable session id, the live session browser list,
    // and the user-facing exact model selection.
    // ------------------------------------------------------------------
    val chatMode: ChatMode = ChatMode.QUICK_CHAT,
    val activeSessionId: String? = null,
    val sessions: List<ConversationSession> = emptyList(),
    val isSessionBrowserOpen: Boolean = false,
    val selectedModelResourceId: String? = null,
    val selectedModelDisplayName: String? = null,
    val isDegraded: Boolean = false,
    val degradedReason: DegradedReason? = null,
    val diagnosticBanner: String? = null,
    val currentTokensConsumed: Int = 0,
    val sessionTotalTokens: Int = 0,
    val remainingBudget: Int = 30000,
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
    val autonomyPolicy: AutonomyPolicy = AutonomyPolicy.SUPERVISED,
    /**
     * REPAIR ORDER §3A — the bootstrap state machine phase label
     * (BOOTSTRAPPING / WORKSPACE_READY / PROJECT_RESOLVED / CONTEXT_READY /
     * READY / FAILED) and its explicit failure message, if any. UI gates
     * project-dependent features on READY and renders honest failure states.
     */
    val bootstrapPhase: String = "BOOTSTRAPPING",
    val bootstrapFailureMessage: String? = null,
    /**
     * REPAIR ORDER §5 — projects of the active workspace (authoritative
     * list from ProjectRuntimeService; UI project pickers render THIS).
     */
    val resourceGraph: ResourceGraph = ResourceGraph(),

    // Tasks & Workflows
    val activeTasks: List<TaskDefinition> = emptyList(),
    val workflowReport: WorkflowExecutionReport? = null,
    val isExecutingWorkflow: Boolean = false,
    // WORKFLOW LIBRARY (report gap: durable, re-editable authored assets).
    val workflowBuilder: WorkflowBuilderState = WorkflowBuilderState(),
    val workflowLibrary: List<com.example.application.workflow.WorkflowLibraryService.WorkflowDefinitionSummary> = emptyList(),
    val resumableWorkflows: List<com.example.application.workflow.ResumableWorkflow> = emptyList(),

    // Decision Intelligence (CBR-MDP)
    val latestDecision: DecisionResult? = null,
    val caseBaseList: List<DecisionCase> = emptyList(),
    val isSimulatingDecision: Boolean = false,
    val decisionTaskComplexity: Float = 0.6f,
    val decisionUncertainty: Float = 0.2f,

    // Intelligence Radar & Evolution Pipeline
    val radarItems: List<RadarItem> = emptyList(),
    val evolutionCandidates: List<EvolutionCandidate> = emptyList(),
    val isRadarRefreshing: Boolean = false,

    // GOVERNANCE PHASE — Capability Radar + Economic Budget observatory.
    // Every field below is backend-truth (Room-backed flows); nothing here
    // is fabricated or UI-assumed.
    val radarCapabilityStatuses: List<RadarCapabilityStatus> = emptyList(),
    val radarRecommendations: List<RadarRecommendation> = emptyList(),
    val radarChanges: List<CapabilityChangeRecord> = emptyList(),
    val radarSnapshotTakenAtMs: Long? = null,
    val workspaceBudgetStatus: BudgetStatus? = null,
    val costLedgerRecent: List<UsageCostRecord> = emptyList(),
    val workspaceTokensConsumed: Long = 0L,
    val budgetAllocationInputUsd: String = "",
    val isSavingBudgetAllocation: Boolean = false,

    // Extensions & Ecosystem
    val skills: List<SkillManifest> = emptyList(),
    val plugins: List<PluginManifest> = emptyList(),
    val mcpServers: List<McpServerDescriptor> = emptyList(),
    val integrations: List<IntegrationDescriptor> = emptyList(),

    // Phase 4 — Generalized Provider Architecture
    val generalizedProviders: List<Provider> = emptyList(),
    val generalizedServices: List<ProviderService> = emptyList(),
    val generalizedConfigurations: List<ServiceConfiguration> = emptyList(),
    val discoveredOfferings: List<ServiceOffering> = emptyList(),
    val materializedResources: List<ResourceRecord> = emptyList(),
    val isDiscoveringModels: Boolean = false,
    val isTestingProvider: Boolean = false,
    val testingProviderId: String? = null,

    // GAP-02 (Design Closure 2026, ADR-2): the HUMAN APPROVAL SURFACE —
    // pending consent requests rendered by the governance observatory
    // (previously the consent loop was a dead end with no reachable UI).
    val pendingApprovals: List<com.example.domain.ports.governed.HumanApprovalRequest> = emptyList(),

    // "Connect Provider" wizard — the guided full-chain path that ends with a
    // usable ENABLED resource (fix: user could add a provider but never use it).
    val isConnectWizardOpen: Boolean = false,
    val wizardRunning: Boolean = false,
    val wizardStep: Int = 0,
    val wizardStepLabel: String? = null,
    val wizardResult: String? = null,
    val wizardResultIsSuccess: Boolean = true,

    // FIX F-4 (audit c03919d): credential input dialog state — previously the
    // dialog flag was set with no reader; now the ProviderServiceManager screen
    // renders a real AlertDialog bound to these fields.
    val credentialDialogServiceId: String? = null,
    val credentialDialogServiceName: String = "",
    val credentialDialogAuthAlias: String? = null,
    val credentialInput: String = "",
    val isSavingCredential: Boolean = false,

    // Memory & Knowledge RAG
    val memoryQuery: String = "",
    val retrievedMemories: List<ScoredMemoryRecord> = emptyList(),
    val allMemories: List<MemoryEntry> = emptyList(),
    val isSearchingMemory: Boolean = false,
    val newMemoryContent: String = "",
    // TRUE when the on-device ONNX semantic embedding model is provisioned.
    val semanticModelReady: Boolean = false,
    val isProvisioningSemanticModel: Boolean = false,
    val knowledgeDocuments: List<KnowledgeDocument> = emptyList(),
    val assembledRagContext: AssembledRagContext? = null,
    val newDocTitle: String = "",
    val newDocContent: String = "",

    // Files Tab State
    val workspaceFiles: List<WorkspaceFileEntry> = emptyList(),
    val selectedFileContent: String? = null,
    val selectedFilePath: String? = null,
    val isFileLoading: Boolean = false,

    // Legacy capabilities
    val capabilities: List<CapabilityDescriptor> = emptyList(),

    // Error notification
    val errorMessage: String? = null
)
