package com.example.presentation.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.domain.core.project.Project
import com.example.presentation.ui.components.ConfirmDialog
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.InfoRow
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.ProjectsViewModel
import com.example.ui.theme.LocalExtendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * ProjectsScreen — the REAL projects surface (UI Design Closure, phase B —
 * defect D-01)
 * ============================================================================
 *
 * The PROJECTS bottom-bar destination previously opened the Tasks board
 * while the ENTIRE project lifecycle sat unexposed in the backend. This
 * screen surfaces it honestly:
 *
 *  - CURRENT PROJECT: the activeProjectId binding resolved to the real
 *    project row (metadata + evidence-derived session count);
 *  - PROJECT LIST: the workspace's ACTIVE projects (the only legal picker
 *    list per §27) with open/switch, rename, archive and trash — every
 *    action routed through the feature VM to the AUTHORITATIVE services
 *    (scope saved durably on switch);
 *  - FULL STATE HONESTY: loading, loaded, empty (with a create action),
 *    in-flight switch spinner, and the feature's own error/success
 *    channels (dismissed here, never silent).
 *
 * All copy is resource-backed Arabic (the D-12 rule for new screens).
 */
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var renameTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var archiveTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var trashTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var moveTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var snapshotTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    // CLOSURE §4.4 — SAF bridges for the real export/import workflows.
    // The resolver seam is set per-composition (the activity context).
    val context = androidx.compose.ui.platform.LocalContext.current
    AppContextHolder.resolver = context.contentResolver
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val projectId = exportTarget
        val resolver = AppContextHolder.resolver
        if (uri != null && projectId != null && resolver != null) {
            val outputStream = runCatching { resolver.openOutputStream(uri) }.getOrNull()
            if (outputStream != null) {
                viewModel.exportProjectTo(projectId, outputStream) { exportTarget = null }
                return@rememberLauncherForActivityResult
            }
        }
        exportTarget = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val resolver = AppContextHolder.resolver
        if (uri != null && resolver != null) {
            val inputStream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            if (inputStream != null) {
                viewModel.importProjectFrom(inputStream)
            }
        }
    }

    // Load the move-target workspaces + snapshots when the screen opens
    // (CLOSURE §4.4 surfaces).
    LaunchedEffect(state.currentProject?.id) {
        viewModel.loadAvailableWorkspaces()
        viewModel.loadSnapshots()
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSuccess()
        }
    }

    Scaffold(
        modifier = modifier.testTag("projects_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionHeader(
                    icon = Icons.Default.FolderOpen,
                    title = stringResource(com.example.R.string.projects_title),
                    subtitle = stringResource(com.example.R.string.projects_subtitle),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // ---- Current project (the honest binding mirror) ----
            item {
                CurrentProjectCard(
                    currentProject = state.currentProject,
                    sessionCount = state.currentProjectSessionCount,
                    isLoading = state.isLoading
                )
            }

            // ---- Scoped note + create action ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(com.example.R.string.projects_scoped_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !state.isTransferring,
                        modifier = Modifier.testTag("btn_import_project")
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(com.example.R.string.projects_import))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = { createOpen = true },
                        modifier = Modifier.testTag("btn_create_project")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(com.example.R.string.projects_create))
                    }
                }
            }

            // ---- CLOSURE §4.4: transfer progress (honest in-flight state) ----
            if (state.isTransferring) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("transfer_progress_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = state.transferProgressLabel
                                    ?: stringResource(com.example.R.string.projects_transfer_progress),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ---- CLOSURE §4.4: the current project's snapshots (restore) ----
            if (state.projectSnapshots.isNotEmpty()) {
                item {
                    SectionHeader(
                        icon = Icons.Default.PhotoCamera,
                        title = "لقطات المشروع الحالي (${state.projectSnapshots.size})",
                        subtitle = "استعادة كاملة ومتحقق منها — الفشل لا يمس الحالة الحالية",
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                items(
                    state.projectSnapshots,
                    key = { "snapshot_${it.id}" }
                ) { snapshot ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("snapshot_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(snapshot.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = snapshot.reason + " • " +
                                            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                                                .format(Date(snapshot.createdAtEpochMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.restoreSnapshot(snapshot.id) },
                                enabled = !state.isTransferring,
                                modifier = Modifier.testTag("btn_restore_snapshot")
                            ) { Text(stringResource(com.example.R.string.projects_restore)) }
                        }
                    }
                }
            }

            // ---- The ACTIVE-projects list (§27 picker list) ----
            if (state.projects.isEmpty() && !state.isLoading) {
                item {
                    EmptyState(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(com.example.R.string.projects_list_empty_title),
                        hint = stringResource(com.example.R.string.projects_list_empty_hint),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(com.example.R.string.projects_list_header),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                items(state.projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        isCurrent = project.id == state.currentProject?.id,
                        isSwitching = state.isSwitchingProjectId == project.id,
                        isTransferring = state.isTransferring,
                        onOpen = { viewModel.openProject(project.id) },
                        onRename = { renameTarget = project.id },
                        onArchive = { archiveTarget = project.id },
                        onTrash = { trashTarget = project.id },
                        onExport = {
                            exportTarget = project.id
                            exportLauncher.launch("${project.name}.aiv0project")
                        },
                        onMove = { moveTarget = project.id },
                        onClone = { viewModel.cloneProject(project.id) },
                        onSnapshot = { snapshotTarget = project.id }
                    )
                }
            }
        }
    }

    if (createOpen) {
        CreateProjectDialog(
            onConfirm = { name, description ->
                viewModel.createProject(name, description)
                createOpen = false
            },
            onDismiss = { createOpen = false }
        )
    }

    renameTarget?.let { id ->
        val project = state.projects.firstOrNull { it.id == id }
        if (project != null) {
            RenameProjectDialog(
                initialName = project.name,
                initialDescription = project.description.orEmpty(),
                onConfirm = { name, description ->
                    viewModel.renameProject(id, name, description)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null }
            )
        }
    }

    archiveTarget?.let { id ->
        val project = state.projects.firstOrNull { it.id == id }
        if (project != null) {
            ConfirmDialog(
                title = stringResource(com.example.R.string.projects_archive_title),
                message = stringResource(com.example.R.string.projects_archive_body, project.name),
                confirmLabel = stringResource(com.example.R.string.projects_archive_confirm),
                onConfirm = {
                    viewModel.archiveProject(id)
                    archiveTarget = null
                },
                onDismiss = { archiveTarget = null }
            )
        }
    }

    trashTarget?.let { id ->
        val project = state.projects.firstOrNull { it.id == id }
        if (project != null) {
            ConfirmDialog(
                title = stringResource(com.example.R.string.projects_trash_title),
                message = stringResource(com.example.R.string.projects_trash_body, project.name),
                confirmLabel = stringResource(com.example.R.string.projects_trash_confirm),
                onConfirm = {
                    viewModel.trashProject(id)
                    trashTarget = null
                },
                onDismiss = { trashTarget = null }
            )
        }
    }

    // CLOSURE §4.4 — MOVE: pick the target workspace (identity-preserving
    // verified rebind with count witnesses).
    moveTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(stringResource(com.example.R.string.projects_move)) },
            text = {
                Column {
                    Text(
                        "نقل يحافظ على الهوية: الجلسات والملفات والمعرفة والمهام تُربط كلها بالمساحة الهدف داخل معاملة واحدة متحقق منها.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.availableWorkspaces.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "لا توجد مساحات عمل أخرى — أنشئ مساحة أولاً من مبدّل المساحات.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.availableWorkspaces.forEach { ws ->
                        TextButton(
                            onClick = {
                                viewModel.moveProjectToWorkspace(id, ws.id)
                                moveTarget = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_move_to_${ws.id}")
                        ) { Text(ws.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) { Text("إلغاء") }
            }
        )
    }

    // CLOSURE §4.4 — SNAPSHOT: label prompt.
    snapshotTarget?.let { id ->
        var label by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { snapshotTarget = null },
            title = { Text(stringResource(com.example.R.string.projects_snapshot)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("تسمية اللقطة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.snapshotProject(id, label)
                        snapshotTarget = null
                    },
                    modifier = Modifier.testTag("btn_confirm_snapshot")
                ) { Text("إنشاء") }
            },
            dismissButton = {
                TextButton(onClick = { snapshotTarget = null }) { Text("إلغاء") }
            }
        )
    }
}

/** CLOSURE §4.4: the SAF resolver holder (set by the composition — the
 *  activity-context seam the launchers write streams through). */
object AppContextHolder {
    @Volatile
    var resolver: android.content.ContentResolver? = null
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun CurrentProjectCard(
    currentProject: Project?,
    sessionCount: Int?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("projects_current_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        if (isLoading) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(com.example.R.string.projects_opening),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (currentProject == null) {
            Text(
                text = stringResource(com.example.R.string.projects_none_current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp)
            )
        } else {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(com.example.R.string.projects_current_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    StatusBadge(
                        text = stringResource(com.example.R.string.projects_active_badge),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = currentProject.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                currentProject.description?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(
                    label = stringResource(
                        com.example.R.string.projects_created_at,
                        formatDate(currentProject.createdAtEpochMs)
                    ),
                    value = sessionCount?.let { count ->
                        stringResource(com.example.R.string.projects_sessions_count, count)
                    } ?: stringResource(
                        com.example.R.string.projects_updated_at,
                        formatDate(currentProject.updatedAtEpochMs)
                    )
                )
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    isCurrent: Boolean,
    isSwitching: Boolean,
    isTransferring: Boolean = false,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onExport: () -> Unit = {},
    onMove: () -> Unit = {},
    onClone: () -> Unit = {},
    onSnapshot: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("project_card_${project.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(com.example.R.string.projects_active_badge),
                            tint = LocalExtendedColors.current.success,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                project.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(
                    text = stringResource(
                        com.example.R.string.projects_created_at,
                        formatDate(project.createdAtEpochMs)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isSwitching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(
                    onClick = onOpen,
                    enabled = !isCurrent,
                    modifier = Modifier.testTag("btn_open_project_${project.id}")
                ) {
                    Text(
                        text = if (isCurrent) {
                            stringResource(com.example.R.string.projects_active_badge)
                        } else {
                            stringResource(com.example.R.string.projects_open)
                        }
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(com.example.R.string.projects_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ProjectActionsMenu(
                    menuOpen = menuOpen,
                    setMenuOpen = { menuOpen = it },
                    isTransferring = isTransferring,
                    onRename = onRename,
                    onArchive = onArchive,
                    onTrash = onTrash,
                    onExport = onExport,
                    onMove = onMove,
                    onClone = onClone,
                    onSnapshot = onSnapshot
                )
            }
        }
    }
}

@Composable
private fun ProjectActionsMenu(
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
    isTransferring: Boolean = false,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onExport: () -> Unit = {},
    onMove: () -> Unit = {},
    onClone: () -> Unit = {},
    onSnapshot: () -> Unit = {}
) {
    DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_export)) },
            leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp)) },
            enabled = !isTransferring,
            onClick = { setMenuOpen(false); onExport() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_move)) },
            leadingIcon = { Icon(Icons.Default.MoveDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            enabled = !isTransferring,
            onClick = { setMenuOpen(false); onMove() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_clone)) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
            enabled = !isTransferring,
            onClick = { setMenuOpen(false); onClone() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_snapshot)) },
            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp)) },
            enabled = !isTransferring,
            onClick = { setMenuOpen(false); onSnapshot() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_rename)) },
            onClick = { setMenuOpen(false); onRename() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_archive)) },
            onClick = { setMenuOpen(false); onArchive() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(com.example.R.string.projects_trash)) },
            onClick = { setMenuOpen(false); onTrash() }
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.example.R.string.projects_create_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(com.example.R.string.projects_create_name_label)) },
                    supportingText = { Text(stringResource(com.example.R.string.projects_create_name_support)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_project_name")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(com.example.R.string.projects_create_desc_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(com.example.R.string.projects_create_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("btn_confirm_create_project")
            ) {
                Text(stringResource(com.example.R.string.projects_create_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.example.R.string.projects_cancel))
            }
        }
    )
}

@Composable
private fun RenameProjectDialog(
    initialName: String,
    initialDescription: String,
    onConfirm: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.example.R.string.projects_rename_title), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(com.example.R.string.projects_create_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("input_project_rename")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim().ifEmpty { null }) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(com.example.R.string.projects_rename_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.example.R.string.projects_cancel))
            }
        }
    )
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale("ar")).format(Date(epochMs))
