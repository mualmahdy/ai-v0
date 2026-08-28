package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.db.AppDatabase
import com.example.data.local.db.entities.*
import com.example.domain.models.*
import com.example.runtime.agents.*
import com.example.runtime.budget.TokenBudgetTracker
import com.example.runtime.events.EventBus
import com.example.runtime.events.SystemEvent
import com.example.runtime.execution.CodeExecutionEngine
import com.example.runtime.execution.TerminalManager
import com.example.runtime.memory.MemoryManager
import com.example.runtime.providers.GeminiCloudProvider
import com.example.runtime.providers.LocalHeuristicProvider
import com.example.runtime.providers.ModelOrchestrator
import com.example.runtime.providers.SearchProviderEngine
import com.example.runtime.rag.LocalRagEngine
import com.example.runtime.rag.NativeOfflineEmbedder
import com.example.runtime.storage.FileEntry
import com.example.runtime.storage.WorkspaceStorageManager
import com.example.runtime.workflow.WorkflowExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application, viewModelScope)

    // Storage & Core engines
    val storageManager = WorkspaceStorageManager(application, db.fileVersionDao())
    val tokenBudgetTracker = TokenBudgetTracker(db.tokenBudgetDao())
    val embedder = NativeOfflineEmbedder(64)
    val ragEngine = LocalRagEngine(db.knowledgeDao(), embedder)
    val memoryManager = MemoryManager(db.memoryDao(), embedder)
    val searchEngine = SearchProviderEngine(ragEngine, memoryManager)

    // Providers & Orchestrator
    val localModelProvider = LocalHeuristicProvider()
    val geminiModelProvider = GeminiCloudProvider()
    val orchestrator = ModelOrchestrator(localModelProvider, geminiModelProvider)

    // Execution & Tools
    val executionEngine = CodeExecutionEngine(storageManager)
    val toolExecutor = ToolExecutor(
        storageManager = storageManager,
        searchEngine = searchEngine,
        ragEngine = ragEngine,
        memoryManager = memoryManager,
        codeRunnerCallback = { lang, code ->
            val out = executionEngine.executeCode(activeProjectId.value, lang, code)
            if (out.stderr.isNotEmpty()) "ERROR: ${out.stderr}" else out.stdout
        }
    )
    val toolLoop = ToolLoop(orchestrator, toolExecutor)

    // Agent Registry
    val agentRegistry = AgentRegistry().apply {
        register(DirectAgent(orchestrator))
        register(PlannerAgent(orchestrator, memoryManager))
        register(CodeAgent(toolLoop))
        register(ResearchAgent(toolLoop))
        register(SearchAgent(toolLoop))
        register(MemoryAgent(memoryManager))
        register(ReviewerAgent(orchestrator))
    }

    val workflowExecutor = WorkflowExecutor(
        agentRegistry = agentRegistry,
        tokenBudgetTracker = tokenBudgetTracker,
        plannerAgent = agentRegistry.get("planner") as PlannerAgent
    )

    val terminalManager = TerminalManager(
        storageManager = storageManager,
        executionEngine = executionEngine,
        agentRegistry = agentRegistry,
        tokenBudgetTracker = tokenBudgetTracker
    )

    // --- State Observables ---
    val activeProjectId = MutableStateFlow(1L)
    val activeSessionId = MutableStateFlow("default_session")
    val activeTab = MutableStateFlow("chat_panel") // Current selected workspace panel

    val isOfflineMode = MutableStateFlow(false)

    // Projects
    val projects: StateFlow<List<ProjectEntity>> = db.projectDao().getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Sessions for active project
    val sessions: StateFlow<List<SessionEntity>> = activeProjectId.flatMapLatest { pid ->
        db.sessionDao().getSessionsForProject(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Messages for active session
    val messages: StateFlow<List<MessageEntity>> = combine(activeProjectId, activeSessionId) { pid, sid ->
        pid to sid
    }.flatMapLatest { (pid, sid) ->
        db.messageDao().getMessagesForSession(pid, sid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Agents in DB
    val agentsConfig: StateFlow<List<AgentConfigEntity>> = activeProjectId.flatMapLatest { pid ->
        db.agentConfigDao().getAgentsForProject(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Workspace Components (UI customization)
    val workspaceComponents: StateFlow<List<WorkspaceComponentEntity>> = db.workspaceComponentDao().getAllComponents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Workflows in DB
    val savedWorkflows: StateFlow<List<WorkflowEntity>> = activeProjectId.flatMapLatest { pid ->
        db.workflowDao().getWorkflows(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Documents in DB
    val documents: StateFlow<List<DocumentEntity>> = activeProjectId.flatMapLatest { pid ->
        db.knowledgeDao().getDocuments(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Long-term Memories in DB
    val memories: StateFlow<List<LongTermMemoryEntity>> = activeProjectId.flatMapLatest { pid ->
        db.memoryDao().getAllMemories(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Model Providers in DB
    val modelProviders: StateFlow<List<ModelProviderEntity>> = activeProjectId.flatMapLatest { pid ->
        db.modelProviderDao().getProvidersForProject(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search Providers in DB
    val searchProviders: StateFlow<List<SearchProviderEntity>> = activeProjectId.flatMapLatest { pid ->
        db.searchProviderDao().getSearchProviders(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Files in Workspace
    val workspaceFiles = MutableStateFlow<List<FileEntry>>(emptyList())
    val currentOpenFile = MutableStateFlow<String?>("README.md")
    val fileContent = MutableStateFlow("")
    val fileDiff = MutableStateFlow<String?>(null)

    // Workflow state
    val currentWorkflowPlan = MutableStateFlow<WorkflowPlan?>(null)
    val activeWorkflowResult = MutableStateFlow<WorkflowExecutionResult?>(null)
    val isWorkflowRunning = MutableStateFlow(false)

    // Events stream
    val systemEvents = EventBus.events

    // Generating State
    val isGenerating = MutableStateFlow(false)

    init {
        refreshFiles()
        loadFileContent("README.md")
        observeAgentsAndRegister()
    }

    private fun observeAgentsAndRegister() {
        viewModelScope.launch {
            agentsConfig.collect { list ->
                list.forEach { entity ->
                    val tools = try {
                        val arr = org.json.JSONArray(entity.toolsJson)
                        (0 until arr.length()).map { arr.getString(it) }
                    } catch (e: Exception) { emptyList() }

                    agentRegistry.register(
                        ConfigurableAgent(
                            name = entity.name,
                            description = entity.description,
                            modelRole = entity.modelRole,
                            systemPrompt = entity.systemPrompt,
                            tools = tools,
                            toolLoop = toolLoop
                        )
                    )
                }
            }
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        isOfflineMode.value = enabled
        orchestrator.isOfflineModeEnforced = enabled
        searchEngine.isOfflineMode = enabled
        viewModelScope.launch(Dispatchers.IO) {
            db.appSettingDao().setSetting(AppSettingEntity("network.offline_mode", enabled.toString()))
            EventBus.publish("system.network", if (enabled) "تم تفعيل وضع عدم الاتصال (Offline Mode)" else "تم تفعيل وضع الاتصال (Online Mode)")
        }
    }

    // --- Chat Actions ---
    fun sendMessage(prompt: String, agentName: String = "direct") {
        if (prompt.isBlank() || isGenerating.value) return
        val pid = activeProjectId.value
        val sid = activeSessionId.value
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        viewModelScope.launch(Dispatchers.IO) {
            isGenerating.value = true

            // Insert user message
            db.messageDao().insertMessage(
                MessageEntity(
                    projectId = pid,
                    sessionId = sid,
                    role = "user",
                    content = prompt,
                    createdAt = now
                )
            )

            // Update session counter
            db.sessionDao().insertOrUpdateSession(
                SessionEntity(
                    sessionId = sid,
                    projectId = pid,
                    agentName = agentName,
                    messageCount = db.messageDao().getMessageCount(pid, sid) + 1,
                    updatedAt = now
                )
            )

            EventBus.publishAgentStarted(agentName, prompt)
            val startTime = System.currentTimeMillis()

            val agent = agentRegistry.get(agentName) ?: agentRegistry.get("direct")!!
            val result = agent.execute(
                mapOf(
                    "prompt" to prompt,
                    "projectId" to pid
                )
            )

            val duration = System.currentTimeMillis() - startTime
            EventBus.publishAgentCompleted(agentName, result.status, duration)

            // Insert assistant reply
            db.messageDao().insertMessage(
                MessageEntity(
                    projectId = pid,
                    sessionId = sid,
                    role = "assistant",
                    content = result.response,
                    providerName = result.providerUsed ?: "Local Native Engine",
                    modelName = result.modelUsed ?: "native-cbr-engine",
                    status = result.status,
                    degradedReason = result.degradedReason,
                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
            )

            isGenerating.value = false
        }
    }

    // --- Workspace Files & Code Editor ---
    fun refreshFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceFiles.value = storageManager.listFiles(activeProjectId.value)
        }
    }

    fun loadFileContent(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                currentOpenFile.value = relativePath
                val content = storageManager.readFile(activeProjectId.value, relativePath)
                fileContent.value = content
                fileDiff.value = null
            } catch (e: Exception) {
                fileContent.value = "// Error loading file: ${e.message}"
            }
        }
    }

    fun saveCurrentFile(newContent: String) {
        val path = currentOpenFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.writeFile(activeProjectId.value, path, newContent)
            fileContent.value = newContent
            fileDiff.value = null
            refreshFiles()
            EventBus.publish("file.saved", "تم حفظ الملف $path")
        }
    }

    fun checkFileDiff(newContent: String) {
        val path = currentOpenFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val oldContent = try { storageManager.readFile(activeProjectId.value, path) } catch (e: Exception) { "" }
            fileDiff.value = storageManager.computeDiff(oldContent, newContent)
        }
    }

    // --- Workflows ---
    fun generateWorkflowPlan(goal: String) {
        if (goal.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val planner = agentRegistry.get("planner") as? PlannerAgent
            if (planner != null) {
                val plan = planner.createPlan(activeProjectId.value, goal)
                currentWorkflowPlan.value = plan
            }
        }
    }

    fun runCurrentWorkflow() {
        val plan = currentWorkflowPlan.value ?: return
        if (isWorkflowRunning.value) return

        viewModelScope.launch(Dispatchers.IO) {
            isWorkflowRunning.value = true
            val result = workflowExecutor.execute(activeProjectId.value, plan) { step ->
                // Step progress
            }
            activeWorkflowResult.value = result
            isWorkflowRunning.value = false

            // Save to database
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            db.workflowDao().insertWorkflow(
                WorkflowEntity(
                    projectId = activeProjectId.value,
                    name = plan.goal.take(50),
                    description = plan.goal,
                    templateJson = org.json.JSONObject(mapOf("goal" to plan.goal, "status" to result.status, "quality" to result.quality)).toString(),
                    createdAt = now
                )
            )

            // Automatic CBR Case Formation
            memoryManager.evaluateAndFormWorkflowCase(activeProjectId.value, result)
        }
    }

    // --- Knowledge & RAG ---
    fun addKnowledgeDoc(title: String, content: String, collection: String = "default") {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val docId = "doc_${System.currentTimeMillis()}"
            ragEngine.addDocument(activeProjectId.value, docId, title, content, collection)
            EventBus.publish("knowledge.indexed", "تمت فهرسة المستند '$title' في قاعدة المعرفة المحلية")
        }
    }

    // --- UI Layout Reordering & Visibility ---
    fun toggleComponentVisibility(componentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = workspaceComponents.value
            val item = list.find { it.componentId == componentId } ?: return@launch
            db.workspaceComponentDao().updateComponent(item.copy(isVisible = !item.isVisible))
        }
    }

    fun moveComponent(componentId: String, moveUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = workspaceComponents.value.toMutableList()
            val index = list.indexOfFirst { it.componentId == componentId }
            if (index == -1) return@launch
            val targetIndex = if (moveUp) index - 1 else index + 1
            if (targetIndex in list.indices) {
                val current = list[index]
                val target = list[targetIndex]
                db.workspaceComponentDao().updateComponent(current.copy(displayOrder = target.displayOrder))
                db.workspaceComponentDao().updateComponent(target.copy(displayOrder = current.displayOrder))
            }
        }
    }

    // --- Agent Management ---
    fun createAgent(name: String, description: String, role: String, prompt: String, budget: Int, tools: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            db.agentConfigDao().insertAgent(
                AgentConfigEntity(
                    projectId = activeProjectId.value,
                    name = name.trim().lowercase(),
                    description = description,
                    modelRole = role,
                    systemPrompt = prompt,
                    budget = budget,
                    toolsJson = org.json.JSONArray(tools).toString(),
                    createdAt = now
                )
            )
            EventBus.publish("agent.created", "تم تسجيل وكيل جديد: $name")
        }
    }

    fun deleteAgent(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.agentConfigDao().deleteAgent(activeProjectId.value, name)
            EventBus.publish("agent.deleted", "تم حذف الوكيل: $name")
        }
    }
}
