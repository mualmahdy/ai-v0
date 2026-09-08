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
 * P0 CONVERGENCE (audit step 12 §7): RAG metadata durability.
 *  - The dead `projectId` column + its index were REMOVED (MIGRATION_11_TO_12):
 *    the column was written as NULL on every insert since Phase 2 and nothing
 *    ever read it — schema residue from the pre-workspace ownership model.
 *  - `mimeType` is now persisted (previously dropped on write, so reloaded
 *    documents silently lost their content type).
 */
@Entity(
    tableName = "knowledge_documents",
    indices = [Index("workspaceId"), Index("createdAtEpochMs")]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey
    val id: String,
    val workspaceId: String,
    val title: String,
    val sourceUri: String,
    val content: String,              // full document text (so re-chunking is possible)
    val mimeType: String = "text/markdown",
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
 * The `retrievalSource` field records whether the vector came from a real
 * semantic embedding model ("SEMANTIC") or the lexical hash fallback
 * ("LEXICAL_FALLBACK") — derived from the persisted chunk metadata so the
 * label stays honest across restarts (previously any non-null vector was
 * labeled SEMANTIC, but the lexical fallback ALSO produces a vector).
 *
 * P0 CONVERGENCE (audit step 12 §7): `metadataJson` persists the chunk's
 * metadata map (embeddingResourceId, embeddingSemantic, tags, source…).
 * Previously metadata was dropped on write and rebuilt as an empty map on
 * reload, which silently collapsed the embedding-compatibility boundary,
 * authority/recency ranking signals and metadata filters after restart.
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
    val metadataJson: String = "{}",  // JSON map of chunk metadata
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
