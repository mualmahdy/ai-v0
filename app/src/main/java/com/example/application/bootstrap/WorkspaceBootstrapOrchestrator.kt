package com.example.application.bootstrap

import com.example.domain.core.workspace.Workspace
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * ============================================================================
 * REPAIR ORDER §3A — STARTUP PROJECT/WORKSPACE FAILURE: ROOT-CAUSE FIX
 * ============================================================================
 *
 * The recurring startup error
 *   "No project is associated with the current workspace — create a new
 *    workspace or select a project before accessing files."
 * had FOUR root causes (all fixed here, not hidden):
 *
 *   1. Workspace creation was NOT transactional with its required project:
 *      a failed project insert left `lastActiveProjectId = null` forever.
 *   2. There was NO bootstrap state machine — a fire-and-forget init{}
 *      coroutine raced every consumer, and failure was indistinguishable
 *      from "not yet finished".
 *   3. A stale lastActiveProjectId (deleted/archived/foreign project) was
 *      NEVER reconciled — the error then repeated on every startup.
 *   4. Project-dependent features ran before any context was ready.
 *
 * This orchestrator implements the required deterministic state machine:
 *
 *   BOOTSTRAPPING → WORKSPACE_READY → PROJECT_RESOLVED → CONTEXT_READY → READY
 *
 * with EXPLICIT failure states:
 *   NO_WORKSPACE | PROJECT_NOT_FOUND |
 *   CONTEXT_REPAIR_REQUIRED | BOOTSTRAP_FAILED
 *
 * GAP-25 (Design Closure 2026): the previously-declared PROJECT_CORRUPT
 * failure state was REMOVED — it was never emitted. A corrupt root path is
 * (by design, §3A) REPAIRED to the canonical sandbox path and recorded as
 * a repair note ("repaired_corrupt_root_path") on the Ready/ContextReady
 * phase — a repairable condition must not fail the whole startup. The
 * honest audit trail of that repair is the repairNote + bootstrap
 * degradation events, not a hard failure state.
 *
 * Guarantees:
 *   - workspace + required project creation is ONE Room transaction —
 *     an invalid workspace without its project can no longer exist;
 *   - active workspace AND active project are restored deterministically;
 *   - stale lastActiveProjectId is reconciled to the workspace's most
 *     recently updated ACTIVE OWNED project, else cleared to the explicit
 *     PROJECT_NOT_FOUND state (NEVER "project 1" / first-project fallback);
 *   - startup is IDEMPOTENT — re-running on an already-good state is a no-op;
 *   - corrupted/missing references are repaired or reported explicitly;
 *   - project-dependent features gate on [BootstrapPhase.READY] (consumed
 *     via [WorkspaceRuntimeService.bootstrapState]).
 */
class WorkspaceBootstrapOrchestrator(
    private val database: AppDatabase,
    /** Resolves the on-disk sandbox root for a project id. */
    private val projectRootResolver: (Long) -> File,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val workspaceDao = database.workspaceDao()
    private val projectDao = database.projectDao()

    private val mutex = Mutex()

    private val _state = MutableStateFlow(BootstrapState.BOOTSTRAPPING)
    /** Single source of truth for startup progress — UI and services observe THIS. */
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    /** Bounded number of reconcile attempts before BOOTSTRAP_FAILED (no infinite loop). */
    var maxReconcileAttempts: Int = 3

    /**
     * Runs the full state machine. Idempotent — a re-run on a READY state
     * re-verifies (repairing drift) instead of failing. Safe to call from
     * any dispatcher; mutually exclusive via [mutex].
     */
    suspend fun bootstrap(): BootstrapState = mutex.withLock {
        try {
            runStateMachine()
        } catch (t: Throwable) {
            _state.value = BootstrapState(
                BootstrapPhase.Failed(
                    BootstrapFailure.BOOTSTRAP_FAILED,
                    "BOOTSTRAP_FAILED: ${t.javaClass.simpleName}: ${t.message}"
                )
            )
            _state.value
        }
    }

    private suspend fun runStateMachine(): BootstrapState {
        _state.value = BootstrapState(BootstrapPhase.Bootstrapping)

        // ---------------------------------------------------------------
        // Phase 1: BOOTSTRAPPING → WORKSPACE_READY
        // Ensure exactly one active workspace exists. First launch creates
        // the default workspace TOGETHER with its required project in ONE
        // transaction (root cause 1).
        // ---------------------------------------------------------------
        val activeWorkspace = ensureActiveWorkspace()
        if (activeWorkspace == null) {
            _state.value = BootstrapState(
                BootstrapPhase.Failed(
                    BootstrapFailure.NO_WORKSPACE,
                    "NO_WORKSPACE: لا توجد مساحة عمل — تعذر إنشاء/استعادة مساحة عمل صالحة."
                )
            )
            return _state.value
        }
        _state.value = BootstrapState(BootstrapPhase.WorkspaceReady(activeWorkspace.id))
        val workspaceId = activeWorkspace.id

        // ---------------------------------------------------------------
        // Phase 2: WORKSPACE_READY → PROJECT_RESOLVED
        // Reconcile lastActiveProjectId: it must exist, be ACTIVE and be
        // OWNED by this workspace. Stale references are repaired
        // deterministically (root cause 3): the most recently updated
        // ACTIVE OWNED project, else explicit PROJECT_NOT_FOUND.
        // ---------------------------------------------------------------
        var attempt = 0
        var resolved: ProjectEntity? = null
        var repairNote: String? = null
        while (resolved == null && attempt < maxReconcileAttempts.coerceAtLeast(1)) {
            attempt++
            val reconciled = reconcileActiveProject(activeWorkspace)
            resolved = reconciled.first
            if (resolved != null && reconciled.second != null) {
                repairNote = reconciled.second
            }
            if (resolved == null) {
                // No resolvable project in this workspace. If it has ZERO
                // projects at all, create the required default project
                // transactionally (workspace must not stay invalid — §3A).
                val anyProject = projectDao.forWorkspaceInState(workspaceId, "ACTIVE") +
                        projectDao.forWorkspaceInState(workspaceId, "ARCHIVED") +
                        projectDao.forWorkspaceInState(workspaceId, "TRASHED")
                if (anyProject.isEmpty()) {
                    resolved = createProjectInTransaction(
                        workspaceId = workspaceId,
                        name = "مشروع مساحة العمل الافتراضية",
                        description = "مشروع sandbox مملوك لمساحة العمل",
                        activate = true
                    )
                    repairNote = "created_missing_required_project"
                } else {
                    // Projects exist but none is ACTIVE+resolvable (all
                    // archived/trashed) → explicit honest state.
                    break
                }
            }
        }

        val resolvedProject = resolved
        if (resolvedProject == null) {
            _state.value = BootstrapState(
                BootstrapPhase.Failed(
                    BootstrapFailure.PROJECT_NOT_FOUND,
                    "PROJECT_NOT_FOUND: لا يوجد مشروع نشط قابل للاختيار في هذه المساحة — " +
                            "أنشئ مشروعاً جديداً أو استعد مشروعاً مؤرشفاً."
                )
            )
            return _state.value
        }
        _state.value = BootstrapState(BootstrapPhase.ProjectResolved(workspaceId, resolvedProject.id, repairNote))

        // ---------------------------------------------------------------
        // Phase 3: PROJECT_RESOLVED → CONTEXT_READY
        // The sandbox root must exist on disk; a missing root is repaired
        // (materialized). GAP-25 (Design Closure 2026): a rootPath pointing
        // OUTSIDE the sanctioned projects directory is NOT a hard failure —
        // it is REPAIRED to the canonical path (never trusted blindly),
        // recorded in the repair note, and surfaced as a bootstrap
        // degradation. The previously-declared PROJECT_CORRUPT failure
        // state was never actually emitted (dead constant — removed).
        // ---------------------------------------------------------------
        val canonicalRoot = projectRootResolver(resolvedProject.id)
        val rootPathRecorded = resolvedProject.rootPath
        if (rootPathRecorded.isNotBlank() && File(rootPathRecorded).canonicalPath != canonicalRoot.canonicalPath) {
            // Corrupt root path: repair to canonical + audit note.
            database.withTransaction {
                projectDao.updateProject(
                    resolvedProject.copy(rootPath = canonicalRoot.canonicalPath)
                )
            }
            repairNote = (repairNote?.plus(";") ?: "") + "repaired_corrupt_root_path"
        }
        if (!canonicalRoot.exists() && !canonicalRoot.mkdirs()) {
            _state.value = BootstrapState(
                BootstrapPhase.Failed(
                    BootstrapFailure.CONTEXT_REPAIR_REQUIRED,
                    "CONTEXT_REPAIR_REQUIRED: تعذر إنشاء جذر sandbox للمشروع ${resolvedProject.id} في ${canonicalRoot.canonicalPath}"
                )
            )
            return _state.value
        }
        _state.value = BootstrapState(BootstrapPhase.ContextReady(workspaceId, resolvedProject.id, repairNote))

        // ---------------------------------------------------------------
        // Phase 4: CONTEXT_READY → READY
        // ---------------------------------------------------------------
        _state.value = BootstrapState(BootstrapPhase.Ready(workspaceId, resolvedProject.id, repairNote))
        return _state.value
    }

    /**
     * Exactly-one-active-workspace invariant. First launch: default
     * workspace + project in ONE transaction. Restart: deterministic
     * restoration (most recently accessed).
     */
    private suspend fun ensureActiveWorkspace(): WorkspaceEntity? {
        val all = workspaceDao.getAllWorkspaces()
        return when {
            all.isEmpty() -> createDefaultWorkspaceInTransaction()
            all.count { it.isActive } == 1 -> all.first { it.isActive }
            all.count { it.isActive } > 1 -> {
                // Multiple active (corruption): keep the most recent, deactivate others — atomic.
                val keeper = all.filter { it.isActive }.maxByOrNull { it.lastAccessedEpochMs } ?: return null
                database.withTransaction {
                    workspaceDao.deactivateAll()
                    workspaceDao.setActive(keeper.id, System.currentTimeMillis())
                }
                workspaceDao.getWorkspaceById(keeper.id)
            }
            else -> {
                // Zero active: activate most recently accessed — atomic.
                val candidate = all.maxByOrNull { it.lastAccessedEpochMs } ?: return null
                database.withTransaction {
                    workspaceDao.deactivateAll()
                    workspaceDao.setActive(candidate.id, System.currentTimeMillis())
                }
                workspaceDao.getWorkspaceById(candidate.id)
            }
        }
    }

    /**
     * Default workspace + its OWN required project — ONE transaction: the
     * workspace row is only visible with a valid project binding (root
     * cause 1). On transaction failure nothing is written (previously a
     * half-created workspace persisted forever).
     */
    private suspend fun createDefaultWorkspaceInTransaction(): WorkspaceEntity? {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val projectId = insertProjectRow(
                workspaceId = "default",
                name = "مشروع مساحة العمل الافتراضية",
                description = "مشروع sandbox مملوك لمساحة العمل الافتراضية",
                now = now
            )
            val entity = WorkspaceEntity(
                id = "default",
                name = "مساحة العمل الافتراضية",
                description = "مساحة العمل الأساسية المعزولة لتنسيق الوكلاء والملفات",
                networkPolicy = "HYBRID",
                autonomyPolicy = "SUPERVISED",
                settingsJson = "{}",
                isActive = true,
                lastActiveProjectId = projectId,
                createdAtEpochMs = now,
                lastAccessedEpochMs = now
            )
            workspaceDao.insertOrUpdate(entity)
            entity
        }
    }

    /**
     * Stale-reference reconciliation (root cause 3). The pinned
     * lastActiveProjectId must be ACTIVE and OWNED by this workspace.
     * Stale → the workspace's most recently updated ACTIVE OWNED project
     * (deterministic, never "project 1"/first-project). Returns
     * (project, repairNote?).
     */
    private suspend fun reconcileActiveProject(workspace: WorkspaceEntity): Pair<ProjectEntity?, String?> {
        val workspaceId = workspace.id
        val pinned = workspace.lastActiveProjectId

        if (pinned != null && pinned > 0) {
            val resolvable = projectDao.resolvableProjectForWorkspace(pinned, workspaceId)
            if (resolvable != null) return resolvable to null

            // Stale: missing / archived / trashed / foreign workspace.
            // Repair deterministically to the newest ACTIVE OWNED project.
            val replacement = projectDao.mostRecentActiveProjectForWorkspace(workspaceId)
            database.withTransaction {
                workspaceDao.setActiveProject(workspaceId, replacement?.id, System.currentTimeMillis())
            }
            return replacement to "reconciled_stale_project(pinned=$pinned → ${replacement?.id ?: "none"})"
        }

        // No pin: deterministically bind the newest ACTIVE OWNED project.
        val replacement = projectDao.mostRecentActiveProjectForWorkspace(workspaceId)
        if (replacement != null && replacement.id != pinned) {
            database.withTransaction {
                workspaceDao.setActiveProject(workspaceId, replacement.id, System.currentTimeMillis())
            }
            return replacement to "bound_most_recent_active_project(${replacement.id})"
        }
        return replacement to null
    }

    /** Creates a project row + activates it for the workspace — ONE transaction. */
    private suspend fun createProjectInTransaction(
        workspaceId: String,
        name: String,
        description: String,
        activate: Boolean
    ): ProjectEntity? {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val id = insertProjectRow(workspaceId, name, description, now)
            if (activate) workspaceDao.setActiveProject(workspaceId, id, now)
            projectDao.getProjectById(id)
        }
    }

    private suspend fun insertProjectRow(
        workspaceId: String,
        name: String,
        description: String,
        now: Long
    ): Long {
        val provisional = ProjectEntity(
            name = name,
            description = description,
            rootPath = "",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            workspaceId = workspaceId,
            lifecycleState = "ACTIVE"
        )
        val id = projectDao.insertProject(provisional)
        projectDao.updateProject(
            provisional.copy(id = id, rootPath = projectRootResolver(id).canonicalPath)
        )
        return id
    }
}

/** The bootstrap state machine phases (REPAIR ORDER §3A). */
sealed class BootstrapPhase {
    val isReady: Boolean get() = this is Ready
    val label: String
        get() = when (this) {
            is Bootstrapping -> "BOOTSTRAPPING"
            is WorkspaceReady -> "WORKSPACE_READY"
            is ProjectResolved -> "PROJECT_RESOLVED"
            is ContextReady -> "CONTEXT_READY"
            is Ready -> "READY"
            is Failed -> "FAILED"
        }

    data object Bootstrapping : BootstrapPhase()
    data class WorkspaceReady(val workspaceId: String) : BootstrapPhase()
    data class ProjectResolved(val workspaceId: String, val projectId: Long, val repairNote: String? = null) : BootstrapPhase()
    data class ContextReady(val workspaceId: String, val projectId: Long, val repairNote: String? = null) : BootstrapPhase()
    data class Ready(val workspaceId: String, val projectId: Long, val repairNote: String? = null) : BootstrapPhase()
    data class Failed(val failure: BootstrapFailure, val message: String) : BootstrapPhase()
}

enum class BootstrapFailure {
    NO_WORKSPACE,
    PROJECT_NOT_FOUND,
    CONTEXT_REPAIR_REQUIRED,
    BOOTSTRAP_FAILED
    // GAP-25 (Design Closure 2026): PROJECT_CORRUPT removed — never emitted;
    // a corrupt root path is repaired (repairNote + degradation event), see
    // the class KDoc.
}

/** Observable state value consumed by WorkspaceRuntimeService / UI. */
data class BootstrapState(
    val phase: BootstrapPhase
) {
    val workspaceId: String?
        get() = when (val p = phase) {
            is BootstrapPhase.WorkspaceReady -> p.workspaceId
            is BootstrapPhase.ProjectResolved -> p.workspaceId
            is BootstrapPhase.ContextReady -> p.workspaceId
            is BootstrapPhase.Ready -> p.workspaceId
            else -> null
        }
    val projectId: Long?
        get() = when (val p = phase) {
            is BootstrapPhase.ProjectResolved -> p.projectId
            is BootstrapPhase.ContextReady -> p.projectId
            is BootstrapPhase.Ready -> p.projectId
            else -> null
        }
    val isReady: Boolean get() = phase is BootstrapPhase.Ready
    val isFailed: Boolean get() = phase is BootstrapPhase.Failed

    companion object {
        val BOOTSTRAPPING = BootstrapState(BootstrapPhase.Bootstrapping)
    }
}
