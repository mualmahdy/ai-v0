package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.registry.ComponentRegistry
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.workflow.WorkflowPlan
import com.example.domain.ports.storage.SessionRepositoryPort
import com.example.presentation.state.ActiveNavigationTab
import com.example.presentation.state.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    private val executeAgentTaskUseCase: ExecuteAgentTaskUseCase,
    private val executeWorkflowUseCase: ExecuteWorkflowUseCase,
    private val manageMemoryUseCase: ManageMemoryUseCase,
    private val manageWorkspaceFilesUseCase: ManageWorkspaceFilesUseCase,
    private val sessionRepository: SessionRepositoryPort,
    private val componentRegistry: ComponentRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentExecutionJob: Job? = null

    init {
        initializeAgents()
        loadInitialData()
    }

    private fun initializeAgents() {
        val defaultAgents = listOf(
            AgentDefinition(
                identity = AgentIdentity(
                    id = AgentId("architect_orchestrator"),
                    name = "المخطط الرئيسي (Strategic Planner)",
                    description = "يقود تحليل المهام المعقدة، تقسيم العمليات، وحوكمة الموارد.",
                    role = AgentRole.PLANNER,
                    systemPrompt = "أنت المخطط الرئيسي لمنظومة AI-V0 Agent Studio. تتميز بالدقة الهندسية، التحليل المنهجي، وتوضيح القيود الواقعية."
                ),
                allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.STREAMING, CapabilityType.MEMORY_RETRIEVAL),
                budget = AgentBudget(maxTokens = 30000)
            ),
            AgentDefinition(
                identity = AgentIdentity(
                    id = AgentId("code_craftsman"),
                    name = "المبرمج التنفيذي (Executive Coder)",
                    description = "متخصص في بناء البرمجيات النظيفة وكتابة الشيفرات المعيارية والملفات.",
                    role = AgentRole.CODER,
                    systemPrompt = "أنت مهندس برمجيات محترف ومختص في هندسة النظم النظيفة وتطوير الأدوات."
                ),
                allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION, CapabilityType.FILE_STORAGE),
                budget = AgentBudget(maxTokens = 30000)
            ),
            AgentDefinition(
                identity = AgentIdentity(
                    id = AgentId("security_auditor"),
                    name = "حارس الأمان (Security Guard)",
                    description = "فحص السياسات الأمنية، تطهير المدخلات، وحماية العزل.",
                    role = AgentRole.SECURITY_GUARD,
                    systemPrompt = "أنت حارس الأمان والسياسات لمنصة الوكلاء الذكية. تفحص الأذونات وتضمن سلامة البيئة المعزولة."
                ),
                allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
                budget = AgentBudget(maxTokens = 30000)
            )
        )

        _uiState.update {
            it.copy(
                availableAgents = defaultAgents,
                activeAgent = defaultAgents.first()
            )
        }
    }

    fun loadInitialData() {
        viewModelScope.launch {
            when (val projectOutcome = sessionRepository.getActiveProject()) {
                is Outcome.Success -> _uiState.update { it.copy(activeProject = projectOutcome.value) }
                is Outcome.Degraded -> _uiState.update { it.copy(activeProject = projectOutcome.partialValue) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = projectOutcome.diagnosticMessage) }
            }

            refreshCapabilities()
            refreshFiles()
            refreshMemories()
        }
    }

    fun selectTab(tab: ActiveNavigationTab) {
        _uiState.update { it.copy(activeTab = tab) }
        when (tab) {
            ActiveNavigationTab.FILES -> refreshFiles()
            ActiveNavigationTab.MEMORY_RAG -> refreshMemories()
            ActiveNavigationTab.CAPABILITIES -> refreshCapabilities()
            else -> Unit
        }
    }

    fun selectAgent(agent: AgentDefinition) {
        _uiState.update { it.copy(activeAgent = agent) }
    }

    fun updatePromptInput(text: String) {
        _uiState.update { it.copy(promptInput = text) }
    }

    fun cancelExecution() {
        currentExecutionJob?.cancel()
        _uiState.update {
            it.copy(
                isExecuting = false,
                isExecutingWorkflow = false,
                diagnosticBanner = "تم إلغاء العملية بواسطة المستخدم."
            )
        }
    }

    fun executePrompt() {
        val current = _uiState.value
        val prompt = current.promptInput.trim()
        val agent = current.activeAgent ?: return
        if (prompt.isEmpty() || current.isExecuting) return

        _uiState.update {
            it.copy(
                isExecuting = true,
                streamText = "",
                executionLog = emptyList(),
                isDegraded = false,
                degradedReason = null,
                diagnosticBanner = null,
                errorMessage = null
            )
        }

        currentExecutionJob = viewModelScope.launch {
            try {
                executeAgentTaskUseCase(
                    agent = agent,
                    prompt = prompt
                ).collect { event ->
                    _uiState.update { state ->
                        val updatedLogs = state.executionLog + event
                        when (event) {
                            is ExecutionEvent.ContentChunk -> {
                                state.copy(
                                    streamText = state.streamText + event.deltaText,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.Degraded -> {
                                state.copy(
                                    isDegraded = true,
                                    degradedReason = event.reason,
                                    diagnosticBanner = event.message,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.UsageBudgetUpdate -> {
                                state.copy(
                                    currentTokensConsumed = event.promptTokens + event.completionTokens,
                                    sessionTotalTokens = event.totalSessionTokens,
                                    remainingBudget = event.remainingBudgetTokens,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.Completed -> {
                                state.copy(
                                    isExecuting = false,
                                    streamText = if (event.finalText.isNotBlank()) event.finalText else state.streamText,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.Error -> {
                                state.copy(
                                    isExecuting = false,
                                    errorMessage = event.message,
                                    executionLog = updatedLogs
                                )
                            }
                            else -> state.copy(executionLog = updatedLogs)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExecuting = false,
                        errorMessage = "حدث خطأ غير متوقع أثناء المعالجة: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun executeWorkflow(plan: WorkflowPlan) {
        if (_uiState.value.isExecutingWorkflow) return

        _uiState.update {
            it.copy(
                isExecutingWorkflow = true,
                workflowReport = null,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val report = executeWorkflowUseCase(plan)
                _uiState.update {
                    it.copy(
                        isExecutingWorkflow = false,
                        workflowReport = report
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExecutingWorkflow = false,
                        errorMessage = "فشل تنفيذ سير العمل: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // --- Files Operations ---
    fun refreshFiles() {
        val projectId = _uiState.value.activeProject?.id ?: 1L
        viewModelScope.launch {
            _uiState.update { it.copy(isFileLoading = true) }
            when (val outcome = manageWorkspaceFilesUseCase.listProjectFiles(projectId)) {
                is Outcome.Success -> _uiState.update { it.copy(workspaceFiles = outcome.value, isFileLoading = false) }
                is Outcome.Degraded -> _uiState.update { it.copy(workspaceFiles = outcome.partialValue ?: emptyList(), isFileLoading = false) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage, isFileLoading = false) }
            }
        }
    }

    fun openFile(relativePath: String) {
        val projectId = _uiState.value.activeProject?.id ?: 1L
        viewModelScope.launch {
            _uiState.update { it.copy(isFileLoading = true, selectedFilePath = relativePath) }
            when (val outcome = manageWorkspaceFilesUseCase.readProjectFile(projectId, relativePath)) {
                is Outcome.Success -> _uiState.update { it.copy(selectedFileContent = outcome.value, isFileLoading = false) }
                is Outcome.Degraded -> _uiState.update { it.copy(selectedFileContent = outcome.partialValue, isFileLoading = false) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage, isFileLoading = false) }
            }
        }
    }

    fun saveFile(relativePath: String, content: String) {
        val projectId = _uiState.value.activeProject?.id ?: 1L
        viewModelScope.launch {
            when (val outcome = manageWorkspaceFilesUseCase.writeProjectFile(projectId, relativePath, content)) {
                is Outcome.Success -> {
                    refreshFiles()
                    openFile(relativePath)
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> refreshFiles()
            }
        }
    }

    // --- Memory Operations ---
    fun updateMemoryQuery(q: String) {
        _uiState.update { it.copy(memoryQuery = q) }
    }

    fun searchMemory() {
        val q = _uiState.value.memoryQuery.trim()
        if (q.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingMemory = true) }
            when (val outcome = manageMemoryUseCase.retrieveContext(q)) {
                is Outcome.Success -> _uiState.update { it.copy(retrievedMemories = outcome.value, isSearchingMemory = false) }
                is Outcome.Degraded -> _uiState.update { it.copy(retrievedMemories = outcome.partialValue ?: emptyList(), isSearchingMemory = false) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage, isSearchingMemory = false) }
            }
        }
    }

    fun updateNewMemoryContent(text: String) {
        _uiState.update { it.copy(newMemoryContent = text) }
    }

    fun addNewMemory() {
        val content = _uiState.value.newMemoryContent.trim()
        if (content.isEmpty()) return

        viewModelScope.launch {
            val entry = MemoryEntry(
                id = UUID.randomUUID().toString(),
                content = content,
                type = MemoryType.FACTUAL_INSIGHT,
                confidence = 1.0f,
                provenance = MemoryProvenance(sourceSessionId = "MANUAL_ENTRY", createdAtTimestampMs = System.currentTimeMillis()),
                isActive = true
            )
            when (val outcome = manageMemoryUseCase.recordInsight(entry)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(newMemoryContent = "") }
                    refreshMemories()
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> refreshMemories()
            }
        }
    }

    fun refreshMemories() {
        viewModelScope.launch {
            when (val outcome = manageMemoryUseCase.getActiveMemories()) {
                is Outcome.Success -> _uiState.update { it.copy(allMemories = outcome.value) }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    // --- Capabilities ---
    fun refreshCapabilities() {
        val caps = componentRegistry.getCapabilityDescriptors()
        _uiState.update { it.copy(capabilities = caps) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
