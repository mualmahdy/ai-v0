package com.example.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.presentation.state.UiState
import com.example.presentation.viewmodel.MainViewModel

@Composable
fun FilesWorkspaceScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("files_workspace_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "مستكشف مساحة العمل المعزولة (Workspace Files)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "بيئة تخزين معزولة تمنع Path Traversal وتتحكم في الوصول.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { viewModel.refreshFiles() },
                modifier = Modifier.testTag("refresh_files_button")
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "تحديث الملفات")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.selectedFilePath != null && state.selectedFileContent != null) {
            FileEditorView(
                filePath = state.selectedFilePath,
                content = state.selectedFileContent,
                onBack = { viewModel.openFile("") },
                onSave = { updated -> viewModel.saveFile(state.selectedFilePath, updated) }
            )
        } else {
            FileListView(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun FileListView(state: UiState, viewModel: MainViewModel) {
    if (state.isFileLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (state.workspaceFiles.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "مساحة العمل فارغة حالياً. يستطيع الوكلاء إنشاء الملفات تلقائياً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.workspaceFiles) { file ->
                WorkspaceFileItem(
                    entry = file,
                    onClick = {
                        if (!file.isDirectory) {
                            viewModel.openFile(file.relativePath)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WorkspaceFileItem(entry: WorkspaceFileEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("file_item_${entry.relativePath}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.relativePath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (entry.isDirectory) "مجلد" else "${entry.sizeBytes} بايت",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun FileEditorView(
    filePath: String,
    content: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    var editedContent by remember(content) { mutableStateOf(content) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "رجوع")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { onSave(editedContent) },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("حفظ التعديلات")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = editedContent,
            onValueChange = { editedContent = it },
            modifier = Modifier
                .fillMaxSize()
                .testTag("file_content_editor"),
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}
