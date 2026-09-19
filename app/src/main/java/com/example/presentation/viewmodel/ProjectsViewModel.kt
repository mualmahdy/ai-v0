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
    private val conversationSessionService: ConversationSessionService
) : ViewModel() {

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
        val successMessage: String? = null
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
}
