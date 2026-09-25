package com.example.presentation.ui.screens.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.example.presentation.state.CapabilityKind
import com.example.presentation.ui.navigation.navWidthClassForWidthDp
import com.example.presentation.viewmodel.ChatCapabilitiesViewModel
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SessionsViewModel

/**
 * ============================================================================
 * StudioScreen — the route-facing entry (thin adapter)
 * ============================================================================
 *
 * The Studio destination composes its feature ViewModels (ADR-6 owner-VM
 * composition, unchanged) and delegates ALL rendering to [ChatWorkspace] —
 * the conversation-first surface (Chat Workspace Task 1 + Chat Capabilities
 * Task 2 + FUNCTIONAL CLOSURE Phase 1). Keeping this adapter separate from
 * the workspace keeps the navigation graph stable while the conversation UI
 * evolves behind it.
 *
 *  - the conversation runtime state → StudioViewModel (its owner);
 *  - the capability layer (availability catalog, attachment drafts,
 *    tool/skill/MCP/search/knowledge invocation state) →
 *    ChatCapabilitiesViewModel (Task 2);
 *  - the durable-session registry rows + browser flag → SessionsViewModel;
 *  - the model picker resources + connect-LLM gate → ProvidersViewModel;
 *  - the agent catalog (picker/builder/delete/selection seam) →
 *    AgentsViewModel;
 *  - the autonomy-policy DISPLAY mirror stays on MainViewModel (its owner —
 *    the workspace-scoped mirror); mutations route to SettingsViewModel
 *    (authoritative service routing — ADR-6 slice 1);
 *  - the honest current-project mirror → ProjectsViewModel (D-02).
 *
 * FUNCTIONAL CLOSURE (Phase 1) seams owned by this adapter:
 *  - §4 AGENT RESTORATION: a reopened session's agent
 *    ([com.example.presentation.viewmodel.StudioViewModel.StudioUiState.
 *    restoredAgentId]) is routed into the agent-catalog owner's selection —
 *    the ACTUAL agent executes continuations, not just a display name.
 *  - §22 CAPABILITY LIFECYCLE: every direct invocation first lands a PENDING
 *    block in the conversation (the honest kind + title), then resolves it
 *    with the real outcome — the sheets can close immediately without the
 *    user losing track of what ran.
 *  - §14 ABORTED SENDS: when the ViewModel aborts a send pre-execution
 *    (grounding failure), the attachment drafts are handed BACK to the
 *    capability layer — the user's picks are never silently consumed.
 */
@Composable
fun StudioScreen(
    viewModel: MainViewModel,
    studioViewModel: com.example.presentation.viewmodel.StudioViewModel,
    chatCapabilitiesViewModel: ChatCapabilitiesViewModel,
    sessionsViewModel: SessionsViewModel,
    providersViewModel: com.example.presentation.viewmodel.ProvidersViewModel,
    agentsViewModel: com.example.presentation.viewmodel.AgentsViewModel,
    projectsViewModel: com.example.presentation.viewmodel.ProjectsViewModel,
    onNavigate: (String) -> Unit,
    onAutonomyPolicy: (com.example.domain.core.task.AutonomyPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val providersState by providersViewModel.state.collectAsState()
    val studioState by studioViewModel.state.collectAsState()
    val sessionsState by sessionsViewModel.state.collectAsState()
    val agentsState by agentsViewModel.state.collectAsState()
    val projectsState by projectsViewModel.state.collectAsState()
    val capabilityState by chatCapabilitiesViewModel.state.collectAsState()

    // ------------------------------------------------------------------
    // FUNCTIONAL CLOSURE (§4): AGENT RESTORATION — when a session is
    // reopened, its OWN agent becomes the catalog owner's active selection
    // (the continuation then executes through the real agent, not just a
    // name in the header). A null/unknown agent keeps the current selection;
    // AGENT-mode sends without a resolvable selection fail honestly in the
    // ViewModel (the documented actionable error).
    // ------------------------------------------------------------------
    val restoredAgentId = studioState.restoredAgentId
    LaunchedEffect(restoredAgentId) {
        if (restoredAgentId != null) {
            agentsState.availableAgents.firstOrNull {
                it.identity.id.value == restoredAgentId
            }?.let { agent -> agentsViewModel.selectAgent(agent) }
        }
    }

    // The composer follows the same semantic boundary as the visible chat.
    val conversationKey = studioState.activeSessionId ?: "draft:${studioState.chatMode.name}"
    LaunchedEffect(conversationKey, projectsState.currentProject?.id) {
        chatCapabilitiesViewModel.bindConversationKey(conversationKey)
    }

    // §19 (Task 2): the adaptive width class (the same M3 breakpoints the
    // navigation shell uses — chat-first / sessions+chat / sessions+chat+context).
    val widthClass = navWidthClassForWidthDp(LocalConfiguration.current.screenWidthDp)

    // ------------------------------------------------------------------
    // UI POLISH (§4 — Creation group honesty): the LLM connection fact the
    // capability hub resolves its Creation rows on. The providers feature
    // owns the resources (owner-VM composition); the operational truth of
    // "an LLM is usable" = lifecycle ENABLED **or** ACTIVE (the runtime
    // promotes healthy resources to ACTIVE) AND health not UNAVAILABLE.
    // Pushed to the capability layer as value+lambda (the sessionNetworkPolicy
    // seam pattern — no new cross-VM dependency).
    // ------------------------------------------------------------------
    val hasActiveLlm = providersState.materializedResources.any {
        it.resourceType == com.example.domain.core.resource.ResourceType.LLM &&
            (it.lifecycleState == com.example.domain.core.resource.ResourceLifecycleState.ENABLED ||
                it.lifecycleState == com.example.domain.core.resource.ResourceLifecycleState.ACTIVE) &&
            it.healthStatus != com.example.domain.core.provider.HealthStatus.UNAVAILABLE
    }
    LaunchedEffect(hasActiveLlm) {
        chatCapabilitiesViewModel.setLlmConnected(hasActiveLlm)
    }

    // FRONTIER EXPORT: the platform share sheet target — hoisted OUT of the
    // callback (Compose locals are composable-context-only).
    val shareContext = androidx.compose.ui.platform.LocalContext.current

    ChatWorkspace(
        state = studioState,
        shellAutonomyPolicy = state.autonomyPolicy,
        projectName = projectsState.currentProject?.name,
        activeSessionTitle = sessionsState.sessions
            .firstOrNull { it.id.value == studioState.activeSessionId }?.title,
        // FRONTIER SEARCH: the pure filter (title/agent/model, case-insensitive)
        // runs ONCE here — pane and sheet render the same filtered truth.
        sessions = com.example.presentation.state.SessionTranscriptExporter.filterSessions(
            sessionsState.sessions,
            sessionsState.searchQuery
        ),
        llmResources = providersState.materializedResources,
        agents = agentsState.availableAgents,
        activeAgent = agentsState.activeAgent,
        isSessionBrowserOpen = sessionsState.isSessionBrowserOpen,
        sessionSearchQuery = sessionsState.searchQuery,
        onSessionSearchQueryChange = sessionsViewModel::setSearchQuery,
        onRenameSession = { sessionId, title ->
            sessionsViewModel.renameSession(sessionId, title)
        },
        onExportSession = { sessionId ->
            // FRONTIER EXPORT: the durable transcript leaves through the
            // platform share sheet (ACTION_SEND, plain Markdown text — no
            // storage permission, no file provider, honest content).
            val context = shareContext
            sessionsViewModel.exportSession(
                sessionId,
                onReady = { title, markdown ->
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                        putExtra(android.content.Intent.EXTRA_TEXT, markdown)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, "مشاركة الجلسة")
                    )
                },
                onUnavailable = { sessionsViewModel.reportExportUnavailable() }
            )
        },
        capabilityState = capabilityState,
        widthClass = widthClass,
        onNavigate = onNavigate,
        onPromptInput = studioViewModel::updatePromptInput,
        onSend = {
            // §5 (Task 2) + FUNCTIONAL CLOSURE (§14): the send consumes the
            // attachment drafts only when the ViewModel really ACCEPTED it; a
            // send aborted at the grounding stage hands the drafts BACK (the
            // user's picks are never silently lost).
            val accepted = studioViewModel.executePrompt(
                agent = agentsState.activeAgent,
                attachments = capabilityState.attachmentDrafts,
                onSendAborted = { drafts ->
                    chatCapabilitiesViewModel.restoreAttachmentDrafts(drafts)
                }
            )
            if (accepted && capabilityState.attachmentDrafts.isNotEmpty()) {
                chatCapabilitiesViewModel.clearAttachmentDrafts()
            }
        },
        onCancelExecution = studioViewModel::cancelExecution,
        // FUNCTIONAL CLOSURE (§6): targeted regenerate — the tapped entry's id.
        onRegenerate = { assistantEntryId ->
            studioViewModel.regenerateFromAssistant(
                assistantEntryId = assistantEntryId,
                agent = agentsState.activeAgent
            )
        },
        onEditMessage = studioViewModel::editUserMessage,
        onResetView = studioViewModel::resetTranscriptView,
        onNewSession = {
            studioViewModel.startNewSession(agent = agentsState.activeAgent)
        },
        onOpenSession = studioViewModel::openSession,
        onDeleteSession = { sessionId ->
            sessionsViewModel.deleteSession(sessionId) { deleted ->
                studioViewModel.onSessionDeleted(deleted)
            }
        },
        onSessionBrowserOpen = sessionsViewModel::setSessionBrowserOpen,
        onModeChange = studioViewModel::setChatMode,
        onSelectModel = studioViewModel::selectModel,
        onSelectAgent = agentsViewModel::selectAgent,
        onNetworkPolicy = studioViewModel::setNetworkPolicy,
        onAutonomyPolicy = onAutonomyPolicy,
        onCreateAgent = { name, role, description, systemPrompt, capabilities ->
            // §23 (audit 2026): the user authors the agent's execution
            // capabilities — the contract surface.
            agentsViewModel.createAgent(
                name = name,
                role = role,
                description = description,
                systemPrompt = systemPrompt,
                capabilities = capabilities
            )
        },
        onDeleteAgent = agentsViewModel::deleteAgent,
        onOpenCapabilities = chatCapabilitiesViewModel::refreshCapabilities,
        onPickFiles = { uris, mimeTypes ->
            chatCapabilitiesViewModel.pickFiles(uris, mimeTypes)
        },
        onPickFolder = chatCapabilitiesViewModel::pickFolder,
        onRemoveAttachment = chatCapabilitiesViewModel::removeAttachment,
        onInvokeSearch = { query ->
            // FUNCTIONAL CLOSURE (§22): the PENDING block lands FIRST (the
            // sheet closes immediately), then the real outcome resolves it.
            val pendingId = studioViewModel.appendPendingCapability(
                kind = CapabilityKind.SEARCH,
                title = "بحث ذكي: $query"
            )
            // CHAT FINAL CLOSURE (§4/§5): the session the invocation belongs
            // to rides the invocation (pinned scope snapshot end-to-end).
            chatCapabilitiesViewModel.invokeSearch(
                query = query,
                agent = agentsState.activeAgent,
                sessionId = studioState.activeSessionId
            ) { entry ->
                studioViewModel.resolveCapabilityResult(pendingId, entry)
            }
        },
        onInvokeKnowledge = { query ->
            val pendingId = studioViewModel.appendPendingCapability(
                kind = CapabilityKind.KNOWLEDGE_RETRIEVAL,
                title = "استرجاع المعرفة: $query"
            )
            chatCapabilitiesViewModel.invokeKnowledgeRetrieval(
                query = query,
                sessionId = studioState.activeSessionId
            ) { entry ->
                studioViewModel.resolveCapabilityResult(pendingId, entry)
            }
        },
        onInvokeTool = { toolName, argumentsJson, kind ->
            val pendingId = studioViewModel.appendPendingCapability(
                kind = kind,
                title = toolName
            )
            chatCapabilitiesViewModel.invokeTool(
                toolName = toolName,
                argumentsJson = argumentsJson,
                agent = agentsState.activeAgent,
                isMcp = kind == CapabilityKind.MCP,
                sessionId = studioState.activeSessionId
            ) { entry ->
                studioViewModel.resolveCapabilityResult(pendingId, entry)
            }
        },
        onPingMcp = chatCapabilitiesViewModel::pingMcpServer,
        onApprove = studioViewModel::approveApproval,
        onReject = studioViewModel::rejectApproval,
        // P4: the tapped approval block's OWN id — the retry re-executes
        // exactly that block's message, never "the last approved one".
        onRetryAfterApproval = { approvalId ->
            studioViewModel.retryAfterApproval(
                approvalId = approvalId,
                agent = agentsState.activeAgent
            )
        },
        onGrantAlways = studioViewModel::grantAlwaysForApproval,
        // ARTIFACT CANVAS (§10): the conversation's artifact cards open the
        // scope-aware preview surface (pane at expanded width, sheet below).
        onOpenArtifact = studioViewModel::openArtifact,
        onCloseArtifact = studioViewModel::closeArtifact,
        onRequestArtifactEdit = studioViewModel::requestArtifactEdit,
        modifier = modifier
    )
}
