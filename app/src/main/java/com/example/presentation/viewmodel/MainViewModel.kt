package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.extension.ExtensionManager
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.application.workspace.WorkspaceRuntimeService
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
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.ServiceValidationResult
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceRecord
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
    private val componentRegistry: ComponentRegistry,
    private val cbrMdpEngine: CbrMdpEngine,
    private val extensionManager: ExtensionManager,
    private val intelligenceRadarPipeline: IntelligenceRadarPipeline,
    private val ragPipelineService: RagPipelineService,
    private val providerControlPlaneService: ProviderControlPlaneService,
    // Phase 2 — workspace runtime service for multi-workspace support
    private val workspaceRuntimeService: WorkspaceRuntimeService
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Phase 2 — expose active workspace and workspace list as StateFlows
    val activeWorkspace: StateFlow<com.example.domain.core.workspace.Workspace?> =
        workspaceRuntimeService.activeWorkspace
    val allWorkspaces: StateFlow<List<com.example.domain.core.workspace.Workspace>> =
        workspaceRuntimeService.allWorkspaces

    private var currentExecutionJob: Job? = null

    init {
        initializeAgents()
        observeSubsystems()
        loadInitialData()
        observeWorkspace()
        // Phase 4 — first-run bootstrap: seeds local embedding + multi-source
        // search + Gemini provider records (idempotent, no network for in-process).
        providerControlPlaneService.launchBootstrapDefaults()
    }

    /**
     * Phase 2 — Observes the active workspace and reacts to workspace switches:
     *   - Updates UiState.activeProject to the active workspace's lastActiveProjectId
     *   - Reloads the RAG in-memory index from the new workspace's persisted knowledge
     *   - Refreshes files for the new project
     */
    private fun observeWorkspace() {
        viewModelScope.launch {
            // FIX R-5 (audit c03919d): unhandled exception in an init-path
            // collector previously crashed the app (no catch on launch).
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    if (workspace != null) {
                        _uiState.update {
                            it.copy(
                                activeProject = com.example.domain.core.storage.ProjectMetadata(
                                    id = workspace.activeProjectId.takeIf { id -> id > 0 } ?: 1L,
                                    name = workspace.name,
                                    description = workspace.description,
                                    isDefault = workspace.id == "default",
                                    createdAtTimestampMs = workspace.createdAtTimestampMs
                                )
                            )
                        }
                        // Reload RAG knowledge for the new workspace scope.
                        ragPipelineService.loadFromPersistence()
                        // Refresh files for the new active project.
                        refreshFiles()
                    }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر تحميل مساحة العمل النشطة: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Phase 2 — Switches to a different workspace. The UI calls this when the user
     * picks a workspace from the workspace switcher.
     */
    fun switchWorkspace(workspaceId: String) {
        viewModelScope.launch {
            workspaceRuntimeService.switchWorkspace(workspaceId)
        }
    }

    /**
     * Phase 2 — Creates a new workspace and switches to it.
     */
    fun createWorkspace(name: String, description: String) {
        viewModelScope.launch {
            workspaceRuntimeService.createWorkspace(name = name, description = description)
        }
    }

    /**
     * Phase 2 — Updates the network policy of the active workspace.
     */
    fun updateWorkspaceNetworkPolicy(policy: NetworkPolicy) {
        viewModelScope.launch {
            workspaceRuntimeService.updateNetworkPolicy(policy)
        }
    }

    private fun observeSubsystems() {
        viewModelScope.launch {
            providerControlPlaneService.allProvidersFlow.collect { providers ->
                _uiState.update { it.copy(generalizedProviders = providers) }
                refreshCapabilities()
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allServicesFlow.collect { services ->
                _uiState.update { it.copy(generalizedServices = services) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allConfigurationsFlow.collect { configs ->
                _uiState.update { it.copy(generalizedConfigurations = configs) }
            }
        }
        viewModelScope.launch {
            providerControlPlaneService.allResourcesFlow.collect { resources ->
                _uiState.update { it.copy(materializedResources = resources) }
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
            // FIX R-5: guarded initial load (previously an exception here — e.g.
            // corrupt DB row — crashed the app during ViewModel init).
            runCatching {
                refreshMemories()
                refreshFiles()
                refreshCapabilities()
                simulateDecision()
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "تعذر تحميل البيانات الأولية: ${e.localizedMessage}") }
            }
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

    // --- Provider & Resource Control Plane (Phase 4 — generalized API) ---

    /**
     * Create a new Provider. Pure persistence — no network calls.
     */
    fun createProvider(name: String, description: String, websiteUrl: String?, isLocal: Boolean) {
        viewModelScope.launch {
            val provider = com.example.domain.core.provider.Provider(
                id = "prov_${System.currentTimeMillis()}",
                name = name,
                description = description,
                websiteUrl = websiteUrl,
                isLocal = isLocal,
                isEnabled = true
            )
            when (val outcome = providerControlPlaneService.createProvider(provider)) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(isAddProviderDialogOpen = false) }
                    refreshCapabilities()
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Add a service to an existing provider. ServiceType↔Protocol compatibility
     * is verified by the control plane. Pure persistence — no network calls.
     */
    fun addService(providerId: String, name: String, serviceType: ServiceType, supportedProtocols: List<String>) {
        viewModelScope.launch {
            val service = ProviderService(
                id = "${providerId}_${serviceType.code}_${System.currentTimeMillis()}",
                providerId = providerId,
                name = name,
                serviceType = serviceType,
                supportedProtocolIds = supportedProtocols,
                isEnabled = true
            )
            when (val outcome = providerControlPlaneService.addService(service)) {
                is Outcome.Success -> refreshCapabilities()
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Save a ServiceConfiguration. Pure persistence — no network calls.
     */
    fun saveServiceConfiguration(
        serviceId: String,
        protocolId: com.example.domain.core.provider.ServiceProtocolId,
        endpointUrl: String,
        authAlias: String?,
        secretApiKey: String?,
        timeoutSeconds: Int = 30
    ) {
        viewModelScope.launch {
            val config = ServiceConfiguration(
                id = "cfg_${serviceId}_${System.currentTimeMillis()}",
                serviceId = serviceId,
                protocolId = protocolId,
                endpointUrl = endpointUrl,
                authAlias = authAlias,
                timeoutSeconds = timeoutSeconds,
                isEnabled = true
            )
            when (val outcome = providerControlPlaneService.saveConfiguration(config)) {
                is Outcome.Success -> {
                    // Also store the secret if provided
                    if (!authAlias.isNullOrBlank() && !secretApiKey.isNullOrBlank()) {
                        providerControlPlaneService.storeSecret(authAlias, secretApiKey)
                    }
                    refreshCapabilities()
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Test the connection for a ServiceConfiguration. Explicit network call
     * (POST /chat/completions for LLM, real protocol operation for others).
     */
    fun testServiceConnection(configId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingProvider = true, testingProviderId = configId) }
            try {
                when (val outcome = providerControlPlaneService.testServiceConnection(configId)) {
                    is Outcome.Success -> {
                        _uiState.update {
                            it.copy(
                                isTestingProvider = false,
                                diagnosticBanner = outcome.value.message
                            )
                        }
                    }
                    is Outcome.Error -> {
                        _uiState.update {
                            it.copy(
                                isTestingProvider = false,
                                errorMessage = outcome.diagnosticMessage
                            )
                        }
                    }
                    else -> _uiState.update { it.copy(isTestingProvider = false) }
                }
            } finally {
                _uiState.update { it.copy(isTestingProvider = false) }
            }
        }
    }

    /**
     * Discover offerings for a service. Explicit network discovery — produces
     * `ServiceOffering`s but does NOT materialize ResourceRecords.
     */
    fun discoverOfferings(serviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringModels = true) }
            try {
                when (val outcome = providerControlPlaneService.discoverOfferings(serviceId)) {
                    is Outcome.Success -> {
                        _uiState.update {
                            it.copy(discoveredOfferings = outcome.value)
                        }
                    }
                    is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                    else -> Unit
                }
            } finally {
                _uiState.update { it.copy(isDiscoveringModels = false) }
            }
        }
    }

    /**
     * Materialize a ServiceOffering into a ResourceRecord. The record starts
     * at REGISTERED/runtimeSupported=false/UNKNOWN. The user must call
     * `validateResource(resourceId)` to promote it to ENABLED/true/HEALTHY.
     */
    fun materializeResource(providerId: String, serviceId: String, offeringId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.materializeResource(providerId, serviceId, offeringId)) {
                is Outcome.Success -> refreshCapabilities()
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Validate a materialized ResourceRecord. Runs the appropriate
     * ResourceValidator and updates lifecycle/runtimeSupported/health.
     */
    fun validateResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.validateResource(ResourceId(resourceId))) {
                is Outcome.Success -> {
                    _uiState.update {
                        it.copy(diagnosticBanner = outcome.value.message)
                    }
                    refreshCapabilities()
                }
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Enable a previously-validated resource.
     */
    fun enableResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.enableResource(ResourceId(resourceId))) {
                is Outcome.Success -> refreshCapabilities()
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    /**
     * Disable a materialized resource (lifecycle → DISABLED).
     */
    fun disableResource(resourceId: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.disableResource(ResourceId(resourceId))) {
                is Outcome.Success -> refreshCapabilities()
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.deleteProvider(id)) {
                is Outcome.Success -> refreshCapabilities()
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> refreshCapabilities()
            }
        }
    }

    fun toggleProvider(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.toggleProvider(id, isEnabled)) {
                is Outcome.Success -> refreshCapabilities()
                is Outcome.Error -> _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> refreshCapabilities()
            }
        }
    }

    fun openAddProviderDialog() {
        _uiState.update { it.copy(isAddProviderDialogOpen = true) }
    }

    fun closeAddProviderDialog() {
        _uiState.update { it.copy(isAddProviderDialogOpen = false) }
    }

    // ------------------------------------------------------------------
    // FIX F-4 (audit c03919d): credential input dialog — a real user path to
    // store an API key for a service configuration. Previously there was NO
    // way to enter a key (the flag existed but nothing read it), so every
    // remote provider stayed unusable.
    // ------------------------------------------------------------------

    fun openCredentialDialog(serviceId: String, serviceName: String, authAlias: String?) {
        _uiState.update {
            it.copy(
                credentialDialogServiceId = serviceId,
                credentialDialogServiceName = serviceName,
                credentialDialogAuthAlias = authAlias,
                credentialInput = ""
            )
        }
    }

    fun updateCredentialInput(value: String) {
        _uiState.update { it.copy(credentialInput = value) }
    }

    fun closeCredentialDialog() {
        _uiState.update {
            it.copy(
                credentialDialogServiceId = null,
                credentialDialogServiceName = "",
                credentialDialogAuthAlias = null,
                credentialInput = "",
                isSavingCredential = false
            )
        }
    }

    /**
     * Stores the entered secret under the service's authAlias (or the service
     * id as the storage key) and immediately runs a real connection test so
     * the user gets honest feedback that the key works.
     */
    fun submitCredential() {
        val state = _uiState.value
        val serviceId = state.credentialDialogServiceId ?: return
        val authAlias = state.credentialDialogAuthAlias ?: serviceId
        val secret = state.credentialInput.trim()
        if (secret.isEmpty()) return

        _uiState.update { it.copy(isSavingCredential = true) }
        viewModelScope.launch {
            when (val outcome = providerControlPlaneService.storeSecret(authAlias, secret)) {
                is Outcome.Success -> {
                    _uiState.update {
                        it.copy(
                            isSavingCredential = false,
                            diagnosticBanner = "تم حفظ المفتاح بنجاح. جاري التحقق من الاتصال..."
                        )
                    }
                    closeCredentialDialog()
                    // Explicit validation right after storing the key —
                    // honest feedback instead of silent "saved".
                    val config = providerControlPlaneService.getCurrentConfigurationForService(serviceId)
                    if (config != null) {
                        testServiceConnection(config.id)
                    }
                }
                is Outcome.Error -> {
                    _uiState.update {
                        it.copy(isSavingCredential = false, errorMessage = outcome.diagnosticMessage)
                    }
                }
                else -> _uiState.update { it.copy(isSavingCredential = false) }
            }
        }
    }

    fun clearProviderTestResult() {
        _uiState.update { it.copy(testingProviderId = null) }
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
        viewModelScope.launch {
            // FIX F-10: surface the governance gate's verdict honestly instead
            // of silently ignoring a rejected promotion.
            when (val outcome = intelligenceRadarPipeline.advanceEvolutionStage(candidateId, nextStage)) {
                is Outcome.Success -> {
                    _uiState.update {
                        it.copy(diagnosticBanner = "تمت ترقية المرشح إلى ${nextStage.displayName}.")
                    }
                }
                is Outcome.Error -> {
                    _uiState.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                }
                else -> Unit
            }
        }
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
            // FIX P0-8 (audit c03919d): sanitize the title so it cannot inject
            // path separators into the workspace:// source URI.
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ragPipelineService.ingestDocument(safeTitle, content, "workspace://docs/$safeTitle.md")
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

    /**
     * FIX P0-5 (audit c03919d): proper editor close. Previously the editor's
     * back button called openFile("") which attempted to READ a file with an
     * empty relative path; now the editor state is cleared directly.
     */
    fun closeFileEditor() {
        _uiState.update { it.copy(selectedFilePath = null, selectedFileContent = null) }
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
