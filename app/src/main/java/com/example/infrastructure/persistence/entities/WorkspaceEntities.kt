package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2 — Workspace entity for true multi-workspace support.
 *
 * Previously the app hardcoded a single workspace (project id=1L) and treated
 * Workspace as a Domain-only model with no persistence. This entity makes
 * Workspace a first-class persistent citizen so users can:
 *   - Create multiple workspaces
 *   - Switch between them
 *   - Close the app and find their active workspace restored
 *   - Have independent resource graphs, network policies, and settings per workspace
 */
@Entity(
    tableName = "workspaces",
    indices = [Index("isActive"), Index("createdAtEpochMs")]
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val networkPolicy: String,        // HYBRID, OFFLINE, LOCAL_ONLY, CLOUD_ONLY
    val autonomyPolicy: String,       // ASSISTED, SUPERVISED, AUTONOMOUS
    val settingsJson: String,         // JSON map of workspace-level settings
    val isActive: Boolean,            // only one workspace active at a time
    val lastActiveProjectId: Long?,   // null = no project selected yet
    val createdAtEpochMs: Long,
    val lastAccessedEpochMs: Long
)

/**
 * Phase 2 — Persistent knowledge document entity.
 *
 * Previously RagPipelineService kept `_documents` and `chunks` in memory only,
 * so all ingested knowledge was lost on app restart. This entity persists the
 * document metadata so the RAG subsystem can rebuild its in-memory index on
 * startup. The actual chunk text + embedding vectors live in DocumentChunkEntity.
 */
@Entity(
    tableName = "knowledge_documents",
    indices = [Index("workspaceId"), Index("projectId"), Index("createdAtEpochMs")]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey
    val id: String,
    val workspaceId: String,
    val projectId: Long?,
    val title: String,
    val sourceUri: String,
    val content: String,              // full document text (so re-chunking is possible)
    val tagsJson: String,             // JSON array of tags
    val totalChunks: Int,
    val totalTokensEstimated: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isArchived: Boolean = false
)

/**
 * Phase 2 — Persistent document chunk entity with embedding vector.
 *
 * Each chunk is stored with its embedding vector as JSON (Phase 2 simplicity;
 * Phase 4 can migrate to BLOB). The `vectorDimension` is redundant with the
 * JSON length but stored explicitly for sanity checks and to detect schema
 * drift across embedding model upgrades.
 *
 * The `retrievalSource` field records whether the vector came from a real
 * embedding model ("SEMANTIC") or the lexical hash fallback ("LEXICAL_FALLBACK").
 * This lets the retrieval pipeline weight results honestly instead of treating
 * all vectors as semantic.
 */
@Entity(
    tableName = "document_chunks",
    indices = [Index("documentId"), Index("workspaceId"), Index("chunkIndex")]
)
data class DocumentChunkEntity(
    @PrimaryKey
    val id: String,
    val documentId: String,
    val workspaceId: String,
    val chunkIndex: Int,
    val text: String,
    val tokenCount: Int,
    val vectorDimension: Int,
    val vectorJson: String,           // Float array as JSON
    val retrievalSource: String,      // SEMANTIC | LEXICAL_FALLBACK
    val createdAtEpochMs: Long
)

/**
 * Phase 2 — Persistent resource edge entity for the Workspace Resource Graph.
 *
 * Previously the ResourceGraph was an immutable in-memory data structure with
 * no persistence — edges were never created at runtime anyway (the 21-value
 * ResourceType enum was largely dead). This entity makes edges first-class
 * persistent citizens so the graph survives app restart and can be queried
 * (e.g. "which files depend on which knowledge documents?").
 *
 * Note: This stores workspace-scoped semantic edges (DEPENDS_ON, USES_TOOL,
 * REFERENCES_KNOWLEDGE, etc.) — NOT the runtime adapter resolution graph
 * (which lives in ComponentRegistry / RuntimeAdapterResolver and is rebuilt
 * on every app start from provider configs).
 */
@Entity(
    tableName = "resource_edges",
    indices = [
        Index("workspaceId"),
        Index("sourceId"),
        Index("targetId"),
        Index("edgeType")
    ]
)
data class ResourceEdgeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val workspaceId: String,
    val sourceId: String,
    val sourceType: String,           // ResourceType.name (workspace scope: FILE, DOCUMENT, TASK, etc.)
    val targetId: String,
    val targetType: String,
    val edgeType: String,             // ResourceEdgeType.name (CONTAINS, DEPENDS_ON, USES_TOOL, etc.)
    val weight: Float = 1.0f,
    val metadataJson: String = "{}",  // JSON map
    val createdAtEpochMs: Long
)
