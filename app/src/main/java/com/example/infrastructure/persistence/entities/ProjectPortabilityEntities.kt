package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * DB v16 — REPAIR ORDER §7 (unified artifact/resource library)
 * ============================================================================
 * One application-wide logical resource library. `artifacts` is the index
 * of every addressable resource (files, folders, transcripts, packages,
 * snapshots, reports…). Scope columns define ownership:
 *   - workspaceId NULL + projectId NULL  → APPLICATION-scoped
 *   - workspaceId set  + projectId NULL  → WORKSPACE-scoped (shared)
 *   - workspaceId set  + projectId set   → PROJECT-scoped (private)
 *
 * SEPARATION OF CONCERNS: artifact EXISTENCE (this table) is independent
 * from knowledge INDEXING (indexingState) — an artifact may exist without
 * being indexed.
 */
@Entity(
    tableName = "artifacts",
    indices = [
        Index("workspaceId"),
        Index("projectId"),
        Index("type"),
        Index("createdAtEpochMs"),
        Index(value = ["workspaceId", "projectId"])
    ]
)
data class ArtifactEntity(
    @PrimaryKey
    val id: String,
    val workspaceId: String? = null,
    val projectId: Long? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val executionId: String? = null,
    val workflowId: String? = null,
    /** ArtifactType.name */
    val type: String,
    val name: String,
    // GAP-01 (Design Closure 2026): the @ColumnInfo defaultValue annotations
    // mirror the v16 creation DDL (MIGRATION_15_TO_16) exactly so the migrated
    // and fresh-install schemas validate identically.
    @ColumnInfo(defaultValue = "'application/octet-stream'")
    val mimeType: String = "application/octet-stream",
    @ColumnInfo(defaultValue = "0")
    val sizeBytes: Long = 0L,
    val contentHash: String? = null,
    /** Sandbox-relative URI — never an arbitrary absolute path. */
    val storageUri: String,
    @ColumnInfo(defaultValue = "'USER'")
    val source: String = "USER",
    /** SecurityClassification.name */
    @ColumnInfo(defaultValue = "'UNCLASSIFIED'")
    val securityClassification: String = "UNCLASSIFIED",
    /** ArtifactIndexingState.name */
    @ColumnInfo(defaultValue = "'NOT_INDEXED'")
    val indexingState: String = "NOT_INDEXED",
    val ownerId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'{}'")
    val metadataJson: String = "{}"
)

/**
 * ============================================================================
 * DB v16 — REPAIR ORDER §24 (project dependency graph)
 * ============================================================================
 * Explicit dependencies of a project on models/providers/embeddings/tools/
 * workflows/agents/shared artifacts, with REQUIRED/OPTIONAL and
 * RESOLVED/MISSING/INCOMPATIBLE classification.
 */
@Entity(
    tableName = "project_dependencies",
    indices = [Index("projectId"), Index("type"), Index("key")],
    primaryKeys = ["projectId", "type", "key"]
)
data class ProjectDependencyEntity(
    val projectId: Long,
    /** ProjectDependencyType.name */
    val type: String,
    /** Stable dependency key (model resource id, tool name, agent id…). */
    val key: String,
    /** DependencyRequirement.name — REQUIRED / OPTIONAL */
    val requirement: String,
    /** DependencyStatus.name — RESOLVED / MISSING / INCOMPATIBLE */
    val status: String,
    val detail: String? = null,
    // GAP-01 (Design Closure 2026): mirrors MIGRATION_15_TO_16's
    // project_dependencies DDL (metadataJson TEXT NOT NULL DEFAULT '{}').
    @ColumnInfo(defaultValue = "'{}'")
    val metadataJson: String = "{}",
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/**
 * ============================================================================
 * DB v16 — REPAIR ORDER §26 (snapshots/recovery)
 * ============================================================================
 * Point-in-time project state bundle protecting destructive operations
 * (move, large imports, migration, bulk changes). Manifest is a complete
 * export-format state bundle WITHOUT secrets.
 */
@Entity(
    tableName = "project_snapshots",
    indices = [Index("projectId"), Index("workspaceId"), Index("createdAtEpochMs")]
)
data class ProjectSnapshotEntity(
    @PrimaryKey
    val id: String,
    val projectId: Long,
    val workspaceId: String,
    val label: String,
    /** Why the snapshot exists (e.g. PRE_MOVE, PRE_IMPORT). */
    val reason: String,
    /** Export-format manifest JSON (no secrets). */
    val manifestJson: String,
    val contentHash: String,
    val createdAtEpochMs: Long
)

/**
 * ============================================================================
 * DB v16 — REPAIR ORDER §30 (unified audit trail)
 * ============================================================================
 * Security-sensitive actions with full attribution:
 * WHO (actorType/actorId) / WHAT (action/resourceType/resourceId) /
 * WHEN / SOURCE SCOPE / TARGET SCOPE / POLICY / RESULT. NEVER stores
 * secrets or sensitive payloads.
 */
@Entity(
    tableName = "audit_events",
    indices = [
        Index("occurredAtEpochMs"),
        Index("action"),
        Index("workspaceId"),
        Index("projectId"),
        Index("resourceType")
    ]
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** AuditActorType.name — USER / AGENT / SYSTEM / IMPORTER */
    val actorType: String,
    val actorId: String,
    /** Canonical action name (AuditActions). */
    val action: String,
    val resourceType: String,
    val resourceId: String? = null,
    /** SOURCE scope. */
    val sourceScopeType: String,
    val sourceScopeId: String,
    /** TARGET scope (nullable for single-scope actions). */
    val targetScopeType: String? = null,
    val targetScopeId: String? = null,
    val policy: String? = null,
    /** AuditResult.name — SUCCESS / FAILURE / DENIED / DEGRADED */
    val result: String,
    val reason: String? = null,
    val workspaceId: String? = null,
    val projectId: Long? = null,
    val occurredAtEpochMs: Long,
    // GAP-01 (Design Closure 2026): mirrors MIGRATION_15_TO_16's
    // audit_events DDL (metadataJson TEXT NOT NULL DEFAULT '{}').
    @ColumnInfo(defaultValue = "'{}'")
    val metadataJson: String = "{}"
)
