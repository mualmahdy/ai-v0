package com.example.presentation.ui.screens.dashboard

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.MainViewModel

/**
 * ============================================================================
 * DashboardScreen — "المزيد": a REAL secondary-sections hub
 * ============================================================================
 *
 * Replaces the old ModalBottomSheet with a full dashboard: live system
 * status (evidence-derived) + a grid of the workspace's capability surfaces,
 * each routing to a real backend subsystem. No dead entries.
 */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val suggestions by viewModel.activeSuggestions.collectAsState()

    val activeResourceCount = state.materializedResources.count { it.lifecycleState.name == "ENABLED" }

    Column(modifier = modifier.testTag("dashboard_screen")) {
        // ---- Live system status ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardStat(
                icon = Icons.Default.Dns,
                value = "${state.generalizedProviders.size}",
                label = "مزوّد مسجّل",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            DashboardStat(
                icon = Icons.Default.Verified,
                value = "$activeResourceCount",
                label = "مورد مفعّل",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
            DashboardStat(
                icon = Icons.Default.AutoAwesome,
                value = "${suggestions.size}",
                label = "اقتراح استباقي",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "أسطح القدرات",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        val sections = listOf(
            DashboardEntry(
                route = WorkspaceRoutes.PROVIDERS,
                icon = Icons.Default.Dns,
                title = "المزوّدون والنماذج",
                description = "ربط وإدارة مزودي LLM/البحث/التضمين، اكتشاف النماذج، والتحقق الحي",
                tag = "more_tab_providers"
            ),
            DashboardEntry(
                route = WorkspaceRoutes.TASKS,
                icon = Icons.Default.AccountTree,
                title = "المهام وخطط العمل",
                description = "بناء وتنفيذ خطط DAG متعددة الخطوات مع تبعيات حقيقية",
                tag = "more_tab_tasks"
            ),
            DashboardEntry(
                route = WorkspaceRoutes.DECISION,
                icon = Icons.Default.AutoAwesome,
                title = "ذكاء القرار (CBR-MDP)",
                description = "محرك القرار القائم على الحالات والتعلم المعزّز — اختيار الأفعال والأدوات",
                tag = "more_tab_decision"
            ),
            DashboardEntry(
                route = WorkspaceRoutes.RADAR,
                icon = Icons.Default.Radar,
                title = "رادار التطور",
                description = "مسح تقنيات، ومرشّحات تطوير القدرات عبر دورة حياة مُحكمة",
                tag = "more_tab_radar"
            ),
            DashboardEntry(
                route = WorkspaceRoutes.GOVERNANCE,
                icon = Icons.Default.Verified,
                title = "مرصد الحوكمة والاستدامة",
                description = "حالة القدرات المشتقة من الأدلة + الميزانية والتكلفة وحدود المعدل",
                tag = "more_tab_governance"
            ),
            DashboardEntry(
                route = WorkspaceRoutes.EXTENSIONS,
                icon = Icons.Default.Extension,
                title = "الملحقات والمهارات",
                description = "خوادم MCP، تنفيذ المهارات، والربط مع التكاملات الخارجية",
                tag = "more_tab_extensions"
            ),
            DashboardEntry(
                route = WorkspaceRoutes.SETTINGS,
                icon = Icons.Default.Settings,
                title = "الإعدادات",
                description = "سياسات الشبكة والاستقلالية، إدارة مساحات العمل، النموذج الدلالي",
                tag = "more_tab_settings"
            )
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 6.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sections, key = { it.route }) { entry ->
                SectionCard(entry = entry, onClick = { onNavigate(entry.route) })
            }
        }
    }
}

private data class DashboardEntry(
    val route: String,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val tag: String
)

@Composable
private fun DashboardStat(
    icon: ImageVector,
    value: String,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(tint.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(entry: DashboardEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(entry.tag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
