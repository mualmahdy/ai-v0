package com.example.presentation.ui.screens.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
 * Task 2). Keeping this adapter separate from the workspace keeps the
 * navigation graph stable while the conversation UI evolves behind it.
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

    // §19 (Task 2): the adaptive width class (the same M3 breakpoints the
    // navigation shell uses — chat-first / sessions+chat / sessions+chat+context).
    val widthClass = navWidthClassForWidthDp(LocalConfiguration.current.screenWidthDp)

    ChatWorkspace(
        state = studioState,
        shellAutonomyPolicy = state.autonomyPolicy,
        projectName = projectsState.currentProject?.name,
        activeSessionTitle = sessionsState.sessions
            .firstOrNull { it.id.value == studioState.activeSessionId }?.title,
        sessions = sessionsState.sessions,
        llmResources = providersState.materializedResources,
        agents = agentsState.availableAgents,
        activeAgent = agentsState.activeAgent,
        isSessionBrowserOpen = sessionsState.isSessionBrowserOpen,
        capabilityState = capabilityState,
        widthClass = widthClass,
        onNavigate = onNavigate,
        onPromptInput = studioViewModel::updatePromptInput,
        onSend = {
            // §5 (Task 2): the send consumes the attachment drafts only when
            // the ViewModel really ACCEPTED it (a refused send keeps them).
            val accepted = studioViewModel.executePrompt(
                agent = agentsState.activeAgent,
                attachments = capabilityState.attachmentDrafts
            )
            if (accepted && capabilityState.attachmentDrafts.isNotEmpty()) {
                chatCapabilitiesViewModel.clearAttachmentDrafts()
            }
        },
        onCancelExecution = studioViewModel::cancelExecution,
        onRegenerate = { studioViewModel.regenerateLast(agent = agentsState.activeAgent) },
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
            chatCapabilitiesViewModel.invokeSearch(query, agentsState.activeAgent) { entry ->
                studioViewModel.appendCapabilityResult(entry)
            }
        },
        onInvokeKnowledge = { query ->
            chatCapabilitiesViewModel.invokeKnowledgeRetrieval(query) { entry ->
                studioViewModel.appendCapabilityResult(entry)
            }
        },
        onInvokeTool = { toolName, argumentsJson, isMcp ->
            chatCapabilitiesViewModel.invokeTool(
                toolName = toolName,
                argumentsJson = argumentsJson,
                agent = agentsState.activeAgent,
                isMcp = isMcp
            ) { entry ->
                studioViewModel.appendCapabilityResult(entry)
            }
        },
        onPingMcp = chatCapabilitiesViewModel::pingMcpServer,
        onApprove = studioViewModel::approveApproval,
        onReject = studioViewModel::rejectApproval,
        onRetryAfterApproval = {
            studioViewModel.retryAfterApproval(agent = agentsState.activeAgent)
        },
        onGrantAlways = studioViewModel::grantAlwaysForApproval,
        modifier = modifier
    )
}
