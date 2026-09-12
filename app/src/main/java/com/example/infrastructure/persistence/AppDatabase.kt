package com.example.infrastructure.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.infrastructure.persistence.dao.AgentMemoryNamespaceDao
import com.example.infrastructure.persistence.dao.AuditTrailDao
import com.example.infrastructure.persistence.dao.DecisionCaseDao
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.dao.EvolutionCandidateDao
import com.example.infrastructure.persistence.dao.ExecutionLogDao
import com.example.infrastructure.persistence.dao.ExecutionTraceDao
import com.example.infrastructure.persistence.dao.ExtensionConfigDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.dao.MetricEventDao
import com.example.infrastructure.persistence.dao.PermissionGrantDao
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.ProviderDao
import com.example.infrastructure.persistence.dao.ProviderServiceDao
import com.example.infrastructure.persistence.dao.RadarItemDao
import com.example.infrastructure.persistence.dao.ResourceEdgeDao
import com.example.infrastructure.persistence.dao.ResourceRecordDao
import com.example.infrastructure.persistence.dao.ServiceConfigurationDao
import com.example.infrastructure.persistence.dao.ServiceHealthRecordDao
import com.example.infrastructure.persistence.dao.ServiceOfferingDao
import com.example.infrastructure.persistence.dao.TaskDao
import com.example.infrastructure.persistence.dao.ToolAuditDao
import com.example.infrastructure.persistence.dao.ToolHealthDao
import com.example.infrastructure.persistence.dao.ToolLifecycleDao
import com.example.infrastructure.persistence.dao.UserResourcePreferenceDao
import com.example.infrastructure.persistence.dao.WorkflowExecutionDao
import com.example.infrastructure.persistence.dao.WorkflowStepStateDao
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.dao.MdpQValueDao
import com.example.infrastructure.persistence.entities.AgentMemoryNamespaceEntity
import com.example.infrastructure.persistence.entities.AuditTrailEntity
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.EvolutionCandidateEntity
import com.example.infrastructure.persistence.entities.ExecutionLogEntity
import com.example.infrastructure.persistence.entities.ExecutionTraceNodeEntity
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.MetricEventEntity
import com.example.infrastructure.persistence.entities.PermissionGrantEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.ProviderEntity
import com.example.infrastructure.persistence.entities.ProviderServiceEntity
import com.example.infrastructure.persistence.entities.RadarItemEntity
import com.example.infrastructure.persistence.entities.ResourceEdgeEntity
import com.example.infrastructure.persistence.entities.ResourceRecordEntity
import com.example.infrastructure.persistence.entities.ServiceConfigurationEntity
import com.example.infrastructure.persistence.entities.ServiceHealthRecordEntity
import com.example.infrastructure.persistence.entities.ServiceOfferingEntity
import com.example.infrastructure.persistence.entities.TaskEntity
import com.example.infrastructure.persistence.entities.ToolAuditEntity
import com.example.infrastructure.persistence.entities.ToolHealthSnapshotEntity
import com.example.infrastructure.persistence.entities.ToolLifecycleStateEntity
import com.example.infrastructure.persistence.entities.UserResourcePreferenceEntity
import com.example.infrastructure.persistence.entities.WorkflowExecutionEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import com.example.infrastructure.persistence.entities.WorkflowStepStateEntity
import com.example.infrastructure.persistence.entities.MdpQValueEntity

/**
 * AI-V0 Ultimate — Room Database
 *
 * FIX INF-P0-10: Replaced `fallbackToDestructiveMigration()` with explicit Migration objects.
 * The previous behaviour wiped all user data (projects, sessions, memory vectors, decision
 * cases, radar items) on every schema bump. This is the single most dangerous line in the
 * codebase for a production-targeted app.
 *
 * Phase 2: Added workspaces, knowledge_documents, document_chunks, resource_edges tables
 * to support true multi-workspace runtime and RAG persistence. Bumped version 4 → 5.
 *
 * Phase 4 (Generalized Provider Architecture): added seven tables for the
 * Provider → Service → Configuration → Offering → ResourceRecord → Preference
 * pipeline. Bumped version 5 → 6. Purely additive — no existing table altered.
 *
 * Migration policy going forward:
 *   1. Bump `version` below when adding columns/tables.
 *   2. Add a new `MIGRATION_N_TO_N1` Migration object to `ALL_MIGRATIONS`.
 *   3. exportSchema is enabled (GAP-01, Design Closure 2026) with
 *      `room.schemaLocation` wired in build.gradle — commit the exported
 *      JSON on every version bump so CI can catch schema drift and
 *      MigrationTestHelper can validate future steps.
 *
 * FIX R-3 (audit c03919d): the migration chain now starts at v1 (1→2→3→4→5→6→7)
 * so ANY historically shipped database upgrades cleanly.
 *
 * P1 (real intelligence, audit c03919d): v6 → v7 adds the `mdp_q_values` table —
 * the persistent tabular-MDP Q-table backing the CBR-MDP engine (fixes D-1/D-4:
 * per-(region, action) values + transition rates that survive restarts).
 *
 * Phase 5 (P0/P1 remediation — Observability/Memory/Tool/Workflow/Security/
 * Evolution/Agent gaps): v7 → v8 introduces TEN new tables (metric_events,
 * audit_trail, health_probes, execution_trace_nodes, workflow_executions,
 * workflow_step_states, tool_audit_log, permission_grants, policy_versions,
 * agent_memory_namespaces, tool_lifecycle_states, tool_health_snapshots)
 * AND extends the existing `memory_records` table with five new columns
 * (memory_type, importance, decay_score, workspace_id, agent_id, tags_json,
 * last_decay_evaluated_at_epoch_ms) so the new MemoryLifecycleService can
 * decay / consolidate / scope memories per workspace and per agent.
 * Purely additive — no existing column type changed, so v7 data survives.
 *
 * Governance Phase (Intelligence Governance & Sustainability): v9 → v10 adds
 * SEVEN new tables for the two first-class domain subsystems:
 *   - Capability & Evolution Radar: capability_evidence (append-only real
 *     observations), radar_capability_states (derived state per workspace),
 *     capability_changes (evolution log), radar_recommendations.
 *   - Token/Economic Budget Governance: pricing_entries (versioned unit
 *     prices at provider/service/model scopes), cost_ledger_entries
 *     (usage+cost accounting with full attribution), budget_allocations
 *     (monetary spending authority with enforceable policies).
 * Purely additive — no existing table is altered.
 */
@Database(
    entities = [
        ProjectEntity::class,
        MemoryEntity::class,
        ExecutionLogEntity::class,
        TaskEntity::class,
        DecisionCaseEntity::class,
        RadarItemEntity::class,
        EvolutionCandidateEntity::class,
        ExtensionConfigEntity::class,
        // Phase 2 — new entities for true multi-workspace runtime + RAG persistence
        WorkspaceEntity::class,
        KnowledgeDocumentEntity::class,
        DocumentChunkEntity::class,
        ResourceEdgeEntity::class,
        // Phase 4 — Generalized Provider Architecture persistence
        ProviderEntity::class,
        ProviderServiceEntity::class,
        ServiceConfigurationEntity::class,
        ServiceHealthRecordEntity::class,
        ServiceOfferingEntity::class,
        UserResourcePreferenceEntity::class,
        ResourceRecordEntity::class,
        // P1 — tabular MDP Q-table (CBR-MDP real learning)
        MdpQValueEntity::class,
        // Phase 5 — Observability / Memory / Tool / Workflow / Security / Evolution
        MetricEventEntity::class,
        AuditTrailEntity::class,
        ExecutionTraceNodeEntity::class,
        WorkflowExecutionEntity::class,
        WorkflowStepStateEntity::class,
        ToolAuditEntity::class,
        PermissionGrantEntity::class,
        AgentMemoryNamespaceEntity::class,
        ToolLifecycleStateEntity::class,
        ToolHealthSnapshotEntity::class,
        // Governance Phase — Capability Radar + Economic Budget
        com.example.infrastructure.persistence.entities.CapabilityEvidenceEntity::class,
        com.example.infrastructure.persistence.entities.RadarCapabilityStateEntity::class,
        com.example.infrastructure.persistence.entities.CapabilityChangeEntity::class,
        com.example.infrastructure.persistence.entities.RadarRecommendationEntity::class,
        com.example.infrastructure.persistence.entities.PricingEntryEntity::class,
        com.example.infrastructure.persistence.entities.CostLedgerEntryEntity::class,
        com.example.infrastructure.persistence.entities.BudgetAllocationEntity::class,
        // Gap-closure — canonical durable agent registry (P1-08/P1-09)
        com.example.infrastructure.persistence.entities.ActionIntentEntity::class,
        com.example.infrastructure.persistence.entities.AgentDefinitionEntity::class,
        // v14 — durable agent revision ledger (defect family 4: agent
        // revisions must be durable and reproducible)
        com.example.infrastructure.persistence.entities.AgentRevisionEntity::class,
        // v13 — Report gap-closure: durable conversation sessions + workflow
        // library + full-fidelity durable agents
        com.example.infrastructure.persistence.entities.ConversationSessionEntity::class,
        com.example.infrastructure.persistence.entities.ConversationTurnEntity::class,
        com.example.infrastructure.persistence.entities.WorkflowDefinitionEntity::class,
        // v15 — DURABLE human approval requests (audit 2026 §17) + explicit
        // workspace identity on tasks / execution logs / trace nodes (P1-7/P1-10)
        com.example.infrastructure.persistence.entities.HumanApprovalRequestEntity::class,
        // v16 — REPAIR ORDER: unified artifact library (§7), project
        // dependency graph (§24), snapshots (§26), audit trail (§30)
        com.example.infrastructure.persistence.entities.ArtifactEntity::class,
        com.example.infrastructure.persistence.entities.ProjectDependencyEntity::class,
        com.example.infrastructure.persistence.entities.ProjectSnapshotEntity::class,
        com.example.infrastructure.persistence.entities.AuditEventEntity::class
    ],
    version = 17,
    // GAP-01 (Design Closure 2026, ADR-1): schema export is now enabled and
    // committed under app/schemas/ — every future schema change gets a
    // committed baseline JSON, enabling MigrationTestHelper tests and CI
    // schema-drift detection from v16 onward. (Historical v1–v15 JSONs
    // cannot be regenerated honestly: exportSchema was false from the start.
    // The full-chain identity validation for those paths lives in
    // MigrationChainValidationTest.)
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun memoryDao(): MemoryDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun taskDao(): TaskDao
    abstract fun decisionCaseDao(): DecisionCaseDao
    abstract fun radarItemDao(): RadarItemDao
    abstract fun evolutionCandidateDao(): EvolutionCandidateDao
    abstract fun extensionConfigDao(): ExtensionConfigDao
    // Phase 2 — new DAOs
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun resourceEdgeDao(): ResourceEdgeDao

    // Phase 4 — Generalized Provider Architecture DAOs
    abstract fun providerDao(): ProviderDao
    abstract fun providerServiceDao(): ProviderServiceDao
    abstract fun serviceConfigurationDao(): ServiceConfigurationDao
    abstract fun serviceHealthRecordDao(): ServiceHealthRecordDao
    abstract fun serviceOfferingDao(): ServiceOfferingDao
    abstract fun userResourcePreferenceDao(): UserResourcePreferenceDao
    abstract fun resourceRecordDao(): ResourceRecordDao

    // P1 — tabular MDP Q-table (CBR-MDP real learning)
    abstract fun mdpQValueDao(): MdpQValueDao

    // Phase 5 — Observability / Telemetry / Audit / Trace
    abstract fun metricEventDao(): MetricEventDao
    abstract fun auditTrailDao(): AuditTrailDao
    abstract fun executionTraceDao(): ExecutionTraceDao

    // Phase 5 — Tool lifecycle / health / audit
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun toolLifecycleDao(): ToolLifecycleDao
    abstract fun toolHealthDao(): ToolHealthDao

    // Phase 5 — Security / Permissions
    abstract fun permissionGrantDao(): PermissionGrantDao

    // Phase 5 — Workflow persistence (resume after process death)
    abstract fun workflowExecutionDao(): WorkflowExecutionDao
    abstract fun workflowStepStateDao(): WorkflowStepStateDao

    // Phase 5 — Agent memory namespaces
    abstract fun agentMemoryNamespaceDao(): AgentMemoryNamespaceDao

    // Governance Phase — Capability Radar persistence
    abstract fun capabilityEvidenceDao(): com.example.infrastructure.persistence.dao.CapabilityEvidenceDao
    abstract fun radarCapabilityStateDao(): com.example.infrastructure.persistence.dao.RadarCapabilityStateDao
    abstract fun capabilityChangeDao(): com.example.infrastructure.persistence.dao.CapabilityChangeDao
    abstract fun radarRecommendationDao(): com.example.infrastructure.persistence.dao.RadarRecommendationDao

    // Governance Phase — Economic budget persistence
    abstract fun pricingEntryDao(): com.example.infrastructure.persistence.dao.PricingEntryDao
    abstract fun costLedgerEntryDao(): com.example.infrastructure.persistence.dao.CostLedgerEntryDao
    abstract fun budgetAllocationDao(): com.example.infrastructure.persistence.dao.BudgetAllocationDao

    // Gap-closure — action idempotency ledger (P0-05/P0-06)
    abstract fun actionIntentDao(): com.example.infrastructure.persistence.dao.ActionIntentDao

    // Gap-closure — canonical durable agent registry (P1-08/P1-09)
    abstract fun agentDefinitionDao(): com.example.infrastructure.persistence.dao.AgentDefinitionDao

    // v14 — durable agent revision ledger (defect family 4)
    abstract fun agentRevisionDao(): com.example.infrastructure.persistence.dao.AgentRevisionDao

    // v13 — durable conversation sessions (report gap: sessions)
    abstract fun conversationSessionDao(): com.example.infrastructure.persistence.dao.ConversationSessionDao
    abstract fun conversationTurnDao(): com.example.infrastructure.persistence.dao.ConversationTurnDao

    // v13 — user-authored workflow library (report gap: workflow assets)
    abstract fun workflowDefinitionDao(): com.example.infrastructure.persistence.dao.WorkflowDefinitionDao

    // v15 — DURABLE human approval requests (audit 2026 §17)
    abstract fun humanApprovalRequestDao(): com.example.infrastructure.persistence.dao.HumanApprovalRequestDao

    // v16 — REPAIR ORDER: unified artifact library, dependency graph,
    // snapshots, audit trail
    abstract fun artifactDao(): com.example.infrastructure.persistence.dao.ArtifactDao
    abstract fun projectDependencyDao(): com.example.infrastructure.persistence.dao.ProjectDependencyDao
    abstract fun projectSnapshotDao(): com.example.infrastructure.persistence.dao.ProjectSnapshotDao
    abstract fun auditEventDao(): com.example.infrastructure.persistence.dao.AuditEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * FIX R-3 (audit c03919d): Migration v1 → v2. Users who installed the
         * very first build (projects/sessions/memory/execution_logs only)
         * previously CRASHED on upgrade ("A migration from 1 to 6 was required
         * but not found") because the migration chain only started at v3.
         * This migration creates the five v2-era tables with their exact
         * historical schemas (no indices — matching the v2 entity definitions).
         */
        private val MIGRATION_1_TO_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        assignedAgentId TEXT NOT NULL,
                        rawPrompt TEXT NOT NULL,
                        lifecycleState TEXT NOT NULL,
                        autonomyPolicy TEXT NOT NULL,
                        resultSummary TEXT,
                        totalTokensConsumed INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        isDegraded INTEGER NOT NULL,
                        degradedReason TEXT,
                        errorMessage TEXT,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS decision_cases (
                        id TEXT NOT NULL PRIMARY KEY,
                        featuresJson TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        targetId TEXT,
                        outcomeReward REAL NOT NULL,
                        taskType TEXT NOT NULL,
                        timestampEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS radar_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        category TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        relevanceScore REAL NOT NULL,
                        confidence REAL NOT NULL,
                        provenance TEXT NOT NULL,
                        tagsJson TEXT NOT NULL,
                        extractedCapabilityJson TEXT,
                        discoveredTimestampEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS evolution_candidates (
                        id TEXT NOT NULL PRIMARY KEY,
                        radarItemId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        targetType TEXT NOT NULL,
                        evaluationNotes TEXT NOT NULL,
                        securityAuditPassed INTEGER NOT NULL,
                        governanceApproved INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        provenanceUrl TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS extension_configs (
                        id TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        endpointOrConfig TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        isConnected INTEGER NOT NULL,
                        healthStatus TEXT NOT NULL,
                        authMetadataJson TEXT,
                        lastVerifiedEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * FIX R-3 (audit c03919d): Migration v2 → v3 — adds the provider_configs
         * table (exact historical v3 schema; the table was dropped from the
         * current entity graph later but remains in the schema for migration
         * chain integrity). Users on v2 previously crashed on upgrade.
         */
        private val MIGRATION_2_TO_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_configs (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        category TEXT NOT NULL,
                        flavor TEXT NOT NULL,
                        endpointUrl TEXT NOT NULL,
                        defaultModelId TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL,
                        healthStatus TEXT NOT NULL,
                        lastValidatedEpochMs INTEGER NOT NULL,
                        lastLatencyMs INTEGER NOT NULL,
                        lastErrorMessage TEXT,
                        extraHeadersJson TEXT,
                        timeoutSeconds INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration v3 → v4: add full-fidelity TaskEntity columns needed by
         * AgentOrchestrator.resumeTask (APP-P0-07 fix). All new columns have
         * defaults so existing rows migrate cleanly.
         */
        private val MIGRATION_3_TO_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN goal TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN currentStepIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN tokenLimit INTEGER NOT NULL DEFAULT 30000")
                db.execSQL("ALTER TABLE tasks ADD COLUMN maxRetries INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE tasks ADD COLUMN allowDegradedExecution INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requireHumanConsentForSensitiveTools INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN timeoutMs INTEGER NOT NULL DEFAULT 60000")
                db.execSQL("ALTER TABLE tasks ADD COLUMN minOutputLengthChars INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN verificationStrategy TEXT NOT NULL DEFAULT 'STRICT'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN assignedModelId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN activeToolsJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requiredCapabilitiesJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requiredEvidenceKeysJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requiredOutputKeysJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN executionLogJson TEXT")
                db.execSQL("UPDATE tasks SET goal = rawPrompt WHERE goal = '' AND rawPrompt != ''")
            }
        }

        /**
         * Phase 2 — Migration v4 → v5: add four new tables for true multi-workspace
         * runtime and RAG persistence. No existing table is altered; the migration
         * is purely additive so it's safe even if the user had data in v4.
         *
         *   workspaces           — multi-workspace registry (one active at a time)
         *   knowledge_documents  — RAG document metadata (was in-memory only before)
         *   document_chunks      — RAG chunk text + embedding vectors (was in-memory only)
         *   resource_edges       — persistent workspace resource graph edges
         *
         * After migration runs, WorkspaceRuntimeService.bootstrapDefaultWorkspace()
         * will create a default workspace (id="default") and mark it active so the
         * existing single-project user flow continues to work without interruption.
         */
        private val MIGRATION_4_TO_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        networkPolicy TEXT NOT NULL,
                        autonomyPolicy TEXT NOT NULL,
                        settingsJson TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        lastActiveProjectId INTEGER,
                        createdAtEpochMs INTEGER NOT NULL,
                        lastAccessedEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspaces_isActive ON workspaces(isActive)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workspaces_createdAtEpochMs ON workspaces(createdAtEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_documents (
                        id TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        projectId INTEGER,
                        title TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tagsJson TEXT NOT NULL,
                        totalChunks INTEGER NOT NULL,
                        totalTokensEstimated INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_workspaceId ON knowledge_documents(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_projectId ON knowledge_documents(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_createdAtEpochMs ON knowledge_documents(createdAtEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        workspaceId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        tokenCount INTEGER NOT NULL,
                        vectorDimension INTEGER NOT NULL,
                        vectorJson TEXT NOT NULL,
                        retrievalSource TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_documentId ON document_chunks(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_workspaceId ON document_chunks(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_chunkIndex ON document_chunks(chunkIndex)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS resource_edges (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        workspaceId TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        targetId TEXT NOT NULL,
                        targetType TEXT NOT NULL,
                        edgeType TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        metadataJson TEXT NOT NULL DEFAULT '{}',
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_workspaceId ON resource_edges(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_sourceId ON resource_edges(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_targetId ON resource_edges(targetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_edges_edgeType ON resource_edges(edgeType)")
            }
        }

        /**
         * Phase 4 — Migration v5 → v6: seven new tables for the Generalized
         * Provider Architecture (providers, provider_services,
         * service_configurations, service_health_records, service_offerings,
         * user_resource_preferences, resource_records). Purely additive — no
         * existing table is altered, so v5 data (workspaces, RAG, tasks) is safe.
         */
        private val MIGRATION_5_TO_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS providers (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        websiteUrl TEXT,
                        isLocal INTEGER NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_services (
                        id TEXT NOT NULL PRIMARY KEY,
                        providerId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        serviceType TEXT NOT NULL,
                        supportedProtocolIdsJson TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_services_providerId ON provider_services(providerId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS service_configurations (
                        id TEXT NOT NULL PRIMARY KEY,
                        serviceId TEXT NOT NULL,
                        protocolId TEXT NOT NULL,
                        endpointUrl TEXT NOT NULL,
                        defaultOfferingId TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL,
                        healthStatus TEXT NOT NULL,
                        lastValidatedEpochMs INTEGER NOT NULL,
                        lastLatencyMs INTEGER NOT NULL,
                        lastErrorMessage TEXT,
                        extraHeadersJson TEXT NOT NULL,
                        timeoutSeconds INTEGER NOT NULL,
                        hasSecretKey INTEGER NOT NULL,
                        authAlias TEXT,
                        configurationVersion INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_service_configurations_serviceId ON service_configurations(serviceId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS service_health_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        serviceConfigurationId TEXT NOT NULL,
                        healthStatus TEXT NOT NULL,
                        lastHealthClassification TEXT NOT NULL,
                        lastValidatedEpochMs INTEGER NOT NULL,
                        lastLatencyMs INTEGER NOT NULL,
                        lastErrorMessage TEXT,
                        validatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_service_health_records_serviceConfigurationId ON service_health_records(serviceConfigurationId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS service_offerings (
                        id TEXT NOT NULL,
                        serviceId TEXT NOT NULL,
                        offeringType TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        contextWindowTokens INTEGER,
                        supportedCapabilitiesJson TEXT NOT NULL,
                        isLocal INTEGER NOT NULL,
                        isAvailable INTEGER NOT NULL,
                        pricingInputTokensPerMillion REAL,
                        pricingOutputTokensPerMillion REAL,
                        latencyScoreMs INTEGER NOT NULL,
                        discoveredEpochMs INTEGER NOT NULL,
                        discoverySource TEXT NOT NULL,
                        PRIMARY KEY(id, serviceId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_resource_preferences (
                        serviceType TEXT NOT NULL PRIMARY KEY,
                        preferredResourceId TEXT NOT NULL,
                        preferredResourceName TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS resource_records (
                        resourceId TEXT NOT NULL PRIMARY KEY,
                        providerId TEXT NOT NULL,
                        serviceId TEXT NOT NULL,
                        resourceType TEXT NOT NULL,
                        capabilitiesJson TEXT NOT NULL,
                        configurationVersion INTEGER NOT NULL,
                        lifecycleState TEXT NOT NULL,
                        runtimeSupported INTEGER NOT NULL,
                        healthStatus TEXT NOT NULL,
                        isLocal INTEGER NOT NULL,
                        metadataJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_resource_records_serviceId ON resource_records(serviceId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_resource_records_providerId ON resource_records(providerId)"
                )
            }
        }

        /**
         * P1 — Migration v6 → v7: adds the `mdp_q_values` table for the
         * persistent tabular-MDP Q-table (per-(region, action) value + visit /
         * success counts). Purely additive — no existing table altered.
         */
        private val MIGRATION_6_TO_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mdp_q_values (
                        regionKey TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        qValue REAL NOT NULL,
                        visitCount INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        lastUpdatedEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(regionKey, actionType)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Phase 5 — Migration v7 → v8: introduces the observability, tool
         * lifecycle, security, workflow persistence, policy versioning,
         * agent memory namespaces, and tool health tables. ALSO extends
         * `memory_records` with five new columns so the MemoryLifecycleService
         * can decay / consolidate / scope memories per workspace and per agent.
         *
         * All new columns on `memory_records` have defaults so existing rows
         * migrate cleanly:
         *   - memory_type = 'FACTUAL_INSIGHT' (preserves current behaviour)
         *   - importance = 1.0
         *   - decay_score = 1.0
         *   - workspace_id = NULL
         *   - agent_id = NULL
         *   - tags_json = '[]'
         *   - last_decay_evaluated_at_epoch_ms = <now>
         */
        private val MIGRATION_7_TO_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- Extend memory_records (additive) ---
                db.execSQL("ALTER TABLE memory_records ADD COLUMN memoryType TEXT NOT NULL DEFAULT 'FACTUAL_INSIGHT'")
                db.execSQL("ALTER TABLE memory_records ADD COLUMN importance REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE memory_records ADD COLUMN decayScore REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE memory_records ADD COLUMN workspaceId TEXT")
                db.execSQL("ALTER TABLE memory_records ADD COLUMN agentId TEXT")
                db.execSQL("ALTER TABLE memory_records ADD COLUMN tagsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE memory_records ADD COLUMN lastDecayEvaluatedAtEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_workspaceId ON memory_records(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_agentId ON memory_records(agentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_memoryType ON memory_records(memoryType)")

                // --- Observability: metric_events ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS metric_events (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        metricType TEXT NOT NULL,
                        dimensionsKey TEXT NOT NULL,
                        executionId TEXT,
                        sessionId TEXT,
                        workspaceId TEXT,
                        providerId TEXT,
                        toolName TEXT,
                        agentId TEXT,
                        resourceType TEXT,
                        actionType TEXT,
                        value INTEGER NOT NULL,
                        attributesJson TEXT NOT NULL,
                        recordedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_events_metricType ON metric_events(metricType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_events_dimensionsKey ON metric_events(dimensionsKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_events_recordedAtEpochMs ON metric_events(recordedAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_events_executionId ON metric_events(executionId)")

                // --- Security: audit_trail ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_trail (
                        id TEXT NOT NULL PRIMARY KEY,
                        severity TEXT NOT NULL,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        resourceType TEXT NOT NULL,
                        resourceId TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        workspaceId TEXT,
                        attributesJson TEXT NOT NULL,
                        occurredAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_trail_severity ON audit_trail(severity)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_trail_actor ON audit_trail(actor)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_trail_resourceType ON audit_trail(resourceType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_trail_resourceId ON audit_trail(resourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_trail_workspaceId ON audit_trail(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_trail_occurredAtEpochMs ON audit_trail(occurredAtEpochMs)")

                // --- Observability: health_probes ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_probes (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        resourceId TEXT NOT NULL,
                        resourceType TEXT NOT NULL,
                        isHealthy INTEGER NOT NULL,
                        latencyMs INTEGER NOT NULL,
                        errorMessage TEXT,
                        probedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_probes_resourceId ON health_probes(resourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_probes_resourceType ON health_probes(resourceType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_probes_probedAtEpochMs ON health_probes(probedAtEpochMs)")

                // --- Observability: execution_trace_nodes ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_trace_nodes (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        executionId TEXT NOT NULL,
                        stepIndex INTEGER NOT NULL,
                        actionType TEXT NOT NULL,
                        targetResourceId TEXT,
                        agentId TEXT,
                        startedAtEpochMs INTEGER NOT NULL,
                        completedAtEpochMs INTEGER,
                        durationMs INTEGER,
                        outcome TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        observationSummary TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_execution_trace_nodes_executionId ON execution_trace_nodes(executionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_execution_trace_nodes_stepIndex ON execution_trace_nodes(stepIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_execution_trace_nodes_startedAtEpochMs ON execution_trace_nodes(startedAtEpochMs)")

                // --- Workflow Intelligence: workflow_executions + workflow_step_states ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_executions (
                        workflowId TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        planJson TEXT NOT NULL,
                        lifecycleState TEXT NOT NULL,
                        currentStepIndex INTEGER NOT NULL,
                        totalSteps INTEGER NOT NULL,
                        startedAtEpochMs INTEGER NOT NULL,
                        lastCheckpointAtEpochMs INTEGER NOT NULL,
                        completedAtEpochMs INTEGER,
                        failureReason TEXT,
                        cancellationReason TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_executions_workspaceId ON workflow_executions(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_executions_lifecycleState ON workflow_executions(lifecycleState)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_executions_startedAtEpochMs ON workflow_executions(startedAtEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_step_states (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        workflowId TEXT NOT NULL,
                        stepId TEXT NOT NULL,
                        stepIndex INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        outputSummary TEXT,
                        durationMs INTEGER,
                        startedAtEpochMs INTEGER,
                        completedAtEpochMs INTEGER,
                        attemptCount INTEGER NOT NULL DEFAULT 0,
                        lastErrorMessage TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_step_states_workflowId ON workflow_step_states(workflowId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_step_states_stepId ON workflow_step_states(stepId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_step_states_status ON workflow_step_states(status)")

                // --- Tool Ecosystem: tool_audit_log ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tool_audit_log (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        toolName TEXT NOT NULL,
                        toolVersion TEXT NOT NULL,
                        executionId TEXT NOT NULL,
                        callerAgentId TEXT,
                        workspaceId TEXT,
                        argumentsHash TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        failureCode TEXT,
                        durationMs INTEGER NOT NULL,
                        tokenCostEstimate INTEGER NOT NULL,
                        occurredAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_audit_log_toolName ON tool_audit_log(toolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_audit_log_executionId ON tool_audit_log(executionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_audit_log_outcome ON tool_audit_log(outcome)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_audit_log_occurredAtEpochMs ON tool_audit_log(occurredAtEpochMs)")

                // --- Security: permission_grants ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS permission_grants (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        principalType TEXT NOT NULL,
                        principalId TEXT NOT NULL,
                        resourceType TEXT NOT NULL,
                        resourceId TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        isAllowed INTEGER NOT NULL,
                        grantedBy TEXT NOT NULL,
                        grantedAtEpochMs INTEGER NOT NULL,
                        expiresAtEpochMs INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_grants_principalType ON permission_grants(principalType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_grants_principalId ON permission_grants(principalId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_grants_resourceType ON permission_grants(resourceType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_grants_resourceId ON permission_grants(resourceId)")

                // --- Evolution: policy_versions ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS policy_versions (
                        versionId TEXT NOT NULL PRIMARY KEY,
                        policyKind TEXT NOT NULL,
                        versionLabel TEXT NOT NULL,
                        snapshotJson TEXT NOT NULL,
                        evaluationReportJson TEXT,
                        isPromoted INTEGER NOT NULL,
                        promotedBy TEXT NOT NULL,
                        promotedAtEpochMs INTEGER,
                        createdAtEpochMs INTEGER NOT NULL,
                        parentVersionId TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_policy_versions_policyKind ON policy_versions(policyKind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_policy_versions_isPromoted ON policy_versions(isPromoted)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_policy_versions_createdAtEpochMs ON policy_versions(createdAtEpochMs)")

                // --- Memory: agent_memory_namespaces ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_memory_namespaces (
                        namespaceId TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        memoryScope TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        isActive INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memory_namespaces_workspaceId ON agent_memory_namespaces(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memory_namespaces_agentId ON agent_memory_namespaces(agentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memory_namespaces_isActive ON agent_memory_namespaces(isActive)")

                // --- Tool Ecosystem: tool_lifecycle_states ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tool_lifecycle_states (
                        toolId TEXT NOT NULL PRIMARY KEY,
                        toolName TEXT NOT NULL,
                        version TEXT NOT NULL,
                        lifecycleState TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        timeoutMs INTEGER NOT NULL,
                        maxRetries INTEGER NOT NULL,
                        retryBackoffMs INTEGER NOT NULL,
                        registeredAtEpochMs INTEGER NOT NULL,
                        lastValidatedAtEpochMs INTEGER,
                        lastExecutedAtEpochMs INTEGER,
                        revokedAtEpochMs INTEGER,
                        revokeReason TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_lifecycle_states_toolName ON tool_lifecycle_states(toolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_lifecycle_states_lifecycleState ON tool_lifecycle_states(lifecycleState)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_lifecycle_states_isEnabled ON tool_lifecycle_states(isEnabled)")

                // --- Tool Ecosystem: tool_health_snapshots ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tool_health_snapshots (
                        toolId TEXT NOT NULL PRIMARY KEY,
                        totalCalls INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        failureCount INTEGER NOT NULL,
                        degradedCount INTEGER NOT NULL,
                        averageLatencyMs REAL NOT NULL,
                        p95LatencyMs INTEGER NOT NULL,
                        lastFailureCode TEXT,
                        lastErrorMessage TEXT,
                        circuitState TEXT NOT NULL,
                        openedAtEpochMs INTEGER,
                        lastUpdatedEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_health_snapshots_toolId ON tool_health_snapshots(toolId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_health_snapshots_isHealthy ON tool_health_snapshots(circuitState)")
            }
        }

        /**
         * v8 → v9 — Durable execution (audit 2026 fix).
         *
         * Adds to `tasks`:
         *  - parentTaskId / delegationDepth — real multi-agent delegation lineage
         *    (children persist their own rows and can be traced to the parent).
         *  - checkpointJson — the closed-loop checkpoint (step, evidence, output,
         *    tokens) so a task that survives process death can be RESUMED
         *    instead of silently re-run from step 0.
         */
        private val MIGRATION_8_TO_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN parentTaskId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN delegationDepth INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN checkpointJson TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_parentTaskId ON tasks(parentTaskId)")
            }
        }

        /**
         * v9 → v10 — Governance Phase (Capability Radar + Economic Budget).
         *
         * Adds seven tables (all additive; existing v9 data untouched):
         *   capability_evidence, radar_capability_states, capability_changes,
         *   radar_recommendations, pricing_entries, cost_ledger_entries,
         *   budget_allocations.
         */
        private val MIGRATION_9_TO_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS capability_evidence (
                        id TEXT NOT NULL PRIMARY KEY,
                        capabilityKey TEXT NOT NULL,
                        source TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        providerId TEXT,
                        serviceId TEXT,
                        modelId TEXT,
                        resourceId TEXT,
                        executionId TEXT,
                        workspaceId TEXT,
                        agentId TEXT,
                        detail TEXT,
                        timestampEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_evidence_capabilityKey_timestampEpochMs ON capability_evidence(capabilityKey, timestampEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_evidence_workspaceId ON capability_evidence(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_evidence_executionId ON capability_evidence(executionId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS radar_capability_states (
                        capabilityKey TEXT NOT NULL,
                        workspaceId TEXT NOT NULL,
                        state TEXT NOT NULL,
                        dimensionsJson TEXT NOT NULL,
                        health TEXT NOT NULL,
                        trend TEXT NOT NULL,
                        evidenceCount INTEGER NOT NULL,
                        lastEvidenceEpochMs INTEGER,
                        contributingResourceIdsJson TEXT NOT NULL,
                        rationale TEXT NOT NULL,
                        derivedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(capabilityKey, workspaceId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radar_capability_states_workspaceId ON radar_capability_states(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radar_capability_states_state ON radar_capability_states(state)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS capability_changes (
                        id TEXT NOT NULL PRIMARY KEY,
                        capabilityKey TEXT NOT NULL,
                        workspaceId TEXT,
                        fromState TEXT NOT NULL,
                        toState TEXT NOT NULL,
                        changeType TEXT NOT NULL,
                        evidenceId TEXT,
                        detail TEXT NOT NULL,
                        detectedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_changes_workspaceId_detectedAtEpochMs ON capability_changes(workspaceId, detectedAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capability_changes_capabilityKey ON capability_changes(capabilityKey)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS radar_recommendations (
                        id TEXT NOT NULL PRIMARY KEY,
                        capabilityKey TEXT NOT NULL,
                        workspaceId TEXT,
                        type TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        message TEXT NOT NULL,
                        actionHint TEXT,
                        supportingEvidenceIdsJson TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        isDismissed INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radar_recommendations_workspaceId_isDismissed ON radar_recommendations(workspaceId, isDismissed)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radar_recommendations_capabilityKey ON radar_recommendations(capabilityKey)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pricing_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        scopeType TEXT NOT NULL,
                        providerId TEXT NOT NULL,
                        serviceId TEXT,
                        modelId TEXT,
                        inputPriceMicroPerMillion INTEGER,
                        outputPriceMicroPerMillion INTEGER,
                        cachedInputPriceMicroPerMillion INTEGER,
                        currency TEXT NOT NULL,
                        billingClass TEXT NOT NULL,
                        pricingVersion TEXT NOT NULL,
                        effectiveFromEpochMs INTEGER NOT NULL,
                        effectiveToEpochMs INTEGER,
                        provenance TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pricing_entries_providerId ON pricing_entries(providerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pricing_entries_scopeType_providerId_serviceId_modelId ON pricing_entries(scopeType, providerId, serviceId, modelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pricing_entries_effectiveFromEpochMs ON pricing_entries(effectiveFromEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cost_ledger_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        executionId TEXT NOT NULL,
                        taskId TEXT,
                        workspaceId TEXT,
                        agentId TEXT,
                        providerId TEXT,
                        serviceId TEXT,
                        modelId TEXT,
                        resourceId TEXT,
                        inputTokens INTEGER NOT NULL,
                        outputTokens INTEGER NOT NULL,
                        cachedTokens INTEGER NOT NULL,
                        totalTokens INTEGER NOT NULL,
                        isEstimate INTEGER NOT NULL,
                        appliedInputPriceMicroPerMillion INTEGER,
                        appliedOutputPriceMicroPerMillion INTEGER,
                        appliedCachedInputPriceMicroPerMillion INTEGER,
                        appliedPricingVersion TEXT,
                        costAmountMicro INTEGER,
                        currency TEXT NOT NULL,
                        costStatus TEXT NOT NULL,
                        billingClass TEXT NOT NULL,
                        timestampEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cost_ledger_entries_workspaceId_timestampEpochMs ON cost_ledger_entries(workspaceId, timestampEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cost_ledger_entries_executionId ON cost_ledger_entries(executionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cost_ledger_entries_providerId ON cost_ledger_entries(providerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cost_ledger_entries_taskId ON cost_ledger_entries(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cost_ledger_entries_agentId ON cost_ledger_entries(agentId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budget_allocations (
                        scopeType TEXT NOT NULL,
                        scopeId TEXT NOT NULL,
                        allocatedAmountMicro INTEGER NOT NULL,
                        currency TEXT NOT NULL,
                        policyActionsCsv TEXT NOT NULL,
                        warnThresholdRatio REAL NOT NULL,
                        policyNote TEXT,
                        isActive INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(scopeType, scopeId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_allocations_scopeType ON budget_allocations(scopeType)")
            }
        }

        /**
         * v10 → v11 — Gap-closure (Canonical Execution Kernel + Agent Registry).
         *
         *  1. `tasks.executionContextJson` — serialized CanonicalExecutionContext
         *     (stable executionId + pinned workspace/project/agent/model + attempt).
         *  2. `action_intents` — the ACTION IDEMPOTENCY LEDGER: durable
         *     intention → side-effect → completion records for exactly-once
         *     recovery (P0-05 / P0-06).
         *  3. `agent_definitions` — CANONICAL durable agent registry shared by
         *     the UI catalog and the runtime (P1-08 / P1-09).
         */
        private val MIGRATION_10_TO_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN executionContextJson TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS action_intents (
                        executionId TEXT NOT NULL,
                        actionKey TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        targetId TEXT,
                        stepIndex INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        outputFingerprint TEXT,
                        outputSummary TEXT,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(executionId, actionKey)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_action_intents_executionId ON action_intents(executionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_action_intents_state ON action_intents(state)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_definitions (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        role TEXT NOT NULL,
                        description TEXT NOT NULL,
                        systemPrompt TEXT NOT NULL,
                        capabilitiesJson TEXT NOT NULL,
                        workspaceScopeJson TEXT NOT NULL,
                        maxTokens INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        origin TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * P0 CONVERGENCE — Migration v11 → v12 (audit step 12 §6/§7 + §4):
         *
         *  1. WORKSPACE OWNERSHIP UNIFICATION:
         *     - `projects` gains a `workspaceId` column (explicit ownership
         *       backfilled from the `workspaces.lastActiveProjectId` bridge —
         *       previously ownership was only inferable and ambiguous).
         *     - The legacy implicit project id=1L is MATERIALIZED as a real
         *       owned row when (and only when) a workspace still references
         *       it — a data repair that kills the magic reference.
         *     - `workspaces.lastActiveProjectId = 1` rows keep pointing at a
         *       REAL row from now on; fresh installs never reference 1L at
         *       all (WorkspaceRuntimeService creates an owned project).
         *
         *  2. LEGACY REMNANT REMOVAL: the `sessions` table is dropped. It was
         *    project-scoped (never workspace-scoped) and had zero production
         *    readers/writers — a dead remnant of the pre-workspace era.
         *
         *  3. RAG METADATA DURABILITY:
         *     - `knowledge_documents` is rebuilt WITHOUT the dead `projectId`
         *       column (always NULL since Phase 2, never read) and WITH the
         *       previously-dropped `mimeType` column.
         *     - `document_chunks` gains `metadataJson` so chunk metadata
         *       (embedding provenance, tags, filters) survives restarts.
         *
         *  4. RESOURCE-AWARE DECISION LEARNING: `mdp_q_values` is rebuilt
         *    with a `resourceKey` axis in the primary key so the tabular MDP
         *    can learn per-resource/model performance instead of collapsing
         *    every resource into one cell. Legacy rows map to "R:none" —
         *    exactly the axis where resource-less actions keep learning.
         */
        private val MIGRATION_11_TO_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                // --- 1. Workspace ownership of projects -------------------
                db.execSQL("ALTER TABLE projects ADD COLUMN workspaceId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_workspaceId ON projects(workspaceId)")
                db.execSQL(
                    """
                    UPDATE projects SET workspaceId = (
                        SELECT w.id FROM workspaces w
                        WHERE w.lastActiveProjectId = projects.id
                        ORDER BY w.lastAccessedEpochMs DESC LIMIT 1
                    ) WHERE workspaceId IS NULL
                    """.trimIndent()
                )
                // Materialize the legacy implicit 1L reference as a REAL owned row.
                db.execSQL(
                    """
                    INSERT INTO projects (id, name, description, rootPath, createdAtEpochMs, updatedAtEpochMs, isArchived, workspaceId)
                    SELECT 1, 'المشروع الافتراضي (مُهاجر)', 'مشروع sandbox مُنشأ من المرجع الضمني القديم 1L أثناء توحيد ملكية مساحات العمل', 'workspaces/proj_1', $now, $now, 0, 'default'
                    WHERE NOT EXISTS (SELECT 1 FROM projects WHERE id = 1)
                      AND EXISTS (SELECT 1 FROM workspaces WHERE lastActiveProjectId = 1)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE projects SET workspaceId = 'default'
                    WHERE id = 1 AND workspaceId IS NULL
                      AND EXISTS (SELECT 1 FROM workspaces WHERE id = 'default' AND lastActiveProjectId = 1)
                    """.trimIndent()
                )

                // --- 2. Drop the dead project-scoped sessions remnant ------
                db.execSQL("DROP TABLE IF EXISTS sessions")

                // --- 3a. knowledge_documents: drop dead projectId, add mimeType.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_documents_v12 (
                        id TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        content TEXT NOT NULL,
                        mimeType TEXT NOT NULL DEFAULT 'text/markdown',
                        tagsJson TEXT NOT NULL,
                        totalChunks INTEGER NOT NULL,
                        totalTokensEstimated INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO knowledge_documents_v12 (id, workspaceId, title, sourceUri, content, mimeType, tagsJson, totalChunks, totalTokensEstimated, createdAtEpochMs, updatedAtEpochMs, isArchived)
                    SELECT id, workspaceId, title, sourceUri, content, 'text/markdown', tagsJson, totalChunks, totalTokensEstimated, createdAtEpochMs, updatedAtEpochMs, isArchived
                    FROM knowledge_documents
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE knowledge_documents")
                db.execSQL("ALTER TABLE knowledge_documents_v12 RENAME TO knowledge_documents")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_workspaceId ON knowledge_documents(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_createdAtEpochMs ON knowledge_documents(createdAtEpochMs)")

                // --- 3b. document_chunks: persist chunk metadata.
                db.execSQL("ALTER TABLE document_chunks ADD COLUMN metadataJson TEXT NOT NULL DEFAULT '{}'")

                // --- 4. Resource-aware MDP Q-table primary key.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mdp_q_values_v12 (
                        regionKey TEXT NOT NULL,
                        resourceKey TEXT NOT NULL DEFAULT 'R:none',
                        actionType TEXT NOT NULL,
                        qValue REAL NOT NULL,
                        visitCount INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        lastUpdatedEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(regionKey, resourceKey, actionType)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO mdp_q_values_v12 (regionKey, resourceKey, actionType, qValue, visitCount, successCount, lastUpdatedEpochMs)
                    SELECT regionKey, 'R:none', actionType, qValue, visitCount, successCount, lastUpdatedEpochMs
                    FROM mdp_q_values
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE mdp_q_values")
                db.execSQL("ALTER TABLE mdp_q_values_v12 RENAME TO mdp_q_values")
            }
        }



        /**
         * v12 → v13 (report gap-closure round):
         *  1. `chat_sessions` + `chat_turns` — RESTORES a durable session
         *     system, born workspace-owned (the legacy `sessions` table was
         *     dropped in v12 without a durable replacement; the conversation
         *     transcript lived only in the ViewModel and died with the
         *     process). Sessions carry mode (QUICK_CHAT/AGENT) and optional
         *     exact model pins.
         *  2. `workflow_definitions` — the USER-AUTHORED workflow library:
         *     save / list / load / edit / clone / run (the execution was
         *     durable since v8, but the authored definition was not).
         *  3. `agent_definitions` gains full-fidelity columns (goals,
         *     networkRequirement, locality, authorityLevel) — previously
         *     dropped on every save, so round-tripping a durable agent lost
         *     its goals and runtime policies.
         * Purely additive — no existing column altered or dropped.
         */
        private val MIGRATION_12_TO_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- 1. Durable conversation sessions ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        sessionId TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        agentId TEXT,
                        agentName TEXT,
                        modelResourceId TEXT,
                        modelDisplayName TEXT,
                        turnCount INTEGER NOT NULL DEFAULT 0,
                        totalTokensConsumed INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMs INTEGER NOT NULL,
                        lastActiveAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_sessions_workspaceId ON chat_sessions(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_sessions_lastActiveAtEpochMs ON chat_sessions(lastActiveAtEpochMs)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_turns (
                        turnId TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        answer TEXT NOT NULL,
                        agentName TEXT,
                        agentRole TEXT,
                        modelResourceId TEXT,
                        tokensConsumed INTEGER NOT NULL DEFAULT 0,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        isSuccessful INTEGER NOT NULL DEFAULT 1,
                        eventCount INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_turns_sessionId ON chat_turns(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_turns_createdAtEpochMs ON chat_turns(createdAtEpochMs)")

                // --- 2. User-authored workflow library ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_definitions (
                        workflowId TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        goal TEXT NOT NULL,
                        executionMode TEXT NOT NULL,
                        stepsJson TEXT NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        runCount INTEGER NOT NULL DEFAULT 0,
                        lastRunAtEpochMs INTEGER,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_definitions_workspaceId ON workflow_definitions(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_definitions_updatedAtEpochMs ON workflow_definitions(updatedAtEpochMs)")

                // --- 3. Full-fidelity durable agents ---
                db.execSQL("ALTER TABLE agent_definitions ADD COLUMN goalsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE agent_definitions ADD COLUMN networkRequirement TEXT NOT NULL DEFAULT 'HYBRID'")
                db.execSQL("ALTER TABLE agent_definitions ADD COLUMN locality TEXT NOT NULL DEFAULT 'LOCAL_ON_DEVICE'")
                db.execSQL("ALTER TABLE agent_definitions ADD COLUMN authorityLevel TEXT NOT NULL DEFAULT 'STANDARD'")
            }
        }

        /**
         * v13 → v14 (cumulative correctness & authority repair order):
         *   1. `permission_grants.workspaceId` — workspace context becomes
         *      part of authorization (defect family 2). Existing rows keep
         *      NULL = explicitly GLOBAL grants.
         *   2. `agent_revisions` — the durable agent revision ledger (defect
         *      family 4: version chains survive process death).
         *   3. `workflow_step_states.artifactsJson` — durable artifact/
         *      dataflow payloads so workflow resume restores the explicit
         *      dataflow, not just a 200-char summary (defect family 7).
         * Purely additive — no existing column altered or dropped.
         */
        private val MIGRATION_13_TO_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- 1. Workspace-scoped permission grants ---
                db.execSQL("ALTER TABLE permission_grants ADD COLUMN workspaceId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_permission_grants_workspaceId ON permission_grants(workspaceId)")

                // --- 2. Durable agent revision ledger ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_revisions (
                        agentId TEXT NOT NULL,
                        revisionId TEXT NOT NULL,
                        major INTEGER NOT NULL,
                        minor INTEGER NOT NULL,
                        patch INTEGER NOT NULL,
                        previousVersionId TEXT,
                        snapshotJson TEXT NOT NULL,
                        createdBy TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(agentId, revisionId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_revisions_agentId ON agent_revisions(agentId)")

                // --- 3. Durable workflow artifacts ---
                db.execSQL("ALTER TABLE workflow_step_states ADD COLUMN artifactsJson TEXT")
            }
        }



        /**
         * v14 → v15 (audit 2026 remediation — P1-7 / P1-10 / §17):
         *  1. Explicit workspace identity on `tasks`, `execution_logs` and
         *     `execution_trace_nodes` (+ indices) — ownership queries are
         *     now workspace-scoped at the SQL boundary.
         *  2. Delegation lineage index on `tasks(parentTaskId)` for the
         *     orphaned-children recovery reconciliation.
         *  3. NEW `human_approval_requests` table — DURABLE approval state
         *     (previously in-memory only; approvals died with the process).
         * All additions are nullable/defaulted columns and a new table —
         * existing data survives untouched.
         */
        private val MIGRATION_14_TO_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- 1. Workspace identity columns ---
                db.execSQL("ALTER TABLE tasks ADD COLUMN workspaceId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_workspaceId ON tasks(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_parentTaskId ON tasks(parentTaskId)")
                db.execSQL("ALTER TABLE execution_logs ADD COLUMN workspaceId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_execution_logs_workspaceId ON execution_logs(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_execution_logs_executionId ON execution_logs(executionId)")
                db.execSQL("ALTER TABLE execution_trace_nodes ADD COLUMN workspaceId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_execution_trace_nodes_workspaceId ON execution_trace_nodes(workspaceId)")

                // --- 2. Durable human approval requests ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS human_approval_requests (
                        approvalId TEXT NOT NULL PRIMARY KEY,
                        executionId TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        riskLevel TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        justification TEXT NOT NULL,
                        requestedAtEpochMs INTEGER NOT NULL,
                        expiresAtEpochMs INTEGER NOT NULL,
                        resolution TEXT NOT NULL,
                        resolvedBy TEXT,
                        resolvedAtEpochMs INTEGER,
                        isTokenConsumed INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_human_approval_requests_executionId ON human_approval_requests(executionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_human_approval_requests_toolName ON human_approval_requests(toolName)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_human_approval_requests_resolution ON human_approval_requests(resolution)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_human_approval_requests_expiresAtEpochMs ON human_approval_requests(expiresAtEpochMs)"
                )
            }
        }


        /**
         * REPAIR ORDER §5/§7/§15/§24/§26/§27/§30 — DB v15 → v16:
         *
         *  1. PROJECT LIFECYCLE (§27): projects += lifecycleState/
         *     archivedAtEpochMs/trashedAtEpochMs (+lifecycleState index);
         *     legacy isArchived=1 backfills to 'ARCHIVED'.
         *  2. PROJECT OWNERSHIP COLUMNS (§5/§15): knowledge_documents,
         *     chat_sessions and tasks gain a queryable nullable projectId;
         *     tasks.projectId is backfilled by decoding executionContextJson
         *     (regex on the pinned context — deterministic, no JSON lib in
         *     raw SQL); knowledge/session rows backfill to NULL (honest
         *     workspace-shared scope).
         *  3. UNIFIED ARTIFACT LIBRARY (§7): new `artifacts` table.
         *  4. PROJECT DEPENDENCY GRAPH (§24): new `project_dependencies`.
         *  5. SNAPSHOTS (§26): new `project_snapshots`.
         *  6. UNIFIED AUDIT TRAIL (§30): new `audit_events`.
         *
         * Purely additive columns/tables — all new columns nullable or
         * defaulted; existing data survives untouched.
         */
        private val MIGRATION_15_TO_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- 0. GAP-01 schema reconciliation (Design Closure 2026) ---
                // MIGRATION_7_TO_8 created only 4 of the 5 indices declared by
                // ToolAuditEntity (index_tool_audit_log_callerAgentId was never
                // created), so every upgraded database fails Room's
                // validateMigration index check. Creating it HERE (in the last
                // migration, idempotently) converges every upgrade path ≤15
                // and fresh installs onto the entity-declared schema. Additive
                // and safe: an extra index never changes query semantics.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tool_audit_log_callerAgentId ON tool_audit_log(callerAgentId)"
                )

                // --- 1. Project lifecycle state machine (§27) ---
                db.execSQL("ALTER TABLE projects ADD COLUMN lifecycleState TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE projects ADD COLUMN archivedAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE projects ADD COLUMN trashedAtEpochMs INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_lifecycleState ON projects(lifecycleState)")
                db.execSQL("UPDATE projects SET lifecycleState = 'ARCHIVED', archivedAtEpochMs = updatedAtEpochMs WHERE isArchived = 1")

                // --- 2. Project ownership columns (§5/§15) ---
                db.execSQL("ALTER TABLE knowledge_documents ADD COLUMN projectId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_projectId ON knowledge_documents(projectId)")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN projectId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_sessions_projectId ON chat_sessions(projectId)")
                db.execSQL("ALTER TABLE tasks ADD COLUMN projectId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_projectId ON tasks(projectId)")
                // Deterministic backfill of tasks.projectId from the pinned
                // canonical execution context JSON. SQLite instr/substr keep
                // this migration dependency-free. Handles BOTH terminators
                // (',' when projectId is mid-JSON, '}' when it is last).
                db.execSQL(
                    """
                    UPDATE tasks SET projectId = CAST(
                        replace(
                            trim(
                                substr(
                                    substr(executionContextJson, instr(executionContextJson, '"projectId":') + 12),
                                    1,
                                    CASE
                                        WHEN instr(substr(executionContextJson, instr(executionContextJson, '"projectId":') + 12), ',') > 0
                                        THEN instr(substr(executionContextJson, instr(executionContextJson, '"projectId":') + 12), ',') - 1
                                        WHEN instr(substr(executionContextJson, instr(executionContextJson, '"projectId":') + 12), '}') > 0
                                        THEN instr(substr(executionContextJson, instr(executionContextJson, '"projectId":') + 12), '}') - 1
                                        ELSE 0
                                    END
                                )
                            ),
                            ' ', ''
                        ) AS INTEGER
                    )
                    WHERE executionContextJson IS NOT NULL
                      AND executionContextJson LIKE '%"projectId":%'
                      AND projectId IS NULL
                    """.trimIndent()
                )
                // --- 2b. REPAIR ORDER §19: action-space version binding for
                //     learned Q-values (legacy rows stay NULL = pre-versioning
                //     and are invalidated honestly at engine load).
                db.execSQL("ALTER TABLE mdp_q_values ADD COLUMN actionSpaceVersion TEXT")

                // --- 3. Unified artifact library (§7) ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artifacts (
                        id TEXT NOT NULL PRIMARY KEY,
                        workspaceId TEXT,
                        projectId INTEGER,
                        sessionId TEXT,
                        taskId TEXT,
                        executionId TEXT,
                        workflowId TEXT,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        mimeType TEXT NOT NULL DEFAULT 'application/octet-stream',
                        sizeBytes INTEGER NOT NULL DEFAULT 0,
                        contentHash TEXT,
                        storageUri TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'USER',
                        securityClassification TEXT NOT NULL DEFAULT 'UNCLASSIFIED',
                        indexingState TEXT NOT NULL DEFAULT 'NOT_INDEXED',
                        ownerId TEXT,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        metadataJson TEXT NOT NULL DEFAULT '{}'
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_workspaceId ON artifacts(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_projectId ON artifacts(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_type ON artifacts(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_createdAtEpochMs ON artifacts(createdAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_workspaceId_projectId ON artifacts(workspaceId, projectId)")

                // --- 4. Project dependency graph (§24) ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS project_dependencies (
                        projectId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        requirement TEXT NOT NULL,
                        status TEXT NOT NULL,
                        detail TEXT,
                        metadataJson TEXT NOT NULL DEFAULT '{}',
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(projectId, type, `key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_project_dependencies_projectId ON project_dependencies(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_project_dependencies_type ON project_dependencies(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_project_dependencies_key ON project_dependencies(`key`)")

                // --- 5. Snapshots (§26) ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS project_snapshots (
                        id TEXT NOT NULL PRIMARY KEY,
                        projectId INTEGER NOT NULL,
                        workspaceId TEXT NOT NULL,
                        label TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        contentHash TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_project_snapshots_projectId ON project_snapshots(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_project_snapshots_workspaceId ON project_snapshots(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_project_snapshots_createdAtEpochMs ON project_snapshots(createdAtEpochMs)")

                // --- 6. Unified audit trail (§30) ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        actorType TEXT NOT NULL,
                        actorId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        resourceType TEXT NOT NULL,
                        resourceId TEXT,
                        sourceScopeType TEXT NOT NULL,
                        sourceScopeId TEXT NOT NULL,
                        targetScopeType TEXT,
                        targetScopeId TEXT,
                        policy TEXT,
                        result TEXT NOT NULL,
                        reason TEXT,
                        workspaceId TEXT,
                        projectId INTEGER,
                        occurredAtEpochMs INTEGER NOT NULL,
                        metadataJson TEXT NOT NULL DEFAULT '{}'
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_occurredAtEpochMs ON audit_events(occurredAtEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_action ON audit_events(action)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_workspaceId ON audit_events(workspaceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_projectId ON audit_events(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_resourceType ON audit_events(resourceType)")
            }
        }

        /**
         * GAP-08 (Design Closure 2026, ADR-7 "delete" branch): v17 drops the
         * three dead tables — `provider_configs` (legacy provider island;
         * its entity graph was removed from the codebase long ago, only the
         * migration-chain remnant remained), `policy_versions` (evolution
         * tail: PolicyVersionService had zero production consumers) and
         * `health_probes` (write-only table — recordHealthProbe was never
         * called in production). All three are DROP-only: no surviving
         * reader loses data, and every historical upgrade path (v1..v16)
         * converges on the same end state.
         *
         * Destructive-by-design and ADR-sanctioned; the v16→v17 identity is
         * validated by MigrationChainValidationTest (v1→17 and v15→17) plus
         * Migration16to17Test (data survival of the surviving tables).
         */
        private val MIGRATION_16_TO_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS provider_configs")
                db.execSQL("DROP TABLE IF EXISTS policy_versions")
                db.execSQL("DROP TABLE IF EXISTS health_probes")
            }
        }

        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            // FIX R-3: complete the chain from the earliest shipped schema (v1)
            // so upgrades never crash with "migration not found".
            MIGRATION_1_TO_2,
            MIGRATION_2_TO_3,
            MIGRATION_3_TO_4,
            MIGRATION_4_TO_5,
            MIGRATION_5_TO_6,
            MIGRATION_6_TO_7,
            MIGRATION_7_TO_8,
            MIGRATION_8_TO_9,
            MIGRATION_9_TO_10,
            MIGRATION_10_TO_11,
            MIGRATION_11_TO_12,
            MIGRATION_12_TO_13,
            MIGRATION_13_TO_14,
            MIGRATION_14_TO_15,
            MIGRATION_15_TO_16,
            MIGRATION_16_TO_17,
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agent_orchestrator_platform.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
