package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.agent.CanonicalAgentCatalog
import com.example.application.attachment.ChatAttachmentCoordinator
import com.example.application.execution.ExecutionService
import com.example.application.extension.ExtensionManager
import com.example.application.radar.CapabilityRadarService
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.search.SearchIntelligenceService
import com.example.application.session.ConversationSessionService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.session.TurnAttachment
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.execution.ExecutionScope
import com.example.infrastructure.network.NetworkMonitor
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ChatCapabilityFacts
import com.example.presentation.state.ChatCapabilityItem
import com.example.presentation.state.ChatCapabilityPolicy
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ChatSourceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ============================================================================
 * ChatCapabilitiesViewModel — the capability layer of the CHAT feature
 * (CHAT CAPABILITIES Task 2 §3–§12)
 * ============================================================================
 *
 * "Conversation first + Capability on demand + Progressive disclosure": this
 * ViewModel owns the state the composer's "+" entry point needs — the honest
 * availability catalog (§4, resolved by the PURE [ChatCapabilityPolicy] over
 * facts from the REAL sources), the attachment drafts (§5, imported through
 * [ChatAttachmentCoordinator] = the real SAF→sandbox→artifact chain), and
 * the capability invocations (§9–§12: tools/skills/MCP through the governed
 * [ExecutionService] boundary, search through the real
 * [SearchIntelligenceService], knowledge retrieval through the real
 * [RagPipelineService]).
 *
 * OWNERSHIP (ADR-6): the conversation TIMELINE stays single-owner
 * (StudioViewModel) — capability results are delivered to it through the
 * caller-supplied callback (the same seam SessionsViewModel uses for
 * deletion effects). Nothing here touches MainViewModel.
 */
class ChatCapabilitiesViewModel(
    private val extensionManager: ExtensionManager,
    private val componentRegistry: ComponentRegistry,
    private val searchIntelligenceService: SearchIntelligenceService,
    private val ragPipelineService: RagPipelineService,
    private val attachmentCoordinator: ChatAttachmentCoordinator,
    private val workspaceRuntimeService: WorkspaceRuntimeService,
    private val executionService: ExecutionService,
    private val capabilityRadarService: CapabilityRadarService?,
    private val networkMonitorProvider: NetworkMonitor?
) : ViewModel() {

    data class ChatCapabilitiesUiState(
        /** The categorized, availability-resolved capability menu rows (§3/§4). */
        val capabilities: List<ChatCapabilityItem> = emptyList(),
        /** Live skill manifests (the extension registry — §8). */
        val skills: List<com.example.domain.core.extension.SkillManifest> = emptyList(),
        /** Live MCP server descriptors with their real health (§10). */
        val mcpServers: List<com.example.domain.core.extension.McpServerDescriptor> = emptyList(),
        /** Indexed knowledge corpus size (the honest RAG availability fact). */
        val knowledgeDocumentCount: Int = 0,
        /** Tool declarations registered in the runtime registry (§9). */
        val tools: List<ToolDeclaration> = emptyList(),
        /** Attachment drafts staged in the composer (chips before send — §5). */
        val attachmentDrafts: List<TurnAttachment> = emptyList(),
        /** One honest import-in-progress marker (progress state — §5). */
        val isImportingAttachment: Boolean = false,
        val attachmentError: String? = null,
        /** One honest capability-in-progress marker. */
        val isInvoking: Boolean = false,
        val isDiscoveringMcp: Boolean = false,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(ChatCapabilitiesUiState())
    val state: StateFlow<ChatCapabilitiesUiState> = _state.asStateFlow()

    init {
        // Live mirrors of the REAL registries (no copy of definitions — §8:
        // the manifests are read from their owner, never duplicated here).
        viewModelScope.launch {
            extensionManager.skills.collect { skills ->
                _state.update { it.copy(skills = skills) }
                refreshCapabilities()
            }
        }
        viewModelScope.launch {
            extensionManager.mcpServers.collect { servers ->
                _state.update { it.copy(mcpServers = servers) }
                refreshCapabilities()
            }
        }
        viewModelScope.launch {
            ragPipelineService.documents.collect { documents ->
                knowledgeDocumentCount = documents.size
                _state.update { it.copy(knowledgeDocumentCount = documents.size) }
                refreshCapabilities()
            }
        }
        // --------------------------------------------------------------
        // FUNCTIONAL CLOSURE (§21): STATE FRESHNESS — the catalog re-resolves
        // whenever the SCOPE (workspace AND its active project) or the
        // NETWORK changes. Observable sources only (the workspace runtime's
        // own StateFlow + the real monitor's StateFlow) — NO polling.
        // --------------------------------------------------------------
        viewModelScope.launch {
            runCatching {
                workspaceRuntimeService.activeWorkspace.collect { workspace ->
                    if (workspace != null) {
                        val scope = workspace.id to workspace.activeProjectId.takeIf { it > 0L }
                        val previous = lastSeenScope
                        lastSeenScope = scope
                        if (previous != null && previous != scope) {
                            // The composer drafts belong to the PREVIOUS
                            // project's sandbox — dropping them (with their
                            // imported files cleaned up) prevents sending
                            // stale-scope references from the new scope.
                            dropAttachmentDraftsForScopeChange()
                            refreshCapabilities()
                        }
                    }
                }
            }
        }
        networkMonitorProvider?.let { monitor ->
            viewModelScope.launch {
                runCatching {
                    monitor.isNetworkAvailable.collect { _ ->
                        refreshCapabilities()
                    }
                }
            }
        }
        refreshCapabilities()
    }

    /** Indexed knowledge corpus size (the RAG availability fact). */
    private var knowledgeDocumentCount: Int = 0

    /** FUNCTIONAL CLOSURE (§21): the last scope this catalog resolved for. */
    private var lastSeenScope: Pair<String, Long?>? = null

    /**
     * CHAT FINAL CLOSURE (§4/§5 scope snapshot): the (workspace, project)
     * captured SYNCHRONOUSLY at invocation acceptance. One immutable capture
     * per invocation — the long-running call may never re-read the live
     * active scope (adapters fall back to it only when no ExecutionScope is
     * present, which the pinned [ExecutionScope] now guarantees here).
     */
    private data class InvocationScopeSnapshot(
        val workspaceId: String?,
        val projectId: Long?
    )

    /** Captured at acceptance — see [InvocationScopeSnapshot]. */
    private fun captureInvocationScope(): InvocationScopeSnapshot = InvocationScopeSnapshot(
        workspaceId = runCatching { workspaceRuntimeService.activeWorkspaceIdOrNull() }.getOrNull(),
        projectId = runCatching { workspaceRuntimeService.activeProjectIdOrNull() }.getOrNull()
    )

    /**
     * UI POLISH (§4 — Creation group honesty): the LLM connection fact —
     * TRUE when an operational LLM resource exists (lifecycle ENABLED or
     * ACTIVE, health not UNAVAILABLE — the same operational truth the
     * workspace's connect banner uses). The providers feature owns the
     * resources; the SCREEN pushes the fact here (value+lambda seam, the
     * established sessionNetworkPolicy pattern — no new dependency).
     */
    private var hasActiveLlm: Boolean = false

    /** The screen pushes the LLM availability fact (see [hasActiveLlm]). */
    fun setLlmConnected(connected: Boolean) {
        if (hasActiveLlm == connected) return
        hasActiveLlm = connected
        refreshCapabilities()
    }

    // ------------------------------------------------------------------
    // §3/§4 — the capability catalog
    // ------------------------------------------------------------------

    /**
     * FUNCTIONAL CLOSURE (§21): cleans the composer drafts when the scope
     * changes under them. §13 ownership contract: a draft the user will NOT
     * send (its sandbox belongs to the previous project) is deleted FOR
     * REAL — the imported file AND its artifact row go away together, so no
     * orphaned storage is left behind. Failures surface on the honest
     * attachmentError channel.
     */
    private fun dropAttachmentDraftsForScopeChange() {
        val drafts = _state.value.attachmentDrafts
        if (drafts.isEmpty()) return
        viewModelScope.launch {
            var failure: String? = null
            drafts.forEach { draft ->
                runCatching { attachmentCoordinator.deleteImportedAttachment(draft) }
                    .onFailure { e -> failure = e.message }
            }
            _state.update {
                it.copy(
                    attachmentDrafts = emptyList(),
                    attachmentError = failure ?: it.attachmentError
                )
            }
        }
    }

    /**
     * FUNCTIONAL CLOSURE (§20): the honest availability facts — counts that
     * reflect OPERATIONAL truth, not raw existence:
     *  - MCP: a server counts as connectable when it is ENABLED; a HEALTHY
     *    one makes the entry plainly AVAILABLE (the browser's ping is the
     *    recovery path for enabled-but-unhealthy servers, shown as a hint);
     *  - Skills: ENABLED manifests that are ACTUALLY REGISTERED as runnable
     *    tool ports (a manifest whose tool never registered is not
     *    invocable from the governed path, so it does not count);
     *  - Tools: the runtime registry IS the operational truth (admission /
     *    consent are per-invocation policy, not availability).
     */
    fun refreshCapabilities() {
        val workspaceId = runCatching {
            workspaceRuntimeService.activeWorkspaceIdOrNull()
        }.getOrNull()
        viewModelScope.launch {
            val radarChecks = if (capabilityRadarService != null && workspaceId != null) {
                runCatching {
                    mapOf(
                        CapabilityType.VISION to capabilityRadarService.checkCapability(
                            CapabilityType.VISION, workspaceId
                        ),
                        CapabilityType.SEARCH to capabilityRadarService.checkCapability(
                            CapabilityType.SEARCH, workspaceId
                        )
                    )
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            _state.update { current ->
                // §20: operational MCP truth — enabled servers, split by
                // healthy vs needs-handshake.
                val enabledMcpServers = current.mcpServers.filter { it.isEnabled }
                val healthyMcpCount = enabledMcpServers.count {
                    it.health == com.example.domain.core.provider.HealthStatus.HEALTHY
                }
                // §20: operational skills — ENABLED AND registered as a
                // runnable tool port in the runtime registry.
                val registeredToolNames = componentRegistry.listTools()
                    .map { it.declaration.name }.toSet()
                val operationalSkillCount = current.skills.count {
                    it.state == com.example.domain.core.extension.SkillState.ENABLED &&
                        it.id in registeredToolNames
                }
                val facts = ChatCapabilityFacts(
                    activeProjectId = runCatching {
                        workspaceRuntimeService.activeProjectIdOrNull()
                    }.getOrNull(),
                    folderAttachSupported = true,
                    radarChecks = radarChecks,
                    knowledgeDocumentCount = knowledgeDocumentCount,
                    semanticKnowledgeReady = ragPipelineService.isLocalSemanticModelReady,
                    searchProviderWired = true, // production wires the local-fallback adapter
                    isNetworkAvailable = networkMonitorProvider?.isNetworkAvailable?.value ?: false,
                    hasActiveLlm = hasActiveLlm,
                    enabledSkillCount = operationalSkillCount,
                    registeredToolCount = componentRegistry.listTools().size,
                    mcpServerCount = enabledMcpServers.size,
                    healthyMcpServerCount = healthyMcpCount
                )
                current.copy(
                    capabilities = ChatCapabilityPolicy.resolve(facts),
                    tools = componentRegistry.listTools().map { it.declaration },
                    knowledgeDocumentCount = knowledgeDocumentCount
                )
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null, attachmentError = null) }
    }

    // ------------------------------------------------------------------
    // §5 — attachment drafts (SAF pick → import → chip)
    // ------------------------------------------------------------------

    /**
     * Imports the SAF-picked files into the active project sandbox and
     * stages them as composer drafts (chips) — the real
     * staging/atomic-promotion/limits/audit transfer path. A failed import
     * surfaces its honest reason on the chip row; partial success keeps the
     * files that DID import.
     */
    fun pickFiles(uris: List<String>, mimeTypes: List<String?> = emptyList()) {
        if (uris.isEmpty()) return
        _state.update { it.copy(isImportingAttachment = true, attachmentError = null) }
        viewModelScope.launch {
            var failure: String? = null
            val imported = mutableListOf<TurnAttachment>()
            uris.forEachIndexed { index, uri ->
                try {
                    imported += attachmentCoordinator.importFileAttachment(
                        uri = uri,
                        reportedMimeType = mimeTypes.getOrNull(index)
                    )
                } catch (e: ChatAttachmentCoordinator.AttachmentImportException) {
                    failure = e.message
                } catch (e: Exception) {
                    failure = "فشل استيراد المرفق: ${e.localizedMessage}"
                }
            }
            _state.update {
                it.copy(
                    attachmentDrafts = it.attachmentDrafts + imported,
                    isImportingAttachment = false,
                    attachmentError = failure
                )
            }
            refreshCapabilities()
        }
    }

    /** Imports a SAF document TREE (folder) as one draft — §5. */
    fun pickFolder(treeUri: String) {
        if (treeUri.isBlank()) return
        _state.update { it.copy(isImportingAttachment = true, attachmentError = null) }
        viewModelScope.launch {
            try {
                val attachment = attachmentCoordinator.importFolderAttachment(treeUri)
                _state.update {
                    it.copy(
                        attachmentDrafts = it.attachmentDrafts + attachment,
                        isImportingAttachment = false
                    )
                }
            } catch (e: ChatAttachmentCoordinator.AttachmentImportException) {
                _state.update {
                    it.copy(isImportingAttachment = false, attachmentError = e.message)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isImportingAttachment = false,
                        attachmentError = "فشل استيراد المجلد: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * FUNCTIONAL CLOSURE (§13): removes one draft BEFORE send — and deletes
     * its REAL footprint (the imported sandbox copy + the artifact row) so
     * no orphaned storage is left behind. A cleanup failure KEEPS the draft
     * visible and surfaces the honest error (the user can retry the removal)
     * — state never silently diverges from storage.
     */
    fun removeAttachment(id: String) {
        val draft = _state.value.attachmentDrafts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { attachmentCoordinator.deleteImportedAttachment(draft) }
                .onSuccess { removed ->
                    if (removed || draft.artifactId == null) {
                        _state.update { current ->
                            current.copy(
                                attachmentDrafts = current.attachmentDrafts
                                    .filterNot { it.id == id },
                                attachmentError = null
                            )
                        }
                    } else {
                        // No artifact row AND no file removed — keep the draft
                        // (honest) and say why.
                        _state.update {
                            it.copy(
                                attachmentError = "تعذر حذف المرفق «${draft.name}» من التخزين — أعد المحاولة."
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            attachmentError = e.message
                                ?: "تعذر حذف المرفق «${draft.name}» — أعد المحاولة."
                        )
                    }
                }
        }
    }

    /** The screen clears the drafts once the send consumed them. */
    fun clearAttachmentDrafts() {
        _state.update { it.copy(attachmentDrafts = emptyList(), attachmentError = null) }
    }

    /**
     * FUNCTIONAL CLOSURE (§14): hands the drafts BACK after an aborted send
     * (the ViewModel refused to execute — e.g. the grounding build failed).
     * The user's picks re-appear as chips; nothing is silently consumed.
     */
    fun restoreAttachmentDrafts(drafts: List<TurnAttachment>) {
        if (drafts.isEmpty()) return
        _state.update { current ->
            val existingIds = current.attachmentDrafts.map { it.id }.toSet()
            current.copy(
                attachmentDrafts = current.attachmentDrafts + drafts.filter { it.id !in existingIds }
            )
        }
    }

    // ------------------------------------------------------------------
    // §11 — Search intelligence (existing pipeline, readable result)
    // ------------------------------------------------------------------

    /**
     * Runs the REAL search-intelligence pipeline (intent → decompose →
     * fan-out → dedup → rank → citations) and hands the caller a structured
     * conversation block: search state, sources, and a readable summary —
     * never raw internal orchestration.
     */
    fun invokeSearch(
        query: String,
        agent: com.example.domain.core.agent.AgentDefinition?,
        sessionId: String? = null,
        onResult: (ChatEntry.CapabilityResult) -> Unit
    ) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || _state.value.isInvoking) return
        _state.update { it.copy(isInvoking = true, errorMessage = null) }
        // CHAT FINAL CLOSURE (§5 scope snapshot): the invocation's scope is
        // captured AT ACCEPTANCE and pinned for the whole pipeline — the
        // MultiSourceSearchAdapter's local-workspace fallback resolves the
        // pinned project (never the live one at fan-out time).
        val scope = captureInvocationScope()
        viewModelScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(
                    ExecutionScope(
                        executionId = "capsearch_${UUID.randomUUID()}",
                        workspaceId = scope.workspaceId ?: "unattributed",
                        projectId = scope.projectId,
                        sessionId = sessionId
                    )
                ) {
                    searchIntelligenceService.searchIntelligent(trimmed)
                }
            }
            _state.update { it.copy(isInvoking = false) }
            val entry = result.fold(
                onSuccess = { intelligence ->
                    val sources = intelligence.citations.map { chain ->
                        ChatSourceRef(
                            title = chain.itemTitle,
                            url = chain.itemUrl,
                            providerId = chain.providerId,
                            confidenceScore = chain.confidenceScore
                        )
                    }
                    // CHAT FINAL CLOSURE (§14 search states): SUCCESS_EMPTY
                    // (no results, all sources answered) is NOT degraded —
                    // "لا توجد نتائج" is a different truth from "البحث عمل
                    // جزئياً" and from "فشل البحث".
                    ChatEntry.CapabilityResult(
                        id = "cap_${UUID.randomUUID()}",
                        kind = CapabilityKind.SEARCH,
                        title = "بحث ذكي: $trimmed",
                        summary = when {
                            intelligence.rankedItems.isEmpty() && !intelligence.isPartial ->
                                "لا نتائج مطابقة للاستعلام."
                            intelligence.rankedItems.isEmpty() ->
                                "لا نتائج — مع تعذر بعض المصادر" +
                                        " (${intelligence.degradationReason ?: "بعض المصادر لم يستجب"})."
                            intelligence.isPartial ->
                                "اكتمل بنمط تراجعي (${intelligence.rankedItems.size} نتيجة) — " +
                                        "${intelligence.degradationReason ?: "بعض المصادر لم يستجب"}."
                            else ->
                                "تم العثور على ${intelligence.rankedItems.size} نتيجة مرتبة."
                        },
                        detail = intelligence.rankedItems.take(5)
                            .joinToString("\n\n") { ranked ->
                                val score = "%.2f".format(ranked.finalScore)
                                val snippet = ranked.item.snippet.take(220)
                                "- **${ranked.item.title}** (ثقة $score)\n  $snippet"
                            }.ifBlank { null },
                        sources = sources,
                        isDegraded = intelligence.isPartial,
                        degradedMessage = intelligence.degradationReason?.takeIf { intelligence.isPartial },
                        timestampMs = System.currentTimeMillis()
                    )
                },
                onFailure = { e ->
                    ChatEntry.CapabilityResult(
                        id = "cap_${UUID.randomUUID()}",
                        kind = CapabilityKind.SEARCH,
                        title = "بحث ذكي: $trimmed",
                        summary = "فشل البحث: ${e.localizedMessage ?: e::class.simpleName}",
                        isSuccessful = false,
                        timestampMs = System.currentTimeMillis()
                    )
                }
            )
            onResult(entry)
        }
    }

    // ------------------------------------------------------------------
    // §12 — Knowledge / RAG retrieval (real pipeline, honest state)
    // ------------------------------------------------------------------

    /**
     * Runs the REAL RAG retrieval (hybrid semantic/lexical when provisioned,
     * honest lexical fallback otherwise) and hands the caller a structured
     * block: retrieval state, the retrieved chunks and their scores — never
     * embeddings internals.
     */
    fun invokeKnowledgeRetrieval(
        query: String,
        sessionId: String? = null,
        onResult: (ChatEntry.CapabilityResult) -> Unit
    ) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || _state.value.isInvoking) return
        _state.update { it.copy(isInvoking = true, errorMessage = null) }
        // CHAT FINAL CLOSURE (§5 scope snapshot): pinned at acceptance — the
        // RAG pipeline's retrieval scope (workspace/project filter and stale
        // working-set repair) resolves the PINNED scope, never the live one.
        val scope = captureInvocationScope()
        viewModelScope.launch {
            val outcome = runCatching {
                kotlinx.coroutines.withContext(
                    ExecutionScope(
                        executionId = "caprag_${UUID.randomUUID()}",
                        workspaceId = scope.workspaceId ?: "unattributed",
                        projectId = scope.projectId,
                        sessionId = sessionId
                    )
                ) {
                    ragPipelineService.retrieveRelevantContext(trimmed)
                }
            }
            _state.update { it.copy(isInvoking = false) }
            val entry = outcome.fold(
                onSuccess = { context ->
                    if (context.retrievedChunks.isEmpty()) {
                        ChatEntry.CapabilityResult(
                            id = "cap_${UUID.randomUUID()}",
                            kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
                            title = "استرجاع المعرفة: $trimmed",
                            summary = "لا توجد مقاطع ذات صلة في قاعدة المعرفة لهذا الاستعلام.",
                            isSuccessful = true,
                            timestampMs = System.currentTimeMillis()
                        )
                    } else {
                        ChatEntry.CapabilityResult(
                            id = "cap_${UUID.randomUUID()}",
                            kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
                            title = "استرجاع المعرفة: $trimmed",
                            summary = "تم استرجاع ${context.retrievedChunks.size} مقطعاً ذا صلة" +
                                if (context.isTruncated) " (تم اقتصار البحث)." else ".",
                            detail = context.retrievedChunks.take(4).joinToString("\n\n") { chunk ->
                                val score = "%.2f".format(chunk.relevanceScore)
                                "- من «${chunk.chunk.documentTitle}» (صلة $score — ${retrievalModeLabel(chunk.retrievalMode)})\n  ${chunk.snippet.take(200)}"
                            },
                            isSuccessful = true,
                            timestampMs = System.currentTimeMillis()
                        )
                    }
                },
                onFailure = { e ->
                    ChatEntry.CapabilityResult(
                        id = "cap_${UUID.randomUUID()}",
                        kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
                        title = "استرجاع المعرفة: $trimmed",
                        summary = "فشل الاسترجاع: ${e.localizedMessage ?: e::class.simpleName}",
                        isSuccessful = false,
                        timestampMs = System.currentTimeMillis()
                    )
                }
            )
            onResult(entry)
        }
    }

    // ------------------------------------------------------------------
    // §9/§8/§10 — Tool / skill / MCP invocation (the governed path)
    // ------------------------------------------------------------------

    /**
     * Invokes a registered tool (or skill, or HEALTHY-server MCP tool — they
     * are all registered ToolPorts) through the REAL governed execution
     * boundary: adapter resolution → argument parsing → authorization
     * (admission/approval) → execution. The result is handed to the
     * conversation as a structured block; a HUMAN_APPROVAL_REQUIRED denial
     * reports the consent path honestly (the inline block appears when the
     * conversation execution hits the same gate).
     */
    fun invokeTool(
        toolName: String,
        argumentsJson: String,
        agent: com.example.domain.core.agent.AgentDefinition?,
        isMcp: Boolean,
        sessionId: String? = null,
        onResult: (ChatEntry.CapabilityResult) -> Unit
    ) {
        if (_state.value.isInvoking) return
        _state.update { it.copy(isInvoking = true, errorMessage = null) }
        // CHAT FINAL CLOSURE (§4 scope pinning): the invocation's workspace,
        // project AND session (when available) are captured SYNCHRONOUSLY at
        // acceptance and pinned into the governed execution's
        // [ExecutionScope] — a project/workspace switch between acceptance
        // and execution dispatch can neither re-target the tool's sandbox
        // (FileSystemTool resolves the PINNED projectId first) nor re-attribute
        // the invocation to another workspace.
        val scope = captureInvocationScope()
        viewModelScope.launch {
            val invocationId = "capexec_${UUID.randomUUID()}"
            val resolvedAgent = resolveInvocationAgent(agent)
            val result = runCatching {
                executionService.executeStandaloneTool(
                    executionId = invocationId,
                    toolName = toolName,
                    argumentsJson = argumentsJson.ifBlank { "{}" },
                    agent = resolvedAgent,
                    isMcp = isMcp,
                    // The PINNED workspace (captured at acceptance — the same
                    // authority the orchestrator pins) so the admission
                    // pipeline's workspace-scope stage validates against it.
                    workspaceId = scope.workspaceId,
                    // CHAT FINAL CLOSURE: the PINNED project + session ride the
                    // SAME ExecutionScope the kernel's own executions use.
                    projectId = scope.projectId,
                    sessionId = sessionId
                )
            }
            _state.update { it.copy(isInvoking = false) }
            val entry = result.fold(
                onSuccess = { toolResult ->
                    val kind = capabilityKindFor(toolName, isMcp)
                    when (val outcomeValue = toolResult.outcome) {
                        is Outcome.Success -> ChatEntry.CapabilityResult(
                            id = "cap_${UUID.randomUUID()}",
                            kind = kind,
                            title = toolName,
                            summary = "تم التنفيذ بنجاح.",
                            detail = outcomeValue.value.take(4_000).ifBlank { null },
                            isSuccessful = true,
                            timestampMs = System.currentTimeMillis()
                        )
                        is Outcome.Degraded -> ChatEntry.CapabilityResult(
                            id = "cap_${UUID.randomUUID()}",
                            kind = kind,
                            title = toolName,
                            summary = "اكتمل بنمط تراجعي — ${outcomeValue.reason.userFriendlyLabel}",
                            detail = outcomeValue.partialValue?.take(4_000),
                            isSuccessful = true,
                            isDegraded = true,
                            degradedMessage = outcomeValue.diagnosticMessage.take(200),
                            timestampMs = System.currentTimeMillis()
                        )
                        is Outcome.Error -> ChatEntry.CapabilityResult(
                            id = "cap_${UUID.randomUUID()}",
                            kind = kind,
                            title = toolName,
                            summary = "فشل التنفيذ: ${outcomeValue.failure::class.simpleName ?: "خطأ"}",
                            detail = outcomeValue.diagnosticMessage.take(2_000)
                                .ifBlank { outcomeValue.failure.toString().take(2_000) },
                            isSuccessful = false,
                            timestampMs = System.currentTimeMillis()
                        )
                    }
                },
                onFailure = { e ->
                    ChatEntry.CapabilityResult(
                        id = "cap_${UUID.randomUUID()}",
                        kind = capabilityKindFor(toolName, isMcp),
                        title = toolName,
                        summary = "فشل الاستدعاء: ${e.localizedMessage ?: e::class.simpleName}",
                        isSuccessful = false,
                        timestampMs = System.currentTimeMillis()
                    )
                }
            )
            onResult(entry)
        }
    }

    /** The skill manifest parameters → a JSON arguments object for the tool path. */
    fun buildSkillArgumentsJson(parameters: Map<String, String>): String {
        val json = org.json.JSONObject()
        parameters.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    /**
     * §10 — MCP discovery: pings + handshakes a server through the REAL
     * discovery path (its tools register only after a successful handshake).
     */
    fun pingMcpServer(serverId: String) {
        if (_state.value.isDiscoveringMcp) return
        _state.update { it.copy(isDiscoveringMcp = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { extensionManager.pingAndDiscoverMcpServer(serverId) }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "تعذر اكتشاف خادم MCP: ${e.localizedMessage ?: e::class.simpleName}")
                    }
                }
            _state.update { it.copy(isDiscoveringMcp = false) }
            refreshCapabilities()
        }
    }

    /**
     * The honest retrieval-mode label (§12: no embeddings internals to the
     * user — just which retrieval style ran).
     */
    private fun retrievalModeLabel(mode: com.example.domain.core.memory.RetrievalMode): String =
        when (mode) {
            com.example.domain.core.memory.RetrievalMode.SEMANTIC -> "استرجاع دلالي"
            com.example.domain.core.memory.RetrievalMode.LEXICAL_FALLBACK -> "استرجاع معجمي"
            com.example.domain.core.memory.RetrievalMode.HYBRID -> "استرجاع هجين"
        }

    private fun capabilityKindFor(toolName: String, isMcp: Boolean): CapabilityKind = when {
        isMcp -> CapabilityKind.MCP
        _state.value.skills.any { it.id == toolName } -> CapabilityKind.SKILL
        else -> CapabilityKind.TOOL
    }

    /**
     * The agent identity a capability invocation is attributed to: the
     * caller's selection (AGENT mode) or the canonical quick-chat agent —
     * every invocation keeps the SAME governed principal the conversation
     * itself uses.
     */
    private fun resolveInvocationAgent(
        preferred: com.example.domain.core.agent.AgentDefinition?
    ): com.example.domain.core.agent.AgentDefinition {
        preferred?.let { return it }
        componentRegistry.getAgent(ConversationSessionService.QUICK_CHAT_AGENT_ID)?.let { return it }
        return CanonicalAgentCatalog.defaults.first {
            it.identity.id.value == ConversationSessionService.QUICK_CHAT_AGENT_ID
        }
    }
}
    // Composer drafts are scoped to the semantic conversation. The project
    // scope is tracked separately so a session switch never cleans a draft
    // through the wrong project after a workspace/project transition.
    private var boundConversationKey: String? = null
    private var boundConversationScope: Pair<String, Long>? = null
    private var conversationBindingInitialized = false

    /** Binds composer state to a conversation boundary without touching the timeline owner. */
    fun bindConversationKey(key: String?) {
        val currentScope = runCatching {
            val workspaceId = workspaceRuntimeService.activeWorkspaceIdOrNull() ?: return@runCatching null
            val projectId = workspaceRuntimeService.activeProjectIdOrNull() ?: return@runCatching null
            workspaceId to projectId
        }.getOrNull()
        if (!conversationBindingInitialized) {
            conversationBindingInitialized = true
            boundConversationKey = key
            boundConversationScope = currentScope
            return
        }
        if (boundConversationKey == key) {
            boundConversationScope = currentScope
            return
        }
        val staleDrafts = _state.value.attachmentDrafts
        val staleScope = boundConversationScope
        boundConversationKey = key
        boundConversationScope = currentScope
        _state.update {
            it.copy(
                attachmentDrafts = emptyList(),
                attachmentError = null,
                isImportingAttachment = false
            )
        }
        // A pure session switch stays in the same project, so the existing
        // coordinator can safely delete the old draft footprint. On a scope
        // transition we deliberately leave cleanup to the existing scope
        // observer, avoiding deletion through the NEW project's scope.
        if (staleDrafts.isNotEmpty() && staleScope != null && staleScope == currentScope) {
            viewModelScope.launch {
                staleDrafts.forEach { draft ->
                    runCatching { attachmentCoordinator.deleteImportedAttachment(draft) }
                }
            }
        }
    }

