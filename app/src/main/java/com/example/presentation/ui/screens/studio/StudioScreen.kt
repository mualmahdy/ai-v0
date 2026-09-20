package com.example.presentation.ui.screens.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.SessionsViewModel

/**
 * ============================================================================
 * StudioScreen — the route-facing entry (thin adapter)
 * ============================================================================
 *
 * The Studio destination composes its feature ViewModels (ADR-6 owner-VM
 * composition, unchanged) and delegates ALL rendering to [ChatWorkspace] —
 * the conversation-first surface (Chat Workspace Task 1). Keeping this
 * adapter separate from the workspace keeps the navigation graph stable
 * while the conversation UI evolves behind it.
 *
 *  - the conversation runtime state → StudioViewModel (its owner);
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
        onNavigate = onNavigate,
        onPromptInput = studioViewModel::updatePromptInput,
        onSend = { studioViewModel.executePrompt(agent = agentsState.activeAgent) },
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
        modifier = modifier
    )
}
