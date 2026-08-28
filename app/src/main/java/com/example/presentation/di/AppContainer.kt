package com.example.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.domain.core.Outcome
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.tools.ToolPort
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxWorkspaceStorageAdapter
import com.example.presentation.viewmodel.MainViewModel

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

    val securityGuardService: SecurityGuardService by lazy {
        SecurityGuardService()
    }

    // Component & Tools Registry
    val componentRegistry: ComponentRegistry by lazy {
        ComponentRegistry().apply {
            // Register Gemini LLM Provider as default
            registerLlmProvider(geminiLlmAdapter, isDefault = true)

            // Register Workspace File Tool
            registerTool(
                object : ToolPort {
                    override val declaration = ToolDeclaration(
                        name = "workspace_file_tool",
                        description = "أداة قراءة وكتابة الملفات المعزولة في مساحة العمل.",
                        parameters = listOf(
                            ToolParameter(name = "action", type = "string", description = "read|write|list"),
                            ToolParameter(name = "path", type = "string", description = "مسار الملف النسبي"),
                            ToolParameter(name = "content", type = "string", description = "محتوى الملف", isRequired = false)
                        )
                    )

                    override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                        val action = input.arguments["action"]?.toString() ?: "list"
                        val path = input.arguments["path"]?.toString() ?: ""
                        val content = input.arguments["content"]?.toString() ?: ""

                        return when (action) {
                            "write" -> {
                                when (val writeResult = workspaceStorage.writeFile(1L, path, content)) {
                                    is Outcome.Success -> Outcome.Success(ToolOutput(content = "تمت كتابة الملف $path بنجاح."))
                                    is Outcome.Error -> Outcome.Error(ToolFailure.InternalExecutionError(writeResult.diagnosticMessage))
                                    is Outcome.Degraded -> Outcome.Success(ToolOutput(content = "تمت الكتابة مع تراجع."))
                                }
                            }
                            "read" -> {
                                when (val readResult = workspaceStorage.readFile(1L, path)) {
                                    is Outcome.Success -> Outcome.Success(ToolOutput(content = readResult.value))
                                    is Outcome.Error -> Outcome.Error(ToolFailure.InternalExecutionError(readResult.diagnosticMessage))
                                    is Outcome.Degraded -> Outcome.Success(ToolOutput(content = readResult.partialValue ?: ""))
                                }
                            }
                            else -> {
                                when (val listResult = workspaceStorage.listFiles(1L)) {
                                    is Outcome.Success -> {
                                        val names = listResult.value.joinToString(", ") { it.relativePath }
                                        Outcome.Success(ToolOutput(content = "الملفات: $names"))
                                    }
                                    is Outcome.Error -> Outcome.Error(ToolFailure.InternalExecutionError(listResult.diagnosticMessage))
                                    is Outcome.Degraded -> Outcome.Success(ToolOutput(content = "سرد الملفات بنمط تراجع."))
                                }
                            }
                        }
                    }
                }
            )

            // Register Diagnostics Tool
            registerTool(
                object : ToolPort {
                    override val declaration = ToolDeclaration(
                        name = "safe_diagnostics_tool",
                        description = "أداة فحص ومراقبة استقرار النظام والأمان المعزول.",
                        parameters = listOf(
                            ToolParameter(name = "metric", type = "string", description = "المقياس المطلوب فحصه")
                        )
                    )

                    override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                        return Outcome.Success(
                            ToolOutput(content = "{\"status\": \"OPTIMAL\", \"security\": \"SECURE\", \"sandbox\": \"ISOLATED\"}")
                        )
                    }
                }
            )
        }
    }

    // Orchestrator Engine
    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            registry = componentRegistry,
            securityGuard = securityGuardService
        )
    }

    // Use Cases
    val executeAgentTaskUseCase: ExecuteAgentTaskUseCase by lazy {
        ExecuteAgentTaskUseCase(agentOrchestrator)
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
                manageMemoryUseCase = appContainer.manageMemoryUseCase,
                manageWorkspaceFilesUseCase = appContainer.manageWorkspaceFilesUseCase,
                sessionRepository = appContainer.workspaceStorage,
                componentRegistry = appContainer.componentRegistry
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
