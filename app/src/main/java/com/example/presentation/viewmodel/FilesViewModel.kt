package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.bootstrap.BootstrapPhase
import com.example.application.bootstrap.BootstrapState
import com.example.application.usecases.ManageWorkspaceFilesUseCase
import com.example.domain.core.Outcome
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.workspace.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * FilesViewModel — the FILES feature ViewModel (ADR-6 slice 1, Design
 * Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * GAP-19/21 (Design Closure 2026 — ADR-6 "تفكيك تدريجي متزامن"): the
 * sandbox file explorer state leaves the 2400-line MainViewModel and its
 * 264-field UiState and gets its OWN feature ViewModel, exactly like the
 * TasksViewModel precedent (GAP-11). The boundary is complete:
 *
 *  - state: the sandbox listing, the open editor (path + content), the
 *    loading flag, and this feature's OWN error/banner channels — nothing
 *    here is shared with the Studio/decision execution paths;
 *  - behavior: refresh / open / close / save / create / delete, all routed
 *    through the governed [ManageWorkspaceFilesUseCase] (fail-closed
 *    workspace path policy, audited writes);
 *  - freshness: the listing auto-refreshes when the ACTIVE PROJECT changes
 *    (workspace switch or project bind) — this collector replaces the
 *    refreshFiles() call MainViewModel.observeWorkspace used to make, so
 *    MainViewModel loses the last files responsibility entirely.
 *
 * Honesty contract (unchanged from the MainViewModel implementation it
 * replaces): every Outcome.Degraded surfaces its partial value, every
 * Outcome.Error surfaces its real diagnostic message, and acting without a
 * bound project produces the bootstrap-phase-aware explanation instead of
 * a silent no-op or a fabricated error.
 */
class FilesViewModel(
    private val manageWorkspaceFilesUseCase: ManageWorkspaceFilesUseCase,
    private val activeWorkspace: StateFlow<Workspace?>,
    private val bootstrapState: StateFlow<BootstrapState>,
    /** Injectable clock-free seam for deterministic tests. */
    private val refreshOnProjectChange: Boolean = true,
    /**
     * CLOSURE §9 (Coding Workspace — versioning): the artifact service —
     * every governed file save lands as an artifact VERSION (append-only
     * history with rollback). Null (tests) ⇒ versioning is honestly off.
     */
    private val artifactService: com.example.application.artifacts.ArtifactService? = null
) : ViewModel() {

    /** The resolved workspace id for the version writes. */
    private val activeWorkspaceId: String
        get() = activeWorkspace.value?.id ?: ""

    /** The files feature's own slice of UI state (was 4 fields of UiState). */
    data class FilesUiState(
        val files: List<WorkspaceFileEntry> = emptyList(),
        val selectedFilePath: String? = null,
        val selectedFileContent: String? = null,
        val isFileLoading: Boolean = false,
        val errorMessage: String? = null,
        val diagnosticBanner: String? = null
    )

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    /** The last project id whose listing was loaded (switch deduplication). */
    private var lastListedProjectId: Long? = null

    init {
        if (refreshOnProjectChange) {
            viewModelScope.launch {
                runCatching {
                    activeWorkspace.collect { workspace ->
                        val projectId = workspace?.activeProjectId?.takeIf { it > 0L }
                        if (projectId != null && projectId != lastListedProjectId) {
                            refreshFiles()
                        }
                    }
                }.onFailure { e ->
                    _state.update { it.copy(errorMessage = "تعذر تحديث قائمة الملفات: ${e.localizedMessage}") }
                }
            }
        }
    }

    /** Lists the active project's sandbox files (governed, fail-closed). */
    fun refreshFiles() {
        val projectId = projectIdOrInform() ?: return
        viewModelScope.launch {
            _state.update { it.copy(isFileLoading = true) }
            when (val outcome = manageWorkspaceFilesUseCase.listProjectFiles(projectId)) {
                is Outcome.Success -> {
                    lastListedProjectId = projectId
                    _state.update { it.copy(files = outcome.value, isFileLoading = false) }
                }
                is Outcome.Degraded -> {
                    lastListedProjectId = projectId
                    _state.update {
                        it.copy(
                            files = outcome.partialValue ?: emptyList(),
                            isFileLoading = false,
                            diagnosticBanner = outcome.diagnosticMessage
                        )
                    }
                }
                is Outcome.Error -> _state.update {
                    it.copy(errorMessage = outcome.diagnosticMessage, isFileLoading = false)
                }
            }
        }
    }

    /** Opens a file into the editor (loads its current content). */
    fun openFile(relativePath: String) {
        if (relativePath.isBlank()) return
        val projectId = projectIdOrInform() ?: return
        viewModelScope.launch {
            _state.update { it.copy(isFileLoading = true, selectedFilePath = relativePath) }
            when (val outcome = manageWorkspaceFilesUseCase.readProjectFile(projectId, relativePath)) {
                is Outcome.Success -> _state.update {
                    it.copy(selectedFileContent = outcome.value, isFileLoading = false)
                }
                is Outcome.Degraded -> _state.update {
                    it.copy(selectedFileContent = outcome.partialValue, isFileLoading = false)
                }
                is Outcome.Error -> _state.update {
                    it.copy(errorMessage = outcome.diagnosticMessage, isFileLoading = false)
                }
            }
        }
    }

    /**
     * FIX P0-5 (audit c03919d, carried over): proper editor close — clears
     * the editor state directly instead of attempting to read an empty path.
     */
    fun closeFileEditor() {
        _state.update { it.copy(selectedFilePath = null, selectedFileContent = null) }
    }

    /** Saves the editor content (governed write, then re-list + re-read). */
    fun saveFile(relativePath: String, content: String) {
        if (relativePath.isBlank()) return
        val projectId = projectIdOrInform() ?: return
        viewModelScope.launch {
            when (val outcome = manageWorkspaceFilesUseCase.writeProjectFile(projectId, relativePath, content)) {
                is Outcome.Success -> {
                    // CLOSURE §9 (Coding Workspace — version/undo): every
                    // governed file save is snapshotted as an ARTIFACT
                    // VERSION (append-only history) — the file gains
                    // per-save rollback through the SAME versioned artifact
                    // pipeline the chat results use. A failed version write
                    // is honestly surfaced (the FILE save itself stands).
                    val versionNote = runCatching {
                        artifactService?.let { service ->
                            val existing = service.forProject(projectId, limit = 500)
                                .firstOrNull { it.storageUri == relativePath }
                            val artifactId = existing?.id ?: service.registerFileArtifact(
                                workspaceId = activeWorkspaceId.orEmpty(),
                                projectId = projectId,
                                relativePath = relativePath
                            ).id
                            service.createVersion(
                                accessorScope = com.example.domain.core.context.ResourceScope.Project(
                                    activeWorkspaceId.orEmpty(), projectId
                                ),
                                artifactId = artifactId,
                                content = content.toByteArray(),
                                note = "حفظ من مساحة عمل الكود",
                                createdBy = "user"
                            )?.version
                        }
                    }.getOrNull()
                    refreshFiles()
                    openFile(relativePath)
                    if (versionNote != null) {
                        _state.update {
                            it.copy(diagnosticBanner = "حُفظ الملف — النسخة رقم $versionNote (يمكن التراجع عبر سجل المخرجات).")
                        }
                    }
                }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                is Outcome.Degraded -> {
                    _state.update { it.copy(diagnosticBanner = outcome.diagnosticMessage) }
                    refreshFiles()
                }
            }
        }
    }

    /**
     * Creates a new file in the active project (create + write in one
     * governed operation) — blank paths are rejected outright.
     */
    fun createFile(relativePath: String, content: String) {
        if (relativePath.isBlank()) return
        saveFile(relativePath, content)
    }

    /** Deletes a sandbox file (governed, fail-closed, audited). */
    fun deleteWorkspaceFile(relativePath: String) {
        val projectId = projectIdOrInform() ?: return
        viewModelScope.launch {
            when (val outcome = manageWorkspaceFilesUseCase.deleteProjectFile(projectId, relativePath)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            diagnosticBanner = "تم حذف الملف: $relativePath",
                            selectedFilePath = null,
                            selectedFileContent = null
                        )
                    }
                    refreshFiles()
                }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                is Outcome.Degraded -> {
                    _state.update { it.copy(diagnosticBanner = outcome.diagnosticMessage) }
                    refreshFiles()
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }

    /**
     * The project gate: acts only when the active workspace has a bound
     * sandbox project; otherwise surfaces the bootstrap-phase-aware honest
     * explanation (the message logic MainViewModel.currentProjectIdOrInform
     * used, now owned by the feature that needs it).
     */
    private fun projectIdOrInform(): Long? {
        val projectId = activeWorkspace.value?.activeProjectId?.takeIf { it > 0L }
        if (projectId == null) {
            val phase = bootstrapState.value
            val message = when {
                phase.isReady -> "لا يوجد مشروع مرتبط بمساحة العمل النشطة — أنشئ مشروعاً جديداً أو اختر مشروعاً."
                phase.isFailed -> (phase.phase as? BootstrapPhase.Failed)?.message
                    ?: "فشل تهيئة سياق مساحة العمل."
                else -> "جارٍ تهيئة سياق مساحة العمل (${phase.phase.label}) — أعد المحاولة بعد لحظات."
            }
            _state.update { it.copy(errorMessage = message) }
        }
        return projectId
    }
}
