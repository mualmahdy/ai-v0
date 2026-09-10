package com.example.domain.core.artifact

import com.example.domain.core.context.ResourceScope

/**
 * REPAIR ORDER §7 — unified artifact concept: ONE application-wide logical
 * resource library. An artifact is any addressable resource: file, folder,
 * session transcript, project package, snapshot, report, image, dataset…
 *
 * CRITICAL distinction: "artifact EXISTS" is independent from "artifact is
 * INDEXED AS KNOWLEDGE" ([indexingState]) — artifact storage and knowledge
 * indexing are separate concerns.
 */
enum class ArtifactType {
    FILE,
    FOLDER,
    SESSION_TRANSCRIPT,
    PROJECT_PACKAGE,
    SNAPSHOT,
    GENERATED_REPORT,
    IMAGE,
    ATTACHMENT,
    DATASET,
    GENERATED_ARTIFACT
}

enum class SecurityClassification {
    UNCLASSIFIED,
    INTERNAL,
    CONFIDENTIAL,
    SECRET
}

/** Knowledge-indexing lifecycle of an artifact (separate from existence). */
enum class ArtifactIndexingState {
    NOT_INDEXED,
    INDEXING,
    INDEXED,
    INDEX_FAILED,
    EXCLUDED
}

data class ArtifactDescriptor(
    val id: String,
    val scope: ResourceScope,
    val type: ArtifactType,
    val name: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val contentHash: String? = null,
    /** Sandbox-relative storage URI (never an arbitrary absolute path). */
    val storageUri: String,
    val source: String = "USER",
    val securityClassification: SecurityClassification = SecurityClassification.UNCLASSIFIED,
    val indexingState: ArtifactIndexingState = ArtifactIndexingState.NOT_INDEXED,
    val ownerId: String? = null,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val metadataJson: String = "{}"
)

/** Stat result for [ArtifactRepositoryPort.stat]. */
data class ArtifactStat(
    val exists: Boolean,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val contentHash: String? = null
)

/** Query for the global resource browser (REPAIR ORDER §29). */
data class ArtifactQuery(
    val scopeType: com.example.domain.core.context.ScopeType? = null,
    val workspaceId: String? = null,
    val projectId: Long? = null,
    val types: Set<ArtifactType> = emptySet(),
    val nameContains: String? = null,
    val limit: Int = 200
)
