package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.db.entities.ProjectEntity
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTopBar(
    projects: List<ProjectEntity>,
    activeProjectId: Long,
    isOfflineMode: Boolean,
    onProjectSelected: (Long) -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLayoutEditor: () -> Unit
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    val currentProject = projects.find { it.id == activeProjectId } ?: projects.firstOrNull()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Brand & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "AI-V0 Core",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = "AI-V0 Ultimate",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Project Selector Dropdown trigger
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { projectMenuExpanded = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = currentProject?.name ?: "المشروع الافتراضي",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "قائمة المشاريع",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = projectMenuExpanded,
                            onDismissRequest = { projectMenuExpanded = false }
                        ) {
                            projects.forEach { proj ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = proj.name,
                                            fontWeight = if (proj.id == activeProjectId) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        onProjectSelected(proj.id)
                                        projectMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (proj.isDefault) Icons.Default.FolderSpecial else Icons.Default.Folder,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Actions & Offline Toggle Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Offline / Online Status Pill
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isOfflineMode) AmberWarning.copy(alpha = 0.15f) else EmeraldSuccess.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isOfflineMode) AmberWarning else EmeraldSuccess
                        ),
                        modifier = Modifier
                            .clickable { onToggleOffline(!isOfflineMode) }
                            .testTag("offline_toggle_pill")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOfflineMode) AmberWarning else EmeraldSuccess)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOfflineMode) "Offline" else "Online",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isOfflineMode) AmberWarning else EmeraldSuccess
                            )
                        }
                    }

                    // Terminal Quick Button
                    IconButton(
                        onClick = onOpenTerminal,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("terminal_quick_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Terminal",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Layout Customizer Button
                    IconButton(
                        onClick = onOpenLayoutEditor,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("layout_customizer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DashboardCustomize,
                            contentDescription = "تخصيص الواجهة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
