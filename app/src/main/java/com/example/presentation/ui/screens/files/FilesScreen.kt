package com.example.presentation.ui.screens.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.ui.components.BusyIndicator
import com.example.presentation.ui.components.ConfirmDialog
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.ui.components.StatusBadge
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * FilesScreen — the real sandbox workspace file explorer
 * ============================================================================
 *
 * Previously a bare flat list + raw editor. Now: live search/filter, file
 * CREATION (governed write into the sandbox), file DELETION (with confirm),
 * folder/file semantics, size + mtime metadata, and a monospace editor with
 * save — all bound to the fail-closed active-workspace project contract.
 */
@Composable
fun FilesScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var createFileOpen by rememberSaveable { mutableStateOf(false) }
    var deleteFileTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editorContent by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshFiles() }

    // ---- Editor mode ----
    if (state.selectedFilePath != null && state.selectedFileContent != null) {
        FileEditor(
            path = state.selectedFilePath ?: "",
            initialContent = state.selectedFileContent ?: "",
            isLoading = state.isFileLoading,
            onBack = viewModel::closeFileEditor,
            onSave = { viewModel.saveFile(state.selectedFilePath ?: "", it) },
            onDelete = { deleteFileTarget = state.selectedFilePath }
        )
        deleteFileTarget?.let { target ->
            ConfirmDialog(
                title = "حذف الملف",
                message = "سيُحذف الملف «$target» من ملعب مساحة العمل نهائياً.",
                confirmLabel = "حذف",
                onConfirm = {
                    viewModel.deleteWorkspaceFile(target)
                    deleteFileTarget = null
                },
                onDismiss = { deleteFileTarget = null }
            )
        }
        return
    }

    // ---- Explorer mode ----
    val filteredFiles = if (searchQuery.isBlank()) state.workspaceFiles
    else state.workspaceFiles.filter { it.relativePath.contains(searchQuery, ignoreCase = true) }

    Column(modifier = modifier.testTag("files_workspace_screen")) {
        SectionHeader(
            icon = Icons.Default.Folder,
            title = "ملفات ملعب مساحة العمل",
            subtitle = "ملعب معزول لكل مشروع — عمليات محكومة ومُدقّقة أمنياً",
            trailing = {
                IconButton(
                    onClick = viewModel::refreshFiles,
                    modifier = Modifier.testTag("refresh_files_button")
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "تحديث",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

        if (state.isFileLoading && state.workspaceFiles.isEmpty()) {
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
                items(filteredFiles, key = { it.relativePath }) { file ->
                    FileRow(
                        entry = file,
                        onOpen = { viewModel.openFile(file.relativePath) },
                        onDelete = { deleteFileTarget = file.relativePath }
                    )
                }
            }
        }
    }

    if (createFileOpen) {
        CreateFileDialog(
            onConfirm = { path ->
                viewModel.createWorkspaceFile(path, "")
                createFileOpen = false
            },
            onDismiss = { createFileOpen = false }
        )
    }

    deleteFileTarget?.let { target ->
        ConfirmDialog(
            title = "حذف الملف",
            message = "سيُحذف «$target» نهائياً من ملعب مساحة العمل الحالية.",
            confirmLabel = "حذف",
            onConfirm = {
                viewModel.deleteWorkspaceFile(target)
                deleteFileTarget = null
            },
            onDismiss = { deleteFileTarget = null }
        )
    }
}

@Composable
private fun FileRow(
    entry: com.example.domain.core.storage.WorkspaceFileEntry,
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
                if (!entry.isDirectory) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("btn_delete_file_${entry.relativePath}")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp)
                        )
                    }
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
