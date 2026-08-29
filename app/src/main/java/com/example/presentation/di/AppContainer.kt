package com.example.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.application.extension.ExtensionManager
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.provider.ProviderRegistryService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.llm.discovery.GeminiModelDiscoveryAdapter
import com.example.infrastructure.llm.discovery.OpenAiCompatibleDiscoveryAdapter
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.radar.GitHubReleasesRadarSource
import com.example.infrastructure.radar.RssFeedRadarSource
import com.example.infrastructure.search.MultiSourceSearchAdapter
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
 * executable skills, and CBR-MDP decision intelligence.
 */
class AppContainer(context: Context) {
    val appContext = context.applicationContext

    // Persistence Layer (Room Database)
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    // Infrastructure Adapters
    val workspaceStorage: SandboxWorkspaceStorageAdapter by lazy {
        SandboxWorkspaceStorageAdapter(
            context = appContext,
            projectDao = database.projectDao(),
            sessionDao = database.sessionDao()
        )
    }

    val memoryVectorStore: RoomVectorStoreAdapter by lazy {
        RoomVectorStoreAdapter(
            memoryDao = database.memoryDao(),
            embeddingProvider = null
        )
    }

    val geminiLlmAdapter: GeminiLlmAdapter by lazy {
        GeminiLlmAdapter()
    }

    val searchAdapter: MultiSourceSearchAdapter by lazy {
        MultiSourceSearchAdapter(
            tavilyApiKeyProvider = { null },
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
            registerMemoryRepository(memoryVectorStore)
            registerTool(fileSystemTool)
            registerTool(safeDiagnosticsTool)
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
    val ragPipelineService: RagPipelineService by lazy {
        RagPipelineService(embeddingPort = null)
    }

    // Orchestrator Engine with Task Lifecycle Persistence
    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            registry = componentRegistry,
            securityGuard = securityGuardService,
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
                ragPipelineService = appContainer.ragPipelineService
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
