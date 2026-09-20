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
        refreshCapabilities()
    }

    /** Indexed knowledge corpus size (the RAG availability fact). */
    private var knowledgeDocumentCount: Int = 0

    // ------------------------------------------------------------------
    // §3/§4 — the capability catalog
    // ------------------------------------------------------------------

    /**
     * Re-resolves the availability catalog from the REAL facts (project
     * binding, radar checks, registries, corpus, network) through the PURE
     * [ChatCapabilityPolicy]. No second availability system: the facts come
     * from the sources of truth, the policy only maps them honestly.
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
                    enabledSkillCount = current.skills.count {
                        it.state == com.example.domain.core.extension.SkillState.ENABLED
                    },
                    registeredToolCount = componentRegistry.listTools().size,
                    mcpServerCount = current.mcpServers.size
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

    /** Removes one draft BEFORE send (§5: remove before send). */
    fun removeAttachment(id: String) {
        _state.update { current ->
            current.copy(attachmentDrafts = current.attachmentDrafts.filterNot { it.id == id })
        }
    }

    /** The screen clears the drafts once the send consumed them. */
    fun clearAttachmentDrafts() {
        _state.update { it.copy(attachmentDrafts = emptyList(), attachmentError = null) }
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
        onResult: (ChatEntry.CapabilityResult) -> Unit
    ) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || _state.value.isInvoking) return
        _state.update { it.copy(isInvoking = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                searchIntelligenceService.searchIntelligent(trimmed)
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
                    ChatEntry.CapabilityResult(
                        id = "cap_${UUID.randomUUID()}",
                        kind = CapabilityKind.SEARCH,
                        title = "بحث ذكي: $trimmed",
                        summary = when {
                            intelligence.rankedItems.isEmpty() ->
                                "لا نتائج مطابقة للاستعلام."
                            intelligence.isPartial ->
                                "اكتمل بنمط تراجعي (${intelligence.rankedItems.size} نتيجة) — ${intelligence.degradationReason ?: "بعض المصادر لم يستجب"}."
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
                        degradedMessage = intelligence.degradationReason,
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
        onResult: (ChatEntry.CapabilityResult) -> Unit
    ) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || _state.value.isInvoking) return
        _state.update { it.copy(isInvoking = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                ragPipelineService.retrieveRelevantContext(trimmed)
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
        onResult: (ChatEntry.CapabilityResult) -> Unit
    ) {
        if (_state.value.isInvoking) return
        _state.update { it.copy(isInvoking = true, errorMessage = null) }
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
                    // Pin the ACTIVE workspace (the same authority the
                    // orchestrator pins) so the admission pipeline's
                    // workspace-scope stage validates against it.
                    workspaceId = runCatching {
                        workspaceRuntimeService.activeWorkspaceIdOrNull()
                    }.getOrNull()
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
