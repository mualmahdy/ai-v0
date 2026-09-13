package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.session.ConversationSessionService
import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.workspace.Workspace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * SessionsViewModel — the DURABLE SESSIONS registry ViewModel (ADR-6 slice
 * 2, Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * GAP-19/21 (ADR-6 "تفكيك تدريجي متزامن"): the durable-session BROWSER
 * surface — the workspace/project-scoped session list and the browser
 * sheet flag — leaves the MainViewModel and gets its OWN feature
 * ViewModel, following the TasksViewModel / FilesViewModel /
 * SettingsViewModel precedents.
 *
 * Boundary (documented honestly, this slice):
 *  - OWNED here: the live session list (GAP-14 project scoping — the
 *    active project's private sessions; a project-less workspace shows
 *    the workspace's shared sessions; sibling projects are invisible),
 *    the browser open/close flag, and deletion.
 *  - OWNED by [StudioViewModel]: the conversation's ACTIVE session
 *    binding + transcript (the execution path ensures/loads sessions —
 *    inseparable from the conversation runtime). The screen wires the
 *    two features together: deletion notifies the studio via callback.
 *
 * FIX (collector leak + last-writer race, inherited from
 * observeWorkspaceScopedAssets): the old observer launched a NEW infinite
 * collector per workspace emission WITHOUT cancelling the previous one —
    * every workspace/project switch stacked another live Room collector,
    * and whichever emitted LAST won the UiState write (a stale-workspace
    * race). The observation here is a single flatMapLatest chain: a
    * workspace/project change CANCELS the previous query collector
    * before the new one starts. The list is now always exactly the
    * active scope's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val conversationSessionService: ConversationSessionService,
    private val activeWorkspace: StateFlow<Workspace?>
) : ViewModel() {

    /** The sessions feature's own slice of UI state (was 3 fields of UiState). */
    data class SessionsUiState(
        val sessions: List<ConversationSession> = emptyList(),
        val isSessionBrowserOpen: Boolean = false,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        // GAP-14 + workspace continuity: the browser list follows the ACTIVE
        // workspace AND its active project — switching either re-scopes the
        // list (previous collector cancelled by flatMapLatest semantics).
        viewModelScope.launch {
            runCatching {
                activeWorkspace.collectLatest { workspace ->
                    val wsId = workspace?.id ?: return@collectLatest
                    conversationSessionService.observeSessionsForProject(
                        wsId,
                        workspace.activeProjectId.takeIf { it > 0L }
                    ).collect { sessions ->
                        _state.update { it.copy(sessions = sessions) }
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(errorMessage = "تعذر تحديث قائمة الجلسات: ${e.localizedMessage}") }
            }
        }
    }

    /** Opens/closes the durable session browser sheet. */
    fun setSessionBrowserOpen(open: Boolean) {
        _state.update { it.copy(isSessionBrowserOpen = open) }
    }

    /**
     * Deletes a durable session (cascades to its turns). [onDeleted] fires
     * AFTER the service call settles (success or failure) so the caller can
     * run the studio-side effect — clearing the active conversation binding
     * when the deleted session was the active one — with deterministic
     * ordering (the deletion result is known before the callback runs).
     */
    fun deleteSession(sessionId: String, onDeleted: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                conversationSessionService.deleteSession(ConversationSessionId(sessionId))
            }
            onDeleted(sessionId)
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
