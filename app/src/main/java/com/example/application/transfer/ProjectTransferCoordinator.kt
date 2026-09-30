package com.example.application.transfer

import androidx.room.withTransaction
import com.example.application.audit.AuditTrailService
import com.example.application.project.JournalPhase
import com.example.application.project.ProjectOperationJournal
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.domain.core.rethrowIfCancellation
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

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
    private val auditTrail: AuditTrailService? = null,
    /**
     * CLOSURE P0-3 (audit §5.4/B4): the durable operation journal. Nullable =
     * documented test seam; production passes the AppContainer-wide journal
     * so interrupted moves become REAL recovery work items.
     */
    private val journal: ProjectOperationJournal? = null
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

            // CLOSURE P0-3: the durable journal witnesses the operation from
            // INTENT (PREPARE, before any side effect) through the database
            // commit to FINALIZE — an interrupted move leaves a PENDING_RECOVERY
            // work item instead of forensically nothing.
            val operationId = "move_identity_${UUID.randomUUID().toString().take(16)}"
            journal?.begin(
                operationId = operationId,
                operationType = OPERATION_MOVE_IDENTITY,
                projectId = projectId,
                workspaceId = sourceWorkspaceId,
                detail = "identity move $sourceWorkspaceId → $targetWorkspaceId; " +
                    "staged(sessions=${stagedCounts.sessions}, docs=${stagedCounts.knowledgeDocuments}, " +
                    "tasks=${stagedCounts.tasks}, artifacts=${stagedCounts.artifacts})"
            )

            // ---- TRANSFER + VERIFY (inside ONE transaction) ----
            try {
                journal?.advance(operationId, JournalPhase.COMMIT_DATABASE)
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
                e.rethrowIfCancellation()
                // The transaction rolled back — nothing moved. The journal
                // keeps the interrupted attempt as a PENDING_RECOVERY row so
                // recovery has something truthful to reconcile against.
                journal?.fail(
                    operationId,
                    "identity move failed (transaction rolled back): ${e.message}"
                )
                return@withContext MoveOutcome.Failed(
                    "MOVE_VERIFICATION_FAILED",
                    "فشل التحقق من نقل النطاقات — أُلغيت العملية بالكامل ولم يتغيّر شيء. (${e.message})"
                )
            }
            journal?.advance(operationId, JournalPhase.VERIFY_POST_STATE)

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
            journal?.complete(
                operationId,
                "identity move committed and verified; project now in $targetWorkspaceId"
            )
            MoveOutcome.IdentityPreserved(projectId, targetWorkspaceId, stagedCounts)
        }
    }

    /**
     * CROSS-PACKAGE move (new identity) — the verified package pipeline.
     *
     * CLOSURE P0-3 (honest envelope): journaled at the coordinator boundary
     * (begin / complete / fail). The package pipeline's INTERNAL phases
     * (staged files → one DB transaction → post-state verify) are not yet
     * journaled per-phase — that deeper integration is the import/export
     * stage's work; the envelope already makes an interrupted package move
     * a visible recovery work item instead of silence.
     */
    suspend fun moveProjectVerifiedPackage(
        sourceWorkspaceId: String,
        sourceProjectId: Long,
        targetWorkspaceId: String
    ): MoveOutcome = withContext(Dispatchers.IO) {
        val operationId = "move_package_${UUID.randomUUID().toString().take(16)}"
        journal?.begin(
            operationId = operationId,
            operationType = OPERATION_MOVE_PACKAGE,
            projectId = sourceProjectId,
            workspaceId = sourceWorkspaceId,
            detail = "package move $sourceWorkspaceId → $targetWorkspaceId"
        )
        val (newId, outcome) = packageService.moveProjectVerified(sourceWorkspaceId, sourceProjectId, targetWorkspaceId)
        when {
            newId != null -> {
                journal?.complete(
                    operationId,
                    "package move committed; new project id $newId in $targetWorkspaceId"
                )
                MoveOutcome.NewIdentity(newId!!, targetWorkspaceId)
            }
            outcome is TransferOutcome.Failure -> {
                journal?.fail(operationId, "package move failed: ${outcome.code} — ${outcome.message}")
                MoveOutcome.Failed(outcome.code, outcome.message)
            }
            else -> {
                journal?.fail(operationId, "package move failed for an unknown reason")
                MoveOutcome.Failed("MOVE_FAILED", "فشل النقل لسبب غير معروف.")
            }
        }
    }

    /**
     * CLOSURE P0-3 (recovery surface): reconciles journaled-but-interrupted
     * identity moves against the DATABASE's actual state — the journal never
     * invents state; it only gives recovery something truthful to reconcile
     * against.
     *
     * For each PENDING_RECOVERY identity-move entry:
     *  - project row now in the TARGET workspace → the transaction COMMITTED
     *    before the crash: re-run the source-pointer cleanup and close the
     *    entry as RECOVERED;
     *  - project row still in the SOURCE workspace → the transaction rolled
     *    back (or never committed): nothing moved; close as RECOVERED with
     *    the honest reason;
     *  - project row in NEITHER → a genuinely partial state the coordinator
     *    cannot auto-resolve: the entry STAYS pending for RepairCenter/
     *    manual reconciliation (returned as [ReconciliationResult.unresolved]).
     */
    suspend fun reconcileInterruptedOperations(): ReconciliationResult = moveMutex.withLock {
        withContext(Dispatchers.IO) {
            val pending = journal?.pendingRecovery().orEmpty()
                .filter { it.operationType == OPERATION_MOVE_IDENTITY }
            var recovered = 0
            val unresolved = mutableListOf<com.example.application.project.JournalEntry>()
            for (entry in pending) {
                val projectId = entry.projectId
                if (projectId == null) {
                    unresolved += entry
                    continue
                }
                val inSource = database.projectDao()
                    .getProjectByIdForWorkspace(projectId, entry.workspaceId) != null
                val targetWorkspaceId = extractTargetWorkspace(entry)
                val inTarget = targetWorkspaceId != entry.workspaceId &&
                    database.projectDao()
                        .getProjectByIdForWorkspace(projectId, targetWorkspaceId) != null
                when {
                    inSource -> {
                        journal?.markRecovered(
                            entry.operationId,
                            "reconciled: transaction rolled back — project still in source workspace"
                        )
                        recovered++
                    }
                    inTarget -> {
                        // The move committed; only the source-pointer cleanup
                        // may be missing — run it idempotently.
                        runCatching {
                            val source = database.workspaceDao().getWorkspaceById(entry.workspaceId)
                            if (source?.lastActiveProjectId == projectId) {
                                database.workspaceDao().setActiveProject(
                                    entry.workspaceId, null, System.currentTimeMillis()
                                )
                            }
                        }
                        journal?.markRecovered(
                            entry.operationId,
                            "reconciled: transaction committed — cleanup re-applied"
                        )
                        recovered++
                    }
                    else -> unresolved += entry
                }
            }
            ReconciliationResult(recovered = recovered, unresolved = unresolved)
        }
    }

    data class ReconciliationResult(
        val recovered: Int,
        val unresolved: List<com.example.application.project.JournalEntry>
    )

    /** Best-effort target-workspace extraction from the journaled detail. */
    private fun extractTargetWorkspace(entry: com.example.application.project.JournalEntry): String =
        entry.detail
            ?.substringAfter("→", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: entry.workspaceId

    companion object {
        const val OPERATION_MOVE_IDENTITY = "PROJECT_MOVE_IDENTITY"
        const val OPERATION_MOVE_PACKAGE = "PROJECT_MOVE_PACKAGE"
    }
}
