package com.example.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.presentation.state.ActiveNavigationTab
import com.example.presentation.ui.screens.AgentStudioScreen
import com.example.presentation.ui.screens.CapabilitiesScreen
import com.example.presentation.ui.screens.FilesWorkspaceScreen
import com.example.presentation.ui.screens.MemoryRagScreen
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
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    selected = state.activeTab == ActiveNavigationTab.MEMORY_RAG,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.MEMORY_RAG) },
                    icon = { Icon(Icons.Default.Memory, contentDescription = "RAG Memory") },
                    label = { Text("الذاكرة الدلالية") },
                    modifier = Modifier.testTag("nav_tab_memory")
                )

                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.FILES,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.FILES) },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Workspace Files") },
                    label = { Text("الملفات") },
                    modifier = Modifier.testTag("nav_tab_files")
                )

                NavigationBarItem(
                    selected = state.activeTab == ActiveNavigationTab.CAPABILITIES,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.CAPABILITIES) },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Capabilities") },
                    label = { Text("الإمكانيات") },
                    modifier = Modifier.testTag("nav_tab_capabilities")
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
                ActiveNavigationTab.MEMORY_RAG -> MemoryRagScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.FILES -> FilesWorkspaceScreen(state = state, viewModel = viewModel)
                ActiveNavigationTab.CAPABILITIES -> CapabilitiesScreen(state = state, viewModel = viewModel)
                else -> AgentStudioScreen(state = state, viewModel = viewModel)
            }
        }
    }
}
