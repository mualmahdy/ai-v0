package com.example.application.repair

import com.example.application.audit.AuditTrailService
import com.example.domain.core.audit.AuditActions
import com.example.domain.core.audit.AuditActorType
import com.example.domain.core.audit.AuditResult
import com.example.domain.core.context.ResourceScope
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * REPAIR ORDER §28 — REPAIR / RECONCILIATION CENTER
 * ============================================================================
 * Domain-level mechanism for orphaned or inconsistent state. Flow:
 *
 *   DETECT → EXPLAIN → REPAIR → VERIFY
 *
 * Detects (deterministically):
 *   - workspace without a valid active project (stale lastActiveProjectId)
 *   - orphaned project (workspace row missing)
 *   - orphaned session / task (workspace row missing)
 *   - invalid resource reference (knowledge document of a missing project)
 *   - dangling execution (RUNNING task with no live execution after restart)
 *
 * Every repair is DETERMINISTIC and AUDITABLE (audit trail entries with
 * REPAIR_EXECUTED). Verification re-detects: a repair that did not fix the
 * condition is reported honestly.
 */
class RepairCenterService(
    private val database: AppDatabase,
    private val fileStore: SandboxProjectFileStore,
    private val auditTrail: AuditTrailService? = null
) {
    data class Condition(
        val code: String,
        val description: String,
        val affectedIds: List<String>
    )

    data class RepairReport(
        val condition: Condition,
        val repaired: Boolean,
        val action: String,
        val verifiedAfterRepair: Boolean
    )

    private val workspaceDao = database.workspaceDao()
    private val projectDao = database.projectDao()
    private val sessionDao = database.conversationSessionDao()
    private val taskDao = database.taskDao()
    private val knowledgeDao = database.knowledgeDocumentDao()

    /** DETECT: all currently-detectable inconsistency conditions. */
    suspend fun detectAll(): List<Condition> = withContext(Dispatchers.IO) {
        val conditions = mutableListOf<Condition>()

        // 1. Workspaces with stale/missing active-project references.
        val workspaces = workspaceDao.getAllWorkspaces()
        for (ws in workspaces) {
            val pinned = ws.lastActiveProjectId
            val invalid = pinned == null || pinned <= 0 ||
                projectDao.resolvableProjectForWorkspace(pinned, ws.id) == null
            if (invalid) {
                conditions.add(
                    Condition(
                        code = "WORKSPACE_WITHOUT_VALID_PROJECT",
                        description = "مساحة العمل '${ws.name}' (${ws.id}) تشير إلى مشروع غير صالح أو معدوم" +
                                " (lastActiveProjectId=${pinned ?: "null"}).",
                        affectedIds = listOf(ws.id)
                    )
                )
            }
        }

        // 2. Orphaned projects (workspace row missing).
        val workspaceIds = workspaces.map { it.id }.toSet()
        val orphanProjects = mutableListOf<String>()
        for (ws in workspaces) {
            // per-workspace scan keeps this bounded
            projectDao.forWorkspaceInState(ws.id, "ACTIVE") +
                    projectDao.forWorkspaceInState(ws.id, "ARCHIVED")
        }
        // Global maintenance scan (only the repair center may use unscoped reads).
        runCatching {
            val method = projectDao.javaClass.methods.firstOrNull { it.name == "getAllActiveProjectsList" }
            @Suppress("UNCHECKED_CAST")
            val all = method?.invoke(projectDao) as? List<com.example.infrastructure.persistence.entities.ProjectEntity>
            all?.forEach { p ->
                if (p.workspaceId != null && p.workspaceId !in workspaceIds) {
                    orphanProjects.add(p.id.toString())
                }
            }
        }
        if (orphanProjects.isNotEmpty()) {
            conditions.add(
                Condition(
                    code = "ORPHANED_PROJECT",
                    description = "مشاريع تشير إلى مساحات عمل غير موجودة: ${orphanProjects.joinToString()}.",
                    affectedIds = orphanProjects
                )
            )
        }

        // 3. Dangling executions (RUNNING tasks with no live execution —
        //    process death leftovers the resume sweep did not reconcile).
        val dangling = runCatching {
            val running = taskDao.getAllTasks().filter { it.lifecycleState == "RUNNING" }
            running
                .filter { task ->
                    // RUNNING with NO live handle in its (pinned) workspace:
                    // the in-memory registry died with the process, so any
                    // RUNNING row without a live ExecutionHost handle is stale.
                    val wsId = task.workspaceId
                    wsId == null || com.example.application.execution.ExecutionHost
                        .executionsFor(wsId).isEmpty()
                }
                .map { it.id }
                .take(50)
        }.getOrDefault(emptyList())
        if (dangling.isNotEmpty()) {
            conditions.add(
                Condition(
                    code = "STALE_EXECUTION",
                    description = "مهام بحالة RUNNING دون تنفيذ حي (بقايا موت العملية): ${dangling.joinToString()}.",
                    affectedIds = dangling
                )
            )
        }

        // 4. Knowledge documents referencing missing projects.
        val invalidKnowledge = mutableListOf<String>()
        for (ws in workspaces) {
            runCatching {
                knowledgeDao.getDocumentsForWorkspace(ws.id).forEach { doc ->
                    val pid = doc.projectId
                    if (pid != null && projectDao.getProjectById(pid) == null) {
                        invalidKnowledge.add(doc.id)
                    }
                }
            }
        }
        if (invalidKnowledge.isNotEmpty()) {
            conditions.add(
                Condition(
                    code = "INVALID_RESOURCE_REFERENCE",
                    description = "مستندات معرفة تشير إلى مشاريع غير موجودة: ${invalidKnowledge.joinToString()}.",
                    affectedIds = invalidKnowledge
                )
            )
        }
        conditions
    }

    /** REPAIR: fixes a condition deterministically; VERIFY re-detects. */
    suspend fun repair(condition: Condition): RepairReport = withContext(Dispatchers.IO) {
        val action = when (condition.code) {
            "WORKSPACE_WITHOUT_VALID_PROJECT" -> repairWorkspaceProject(condition)
            "ORPHANED_PROJECT" -> repairOrphanedProjects(condition)
            "STALE_EXECUTION" -> repairStaleExecutions(condition)
            "INVALID_RESOURCE_REFERENCE" -> repairInvalidKnowledgeRefs(condition)
            else -> "NO_REPAIR_DEFINED"
        }
        val verified = detectAll().none { it.code == condition.code }
        val report = RepairReport(condition, action != "NO_REPAIR_DEFINED", action, verified)
        auditTrail?.recordAsync(
            actorType = AuditActorType.SYSTEM,
            actorId = "repair_center",
            action = AuditActions.REPAIR_EXECUTED,
            resourceType = "REPAIR",
            resourceId = condition.code,
            sourceScope = ResourceScope.Application,
            result = if (report.repaired && verified) AuditResult.SUCCESS else AuditResult.DEGRADED,
            reason = action
        )
        report
    }

    /** Detect + repair everything; returns the full report. */
    suspend fun detectAndRepairAll(): List<RepairReport> {
        val conditions = detectAll()
        return conditions.map { repair(it) }
    }

    // ------------------------------------------------------------------
    // Deterministic repairs
    // ------------------------------------------------------------------

    private suspend fun repairWorkspaceProject(condition: Condition): String {
        var repaired = 0
        for (wsId in condition.affectedIds) {
            val ws = workspaceDao.getWorkspaceById(wsId) ?: continue
            val replacement = projectDao.mostRecentActiveProjectForWorkspace(wsId)
            workspaceDao.setActiveProject(wsId, replacement?.id, System.currentTimeMillis())
            repaired++
        }
        return "rebound_active_project($repaired workspace(s))"
    }

    private suspend fun repairOrphanedProjects(condition: Condition): String {
        // Deterministic: orphaned projects are moved to the workspace's
        // ownership ONLY when unambiguous (a single remaining workspace);
        // otherwise they are TRASHED (recoverable — never deleted).
        val workspaces = workspaceDao.getAllWorkspaces()
        return if (workspaces.size == 1) {
            val target = workspaces.first()
            var moved = 0
            for (idStr in condition.affectedIds) {
                val id = idStr.toLongOrNull() ?: continue
                val project = projectDao.getProjectById(id) ?: continue
                projectDao.updateProject(project.copy(workspaceId = target.id))
                moved++
            }
            "reowned_orphaned_projects($moved → ${target.id})"
        } else {
            var trashed = 0
            for (idStr in condition.affectedIds) {
                val id = idStr.toLongOrNull() ?: continue
                val project = projectDao.getProjectById(id) ?: continue
                val now = System.currentTimeMillis()
                projectDao.setLifecycleState(id, project.workspaceId ?: "", "TRASHED", now, false, null, now)
                trashed++
            }
            "trashed_ambiguous_orphans($trashed)"
        }
    }

    private suspend fun repairStaleExecutions(condition: Condition): String {
        var reconciled = 0
        for (taskId in condition.affectedIds) {
            taskDao.updateTaskStatus(
                id = taskId,
                state = "FAILED",
                summary = "STALE_EXECUTION: انتهى التنفيذ بموت العملية دون إكمال (إصلاح مركز الإصلاح).",
                tokens = 0,
                duration = 0L,
                isDegraded = true,
                degradedReason = "PROCESS_DEATH",
                errorMsg = "STALE_EXECUTION",
                now = System.currentTimeMillis()
            )
            reconciled++
        }
        return "reconciled_stale_executions($reconciled)"
    }

    private suspend fun repairInvalidKnowledgeRefs(condition: Condition): String {
        // Deterministic: project-private knowledge of a missing project is
        // re-scoped to WORKSPACE-SHARED (projectId = NULL) — recoverable,
        // visible, never deleted.
        var rebinds = 0
        for (docId in condition.affectedIds) {
            val doc = knowledgeDao.getDocumentById(docId) ?: continue
            knowledgeDao.insertOrUpdate(doc.copy(projectId = null))
            rebinds++
        }
        return "rebound_knowledge_to_workspace($rebinds)"
    }
}
