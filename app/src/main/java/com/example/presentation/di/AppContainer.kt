package com.example.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.application.extension.ExtensionManager
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.ProviderRegistryService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.KnowledgePersistenceService
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
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

    // Phase 2 — WorkspaceRuntimeService: turns Workspace from a Domain-only model
    // into a first-class runtime citizen with persistence and multi-workspace switching.
    val workspaceRuntimeService: WorkspaceRuntimeService by lazy {
        WorkspaceRuntimeService(workspaceDao = database.workspaceDao())
    }

    // Phase 2 — KnowledgePersistenceService: persists RAG documents+chunks to Room
    // so the knowledge base survives app restart.
    val knowledgePersistenceService: KnowledgePersistenceService by lazy {
        KnowledgePersistenceService(
            documentDao = database.knowledgeDocumentDao(),
            chunkDao = database.documentChunkDao()
        )
    }

    val defaultEmbeddingAdapter: LocalDeterministicEmbeddingAdapter by lazy {
        LocalDeterministicEmbeddingAdapter(providerId = "local_embedding_engine", dimension = 128)
    }

    val memoryVectorStore: RoomVectorStoreAdapter by lazy {
        RoomVectorStoreAdapter(
            memoryDao = database.memoryDao(),
            embeddingProvider = defaultEmbeddingAdapter
        )
    }

    val geminiLlmAdapter: GeminiLlmAdapter by lazy {
        GeminiLlmAdapter()
    }

    val searchAdapter: MultiSourceSearchAdapter by lazy {
        MultiSourceSearchAdapter(
            tavilyApiKeyProvider = {
                kotlinx.coroutines.runBlocking { providerRepository.getSecretForProvider("tavily_search") }
            },
            workspaceStoragePort = workspaceStorage,
            defaultProjectId = 1L
        )
    }

    val securityGuardService: SecurityGuardService by lazy {
        SecurityGuardService()
    }

    // Concrete Tools
    val fileSystemTool: FileSystemTool by lazy {
        FileSystemTool(storagePort = workspaceStorage, defaultProjectId = 1L)
    }

    val safeDiagnosticsTool: SafeDiagnosticsTool by lazy {
        val sandboxDir = File(appContext.filesDir, "workspaces").apply { if (!exists()) mkdirs() }
        SafeDiagnosticsTool(sandboxDir = sandboxDir)
    }

    // Executable Skills
    val cleanArchitectureSkill by lazy {
        CleanArchitectureScaffolderSkill(storagePort = workspaceStorage, defaultProjectId = 1L)
    }

    val securityAuditorSkill by lazy {
        SecurityAuditorSkill()
    }

    // Component & Tools Registry
    val componentRegistry: ComponentRegistry by lazy {
        ComponentRegistry().apply {
            registerLlmProvider(geminiLlmAdapter, isDefault = true)
            registerSearchProvider(searchAdapter, isDefault = true)
            registerEmbeddingProvider(defaultEmbeddingAdapter, isDefault = true)
            registerMemoryRepository(memoryVectorStore)
            registerTool(fileSystemTool)
            registerTool(safeDiagnosticsTool)

            // Register standard agents
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

    // Provider Adapter Factory & Control Plane Service
    val providerAdapterFactory: ProviderAdapterFactory by lazy {
        ProviderAdapterFactory(workspaceStoragePort = workspaceStorage, defaultProjectId = 1L)
    }

    val providerControlPlaneService: ProviderControlPlaneService by lazy {
        ProviderControlPlaneService(
            providerRepository = providerRepository,
            adapterFactory = providerAdapterFactory,
            componentRegistry = componentRegistry
        ).apply {
            initialize()
        }
    }

    // CBR-MDP Decision Intelligence Engine with persistent CaseBase
    val cbrMdpEngine: CbrMdpEngine by lazy {
        val persistentCaseBase = CaseBase(decisionCaseDao = database.decisionCaseDao())
        CbrMdpEngine(caseBase = persistentCaseBase)
    }

    // Provider & Model Registry Service with Discovery Adapters
    val providerRegistryService: ProviderRegistryService by lazy {
        ProviderRegistryService().apply {
            registerDiscoveryAdapter(GeminiModelDiscoveryAdapter())
            registerDiscoveryAdapter(OpenAiCompatibleDiscoveryAdapter("local_ollama", "http://127.0.0.1:11434"))
        }
    }

    // Extensibility Engine (Skills, Plugins, MCP, Integrations)
    val extensionManager: ExtensionManager by lazy {
        ExtensionManager(
            componentRegistry = componentRegistry,
            mcpClient = McpClient(),
            integrationGateway = IntegrationGateway(),
            extensionConfigDao = database.extensionConfigDao(),
            executableSkills = listOf(cleanArchitectureSkill, securityAuditorSkill)
        )
    }

    // Intelligence Radar & Capability Evolution Pipeline
    val intelligenceRadarPipeline: IntelligenceRadarPipeline by lazy {
        IntelligenceRadarPipeline(
            radarSources = listOf(
                GitHubReleasesRadarSource(),
                RssFeedRadarSource()
            ),
            radarItemDao = database.radarItemDao(),
            evolutionCandidateDao = database.evolutionCandidateDao()
        )
    }

    // Knowledge & RAG Subsystem
    // Phase 2 — wired with persistenceService + workspaceIdProvider so documents
    // and chunks survive app restart. The workspaceIdProvider reads from
    // workspaceRuntimeService.requireActiveWorkspaceId() so RAG is always scoped
    // to the currently active workspace.
    val ragPipelineService: RagPipelineService by lazy {
        RagPipelineService(
            embeddingPort = defaultEmbeddingAdapter,
            persistenceService = knowledgePersistenceService,
            workspaceIdProvider = { workspaceRuntimeService.requireActiveWorkspaceId() }
        )
    }

    // Dedicated Decision Service Boundary (CBR-MDP Decision Intelligence)
    val decisionService: com.example.application.decision.DecisionService by lazy {
        com.example.application.decision.DecisionService(
            cbrMdpEngine = cbrMdpEngine,
            componentRegistry = componentRegistry,
            securityGuard = securityGuardService
        )
    }

    // Autonomous Closed-Loop Execution, Observation & Outcome Services
    val executionService: com.example.application.execution.ExecutionService by lazy {
        com.example.application.execution.ExecutionService(
            componentRegistry = componentRegistry,
            securityGuard = securityGuardService,
            extensionManager = extensionManager
        )
    }

    val observationService: com.example.application.observation.ObservationService by lazy {
        com.example.application.observation.ObservationService()
    }

    val outcomeService: com.example.application.outcome.OutcomeService by lazy {
        com.example.application.outcome.OutcomeService()
    }

    // Orchestrator Engine with Task Lifecycle Persistence & CBR-MDP Integration
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
                sessionRepository = appContainer.workspaceStorage,
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

