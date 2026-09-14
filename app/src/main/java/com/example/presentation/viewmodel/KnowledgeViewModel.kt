package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.rag.RagPipelineService
import com.example.application.usecases.ManageMemoryUseCase
import com.example.domain.core.Outcome
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryProvenance
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.rag.AssembledRagContext
import com.example.domain.core.rag.KnowledgeDocument
import com.example.domain.core.rag.KnowledgePersistenceState
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.workspace.Workspace
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * KnowledgeViewModel — the KNOWLEDGE feature ViewModel (ADR-6 slice 3,
 * Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The whole knowledge/memory/RAG surface leaves the MainViewModel and its
 * shared UiState (the same freeze rule as Files/Settings/Studio/Sessions
 * before it). The boundary is complete:
 *
 *  - STATE: the knowledge-base listing, the assembled RAG context, the
 *    ingest dialog fields, the semantic-engine provisioning flags (the
 *    previously SHARED `semanticModelReady` now lives HERE — its owner),
 *    the long-term memory browser (query, results, all-memories, add form)
 *    and this feature's OWN error/banner channels.
 *
 *  - BEHAVIOR (moved verbatim from MainViewModel): document ingest with the
 *    P0-8 title sanitization + honest persistence-failure surface (P1-14),
 *    hybrid retrieval, durable deletion with honest outcomes, local ONNX
 *    semantic-model provisioning (idempotent, honest failure — GAP-07), the
 *    readiness mirror (GAP-07/25 truth chain), and the memory operations
 *    (search/add/refresh through [ManageMemoryUseCase]).
 *
 *  - FRESHNESS: the RAG index reloads from persistence whenever the ACTIVE
 *    WORKSPACE changes — this collector replaces the
 *    `ragPipelineService.loadFromPersistence()` call MainViewModel's
 *    observeWorkspace used to make, so MainViewModel loses the last
 *    knowledge responsibility entirely. The initial memory listing replaces
 *    the refreshMemories() call in MainViewModel.loadInitialData.
 *
 * Honesty contract (unchanged from the MainViewModel implementation it
 * replaces): every Outcome surfaces its real diagnostic, a FAILED knowledge
 * write names what survived where (live index vs durable store), and the
 * semantic engine NEVER fabricates a "semantic" mode — unprovisioned means
 * lexical, degraded means degraded.
 */
class KnowledgeViewModel(
    private val ragPipelineService: RagPipelineService,
    private val manageMemoryUseCase: ManageMemoryUseCase,
    /** The active workspace stream — knowledge is workspace-scoped (§15). */
    private val activeWorkspace: StateFlow<Workspace?>,
    /** Injectable seam for deterministic tests (disable the auto-reload). */
    private val reloadOnWorkspaceChange: Boolean = true
) : ViewModel() {

    /**
     * The knowledge feature's own slice of UI state (was 11 fields of the
     * shared UiState + the two shared channels).
     */
    data class KnowledgeUiState(
        // --- knowledge base (RAG) ---
        val knowledgeDocuments: List<KnowledgeDocument> = emptyList(),
        val assembledRagContext: AssembledRagContext? = null,
        val newDocTitle: String = "",
        val newDocContent: String = "",
        // --- semantic engine (owner of the previously-shared flags) ---
        val semanticModelReady: Boolean = false,
        val isProvisioningSemanticModel: Boolean = false,
        // --- long-term memory ---
        val memoryQuery: String = "",
        val retrievedMemories: List<ScoredMemoryRecord> = emptyList(),
        val allMemories: List<MemoryEntry> = emptyList(),
        val isSearchingMemory: Boolean = false,
        val newMemoryContent: String = "",
        // --- feature-owned channels ---
        val errorMessage: String? = null,
        val diagnosticBanner: String? = null
    )

    private val _state = MutableStateFlow(KnowledgeUiState())
    val state: StateFlow<KnowledgeUiState> = _state.asStateFlow()

    init {
        // The durable listing (live index) — single source of truth.
        viewModelScope.launch {
            ragPipelineService.documents.collect { docs ->
                _state.update { it.copy(knowledgeDocuments = docs) }
            }
        }
        // Knowledge is workspace-scoped: a workspace switch reloads the
        // in-memory index from the new workspace's persisted knowledge.
        if (reloadOnWorkspaceChange) {
            viewModelScope.launch {
                runCatching {
                    activeWorkspace.collect { workspace ->
                        if (workspace != null) {
                            ragPipelineService.loadFromPersistence()
                        }
                    }
                }.onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر تحميل قاعدة معرفة مساحة العمل: ${e.localizedMessage}")
                    }
                }
            }
        }
        // The initial memory listing (was MainViewModel.loadInitialData's
        // refreshMemories() — now owned by the feature that renders it).
        refreshMemories()
    }

    // ==================================================================
    // Knowledge base (RAG)
    // ==================================================================

    fun updateDocTitle(title: String) {
        _state.update { it.copy(newDocTitle = title) }
    }

    fun updateDocContent(content: String) {
        _state.update { it.copy(newDocContent = content) }
    }

    fun ingestNewDocument() {
        val title = _state.value.newDocTitle.trim()
        val content = _state.value.newDocContent.trim()
        if (title.isEmpty() || content.isEmpty()) return

        viewModelScope.launch {
            // FIX P0-8 (audit c03919d): sanitize the title so it cannot inject
            // path separators into the workspace:// source URI.
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val ingested = ragPipelineService.ingestDocument(safeTitle, content, "workspace://docs/$safeTitle.md")
            // GAP-CLOSURE P1-14: surface honest persistence failure to the user.
            _state.update {
                when (ingested.persistenceState) {
                    KnowledgePersistenceState.FAILED -> it.copy(
                        newDocTitle = "",
                        newDocContent = "",
                        diagnosticBanner = ingested.persistenceDiagnostic
                            ?: "تعذر حفظ المستند في قاعدة البيانات."
                    )
                    else -> it.copy(newDocTitle = "", newDocContent = "")
                }
            }
        }
    }

    fun queryKnowledgeRag(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            val assembled = ragPipelineService.retrieveRelevantContext(query)
            _state.update { it.copy(assembledRagContext = assembled) }
        }
    }

    /**
     * Deletes a knowledge document from BOTH the in-memory index and the
     * durable Room store (honest outcome surfaced to the user).
     */
    fun deleteKnowledgeDocument(documentId: String) {
        viewModelScope.launch {
            when (val outcome = ragPipelineService.deleteDocument(documentId)) {
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.failure) }
                else -> _state.update {
                    it.copy(diagnosticBanner = "تم حذف المستند من قاعدة المعرفة.")
                }
            }
        }
    }

    // ==================================================================
    // Semantic engine (owner of the previously-shared readiness state)
    // ==================================================================

    /**
     * Local-first semantic RAG provisioning (audit 2026 fix): downloads the
     * on-device sentence-transformer ONCE (~23MB int8). Idempotent — no-ops
     * when already provisioned, and fails honestly (offline, network error)
     * without ever fabricating a "semantic" mode.
     */
    fun provisionLocalSemanticModel() {
        viewModelScope.launch {
            _state.update { it.copy(isProvisioningSemanticModel = true) }
            runCatching {
                when (val r = ragPipelineService.provisionSemanticModel()) {
                    is Outcome.Success -> _state.update {
                        it.copy(
                            semanticModelReady = true,
                            isProvisioningSemanticModel = false,
                            // GAP-07: honest — new ingest + queries are semantic;
                            // pre-provisioning chunks stay lexical (boundary).
                            diagnosticBanner = "النموذج الدلالي المحلي جاهز — الاسترجاع والدمج الجديد دلالي على الجهاز؛ المتجهات القديمة تبقى معجمية (حد التوافق)."
                        )
                    }
                    is Outcome.Error -> _state.update {
                        it.copy(
                            isProvisioningSemanticModel = false,
                            diagnosticBanner = "تعذر تجهيز النموذج الدلالي المحلي: ${r.failure}"
                        )
                    }
                    else -> _state.update { it.copy(isProvisioningSemanticModel = false) }
                }
            }.onFailure {
                _state.update { it.copy(isProvisioningSemanticModel = false) }
            }
        }
    }

    /** Refreshes the honest on-device semantic-model readiness flag. */
    fun refreshSemanticModelStatus() {
        _state.update { it.copy(semanticModelReady = ragPipelineService.isLocalSemanticModelReady) }
    }

    // ==================================================================
    // Long-term memory
    // ==================================================================

    fun updateMemoryQuery(q: String) {
        _state.update { it.copy(memoryQuery = q) }
    }

    fun searchMemory() {
        val q = _state.value.memoryQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isSearchingMemory = true) }
            when (val outcome = manageMemoryUseCase.retrieveContext(q)) {
                is Outcome.Success -> _state.update { it.copy(retrievedMemories = outcome.value, isSearchingMemory = false) }
                is Outcome.Degraded -> _state.update { it.copy(retrievedMemories = outcome.partialValue ?: emptyList(), isSearchingMemory = false) }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage, isSearchingMemory = false) }
            }
        }
    }

    fun updateNewMemoryContent(text: String) {
        _state.update { it.copy(newMemoryContent = text) }
    }

    fun addNewMemory() {
        val content = _state.value.newMemoryContent.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val entry = MemoryEntry(
                id = UUID.randomUUID().toString(),
                content = content,
                type = MemoryType.FACTUAL_INSIGHT,
                confidence = 1.0f,
                provenance = MemoryProvenance(sourceSessionId = "MANUAL_ENTRY", createdAtTimestampMs = System.currentTimeMillis()),
                isActive = true
            )
            when (val outcome = manageMemoryUseCase.recordInsight(entry)) {
                is Outcome.Success -> {
                    _state.update { it.copy(newMemoryContent = "") }
                    refreshMemories()
                }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> refreshMemories()
            }
        }
    }

    fun refreshMemories() {
        viewModelScope.launch {
            when (val outcome = manageMemoryUseCase.getActiveMemories()) {
                is Outcome.Success -> _state.update { it.copy(allMemories = outcome.value) }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    // ==================================================================
    // Feature-owned channels
    // ==================================================================

    fun clearErrorMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the feature's transient diagnostic banner. */
    fun dismissDiagnosticBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }
}
