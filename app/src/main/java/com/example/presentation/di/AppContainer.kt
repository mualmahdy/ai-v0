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
import com.example.application.governed.AdmissionControlService
import com.example.application.governed.CodingToolchainService
import com.example.application.governed.HumanApprovalGate
import com.example.application.governed.SandboxLifecycleService
import com.example.application.governed.BudgetAuthorizationPort
import com.example.application.governed.BudgetAuthorizationOutcome
import com.example.application.governed.ToolDeclarationResolver
import com.example.domain.core.runtime.IsolationLevel
import com.example.domain.core.security.governance.BudgetAuthorizationVerdict
import com.example.domain.core.security.governance.ToolAdmissionRequest
import com.example.domain.ports.governed.AdmissionAuditPort
import com.example.domain.ports.governed.HumanApprovalStorePort
import com.example.domain.ports.governed.PrincipalAuthorizationPort
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.AuditEvent
import com.example.infrastructure.governed.InMemoryHumanApprovalStore
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

    /**
     * Real connectivity monitor (audit 2026 fix) — single source of truth for
     * `isNetworkAvailable` consumed by the orchestrator via the ViewModel.
     */
    val networkMonitor: com.example.infrastructure.network.NetworkMonitor by lazy {
        com.example.infrastructure.network.NetworkMonitor(appContext).also { it.start() }
    }

    // --- Persistence (Room Database) ---
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    // --- Secure Credential Storage ---
    val secureCredentialStorage: SecureCredentialStoragePort by lazy {
        EncryptedSecretStorageAdapter(appContext)
    }

    // --- Workspace Storage & Runtime ---
    val workspaceStorage: SandboxWorkspaceStorageAdapter by lazy {
        // P0 CONVERGENCE: the adapter is a pure file-system port. The legacy
        // SessionRepositoryPort implementation (project-scoped, zero production
        // callers, implicit 1L bootstrap) was removed with the sessions table.
        SandboxWorkspaceStorageAdapter(context = appContext)
    }

    val workspaceRuntimeService: WorkspaceRuntimeService by lazy {
        WorkspaceRuntimeService(
            workspaceDao = database.workspaceDao(),
            // P0-04: every new workspace gets its OWN sandbox project row.
            projectDao = database.projectDao(),
            projectRootPathResolver = { projectId ->
                java.io.File(appContext.filesDir, "workspaces/proj_$projectId").absolutePath
            },
            // REPAIR ORDER §3A — the bootstrap STATE MACHINE owns startup:
            // transactional workspace+project creation, deterministic
            // restoration, stale-reference reconciliation, observable phases.
            bootstrapOrchestrator = workspaceBootstrapOrchestrator,
            // REPAIR ORDER §5 — project lifecycle operations delegate to the
            // dedicated runtime (this service stays a workspace-scope runtime).
            projectRuntime = projectRuntimeService
        )
    }

    // ------------------------------------------------------------------
    // REPAIR ORDER §3A/§5/§7/§9-§14/§24-§30 — NEW PORTABILITY SUBSYSTEM
    // ------------------------------------------------------------------

    /** §30 — unified audit trail (typed writer, secret-redacting). */
    val auditTrailService: com.example.application.audit.AuditTrailService by lazy {
        com.example.application.audit.AuditTrailService(database = database)
    }

    /** Shared sandbox file engine (containment-checked §7/§9/§11). */
    private val sandboxProjectsDir: java.io.File by lazy {
        java.io.File(appContext.filesDir, "workspaces").apply { mkdirs() }
    }
    val sandboxFileStore: com.example.infrastructure.storage.SandboxProjectFileStore by lazy {
        com.example.infrastructure.storage.SandboxProjectFileStore(baseProjectsDir = sandboxProjectsDir)
    }

    /** §3A — bootstrap state machine (BOOTSTRAPPING → … → READY). */
    val workspaceBootstrapOrchestrator: com.example.application.bootstrap.WorkspaceBootstrapOrchestrator by lazy {
        com.example.application.bootstrap.WorkspaceBootstrapOrchestrator(
            database = database,
            projectRootResolver = { projectId ->
                java.io.File(sandboxProjectsDir, "proj_$projectId")
            }
        )
    }

    /** §5/§27 — project lifecycle runtime (multi-project management). */
    val projectRuntimeService: com.example.application.project.ProjectRuntimeService by lazy {
        com.example.application.project.ProjectRuntimeService(
            database = database,
            projectRootResolver = { projectId ->
                java.io.File(sandboxProjectsDir, "proj_$projectId")
            },
            auditTrail = auditTrailService
        )
    }

    /** §24/§25 — project readiness + dependency resolution. */
    val projectReadinessService: com.example.application.project.ProjectReadinessService by lazy {
        com.example.application.project.ProjectReadinessService(
            database = database,
            fileStore = sandboxFileStore
        )
    }

    /** §7/§8/§29 — unified artifact / resource library. */
    val artifactService: com.example.application.artifacts.ArtifactService by lazy {
        com.example.application.artifacts.ArtifactService(
            database = database,
            fileStore = sandboxFileStore,
            auditTrail = auditTrailService
        )
    }

    /** §9 — file/folder import/export (SAF streams, safety, staging+atomic). */
    val fileTransferService: com.example.application.transfer.FileTransferService by lazy {
        com.example.application.transfer.FileTransferService(
            fileStore = sandboxFileStore,
            auditTrail = auditTrailService
        )
    }

    /** §10-§12 — versioned portable project packages + clone/move. */
    val projectPackageService: com.example.application.transfer.ProjectPackageService by lazy {
        com.example.application.transfer.ProjectPackageService(
            database = database,
            fileStore = sandboxFileStore,
            auditTrail = auditTrailService
        )
    }

    /** §14 — session transcript export (canonical → TXT/MD/JSON). */
    val sessionExportService: com.example.application.transfer.SessionExportService by lazy {
        com.example.application.transfer.SessionExportService(database = database)
    }

    /** §28 — repair / reconciliation center (DETECT → EXPLAIN → REPAIR → VERIFY). */
    val repairCenterService: com.example.application.repair.RepairCenterService by lazy {
        com.example.application.repair.RepairCenterService(
            database = database,
            fileStore = sandboxFileStore,
            auditTrail = auditTrailService
        )
    }

    /** §4/§6/§8 — centralized scope resolution + access enforcement. */
    val contextResolverService: com.example.application.context.ContextResolverService by lazy {
        com.example.application.context.ContextResolverService(
            database = database,
            activeWorkspaceIdProvider = { workspaceRuntimeService.activeWorkspaceIdOrNull() }
        )
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

    /**
     * REAL on-device semantic embedding (audit 2026 fix): a trained
     * sentence-transformer (MiniLM-L6, int8 ONNX) run locally via ONNX
     * Runtime. The model is provisioned lazily (one-time ~23MB download);
     * before provisioning the adapter reports honest unavailability and the
     * router falls back to the lexical adapter (honestly labeled).
     */
    val onnxSemanticEmbeddingAdapter: com.example.infrastructure.memory.semantic.OnnxSemanticEmbeddingAdapter by lazy {
        com.example.infrastructure.memory.semantic.OnnxSemanticEmbeddingAdapter(appContext)
    }

    /**
     * Single local embedding resource: routes to the ONNX semantic model when
     * provisioned, otherwise to the lexical fallback with honest labeling.
     */
    val localEmbeddingRouter: com.example.infrastructure.memory.semantic.LocalSemanticEmbeddingRouter by lazy {
        com.example.infrastructure.memory.semantic.LocalSemanticEmbeddingRouter(
            semanticAdapter = onnxSemanticEmbeddingAdapter,
            lexicalFallback = defaultEmbeddingAdapter
        )
    }

    val memoryVectorStore: RoomVectorStoreAdapter by lazy {
        RoomVectorStoreAdapter(
            memoryDao = database.memoryDao(),
            embeddingProvider = localEmbeddingRouter,
            // GOVERNANCE PHASE: workspace-scoped memory writes AND reads
            // (fixes the cross-workspace memory leak — see adapter KDoc).
            // GAP-CLOSURE P0-03: null = honestly UNATTRIBUTED (never a
            // fabricated "default" id).
            workspaceIdProvider = { workspaceRuntimeService.activeWorkspaceIdOrNull() }
        )
    }

    val securityGuardService: SecurityGuardService by lazy { SecurityGuardService() }

    // --- Concrete Tools (in-app extensions) ---
    val fileSystemTool: FileSystemTool by lazy {
        // P0-04: agent-driven file operations target the ACTIVE workspace's
        // OWN project — never the legacy shared project 1L.
        FileSystemTool(
            storagePort = workspaceStorage,
            projectIdProvider = { workspaceRuntimeService.activeProjectIdOrNull() }
        )
    }

    val safeDiagnosticsTool: SafeDiagnosticsTool by lazy {
        val sandboxDir = File(appContext.filesDir, "workspaces").apply { if (!exists()) mkdirs() }
        SafeDiagnosticsTool(sandboxDir = sandboxDir)
    }

    // --- Executable Skills ---
    val cleanArchitectureSkill by lazy {
        CleanArchitectureScaffolderSkill(
            storagePort = workspaceStorage,
            projectIdProvider = { workspaceRuntimeService.activeProjectIdOrNull() }
        )
    }

    val securityAuditorSkill by lazy { SecurityAuditorSkill() }

    // ========================================================================
    // Phase 4 — Generalized Provider Architecture (single authoritative path)
    // ========================================================================

    val geminiBootstrap: GeminiBootstrap by lazy {
        GeminiBootstrap(appContext)
    }

    /**
     * EGRESS CONTROL (defect family 1 — scoped, fail-closed): the ONE
     * composition-root-owned transport guard shared by EVERY outbound
     * adapter (LLM, embeddings, search, MCP, discovery). It keeps a
     * per-workspace policy REGISTRY (an execution pinned to workspace A
     * keeps A's policy even after the user switches the active workspace),
     * evaluates requests against their OWN execution scope, and FAILS
     * CLOSED when the governing workspace has no pinned policy.
     */
    val egressControl: com.example.infrastructure.network.EgressControl by lazy {
        com.example.infrastructure.network.EgressControl()
    }

    val protocolAdapterFactory: ProtocolAdapterFactory by lazy {
        ProtocolAdapterFactory(
            geminiBootstrap = geminiBootstrap,
            egressControl = egressControl
        )
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
     * (tools, memory repository). It does NOT register LLM/Search/
     * Embedding providers — those are now `ResourceRecord`s authored by the
     * `ProviderControlPlaneService` via the control-plane resource repository
     * (which routes through the SAME DurableResourceRegistryService).
     *
     * GAP-CLOSURE P1-08: agents are NOT hardcoded here anymore — the
     * canonical durable [agentRegistryService] seeds and syncs them into
     * this registry (same store feeds the UI catalog AND the runtime).
     */
    val componentRegistry: ComponentRegistry by lazy {
        ComponentRegistry(durableResourceRegistryService).apply {
            registerMemoryRepository(memoryVectorStore)
            registerTool(fileSystemTool)
            registerTool(safeDiagnosticsTool)
        }
    }

    /**
     * GAP-CLOSURE P1-08/P1-09/P1-10: the CANONICAL durable agent registry
     * (Room `agent_definitions`). One source of agent truth — the same store
     * feeds the UI catalog and the runtime ComponentRegistry.
     */
    val agentRegistryService: com.example.application.agent.AgentRegistryService by lazy {
        com.example.application.agent.AgentRegistryService(database.agentDefinitionDao())
    }

    /** Loads (or seeds) the canonical agent catalog into the runtime registry. */
    private suspend fun syncCanonicalAgents() {
        agentRegistryService.ensureSeeded(canonicalDefaultAgents)
        agentRegistryService.syncInto(componentRegistry)
    }

    /**
     * Canonical default catalog (P1-08) — defined ONCE in
     * [com.example.application.agent.CanonicalAgentCatalog] and shared by the
     * durable seed, the runtime registry and the ViewModel cold-start fallback.
     */
    val canonicalDefaultAgents: List<com.example.domain.core.agent.AgentDefinition>
        get() = com.example.application.agent.CanonicalAgentCatalog.defaults

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
            egressControl = egressControl,
            // FIX F-1: bridge materialized/validated adapters into the SAME
            // RuntimeAdapterResolver consumed by ExecutionService & RAG.
            runtimeAdapterResolver = componentRegistry.runtimeAdapterResolver
        ).also { controlPlane ->
            // GOVERNANCE PHASE: provider/resource lifecycle transitions emit
            // REAL radar evidence through the late-bound sink (no second bus).
            controlPlane.radarEvidenceSink = { evidence ->
                capabilityRadarService.recordEvidence(evidence)
            }
            // GOVERNANCE PHASE: offering pricing discovered by the control
            // plane becomes versioned MODEL-scope pricing entries.
            controlPlane.pricingPublisher = { entry ->
                applicationScope.launch {
                    runCatching { economicGovernanceService.upsertPricing(entry) }
                }
            }
        }
    }

    // --- CBR-MDP Decision Intelligence ---
    /**
     * FIX D-1/D-4 (audit c03919d): the engine is backed by the persistent
     * tabular-MDP Q-table (Room `mdp_q_values`) — per-(region, action) values
     * and transition rates that survive app restarts.
     *
     * ARCHITECTURE BOUNDARY FIX (report: "domain → infrastructure violation"):
     * the CaseBase no longer takes a Room DAO directly; persistence flows
     * through the domain-owned [com.example.domain.core.decision.DecisionCaseStore]
     * port, implemented by RoomDecisionCaseStore in infrastructure. Persistence
     * failures inside the case base are COUNTED (honest accounting), never
     * silently swallowed.
     */
    val cbrMdpEngine: CbrMdpEngine by lazy {
        val decisionCaseStore: com.example.domain.core.decision.DecisionCaseStore =
            com.example.infrastructure.persistence.repository.RoomDecisionCaseStore(
                database.decisionCaseDao()
            )
        val persistentCaseBase = CaseBase(
            store = decisionCaseStore,
            persistenceScope = applicationScope
        )
        val mdpLearningStore: MdpLearningStore = RoomMdpLearningStore(database.mdpQValueDao())
        CbrMdpEngine(
            caseBase = persistentCaseBase,
            mdpStore = mdpLearningStore,
            persistenceScope = applicationScope
        )
    }

    // --- Extensibility Engine ---
    /**
     * FIX F-8 + P0 CONVERGENCE: the in-process MCP bridge tools are backed
     * by REAL executors — `workspace_summary` reads the ACTIVE workspace's
     * OWN sandbox statistics. Previously the bridge hard-read the LEGACY
     * shared project 1L (cross-workspace data bleed — audit step 12 §6);
     * now the project is resolved from the active workspace (pinned
     * execution scope first), and the tool fails HONESTLY when no project
     * is bound instead of reporting another workspace's files.
     */
    private val inProcessMcpTools: Map<String, suspend (Map<String, Any?>) -> com.example.domain.core.Outcome<com.example.domain.core.tools.ToolOutput, com.example.domain.core.tools.ToolFailure>> = mapOf(
        "workspace_summary" to { _ ->
            val mcpProjectId = kotlin.coroutines.coroutineContext[
                com.example.domain.core.execution.ExecutionScope.Key
            ]?.projectId?.takeIf { it > 0 }
                ?: workspaceRuntimeService.activeProjectIdOrNull()
            if (mcpProjectId == null) {
                // Fail honestly: no project bound to the active workspace —
                // NEVER fall back to the legacy shared project 1L.
                com.example.domain.core.Outcome.Error(
                    failure = com.example.domain.core.tools.ToolFailure.CapabilityUnavailable(
                        capabilityName = "workspace_summary",
                        message = "PROJECT_CONTEXT_REQUIRED: لا يوجد مشروع مرتبط بمساحة العمل النشطة — يرفض الجسر المحلي قراءة ملفات مشروع مشترك قديم."
                    ),
                    diagnosticMessage = "PROJECT_CONTEXT_REQUIRED"
                )
            } else when (val files = workspaceStorage.listFiles(mcpProjectId)) {
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
            mcpClient = McpClient(
                egressControl = egressControl,
                inProcessTools = inProcessMcpTools
            ),
            // P1-15 (audit 2026 §28): ONE egress-controlled IntegrationGateway
            // for the whole process (previously this constructed an UNGOVERNED
            // OkHttpClient outside EgressControl, and ExtensionManager could
            // silently build a SECOND one).
            integrationGateway = IntegrationGateway(egressControl = egressControl),
            extensionConfigDao = database.extensionConfigDao(),
            executableSkills = listOf(cleanArchitectureSkill, securityAuditorSkill)
        )
    }

    // --- Intelligence Radar ---
    val intelligenceRadarPipeline: IntelligenceRadarPipeline by lazy {
        IntelligenceRadarPipeline(
            radarSources = listOf(GitHubReleasesRadarSource(), RssFeedRadarSource()),
            radarItemDao = database.radarItemDao(),
            evolutionCandidateDao = database.evolutionCandidateDao(),
            // GAP-CLOSURE P1-17 (MEASURE): a REGISTERED capability's
            // measurement lands in the SAME capability-radar evidence stream
            // every other runtime evidence flows through (one loop, one bus).
            measurementRecorder = { candidate ->
                capabilityRadarService.recordEvidence(
                    com.example.domain.core.radar.CapabilityEvidence(
                        id = java.util.UUID.randomUUID().toString(),
                        capabilityKey = "acquired:${candidate.targetType.lowercase()}:${candidate.id}",
                        source = com.example.domain.core.radar.EvidenceSource.OPERATOR_ACTION,
                        outcome = com.example.domain.core.radar.EvidenceOutcome.SUCCESS,
                        confidence = candidate.confidence,
                        detail = "قياس أساسي لقدرة مكتسبة: ${candidate.title} (${candidate.stage.name})",
                        timestampEpochMs = System.currentTimeMillis()
                    )
                )
            }
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
            fallbackEmbeddingProvider = localEmbeddingRouter,
            persistenceService = knowledgePersistenceService,
            workspaceIdProvider = { workspaceRuntimeService.requireActiveWorkspaceId() }
        )
    }

    // ========================================================================
    // Governance Phase — Capability Radar + Economic Budget (first-class
    // domain subsystems: persistence -> services -> runtime -> decision ->
    // telemetry -> workspace -> UI; the observatory screen is the LAST hop.)
    // ========================================================================

    /** Radar persistence (Room v10: evidence, states, changes, recommendations). */
    val capabilityRadarPersistence: com.example.domain.ports.radar.CapabilityRadarPersistencePort by lazy {
        com.example.infrastructure.persistence.radar.RoomCapabilityRadarStore(
            evidenceDao = database.capabilityEvidenceDao(),
            stateDao = database.radarCapabilityStateDao(),
            changeDao = database.capabilityChangeDao(),
            recommendationDao = database.radarRecommendationDao()
        )
    }

    /** Economic persistence (Room v10: pricing, cost ledger, allocations). */
    val economicPersistence: com.example.infrastructure.persistence.budget.RoomEconomicStore by lazy {
        com.example.infrastructure.persistence.budget.RoomEconomicStore(
            pricingDao = database.pricingEntryDao(),
            ledgerDao = database.costLedgerEntryDao(),
            allocationDao = database.budgetAllocationDao()
        )
    }

    /**
     * The operational Capability & Evolution Radar: evidence-derived states,
     * persisted, event-driven from the SAME execution bus telemetry uses.
     */
    val capabilityRadarService: com.example.application.radar.CapabilityRadarService by lazy {
        com.example.application.radar.CapabilityRadarService(
            persistence = capabilityRadarPersistence,
            resourceSnapshotProvider = { durableResourceRegistryService.listResources() },
            embeddingSemanticProvisioned = { onnxSemanticEmbeddingAdapter.isProvisioned },
            scope = applicationScope
        )
    }

    /** RPM/TPM governor (in-memory windows; limits from config/discovery). */
    val rateLimitGovernor: com.example.application.budget.RateLimitGovernor by lazy {
        com.example.application.budget.RateLimitGovernor()
    }

    /**
     * The economic governance facade: pricing resolution, cost ledger,
     * hierarchical budgets with enforceable policies, rate-limit gate,
     * pre-execution authorization and post-execution accounting.
     */
    val economicGovernanceService: com.example.application.budget.EconomicGovernanceService by lazy {
        com.example.application.budget.EconomicGovernanceService(
            pricingRepository = economicPersistence,
            costLedger = economicPersistence,
            allocationRepository = economicPersistence,
            rateLimitGovernor = rateLimitGovernor,
            telemetry = telemetryService,
            scope = applicationScope
        )
    }

    // --- GOVERNED RUNTIME (Phase 1: admission-controlled execution) ---

    /**
     * Honest host isolation assessment: Android in-app execution provides
     * ONLY the per-app sandbox (UID + app data dir). There is no process
     * boundary, no network firewall, no syscall filter — so code-execution
     * tools are DENIED at admission rather than executed with a false
     * isolation claim.
     */
    val sandboxLifecycleService: SandboxLifecycleService by lazy {
        SandboxLifecycleService(hostIsolationLevel = IsolationLevel.APP_SANDBOX_BEST_EFFORT)
    }

    /**
     * P1-15 / §17 FIX (audit 2026 — human approval was NOT durable): the
     * production approval store is now Room-backed (`human_approval_requests`,
     * v15) — approvals and one-shot tokens survive process death. The
     * volatile [InMemoryHumanApprovalStore] remains available for tests that
     * explicitly want volatile semantics; production wiring never uses it.
     */
    val humanApprovalStore: HumanApprovalStorePort by lazy {
        com.example.infrastructure.governed.RoomHumanApprovalStore(
            dao = database.humanApprovalRequestDao()
        )
    }

    val humanApprovalGate: HumanApprovalGate by lazy {
        HumanApprovalGate(store = humanApprovalStore)
    }

    val admissionAuditPort: AdmissionAuditPort by lazy {
        // Admission decisions flow into the SAME audit trail the rest of
        // the system uses (Room-backed via TelemetryService).
        AdmissionAuditPort { severity, actor, action, resourceType, resourceId, decision, reason, workspaceId, attributes ->
            telemetryService.recordAudit(
                AuditEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    severity = AuditSeverity.entries.firstOrNull { it.name == severity } ?: AuditSeverity.WARN,
                    actor = actor,
                    action = action,
                    resourceType = resourceType,
                    resourceId = resourceId,
                    decision = decision,
                    reason = reason,
                    workspaceId = workspaceId,
                    attributes = attributes
                )
            )
        }
    }

    /** Principal authorization via the Room-backed PermissionGrantService. */
    val principalAuthorizationPort: PrincipalAuthorizationPort by lazy {
        PrincipalAuthorizationPort { principalType, principalId, resourceType, resourceId, permission, workspaceId ->
            // P0-1 (audit 2026 §15 — Universal Admission): the principal
            // authorization stage follows the SAME policy the canonical
            // execution boundary applies — an explicit workspace-scoped (or
            // global) grant is REQUIRED for sensitive / consent-requiring
            // resources and fail-closes without one; ordinary non-sensitive
            // tools remain permitted for authenticated principals (the
            // security-ceiling, risk, budget, approval and sandbox stages of
            // the admission pipeline still gate them).
            val declaration = resolveToolDeclarationFor(resourceId)
            val sensitive = declaration?.isSensitive == true ||
                declaration?.requiresHumanConsent == true
            if (sensitive) {
                permissionGrantService.check(
                    principalType, principalId, resourceType, resourceId, permission, workspaceId
                )
            } else {
                true
            }
        }
    }

    /**
     * P0-1: single declaration lookup shared by the admission pipeline —
     * the governed coding toolchain's STATIC registry first (no service
    * construction — the companion exists precisely to avoid the circular
    * dependency), then the live runtime adapter declarations (in-app tools,
    * MCP bridge tools). NEVER null for a tool that is actually registered.
     */
    private fun resolveToolDeclarationFor(toolName: String): com.example.domain.core.tools.ToolDeclaration? {
        com.example.application.governed.CodingToolchainService.Companion.declarations[toolName]?.let { return it }
        return runCatching {
            componentRegistry.runtimeAdapterResolver.listToolDeclarations()
                .firstOrNull { it.name.equals(toolName, ignoreCase = true) }
        }.getOrNull()
    }

    /** Budget gate adapter over EconomicGovernanceService.authorize(). */
    val budgetAuthorizationPort: BudgetAuthorizationPort by lazy {
        BudgetAuthorizationPort { request: ToolAdmissionRequest ->
            val result = economicGovernanceService.authorize(
                com.example.domain.core.budget.EconomicAuthorizationRequest(
                    executionId = request.executionId,
                    workspaceId = request.workspaceId,
                    agentId = request.principalId,
                    taskId = null,
                    providerId = null,
                    serviceId = null,
                    modelId = null,
                    resourceId = null,
                    isLocalResource = true,
                    expectedTotalTokens = request.estimatedTokens,
                    actionTypeCode = "TOOL:${request.toolName}"
                )
            )
            BudgetAuthorizationOutcome(
                verdict = when (result.decision) {
                    com.example.domain.core.budget.EconomicGateDecision.ALLOWED -> BudgetAuthorizationVerdict.ALLOWED
                    com.example.domain.core.budget.EconomicGateDecision.WARNED -> BudgetAuthorizationVerdict.WARNED
                    com.example.domain.core.budget.EconomicGateDecision.DENIED -> BudgetAuthorizationVerdict.DENIED
                    com.example.domain.core.budget.EconomicGateDecision.DOWNGRADE -> BudgetAuthorizationVerdict.DOWNGRADE
                    com.example.domain.core.budget.EconomicGateDecision.LOCAL_FALLBACK -> BudgetAuthorizationVerdict.LOCAL_FALLBACK
                    com.example.domain.core.budget.EconomicGateDecision.APPROVAL_REQUIRED -> BudgetAuthorizationVerdict.APPROVAL_REQUIRED
                },
                reason = result.reason
            )
        }
    }

    /**
     * THE single ordered admission gate for every governed tool request.
     * Production path policy resolves workspace roots through the same
     * sandboxed directories SandboxWorkspaceStorageAdapter uses.
     */
    val admissionControlService: AdmissionControlService by lazy {
        // P0-1: the STATIC coding declarations (companion) are used to avoid
        // the AdmissionControlService ⇄ CodingToolchainService construction
        // cycle; live adapter declarations resolve the rest.
        AdmissionControlService(
            toolDeclarations = ToolDeclarationResolver { name -> resolveToolDeclarationFor(name) },
            principalAuthorization = principalAuthorizationPort,
            securityGuard = securityGuardService,
            budgetAuthorization = budgetAuthorizationPort,
            rateLimitCheck = { scopeKey -> rateLimitGovernor.tryAcquire(scopeKey) },
            approvalGate = humanApprovalGate,
            sandboxService = sandboxLifecycleService,
            auditSink = admissionAuditPort,
            workspaceRootResolver = { projectId ->
                val dir = File(appContext.filesDir, "workspaces/proj_$projectId")
                if (dir.exists()) dir.canonicalPath else null
            },
            canonicalResolver = { path -> File(path).canonicalPath }
        )
    }

    val codingToolchainService: CodingToolchainService by lazy {
        CodingToolchainService(
            admission = admissionControlService,
            workspaceStorage = workspaceStorage
        )
    }

    // --- Decision & Execution ---
    val decisionService: DecisionService by lazy {
        DecisionService(
            cbrMdpEngine = cbrMdpEngine,
            resourceCapabilityGraph = componentRegistry.resourceCapabilityGraph,
            securityGuard = securityGuardService,
            userPreferenceRepository = generalizedUserPreferenceRepository,
            // REAL delegation candidates: the planner consults the registered
            // agent catalog when the current agent lacks required capabilities.
            agentCatalog = { componentRegistry.listAgents() },
            // GOVERNANCE PHASE: pre-execution economic + capability gates.
            economicGovernance = economicGovernanceService,
            capabilityRadar = capabilityRadarService
        ).apply {
            // P1-1 (audit 2026 §8 — DecisionContext truth): the planner sees
            // the REAL capability graph derived from the authoritative
            // DurableResourceRegistryService, and the REAL registered tool
            // names from the RuntimeAdapterResolver. Planner state == world
            // state; no more empty capabilities/tools.
            liveCapabilityDescriptorsProvider = {
                durableResourceRegistryService.listResources().flatMap { record ->
                    record.capabilities.map { cap ->
                        com.example.domain.core.capability.CapabilityDescriptor(
                            type = cap,
                            providerId = record.providerId,
                            resourceType = record.resourceType.name,
                            isLocal = record.isLocal,
                            state = when (record.healthStatus) {
                                com.example.domain.core.provider.HealthStatus.HEALTHY ->
                                    com.example.domain.core.capability.CapabilityState.AVAILABLE
                                com.example.domain.core.provider.HealthStatus.DEGRADED ->
                                    com.example.domain.core.capability.CapabilityState.DEGRADED
                                else -> com.example.domain.core.capability.CapabilityState.UNAVAILABLE
                            },
                            attributes = mapOf(
                                "resourceId" to record.resourceId.value,
                                "lifecycleState" to record.lifecycleState.name
                            )
                        )
                    }
                }
            }
            liveToolNamesProvider = {
                componentRegistry.runtimeAdapterResolver.listToolDeclarations().map { it.name }
            }
        }
    }

    val executionService: ExecutionService by lazy {
        ExecutionService(
            runtimeAdapterResolver = componentRegistry.runtimeAdapterResolver,
            resourceRegistry = durableResourceRegistryService,
            securityGuard = securityGuardService,
            extensionManager = extensionManager,
            memoryRepositoryProvider = { componentRegistry.getMemoryRepository() }
        ).apply {
            // REAL multi-agent delegation wiring (audit 2026 fix): the
            // execution layer resolves child agents through the registry and
            // executes them through the orchestrator with structured
            // concurrency (parent cancellation cancels the child).
            registryAgentResolver = { agentId -> componentRegistry.getAgent(agentId) }
            // -----------------------------------------------------------
            // P0-1 / P1-11 (audit 2026 §15/§33 — Universal Execution
            // Authority): THE admission gate is wired into the agent-loop
            // execution kernel itself. Every EXECUTE_TOOL / EXECUTE_MCP /
            // MODEL_TOOL_CALL now passes through the SAME ordered pipeline
            // as the governed coding toolchain — no parallel ungoverned
            // execution path remains.
            admissionControl = admissionControlService
            // P1-2: tool lifecycle authority — a REVOKED tool can no longer
            // be executed through any path even if its adapter is registered.
            toolLifecycleEnforcer = { toolName ->
                toolLifecycleService.isToolExecutable(toolName)
            }
            // P1-12: circuit breaker becomes a real fail-fast authority for
            // provider-backed actions (LLM / search).
            circuitBreakerGate = circuitBreakerService
            // P1-3: search intelligence runs INSIDE the production search
            // path, over the authoritatively resolved adapter.
            searchIntelligence = { query, provider ->
                searchIntelligenceService.searchIntelligent(query, provider)
            }
        }
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
            taskDao = database.taskDao(),
            // GAP-CLOSURE P0-05/P0-06: the action idempotency ledger — durable
            // intention → outcome records for exactly-once recovery.
            actionIntentDao = database.actionIntentDao(),
            // GOVERNANCE PHASE: economic governance (token quota gate +
            // usage accounting into the cost ledger).
            economicGovernanceService = economicGovernanceService
        ).also { orchestrator ->
            // P1-8 (audit 2026 §18 — startup race): every execution awaits the
            // bootstrap readiness barrier before entering the loop — resource
            // restore, Q-table load, canonical agent sync and the recovery
            // sweep complete BEFORE any execution runs.
            orchestrator.readinessGate = { awaitRuntimeReadiness() }
            // GOVERNANCE PHASE: workspace scoping for accounting/evidence/
            // decision context. GAP-CLOSURE P0-02/P0-03: the provider is
            // consulted ONCE at execution start to PIN the canonical context;
            // when no workspace is active the execution fails CLOSED with
            // WORKSPACE_CONTEXT_REQUIRED (no silent "default" scope).
            orchestrator.workspaceIdProvider = { workspaceRuntimeService.activeWorkspaceIdOrNull() }
            // P0 CONVERGENCE (audit step 12 §6): the workspace's sandbox
            // project is pinned INTO the canonical execution context at
            // launch, so mid-run workspace switches cannot re-target agent
            // file operations (FileSystemTool / skills / MCP local bridge all
            // resolve the pinned scope first). No implicit 1L ever.
            orchestrator.projectIdProvider = { workspaceRuntimeService.activeProjectIdOrNull() }
            // REPAIR ORDER §20 — the PINNED workspace's authoritative policy,
            // read from persistence BY ID once at launch (never the
            // currently-active StateFlow: a mid-run workspace switch must not
            // change a live execution's governance).
            orchestrator.pinnedWorkspacePolicyProvider = { workspaceId ->
                workspaceRuntimeService.autonomyPolicyForWorkspace(workspaceId)?.name
            }
            // REPAIR ORDER §3B — DURABLE consent requests: when a genuinely
            // sensitive action is blocked and no admissible alternative
            // remains, a human-approval request is RECORDED so the user can
            // actually see and resolve it (the previously unreachable
            // approval surface).
            orchestrator.approvalRequester = { executionId, toolName, prompt, justification ->
                runCatching {
                    humanApprovalGate.requestApproval(
                        executionId = executionId,
                        toolName = toolName,
                        riskLevel = "HIGH",
                        prompt = prompt,
                        justification = justification
                    )
                }.getOrNull()
            }
            // Delegation executor: child tasks run through the same closed
            // loop (DECIDE → EXECUTE → OBSERVE), so children persist their
            // own task rows, emit their own traces, and honour the same
            // cancellation scope as the parent.
            executionService.delegationExecutor = { childAgent, childTask ->
                orchestrator.executeTask(childAgent, childTask)
            }
            // RAG wiring (audit 2026 fix): RETRIEVE_KNOWLEDGE actions in the
            // agent loop now query the real document knowledge base.
            executionService.ragRetrievalProvider = { query, topK ->
                ragPipelineService.retrieveRelevantContext(query, topK)
            }
            // Observability wiring (audit 2026 fix): every execution event
            // becomes a persisted trace node + metric sample — the trace
            // previously vanished when the in-memory stream ended.
            telemetryService.subscribeToExecutionEvents(
                orchestrator.executionEventPublisher
            )
            // GOVERNANCE PHASE: the radar subscribes to the SAME bus (no
            // second event bus) — execution outcomes become capability
            // evidence.
            capabilityRadarService.subscribeToExecutionEvents(
                orchestrator.executionEventPublisher
            )
            capabilityRadarService.workspaceIdProvider = { workspaceRuntimeService.activeWorkspaceIdOrNull() }
            // GOVERNANCE PHASE: rate-limit encounters from the execution
            // layer close the RPM/TPM windows in the governor.
            executionService.rateLimitRecorder = { providerId, modelId, resourceId, _, retryAfterMs ->
                economicGovernanceService.recordRateLimitEncounter(providerId, modelId, resourceId, retryAfterMs)
            }
            // Security governance wiring (audit 2026 fix): tool/MCP/delegation
            // permission checks are enforced through the permission service.
            executionService.permissionGrantService = permissionGrantService

            // AUTONOMY + BUDGET GOVERNORS (defect family 4): agent autonomy
            // and budget governance now derive from AUTHORITATIVE effective
            // policy — the more restrictive of the WORKSPACE's stored policy
            // (authoritative) and the task's persisted policy — instead of
            // raw caller-supplied authority. An explicit EXECUTE grant counts
            // as recorded consent.
            //
            // REPAIR ORDER §20: the orchestrator now resolves the effective
            // policy ONCE at launch (pinned workspace policy from persistence
            // BY ID ⊔ task constraints) and passes it in — the governor no
            // longer re-reads the CURRENTLY-ACTIVE workspace mid-execution
            // (scope mismatch: a live execution's governance must never
            // change because the user switched workspaces).
            orchestrator.autonomyGovernor = { agent, action, isSensitive, taskPolicy ->
                val effective = taskPolicy
                val evaluation = agentLifecycleService.evaluateAutonomy(
                    agent.identity.id, effective, action.type.name, isSensitive
                )
                if (evaluation.isAllowed) {
                    evaluation
                } else if (evaluation.requireHumanConsent && !action.targetId.isNullOrBlank()) {
                    // An explicit EXECUTE grant IS recorded consent (resolved
                    // against the PINNED execution workspace, not the active one).
                    val workspaceId = com.example.domain.core.execution.ExecutionScope
                        .currentWorkspaceIdOrNull() ?: workspaceRuntimeService.activeWorkspaceIdOrNull()
                    val granted = runCatching {
                        permissionGrantService.check(
                            principalType = com.example.domain.core.security.governance.PrincipalType.AGENT,
                            principalId = agent.identity.id.value,
                            resourceType = com.example.domain.core.security.governance.SecurableResourceType.TOOL,
                            resourceId = action.targetId,
                            permission = com.example.domain.core.security.governance.Permission.EXECUTE,
                            workspaceId = workspaceId
                        )
                    }.getOrDefault(false)
                    if (granted) {
                        evaluation.copy(
                            isAllowed = true,
                            requireHumanConsent = false,
                            reason = "سياسة فعّالة ${effective.name}: الموافقة مُستوفاة بمنح إذن EXECUTE صريح."
                        )
                    } else {
                        evaluation
                    }
                } else {
                    evaluation
                }
            }
            orchestrator.budgetGovernor = { agent, task, accumulatedTokens ->
                // Effective budget: the AGENT's durable budget intersected
                // with the task quota (authoritative, not caller-supplied).
                val effectiveMax = minOf(agent.budget.maxTokens, task.budget.tokenLimit)
                agentLifecycleService.evaluateBudget(
                    agent.identity.id,
                    com.example.domain.core.agent.AgentBudget(
                        maxTokens = effectiveMax,
                        usedTokens = accumulatedTokens
                    )
                )
            }

            // CANONICAL AUTHORITY BOUNDARY (defect family 2): even when the
            // grant service is unavailable, authorization decisions are
            // audited through the SAME telemetry audit trail (one authority).
            executionService.authorizationAuditSink = { severity, actor, action, resourceType, resourceId, decision, reason, workspaceId ->
                telemetryService.recordAudit(
                    AuditEvent(
                        id = java.util.UUID.randomUUID().toString(),
                        severity = when (severity) {
                            com.example.domain.core.security.governance.AuditSeverity.INFO -> AuditSeverity.INFO
                            com.example.domain.core.security.governance.AuditSeverity.WARN -> AuditSeverity.WARN
                            com.example.domain.core.security.governance.AuditSeverity.ERROR -> AuditSeverity.ERROR
                            com.example.domain.core.security.governance.AuditSeverity.CRITICAL -> AuditSeverity.CRITICAL
                        },
                        actor = actor,
                        action = action,
                        resourceType = resourceType,
                        resourceId = resourceId,
                        decision = decision,
                        reason = reason,
                        workspaceId = workspaceId
                    )
                )
            }
        }
    }

    // Workflow Engine — now durably persisted (audit 2026 fix).
    val workflowEngine: WorkflowEngine by lazy {
        WorkflowEngine(
            orchestrator = agentOrchestrator,
            persistenceService = workflowPersistenceService,
            // P0-02/P0-03: the workflow PINS its workspace once at start;
            // awaiting bootstrap makes cold-start runs deterministic.
            workspaceIdProvider = {
                workspaceRuntimeService.awaitActiveWorkspaceId()
                    ?: throw com.example.application.workspace.NoActiveWorkspaceStateException(
                        "NO_ACTIVE_WORKSPACE: لا يمكن بدء خطة عمل دون مساحة عمل نشطة."
                    )
            },
            // ----------------------------------------------------------------------
            // CANONICAL AGENT BINDING (report gap: "workflow steps use
            // synthetic agents"): every step resolves its DURABLE registry
            // agent — assignedAgentId first, then a role match; when no
            // durable role agent exists yet, one is MATERIALIZED into the
            // registry (origin PLANNER) and registered into the runtime —
            // so steps execute through REAL agents (system prompt,
            // capabilities, budget, workspace scope, version, lifecycle),
            // never throwaway synthetic ones.
            // ----------------------------------------------------------------------
            agentResolver = { step ->
                resolveWorkflowStepAgent(step)
            }
        )
    }

    /**
     * Canonical-agent resolution policy for workflow steps: durable
     * registry first (pinned id → role match → materialize-and-register).
     * Every resolution path is AUDITED (defect family 3: explicit fallback
     * behavior, when legitimately allowed, is an intentional recorded policy
     * decision) and a failed PIN throws an explicit error the engine turns
     * into an honest step failure — never a silent role fallback.
     */
    private suspend fun resolveWorkflowStepAgent(
        step: com.example.domain.core.workflow.StepNode
    ): com.example.domain.core.agent.AgentDefinition {
        val workspaceId = runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }.getOrNull()
        when (val resolution = agentRegistryService.resolveStepAgentDetailed(
            role = step.agentRole,
            workspaceId = workspaceId,
            assignedAgentId = step.assignedAgentId
        )) {
            is com.example.application.agent.AgentRegistryService.StepAgentResolution.Pinned -> {
                auditAgentBinding(step, "PINNED", resolution.agent.identity.id.value)
                componentRegistry.registerAgent(resolution.agent)
                return resolution.agent
            }
            is com.example.application.agent.AgentRegistryService.StepAgentResolution.RoleMatched -> {
                auditAgentBinding(step, "ROLE_MATCHED", resolution.agent.identity.id.value)
                componentRegistry.registerAgent(resolution.agent)
                return resolution.agent
            }
            is com.example.application.agent.AgentRegistryService.StepAgentResolution.PinnedAgentUnavailable -> {
                if (step.assignedAgentId.isNullOrBlank()) {
                    // No pin and no role agent yet — the DECLARED materialization
                    // policy (an intentional decision, audited below).
                } else {
                    // REPAIR (defect family 3): an exact pin that cannot be
                    // honoured FAILS the binding honestly — it must never
                    // silently degrade into a role/materialized fallback.
                    auditAgentBinding(step, "PIN_UNAVAILABLE", step.assignedAgentId)
                    throw IllegalStateException(resolution.reason)
                }
            }
        }
        // MATERIALIZE a durable role agent (PLANNER-authored) so future runs
        // resolve it from the registry — the registry stays canonical. This
        // is the DECLARED fallback policy for un-pinned steps and is audited.
        val materialized = agentRegistryService.saveAgent(
            com.example.domain.core.agent.AgentDefinition(
                identity = com.example.domain.core.agent.AgentIdentity(
                    id = com.example.domain.core.agent.AgentId("agent_role_${step.agentRole.name.lowercase()}"),
                    name = "وكيل ${step.agentRole.displayName}",
                    role = step.agentRole,
                    description = "وكيل قانوني منشأ لتنفيذ خطوات دور ${step.agentRole.displayName} في خطط العمل.",
                    systemPrompt = step.agentRole.defaultSystemPrompt
                ),
                allowedCapabilities = setOf(
                    com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                    com.example.domain.core.capability.CapabilityType.STREAMING,
                    com.example.domain.core.capability.CapabilityType.TOOL_EXECUTION
                ),
                budget = com.example.domain.core.agent.AgentBudget(maxTokens = 30000)
            ),
            origin = "PLANNER"
        )
        auditAgentBinding(step, "MATERIALIZED_ROLE_AGENT", materialized.identity.id.value)
        componentRegistry.registerAgent(materialized)
        return materialized
    }

    /** Records the agent-binding policy decision in the audit trail. */
    private suspend fun auditAgentBinding(
        step: com.example.domain.core.workflow.StepNode,
        policy: String,
        resolvedAgentId: String
    ) {
        runCatching {
            telemetryService.recordAudit(
                AuditEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    severity = AuditSeverity.INFO,
                    actor = "workflow_engine",
                    action = "WORKFLOW_STEP_AGENT_BINDING",
                    resourceType = "AGENT",
                    resourceId = resolvedAgentId,
                    decision = policy,
                    reason = "سياسة ربط الوكيل بخطوة '${step.id}' (الدور: ${step.agentRole.name})",
                    attributes = mapOf(
                        "stepId" to step.id,
                        "assignedAgentId" to (step.assignedAgentId ?: "(none)")
                    )
                )
            )
        }
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

    val telemetryService: TelemetryService by lazy {
        TelemetryService(telemetryPort).also { service ->
            // GOVERNANCE PHASE: every metric row carries the real workspace
            // attribution (previously always NULL — global metrics).
            // GAP-CLOSURE P0-03: null = honestly UNATTRIBUTED.
            service.workspaceIdProvider = { workspaceRuntimeService.activeWorkspaceIdOrNull() }
        }
    }

    val memoryLifecycleService: MemoryLifecycleService by lazy {
        MemoryLifecycleService(
            memoryDao = database.memoryDao(),
            namespaceDao = database.agentMemoryNamespaceDao(),
            memoryRepository = memoryVectorStore,
            embeddingProvider = localEmbeddingRouter
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
                // Audit 2026 fix: robust resolution by SUFFIX match on the
                // declaration name (the previous substring hack
                // `substringAfter("tool_").substringBefore("_")` mis-parsed
                // most real ids). The service's own declaration cache is
                // consulted first; this provider covers in-app tools.
                runCatching {
                    componentRegistry.runtimeAdapterResolver.listToolDeclarations()
                        .firstOrNull { toolId.endsWith(it.name) || toolId.contains(it.name) }
                }.getOrNull()
            }
        ).apply {
            // Pre-cache in-app tool declarations so lifecycle state machine
            // (validate/authorize/expose) works for them from the start.
            cacheDeclaration("tool_workspace_file_tool", fileSystemTool.declaration)
            cacheDeclaration("tool_safe_diagnostics_tool", safeDiagnosticsTool.declaration)
        }
    }

    val searchIntelligenceService: SearchIntelligenceService by lazy {
        // P0 CONVERGENCE: the offline local-workspace search fallback is now
        // WIRED (previously dead: no storage port) and scoped to the ACTIVE
        // workspace's own project — never the legacy shared 1L.
        SearchIntelligenceService(
            searchProvider = com.example.infrastructure.search.MultiSourceSearchAdapter(
                workspaceStoragePort = workspaceStorage,
                projectIdProvider = { workspaceRuntimeService.activeProjectIdOrNull() },
                egressControl = egressControl
            )
        )
    }

    val ragIntelligenceService: RagIntelligenceService by lazy {
        RagIntelligenceService(
            documentChunkDao = database.documentChunkDao(),
            embeddingProvider = localEmbeddingRouter,
            // P0 CONVERGENCE: real document titles survive the intelligence
            // reload path (previously reloaded chunks lost their titles).
            documentDao = database.knowledgeDocumentDao()
        )
    }

    val agentLifecycleService: AgentLifecycleService by lazy {
        // REPAIR (defect family 4): dryRun now executes through the REAL
        // governed kernel (decision → governance → execution) instead of a
        // validation no-op; agent revisions are persisted in the durable
        // `agent_revisions` ledger so the version chain survives process
        // death and remains reproducible.
        AgentLifecycleService(
            memoryLifecyclePort = memoryLifecycleService,
            sandboxExecutor = { agent, testPrompt ->
                // A REAL governed execution with a bounded SANDBOX budget —
                // every kernel gate (security, permissions, budget,
                // telemetry) applies exactly as in production execution.
                val sandboxTask = com.example.domain.core.task.TaskDefinition(
                    id = com.example.domain.core.task.TaskId(
                        "dryrun_${java.util.UUID.randomUUID().toString().take(8)}"
                    ),
                    assignedAgentId = agent.identity.id,
                    input = com.example.domain.core.task.TaskInput(rawPrompt = testPrompt),
                    constraints = com.example.domain.core.task.TaskConstraints(
                        autonomyPolicy = com.example.domain.core.task.AutonomyPolicy.SUPERVISED,
                        maxRetries = 0,
                        timeoutMs = 30_000L
                    ),
                    budget = com.example.domain.core.task.TaskBudget(
                        tokenLimit = minOf(agent.budget.maxTokens, 4_000)
                    )
                )
                val start = System.currentTimeMillis()
                val summary = agentOrchestrator.executeTaskDetailed(
                    agent = agent,
                    task = sandboxTask,
                    pinnedWorkspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull()
                )
                com.example.application.agent.SandboxExecutionOutcome(
                    isSuccessful = summary.outcome is com.example.domain.core.Outcome.Success<*>,
                    responseSummary = when (val o = summary.outcome) {
                        is com.example.domain.core.Outcome.Success<*> ->
                            (o.value as? String)?.take(400) ?: "تم التنفيذ بنجاح"
                        is com.example.domain.core.Outcome.Degraded<*, *> ->
                            "تنفيذ متدهور: ${o.diagnosticMessage.take(200)}"
                        is com.example.domain.core.Outcome.Error<*> ->
                            "فشل التنفيذ: ${o.diagnosticMessage.take(200)}"
                        else -> "نتيجة غير معروفة"
                    },
                    durationMs = System.currentTimeMillis() - start,
                    tokensConsumed = summary.totalTokensConsumed,
                    executionId = summary.executionId
                )
            },
            revisionStore = com.example.infrastructure.persistence.repository.RoomAgentRevisionStore(
                database.agentRevisionDao()
            )
        )
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

    // ==================================================================
    // v13 — DURABLE SESSIONS + WORKFLOW LIBRARY (report gap-closure)
    // ==================================================================

    /**
     * DURABLE CONVERSATION SESSIONS (report gap: "Sessions NOT FIXED —
     * deleted without a durable replacement"): workspace-scoped sessions
     * (chat_sessions/chat_turns) with mode (QUICK_CHAT/AGENT), exact model
     * pins, turn/aggregate persistence — browsable, reopenable, resumable.
     */
    val conversationSessionService: com.example.application.session.ConversationSessionService by lazy {
        com.example.application.session.ConversationSessionService(
            repository = com.example.infrastructure.persistence.repository.RoomConversationSessionRepository(
                database = database,
                sessionDao = database.conversationSessionDao(),
                turnDao = database.conversationTurnDao()
            ),
            workspaceIdProvider = { workspaceRuntimeService.requireActiveWorkspaceId() }
        )
    }

    /**
     * WORKFLOW LIBRARY (report gap: "workflow library/history NOT FIXED"):
     * the USER-AUTHORED workflow definition as a durable, re-editable,
     * clonable, run-recorded workspace asset.
     */
    val workflowLibraryService: com.example.application.workflow.WorkflowLibraryService by lazy {
        com.example.application.workflow.WorkflowLibraryService(
            workflowDefinitionDao = database.workflowDefinitionDao(),
            planSerializer = workflowPersistenceService,
            workspaceIdProvider = { workspaceRuntimeService.requireActiveWorkspaceId() }
        )
    }

    /**
     * P1-8 FIX (audit 2026 §18 — bootstrap not synchronized with runtime
     * readiness): the bootstrap completion BARRIER. `bootstrapRuntime()`
     * completes it when resource restore, Q-table load, canonical agent
     * sync, recovery sweep and budget seeding are DONE. Executions gate on
     * [awaitRuntimeReadiness] (wired into AgentOrchestrator.readinessGate)
     * so no execution can race a half-initialized runtime. A timeout
     * (15s) audits an honest warning and proceeds — the gate must never
     * deadlock the app on a slow/failed bootstrap step.
     */
    private val runtimeReadiness = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** TRUE once the runtime bootstrap completed successfully. */
    val isRuntimeReady: Boolean get() = runtimeReadiness.isCompleted

    /**
     * Awaits bootstrap completion with a bounded timeout. Returns false
     * (and records an honest WARN audit event) when bootstrap is late —
     * callers proceed with a degraded-but-attributed runtime.
     */
    suspend fun awaitRuntimeReadiness(timeoutMs: Long = 15_000L): Boolean {
        val ready = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { runtimeReadiness.await() }
        if (ready == null) {
            runCatching {
                telemetryService.recordAudit(
                    AuditSeverity.WARN,
                    actor = "bootstrap",
                    action = "runtime_readiness_timeout",
                    resourceType = "runtime",
                    resourceId = "bootstrap",
                    decision = "DEGRADED",
                    reason = "انتهت مهلة انتظار جهوزية التشغيل (${timeoutMs}ms) — يُتابع التنفيذ بتشغيل غير مكتمل التهيئة (مُسجَّل بأمانة)."
                )
            }
        }
        return ready != null
    }

    /**
     * First-run bootstrap (parity with the legacy default providers): seeds
     * local embedding + multi-source search + Gemini provider records, then
     * validates ONLY the zero-network in-process resources.
     *
     * Audit 2026 fix — this function was previously DEAD CODE (never called
     * from anywhere): adapter restore, MDP Q-table load, memory decay, and
     * task resumption never ran. It is now invoked once from MainActivity
     * (application scope, never the main thread).
     */
    fun bootstrapRuntime() {
        applicationScope.launch {
            try {
                durableResourceRegistryService.eagerLoad()
                cbrMdpEngine.loadPersistedQTable()
            // ----------------------------------------------------------------------
            // EGRESS ENFORCEMENT WIRING (report gap: sandbox network-egress
            // restrictions) — REPAIRED (defect family 1): the workspace's
            // network policy is PINNED per workspace (a registry, so an
            // execution pinned to a previous workspace keeps its policy after
            // a mid-run switch), the ACTIVE workspace governs unscoped
            // user-driven requests, and requests with NO governing policy
            // fail CLOSED. An OFFLINE workspace can never dial out, from any
            // adapter — and one blocked sandbox session can never affect
            // another session or workspace.
            // ----------------------------------------------------------------------
            launch {
                runCatching {
                    workspaceRuntimeService.activeWorkspace.collect { workspace ->
                        if (workspace != null) {
                            egressControl.pinWorkspacePolicy(workspace.id, workspace.networkPolicy)
                        }
                        egressControl.setActiveWorkspace(workspace?.id)
                    }
                }
            }
            // SESSION-SCOPED EGRESS BLOCKS (defect family 1): a governed
            // sandbox session with a NO_NETWORK egress policy blocks egress
            // ONLY for requests carrying that session's scope — never for
            // unrelated sessions or workspaces. Blocks are released on
            // terminal states (no stale leaks after sandbox teardown).
            launch {
                runCatching {
                    sandboxLifecycleService.sessionsState.collect { sessions ->
                        val blocked = sessions.filter { session ->
                            session.limits.networkPolicy ==
                                com.example.domain.core.runtime.SandboxNetworkPolicy.NO_NETWORK &&
                                session.state != com.example.domain.core.runtime.SandboxLifecycleState.DESTROYED &&
                                session.state != com.example.domain.core.runtime.SandboxLifecycleState.FAILED
                        }
                        blocked.forEach { session ->
                            egressControl.blockSession(session.workspaceId, session.sessionId)
                        }
                        val activeIds = blocked.map { it.sessionId }.toSet()
                        sessions
                            .filter { it.sessionId !in activeIds }
                            .forEach { session -> egressControl.unblockSession(session.sessionId) }
                    }
                }
            }
            // GAP-CLOSURE P1-08: seed/sync the canonical durable agent catalog
            // into the runtime registry BEFORE any execution can start.
            runCatching { syncCanonicalAgents() }
            providerControlPlaneService.ensureBootstrapDefaults()
            providerControlPlaneService.restoreAdaptersForPersistedResources()
            // Phase 5 — memory decay + workflow/task resume on startup.
            runCatching { memoryLifecycleService.applyDecay() }
            runCatching { memoryLifecycleService.consolidate() }
            // Process-death recovery (audit 2026 fix): actually RESUME tasks
            // left RUNNING by a crashed/killed process — previously the
            // resumable list was computed and then discarded.
            runCatching { agentOrchestrator.resumeInterruptedTasks() }
            runCatching { workflowPersistenceService.resumable() } // surfaces resumable workflows for the UI/log
            // GOVERNANCE PHASE: derive the initial radar snapshot AFTER the
            // registry is eager-loaded (persisted capability states survive
            // restarts; this refreshes them against live resource facts).
            // GAP-CLOSURE P0-03: bootstrap-aware — skipped honestly when no
            // workspace became active (never a fabricated "default" scope).
            val radarWorkspaceId = workspaceRuntimeService.awaitActiveWorkspaceId(timeoutMs = 2_000L)
            if (radarWorkspaceId != null) {
                runCatching {
                    capabilityRadarService.deriveSnapshot(
                        workspaceId = radarWorkspaceId,
                        networkPolicy = com.example.domain.core.network.NetworkPolicy.HYBRID,
                        isNetworkAvailable = networkMonitor.isNetworkAvailable.value
                    )
                }
            }
            // GOVERNANCE PHASE: ensure a SYSTEM-scope budget policy exists so
            // budget governance has defined (non-fabricated) semantics. NO
            // monetary amount is seeded — an allocation with UNKNOWN amount
            // means "track cost, no spending authority configured" until the
            // operator sets one from the observatory screen.
            runCatching {
                val systemScope = com.example.domain.core.budget.BudgetScope(
                    com.example.domain.core.budget.BudgetScopeType.SYSTEM, "platform"
                )
                if (economicGovernanceService.budgetStatusFor(systemScope).allocation == null) {
                    economicGovernanceService.setAllocation(
                        scope = systemScope,
                        allocated = com.example.domain.core.budget.MoneyAmount.unknown("USD"),
                        policy = com.example.domain.core.budget.BudgetPolicy(
                            actions = listOf(com.example.domain.core.budget.BudgetPolicyAction.SOFT_LIMIT)
                        )
                    )
                }
            }
            } catch (t: Throwable) {
                // P1-8: a bootstrap step threw. The readiness gate is STILL
                // released below — the failure is attributed honestly so the
                // audit trail carries it, and the runtime proceeds in a
                // degraded-but-attributed state instead of deadlocking every
                // execution behind a gate that never opens.
                runCatching {
                    telemetryService.recordAudit(
                        AuditSeverity.ERROR,
                        actor = "bootstrap",
                        action = "bootstrap_step_failed",
                        resourceType = "runtime",
                        resourceId = "bootstrap",
                        decision = "ERROR",
                        reason = "فشلت خطوة في تهيئة التشغيل: ${t::class.simpleName}: ${t.message?.take(200)}"
                    )
                }
            } finally {
                // P1-8: the barrier ALWAYS opens — success, failure or late
                // bootstrap. Executions waiting on awaitRuntimeReadiness()
                // resume here.
                runtimeReadiness.complete(Unit)
            }
        }
    }
}

/**
 * Effective-autonomy ranking (defect family 4): ASSISTED (most restrictive)
 * < SUPERVISED < AUTONOMOUS (least restrictive). The effective policy is the
 * MORE RESTRICTIVE of the workspace's authoritative policy and the task's
 * persisted policy.
 */
private fun moreRestrictiveAutonomy(
    a: com.example.domain.core.task.AutonomyPolicy?,
    b: com.example.domain.core.task.AutonomyPolicy
): com.example.domain.core.task.AutonomyPolicy {
    val rank = mapOf(
        com.example.domain.core.task.AutonomyPolicy.ASSISTED to 0,
        com.example.domain.core.task.AutonomyPolicy.SUPERVISED to 1,
        com.example.domain.core.task.AutonomyPolicy.AUTONOMOUS to 2
    )
    val aRank = a?.let { rank[it] } ?: return b
    return if (aRank <= rank.getValue(b)) a!! else b
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
                // GAP-CLOSURE P1-08/P1-10 — canonical durable agent registry.
                agentRegistryService = appContainer.agentRegistryService,
                // Phase 5 — pass the new intelligence services for the
                // Unified Activity Feed + proactive suggestion surface.
                telemetryService = appContainer.telemetryService,
                workspaceContextEngine = appContainer.workspaceContextEngine,
                telemetryPort = appContainer.telemetryPort,
                networkMonitorProvider = appContainer.networkMonitor,
                appContext = appContainer.appContext,
                // GOVERNANCE PHASE — radar + economic governance surfaces for
                // the GovernanceObservatoryScreen.
                capabilityRadarService = appContainer.capabilityRadarService,
                economicGovernanceService = appContainer.economicGovernanceService,
                // v13 — DURABLE SESSIONS + QUICK CHAT + WORKFLOW LIBRARY
                // (report gap-closure).
                conversationSessionService = appContainer.conversationSessionService,
                workflowLibraryService = appContainer.workflowLibraryService,
                workflowPersistenceService = appContainer.workflowPersistenceService,
                // REPAIR ORDER §3A/§5/§9-§14/§24-§28 — portability subsystem:
                // bootstrap state machine, project runtime, transfers,
                // readiness, repair center, and the approval surface.
                projectRuntimeService = appContainer.projectRuntimeService,
                bootstrapStateProvider = appContainer.workspaceRuntimeService.bootstrapState,
                fileTransferService = appContainer.fileTransferService,
                projectPackageService = appContainer.projectPackageService,
                sessionExportService = appContainer.sessionExportService,
                projectReadinessService = appContainer.projectReadinessService,
                repairCenterService = appContainer.repairCenterService,
                humanApprovalGate = appContainer.humanApprovalGate
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
