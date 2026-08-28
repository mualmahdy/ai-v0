package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.ui.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.launch

@Composable
fun CodeEditorPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val files by viewModel.workspaceFiles.collectAsState()
    val activeFile by viewModel.currentOpenFile.collectAsState()
    val content by viewModel.fileContent.collectAsState()
    val diff by viewModel.fileDiff.collectAsState()

    var editableText by remember(content) { mutableStateOf(content) }
    var executionResult by remember { mutableStateOf<String?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var showDiffDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Files Switcher Tab Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.refreshFiles() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث الملفات", modifier = Modifier.size(16.dp))
            }

            files.forEach { file ->
                val isSelected = file.relativePath == activeFile
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.loadFileContent(file.relativePath) },
                    label = {
                        Text(
                            text = file.name,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Editor Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "الملف: ${activeFile ?: "لا يوجد"}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Diff Button
                OutlinedButton(
                    onClick = {
                        viewModel.checkFileDiff(editableText)
                        showDiffDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Difference, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Diff", fontSize = 11.sp)
                }

                // Run Code Button
                Button(
                    onClick = {
                        val ext = (activeFile ?: "kt").substringAfterLast(".", "kt")
                        isExecuting = true
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val out = viewModel.executionEngine.executeCode(viewModel.activeProjectId.value, ext, editableText)
                            executionResult = if (out.stderr.isNotEmpty()) "ERROR:\n${out.stderr}" else out.stdout
                            isExecuting = false
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تشغيل الكود", fontSize = 11.sp)
                }

                // Save Button
                Button(
                    onClick = { viewModel.saveCurrentFile(editableText) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حفظ", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Code Editor Canvas
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Slate950,
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate850),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            BasicTextField(
                value = editableText,
                onValueChange = { editableText = it },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("code_editor_text_field")
            )
        }

        // Execution Output Console
        if (executionResult != null || isExecuting) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Slate900,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("مخرجات التنفيذ (Console Output):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { executionResult = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = if (isExecuting) "جاري تشغيل الكود في بيئة العزل..." else executionResult ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    if (showDiffDialog) {
        AlertDialog(
            onDismissRequest = { showDiffDialog = false },
            title = { Text("مقارنة الفروقات (File Diff)") },
            text = {
                Text(
                    text = diff ?: "لا توجد تغييرات بين المحتوى الحالي والنسخة المحفوظة.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            },
            confirmButton = {
                Button(onClick = { showDiffDialog = false }) { Text("إغلاق") }
            }
        )
    }
}
