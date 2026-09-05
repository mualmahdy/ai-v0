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
import com.example.domain.core.rag.AssembledRagContext
import com.example.domain.core.rag.KnowledgeDocument
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.storage.ProjectMetadata
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.workflow.WorkflowExecutionReport
import com.example.domain.core.workspace.ResourceGraph

enum class ActiveNavigationTab(val displayName: String, val iconName: String) {
    STUDIO("المشغل الذكي (Studio)", "ic_studio"),
    TASKS_WORKFLOWS("المهام وخطط العمل", "ic_workflow"),
    DECISION_INTELLIGENCE("ذكاء القرار (CBR-MDP)", "ic_decision"),
    RADAR_EVOLUTION("رادار التطور والقدرات", "ic_radar"),
    EXTENSIONS("الملحقات وMCP والمهارات", "ic_extensions"),
    MODELS_CAPABILITIES("النماذج والمزودين", "ic_models"),
    KNOWLEDGE_RAG("المعرفة والوثائق (RAG)", "ic_knowledge"),
    FILES("ملفات مساحة العمل", "ic_files")
}

data class ExecutionStepItem(
    val id: String,
    val title: String,
    val detail: String,
    val isRunning: Boolean = false,
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
    val activeTab: ActiveNavigationTab = ActiveNavigationTab.STUDIO,
    val activeProject: ProjectMetadata? = null,
    val activeAgent: AgentDefinition? = null,
    val availableAgents: List<AgentDefinition> = emptyList(),
    val promptInput: String = "",
    val isExecuting: Boolean = false,
    val executionLog: List<ExecutionEvent> = emptyList(),
    val streamText: String = "",
    val isDegraded: Boolean = false,
    val degradedReason: DegradedReason? = null,
    val diagnosticBanner: String? = null,
    val currentTokensConsumed: Int = 0,
    val sessionTotalTokens: Int = 0,
    val remainingBudget: Int = 30000,
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
    val autonomyPolicy: AutonomyPolicy = AutonomyPolicy.SUPERVISED,
    val resourceGraph: ResourceGraph = ResourceGraph(),

    // Tasks & Workflows
    val activeTasks: List<TaskDefinition> = emptyList(),
    val workflowReport: WorkflowExecutionReport? = null,
    val isExecutingWorkflow: Boolean = false,

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
    val isAddProviderDialogOpen: Boolean = false,

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
