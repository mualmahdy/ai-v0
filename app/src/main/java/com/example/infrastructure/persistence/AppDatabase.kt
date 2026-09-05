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
import com.example.infrastructure.persistence.dao.HealthProbeDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.dao.MemoryDao
import com.example.infrastructure.persistence.dao.MetricEventDao
import com.example.infrastructure.persistence.dao.PermissionGrantDao
import com.example.infrastructure.persistence.dao.PolicyVersionDao
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.ProviderConfigDao
import com.example.infrastructure.persistence.dao.ProviderDao
import com.example.infrastructure.persistence.dao.ProviderServiceDao
import com.example.infrastructure.persistence.dao.RadarItemDao
import com.example.infrastructure.persistence.dao.ResourceEdgeDao
import com.example.infrastructure.persistence.dao.ResourceRecordDao
import com.example.infrastructure.persistence.dao.ServiceConfigurationDao
import com.example.infrastructure.persistence.dao.ServiceHealthRecordDao
import com.example.infrastructure.persistence.dao.ServiceOfferingDao
import com.example.infrastructure.persistence.dao.SessionDao
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
import com.example.infrastructure.persistence.entities.HealthProbeEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import com.example.infrastructure.persistence.entities.MemoryEntity
import com.example.infrastructure.persistence.entities.MetricEventEntity
import com.example.infrastructure.persistence.entities.PermissionGrantEntity
import com.example.infrastructure.persistence.entities.PolicyVersionEntity
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.ProviderConfigEntity
import com.example.infrastructure.persistence.entities.ProviderEntity
import com.example.infrastructure.persistence.entities.ProviderServiceEntity
import com.example.infrastructure.persistence.entities.RadarItemEntity
import com.example.infrastructure.persistence.entities.ResourceEdgeEntity
import com.example.infrastructure.persistence.entities.ResourceRecordEntity
import com.example.infrastructure.persistence.entities.ServiceConfigurationEntity
import com.example.infrastructure.persistence.entities.ServiceHealthRecordEntity
import com.example.infrastructure.persistence.entities.ServiceOfferingEntity
import com.example.infrastructure.persistence.entities.SessionEntity
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
 *   3. Set `exportSchema = true` once the schemas/ directory is wired in build.gradle.
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
 */
@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MemoryEntity::class,
        ExecutionLogEntity::class,
        TaskEntity::class,
        DecisionCaseEntity::class,
        RadarItemEntity::class,
        EvolutionCandidateEntity::class,
        ExtensionConfigEntity::class,
        ProviderConfigEntity::class,
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
        HealthProbeEntity::class,
        ExecutionTraceNodeEntity::class,
        WorkflowExecutionEntity::class,
        WorkflowStepStateEntity::class,
        ToolAuditEntity::class,
        PermissionGrantEntity::class,
        PolicyVersionEntity::class,
        AgentMemoryNamespaceEntity::class,
        ToolLifecycleStateEntity::class,
        ToolHealthSnapshotEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun taskDao(): TaskDao
    abstract fun decisionCaseDao(): DecisionCaseDao
    abstract fun radarItemDao(): RadarItemDao
    abstract fun evolutionCandidateDao(): EvolutionCandidateDao
    abstract fun extensionConfigDao(): ExtensionConfigDao
    abstract fun providerConfigDao(): ProviderConfigDao
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
    abstract fun healthProbeDao(): HealthProbeDao
    abstract fun executionTraceDao(): ExecutionTraceDao

    // Phase 5 — Tool lifecycle / health / audit
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun toolLifecycleDao(): ToolLifecycleDao
    abstract fun toolHealthDao(): ToolHealthDao

    // Phase 5 — Security / Permissions / Policy versions
    abstract fun permissionGrantDao(): PermissionGrantDao
    abstract fun policyVersionDao(): PolicyVersionDao

    // Phase 5 — Workflow persistence (resume after process death)
    abstract fun workflowExecutionDao(): WorkflowExecutionDao
    abstract fun workflowStepStateDao(): WorkflowStepStateDao

    // Phase 5 — Agent memory namespaces
    abstract fun agentMemoryNamespaceDao(): AgentMemoryNamespaceDao

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
