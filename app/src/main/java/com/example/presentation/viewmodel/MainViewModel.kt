package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.extension.ExtensionManager
import com.example.application.provider.ProviderRegistryService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.RagPipelineService
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
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.decision.EnvironmentObservation
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.workflow.WorkflowPlan
import com.example.domain.core.workspace.ResourceEdge
import com.example.domain.core.workspace.ResourceEdgeType
import com.example.domain.core.workspace.ResourceGraph
import com.example.domain.core.workspace.ResourceNode
import com.example.domain.core.workspace.ResourceType
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
    private val componentRegistry: ComponentRegistry,
    private val cbrMdpEngine: CbrMdpEngine,
    private val providerRegistryService: ProviderRegistryService,
    private val extensionManager: ExtensionManager,
    private val intelligenceRadarPipeline: IntelligenceRadarPipeline,
    private val ragPipelineService: RagPipelineService
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentExecutionJob: Job? = null

    init {
        initializeAgents()
        observeSubsystems()
        loadInitialData()
    }

    private fun observeSubsystems() {
        viewModelScope.launch {
            providerRegistryService.registeredProviders.collect { providers ->
                _uiState.update { it.copy(providers = providers) }
            }
        }
        viewModelScope.launch {
            providerRegistryService.discoveredModels.collect { models ->
                _uiState.update { it.copy(discoveredModels = models) }
            }
        }
        viewModelScope.launch {
            extensionManager.skills.collect { skills ->
                _uiState.update { it.copy(skills = skills) }
            }
        }
        viewModelScope.launch {
            extensionManager.plugins.collect { plugins ->
                _uiState.update { it.copy(plugins = plugins) }
            }
        }
        viewModelScope.launch {
            extensionManager.mcpServers.collect { servers ->
                _uiState.update { it.copy(mcpServers = servers) }
            }
        }
        viewModelScope.launch {
            extensionManager.integrations.collect { integ ->
                _uiState.update { it.copy(integrations = integ) }
            }
        }
        viewModelScope.launch {
            intelligenceRadarPipeline.radarItems.collect { items ->
                _uiState.update { it.copy(radarItems = items) }
            }
        }
        viewModelScope.launch {
            intelligenceRadarPipeline.evolutionCandidates.collect { cand ->
                _uiState.update { it.copy(evolutionCandidates = cand) }
            }
        }
        viewModelScope.launch {
            ragPipelineService.documents.collect { docs ->
                _uiState.update { it.copy(knowledgeDocuments = docs) }
            }
        }
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
                budget = AgentBudget(maxTokens = 40000)
            ),
            AgentDefinition(
                identity = AgentIdentity(
                    id = AgentId("security_guardian"),
                    name = "حارس الحوكمة والأمان (Security Auditor)",
                    description = "يدقق في مدخلات ومخرجات الأدوات، ويتحقق من سلامة الأوامر.",
                    role = AgentRole.SECURITY_GUARD,
                    systemPrompt = "أنت مدقق أمني مستقل وحارس لسياسات الأمان والحوكمة."
                ),
                allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
                budget = AgentBudget(maxTokens = 20000)
            )
        )

        _uiState.update {
            it.copy(
                availableAgents = defaultAgents,
                activeAgent = defaultAgents.first()
            )
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            refreshMemories()
            refreshFiles()
            refreshCapabilities()
            simulateDecision()
        }
    }

    // --- Navigation ---
    fun selectNavigationTab(tab: ActiveNavigationTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun selectTab(tab: ActiveNavigationTab) {
        selectNavigationTab(tab)
    }

    // --- Agent Selection ---
    fun selectAgent(agent: AgentDefinition) {
        _uiState.update { it.copy(activeAgent = agent) }
    }

    // --- Network & Autonomy Policy ---
    fun setNetworkPolicy(policy: NetworkPolicy) {
        _uiState.update { it.copy(networkPolicy = policy) }
        simulateDecision()
    }

    fun setAutonomyPolicy(policy: AutonomyPolicy) {
        _uiState.update { it.copy(autonomyPolicy = policy) }
    }

    // --- Prompt & Task Execution ---
    fun updatePromptInput(input: String) {
        _uiState.update { it.copy(promptInput = input) }
    }

    fun clearPromptInput() {
        _uiState.update { it.copy(promptInput = "") }
    }

    fun cancelExecution() {
        currentExecutionJob?.cancel()
        currentExecutionJob = null
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
                    prompt = prompt,
                    networkPolicy = current.networkPolicy,
                    isNetworkAvailable = true,
                    includeWebSearch = false
                ).collect { event ->
                    _uiState.update { state ->
                        val updatedLogs = state.executionLog + event
                        when (event) {
                            is ExecutionEvent.DecisionMade -> {
                                state.copy(
                                    latestDecision = event.decision,
                                    executionLog = updatedLogs
                                )
                            }
                            is ExecutionEvent.ObservationRecorded -> {
                                state.copy(
                                    decisionUncertainty = event.updatedUncertainty,
                                    caseBaseList = cbrMdpEngine.getCaseBase().getAllCases(),
                                    executionLog = updatedLogs
                                )
                            }
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
                                    executionLog = updatedLogs,
                                    caseBaseList = cbrMdpEngine.getCaseBase().getAllCases()
                                )
                            }
                            is ExecutionEvent.Error -> {
                                state.copy(
                                    isExecuting = false,
                                    errorMessage = event.message,
                                    executionLog = updatedLogs,
                                    caseBaseList = cbrMdpEngine.getCaseBase().getAllCases()
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

    // --- Decision Intelligence (CBR-MDP) ---
    fun updateDecisionComplexity(value: Float) {
        _uiState.update { it.copy(decisionTaskComplexity = value) }
        simulateDecision()
    }

    fun updateDecisionUncertainty(value: Float) {
        _uiState.update { it.copy(decisionUncertainty = value) }
        simulateDecision()
    }

    fun simulateDecision() {
        val current = _uiState.value
        val state = DecisionState(
            taskId = TaskId(UUID.randomUUID().toString()),
            taskComplexity = current.decisionTaskComplexity,
            requiresVision = false,
            requiresToolCalling = true,
            requiresWebSearch = current.decisionTaskComplexity > 0.7f,
            requiresCoding = true,
            networkPolicy = current.networkPolicy,
            uncertaintyScore = current.decisionUncertainty
        )

        val candidateActions = listOf(
            DecisionAction(DecisionActionType.SELECT_AGENT, targetId = "code_craftsman"),
            DecisionAction(DecisionActionType.SELECT_MODEL, targetId = "gemini-2.5-flash", estimatedCost = 0.001),
            DecisionAction(DecisionActionType.SELECT_PROVIDER, targetId = if (current.networkPolicy == NetworkPolicy.OFFLINE) "local_ollama" else "gemini_google"),
            DecisionAction(DecisionActionType.SEARCH, targetId = "multi_source_search", estimatedCost = 0.005),
            DecisionAction(DecisionActionType.RETRIEVE_KNOWLEDGE, targetId = "rag_knowledge_base"),
            DecisionAction(DecisionActionType.CREATE_PLAN, targetId = "dag_workflow_engine"),
            DecisionAction(DecisionActionType.STOP)
        )

        val result = cbrMdpEngine.evaluateAndSelectAction(state, candidateActions)
        _uiState.update {
            it.copy(
                latestDecision = result,
                caseBaseList = cbrMdpEngine.getCaseBase().getAllCases()
            )
        }
    }

    // --- Model Discovery ---
    fun discoverModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringModels = true) }
            try {
                providerRegistryService.discoverAllProvidersAndModels()
            } finally {
                _uiState.update { it.copy(isDiscoveringModels = false) }
            }
        }
    }

    // --- Extensibility Management ---
    fun toggleSkill(skillId: String) {
        extensionManager.toggleSkill(skillId)
    }

    fun executeSkillDirectly(skillId: String, parameters: Map<String, Any?>) {
        viewModelScope.launch {
            when (val outcome = extensionManager.executeSkill(skillId, parameters)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(diagnosticBanner = outcome.value) }
                    refreshFiles()
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun togglePlugin(pluginId: String) {
        extensionManager.togglePlugin(pluginId)
    }

    fun toggleMcpServer(serverId: String) {
        extensionManager.toggleMcpServer(serverId)
    }

    fun pingMcpServer(serverId: String) {
        viewModelScope.launch {
            extensionManager.pingAndDiscoverMcpServer(serverId)
        }
    }

    fun registerMcpServer(name: String, endpointUri: String) {
        extensionManager.registerNewMcpServer(name, endpointUri)
    }

    fun connectIntegration(integrationId: String, token: String) {
        viewModelScope.launch {
            extensionManager.verifyAndConnectIntegration(integrationId, token)
        }
    }

    // --- Intelligence Radar & Evolution ---
    fun refreshRadar() {
        viewModelScope.launch {
            intelligenceRadarPipeline.refreshRadarFeed()
        }
    }

    fun advanceCandidateStage(candidateId: String, nextStage: EvolutionStage) {
        intelligenceRadarPipeline.advanceEvolutionStage(candidateId, nextStage)
    }

    // --- Knowledge & RAG Operations ---
    fun updateDocTitle(title: String) {
        _uiState.update { it.copy(newDocTitle = title) }
    }

    fun updateDocContent(content: String) {
        _uiState.update { it.copy(newDocContent = content) }
    }

    fun ingestNewDocument() {
        val title = _uiState.value.newDocTitle.trim()
        val content = _uiState.value.newDocContent.trim()
        if (title.isEmpty() || content.isEmpty()) return

        viewModelScope.launch {
            ragPipelineService.ingestDocument(title, content, "workspace://docs/$title.md")
            _uiState.update { it.copy(newDocTitle = "", newDocContent = "") }
        }
    }

    fun queryKnowledgeRag(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            val assembled = ragPipelineService.retrieveRelevantContext(query)
            _uiState.update { it.copy(assembledRagContext = assembled) }
        }
    }

    // --- Workflow & Task ---
    fun executeWorkflow(plan: WorkflowPlan) {
        if (_uiState.value.isExecutingWorkflow) return
        _uiState.update { it.copy(isExecutingWorkflow = true, workflowReport = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val report = executeWorkflowUseCase(plan)
                _uiState.update { it.copy(isExecutingWorkflow = false, workflowReport = report) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isExecutingWorkflow = false, errorMessage = "فشل تنفيذ خطة العمل: ${e.localizedMessage}") }
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

    fun refreshCapabilities() {
        val caps = componentRegistry.getCapabilityDescriptors()
        _uiState.update { it.copy(capabilities = caps) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
