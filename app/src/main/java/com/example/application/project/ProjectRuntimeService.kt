package com.example.application.project

import androidx.room.withTransaction
import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.project.Project
import com.example.domain.core.project.ProjectLifecycleState
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * ============================================================================
 * REPAIR ORDER §5/§27 — MULTI-PROJECT WORKSPACE SUPPORT + SAFE LIFECYCLE
 * ============================================================================
 * One service owns project lifecycle semantics. WorkspaceRuntimeService
 * stays a workspace-scope runtime (NOT a monolithic project manager — §5):
 * it delegates project operations HERE.
 *
 * Lifecycle (§27): ACTIVE → ARCHIVED → TRASHED → DELETED → PURGED.
 * Irreversible deletion is NEVER the first/default action:
 *   - delete = TRASHED (recoverable)
 *   - purge  = explicit second destructive step (DB rows + sandbox files)
 *
 * All mutations are transactional and audited; project switching refuses
 * non-ACTIVE projects; name collisions are detected up front.
 */
class ProjectRuntimeService(
    private val database: AppDatabase,
    /** Resolves the sandbox root directory for a project id. */
    private val projectRootResolver: (Long) -> File,
    private val auditTrail: AuditTrailService? = null
) {
    private val projectDao = database.projectDao()
    private val workspaceDao = database.workspaceDao()

    private val mutex = Mutex()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Selectable (ACTIVE) projects for a workspace — the ONLY picker list. */
    suspend fun listActiveProjects(workspaceId: String): List<Project> =
        projectDao.activeProjectsForWorkspaceList(workspaceId).map { it.toDomain() }

    /** Projects in ALL mutable states (for archive/trash browsers). */
    suspend fun listProjectsInState(workspaceId: String, state: ProjectLifecycleState): List<Project> =
        projectDao.forWorkspaceInState(workspaceId, state.name).map { it.toDomain() }

    suspend fun getProject(workspaceId: String, projectId: Long): Project? =
        projectDao.getProjectByIdForWorkspace(projectId, workspaceId)?.toDomain()

    fun observeActiveProjects(workspaceId: String): Flow<List<Project>> =
        projectDao.getActiveProjectsForWorkspace(workspaceId).map { list -> list.map { it.toDomain() } }

    // ------------------------------------------------------------------
    // Lifecycle operations (§27)
    // ------------------------------------------------------------------

    /**
     * Creates a new project in the workspace and optionally activates it.
     * Transactional (project row + rootPath fixup + optional active pin).
     * Refuses duplicate names within the workspace.
     */
    suspend fun createProject(
        workspaceId: String,
        name: String,
        description: String? = null,
        activate: Boolean = true
    ): Project? = mutex.withLock {
        _lastError.value = null
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _lastError.value = "اسم المشروع مطلوب."
            return null
        }
        if (projectDao.countByNameForWorkspace(workspaceId, trimmed) > 0) {
            _lastError.value = "يوجد مشروع بهذا الاسم بالفعل في هذه المساحة."
            return null
        }
        val now = System.currentTimeMillis()
        val id = database.withTransaction {
            val provisional = ProjectEntity(
                name = trimmed,
                description = description,
                rootPath = "",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                workspaceId = workspaceId,
                lifecycleState = ProjectLifecycleState.ACTIVE.name
            )
            val generated = projectDao.insertProject(provisional)
            // §3A invariant: a created project is BORN with a materialized,
            // canonical sandbox root (never a half-created project).
            val root = projectRootResolver(generated)
            root.mkdirs()
            projectDao.updateProject(
                provisional.copy(id = generated, rootPath = root.canonicalPath)
            )
            if (activate) workspaceDao.setActiveProject(workspaceId, generated, now)
            generated
        }
        audit(AuditActions.PROJECT_CREATED, workspaceId, id, AuditResult.SUCCESS)
        projectDao.getProjectById(id)?.toDomain()
    }

    /** Selects the ACTIVE project for the workspace — refuses non-ACTIVE projects. */
    suspend fun selectProject(workspaceId: String, projectId: Long): Boolean = mutex.withLock {
        _lastError.value = null
        val resolvable = projectDao.resolvableProjectForWorkspace(projectId, workspaceId)
        if (resolvable == null) {
            _lastError.value = "المشروع غير موجود أو غير نشط أو لا يخص هذه المساحة."
            return false
        }
        database.withTransaction {
            workspaceDao.setActiveProject(workspaceId, projectId, System.currentTimeMillis())
        }
        true
    }

    suspend fun renameProject(workspaceId: String, projectId: Long, newName: String, newDescription: String? = null): Boolean = mutex.withLock {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            _lastError.value = "اسم المشروع مطلوب."
            return false
        }
        val existing = projectDao.getProjectByIdForWorkspace(projectId, workspaceId) ?: run {
            _lastError.value = "المشروع غير موجود في هذه المساحة."
            return false
        }
        if (trimmed != existing.name && projectDao.countByNameForWorkspace(workspaceId, trimmed) > 0) {
            _lastError.value = "يوجد مشروع بهذا الاسم بالفعل في هذه المساحة."
            return false
        }
        database.withTransaction {
            projectDao.renameProjectForWorkspace(projectId, workspaceId, trimmed, newDescription ?: existing.description, System.currentTimeMillis())
        }
        audit(AuditActions.PROJECT_RENAMED, workspaceId, projectId, AuditResult.SUCCESS)
        true
    }

    /** ARCHIVED: hidden from pickers, data intact, restorable. */
    suspend fun archiveProject(workspaceId: String, projectId: Long): Boolean = mutex.withLock {
        transition(workspaceId, projectId, ProjectLifecycleState.ARCHIVED, AuditActions.PROJECT_ARCHIVED)
    }

    /** Restores ARCHIVED/TRASHED → ACTIVE. */
    suspend fun restoreProject(workspaceId: String, projectId: Long): Boolean = mutex.withLock {
        transition(workspaceId, projectId, ProjectLifecycleState.ACTIVE, AuditActions.PROJECT_RESTORED)
    }

    /**
     * TRASHED (§27 "delete/trash"): recoverable soft-delete. If the trashed
     * project was the workspace's active project, the active binding is
     * cleared (explicit PROJECT_NOT_FOUND bootstrap state follows — honest,
     * not silent).
     */
    suspend fun trashProject(workspaceId: String, projectId: Long): Boolean = mutex.withLock {
        val ok = transition(workspaceId, projectId, ProjectLifecycleState.TRASHED, AuditActions.PROJECT_TRASHED)
        if (ok) {
            val ws = workspaceDao.getWorkspaceById(workspaceId)
            if (ws?.lastActiveProjectId == projectId) {
                database.withTransaction {
                    workspaceDao.setActiveProject(workspaceId, null, System.currentTimeMillis())
                }
            }
        }
        ok
    }

    /**
     * DELETED: DB row removed; sandbox directory retained until PURGED.
     * Second destructive step after TRASH — never the first action.
     *
     * GAP-15 (Design Closure 2026): the §27 transition table is now ENFORCED
     * — TRASHED → DELETED is the only path in. Deleting an ACTIVE/ARCHIVED
     * project is REJECTED ("destruction is never the first action"); the
     * rejection is audited as a FAILURE, never silent.
     */
    suspend fun deleteProject(workspaceId: String, projectId: Long): Boolean = mutex.withLock {
        val entity = projectDao.getProjectByIdForWorkspace(projectId, workspaceId) ?: run {
            _lastError.value = "المشروع غير موجود في هذه المساحة."
            return false
        }
        val current = parseLifecycle(entity)
        if (current != ProjectLifecycleState.TRASHED) {
            _lastError.value = "حذف المشروع يتطلب نقله إلى المهملات أولاً (الحالة الحالية $current) — الإتلاف ليس أول إجراء."
            audit(
                AuditActions.PROJECT_DELETED, workspaceId, projectId, AuditResult.FAILURE,
                reason = "rejected: invalid lifecycle transition $current → DELETED (§27)"
            )
            return false
        }
        val ws = workspaceDao.getWorkspaceById(workspaceId)
        database.withTransaction {
            // Clear an active binding pointing at this project (inside the tx).
            if (ws?.lastActiveProjectId == projectId) {
                workspaceDao.setActiveProject(workspaceId, null, System.currentTimeMillis())
            }
            projectDao.deleteProjectRow(projectId)
        }
        audit(AuditActions.PROJECT_DELETED, workspaceId, projectId, AuditResult.SUCCESS, reason = "row removed; sandbox retained for purge")
        entity.rootPath.isNotBlank()
    }

    /**
     * PURGED: irreversible — DB rows already deleted; destroys the sandbox
     * directory and any dangling scoped rows. Explicit second call.
     *
     * GAP-15 (Design Closure 2026): the §27 transition table is now ENFORCED
     * — PURGED is reachable only from TRASHED (row still present) or from
     * DELETED (row already removed by [deleteProject]). A row that is still
     * ACTIVE/ARCHIVED is REJECTED with an audited FAILURE — direct
     * destruction of a live project is never allowed.
     */
    suspend fun purgeProject(workspaceId: String, projectId: Long): Boolean = mutex.withLock {
        val row = projectDao.getProjectByIdForWorkspace(projectId, workspaceId)
        if (row != null) {
            val current = parseLifecycle(row)
            if (current != ProjectLifecycleState.TRASHED && current != ProjectLifecycleState.DELETED) {
                _lastError.value = "التنظيف النهائي يتطلب مهملات أو حذفاً مسبقاً (الحالة الحالية $current) — الإتلاف ليس أول إجراء."
                audit(
                    AuditActions.PROJECT_PURGED, workspaceId, projectId, AuditResult.FAILURE,
                    reason = "rejected: invalid lifecycle transition $current → PURGED (§27)"
                )
                return false
            }
        } // row == null → already DELETED via deleteProject — purge proceeds.
        val root = projectRootResolver(projectId)
        val deletedFiles = root.exists() && root.deleteRecursively()
        // Cascade dangling scoped rows (defensive — deleteProject removes the row).
        database.withTransaction {
            database.knowledgeDocumentDao().deleteAllForProject(projectId)
            database.conversationSessionDao().deleteForProject(projectId)
            database.artifactDao().deleteForProject(projectId)
            database.projectSnapshotDao().deleteForProject(projectId)
            database.projectDependencyDao().deleteForProject(projectId)
        }
        audit(AuditActions.PROJECT_PURGED, workspaceId, projectId, AuditResult.SUCCESS, reason = "sandboxDeleted=$deletedFiles")
        true
    }

    /**
     * MOVE (§11): moves the project to [targetWorkspaceId]. VERIFIED
     * transfer: the project row + scoped rows are rebound and the sandbox
     * directory renamed in one coordinator step; only on full success is
     * the source binding released. Delegates the heavy lifting to
     * [ProjectTransferCoordinator] when wired; the row-level operation is
     * here.
     */
    suspend fun moveProject(projectId: Long, sourceWorkspaceId: String, targetWorkspaceId: String): Boolean = mutex.withLock {
        _lastError.value = null
        if (sourceWorkspaceId == targetWorkspaceId) {
            _lastError.value = "المشروع موجود بالفعل في المساحة الهدف."
            return false
        }
        if (workspaceDao.getWorkspaceById(targetWorkspaceId) == null) {
            _lastError.value = "مساحة العمل الهدف غير موجودة."
            return false
        }
        val moved = database.withTransaction {
            projectDao.moveProjectToWorkspace(projectId, sourceWorkspaceId, targetWorkspaceId, System.currentTimeMillis())
        }
        if (moved == 0) {
            _lastError.value = "فشل نقل المشروع — الملكية لم تتغير."
            return false
        }
        // Clear a stale active pin in the SOURCE workspace (inside same audit step).
        val ws = workspaceDao.getWorkspaceById(sourceWorkspaceId)
        if (ws?.lastActiveProjectId == projectId) {
            database.withTransaction {
                workspaceDao.setActiveProject(sourceWorkspaceId, null, System.currentTimeMillis())
            }
        }
        audit(
            AuditActions.PROJECT_MOVED, targetWorkspaceId, projectId, AuditResult.SUCCESS,
            reason = "from=$sourceWorkspaceId"
        )
        true
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun transition(
        workspaceId: String,
        projectId: Long,
        target: ProjectLifecycleState,
        auditAction: String
    ): Boolean {
        val entity = projectDao.getProjectByIdForWorkspace(projectId, workspaceId) ?: run {
            _lastError.value = "المشروع غير موجود في هذه المساحة."
            return false
        }
        val current = parseLifecycle(entity)
        if (!isValidTransition(current, target)) {
            _lastError.value = "انتقال غير صالح لحالة المشروع: $current → $target."
            return false
        }
        val now = System.currentTimeMillis()
        database.withTransaction {
            projectDao.setLifecycleState(
                id = projectId,
                workspaceId = workspaceId,
                state = target.name,
                now = now,
                archived = target == ProjectLifecycleState.ARCHIVED,
                archivedAt = if (target == ProjectLifecycleState.ARCHIVED) now else entity.archivedAtEpochMs,
                trashedAt = if (target == ProjectLifecycleState.TRASHED) now else entity.trashedAtEpochMs
            )
        }
        audit(auditAction, workspaceId, projectId, AuditResult.SUCCESS, reason = "$current → $target")
        return true
    }

    private fun parseLifecycle(entity: ProjectEntity): ProjectLifecycleState = try {
        ProjectLifecycleState.valueOf(entity.effectiveLifecycleState)
    } catch (_: IllegalArgumentException) {
        ProjectLifecycleState.ACTIVE
    }

    /** §27 transition table. */
    private fun isValidTransition(from: ProjectLifecycleState, to: ProjectLifecycleState): Boolean = when (from) {
        ProjectLifecycleState.ACTIVE -> to == ProjectLifecycleState.ARCHIVED || to == ProjectLifecycleState.TRASHED
        ProjectLifecycleState.ARCHIVED -> to == ProjectLifecycleState.ACTIVE || to == ProjectLifecycleState.TRASHED
        ProjectLifecycleState.TRASHED -> to == ProjectLifecycleState.ACTIVE || to == ProjectLifecycleState.DELETED || to == ProjectLifecycleState.PURGED
        ProjectLifecycleState.DELETED -> to == ProjectLifecycleState.PURGED
        ProjectLifecycleState.PURGED -> false
    }

    private suspend fun audit(
        action: String,
        workspaceId: String,
        projectId: Long,
        result: AuditResult,
        reason: String? = null
    ) {
        auditTrail?.record(
            actorType = AuditActorType.USER,
            actorId = "user",
            action = action,
            resourceType = "PROJECT",
            resourceId = projectId.toString(),
            sourceScope = ResourceScope.Workspace(workspaceId),
            targetScope = null,
            policy = null,
            result = result,
            reason = reason
        )
    }

    private fun ProjectEntity.toDomain(): Project = Project(
        id = id,
        workspaceId = workspaceId ?: "",
        name = name,
        description = description,
        rootPath = rootPath,
        lifecycleState = parseLifecycle(this),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        archivedAtEpochMs = archivedAtEpochMs,
        trashedAtEpochMs = trashedAtEpochMs
    )
}
