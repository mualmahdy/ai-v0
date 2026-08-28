package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.ui.components.WorkspaceNavTabs
import com.example.ui.components.WorkspaceTopBar
import com.example.ui.viewmodel.WorkspaceViewModel

@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val projects by viewModel.projects.collectAsState()
    val activeProjectId by viewModel.activeProjectId.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val components by viewModel.workspaceComponents.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                projects = projects,
                activeProjectId = activeProjectId,
                isOfflineMode = isOfflineMode,
                onProjectSelected = { viewModel.activeProjectId.value = it },
                onToggleOffline = { viewModel.setOfflineMode(it) },
                onOpenTerminal = { viewModel.activeTab.value = "terminal_panel" },
                onOpenLayoutEditor = { viewModel.activeTab.value = "layout_panel" }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Data-Driven Navigation Bar
            WorkspaceNavTabs(
                components = components,
                activeTab = activeTab,
                onTabSelected = { viewModel.activeTab.value = it }
            )

            // Dynamic Panel Renderer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    "chat_panel" -> ChatPanel(viewModel)
                    "agent_panel" -> AgentsPanel(viewModel)
                    "code_panel" -> CodeEditorPanel(viewModel)
                    "terminal_panel" -> TerminalPanel(viewModel)
                    "workflow_panel" -> WorkflowPanel(viewModel)
                    "knowledge_panel" -> KnowledgePanel(viewModel)
                    "memory_panel" -> MemoryPanel(viewModel)
                    "providers_panel" -> ProvidersPanel(viewModel)
                    "diagnostics_panel" -> DiagnosticsPanel(viewModel)
                    "layout_panel" -> LayoutEditorPanel(viewModel)
                    "settings_panel" -> SettingsPanel(viewModel)
                    else -> ChatPanel(viewModel)
                }
            }
        }
    }
}
