package com.example.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionService
import com.example.application.extension.ExtensionManager
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.observation.ObservationService
import com.example.application.outcome.OutcomeService
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.ProviderRegistryService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.KnowledgePersistenceService
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.resource.DurableResourceRegistryService
import com.example.application.security.SecurityGuardService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.ports.provider.ProviderRepositoryPort
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.llm.discovery.GeminiModelDiscoveryAdapter
import com.example.infrastructure.llm.discovery.OpenAiCompatibleDiscoveryAdapter
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.provider.ProviderAdapterFactory
import com.example.infrastructure.provider.RoomProviderRepositoryAdapter
import com.example.infrastructure.radar.GitHubReleasesRadarSource
import com.example.infrastructure.radar.RssFeedRadarSource
import com.example.infrastructure.search.MultiSourceSearchAdapter
import com.example.infrastructure.security.EncryptedSecretStorageAdapter
import com.example.infrastructure.skills.CleanArchitectureScaffolderSkill
import com.example.infrastructure.skills.SecurityAuditorSkill
import com.example.infrastructure.storage.SandboxWorkspaceStorageAdapter
import com.example.infrastructure.tools.FileSystemTool
import com.example.infrastructure.tools.SafeDiagnosticsTool
import com.example.presentation.viewmodel.MainViewModel
import java.io.File

/**
 * Dependency Injection Container / Composition Root for the Clean Architecture Orchestrator.
 * Connects real Room persistence, genuine MCP protocols, live integrations, multi-source search,
 * executable skills, CBR-MDP decision intelligence, and the dynamic Provider Control Plane.
 */
class AppContainer(context: Context) {
    val appContext = context.applicationContext

    // Persistence Layer (Room Database)
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    // Secure Credential Storage
    val secureCredentialStorage: SecureCredentialStoragePort by lazy {
        EncryptedSecretStorageAdapter(appContext)
    }

    // Persistent Provider Repository
    val providerRepository: ProviderRepositoryPort by lazy {
        RoomProviderRepositoryAdapter(
            providerDao = database.providerConfigDao(),
            secureCredentialStorage = secureCredentialStorage
        )
    }

    // Infrastructure Adapters
    val workspaceStorage: SandboxWorkspaceStorageAdapter by lazy {
        SandboxWorkspaceStorageAdapter(
            context = appContext,
            projectDao = database.projectDao(),
            sessionDao = database.sessionDao()
        )
    }

    val workspaceRuntimeService: WorkspaceRuntimeService by lazy {
        WorkspaceRuntimeService(workspaceDao = database.workspaceDao())
    }

    // --- RAG persistence ---
    val knowledgePersistenceService: KnowledgePersistenceService by lazy {
        KnowledgePersistenceService(
            documentDao = database.knowledgeDocumentDao(),
            chunkDao = database.documentChunkDao()
        )
    }

    // --- Local Embedding Adapter (used as the in-process fallback for RAG) ---
    val defaultEmbeddingAdapter: LocalDeterministicEmbeddingAdapter by lazy {
        LocalDeterministicEmbeddingAdapter(providerId = "local_embedding_engine", dimension = 128)
    }

    val memoryVectorStore: RoomVectorStoreAdapter by lazy {
        RoomVectorStoreAdapter(
            memoryDao = database.memoryDao(),
            embeddingProvider = defaultEmbeddingAdapter
        )
    }

    val securityGuardService: SecurityGuardService by lazy { SecurityGuardService() }

    // --- Concrete Tools (in-app extensions) ---
    val fileSystemTool: FileSystemTool by lazy {
        FileSystemTool(storagePort = workspaceStorage, defaultProjectId = 1L)
    }

    val safeDiagnosticsTool: SafeDiagnosticsTool by lazy {
        val sandboxDir = File(appContext.filesDir, "workspaces").apply { if (!exists()) mkdirs() }
        SafeDiagnosticsTool(sandboxDir = sandboxDir)
    }

    // --- Executable Skills ---
    val cleanArchitectureSkill by lazy {
        CleanArchitectureScaffolderSkill(storagePort = workspaceStorage, defaultProjectId = 1L)
    }

    val securityAuditorSkill by lazy { SecurityAuditorSkill() }

    // ========================================================================
    // Phase 4 — Generalized Provider Architecture (single authoritative path)
    // ========================================================================

    val geminiBootstrap: GeminiBootstrap by lazy {
        GeminiBootstrap(appContext).also { it.ensureInitialized() }
    }

    val protocolAdapterFactory: ProtocolAdapterFactory by lazy {
        ProtocolAdapterFactory(
            workspaceStoragePort = workspaceStorage,
            defaultProjectId = 1L,
            geminiBootstrap = geminiBootstrap
        )
    }

    val resourceValidatorRegistry by lazy { defaultResourceValidatorRegistry() }

    val generalizedProviderRepository: ProviderRepository by lazy {
        RoomProviderRepository(database.providerDao())
    }
    val generalizedServiceRepository: ProviderServiceRepository by lazy {
        RoomProviderServiceRepository(database.providerServiceDao())
    }
    val generalizedConfigurationRepository: ServiceConfigurationRepository by lazy {
        RoomServiceConfigurationRepository(
            dao = database.serviceConfigurationDao(),
            serviceDao = database.providerServiceDao()
        )
    }
    val generalizedHealthRepository: ServiceHealthRepository by lazy {
        RoomServiceHealthRepository(database.serviceHealthRecordDao())
    }
    val generalizedOfferingRepository: OfferingRepository by lazy {
        RoomOfferingRepository(database.serviceOfferingDao())
    }
    val generalizedUserPreferenceRepository: UserPreferenceRepository by lazy {
        RoomUserPreferenceRepository(database.userResourcePreferenceDao())
    }
    val generalizedResourceRecordRepository: ResourceRecordRepository by lazy {
        RoomResourceRecordRepository(database.resourceRecordDao())
    }

    /**
     * Single authoritative resource registry. `DurableResourceRegistryService` is
     * the in-memory facade backed by `ResourceRecordRepository` (Room). The
     * `ComponentRegistry` references this same instance so there is one source of
     * truth for resource identity, lifecycle, and health.
     */
    val durableResourceRegistryService: DurableResourceRegistryService by lazy {
        DurableResourceRegistryService(generalizedResourceRecordRepository)
    }

    /**
     * ComponentRegistry — Phase 4: contains only in-app runtime extensions
     * (tools, agents, memory repository). It does NOT register LLM/Search/
     * Embedding providers — those are now `ResourceRecord`s authored by the
     * `ProviderControlPlaneService` via the `ResourceRecordRepository`.
     */
    val componentRegistry: ComponentRegistry by lazy {
        ComponentRegistry(durableResourceRegistryService).apply {
            registerMemoryRepository(memoryVectorStore)
            registerTool(fileSystemTool)
            registerTool(safeDiagnosticsTool)

            registerAgent(
                com.example.domain.core.agent.AgentDefinition(
                    identity = com.example.domain.core.agent.AgentIdentity(
                        id = com.example.domain.core.agent.AgentId("agent_general"),
                        name = "المساعد الشامل",
                        role = com.example.domain.core.agent.AgentRole.GENERAL_ASSISTANT,
                        description = "المساعد العام للنظام",
                        systemPrompt = com.example.domain.core.agent.AgentRole.GENERAL_ASSISTANT.defaultSystemPrompt
                    ),
                    allowedCapabilities = setOf(
                        com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                        com.example.domain.core.capability.CapabilityType.STREAMING,
                        com.example.domain.core.capability.CapabilityType.SEARCH,
                        com.example.domain.core.capability.CapabilityType.MEMORY_RETRIEVAL
                    ),
                    budget = com.example.domain.core.agent.AgentBudget()
                )
            )
            registerAgent(
                com.example.domain.core.agent.AgentDefinition(
                    identity = com.example.domain.core.agent.AgentIdentity(
                        id = com.example.domain.core.agent.AgentId("agent_coder"),
                        name = "مهندس البرمجيات",
                        role = com.example.domain.core.agent.AgentRole.CODER,
                        description = "متخصص في بناء وتطوير وهندسة الكود",
                        systemPrompt = com.example.domain.core.agent.AgentRole.CODER.defaultSystemPrompt
                    ),
                    allowedCapabilities = setOf(
                        com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                        com.example.domain.core.capability.CapabilityType.TOOL_EXECUTION,
                        com.example.domain.core.capability.CapabilityType.FILE_STORAGE,
                        com.example.domain.core.capability.CapabilityType.MEMORY_RETRIEVAL
                    ),
                    budget = com.example.domain.core.agent.AgentBudget()
                )
            )
            registerAgent(
                com.example.domain.core.agent.AgentDefinition(
                    identity = com.example.domain.core.agent.AgentIdentity(
                        id = com.example.domain.core.agent.AgentId("agent_researcher"),
                        name = "الباحث المعرفي",
                        role = com.example.domain.core.agent.AgentRole.RESEARCHER,
                        description = "متخصص في استرجاع المعرفة والبحث الموثوق",
                        systemPrompt = com.example.domain.core.agent.AgentRole.RESEARCHER.defaultSystemPrompt
                    ),
                    allowedCapabilities = setOf(
                        com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                        com.example.domain.core.capability.CapabilityType.SEARCH,
                        com.example.domain.core.capability.CapabilityType.MEMORY_RETRIEVAL
                    ),
                    budget = com.example.domain.core.agent.AgentBudget()
                )
            )
        }
    }

    /**
     * Authoritative control plane service. Operates on
     * Provider → ProviderService → ServiceConfiguration and resolves
     * protocol-specific adapters through ProtocolAdapterFactory.
     */
    val providerControlPlaneService: ProviderControlPlaneService by lazy {
        ProviderControlPlaneService(
            providerRepository = generalizedProviderRepository,
            serviceRepository = generalizedServiceRepository,
            configurationRepository = generalizedConfigurationRepository,
            healthRepository = generalizedHealthRepository,
            offeringRepository = generalizedOfferingRepository,
            resourceRecordRepository = generalizedResourceRecordRepository,
            userPreferenceRepository = generalizedUserPreferenceRepository,
            secureCredentialStorage = secureCredentialStorage,
            adapterFactory = protocolAdapterFactory,
            validatorRegistry = resourceValidatorRegistry
        )
    }

    // --- CBR-MDP Decision Intelligence ---
    val cbrMdpEngine: CbrMdpEngine by lazy {
        val persistentCaseBase = CaseBase(decisionCaseDao = database.decisionCaseDao())
        CbrMdpEngine(caseBase = persistentCaseBase)
    }

    /**
     * Legacy provider registry service — kept ONLY for the model discovery UI
     * surface in the existing `ModelsCapabilitiesScreen`. This will be removed in
     * a follow-up commit when the new `ProviderServiceManagerScreen` replaces it.
     *
     * It does NOT feed the runtime decision/execution path. Runtime resource
     * selection uses `ResourceRegistryService` via `ResourceCapabilityGraph`.
     */
    val providerRegistryService: ProviderRegistryService by lazy {
        ProviderRegistryService()
    }

    // --- Extensibility Engine ---
    val extensionManager: ExtensionManager by lazy {
        ExtensionManager(
            componentRegistry = componentRegistry,
            mcpClient = McpClient(),
            integrationGateway = IntegrationGateway(),
            extensionConfigDao = database.extensionConfigDao(),
            executableSkills = listOf(cleanArchitectureSkill, securityAuditorSkill)
        )
    }

    // --- Intelligence Radar ---
    val intelligenceRadarPipeline: IntelligenceRadarPipeline by lazy {
        IntelligenceRadarPipeline(
            radarSources = listOf(GitHubReleasesRadarSource(), RssFeedRadarSource()),
            radarItemDao = database.radarItemDao(),
            evolutionCandidateDao = database.evolutionCandidateDao()
        )
    }

    /**
     * RAG Pipeline — Phase 4: routed through the resource pipeline.
     * The embedding adapter is resolved via `ResourceRecordRepository` /
     * `DurableResourceRegistryService` (no direct injection of a single
     * concrete adapter). When no embedding ResourceRecord is registered (e.g.
     * first run before user materializes one), the local in-process fallback
     * is used explicitly so RAG keeps working.
     */
    val ragPipelineService: RagPipelineService by lazy {
        RagPipelineService(
            resourceRegistry = durableResourceRegistryService,
            runtimeAdapterResolver = componentRegistry.runtimeAdapterResolver,
            fallbackEmbeddingProvider = defaultEmbeddingAdapter,
            persistenceService = knowledgePersistenceService,
            workspaceIdProvider = { workspaceRuntimeService.requireActiveWorkspaceId() }
        )
    }

    // --- Decision & Execution ---
    val decisionService: DecisionService by lazy {
        DecisionService(
            cbrMdpEngine = cbrMdpEngine,
            resourceCapabilityGraph = componentRegistry.resourceCapabilityGraph,
            securityGuard = securityGuardService,
            userPreferenceRepository = generalizedUserPreferenceRepository
        )
    }

    val executionService: ExecutionService by lazy {
        ExecutionService(
            runtimeAdapterResolver = componentRegistry.runtimeAdapterResolver,
            resourceRegistry = durableResourceRegistryService,
            securityGuard = securityGuardService,
            extensionManager = extensionManager
        )
    }

    val observationService: ObservationService by lazy { ObservationService() }
    val outcomeService: OutcomeService by lazy { OutcomeService() }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            registry = componentRegistry,
            securityGuard = securityGuardService,
            decisionService = decisionService,
            executionService = executionService,
            observationService = observationService,
            outcomeService = outcomeService,
            taskDao = database.taskDao()
        )
    }

    // Workflow Engine
    val workflowEngine: WorkflowEngine by lazy {
        WorkflowEngine(orchestrator = agentOrchestrator)
    }

    // Use Cases
    val executeAgentTaskUseCase: ExecuteAgentTaskUseCase by lazy {
        ExecuteAgentTaskUseCase(agentOrchestrator)
    }

    val executeWorkflowUseCase: ExecuteWorkflowUseCase by lazy {
        ExecuteWorkflowUseCase(workflowEngine)
    }

    val manageMemoryUseCase: ManageMemoryUseCase by lazy {
        ManageMemoryUseCase(memoryRepository = memoryVectorStore)
    }

    val manageWorkspaceFilesUseCase: ManageWorkspaceFilesUseCase by lazy {
        ManageWorkspaceFilesUseCase(workspaceStorage)
    }
}

class MainViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                executeAgentTaskUseCase = appContainer.executeAgentTaskUseCase,
                executeWorkflowUseCase = appContainer.executeWorkflowUseCase,
                manageMemoryUseCase = appContainer.manageMemoryUseCase,
                manageWorkspaceFilesUseCase = appContainer.manageWorkspaceFilesUseCase,
                componentRegistry = appContainer.componentRegistry,
                cbrMdpEngine = appContainer.cbrMdpEngine,
                providerRegistryService = appContainer.providerRegistryService,
                extensionManager = appContainer.extensionManager,
                intelligenceRadarPipeline = appContainer.intelligenceRadarPipeline,
                ragPipelineService = appContainer.ragPipelineService,
                providerControlPlaneService = appContainer.providerControlPlaneService,
                workspaceRuntimeService = appContainer.workspaceRuntimeService
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
