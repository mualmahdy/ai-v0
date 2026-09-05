package com.example.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.presentation.state.ActiveNavigationTab
import com.example.presentation.state.UiState
import com.example.presentation.ui.screens.AgentStudioScreen
import com.example.presentation.ui.screens.DecisionIntelligenceScreen
import com.example.presentation.ui.screens.ExtensionsScreen
import com.example.presentation.ui.screens.FilesWorkspaceScreen
import com.example.presentation.ui.screens.KnowledgeRagScreen
import com.example.presentation.ui.screens.ProviderServiceManagerScreen
import com.example.presentation.ui.screens.RadarEvolutionScreen
import com.example.presentation.ui.screens.TasksWorkflowsScreen
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * MainAppScreen — smart-workspace shell (redesign)
 * ============================================================================
 *
 * Replaced the redundant double navigation (scrollable top TabRow + bottom
 * NavigationBar both navigating) with a single coherent structure:
 *
 *   - TopAppBar: workspace brand + live intelligence status chip (active LLM
 *     resources count) so the user always knows if the workspace "has a brain".
 *   - Bottom NavigationBar: 5 primary destinations (Studio / Providers /
 *     Knowledge / Files / More).
 *   - "More": ModalBottomSheet with the secondary destinations (Tasks &
 *     Workflows, Decision Intelligence, Radar, Extensions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var moreSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearErrorMessage()
        }
    }

    val activeLlmCount = state.materializedResources.count {
        it.resourceType == com.example.domain.core.resource.ResourceType.LLM &&
            it.lifecycleState == ResourceLifecycleState.ENABLED
    }
    val activeResourceCount = state.materializedResources.count {
        it.lifecycleState == ResourceLifecycleState.ENABLED
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "مساحة العمل الذكية",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.activeProject?.name ?: "AI Studio V0",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    StatusChip(
                        label = if (activeLlmCount > 0) "ذكاء نشط ×$activeLlmCount" else "لا ذكاء نشط",
                        isActive = activeLlmCount > 0,
                        detail = "$activeResourceCount مورد مفعّل"
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.testTag("main_top_bar")
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_bottom_nav")
            ) {
                BottomDestination(
                    selected = state.activeTab == ActiveNavigationTab.STUDIO,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.STUDIO) },
                    icon = Icons.Default.Psychology,
                    label = "الاستوديو",
                    tag = "nav_tab_studio"
                )
                BottomDestination(
                    selected = state.activeTab == ActiveNavigationTab.MODELS_CAPABILITIES,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.MODELS_CAPABILITIES) },
                    icon = Icons.Default.Dns,
                    label = "المزوّدون",
                    tag = "nav_tab_providers"
                )
                BottomDestination(
                    selected = state.activeTab == ActiveNavigationTab.KNOWLEDGE_RAG,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.KNOWLEDGE_RAG) },
                    icon = Icons.Default.MenuBook,
                    label = "المعرفة",
                    tag = "nav_tab_knowledge"
                )
                BottomDestination(
                    selected = state.activeTab == ActiveNavigationTab.FILES,
                    onClick = { viewModel.selectTab(ActiveNavigationTab.FILES) },
                    icon = Icons.Default.Folder,
                    label = "الملفات",
                    tag = "nav_tab_files"
                )
                BottomDestination(
                    selected = moreSheetOpen,
                    onClick = { moreSheetOpen = true },
                    icon = Icons.Default.MoreHoriz,
                    label = "المزيد",
                    tag = "nav_tab_more"
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

    if (moreSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { moreSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Text(
                text = "أقسام إضافية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            MoreDestination(
                icon = Icons.Default.AccountTree,
                label = "المهام وخطط العمل",
                description = "تعريف المهام وتنفيذ خطط العمل متعددة الخطوات",
                tag = "more_tab_tasks"
            ) {
                viewModel.selectTab(ActiveNavigationTab.TASKS_WORKFLOWS)
                moreSheetOpen = false
            }
            MoreDestination(
                icon = Icons.Default.AutoAwesome,
                label = "ذكاء القرار (CBR-MDP)",
                description = "محرك القرار القائم على الحالات والتعلم المعزّز",
                tag = "more_tab_decision"
            ) {
                viewModel.selectTab(ActiveNavigationTab.DECISION_INTELLIGENCE)
                moreSheetOpen = false
            }
            MoreDestination(
                icon = Icons.Default.Radar,
                label = "رادار التطور",
                description = "مسح التقنيات ومرشّحات تطوير القدرات",
                tag = "more_tab_radar"
            ) {
                viewModel.selectTab(ActiveNavigationTab.RADAR_EVOLUTION)
                moreSheetOpen = false
            }
            MoreDestination(
                icon = Icons.Default.Extension,
                label = "الملحقات والمهارات",
                description = "MCP والمهارات والبرامج المساعدة",
                tag = "more_tab_extensions"
            ) {
                viewModel.selectTab(ActiveNavigationTab.EXTENSIONS)
                moreSheetOpen = false
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomDestination(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    tag: String
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun MoreDestination(
    icon: ImageVector,
    label: String,
    description: String,
    tag: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .testTag(tag)
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun StatusChip(label: String, isActive: Boolean, detail: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
