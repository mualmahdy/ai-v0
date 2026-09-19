package com.example.presentation.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.presentation.state.UiState
import com.example.presentation.ui.navigation.WorkspaceRoutes

@Composable
fun HomeScreen(
    uiState: UiState,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val projectName = uiState.activeProject?.name?.takeIf { it.isNotBlank() }
        ?: "لا يوجد مشروع نشط"

    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "مركز العمل",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = projectName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "ابدأ محادثة جديدة",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "افتح الاستوديو للبدء مع النموذج والوكيل والسياق الحالي.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
                )
                Button(onClick = { onNavigate(WorkspaceRoutes.STUDIO) }) {
                    Icon(Icons.Default.Chat, contentDescription = null)
                    Text(" فتح الدردشة")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onNavigate(WorkspaceRoutes.PROJECTS) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Text(" المشاريع")
            }
            OutlinedButton(
                onClick = { onNavigate(WorkspaceRoutes.ACTIVITY) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null)
                Text(" النشاط")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onNavigate(WorkspaceRoutes.EXPLORER) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Text(" المستكشف")
            }
            OutlinedButton(
                onClick = { onNavigate(WorkspaceRoutes.SETTINGS) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text(" الإعدادات")
            }
        }
    }
}
