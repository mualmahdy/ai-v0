package com.example.application.transfer

import androidx.room.withTransaction
import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * CLOSURE §4.1 — THE single source of truth for what "Move" means
 * ============================================================================
 *
 * Two Move semantics existed before this class, and they CONTRADICTED each
 * other:
 *  - `ProjectRuntimeService.moveProject()` updated ONLY `projects.workspaceId`
 *    while sessions/knowledge/chunks/tasks/artifacts kept the OLD workspace
 *    id — the project vanished from the target workspace's views and its rows
 *    were orphaned in the source.
 *  - `ProjectPackageService.moveProjectVerified()` exported→imported (new
 *    identity) with a row-existence-only "verification" and a source
 *    cleanup that missed tasks/artifacts/snapshots.
 *
 * This coordinator is now the ONE authority. Both entry points route here:
 *
 *  1. [moveProjectVerifiedIdentity] — SAME-DEVICE move, IDENTITY-PRESERVING:
 *     Stage → Transfer → Verify → Commit → Cleanup. The sandbox root is
 *     keyed by projectId (no file copy needed); every scoped row
 *     (sessions + turns + timeline, knowledge + chunks, tasks, artifacts) is
 *     rebound to the target workspace inside ONE Room transaction, and the
 *     rebind is VERIFIED by before/after count witnesses — a rebind that
 *     misses rows rolls the whole transaction back instead of silently
 *     orphaning them.
 *
 *  2. [moveProjectVerifiedPackage] — CROSS-PACKAGE move (new identity):
 *     delegates to [ProjectPackageService.moveProjectVerified], whose import
 *     pipeline is atomic (staged files + one DB transaction + post-state
 *     verification with full rollback) and whose source cleanup is a FULL
 *     cascade.
 *
 * Both paths audit `PROJECT_MOVED` with their semantics in the reason field.
 */
class ProjectTransferCoordinator(
    private val database: AppDatabase,
    private val fileStore: SandboxProjectFileStore,
    private val packageService: ProjectPackageService,
    private val auditTrail: AuditTrailService? = null
) {

    private val moveMutex = Mutex()

    sealed interface MoveOutcome {
        /** [projectId] is the SAME id (identity preserved), now owned by [targetWorkspaceId]. */
        data class IdentityPreserved(
            val projectId: Long,
            val targetWorkspaceId: String,
            val verifiedCounts: ScopedCounts
        ) : MoveOutcome

        /** A NEW project id was created in the target workspace. */
        data class NewIdentity(
            val projectId: Long,
            val targetWorkspaceId: String
        ) : MoveOutcome

        data class Failed(val code: String, val message: String) : MoveOutcome
    }

    /** Before/after count witnesses for the verified rebind. */
    data class ScopedCounts(
        val sessions: Int,
        val knowledgeDocuments: Int,
        val tasks: Int,
        val artifacts: Int
    )

    /**
     * IDENTITY-PRESERVING move: Stage → Transfer → Verify → Commit → Cleanup.
     *
     * Stage: the scoped-row counts are captured BEFORE the move (witnesses).
     * Transfer: ONE Room transaction rebinds every scoped row's workspaceId.
     * Verify: counts are re-read INSIDE the transaction — any mismatch throws
     * and Room rolls the whole rebind back (no partial move can commit).
     * Commit: the transaction itself (durable by definition).
     * Cleanup: the source workspace's active-project pointer is cleared if it
     * pointed at the moved project; the target workspace's selection is left
     * to the user (a move never hijacks their active selection).
     */
    suspend fun moveProjectVerifiedIdentity(
        projectId: Long,
        sourceWorkspaceId: String,
        targetWorkspaceId: String
    ): MoveOutcome = moveMutex.withLock {
        withContext(Dispatchers.IO) {
            if (sourceWorkspaceId == targetWorkspaceId) {
                return@withContext MoveOutcome.Failed("SAME_WORKSPACE", "المشروع موجود بالفعل في المساحة الهدف.")
            }
            // ---- STAGE ----
            val project = database.projectDao().getProjectByIdForWorkspace(projectId, sourceWorkspaceId)
                ?: return@withContext MoveOutcome.Failed("PROJECT_NOT_FOUND", "المشروع غير موجود في المساحة المصدر.")
            if (database.workspaceDao().getWorkspaceById(targetWorkspaceId) == null) {
                return@withContext MoveOutcome.Failed("TARGET_WORKSPACE_NOT_FOUND", "مساحة العمل الهدف غير موجودة.")
            }
            val stagedCounts = ScopedCounts(
                sessions = database.projectDao().countSessionsForProjectInWorkspace(projectId, sourceWorkspaceId),
                knowledgeDocuments = database.projectDao().countKnowledgeForProjectInWorkspace(projectId, sourceWorkspaceId),
                tasks = database.projectDao().countTasksForProjectInWorkspace(projectId, sourceWorkspaceId),
                artifacts = database.projectDao().countArtifactsForProjectInWorkspace(projectId, sourceWorkspaceId)
            )

            // ---- TRANSFER + VERIFY (inside ONE transaction) ----
            try {
                database.withTransaction {
                    val moved = database.projectDao().moveProjectToWorkspace(
                        projectId, sourceWorkspaceId, targetWorkspaceId, System.currentTimeMillis()
                    )
                    if (moved == 0) {
                        throw IllegalStateException("PROJECT_ROW_NOT_MOVED")
                    }
                    // Rebind EVERY scoped row to the target workspace.
                    database.conversationSessionDao().rebindWorkspaceForProject(projectId, targetWorkspaceId)
                    database.knowledgeDocumentDao().rebindWorkspaceForProject(projectId, targetWorkspaceId)
                    database.documentChunkDao().rebindWorkspaceForProject(projectId, targetWorkspaceId)
                    database.taskDao().rebindTasksWorkspace(projectId, targetWorkspaceId)
                    database.artifactDao().rebindWorkspaceForProject(projectId, targetWorkspaceId)

                    // VERIFY (in-tx): every staged row must now be in the
                    // target workspace and NONE may remain in the source.
                    fun assert(condition: Boolean, message: String) {
                        if (!condition) throw IllegalStateException(message)
                    }
                    assert(
                        database.projectDao().countSessionsForProjectInWorkspace(projectId, targetWorkspaceId) == stagedCounts.sessions,
                        "SESSION_REBIND_INCOMPLETE"
                    )
                    assert(
                        database.projectDao().countKnowledgeForProjectInWorkspace(projectId, targetWorkspaceId) == stagedCounts.knowledgeDocuments,
                        "KNOWLEDGE_REBIND_INCOMPLETE"
                    )
                    assert(
                        database.projectDao().countTasksForProjectInWorkspace(projectId, targetWorkspaceId) == stagedCounts.tasks,
                        "TASK_REBIND_INCOMPLETE"
                    )
                    assert(
                        database.projectDao().countArtifactsForProjectInWorkspace(projectId, targetWorkspaceId) == stagedCounts.artifacts,
                        "ARTIFACT_REBIND_INCOMPLETE"
                    )
                    assert(
                        database.projectDao().countSessionsForProjectInWorkspace(projectId, sourceWorkspaceId) == 0 &&
                                database.projectDao().countKnowledgeForProjectInWorkspace(projectId, sourceWorkspaceId) == 0 &&
                                database.projectDao().countTasksForProjectInWorkspace(projectId, sourceWorkspaceId) == 0 &&
                                database.projectDao().countArtifactsForProjectInWorkspace(projectId, sourceWorkspaceId) == 0,
                        "SOURCE_ROWS_NOT_CLEARED"
                    )
                }
            } catch (e: Exception) {
                // The transaction rolled back — nothing moved.
                return@withContext MoveOutcome.Failed(
                    "MOVE_VERIFICATION_FAILED",
                    "فشل التحقق من نقل النطاقات — أُلغيت العملية بالكامل ولم يتغيّر شيء. (${e.message})"
                )
            }

            // ---- CLEANUP ----
            // The source workspace's active-project pointer is cleared when it
            // pointed at the moved project (it no longer owns it).
            runCatching {
                val source = database.workspaceDao().getWorkspaceById(sourceWorkspaceId)
                if (source?.lastActiveProjectId == projectId) {
                    database.workspaceDao().setActiveProject(sourceWorkspaceId, null, System.currentTimeMillis())
                }
            }

            auditTrail?.recordAsync(
                actorType = AuditActorType.IMPORTER,
                actorId = "transfer_coordinator",
                action = AuditActions.PROJECT_MOVED,
                resourceType = "PROJECT",
                resourceId = projectId.toString(),
                sourceScope = ResourceScope.Workspace(sourceWorkspaceId),
                result = AuditResult.SUCCESS,
                reason = "identity-preserving move to=$targetWorkspaceId " +
                        "sessions=${stagedCounts.sessions} docs=${stagedCounts.knowledgeDocuments} " +
                        "tasks=${stagedCounts.tasks} artifacts=${stagedCounts.artifacts}"
            )
            MoveOutcome.IdentityPreserved(projectId, targetWorkspaceId, stagedCounts)
        }
    }

    /** CROSS-PACKAGE move (new identity) — the verified package pipeline. */
    suspend fun moveProjectVerifiedPackage(
        sourceWorkspaceId: String,
        sourceProjectId: Long,
        targetWorkspaceId: String
    ): MoveOutcome = withContext(Dispatchers.IO) {
        val (newId, outcome) = packageService.moveProjectVerified(sourceWorkspaceId, sourceProjectId, targetWorkspaceId)
        when {
            newId != null -> MoveOutcome.NewIdentity(newId!!, targetWorkspaceId)
            outcome is TransferOutcome.Failure -> MoveOutcome.Failed(outcome.code, outcome.message)
            else -> MoveOutcome.Failed("MOVE_FAILED", "فشل النقل لسبب غير معروف.")
        }
    }
}
