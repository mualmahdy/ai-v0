package com.example.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.presentation.ui.components.DiagnosticBanner
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.ui.screens.activity.UnifiedActivityFeedScreen
import com.example.presentation.ui.screens.dashboard.DashboardScreen
import com.example.presentation.ui.screens.decision.DecisionScreen
import com.example.presentation.ui.screens.extensions.ExtensionsScreen
import com.example.presentation.ui.screens.files.FilesScreen
import com.example.presentation.ui.screens.governance.GovernanceScreen
import com.example.presentation.ui.screens.knowledge.KnowledgeScreen
import com.example.presentation.ui.screens.radar.RadarScreen
import com.example.presentation.ui.screens.settings.SettingsScreen
import com.example.presentation.ui.screens.studio.StudioScreen
import com.example.presentation.ui.screens.tasks.TasksScreen
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * MainAppScreen — the REAL smart-workspace shell (UI revamp)
 * ============================================================================
 *
 * Rebuilt on a real navigation architecture (androidx.navigation NavHost
 * with a back stack, state restoration and single-top destinations) instead
 * of the previous flat tab-swap:
 *
 *   - TopAppBar: workspace SWITCHER (multi-workspace is a real backend
 *     capability: WorkspaceRuntimeService) + live intelligence status chip.
 *   - Bottom NavigationBar: 5 context-centric primary destinations
 *     (Studio / unified Activity / Knowledge / Files / More).
 *   - "More": a full dashboard screen (not a modal sheet) that routes to the
 *     secondary capability surfaces (Providers, Tasks & Workflows, Decision
 *     Intelligence, Radar, Governance, Extensions, Settings).
 *   - Global diagnostic banner + snackbar error surfaces in ONE place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val allWorkspaces by viewModel.allWorkspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    var createWorkspaceOpen by rememberSaveable { mutableStateOf(false) }

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

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            WorkspaceTopBar(
                workspaceName = activeWorkspace?.name ?: state.activeProject?.name ?: "مساحة العمل الذكية",
                workspaceSubtitle = state.activeProject?.name ?: "AI Studio V0",
                activeLlmCount = activeLlmCount,
                activeResourceCount = activeResourceCount,
                allWorkspaces = allWorkspaces.map { it.name to it.id },
                activeWorkspaceId = activeWorkspace?.id,
                onSwitchWorkspace = { viewModel.switchWorkspace(it) },
                onCreateWorkspace = { createWorkspaceOpen = true },
                onOpenSettings = { navController.navigate(WorkspaceRoutes.SETTINGS) }
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_bottom_nav")
            ) {
                BottomDestination(
                    selected = currentRoute == WorkspaceRoutes.STUDIO,
                    onClick = { navController.navigateToTopLevel(WorkspaceRoutes.STUDIO) },
                    icon = Icons.Default.Psychology,
                    label = "الاستوديو",
                    tag = "nav_tab_studio"
                )
                BottomDestination(
                    selected = currentRoute == WorkspaceRoutes.ACTIVITY,
                    onClick = { navController.navigateToTopLevel(WorkspaceRoutes.ACTIVITY) },
                    icon = Icons.Default.NotificationsActive,
                    label = "النشاط",
                    tag = "nav_tab_activity"
                )
                BottomDestination(
                    selected = currentRoute == WorkspaceRoutes.KNOWLEDGE,
                    onClick = { navController.navigateToTopLevel(WorkspaceRoutes.KNOWLEDGE) },
                    icon = Icons.Default.MenuBook,
                    label = "المعرفة",
                    tag = "nav_tab_knowledge"
                )
                BottomDestination(
                    selected = currentRoute == WorkspaceRoutes.FILES,
                    onClick = { navController.navigateToTopLevel(WorkspaceRoutes.FILES) },
                    icon = Icons.Default.Folder,
                    label = "الملفات",
                    tag = "nav_tab_files"
                )
                BottomDestination(
                    selected = currentRoute == WorkspaceRoutes.MORE,
                    onClick = { navController.navigateToTopLevel(WorkspaceRoutes.MORE) },
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
            Column(modifier = Modifier.fillMaxSize()) {
                // Global honest diagnostic surface (degradation / results info).
                state.diagnosticBanner?.let { banner ->
                    DismissibleInfoBanner(
                        message = banner,
                        isDegraded = state.isDegraded,
                        onDismiss = { viewModel.dismissDiagnosticBanner() }
                    )
                }

                WorkspaceNavHost(
                    navController = navController,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (createWorkspaceOpen) {
        CreateWorkspaceDialog(
            onConfirm = { name, description ->
                viewModel.createWorkspace(name, description)
                createWorkspaceOpen = false
            },
            onDismiss = { createWorkspaceOpen = false }
        )
    }
}

/** Single-top + state-restoring navigation for the five primary tabs. */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun WorkspaceNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val navigate: (String) -> Unit = { route ->
        navController.navigate(route) { launchSingleTop = true }
    }
    NavHost(
        navController = navController,
        startDestination = WorkspaceRoutes.STUDIO,
        modifier = modifier
    ) {
        composable(WorkspaceRoutes.STUDIO) {
            StudioScreen(
                viewModel = viewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize().imePadding()
            )
        }
        composable(WorkspaceRoutes.ACTIVITY) {
            UnifiedActivityFeedScreen(
                viewModel = viewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.KNOWLEDGE) {
            KnowledgeScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.FILES) {
            FilesScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.MORE) {
            DashboardScreen(
                viewModel = viewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.PROVIDERS) {
            com.example.presentation.ui.screens.ProviderServiceManagerScreen(
                state = viewModel.uiState.collectAsState().value,
                viewModel = viewModel
            )
        }
        composable(WorkspaceRoutes.TASKS) {
            TasksScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.DECISION) {
            DecisionScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.RADAR) {
            RadarScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.GOVERNANCE) {
            GovernanceScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.EXTENSIONS) {
            ExtensionsScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    workspaceName: String,
    workspaceSubtitle: String,
    activeLlmCount: Int,
    activeResourceCount: Int,
    allWorkspaces: List<Pair<String, String>>,
    activeWorkspaceId: String?,
    onSwitchWorkspace: (String) -> Unit,
    onCreateWorkspace: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var switcherOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { switcherOpen = true }
                    .testTag("workspace_switcher")
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = workspaceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "تبديل مساحة العمل",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = workspaceSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = switcherOpen,
                    onDismissRequest = { switcherOpen = false }
                ) {
                    allWorkspaces.forEach { (name, id) ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = name,
                                        fontWeight = if (id == activeWorkspaceId) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (id == activeWorkspaceId) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.tertiary,
                                                    CircleShape
                                                )
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSwitchWorkspace(id)
                                switcherOpen = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("إنشاء مساحة عمل جديدة", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onCreateWorkspace()
                            switcherOpen = false
                        }
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
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("btn_open_settings")
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "الإعدادات",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.testTag("main_top_bar")
    )
}

@Composable
private fun DismissibleInfoBanner(
    message: String,
    isDegraded: Boolean,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 0.dp, bottom = 0.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                DiagnosticBanner(title = "تنبيه", message = message, isDegraded = isDegraded)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn_dismiss_banner")) {
                Text("إخفاء")
            }
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

@Composable
private fun CreateWorkspaceDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنشاء مساحة عمل جديدة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم مساحة العمل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_workspace_name")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("الوصف (اختياري)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "تنشئ مساحة عمل بملعب (Sandbox) وملفات وقاعدة معرفة وذاكرة وميزانية مستقلة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("إنشاء", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
