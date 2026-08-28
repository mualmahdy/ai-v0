package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.db.entities.WorkspaceComponentEntity

fun getIconForComponent(iconName: String): ImageVector {
    return when (iconName) {
        "chat" -> Icons.Default.ChatBubbleOutline
        "agent" -> Icons.Default.SupportAgent
        "code" -> Icons.Default.Code
        "terminal" -> Icons.Default.Terminal
        "workflow" -> Icons.Default.AccountTree
        "knowledge" -> Icons.Default.LibraryBooks
        "memory" -> Icons.Default.Psychology
        "providers" -> Icons.Default.Dns
        "diagnostics" -> Icons.Default.Analytics
        "settings" -> Icons.Default.Settings
        else -> Icons.Default.Widgets
    }
}

@Composable
fun WorkspaceNavTabs(
    components: List<WorkspaceComponentEntity>,
    activeTab: String,
    onTabSelected: (String) -> Unit
) {
    val visibleComponents = components.filter { it.isVisible }.sortedBy { it.displayOrder }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleComponents.forEach { comp ->
                val isSelected = comp.componentId == activeTab
                FilterChip(
                    selected = isSelected,
                    onClick = { onTabSelected(comp.componentId) },
                    label = {
                        Text(
                            text = comp.title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = getIconForComponent(comp.iconName),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.testTag("nav_tab_${comp.componentId}")
                )
            }
        }
    }
}
