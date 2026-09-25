package com.example.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.presentation.ui.components.DiagnosticBanner
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.ui.navigation.navWidthClassForWidthDp
import com.example.presentation.ui.navigation.topLevelDestinations
import com.example.presentation.ui.screens.activity.UnifiedActivityFeedScreen
import com.example.presentation.ui.screens.dashboard.DashboardScreen
import com.example.presentation.ui.screens.home.HomeScreen
import com.example.presentation.ui.screens.decision.DecisionScreen
import com.example.presentation.ui.screens.extensions.ExtensionsScreen
import com.example.presentation.ui.screens.files.FilesScreen
import com.example.presentation.ui.screens.governance.GovernanceScreen
import com.example.presentation.ui.screens.knowledge.KnowledgeScreen
import com.example.presentation.ui.screens.projects.ProjectsScreen
import com.example.presentation.ui.screens.radar.RadarScreen
import com.example.presentation.ui.screens.settings.SettingsScreen
import com.example.presentation.ui.screens.studio.StudioScreen
import com.example.presentation.ui.screens.tasks.TasksScreen
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * MainAppScreen — the REAL smart-workspace shell (UI revamp + Design
 * Closure phases B/C)
 * ============================================================================
 *
 * A real navigation architecture (androidx.navigation NavHost with a back
 * stack, state restoration and single-top destinations):
 *
 *   - TopAppBar: the HONEST context hierarchy — the active WORKSPACE name
 *     as the title, the active PROJECT as the subtitle (from the projects
 *     feature VM — never workspace data relabeled as a project), the
 *     workspace SWITCHER (WorkspaceRuntimeService) and the live
 *     intelligence status chip;
 *   - Adaptive navigation (M3 window size classes): compact widths get the
 *     bottom NavigationBar; medium/expanded widths get a side
 *     NavigationRail — both composed from the ONE topLevelDestinations
 *     taxonomy (unique icons per destination);
 *   - Bottom destinations: الرئيسية (real work center) / الدردشة /
 *     المشاريع (the real projects surface) / النشاط / المزيد;
 *   - "المزيد": the three-group capability center (Workspace /
 *     Intelligence / Governance & System) routing to every secondary
 *     surface;
 *   - Global diagnostic banner + snackbar error surfaces in ONE place,
 *     each fed from its owning feature's own channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    tasksViewModel: com.example.presentation.viewmodel.TasksViewModel? = null,
    filesViewModel: com.example.presentation.viewmodel.FilesViewModel,
    settingsViewModel: com.example.presentation.viewmodel.SettingsViewModel,
    studioViewModel: com.example.presentation.viewmodel.StudioViewModel,
    // CHAT CAPABILITIES (Task 2): the chat feature's capability layer VM —
    // the availability catalog, attachment drafts, and the governed
    // tool/skill/MCP/search/knowledge invocation state.
    chatCapabilitiesViewModel: com.example.presentation.viewmodel.ChatCapabilitiesViewModel,
    sessionsViewModel: com.example.presentation.viewmodel.SessionsViewModel,
    // ADR-6 slice 3: the KNOWLEDGE feature ViewModel (RAG base + semantic
    // engine + memory browser) — owned here, passed to the screens that
    // render or read its state.
    knowledgeViewModel: com.example.presentation.viewmodel.KnowledgeViewModel,
    // ADR-6 slice 4: the GOVERNANCE feature ViewModel (observatory + human
    // approval surface) — owned here, passed to the governance screen.
    governanceViewModel: com.example.presentation.viewmodel.GovernanceViewModel,
    // ADR-6 slice 5: the PROVIDERS feature ViewModel (control room: the
    // provider/resource flows, the connect wizard, the credential dialog) —
    // owned here; the providers screen composes on it and the shell's
    // status chip reads its materialized resources.
    providersViewModel: com.example.presentation.viewmodel.ProvidersViewModel,
    // ADR-6 slice 6: the RADAR feature ViewModel (the evolution observatory)
    // — owned here; the radar screen composes on it and its honest error
    // channel surfaces in the global snackbar.
    radarViewModel: com.example.presentation.viewmodel.RadarViewModel,
    // ADR-6 slice 6: the DECISION feature ViewModel (the CBR-MDP cockpit) —
    // owned here; it collects the decision share of the studio signal bus
    // itself, and the decision screen composes on it.
    decisionViewModel: com.example.presentation.viewmodel.DecisionViewModel,
    // ADR-6 slice 6: the WORKFLOWS feature ViewModel (plan builder + durable
    // library + resume) — owned here; the tasks screen composes on it and
    // the explorer's plans row reads its state.
    workflowsViewModel: com.example.presentation.viewmodel.WorkflowsViewModel,
    // ADR-6 slice 7: the AGENTS feature ViewModel (the durable agent
    // catalog + the runtime registration seam) — owned here; the Studio
    // picker, the Tasks builder and the Explorer row compose on it, and
    // its honest error channel surfaces in the global snackbar.
    agentsViewModel: com.example.presentation.viewmodel.AgentsViewModel,
    // ADR-6 slice 7: the EXTENSIONS feature ViewModel (MCP + skills +
    // plugins + integrations) — owned here; the extensions screen composes
    // on it and its honest error channel surfaces in the global snackbar.
    extensionsViewModel: com.example.presentation.viewmodel.ExtensionsViewModel,
    // ADR-6 slice 7: the ACTIVITY feature ViewModel (the unified activity
    // feed) — owned here; the activity screen composes on it (it collects
    // the studio bus's Started stake itself).
    activityViewModel: com.example.presentation.viewmodel.ActivityViewModel,
    // UI Design Closure (phase B): the PROJECTS feature ViewModel — the real
    // project-management surface (list / current binding / create / switch /
    // lifecycle). The shell's TopBar subtitle and the Home work center read
    // its display state (owner-VM composition — the same pattern as every
    // other feature seam in this shell).
    projectsViewModel: com.example.presentation.viewmodel.ProjectsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val allWorkspaces by viewModel.allWorkspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    // ADR-6 SLICE 2: the studio feature's own surfaces (transcript errors,
    // degradation banners) render in the SAME global honest surfaces as
    // the shared ones — each from its OWN source of truth.
    val studioState by studioViewModel.state.collectAsState()
    // ADR-6 SLICE 3: the knowledge feature's honest error channel (ingest
    // failures, deletion errors, memory-store errors) — same global
    // snackbar pattern, dismissed from its own state.
    val knowledgeState by knowledgeViewModel.state.collectAsState()
    // ADR-6 SLICE 4: the governance feature's honest error channel
    // (approval resolution failures, standing-grant failures) — same global
    // snackbar pattern, dismissed from its own state.
    val governanceState by governanceViewModel.state.collectAsState()
    // ADR-6 SLICE 5: the providers feature's honest error channel (service
    // test/discovery failures, resource lifecycle errors) — same global
    // snackbar pattern, dismissed from its own state.
    val providersState by providersViewModel.state.collectAsState()
    // ADR-6 SLICE 6: the radar/decision/workflows features' honest error
    // channels — the same global snackbar surface, each dismissed from its
    // own source of truth.
    val radarState by radarViewModel.state.collectAsState()
    val decisionState by decisionViewModel.state.collectAsState()
    val workflowsState by workflowsViewModel.state.collectAsState()
    // ADR-6 SLICE 7: the agents + extensions features' honest error
    // channels — the same global snackbar surface, each dismissed from its
    // own source of truth. (The activity feature is read-only flows — no
    // error channel to surface.)
    val agentsState by agentsViewModel.state.collectAsState()
    val extensionsState by extensionsViewModel.state.collectAsState()
    // UI Design Closure (phase B): the projects feature's display state —
    // the HONEST current-project mirror (the real project row behind the
    // activeProjectId binding) that the TopBar subtitle and the Home work
    // center compose on.
    val projectsState by projectsViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    var createWorkspaceOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 2: the studio feature's honest error channel (agent-mode
    // gating, session create/open failures, execution errors) — surfaced
    // with the same global snackbar, dismissed from its own state.
    LaunchedEffect(studioState.errorMessage) {
        studioState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            studioViewModel.dismissError()
        }
    }

    // ADR-6 SLICE 3: the knowledge feature's honest error channel — the
    // same global snackbar surface, its own source of truth.
    LaunchedEffect(knowledgeState.errorMessage) {
        knowledgeState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            knowledgeViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 4: the governance feature's honest error channel — the
    // same global snackbar surface, its own source of truth.
    LaunchedEffect(governanceState.errorMessage) {
        governanceState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            governanceViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 5: the providers feature's honest error channel — the
    // same global snackbar surface, its own source of truth.
    LaunchedEffect(providersState.errorMessage) {
        providersState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            providersViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 6: the radar feature's honest error channel (promotion
    // gate failures, audit/approval errors) — same global snackbar.
    LaunchedEffect(radarState.errorMessage) {
        radarState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            radarViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 6: the decision feature's honest error channel — same
    // global snackbar.
    LaunchedEffect(decisionState.errorMessage) {
        decisionState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            decisionViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 6: the workflows feature's honest error channel (save /
    // load / execution failures) — same global snackbar.
    LaunchedEffect(workflowsState.errorMessage) {
        workflowsState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            workflowsViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 7: the agents feature's honest error channel (registry
    // unavailable, create failures) — same global snackbar.
    LaunchedEffect(agentsState.errorMessage) {
        agentsState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            agentsViewModel.clearErrorMessage()
        }
    }

    // ADR-6 SLICE 7: the extensions feature's honest error channel (skill
    // execution failures) — same global snackbar.
    LaunchedEffect(extensionsState.errorMessage) {
        extensionsState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            extensionsViewModel.clearErrorMessage()
        }
    }

    // ------------------------------------------------------------------
    // bootstrapFailureMessage was previously collected by MainViewModel
    // but NEVER rendered: a failed bootstrap still showed the fully-
    // scaffolded app, and every project-dependent action popped an error
    // snackbar. The app now refuses to pretend it is ready: a FAILED
    // bootstrap replaces the whole shell with an explicit failure surface
    // (phase + message + retry). A successful retry dismisses the gate via
    // the same bootstrapState flow that opened it.
    // ------------------------------------------------------------------
    if (state.bootstrapFailureMessage != null) {
        BootstrapFailureGate(
            phaseLabel = state.bootstrapPhase,
            message = state.bootstrapFailureMessage ?: "",
            onRetry = viewModel::retryBootstrap
        )
        return
    }

    val activeLlmCount = providersState.materializedResources.count {
        it.resourceType == com.example.domain.core.resource.ResourceType.LLM &&
            it.lifecycleState == ResourceLifecycleState.ENABLED
    }
    val activeResourceCount = providersState.materializedResources.count {
        it.lifecycleState == ResourceLifecycleState.ENABLED
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Adaptive shell (M3 window size classes — canonical 600/840dp
    // breakpoints): compact keeps the bottom NavigationBar; medium and
    // expanded get a side NavigationRail so the content keeps its height
    // on tablets, unfoldeds and landscape.
    val configuration = LocalConfiguration.current
    val widthClass = navWidthClassForWidthDp(configuration.screenWidthDp)
    val isCompact = widthClass == com.example.presentation.ui.navigation.NavWidthClass.COMPACT

    // ------------------------------------------------------------------
    // IME / INSETS (Chat Workspace Task 1 §4A):
    // ONE clear inset chain, no accumulation:
    //   - statusBars: consumed once at the Scaffold modifier (below);
    //   - navigationBars: owned by the bottom NavigationBar itself;
    //   - ime: owned ONLY by the chat composer (the single element that
    //     must hug the keyboard).
    // While the IME is visible the bottom NavigationBar yields its region
    // to the keyboard and the content insets are consumed for descendants
    // so the composer's imePadding lands EXACTLY at the keyboard's top
    // edge — no double padding, no keyboard-sized blank after send.
    // Portrait and landscape share the math (insets are edge-based, not
    // orientation-based).
    //
    // GAP CLOSURE (§4A-refine — the persistent strip above the keyboard):
    // the bar's disappearance is gated on the ANIMATED inset values, not on
    // a binary "ime arrived" flip. The bar stays until the keyboard has
    // actually COVERED the bar's own navigation-bar region (the IME window
    // draws over it, so the swap is invisible) and returns as soon as the
    // region is uncovered again. A binary flip double-books the region for
    // a visible window — bar height + ime padding on the same strip —
    // which is exactly the blank gap that kept coming back above the
    // keyboard on every open.
    // ------------------------------------------------------------------
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navigationBarBottomPx = WindowInsets.navigationBars.getBottom(density)
    val showBottomNavigationBar = shouldShowBottomNavigationBar(
        isCompact = isCompact,
        imeBottomPx = imeBottomPx,
        navigationBarBottomPx = navigationBarBottomPx
    )

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            WorkspaceTopBar(
                // UI Design Closure (D-02): the HONEST hierarchy — the
                // workspace name as the title, the REAL active project (the
                // projects feature's binding mirror) as the subtitle. Never
                // workspace data relabeled as a project.
                workspaceName = activeWorkspace?.name
                    ?: stringResource(com.example.R.string.topbar_workspace_fallback),
                projectSubtitle = projectsState.currentProject?.name
                    ?.let { stringResource(com.example.R.string.topbar_project_subtitle, it) }
                    ?: stringResource(com.example.R.string.topbar_no_project),
                activeLlmCount = activeLlmCount,
                activeResourceCount = activeResourceCount,
                allWorkspaces = allWorkspaces.map { it.name to it.id },
                activeWorkspaceId = activeWorkspace?.id,
                onSwitchWorkspace = settingsViewModel::switchWorkspace,
                onCreateWorkspace = { createWorkspaceOpen = true },
                onOpenSettings = { navController.navigate(WorkspaceRoutes.SETTINGS) }
            )
        },
        bottomBar = {
            // §4A-refine: the bar yields exactly when the keyboard has
            // covered the bar's own region — never before (that would
            // leave a bar-height blank strip above the still-animating
            // keyboard) and never after (the composer's imePadding takes
            // over precisely at that edge).
            if (showBottomNavigationBar) {
                NavigationBar(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("main_bottom_nav")
                ) {
                    topLevelDestinations.forEach { destination ->
                        BottomDestination(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = destination.icon,
                            label = stringResource(destination.labelRes),
                            tag = destination.tag
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // §4A: the consumed-insets recipe — descendants that pad by
                // insets (the Studio composer's imePadding) deduct what this
                // padding already applied, so the composer lands exactly at
                // the keyboard's top edge instead of above
                // (keyboard + navigation bar).
                .consumeWindowInsets(innerPadding)
        ) {
            if (!isCompact) {
                NavigationRail(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("main_nav_rail"),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    topLevelDestinations.forEach { destination ->
                        NavigationRailItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = stringResource(destination.labelRes)
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                            modifier = Modifier.testTag(destination.tag)
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                // ADR-6 SLICE 8 (D-13): the shell's own diagnostic-banner
                // block was REMOVED — its UiState channel had no writer
                // since the slice-2 extraction (a rendering surface that
                // could never render anything). The FEATURE banners below
                // are the real surfaces (each with its own writer + own
                // dismiss); the shell keeps only the global error snackbar.

                // ADR-6 SLICE 2: the STUDIO feature's own diagnostic banner
                // (execution degradation, durable-turn persistence failures,
                // cancel notices) — same global surface, own state + own
                // dismiss (no shared mutable UiState between the VMs).
                studioState.diagnosticBanner?.let { banner ->
                    DismissibleInfoBanner(
                        message = banner,
                        isDegraded = studioState.isDegraded,
                        onDismiss = { studioViewModel.dismissBanner() }
                    )
                }

                // ADR-6 SLICE 5: the PROVIDERS feature's own diagnostic
                // banner (connection-test results, validation outcomes) —
                // same global surface, own state + own dismiss.
                providersState.diagnosticBanner?.let { banner ->
                    DismissibleInfoBanner(
                        message = banner,
                        isDegraded = false,
                        onDismiss = { providersViewModel.dismissDiagnosticBanner() }
                    )
                }

                WorkspaceNavHost(
                    navController = navController,
                    viewModel = viewModel,
                    tasksViewModel = tasksViewModel,
                    filesViewModel = filesViewModel,
                    settingsViewModel = settingsViewModel,
                    studioViewModel = studioViewModel,
                    chatCapabilitiesViewModel = chatCapabilitiesViewModel,
                    sessionsViewModel = sessionsViewModel,
                    knowledgeViewModel = knowledgeViewModel,
                    governanceViewModel = governanceViewModel,
                    providersViewModel = providersViewModel,
                    radarViewModel = radarViewModel,
                    decisionViewModel = decisionViewModel,
                    workflowsViewModel = workflowsViewModel,
                    agentsViewModel = agentsViewModel,
                    extensionsViewModel = extensionsViewModel,
                    activityViewModel = activityViewModel,
                    // UI Design Closure (phase B): the projects feature VM —
                    // the HOME work center + PROJECTS screen compose on it.
                    projectsViewModel = projectsViewModel,
                    modifier = Modifier.weight(1f)
                )
                }
            }
        }
    }

    if (createWorkspaceOpen) {
        CreateWorkspaceDialog(
            onConfirm = { name, description ->
                settingsViewModel.createWorkspace(name, description)
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

/**
 * GAP-23 (Design Closure 2026) — full-screen honest bootstrap-failure gate.
 * Rendered INSTEAD of the app shell when the startup state machine ended in
 * `Failed`: the phase label, the machine-readable failure message (with its
 * actionable Arabic guidance), and an idempotent retry. No navigation, no
 * workspace switcher — the app does not offer functionality its substrate
 * could not guarantee.
 */
@Composable
private fun BootstrapFailureGate(
    phaseLabel: String,
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("bootstrap_failure_gate")
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "فشل تجهيز التطبيق",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "الحالة: $phaseLabel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag("btn_retry_bootstrap")
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("إعادة محاولة التجهيز")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) {
            Text("أو أعد تشغيل التطبيق ثم جرّب مجدداً")
        }
    }
}

@Composable
private fun WorkspaceNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
    tasksViewModel: com.example.presentation.viewmodel.TasksViewModel? = null,
    filesViewModel: com.example.presentation.viewmodel.FilesViewModel,
    settingsViewModel: com.example.presentation.viewmodel.SettingsViewModel,
    studioViewModel: com.example.presentation.viewmodel.StudioViewModel,
    // CHAT CAPABILITIES (Task 2): the chat feature's capability layer VM.
    chatCapabilitiesViewModel: com.example.presentation.viewmodel.ChatCapabilitiesViewModel,
    sessionsViewModel: com.example.presentation.viewmodel.SessionsViewModel,
    radarViewModel: com.example.presentation.viewmodel.RadarViewModel,
    decisionViewModel: com.example.presentation.viewmodel.DecisionViewModel,
    workflowsViewModel: com.example.presentation.viewmodel.WorkflowsViewModel,
    // ADR-6 slice 3: the knowledge feature VM — composed into the Knowledge /
    // Settings / Explorer destinations.
    knowledgeViewModel: com.example.presentation.viewmodel.KnowledgeViewModel,
    // ADR-6 slice 4: the governance feature VM — composed into the
    // Governance destination.
    governanceViewModel: com.example.presentation.viewmodel.GovernanceViewModel,
    // ADR-6 slice 5: the providers feature VM — composed into the Providers /
    // Dashboard / Studio / Explorer destinations (owner-VM reads).
    providersViewModel: com.example.presentation.viewmodel.ProvidersViewModel,
    // ADR-6 slice 7: the agents + extensions + activity feature VMs —
    // composed into the Studio / Tasks / Explorer (agents), Extensions
    // (extensions) and Activity (activity) destinations.
    agentsViewModel: com.example.presentation.viewmodel.AgentsViewModel,
    extensionsViewModel: com.example.presentation.viewmodel.ExtensionsViewModel,
    activityViewModel: com.example.presentation.viewmodel.ActivityViewModel,
    // UI Design Closure (phase B): the projects feature VM — the HOME
    // work center and the PROJECTS surface compose on it.
    projectsViewModel: com.example.presentation.viewmodel.ProjectsViewModel,
    modifier: Modifier = Modifier
) {
    val navigate: (String) -> Unit = { route ->
        // UI Design Closure (navigation semantics): SECONDARY destinations
        // get their own back-stack hierarchy — popUpTo the start destination
        // (saving the popped top-level state) instead of stacking on top of
        // the hub. Stacking them above the hub poisoned the bottom bar's
        // restoreState contract: after More → Explorer, tapping «المزيد»
        // popped-and-RESTORED the saved stack whose top was the SECONDARY
        // destination, so the tab appeared selected while the content never
        // moved (a silent navigation no-op — caught by the shell's
        // reachability suite). With this hierarchy, every top-level tab
        // restores exactly its own state, and back from any secondary
        // surface returns to the start destination.
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
        }
    }
    NavHost(
        navController = navController,
        startDestination = WorkspaceRoutes.HOME,
        modifier = modifier
    ) {
        composable(WorkspaceRoutes.HOME) {
            // UI Design Closure (phase B — D-05): the real WORK CENTER. Every
            // displayed value is evidence-derived: the workspace + the REAL
            // active project (projects feature), the project-scoped session
            // registry (sessions feature), and the studio callbacks for
            // new/resumed sessions (owner-VM composition throughout).
            HomeScreen(
                workspaceName = viewModel.activeWorkspace.collectAsState().value?.name
                    ?: stringResource(com.example.R.string.topbar_workspace_fallback),
                currentProjectName = projectsViewModel.state.collectAsState().value.currentProject?.name,
                recentSessions = sessionsViewModel.state.collectAsState().value.sessions,
                onNewSession = {
                    // StateFlow.value is a plain read — usable in the
                    // non-composable click context.
                    studioViewModel.startNewSession(
                        agent = agentsViewModel.state.value.activeAgent
                    )
                    navigate(WorkspaceRoutes.STUDIO)
                },
                onOpenSession = { sessionId ->
                    studioViewModel.openSession(sessionId)
                    navigate(WorkspaceRoutes.STUDIO)
                },
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.STUDIO) {
            StudioScreen(
                viewModel = viewModel,
                studioViewModel = studioViewModel,
                // CHAT CAPABILITIES (Task 2): the capability layer VM — the
                // availability catalog, attachment drafts, and the governed
                // tool/skill/MCP/search/knowledge invocation state.
                chatCapabilitiesViewModel = chatCapabilitiesViewModel,
                sessionsViewModel = sessionsViewModel,
                // ADR-6 slice 5: the model picker + the connect-LLM gate read
                // the providers feature VM — the resource owner.
                providersViewModel = providersViewModel,
                // ADR-6 slice 7: the agent catalog (picker + builder +
                // delete) composes on the agents feature VM — its owner.
                agentsViewModel = agentsViewModel,
                // Chat Workspace Task 1: the honest current-project mirror
                // (D-02) — the compact chat header shows the project the
                // conversation is bound to.
                projectsViewModel = projectsViewModel,
                onNavigate = navigate,
                // ADR-6 slice 1: the autonomy mutation routes to the SETTINGS
                // feature ViewModel (authoritative service routing).
                onAutonomyPolicy = settingsViewModel::setAutonomyPolicy,
                // §4A: NO route-level imePadding — the composer owns the
                // single IME inset source of this screen.
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.ACTIVITY) {
            UnifiedActivityFeedScreen(
                // ADR-6 slice 7: the unified feed composes on the activity
                // feature VM — its owner (per-execution trace binding + the
                // workspace-scoped audit window).
                viewModel = activityViewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
        // UI Design Closure (phase B — D-01): the PROJECTS destination now
        // opens the REAL projects surface (the feature VM owns the project
        // lifecycle) — the regression this route opening the Tasks board is
        // pinned by NavigationShellTest (a silent revert fails the suite).
        composable(WorkspaceRoutes.PROJECTS) {
            ProjectsScreen(
                viewModel = projectsViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.KNOWLEDGE) {
            KnowledgeScreen(
                knowledgeViewModel = knowledgeViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.FILES) {
            FilesScreen(
                filesViewModel = filesViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.MORE) {
            DashboardScreen(
                viewModel = viewModel,
                providersViewModel = providersViewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.PROVIDERS) {
            com.example.presentation.ui.screens.ProviderServiceManagerScreen(
                viewModel = providersViewModel
            )
        }
        composable(WorkspaceRoutes.TASKS) {
            TasksScreen(
                // ADR-6 slice 7: the AGENT CATALOG (the step-agent assignment)
                // composes on the agents feature VM — its owner. The screen's
                // MainViewModel seam is GONE: every read now has an owner.
                agentsViewModel = agentsViewModel,
                tasksViewModel = tasksViewModel,
                // ADR-6 slice 6: the builder/library/resume surface composes
                // on the workflows feature VM — its owner.
                workflowsViewModel = workflowsViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.DECISION) {
            DecisionScreen(
                // ADR-6 slice 6: the cockpit composes on the decision
                // feature VM — its owner.
                viewModel = decisionViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.RADAR) {
            RadarScreen(
                // ADR-6 slice 6: the observatory composes on the radar
                // feature VM — its owner.
                viewModel = radarViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.GOVERNANCE) {
            GovernanceScreen(
                viewModel = governanceViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.EXTENSIONS) {
            ExtensionsScreen(
                // ADR-6 slice 7: the extensions screen composes on the
                // extensions feature VM — its owner.
                viewModel = extensionsViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                settingsViewModel = settingsViewModel,
                onNavigate = navigate,
                // ADR-6 slice 2: the SESSION policy (studio-owned) is passed
                // as value + mutation — the settings surface displays and
                // changes it without owning it (same pattern as the slice-1
                // autonomy delegation, direction reversed).
                sessionNetworkPolicy = studioViewModel.state.collectAsState().value.networkPolicy,
                onSessionNetworkPolicy = studioViewModel::setNetworkPolicy,
                // ADR-6 slice 3: the SEMANTIC ENGINE card — the readiness
                // flags and the provisioning mutation come from the
                // knowledge feature VM as value + lambda (same delegation).
                semanticModelReady = knowledgeViewModel.state.collectAsState().value.semanticModelReady,
                isProvisioningSemanticModel = knowledgeViewModel.state.collectAsState().value.isProvisioningSemanticModel,
                onProvisionSemanticModel = knowledgeViewModel::provisionLocalSemanticModel,
                modifier = Modifier.fillMaxSize()
            )
        }
        composable(WorkspaceRoutes.EXPLORER) {
            com.example.presentation.ui.screens.explorer.WorkspaceExplorerScreen(
                viewModel = viewModel,
                filesViewModel = filesViewModel,
                sessionsViewModel = sessionsViewModel,
                // ADR-6 slice 3: the knowledge row (doc count + semantic
                // readiness subtitle) reads the knowledge feature VM — its
                // owner (same pattern as the files/sessions rows).
                knowledgeViewModel = knowledgeViewModel,
                // ADR-6 slice 5: the models/resources rows read the providers
                // feature VM — their owner (same pattern).
                providersViewModel = providersViewModel,
                // ADR-6 slice 6: the plans row reads the workflows feature
                // VM — its owner (same pattern).
                workflowsViewModel = workflowsViewModel,
                // ADR-6 slice 7: the agents row + the tools row read the
                // agents + extensions feature VMs — their owners (same
                // pattern).
                agentsViewModel = agentsViewModel,
                extensionsViewModel = extensionsViewModel,
                onNavigate = navigate,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    /** The active WORKSPACE name (the hierarchy's root — the switcher target). */
    workspaceName: String,
    /** The REAL active project subtitle (from the projects feature — D-02). */
    projectSubtitle: String,
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
                        text = projectSubtitle,
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
                label = if (activeLlmCount > 0) {
                    stringResource(com.example.R.string.topbar_active_intelligence, activeLlmCount)
                } else {
                    stringResource(com.example.R.string.topbar_no_active_intelligence)
                },
                isActive = activeLlmCount > 0,
                detail = stringResource(com.example.R.string.topbar_enabled_resources, activeResourceCount)
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

/**
 * IME / INSETS (§4A-refine — the frame-perfect bottom-bar gate): decides
 * whether the compact bottom NavigationBar occupies its region, from the
 * LIVE (animated) inset values:
 *
 *  - keyboard fully closed ([imeBottomPx] == 0): the bar shows — regardless
 *    of the navigation-bar inset value (a zero inset on some devices must
 *    not hide a bar that still has its own layout height);
 *  - keyboard open and already covering the bar's navigation-bar region
 *    (ime >= navigationBars): the bar yields — the IME window draws over
 *    that exact region, so the swap is visually invisible and the
 *    composer's imePadding takes over precisely at the keyboard's edge;
 *  - keyboard animating IN (0 < ime < navigationBars): the bar STAYS until
 *    the keyboard visually covers it — a binary "ime arrived" gate would
 *    remove the bar while the keyboard is still sliding, double-booking
 *    the strip (bar height + ime padding) and painting the blank gap that
 *    kept returning above the keyboard on every open;
 *  - keyboard animating OUT (ime falling below navigationBars): the bar
 *    returns while the keyboard's shrinking tail still covers the region —
 *    no blank frame in either direction.
 *
 * Pure on purpose: unit-tested without composition (the invariant that
 * kills the gap lives here, not in the composable).
 */
internal fun shouldShowBottomNavigationBar(
    isCompact: Boolean,
    imeBottomPx: Int,
    navigationBarBottomPx: Int
): Boolean {
    if (!isCompact) return false
    if (imeBottomPx <= 0) return true
    return imeBottomPx < navigationBarBottomPx
}
