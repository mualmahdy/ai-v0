package com.example.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.state.ActiveNavigationTab
import com.example.presentation.ui.screens.AgentStudioScreen
import com.example.presentation.ui.screens.DecisionIntelligenceScreen
import com.example.presentation.ui.screens.ExtensionsScreen
import com.example.presentation.ui.screens.FilesWorkspaceScreen
import com.example.presentation.ui.screens.KnowledgeRagScreen
import com.example.presentation.ui.screens.ProviderServiceManagerScreen
import com.example.presentation.ui.screens.RadarEvolutionScreen
import com.example.presentation.ui.screens.TasksWorkflowsScreen
import com.example.presentation.viewmodel.MainViewModel

@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearErrorMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ScrollableTabRow(
                selectedTabIndex = state.activeTab.ordinal,
                edgePadding = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("main_top_tabs")
            ) {
                ActiveNavigationTab.values().forEach { tab ->
                    val isSelected = state.activeTab == tab
                    val icon = when (tab) {
                        ActiveNavigationTab.STUDIO -> Icons.Default.Psychology
                        ActiveNavigationTab.TASKS_WORKFLOWS -> Icons.Default.AccountTree
                        ActiveNavigationTab.DECISION_INTELLIGENCE -> Icons.Default.AutoAwesome
                        ActiveNavigationTab.RADAR_EVOLUTION -> Icons.Default.Radar
                        ActiveNavigationTab.EXTENSIONS -> Icons.Default.Extension
                        ActiveNavigationTab.MODELS_CAPABILITIES -> Icons.Default.Build
                        ActiveNavigationTab.KNOWLEDGE_RAG -> Icons.Default.MenuBook
                        ActiveNavigationTab.FILES -> Icons.Default.Folder
                    }

                    Tab(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.displayName,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = { Icon(icon, contentDescription = tab.displayName) },
                        modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_bottom_nav")
            ) {
                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.STUDIO,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.STUDIO) },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "Studio") },
                    label = { Text("الاستوديو") },
                    modifier = Modifier.testTag("nav_tab_studio")
                )

                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.TASKS_WORKFLOWS,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.TASKS_WORKFLOWS) },
                    icon = { Icon(Icons.Default.AccountTree, contentDescription = "Workflows") },
                    label = { Text("المسارات") },
                    modifier = Modifier.testTag("nav_tab_workflows")
                )

                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.DECISION_INTELLIGENCE,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.DECISION_INTELLIGENCE) },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "CBR-MDP") },
                    label = { Text("القرارات") },
                    modifier = Modifier.testTag("nav_tab_decision")
                )

                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.KNOWLEDGE_RAG,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.KNOWLEDGE_RAG) },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = "RAG Knowledge") },
                    label = { Text("المعرفة") },
                    modifier = Modifier.testTag("nav_tab_knowledge")
                )

                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.FILES,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.FILES) },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Workspace Files") },
                    label = { Text("الملفات") },
                    modifier = Modifier.testTag("nav_tab_files")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (state.activeTab) {
                ActiveNavigationTab.STUDIO -> AgentStudioScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.TASKS_WORKFLOWS -> TasksWorkflowsScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.DECISION_INTELLIGENCE -> DecisionIntelligenceScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.RADAR_EVOLUTION -> RadarEvolutionScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.EXTENSIONS -> ExtensionsScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.MODELS_CAPABILITIES -> ProviderServiceManagerScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.KNOWLEDGE_RAG -> KnowledgeRagScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.FILES -> FilesWorkspaceScreen(state = state, viewModel = viewModel)
            }
        }
    }
}
