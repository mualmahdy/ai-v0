package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.db.entities.LongTermMemoryEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val memories by viewModel.memories.collectAsState()
    var selectedFilter by remember { mutableStateOf("all") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filtered = remember(memories, selectedFilter) {
        if (selectedFilter == "all") memories
        else memories.filter { it.memoryType == selectedFilter }
    }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_memory_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "إضافة ذاكرة")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(
                text = "نظام الذاكرة طويلة المدى (Episodic & Semantic Memory)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "تتبع التفضيلات وحالات التنفيذ (CBR Cases) مع الإحلال التلقائي وتثبيط التلاشي الزمني.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("all" to "الكل", "preference" to "تفضيلات", "case" to "حالات CBR", "insight" to "رؤى").forEach { (key, label) ->
                    FilterChip(
                        selected = selectedFilter == key,
                        onClick = { selectedFilter = key },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered) { mem ->
                    MemoryCard(mem)
                }
            }
        }
    }

    if (showAddDialog) {
        var content by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("preference") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("تسجيل ذاكرة طويلة المدى") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("نص الذاكرة أو التفضيل") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("نوع الذاكرة:", fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("preference", "case", "insight").forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = { Text(t, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (content.isNotBlank()) {
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                viewModel.memoryManager.addLongTermMemory(viewModel.activeProjectId.value, content, memoryType = type)
                            }
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun MemoryCard(mem: LongTermMemoryEntity) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = mem.memoryType,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (mem.status) {
                        "active" -> EmeraldSuccess.copy(alpha = 0.15f)
                        "superseded" -> AmberWarning.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = mem.status,
                        fontSize = 9.sp,
                        color = when (mem.status) {
                            "active" -> EmeraldSuccess
                            "superseded" -> AmberWarning
                            else -> MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = mem.content, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "التاريخ: ${mem.timestamp} | الأهمية: ${mem.importance}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
