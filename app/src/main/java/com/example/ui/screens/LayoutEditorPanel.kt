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
import com.example.data.local.db.entities.WorkspaceComponentEntity
import com.example.ui.components.getIconForComponent
import com.example.ui.viewmodel.WorkspaceViewModel

@Composable
fun LayoutEditorPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val components by viewModel.workspaceComponents.collectAsState()
    val sorted = remember(components) { components.sortedBy { it.displayOrder } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "تخصيص الواجهة الذكية (Workspace Layout Studio)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "الواجهة مبنية بطريقة Data-Driven بالكامل. يمكنك إظهار، إخفاء، وإعادة ترتيب الألواح بدون إعادة بناء التطبيق.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sorted) { item ->
                ComponentConfigCard(
                    item = item,
                    onToggleVisibility = { viewModel.toggleComponentVisibility(item.componentId) },
                    onMoveUp = { viewModel.moveComponent(item.componentId, true) },
                    onMoveDown = { viewModel.moveComponent(item.componentId, false) }
                )
            }
        }
    }
}

@Composable
fun ComponentConfigCard(
    item: WorkspaceComponentEntity,
    onToggleVisibility: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = getIconForComponent(item.iconName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("المعرف: ${item.componentId} | الترتيب: #${item.displayOrder}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "تحريك لأعلى", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "تحريك لأسفل", modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = item.isVisible,
                    onCheckedChange = { onToggleVisibility() },
                    modifier = Modifier.testTag("toggle_${item.componentId}")
                )
            }
        }
    }
}
