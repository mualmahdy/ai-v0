package com.example.presentation.ui.screens.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.presentation.ui.components.BusyIndicator
import com.example.presentation.ui.components.ConfirmDialog
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.FilesViewModel

/**
 * ============================================================================
 * FilesScreen — the real sandbox workspace file explorer
 * ============================================================================
 *
 * ADR-6 slice 1 (Design Closure 2026 UI-redesign track) — REDESIGNED on top
 * of the extracted FilesViewModel (the state left MainViewModel/UiState):
 *
 *  - directories are grouped FIRST (navigation before leaf content);
 *  - a sort control (name / size / last-modified) with a persistent choice;
 *  - the header carries live counts (folders / files / total size);
 *  - the feature's own diagnostic banner + error snackbar render locally
 *    (previously they round-tripped through the global MainViewModel state);
 *  - search, governed CREATE, DELETE with confirm, and the monospace editor
 *    with save are unchanged in behavior (and keep their testTags).
 */
@Composable
fun FilesScreen(
    filesViewModel: FilesViewModel,
    modifier: Modifier = Modifier
) {
    val state by filesViewModel.state.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var createFileOpen by rememberSaveable { mutableStateOf(false) }
    var deleteFileTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var sortOrder by rememberSaveable { mutableStateOf(FileSort.NAME) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // STAGE 7 — UPLOAD FROM PHONE STORAGE: the SAF picker (the platform
    // boundary). Resolved uris go through the governed transfer pipeline
    // (staging + atomic promotion + size limits + audit) into `uploads/`.
    // The launch itself is guarded the same way the chat pickers are — a
    // device without a DocumentsUI activity gets the honest message
    // instead of a crash (and never a dead-looking button).
    val deviceFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            filesViewModel.importFromDevice(uris.map { it.toString() })
        }
    }
    // STAGE 7 (user round 4 — the workspace-files FOLDER import): the SAF
    // tree picker — a picked directory rides the same governed serialize →
    // staging → atomic-promotion pipeline and lands as a REAL browsable
    // folder tree under uploads/ (NOT a zip blob, NOT a chat draft).
    val deviceFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) filesViewModel.importFolderFromDevice(uri.toString())
    }
    // STAGE 7 (user round 3 — the DocumentsUI-less device): the IN-APP
    // storage browser fallback. A device with no DocumentsUI activity gets
    // the REAL browser instead of the dead-end error; the selection rides
    // the SAME governed import (file:// uris through importFromDevice).
    var phoneBrowserOpen by rememberSaveable { mutableStateOf(false) }
    // USER ROUND 4: the browser now serves BOTH import kinds from this
    // screen — files (multi-select) and a whole folder (pick-current-
    // directory) — the mode the user chose in the import menu.
    var phoneBrowserMode by rememberSaveable {
        mutableStateOf(com.example.presentation.ui.components.PhoneStorageBrowserMode.FILES)
    }

    fun launchDeviceFilePickerSafely() {
        runCatching { deviceFilePicker.launch(arrayOf("*/*")) }
            .onFailure {
                android.util.Log.e("FilesScreen", "device file picker launch failed", it)
                phoneBrowserMode =
                    com.example.presentation.ui.components.PhoneStorageBrowserMode.FILES
                phoneBrowserOpen = true
            }
    }

    fun launchDeviceFolderPickerSafely() {
        runCatching { deviceFolderPicker.launch(null) }
            .onFailure {
                android.util.Log.e("FilesScreen", "device folder picker launch failed", it)
                phoneBrowserMode =
                    com.example.presentation.ui.components.PhoneStorageBrowserMode.FOLDER
                phoneBrowserOpen = true
            }
    }

    // The listing refreshes on every visit to this route and on every
    // active-project switch (FilesViewModel observes the workspace).
    LaunchedEffect(Unit) { filesViewModel.refreshFiles() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            filesViewModel.dismissError()
        }
    }

    // ---- Editor mode ----
    if (state.selectedFilePath != null && state.selectedFileContent != null) {
        FileEditor(
            path = state.selectedFilePath ?: "",
            initialContent = state.selectedFileContent ?: "",
            isLoading = state.isFileLoading,
            onBack = filesViewModel::closeFileEditor,
            onSave = { filesViewModel.saveFile(state.selectedFilePath ?: "", it) },
            onDelete = { deleteFileTarget = state.selectedFilePath }
        )
        deleteFileTarget?.let { target ->
            ConfirmDialog(
                title = "حذف الملف",
                message = "سيُحذف الملف «$target» من ملعب مساحة العمل نهائياً.",
                confirmLabel = "حذف",
                onConfirm = {
                    filesViewModel.deleteWorkspaceFile(target)
                    deleteFileTarget = null
                },
                onDismiss = { deleteFileTarget = null }
            )
        }
        return
    }

    // ---- Explorer mode ----
    val filteredFiles = if (searchQuery.isBlank()) state.files
    else state.files.filter { it.relativePath.contains(searchQuery, ignoreCase = true) }

    val directories = filteredFiles.filter { it.isDirectory }.sortedWith(sortOrder.comparator())
    val plainFiles = filteredFiles.filter { !it.isDirectory }.sortedWith(sortOrder.comparator())
    val totalSize = plainFiles.sumOf { it.sizeBytes }

    androidx.compose.material3.Scaffold(
        modifier = modifier.testTag("files_workspace_screen"),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SectionHeader(
                icon = Icons.Default.Folder,
                title = "ملفات ملعب مساحة العمل",
                subtitle = "ملعب معزول لكل مشروع — عمليات محكومة ومُدقّقة أمنياً • " +
                    "${directories.size} مجلد • ${plainFiles.size} ملف • ${formatSize(totalSize)}",
                trailing = {
                    SortMenu(sortOrder = sortOrder, onSelect = { sortOrder = it })
                    IconButton(
                        onClick = filesViewModel::refreshFiles,
                        modifier = Modifier.testTag("refresh_files_button")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "تحديث",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // STAGE 7 (user round 4): the import menu — FILES or a
                    // WHOLE FOLDER from the phone (both guarded; both fall
                    // back to the in-app browser on a DocumentsUI-less
                    // device). The folder import lands as a REAL browsable
                    // tree under uploads/.
                    ImportMenu(
                        enabled = !state.isImportingFromDevice,
                        busy = state.isImportingFromDevice,
                        onPickFiles = ::launchDeviceFilePickerSafely,
                        onPickFolder = ::launchDeviceFolderPickerSafely
                    )
                    IconButton(
                        onClick = { createFileOpen = true },
                        modifier = Modifier.testTag("btn_create_file")
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "ملف جديد",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            // STAGE 7 (user round 4 — the real file manager): the breadcrumb
            // bar — where the explorer IS and how to get one level out. The
            // up action is disabled at the sandbox root (an honest no-op
            // there, never a dead-looking button that pretends to work).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("files_breadcrumb_bar")
            ) {
                IconButton(
                    onClick = filesViewModel::navigateUp,
                    enabled = state.currentDirectory != null,
                    modifier = Modifier.testTag("btn_navigate_up")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "الخروج من المجلد",
                        tint = if (state.currentDirectory != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = state.currentDirectory
                        ?: "الجذر (ملعب المشروع)",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.testTag("breadcrumb_current_path")
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("ابحث في المسارات…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("input_file_search")
            )

            state.diagnosticBanner?.let { banner ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clickable { filesViewModel.dismissBanner() }
                        .testTag("files_diagnostic_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2
                        )
                    }
                }
            }

            if (state.isFileLoading && state.files.isEmpty()) {
                BusyIndicator("جاري قراءة ملفات الملعب…")
            }

            if (filteredFiles.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Folder,
                    title = if (searchQuery.isBlank()) "لا توجد ملفات في الملعب" else "لا نتائج مطابقة",
                    hint = if (searchQuery.isBlank())
                        "أنشئ ملفاً جديداً أو شغّل مهارة الهيكلة من قسم الملحقات لتوليد هيكل معماري."
                    else "جرّب مصطلحاً آخر أو امسح البحث.",
                    action = if (searchQuery.isBlank()) {
                        {
                            Button(onClick = { createFileOpen = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("إنشاء ملف")
                            }
                        }
                    } else null
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 4.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (directories.isNotEmpty()) {
                        item(key = "dirs_header") {
                            Text(
                                text = "المجلدات",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                        items(directories, key = { it.relativePath }) { file ->
                            // STAGE 7 (user round 4 — the real file manager):
                            // a directory row ENTERS the directory (the
                            // storage port's own subDirectory seam), never
                            // a file read that would honestly fail.
                            FileRow(
                                entry = file,
                                onOpen = { filesViewModel.openDirectory(file.relativePath) },
                                onDelete = { deleteFileTarget = file.relativePath }
                            )
                        }
                    }
                    if (plainFiles.isNotEmpty()) {
                        item(key = "files_header") {
                            Text(
                                text = "الملفات",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                            )
                        }
                        items(plainFiles, key = { it.relativePath }) { file ->
                            FileRow(
                                entry = file,
                                onOpen = { filesViewModel.openFile(file.relativePath) },
                                onDelete = { deleteFileTarget = file.relativePath }
                            )
                        }
                    }
                }
            }
        }
    }

    if (createFileOpen) {
        CreateFileDialog(
            onConfirm = { path ->
                filesViewModel.createFile(path, "")
                createFileOpen = false
            },
            onDismiss = { createFileOpen = false }
        )
    }

    // STAGE 7 (user round 3): the IN-APP phone-storage browser — the
    // working fallback when the SAF import picker cannot launch on this
    // device. Selection rides the SAME governed import path (uploads/).
    // USER ROUND 4: the sheet's MODE follows the import kind the user
    // chose (files / a whole folder) and the folder branch is REAL now
    // (it used to be a silent no-op that ate the pick).
    if (phoneBrowserOpen) {
        com.example.presentation.ui.components.PhoneStorageBrowserSheet(
            mode = phoneBrowserMode,
            fallbackNote = "تعذّر فتح منتقي النظام (لا يوجد تطبيق مستندات على هذا الجهاز) — هذا المتصفح الداخلي بديل كامل.",
            onConfirmFiles = { uris ->
                phoneBrowserOpen = false
                filesViewModel.importFromDevice(uris)
            },
            onConfirmFolder = { directoryUri ->
                phoneBrowserOpen = false
                filesViewModel.importFolderFromDevice(directoryUri)
            },
            onDismiss = { phoneBrowserOpen = false }
        )
    }

    deleteFileTarget?.let { target ->
        // STAGE 7 (user round 4): an honest per-kind confirmation — a
        // FOLDER deletion is recursive (the governed delete removes the
        // whole tree) and the message says exactly that. The editor's
        // delete target is always a FILE (directories never open the
        // editor), so directory detection is the CURRENT listing only.
        val isDirectory = state.files.any { it.relativePath == target && it.isDirectory }
        ConfirmDialog(
            title = if (isDirectory) "حذف المجلد" else "حذف الملف",
            message = if (isDirectory) {
                "سيُحذف المجلد «$target» بكل ما بداخله نهائياً من ملعب مساحة العمل الحالية."
            } else {
                "سيُحذف «$target» نهائياً من ملعب مساحة العمل الحالية."
            },
            confirmLabel = "حذف",
            onConfirm = {
                filesViewModel.deleteWorkspaceFile(target)
                deleteFileTarget = null
            },
            onDismiss = { deleteFileTarget = null }
        )
    }
}

/** Explorer sort orders (persisted via rememberSaveable by the caller). */
enum class FileSort(val label: String) {
    NAME("الاسم"),
    SIZE("الحجم"),
    MODIFIED("آخر تعديل");

    fun comparator(): java.util.Comparator<WorkspaceFileEntry> = when (this) {
        NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath.substringAfterLast('/') }
        SIZE -> compareByDescending<WorkspaceFileEntry> { it.sizeBytes }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath }
        MODIFIED -> compareByDescending<WorkspaceFileEntry> { it.lastModifiedMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath }
    }
}

@Composable
private fun SortMenu(sortOrder: FileSort, onSelect: (FileSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.testTag("btn_file_sort")
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Sort,
            contentDescription = "ترتيب: ${sortOrder.label}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        FileSort.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(
                        option.label,
                        fontWeight = if (option == sortOrder) FontWeight.Bold else FontWeight.Normal
                    )
                },
                onClick = {
                    onSelect(option)
                    expanded = false
                }
            )
        }
    }
}

/**
 * STAGE 7 (user round 4): the import menu — the phone-storage import kind
 * chooser (FILES multi-select or a whole FOLDER as a real browsable tree).
 * Both branches are guarded at launch (DocumentsUI-less devices fall back
 * to the in-app browser in the matching mode) and both ride the SAME
 * governed transfer pipeline.
 */
@Composable
private fun ImportMenu(
    enabled: Boolean,
    busy: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.testTag("btn_import_from_device")
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                Icons.Default.UploadFile,
                contentDescription = "استيراد من تخزين الهاتف",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.testTag("import_menu_dropdown")
    ) {
        DropdownMenuItem(
            text = { Text("ملفات من الهاتف…", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            onClick = {
                expanded = false
                onPickFiles()
            }
        )
        DropdownMenuItem(
            text = { Text("مجلد كامل من الهاتف…", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
            onClick = {
                expanded = false
                onPickFolder()
            }
        )
    }
}

@Composable
private fun FileRow(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("file_item_${entry.relativePath}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (entry.isDirectory) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(7.dp)
                        .size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.relativePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = entry.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!entry.isDirectory) {
                    Text(
                        text = formatSize(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // STAGE 7 (user round 4): the delete action is offered for
                // DIRECTORIES too — the governed delete is recursive and the
                // confirm dialog says exactly that (never a dead-looking
                // folder row that cannot be removed).
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("btn_delete_file_${entry.relativePath}")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = if (entry.isDirectory) "حذف المجلد" else "حذف",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEditor(
    path: String,
    initialContent: String,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var content by rememberSaveable(path) { mutableStateOf(initialContent) }
    var dirty by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("btn_close_editor")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (dirty) {
                StatusBadge("غير محفوظ", MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(6.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("btn_delete_open_file")) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "حذف الملف",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(
                onClick = {
                    onSave(content)
                    dirty = false
                },
                modifier = Modifier.testTag("btn_save_file")
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "حفظ",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (isLoading) {
            BusyIndicator("جاري قراءة الملف…")
        }

        OutlinedTextField(
            value = content,
            onValueChange = {
                content = it
                dirty = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("file_content_editor"),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
private fun CreateFileDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنشاء ملف جديد", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("المسار النسبي داخل الملعب") },
                    placeholder = { Text("مثال: notes/architecture.md") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_new_file_path")
                )
                Text(
                    text = "الكتابة تمر بسياسة مسارات مساحة العمل — الرموز العابرة للحدود (../) مرفوضة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("إنشاء", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
