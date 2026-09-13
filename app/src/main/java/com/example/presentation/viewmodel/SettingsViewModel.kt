package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.workspace.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * SettingsViewModel — the SETTINGS feature ViewModel (ADR-6 slice 1, Design
 * Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * GAP-19/21 (Design Closure 2026 — ADR-6 "تفكيك تدريجي متزامن"): the
 * multi-workspace manager leaves the MainViewModel and gets its OWN feature
 * ViewModel, following the TasksViewModel (GAP-11) and FilesViewModel
 * precedents. It owns the four workspace-management mutations:
 *
 *  - switchWorkspace / createWorkspace / updateNetworkPolicy /
 *    updateAutonomyPolicy — every mutation goes through the AUTHORITATIVE
 *    [WorkspaceRuntimeService] (persisted columns + transactional switch,
 *    REPAIR ORDER §20/§GAP-16 semantics), never through UI-local state.
 *
 * Deliberately NOT moved (documented deferral, next ADR-6 slices):
 *  - setNetworkPolicy — the SESSION network policy is an execution-time
 *    input consumed by MainViewModel's decision/execution paths; it moves
 *    with the StudioViewModel/decision decomposition.
 *  - provisionLocalSemanticModel — semanticModelReady is shared read state
 *    (Knowledge/Explorer); it moves with the KnowledgeViewModel slice.
 */
class SettingsViewModel(
    private val workspaceRuntimeService: WorkspaceRuntimeService
) : ViewModel() {

    /** This feature's own transient surface state (errors from mutations). */
    data class SettingsUiState(
        val errorMessage: String? = null,
        val isSwitchingWorkspace: Boolean = false
    )

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** The full workspace registry (live, Room-backed). */
    val allWorkspaces: StateFlow<List<Workspace>> = workspaceRuntimeService.allWorkspaces

    /** The active workspace (live, authoritative). */
    val activeWorkspace: StateFlow<Workspace?> = workspaceRuntimeService.activeWorkspace

    /**
     * Switches the active workspace. GAP-16 semantics live in the service:
     * the switch is transactional and reconciles the pinned execution
     * session; the UI flag here only disables the row mid-switch. The
     * service returns false for a MISSING target row (graceful, no throw) —
     * the VM surfaces that outcome honestly instead of failing silently.
     */
    fun switchWorkspace(workspaceId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSwitchingWorkspace = true) }
            runCatching { workspaceRuntimeService.switchWorkspace(workspaceId) }
                .onSuccess { switched ->
                    if (!switched) {
                        _state.update {
                            it.copy(errorMessage = "تعذر تبديل مساحة العمل: المساحة غير موجودة أو غير متاحة.")
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تبديل مساحة العمل: ${e.localizedMessage}")
                    }
                }
            _state.update { it.copy(isSwitchingWorkspace = false) }
        }
    }

    /** Creates a new workspace (its own sandbox project + budget + memory). */
    fun createWorkspace(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { workspaceRuntimeService.createWorkspace(name = name, description = description) }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر إنشاء مساحة العمل: ${e.localizedMessage}")
                    }
                }
        }
    }

    /**
     * Updates the persisted network policy of the ACTIVE workspace — the
     * egress-governing authority (every governed client consults it).
     */
    fun updateWorkspaceNetworkPolicy(policy: NetworkPolicy) {
        viewModelScope.launch {
            runCatching { workspaceRuntimeService.updateNetworkPolicy(policy) }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تحديث سياسة الشبكة: ${e.localizedMessage}")
                    }
                }
        }
    }

    /**
     * REPAIR ORDER §20 — updates the AUTHORITATIVE autonomy policy
     * (persisted column consumed by the execution pipeline). MainViewModel
     * keeps only a DISPLAY mirror synced from the authoritative flow, so
     * this mutation previously living there is gone for good.
     */
    fun setAutonomyPolicy(policy: AutonomyPolicy) {
        viewModelScope.launch {
            runCatching { workspaceRuntimeService.updateAutonomyPolicy(policy) }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تحديث سياسة الاستقلالية: ${e.localizedMessage}")
                    }
                }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
