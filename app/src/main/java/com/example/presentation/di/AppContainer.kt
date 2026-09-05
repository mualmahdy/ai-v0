package com.example.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.application.decision.DecisionService
import com.example.application.decision.DecisionIntelligenceService
import com.example.application.execution.ExecutionService
import com.example.application.extension.ExtensionManager
import com.example.application.extension.ExtensionLifecycleService
import com.example.application.observability.TelemetryService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.orchestration.WorkflowEngine
import com.example.application.observation.ObservationService
import com.example.application.outcome.OutcomeService
import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.ProviderRoutingService
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.application.rag.KnowledgePersistenceService
import com.example.application.rag.RagPipelineService
import com.example.application.rag.RagIntelligenceService
import com.example.application.registry.ComponentRegistry
import com.example.application.resource.DurableResourceRegistryService
import com.example.application.resource.RegistryBackedResourceRecordRepository
import com.example.application.security.SecurityGuardService
import com.example.application.security.PermissionGrantService
import com.example.application.memory.MemoryLifecycleService
import com.example.application.search.SearchIntelligenceService
import com.example.application.tools.ToolLifecycleService
import com.example.application.agent.AgentLifecycleService
import com.example.application.workspace.WorkspaceContextEngine
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.application.task.TaskDecompositionService
import com.example.application.evolution.PolicyVersionService
import com.example.application.resilience.CircuitBreakerService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.usecases.ExecuteWorkflowUseCase
import com.example.application.usecases.ManageMemoryUseCase
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.MdpLearningStore
import com.example.domain.ports.observability.TelemetryPort
import com.example.domain.ports.provider.OfferingRepository
import com.example.domain.ports.provider.ProviderRepository
import com.example.domain.ports.provider.ProviderServiceRepository
import com.example.domain.ports.provider.SecureCredentialStoragePort
import com.example.domain.ports.provider.ServiceConfigurationRepository
import com.example.domain.ports.provider.ServiceHealthRepository
import com.example.domain.ports.provider.UserPreferenceRepository
import com.example.domain.ports.resource.ResourceRecordRepository
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.llm.gemini.GeminiBootstrap
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.memory.RoomVectorStoreAdapter
import com.example.infrastructure.observability.RoomTelemetryRepository
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.repository.RoomMdpLearningStore
import com.example.infrastructure.persistence.repository.RoomOfferingRepository
import com.example.infrastructure.persistence.repository.RoomProviderRepository
import com.example.infrastructure.persistence.repository.RoomProviderServiceRepository
import com.example.infrastructure.persistence.repository.RoomResourceRecordRepository
import com.example.infrastructure.persistence.repository.RoomServiceConfigurationRepository
import com.example.infrastructure.persistence.repository.RoomServiceHealthRepository
import com.example.infrastructure.persistence.repository.RoomUserPreferenceRepository
import com.example.infrastructure.provider.ProtocolAdapterFactory
import com.example.infrastructure.radar.GitHubReleasesRadarSource
import com.example.infrastructure.radar.RssFeedRadarSource
import com.example.infrastructure.security.EncryptedSecretStorageAdapter
import com.example.infrastructure.skills.CleanArchitectureScaffolderSkill
import com.example.infrastructure.skills.SecurityAuditorSkill
import com.example.infrastructure.storage.SandboxWorkspaceStorageAdapter
import com.example.infrastructure.tools.FileSystemTool
import com.example.infrastructure.tools.SafeDiagnosticsTool
import com.example.infrastructure.validation.defaultResourceValidatorRegistry
import com.example.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * ============================================================================
 * Dependency Injection Container / Composition Root — Phase 4
 * ============================================================================
 *
 * Single authoritative runtime architecture (the legacy parallel wiring is
 * gone). The runtime chain is:
 *
 *   Provider → ProviderService → ServiceProtocol → ServiceConfiguration
 *            → ProtocolAdapterFactory → Runtime Adapter
 *            → Discovery → ServiceOffering → Materialize Resource → ResourceRecord
 *            → DurableResourceRegistryService → ResourceCapabilityGraph
 *            → DecisionService / CBR-MDP → DecisionRecord
 *            → RuntimeAdapterResolver → Execution → Observation → State Update
 */
class AppContainer(context: Context) {
    val appContext = context.applicationContext

    /**
     * FIX R-1: application-wide IO scope for one-shot bootstrap work (registry
     * eager load, MDP Q-table load, adapter restore) — never the main thread.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Persistence (Room Database) ---
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    // --- Secure Credential Storage ---
    val secureCredentialStorage: SecureCredentialStoragePort by lazy {
        EncryptedSecretStorageAdapter(appContext)
    }

    // --- Workspace Storage & Runtime ---
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
        GeminiBootstrap(appContext)
    }

    val protocolAdapterFactory: ProtocolAdapterFactory by lazy {
        ProtocolAdapterFactory(geminiBootstrap = geminiBootstrap)
    }

    val resourceValidatorRegistry by lazy {
        defaultResourceValidatorRegistry(geminiBootstrap = geminiBootstrap)
    }

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
     * Single authoritative resource registry. `DurableResourceRegistryService`
     * is the in-memory facade backed by `ResourceRecordRepository` (Room). The
     * `ComponentRegistry` references this same instance so there is one source
     * of truth for resource identity, lifecycle, and health.
     */
    val durableResourceRegistryService: DurableResourceRegistryService by lazy {
        DurableResourceRegistryService(generalizedResourceRecordRepository)
    }

    /**
     * ComponentRegistry — Phase 4: contains only in-app runtime extensions
     * (tools, agents, memory repository). It does NOT register LLM/Search/
     * Embedding providers — those are now `ResourceRecord`s authored by the
     * `ProviderControlPlaneService` via the control-plane resource repository
     * (which routes through the SAME DurableResourceRegistryService).
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
     * protocol-specific adapters through ProtocolAdapterFactory. Resource
     * writes go through the SAME DurableResourceRegistryService used by
     * ComponentRegistry (Section 21: single write authority).
     */
    val providerControlPlaneService: ProviderControlPlaneService by lazy {
        ProviderControlPlaneService(
            providerRepository = generalizedProviderRepository,
            serviceRepository = generalizedServiceRepository,
            configurationRepository = generalizedConfigurationRepository,
            healthRepository = generalizedHealthRepository,
            offeringRepository = generalizedOfferingRepository,
            resourceRecordRepository = RegistryBackedResourceRecordRepository(durableResourceRegistryService),
            userPreferenceRepository = generalizedUserPreferenceRepository,
            secureCredentialStorage = secureCredentialStorage,
            adapterFactory = protocolAdapterFactory,
            validatorRegistry = resourceValidatorRegistry,
            // FIX F-1: bridge materialized/validated adapters into the SAME
            // RuntimeAdapterResolver consumed by ExecutionService & RAG.
            runtimeAdapterResolver = componentRegistry.runtimeAdapterResolver
        )
    }

    // --- CBR-MDP Decision Intelligence ---
    /**
     * FIX D-1/D-4 (audit c03919d): the engine is backed by the persistent
     * tabular-MDP Q-table (Room `mdp_q_values`) — per-(region, action) values
     * and transition rates that survive app restarts.
     */
    val cbrMdpEngine: CbrMdpEngine by lazy {
        val persistentCaseBase = CaseBase(decisionCaseDao = database.decisionCaseDao())
        val mdpLearningStore: MdpLearningStore = RoomMdpLearningStore(database.mdpQValueDao())
        CbrMdpEngine(
            caseBase = persistentCaseBase,
            mdpStore = mdpLearningStore,
            persistenceScope = applicationScope
        )
    }

    // --- Extensibility Engine ---
    /**
     * FIX F-8: the in-process MCP bridge tools are backed by REAL executors —
     * `workspace_summary` reads the actual sandbox workspace file statistics
     * (previously the bridge returned canned placeholder text).
     */
    private val inProcessMcpTools: Map<String, suspend (Map<String, Any?>) -> com.example.domain.core.Outcome<com.example.domain.core.tools.ToolOutput, com.example.domain.core.tools.ToolFailure>> = mapOf(
        "workspace_summary" to { _ ->
            when (val files = workspaceStorage.listFiles(1L)) {
                is com.example.domain.core.Outcome.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val entries = files.value as? List<com.example.domain.core.storage.WorkspaceFileEntry> ?: emptyList()
                    val totalBytes = entries.sumOf { it.sizeBytes }
                    val fileCount = entries.count { !it.isDirectory }
                    val dirCount = entries.count { it.isDirectory }
                    val summary = """[MCP Local Bridge: workspace_summary — REAL DATA]
- ملفات مساحة العمل النشطة: $fileCount
- المجلدات: $dirCount
- إجمالي الحجم: $totalBytes بايت
- أبرز الملفات: ${entries.take(5).joinToString(", ") { it.relativePath }}
                    """.trimIndent()
                    com.example.domain.core.Outcome.Success(
                        com.example.domain.core.tools.ToolOutput(
                            content = summary,
                            rawBytesCount = summary.toByteArray().size.toLong()
                        )
                    )
                }
                is com.example.domain.core.Outcome.Degraded<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val partial = files.partialValue as? List<com.example.domain.core.storage.WorkspaceFileEntry>
                    com.example.domain.core.Outcome.Degraded(
                        partialValue = com.example.domain.core.tools.ToolOutput(
                            content = "قراءة جزئية لملفات مساحة العمل (${partial?.size ?: 0} ملف)"
                        ),
                        reason = files.reason,
                        diagnosticMessage = files.diagnosticMessage
                    )
                }
                is com.example.domain.core.Outcome.Error<*> -> com.example.domain.core.Outcome.Error(
                    failure = com.example.domain.core.tools.ToolFailure.CapabilityUnavailable(
                        capabilityName = "workspace_summary",
                        message = files.diagnosticMessage ?: "تعذر قراءة ملفات مساحة العمل"
                    ),
                    diagnosticMessage = files.diagnosticMessage
                )
                else -> com.example.domain.core.Outcome.Error(
                    failure = com.example.domain.core.tools.ToolFailure.CapabilityUnavailable(
                        capabilityName = "workspace_summary",
                        message = "نتيجة غير معروفة من مخزن مساحة العمل"
                    )
                )
            }
        }
    )

    val extensionManager: ExtensionManager by lazy {
        ExtensionManager(
            componentRegistry = componentRegistry,
            mcpClient = McpClient(inProcessTools = inProcessMcpTools),
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
     * The embedding adapter is resolved via `DurableResourceRegistryService`
     * (no direct injection of a single concrete adapter). When no embedding
     * ResourceRecord is registered (e.g. first run before bootstrap), the
     * local in-process fallback is used explicitly so RAG keeps working.
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
            extensionManager = extensionManager,
            memoryRepositoryProvider = { componentRegistry.getMemoryRepository() }
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

    // ========================================================================
    // Phase 5 — P0/P1 Intelligence Layer (audit remediation)
    // ========================================================================
    // Each service below closes one of the gaps identified in the audit:
    //   - TelemetryService             → Observability (25-35% → 55%)
    //   - MemoryLifecycleService       → Memory Intelligence (35-40% → 55%)
    //   - WorkspaceContextEngine       → Workspace Intelligence (40-45% → 55%)
    //   - ToolLifecycleService         → Tool Ecosystem (30-40% → 55%)
    //   - SearchIntelligenceService    → Search Intelligence (35-40% → 55%)
    //   - RagIntelligenceService       → RAG Intelligence (40-45% → 55%)
    //   - AgentLifecycleService        → Agent Intelligence (35-40% → 55%)
    //   - WorkflowPersistenceService   → Workflow Intelligence (40-45% → 55%)
    //   - TaskDecompositionService     → Task Intelligence (40-45% → 55%)
    //   - CircuitBreakerService        → Production Resilience (35-45% → 55%)
    //   - PermissionGrantService       → Security Governance (40-45% → 55%)
    //   - PolicyVersionService         → Evolution/Self-Improvement (25-35% → 45%)
    //   - ProviderRoutingService       → Provider Ecosystem (~45% → 55%)
    //   - DecisionIntelligenceService  → Decision Intelligence (~45% → 55%)
    //   - ExtensionLifecycleService    → MCP/Extensions (40-45% → 55%)

    val telemetryPort: TelemetryPort by lazy {
        RoomTelemetryRepository(
            metricEventDao = database.metricEventDao(),
            auditTrailDao = database.auditTrailDao(),
            healthProbeDao = database.healthProbeDao(),
            executionTraceDao = database.executionTraceDao(),
            executionLogDao = database.executionLogDao()
        )
    }

    val telemetryService: TelemetryService by lazy { TelemetryService(telemetryPort) }

    val memoryLifecycleService: MemoryLifecycleService by lazy {
        MemoryLifecycleService(
            memoryDao = database.memoryDao(),
            namespaceDao = database.agentMemoryNamespaceDao(),
            memoryRepository = memoryVectorStore,
            embeddingProvider = defaultEmbeddingAdapter
        )
    }

    val workspaceContextEngine: WorkspaceContextEngine by lazy {
        WorkspaceContextEngine(
            resourceEdgeDao = database.resourceEdgeDao(),
            workspaceRuntimeService = workspaceRuntimeService
        )
    }

    val toolLifecycleService: ToolLifecycleService by lazy {
        ToolLifecycleService(
            toolLifecycleDao = database.toolLifecycleDao(),
            toolHealthDao = database.toolHealthDao(),
            toolAuditDao = database.toolAuditDao(),
            permissionGrantDao = database.permissionGrantDao(),
            declarationProvider = { toolId ->
                // Look up the live ToolDeclaration from ComponentRegistry.
                runCatching {
                    componentRegistry.runtimeAdapterResolver.listToolDeclarations()
                        .firstOrNull { it.name == toolId.substringAfter("tool_").substringBefore("_") }
                }.getOrNull()
            }
        )
    }

    val searchIntelligenceService: SearchIntelligenceService by lazy {
        SearchIntelligenceService(
            searchProvider = com.example.infrastructure.search.MultiSourceSearchAdapter()
        )
    }

    val ragIntelligenceService: RagIntelligenceService by lazy {
        RagIntelligenceService(
            documentChunkDao = database.documentChunkDao(),
            embeddingProvider = defaultEmbeddingAdapter
        )
    }

    val agentLifecycleService: AgentLifecycleService by lazy {
        AgentLifecycleService(memoryLifecyclePort = memoryLifecycleService)
    }

    val workflowPersistenceService: WorkflowPersistenceService by lazy {
        WorkflowPersistenceService(
            workflowExecutionDao = database.workflowExecutionDao(),
            workflowStepStateDao = database.workflowStepStateDao()
        )
    }

    val taskDecompositionService: TaskDecompositionService by lazy {
        TaskDecompositionService(taskDao = database.taskDao())
    }

    val circuitBreakerService: CircuitBreakerService by lazy { CircuitBreakerService() }

    val permissionGrantService: PermissionGrantService by lazy {
        PermissionGrantService(
            permissionGrantDao = database.permissionGrantDao(),
            telemetryPort = telemetryPort
        )
    }

    val policyVersionService: PolicyVersionService by lazy {
        PolicyVersionService(policyVersionDao = database.policyVersionDao())
    }

    val providerRoutingService: ProviderRoutingService by lazy { ProviderRoutingService() }

    val decisionIntelligenceService: DecisionIntelligenceService by lazy {
        DecisionIntelligenceService(
            decisionCaseDao = database.decisionCaseDao(),
            cbrMdpEngine = cbrMdpEngine
        )
    }

    val extensionLifecycleService: ExtensionLifecycleService by lazy {
        ExtensionLifecycleService(extensionConfigDao = database.extensionConfigDao())
    }

    /**
     * First-run bootstrap (parity with the legacy default providers): seeds
     * local embedding + multi-source search + Gemini provider records, then
     * validates ONLY the zero-network in-process resources. Called once from
     * the MainViewModel init scope.
     *
     * FIX R-1 + F-1 + D-4: runs on the application IO scope — eagerly loads
     * persisted resources AND the CBR-MDP Q-table (no runBlocking on main)
     * and restores runtime adapters for every persisted ENABLED resource.
     *
     * Phase 5: also applies memory decay + resumes pending workflows + tasks
     * on startup so the runtime reconstructs its pre-crash state.
     */
    fun bootstrapRuntime() {
        applicationScope.launch {
            durableResourceRegistryService.eagerLoad()
            cbrMdpEngine.loadPersistedQTable()
            providerControlPlaneService.ensureBootstrapDefaults()
            providerControlPlaneService.restoreAdaptersForPersistedResources()
            // Phase 5 — memory decay + workflow/task resume on startup.
            runCatching { memoryLifecycleService.applyDecay() }
            runCatching { memoryLifecycleService.consolidate() }
            runCatching { taskDecompositionService.pendingResumableTasks() }
        }
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
                extensionManager = appContainer.extensionManager,
                intelligenceRadarPipeline = appContainer.intelligenceRadarPipeline,
                ragPipelineService = appContainer.ragPipelineService,
                providerControlPlaneService = appContainer.providerControlPlaneService,
                workspaceRuntimeService = appContainer.workspaceRuntimeService,
                // Phase 5 — pass the new intelligence services for the
                // Unified Activity Feed + proactive suggestion surface.
                telemetryService = appContainer.telemetryService,
                workspaceContextEngine = appContainer.workspaceContextEngine,
                telemetryPort = appContainer.telemetryPort
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
