package com.example.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.search.TavilySearchAdapter
import com.example.infrastructure.storage.SandboxWorkspaceStorageAdapter
import com.example.infrastructure.tools.FileSystemTool
import com.example.infrastructure.tools.SafeDiagnosticsTool
import com.example.presentation.viewmodel.MainViewModel
import java.io.File

/**
 * Dependency Injection Container / Composition Root for the Clean Architecture Orchestrator.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // Persistence Layer
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

    val tavilySearchAdapter: TavilySearchAdapter by lazy {
        TavilySearchAdapter(
            apiKeyProvider = { null } // Or configured securely via Secrets/BuildConfig
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

    // Component & Tools Registry
    val componentRegistry: ComponentRegistry by lazy {
        ComponentRegistry().apply {
            // Register LLM Provider as default
            registerLlmProvider(geminiLlmAdapter, isDefault = true)

            // Register Search Provider
            registerSearchProvider(tavilySearchAdapter, isDefault = true)

            // Register Memory Repository for RAG Context
            registerMemoryRepository(memoryVectorStore)

            // Register Real Tools
            registerTool(fileSystemTool)
            registerTool(safeDiagnosticsTool)
        }
    }

    // Orchestrator Engine
    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            registry = componentRegistry,
            securityGuard = securityGuardService
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
                componentRegistry = appContainer.componentRegistry
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
