package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.operation.OperationPhase
import com.example.application.operation.OperationRegistry
import com.example.application.session.ConversationSessionService
import com.example.domain.core.execution.ScopeSnapshot
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
    private val activeWorkspace: StateFlow<Workspace?>,
    /** CLOSURE P0-1: the app-wide operation registry (nullable = test seam). */
    private val operationRegistry: OperationRegistry? = null,
    /**
     * FIW Stage 1: the UNIFIED export/import authority (canonical TXT/MD/JSON
     * renders + the importable session package). Null (legacy tests) ⇒ the
     * export falls back to the local transcript renderer — honestly degraded,
     * never a silent second implementation in production.
     */
    private val sessionExportService: com.example.application.transfer.SessionExportService? = null
) : ViewModel() {

    /** The sessions feature's own slice of UI state (was 3 fields of UiState). */
    data class SessionsUiState(
        val sessions: List<ConversationSession> = emptyList(),
        val isSessionBrowserOpen: Boolean = false,
        /** FRONTIER: the session-list search query (blank = all sessions). */
        val searchQuery: String = "",
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

    /** FRONTIER SEARCH: the session-list query (blank = show all). */
    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /**
     * FRONTIER RENAME: renames a durable session through the REAL service.
     *
     * CLOSURE P0-1 (scope pinning): the workspace is captured SYNCHRONOUSLY
     * at acceptance — the rename is workspace-authorized under the scope
     * the user acted in, never the live one a mid-flight switch could
     * change (previously the service fell back to its live
     * workspaceIdProvider when no explicit id was passed).
     */
    fun renameSession(sessionId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return // blank rename is an honest no-op
        val pinnedWorkspaceId = activeWorkspace.value?.id
        viewModelScope.launch {
            runCatching {
                conversationSessionService.renameSession(
                    ConversationSessionId(sessionId),
                    clean,
                    workspaceId = pinnedWorkspaceId
                )
            }.onFailure { e ->
                _state.update { it.copy(errorMessage = "تعذّرت إعادة التسمية: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * FRONTIER EXPORT (FIW Stage 1 — UNIFIED): renders the durable session
     * through the ONE export service (canonical representation with
     * attachments/sources/artifacts, redacted, audited). [format] picks the
     * presentation; the package format returns the package JSON.
     * A session that cannot be loaded (deleted mid-tap, workspace mismatch)
     * fires [onUnavailable] honestly — never an empty fabricated transcript.
     */
    fun exportSession(
        sessionId: String,
        format: com.example.application.transfer.SessionExportService.Format,
        onReady: (title: String, fileName: String, mimeType: String, content: String) -> Unit,
        onUnavailable: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val pinnedWorkspaceId = activeWorkspace.value?.id
            val service = sessionExportService
            if (pinnedWorkspaceId == null || service == null) {
                // Honest degraded fallback (legacy tests only in production
                // this never happens — the container wires the service).
                val activeProjectId = activeWorkspace.value?.activeProjectId?.takeIf { it > 0L }
                val loaded = runCatching {
                    conversationSessionService.getSessionWithTurns(
                        ConversationSessionId(sessionId),
                        expectedProjectId = activeProjectId
                    )
                }.getOrNull()
                if (loaded == null || format != com.example.application.transfer.SessionExportService.Format.MARKDOWN) {
                    onUnavailable()
                } else {
                    onReady(
                        loaded.session.title,
                        "${sanitizeFileName(loaded.session.title)}.md",
                        "text/markdown",
                        com.example.presentation.state.SessionTranscriptExporter
                            .toTranscriptMarkdown(loaded.session, loaded.turns)
                    )
                }
                return@launch
            }
            when (format) {
                com.example.application.transfer.SessionExportService.Format.TXT ->
                    service.exportSession(pinnedWorkspaceId, sessionId, format)
                com.example.application.transfer.SessionExportService.Format.MARKDOWN ->
                    service.exportSession(pinnedWorkspaceId, sessionId, format)
                com.example.application.transfer.SessionExportService.Format.JSON ->
                    service.exportSession(pinnedWorkspaceId, sessionId, format)
            }?.let { content ->
                val title = _state.value.sessions
                    .firstOrNull { it.id.value == sessionId }?.title ?: "session"
                val ext = when (format) {
                    com.example.application.transfer.SessionExportService.Format.TXT -> "txt"
                    com.example.application.transfer.SessionExportService.Format.MARKDOWN -> "md"
                    com.example.application.transfer.SessionExportService.Format.JSON -> "json"
                }
                onReady(
                    title,
                    "${sanitizeFileName(title)}.$ext",
                    when (format) {
                        com.example.application.transfer.SessionExportService.Format.TXT -> "text/plain"
                        com.example.application.transfer.SessionExportService.Format.MARKDOWN -> "text/markdown"
                        com.example.application.transfer.SessionExportService.Format.JSON -> "application/json"
                    },
                    content
                )
            } ?: onUnavailable()
        }
    }

    /**
     * FIW Stage 1: builds the IMPORTABLE session package (canonical +
     * artifact payloads, size-capped) — the export side of the round-trip.
     */
    fun exportSessionPackage(
        sessionId: String,
        onReady: (title: String, fileName: String, content: String, skipped: List<String>) -> Unit,
        onUnavailable: (reason: String) -> Unit
    ) {
        viewModelScope.launch {
            val pinnedWorkspaceId = activeWorkspace.value?.id
            val service = sessionExportService
            if (pinnedWorkspaceId == null || service == null) {
                onUnavailable("خدمة تصدير الجلسات غير مهيأة في هذا السياق.")
                return@launch
            }
            when (val outcome = service.buildSessionPackage(pinnedWorkspaceId, sessionId)) {
                is com.example.application.transfer.SessionExportService.PackageBuild.Built -> {
                    val title = _state.value.sessions
                        .firstOrNull { it.id.value == sessionId }?.title ?: "session"
                    onReady(
                        title,
                        "${sanitizeFileName(title)}.aiv0session.json",
                        outcome.packageJson.toString(2),
                        outcome.skippedPayloads
                    )
                }
                is com.example.application.transfer.SessionExportService.PackageBuild.Unavailable ->
                    onUnavailable(outcome.reason)
            }
        }
    }

    /**
     * FIW Stage 1: imports a session package (the file's text, read by the
     * screen's SAF launcher) as a NEW session in the active workspace/project.
     */
    fun importSessionPackage(
        packageText: String,
        onImported: (newSessionTitle: String, turnCount: Int, payloadsRestored: Int) -> Unit,
        onRejected: (reason: String) -> Unit
    ) {
        viewModelScope.launch {
            val pinnedWorkspaceId = activeWorkspace.value?.id
            val projectId = activeWorkspace.value?.activeProjectId?.takeIf { it > 0L }
            val service = sessionExportService
            if (pinnedWorkspaceId == null || service == null) {
                onRejected("خدمة استيراد الجلسات غير مهيأة في هذا السياق.")
                return@launch
            }
            val packageJson = runCatching { org.json.JSONObject(packageText) }.getOrNull()
            if (packageJson == null) {
                onRejected("الملف ليس JSON صالحاً — تعذّر قراءة الحزمة.")
                return@launch
            }
            when (
                val outcome = service.importSessionPackage(pinnedWorkspaceId, projectId, packageJson)
            ) {
                is com.example.application.transfer.SessionExportService.ImportOutcome.Imported -> {
                    val title = _state.value.sessions
                        .firstOrNull { it.id.value == outcome.newSessionId }?.title
                        ?: "الجلسة المستوردة"
                    onImported(title, outcome.turnCount, outcome.payloadsRestored)
                }
                is com.example.application.transfer.SessionExportService.ImportOutcome.Rejected ->
                    onRejected(outcome.reason)
            }
        }
    }

    private fun sanitizeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "session" }.take(60)

    /** FRONTIER EXPORT: honest feedback when a session can no longer load. */
    fun reportExportUnavailable() {
        _state.update {
            it.copy(errorMessage = "تعذّر تصدير الجلسة — ربما حُذفت للتو. حدّث القائمة وحاول مجدداً.")
        }
    }

    /**
     * Deletes a durable session (cascades to its turns).
     *
     * CLOSURE P0-2 (audit §5.4/B2 — UI projection never advances ahead of
     * durable truth): [onDeleted] fires ONLY when the service reports a REAL
     * deletion (rows removed). A thrown failure surfaces as an honest
     * error message and the callback does NOT fire — the studio binding and
     * the list stay untouched, exactly as durable truth left them. A no-op
     * (session already gone / wrong workspace) is silent and callback-free:
     * the Room flow refreshes the list on its own.
     *
     * CLOSURE P0-1 (scope pinning): the deletion is workspace-authorized
     * under the workspace captured at acceptance — a mid-flight workspace
     * switch can no longer retarget the delete at another workspace's
     * live provider resolution (previously NO workspaceId was passed, so
     * the service resolved the CURRENT workspace at execution time).
     */
    fun deleteSession(sessionId: String, onDeleted: (String) -> Unit = {}) {
        val pinnedWorkspaceId = activeWorkspace.value?.id
        val pinnedProjectId = activeWorkspace.value?.activeProjectId?.takeIf { it > 0L }
        val operationId = OperationRegistry.newOperationId("session_delete")
        val snapshot = ScopeSnapshot.capture(
            operationId = operationId,
            workspaceId = pinnedWorkspaceId,
            projectId = pinnedProjectId,
            sessionId = sessionId
        )
        operationRegistry?.register(
            type = "SESSION_DELETE",
            scope = snapshot,
            owner = "SessionsViewModel"
        )
        viewModelScope.launch {
            operationRegistry?.transition(operationId, OperationPhase.RUNNING)
            val deleted = runCatching {
                conversationSessionService.deleteSession(
                    ConversationSessionId(sessionId),
                    workspaceId = pinnedWorkspaceId
                )
            }.onFailure { e ->
                operationRegistry?.fail(operationId, e.localizedMessage)
                _state.update {
                    it.copy(errorMessage = "تعذّر حذف الجلسة: ${e.localizedMessage} — لم يُحذف شيء.")
                }
            }.getOrDefault(false)
            if (deleted) {
                operationRegistry?.transition(
                    operationId,
                    OperationPhase.SUCCEEDED,
                    resultSummary = "session $sessionId deleted under ${pinnedWorkspaceId ?: "<unpinned>"}"
                )
                operationRegistry?.transition(operationId, OperationPhase.FINALIZED)
                onDeleted(sessionId)
            } else if (operationRegistry?.get(operationId)?.phase != OperationPhase.FAILED) {
                // Honest no-op (nothing was deleted): finalize without the
                // success transition and WITHOUT advancing any UI projection.
                operationRegistry?.transition(operationId, OperationPhase.CANCELLED)
                operationRegistry?.transition(operationId, OperationPhase.FINALIZED)
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
