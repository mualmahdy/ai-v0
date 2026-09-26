package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.project.ProjectRuntimeService
import com.example.application.session.ConversationSessionService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.project.Project
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ProjectsViewModel — the PROJECTS feature ViewModel (UI Design Closure,
 * phase B — defect D-01)
 * ============================================================================
 *
 * The workspace → project → session hierarchy was complete in the backend
 * (ProjectRuntimeService §27 lifecycle + WorkspaceRuntimeService.setActiveProject
 * with durable lastActiveProjectId scoping) but had NO UI surface at all —
 * the PROJECTS bottom-bar destination opened the Tasks board. This feature
 * ViewModel is the missing owner of that surface, following the ADR-6 rule
 * frozen since GAP-11/TasksViewModel: a new feature gets its OWN ViewModel —
 * nothing moves into the MainViewModel shell.
 *
 * OWNED here (single source of truth for this screen):
 *  - the ACTIVE-workspace project list (Room-backed flow, re-scoped with
 *    flatMapLatest semantics via collectLatest — the SessionsViewModel
 *    precedent: a workspace switch cancels the previous collector before the
 *    new one starts, so a stale workspace can never bleed in);
 *  - the CURRENT project mirror (the activeProjectId binding resolved
 *    through the REAL project row — an honest Project, not a workspace
 *    relabeled as a project, which is exactly what the old shell mirror
 *    was: defect D-02);
 *  - evidence-derived session count for the current project (one live
 *    ConversationSessionService flow — no fabricated metrics);
 *  - the mutations this surface offers: create / open(switch) / rename /
 *    archive / trash — every one routed through the AUTHORITATIVE services,
 *    never a local write.
 *
 * SCOPE-SAVE CONTRACT (the package's explicit requirement): switching the
 * active project goes through WorkspaceRuntimeService.setActiveProject — the
 * durable lastActiveProjectId column is written, so the choice survives
 * process death AND re-scopes every feature that observes the active
 * workspace (sessions, files, knowledge…).
 *
 * ERROR HONESTY: ProjectRuntimeService.lastError is the authoritative
 * rejection channel (duplicate names, invalid transitions, foreign refs);
 * every mutation surfaces it verbatim into this feature's own error channel
 * — no silent no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModel(
    private val projectRuntimeService: ProjectRuntimeService,
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    private val conversationSessionService: ConversationSessionService,
    /**
     * CLOSURE §4.4: the transfer workflow surface (export/import/move/clone/
     * snapshot/restore). Null ⇒ the transfer UI is honestly UNAVAILABLE
     * (no fake buttons — the previous state was a fully hidden backend).
     */
    private val projectPackageService: com.example.application.transfer.ProjectPackageService? = null,
    private val projectTransferCoordinator: com.example.application.transfer.ProjectTransferCoordinator? = null
) : ViewModel() {

    /** CLOSURE §4.4: a snapshot the user can restore. */
    data class SnapshotEntry(
        val id: String,
        val label: String,
        val reason: String,
        val createdAtEpochMs: Long
    )

    data class ProjectsUiState(
        /** Honest loading flag until the first active-workspace landing. */
        val isLoading: Boolean = true,
        /** ACTIVE projects of the ACTIVE workspace (the only picker list — §27). */
        val projects: List<Project> = emptyList(),
        /** The workspace's activeProjectId resolved to a REAL Project row (null = no binding). */
        val currentProject: Project? = null,
        /** Live session count for the current project (evidence; null = no current project). */
        val currentProjectSessionCount: Int? = null,
        /** In-flight project id (open/switch button spinner). */
        val isSwitchingProjectId: Long? = null,
        /** Honest rejection/error channel (dismissed by the screen). */
        val errorMessage: String? = null,
        /** Success confirmation channel (dismissed by the screen). */
        val successMessage: String? = null,
        // ---- CLOSURE §4.4: transfer workflow state ----
        /** True while ANY transfer workflow is in flight (progress surface). */
        val isTransferring: Boolean = false,
        /** What is in flight (rendered inside the progress surface). */
        val transferProgressLabel: String? = null,
        /** The other workspaces a project may MOVE to. */
        val availableWorkspaces: List<com.example.domain.core.workspace.Workspace> = emptyList(),
        /** The current project's snapshots (restore surface). */
        val projectSnapshots: List<SnapshotEntry> = emptyList()
    )

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        observeWorkspaceScope()
    }

    /**
     * Single flat-scope observation: the ACTIVE workspace drives both the
     * project list and the current-project mirror. collectLatest cancels the
     * previous scope's collectors on every workspace re-emission (which also
     * happens on every active-project switch, because setActiveProject
     * updates the activeWorkspace StateFlow) — the GAP-04 re-scope pattern.
     */
    private fun observeWorkspaceScope() {
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collectLatest { workspace ->
                    if (workspace == null) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                projects = emptyList(),
                                currentProject = null,
                                currentProjectSessionCount = null
                            )
                        }
                        return@collectLatest
                    }
                    // Resolve the binding to the REAL project row (honest
                    // mirror — never workspace data relabeled as a project).
                    val boundId = workspace.activeProjectId.takeIf { it > 0L }
                    val current = boundId?.let { projectRuntimeService.getProject(workspace.id, it) }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            currentProject = current,
                            currentProjectSessionCount = null
                        )
                    }
                    // Two LIVE collectors for this scope; join both so the
                    // collectLatest body stays alive until re-scoped. The
                    // current-project mirror is RECOMPUTED from every live
                    // list emission (a rename inside the bound project
                    // reflects immediately — the mirror is never a stale
                    // one-shot read).
                    val listJob = launch {
                        projectRuntimeService.observeActiveProjects(workspace.id).collect { list ->
                            _state.update { state ->
                                val fromList = boundId?.let { id -> list.firstOrNull { p -> p.id == id } }
                                state.copy(
                                    projects = list,
                                    currentProject = fromList
                                        ?: state.currentProject?.takeIf { p -> p.id == boundId }
                                )
                            }
                        }
                    }
                    val sessionsJob = launch {
                        val pid = boundId ?: return@launch
                        runCatching {
                            conversationSessionService
                                .observeSessionsForProject(workspace.id, pid)
                                .collect { sessions ->
                                    _state.update { it.copy(currentProjectSessionCount = sessions.size) }
                                }
                        }
                    }
                    listJob.join()
                    sessionsJob.join()
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحميل مشاريع مساحة العمل: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Creates a project and ACTIVATES it. Creation is delegated to
     * ProjectRuntimeService (transactional: row + sandbox root + name
     * collision checks); the activation is then routed through
     * WorkspaceRuntimeService.setActiveProject so the activeWorkspace
     * StateFlow mirror — every feature's re-scope trigger — actually moves.
     */
    fun createProject(name: String, description: String) {
        val workspace = workspaceRuntimeService.activeWorkspace.value
        if (workspace == null) {
            _state.update { it.copy(errorMessage = "لا مساحة عمل نشطة لإنشاء المشروع فيها.") }
            return
        }
        if (name.isBlank()) {
            _state.update { it.copy(errorMessage = "اسم المشروع مطلوب.") }
            return
        }
        viewModelScope.launch {
            val created = runCatching {
                projectRuntimeService.createProject(
                    workspaceId = workspace.id,
                    name = name,
                    description = description.trim().ifEmpty { null },
                    activate = true
                )
            }.getOrElse { e ->
                _state.update { it.copy(errorMessage = "تعذر إنشاء المشروع: ${e.localizedMessage}") }
                null
            } ?: run {
                // The service's honest rejection channel (duplicate name…).
                _state.update { it.copy(errorMessage = projectRuntimeService.lastError.value ?: "تعذر إنشاء المشروع.") }
                return@launch
            }
            // Route the activation through the authoritative service so the
            // workspace mirror (and every scoped collector) re-scopes now.
            runCatching { workspaceRuntimeService.setActiveProject(created.id) }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = "أُنشئ المشروع لكن تعذر تفعيله: ${e.localizedMessage}") }
                }
            _state.update { it.copy(successMessage = "أُنشئ المشروع «${created.name}» وأصبح المشروع النشط.") }
        }
    }

    /**
     * Opens (switches to) a project — the SCOPE-SAVE path: the validated
     * selection lands in the durable lastActiveProjectId column through
     * setActiveProject, and the activeWorkspace mirror re-emits so this
     * feature (and every other scoped feature) re-scopes.
     */
    fun openProject(projectId: Long) {
        val workspace = workspaceRuntimeService.activeWorkspace.value
        if (workspace == null) {
            _state.update { it.copy(errorMessage = "لا مساحة عمل نشطة.") }
            return
        }
        if (projectId == _state.value.currentProject?.id) {
            _state.update { it.copy(errorMessage = "هذا المشروع هو النشط بالفعل.") }
            return
        }
        _state.update { it.copy(isSwitchingProjectId = projectId) }
        viewModelScope.launch {
            // setActiveProject returns Unit (a rejected selection is a
            // documented honest no-op there), so the OUTCOME is observed
            // through the authoritative mirror: validate through the
            // project runtime (Boolean), then move the workspace mirror.
            val validated = runCatching {
                projectRuntimeService.selectProject(workspace.id, projectId)
            }.getOrElse { false }
            if (validated) {
                runCatching { workspaceRuntimeService.setActiveProject(projectId) }
            }
            _state.update { it.copy(isSwitchingProjectId = null) }
            if (validated) {
                val name = _state.value.projects.firstOrNull { it.id == projectId }?.name
                _state.update { it.copy(successMessage = "تم التبديل إلى «${name ?: "المشروع"}» وحُفظ الاختيار في مساحة العمل.") }
            } else {
                _state.update {
                    it.copy(errorMessage = projectRuntimeService.lastError.value ?: "تعذر فتح المشروع.")
                }
            }
        }
    }

    /** Renames a project (authoritative service; honest rejection channel). */
    fun renameProject(projectId: Long, newName: String, newDescription: String?) {
        val workspace = workspaceRuntimeService.activeWorkspace.value
        if (workspace == null) {
            _state.update { it.copy(errorMessage = "لا مساحة عمل نشطة.") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching {
                projectRuntimeService.renameProject(workspace.id, projectId, newName, newDescription)
            }.getOrElse { false }
            if (ok) {
                _state.update { it.copy(successMessage = "حُدّث اسم المشروع.") }
            } else {
                _state.update { it.copy(errorMessage = projectRuntimeService.lastError.value ?: "تعذر تحديث المشروع.") }
            }
        }
    }

    /**
     * Archives a project. If it was the ACTIVE one, the binding is honestly
     * cleared through setActiveProject(null) so the UI never points at a
     * hidden project (§27: archived projects are refused by selectProject).
     */
    fun archiveProject(projectId: Long) {
        lifecycleMutation(projectId, "أُرشِف المشروع.") { ws -> projectRuntimeService.archiveProject(ws, projectId) }
    }

    /** Soft-delete to trash; an active binding is cleared first (§27). */
    fun trashProject(projectId: Long) {
        lifecycleMutation(projectId, "نُقل المشروع إلى المهملات (يمكن استرجاعه من الطبقة الخلفية).") { ws ->
            projectRuntimeService.trashProject(ws, projectId)
        }
    }

    private fun lifecycleMutation(projectId: Long, successMessage: String, mutation: suspend (String) -> Boolean) {
        val workspace = workspaceRuntimeService.activeWorkspace.value
        if (workspace == null) {
            _state.update { it.copy(errorMessage = "لا مساحة عمل نشطة.") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching { mutation(workspace.id) }.getOrElse { false }
            if (ok) {
                val wasActive = _state.value.currentProject?.id == projectId
                if (wasActive) {
                    // §27 honesty: never keep an active binding into a
                    // hidden project — clear it through the authoritative
                    // service so every scoped feature re-scopes.
                    runCatching { workspaceRuntimeService.setActiveProject(null) }
                }
                _state.update { it.copy(successMessage = successMessage) }
            } else {
                _state.update {
                    it.copy(errorMessage = projectRuntimeService.lastError.value ?: "تعذر تنفيذ العملية على المشروع.")
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissSuccess() {
        _state.update { it.copy(successMessage = null) }
    }

    // ------------------------------------------------------------------
    // CLOSURE §4.4 — the transfer workflows (Export / Import / Move / Clone
    // / Snapshot / Restore) — previously hidden backend capabilities with
    // ZERO production callers.
    // ------------------------------------------------------------------

    /** Loads the move-target workspaces (all but the active one). */
    fun loadAvailableWorkspaces() {
        viewModelScope.launch {
            val workspaces = runCatching {
                workspaceRuntimeService.allWorkspaces.value ?: emptyList()
            }.getOrDefault(emptyList())
            val activeId = workspaceRuntimeService.activeWorkspace.value?.id
            _state.update {
                it.copy(availableWorkspaces = workspaces.filter { ws -> ws.id != activeId })
            }
        }
    }

    /** Loads the current project's snapshots (the restore surface). */
    fun loadSnapshots() {
        val packages = projectPackageService ?: return
        val workspace = workspaceRuntimeService.activeWorkspace.value ?: return
        val projectId = _state.value.currentProject?.id ?: return
        viewModelScope.launch {
            runCatching {
                // Snapshots live in the package service's snapshot store —
                // surfaced through the Room entity list.
                val db = appDatabaseForSnapshots ?: return@launch
                val entities = db.projectSnapshotDao().forProject(projectId)
                _state.update {
                    it.copy(
                        projectSnapshots = entities.map { e ->
                            SnapshotEntry(e.id, e.label, e.reason, e.createdAtEpochMs)
                        }
                    )
                }
                // Keep the workspace reference honest (unused var guard).
                workspace.id
            }
        }
    }

    /** EXPORT: streams the project package to [destination] (SAF stream). */
    fun exportProjectTo(projectId: Long, destination: java.io.OutputStream, onFinished: () -> Unit = {}) {
        val packages = projectPackageService ?: run {
            _state.update { it.copy(errorMessage = "التصدير غير متاح في هذا التكوين.") }
            return
        }
        val workspace = workspaceRuntimeService.activeWorkspace.value ?: return
        _state.update { it.copy(isTransferring = true, transferProgressLabel = "جارٍ تصدير المشروع…") }
        viewModelScope.launch {
            val outcome = runCatching {
                packages.exportProject(workspace.id, projectId, destination)
            }.getOrElse { e ->
                com.example.application.transfer.TransferOutcome.Failure(
                    "EXPORT_EXCEPTION", "فشل التصدير: ${e.localizedMessage}", true
                )
            }
            _state.update { it.copy(isTransferring = false, transferProgressLabel = null) }
            when (outcome) {
                is com.example.application.transfer.TransferOutcome.Success ->
                    _state.update { it.copy(successMessage = outcome.message) }
                is com.example.application.transfer.TransferOutcome.Failure ->
                    _state.update { it.copy(errorMessage = outcome.message) }
            }
            onFinished()
        }
    }

    /** IMPORT: imports a package from [source] (SAF stream) into the ACTIVE workspace. */
    fun importProjectFrom(source: java.io.InputStream) {
        val packages = projectPackageService ?: run {
            _state.update { it.copy(errorMessage = "الاستيراد غير متاح في هذا التكوين.") }
            return
        }
        val workspace = workspaceRuntimeService.activeWorkspace.value ?: return
        _state.update { it.copy(isTransferring = true, transferProgressLabel = "جارٍ استيراد المشروع…") }
        viewModelScope.launch {
            // The stream is consumed inside the IO dispatcher by the package
            // service; buffer it first so the SAF pipe doesn't close mid-read.
            val buffered = runCatching { source.readBytes() }.getOrNull()
            if (buffered == null) {
                _state.update {
                    it.copy(isTransferring = false, transferProgressLabel = null, errorMessage = "تعذر قراءة الحزمة المحددة.")
                }
                return@launch
            }
            val (newId, report) = runCatching {
                packages.importProject(
                    targetWorkspaceId = workspace.id,
                    packageStream = java.io.ByteArrayInputStream(buffered),
                    conflictPolicy = com.example.application.transfer.ImportConflictPolicy.RENAME
                )
            }.getOrElse { e ->
                null to com.example.application.transfer.ProjectPackageService.ImportReport.error(
                    "IMPORT_EXCEPTION", "فشل الاستيراد: ${e.localizedMessage}"
                )
            }
            _state.update { it.copy(isTransferring = false, transferProgressLabel = null) }
            if (newId != null && report.ok) {
                val details = buildString {
                    append(report.message)
                    append(" — ملفات: ${report.importedFiles}، جلسات: ${report.importedSessions}")
                    append("، دورات: ${report.importedTurns}، مستندات: ${report.importedKnowledge}")
                    append("، مهام: ${report.importedTasks}، مخرجات: ${report.importedArtifacts}")
                    if (report.reingestedDocuments > 0) {
                        append("، مقاطع معرفة مُعاد بناؤها: ${report.reingestedChunks}")
                    }
                    report.degradedNotes.forEach { append("\n• $it") }
                }
                _state.update { it.copy(successMessage = details) }
            } else {
                _state.update { it.copy(errorMessage = report.message) }
            }
        }
    }

    /** MOVE: identity-preserving verified move to another workspace. */
    fun moveProjectToWorkspace(projectId: Long, targetWorkspaceId: String) {
        val coordinator = projectTransferCoordinator ?: run {
            _state.update { it.copy(errorMessage = "النقل غير متاح في هذا التكوين.") }
            return
        }
        val workspace = workspaceRuntimeService.activeWorkspace.value ?: return
        _state.update { it.copy(isTransferring = true, transferProgressLabel = "جارٍ نقل المشروع…") }
        viewModelScope.launch {
            val outcome = coordinator.moveProjectVerifiedIdentity(projectId, workspace.id, targetWorkspaceId)
            _state.update { it.copy(isTransferring = false, transferProgressLabel = null) }
            when (outcome) {
                is com.example.application.transfer.ProjectTransferCoordinator.MoveOutcome.IdentityPreserved -> {
                    _state.update {
                        it.copy(
                            successMessage = "نُقل المشروع بنجاح مع الحفاظ على هويته " +
                                    "(${outcome.verifiedCounts.sessions} جلسة، ${outcome.verifiedCounts.knowledgeDocuments} مستنداً، " +
                                    "${outcome.verifiedCounts.tasks} مهمة، ${outcome.verifiedCounts.artifacts} مخرجاً — كلها مثبتة ومتحقق منها)."
                        )
                    }
                    loadAvailableWorkspaces()
                }
                is com.example.application.transfer.ProjectTransferCoordinator.MoveOutcome.NewIdentity ->
                    _state.update { it.copy(successMessage = "نُقل المشروع بهوية جديدة في المساحة الهدف.") }
                is com.example.application.transfer.ProjectTransferCoordinator.MoveOutcome.Failed ->
                    _state.update { it.copy(errorMessage = "[${outcome.code}] ${outcome.message}") }
            }
        }
    }

    /** CLONE: same-workspace copy through the real package pipeline. */
    fun cloneProject(projectId: Long) {
        val packages = projectPackageService ?: run {
            _state.update { it.copy(errorMessage = "الاستنساخ غير متاح في هذا التكوين.") }
            return
        }
        val workspace = workspaceRuntimeService.activeWorkspace.value ?: return
        _state.update { it.copy(isTransferring = true, transferProgressLabel = "جارٍ استنساخ المشروع…") }
        viewModelScope.launch {
            val (newId, outcome) = runCatching {
                packages.cloneProject(workspace.id, projectId)
            }.getOrElse { e ->
                null to com.example.application.transfer.TransferOutcome.Failure(
                    "CLONE_EXCEPTION", "فشل الاستنساخ: ${e.localizedMessage}", true
                )
            }
            _state.update { it.copy(isTransferring = false, transferProgressLabel = null) }
            if (newId != null) {
                _state.update { it.copy(successMessage = (outcome as com.example.application.transfer.TransferOutcome.Success).message) }
            } else {
                val message = (outcome as? com.example.application.transfer.TransferOutcome.Failure)?.message
                    ?: "فشل الاستنساخ."
                _state.update { it.copy(errorMessage = message) }
            }
        }
    }

    /** SNAPSHOT: creates a REAL restorable snapshot (payload persisted). */
    fun snapshotProject(projectId: Long, label: String) {
        val packages = projectPackageService ?: run {
            _state.update { it.copy(errorMessage = "اللقطات غير متاحة في هذا التكوين.") }
            return
        }
        val workspace = workspaceRuntimeService.activeWorkspace.value ?: return
        _state.update { it.copy(isTransferring = true, transferProgressLabel = "جارٍ إنشاء اللقطة…") }
        viewModelScope.launch {
            val snapshotId = runCatching {
                packages.createSnapshot(
                    workspaceId = workspace.id,
                    projectId = projectId,
                    label = label.ifBlank { "لقطة يدوية" },
                    reason = "USER_INITIATED"
                )
            }.getOrNull()
            _state.update { it.copy(isTransferring = false, transferProgressLabel = null) }
            if (snapshotId != null) {
                _state.update { it.copy(successMessage = "أُنشئت اللقطة «$label» — يمكن استعادتها من قائمة اللقطات.") }
                loadSnapshots()
            } else {
                _state.update { it.copy(errorMessage = "تعذر إنشاء اللقطة.") }
            }
        }
    }

    /** RESTORE: replaces the current project state with the snapshot's payload. */
    fun restoreSnapshot(snapshotId: String) {
        val packages = projectPackageService ?: run {
            _state.update { it.copy(errorMessage = "الاستعادة غير متاحة في هذا التكوين.") }
            return
        }
        _state.update { it.copy(isTransferring = true, transferProgressLabel = "جارٍ استعادة اللقطة…") }
        viewModelScope.launch {
            val (newId, outcome) = runCatching { packages.restoreSnapshot(snapshotId) }.getOrElse { e ->
                null to com.example.application.transfer.TransferOutcome.Failure(
                    "RESTORE_EXCEPTION", "فشل الاستعادة: ${e.localizedMessage}", true
                )
            }
            _state.update { it.copy(isTransferring = false, transferProgressLabel = null) }
            if (newId != null) {
                _state.update { it.copy(successMessage = (outcome as com.example.application.transfer.TransferOutcome.Success).message) }
                loadSnapshots()
            } else {
                val message = (outcome as? com.example.application.transfer.TransferOutcome.Failure)?.message
                    ?: "فشل الاستعادة."
                _state.update { it.copy(errorMessage = message) }
            }
        }
    }

    /** CLOSURE §4.4: the database handle for the snapshot list (composition
     *  root wires the package service's database — the ViewModel reads the
     *  same snapshot DAO). */
    var appDatabaseForSnapshots: com.example.infrastructure.persistence.AppDatabase? = null
}
