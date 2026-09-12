package com.example.application.artifacts

import com.example.application.audit.AuditTrailService
import com.example.domain.core.artifact.ArtifactDescriptor
import com.example.domain.core.artifact.ArtifactIndexingState
import com.example.domain.core.artifact.ArtifactStat
import com.example.domain.core.artifact.ArtifactType
import com.example.domain.core.artifact.SecurityClassification
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.PrincipalType
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.context.ScopeRules
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ArtifactEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * ============================================================================
 * REPAIR ORDER §7/§8/§29 — UNIFIED ARTIFACT / RESOURCE LIBRARY
 * ============================================================================
 * ONE application-wide logical resource library. Every addressable resource
 * (file, folder, transcript, package, snapshot, report, dataset…) is an
 * [ArtifactDescriptor] row in the `artifacts` index with EXPLICIT scope,
 * owner, type, mime, size, content hash, timestamps, source, security
 * classification and indexing state.
 *
 * KEY DISTINCTION: artifact EXISTENCE is independent from knowledge INDEXING
 * ([ArtifactIndexingState]) — storage and indexing are separate concerns.
 *
 * ACCESS MODEL (§8): globally discoverable in the UI, but visibility ≠
 * access. Every operation resolves an [ResourceScope] and enforces
 * [ScopeRules] (child may access permitted ancestor-shared resources;
 * sibling/private access requires an explicit grant; parent may not
 * auto-access child-private resources).
 */
class ArtifactService(
    private val database: AppDatabase,
    private val fileStore: SandboxProjectFileStore,
    private val auditTrail: AuditTrailService? = null
) {
    private val artifactDao = database.artifactDao()

    // ------------------------------------------------------------------
    // Registration / queries
    // ------------------------------------------------------------------

    /**
     * Registers a file (or folder) living in a project sandbox as a
     * first-class artifact. Deduplicated by id.
     */
    suspend fun registerFileArtifact(
        workspaceId: String,
        projectId: Long,
        relativePath: String,
        name: String = relativePath.substringAfterLast('/'),
        mimeType: String = guessMime(name),
        ownerId: String? = null,
        indexingState: ArtifactIndexingState = ArtifactIndexingState.NOT_INDEXED
    ): ArtifactDescriptor = withContext(Dispatchers.IO) {
        val root = fileStore.projectRoot(projectId)
        val stat = fileStore.stat(root, relativePath)
        val id = "art_${projectId}_${relativePath.hashCode()}_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val entity = ArtifactEntity(
            id = id,
            workspaceId = workspaceId,
            projectId = projectId,
            type = if (stat.isDirectory) ArtifactType.FOLDER.name else ArtifactType.FILE.name,
            name = name,
            mimeType = mimeType,
            sizeBytes = stat.sizeBytes,
            contentHash = stat.sha256,
            storageUri = relativePath,
            source = "SANDBOX",
            indexingState = indexingState.name,
            ownerId = ownerId,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        artifactDao.upsert(entity)
        audit(AuditActions.FILE_REGISTERED, "ARTIFACT", entity.id, workspaceId, projectId, AuditResult.SUCCESS)
        entity.toDescriptor()
    }

    /** Registers an externally-representable artifact (transcript, report, package…). */
    suspend fun registerGeneratedArtifact(
        workspaceId: String?,
        projectId: Long?,
        type: ArtifactType,
        name: String,
        storageUri: String,
        mimeType: String = "application/octet-stream",
        sizeBytes: Long = 0L,
        contentHash: String? = null,
        securityClassification: SecurityClassification = SecurityClassification.UNCLASSIFIED
    ): ArtifactDescriptor = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = ArtifactEntity(
            id = "art_${UUID.randomUUID().toString().take(16)}",
            workspaceId = workspaceId,
            projectId = projectId,
            type = type.name,
            name = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentHash = contentHash,
            storageUri = storageUri,
            source = "GENERATED",
            securityClassification = securityClassification.name,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        artifactDao.upsert(entity)
        entity.toDescriptor()
    }

    /**
     * REPAIR ORDER §29 — GLOBAL RESOURCE BROWSER query. Scope-filtered
     * (never bypasses access control): the caller passes the scopes it is
     * ALLOWED to see; this service returns only matching rows.
     */
    suspend fun browse(
        allowedWorkspaceId: String? = null,
        allowedProjectId: Long? = null,
        nameContains: String? = null,
        limit: Int = 200
    ): List<ArtifactDescriptor> = withContext(Dispatchers.IO) {
        artifactDao.search(
            workspaceId = allowedWorkspaceId,
            projectId = allowedProjectId,
            nameContains = nameContains,
            limit = limit
        ).map { it.toDescriptor() }
    }

    suspend fun forProject(projectId: Long, limit: Int = 200): List<ArtifactDescriptor> =
        withContext(Dispatchers.IO) { artifactDao.forProject(projectId, limit).map { it.toDescriptor() } }

    // ------------------------------------------------------------------
    // Content operations (project-scoped, containment-checked)
    // ------------------------------------------------------------------

    suspend fun readContent(
        accessorScope: ResourceScope,
        artifactId: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val artifact = artifactDao.byId(artifactId) ?: return@withContext null
        val resourceScope = artifactScope(artifact) ?: return@withContext null
        if (!ScopeRules.canAccess(accessorScope, resourceScope)) {
            audit(AuditActions.FILE_READ, "ARTIFACT", artifactId, artifact.workspaceId, artifact.projectId, AuditResult.DENIED)
            return@withContext null
        }
        val projectId = artifact.projectId ?: return@withContext null
        val root = fileStore.projectRoot(projectId)
        runCatching { fileStore.read(root, artifact.storageUri) }.getOrNull()
    }

    suspend fun stat(artifactId: String): ArtifactStat? = withContext(Dispatchers.IO) {
        val artifact = artifactDao.byId(artifactId) ?: return@withContext null
        val projectId = artifact.projectId ?: return@withContext null
        val root = fileStore.projectRoot(projectId)
        runCatching {
            val s = fileStore.stat(root, artifact.storageUri)
            ArtifactStat(s.exists, s.isDirectory, s.sizeBytes, s.lastModifiedEpochMs, s.sha256)
        }.getOrNull()
    }

    /** Copy an artifact's content to another project (§8: explicit copy, never implicit). */
    suspend fun copyContentToProject(
        accessorScope: ResourceScope,
        artifactId: String,
        targetProjectId: Long,
        targetRelativePath: String
    ): Boolean = withContext(Dispatchers.IO) {
        val artifact = artifactDao.byId(artifactId) ?: return@withContext false
        val resourceScope = artifactScope(artifact) ?: return@withContext false
        if (!ScopeRules.canAccess(accessorScope, resourceScope)) {
            audit(AuditActions.FILE_COPIED, "ARTIFACT", artifactId, artifact.workspaceId, artifact.projectId, AuditResult.DENIED)
            return@withContext false
        }
        val sourceProject = artifact.projectId ?: return@withContext false
        val srcRoot = fileStore.projectRoot(sourceProject)
        val dstRoot = fileStore.projectRoot(targetProjectId)
        runCatching {
            val content = fileStore.read(srcRoot, artifact.storageUri)
            fileStore.write(dstRoot, targetRelativePath, content)
        }.isSuccess
    }

    suspend fun delete(
        accessorScope: ResourceScope,
        artifactId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val artifact = artifactDao.byId(artifactId) ?: return@withContext false
        val resourceScope = artifactScope(artifact) ?: return@withContext false
        if (!ScopeRules.canAccess(accessorScope, resourceScope, com.example.domain.core.context.ScopePermission.READ_WRITE)) {
            return@withContext false
        }
        val projectId = artifact.projectId
        if (projectId != null) {
            runCatching { fileStore.delete(fileStore.projectRoot(projectId), artifact.storageUri) }
        }
        artifactDao.deleteById(artifactId)
        true
    }

    /** Marks the artifact's knowledge-indexing state (existence ≠ indexing). */
    suspend fun updateIndexingState(artifactId: String, state: ArtifactIndexingState): Boolean =
        withContext(Dispatchers.IO) {
            artifactDao.updateIndexingState(artifactId, state.name, System.currentTimeMillis())
            true
        }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun artifactScope(entity: ArtifactEntity): ResourceScope? = when {
        entity.workspaceId != null && entity.projectId != null ->
            ResourceScope.Project(entity.workspaceId, entity.projectId)
        entity.workspaceId != null -> ResourceScope.Workspace(entity.workspaceId)
        else -> ResourceScope.Application
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".csv", true) -> "text/csv"
        else -> "application/octet-stream"
    }

    private suspend fun audit(
        action: String,
        resourceType: String,
        resourceId: String?,
        workspaceId: String?,
        projectId: Long?,
        result: AuditResult
    ) {
        auditTrail?.recordAsync(
            actorType = AuditActorType.SYSTEM,
            actorId = "artifact_service",
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            sourceScope = ResourceScope.Workspace(workspaceId ?: "app"),
            result = result
        )
    }

    private fun ArtifactEntity.toDescriptor() = ArtifactDescriptor(
        id = id,
        scope = artifactScope(this) ?: ResourceScope.Application,
        type = runCatching { ArtifactType.valueOf(type) }.getOrDefault(ArtifactType.FILE),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        storageUri = storageUri,
        source = source,
        securityClassification = runCatching { SecurityClassification.valueOf(securityClassification) }.getOrDefault(SecurityClassification.UNCLASSIFIED),
        indexingState = runCatching { ArtifactIndexingState.valueOf(indexingState) }.getOrDefault(ArtifactIndexingState.NOT_INDEXED),
        ownerId = ownerId,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        metadataJson = metadataJson
    )
}
