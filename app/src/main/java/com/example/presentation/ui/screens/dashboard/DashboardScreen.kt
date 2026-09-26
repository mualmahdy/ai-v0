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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.presentation.ui.navigation.WorkspaceRoutes
import com.example.presentation.viewmodel.MainViewModel
import com.example.presentation.viewmodel.ProvidersViewModel

/**
 * ============================================================================
 * DashboardScreen — "المزيد": the three-group capability center (UI Design
 * Closure, phase C — defect D-06)
 * ============================================================================
 *
 * The flat eight-card grid becomes the package's taxonomy — every entry
 * routes to a REAL backend surface (no dead entries, capabilities preserved):
 *
 *  - مساحة العمل (Workspace): Explorer / Knowledge / Files / Tasks & Workflows
 *  - الذكاء (Intelligence): Providers / Decision / Radar
 *  - الحوكمة والنظام (Governance & System): Governance / Extensions / Settings
 *
 * The agents capability deliberately has no card here: it has no dedicated
 * route — its surfaces are the Studio picker and the Explorer's agents row
 * (an honest entry would be a dead link; documented as DEFERRED in the
 * closure record instead of faked).
 *
 * The live system-status row stays evidence-derived from the providers
 * feature VM (its owner) — registered providers / enabled resources /
 * registered resources, never fabricated numbers.
 */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    // ADR-6 slice 5: the provider stats (registered providers, enabled /
    // registered resources) read the providers feature VM — their owner.
    providersViewModel: ProvidersViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val providersState by providersViewModel.state.collectAsState()

    val activeResourceCount = providersState.materializedResources.count {
        it.lifecycleState.name == "ENABLED"
    }

    Column(modifier = modifier.testTag("dashboard_screen")) {
        // ---- Live system status (evidence-derived) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardStat(
                icon = Icons.Default.Dns,
                value = stringResource(R.string.more_stats_providers, providersState.generalizedProviders.size),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                tag = "more_stat_providers"
            )
            DashboardStat(
                icon = Icons.Default.Verified,
                value = stringResource(R.string.more_stats_enabled, activeResourceCount),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
                tag = "more_stat_enabled"
            )
            DashboardStat(
                icon = Icons.Default.AutoAwesome,
                value = stringResource(R.string.more_stats_registered, providersState.materializedResources.size),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                tag = "more_stat_registered"
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 6.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ---- Group 1: Workspace ----
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                GroupHeader(
                    icon = Icons.Default.FolderOpen,
                    title = stringResource(R.string.more_group_workspace),
                    hint = stringResource(R.string.more_group_workspace_hint),
                    tag = "more_group_workspace"
                )
            }
            items(
                listOf(
                    DashboardEntry(WorkspaceRoutes.EXPLORER, Icons.Default.Explore, R.string.more_explorer_title, R.string.more_explorer_desc, "more_tab_explorer"),
                    DashboardEntry(WorkspaceRoutes.KNOWLEDGE, Icons.Default.MenuBook, R.string.more_knowledge_title, R.string.more_knowledge_desc, "more_tab_knowledge"),
                    DashboardEntry(WorkspaceRoutes.FILES, Icons.AutoMirrored.Filled.InsertDriveFile, R.string.more_files_title, R.string.more_files_desc, "more_tab_files"),
                    DashboardEntry(WorkspaceRoutes.TASKS, Icons.Default.AccountTree, R.string.more_tasks_title, R.string.more_tasks_desc, "more_tab_tasks")
                ),
                key = { it.route }
            ) { entry ->
                SectionCard(entry = entry, onClick = { onNavigate(entry.route) })
            }

            // ---- Group 2: Intelligence ----
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                GroupHeader(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.more_group_intelligence),
                    hint = stringResource(R.string.more_group_intelligence_hint),
                    tag = "more_group_intelligence"
                )
            }
            items(
                listOf(
                    DashboardEntry(WorkspaceRoutes.PROVIDERS, Icons.Default.Dns, R.string.more_providers_title, R.string.more_providers_desc, "more_tab_providers"),
                    DashboardEntry(WorkspaceRoutes.DECISION, Icons.Default.AutoAwesome, R.string.more_decision_title, R.string.more_decision_desc, "more_tab_decision"),
                    DashboardEntry(WorkspaceRoutes.RADAR, Icons.Default.Radar, R.string.more_radar_title, R.string.more_radar_desc, "more_tab_radar")
                ),
                key = { it.route }
            ) { entry ->
                SectionCard(entry = entry, onClick = { onNavigate(entry.route) })
            }

            // ---- Group 3: Governance & System ----
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                GroupHeader(
                    icon = Icons.Default.Verified,
                    title = stringResource(R.string.more_group_governance),
                    hint = stringResource(R.string.more_group_governance_hint),
                    tag = "more_group_governance"
                )
            }
            items(
                listOf(
                    DashboardEntry(WorkspaceRoutes.GOVERNANCE, Icons.Default.Verified, R.string.more_governance_title, R.string.more_governance_desc, "more_tab_governance"),
                    DashboardEntry(WorkspaceRoutes.EXTENSIONS, Icons.Default.Extension, R.string.more_extensions_title, R.string.more_extensions_desc, "more_tab_extensions"),
                    DashboardEntry(WorkspaceRoutes.HEALTH, Icons.Default.Healing, R.string.more_health_title, R.string.more_health_desc, "more_tab_health"),
                    DashboardEntry(WorkspaceRoutes.SETTINGS, Icons.Default.Settings, R.string.more_settings_title, R.string.more_settings_desc, "more_tab_settings")
                ),
                key = { it.route }
            ) { entry ->
                SectionCard(entry = entry, onClick = { onNavigate(entry.route) })
            }
        }
    }
}

private data class DashboardEntry(
    val route: String,
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val tag: String
)

@Composable
private fun GroupHeader(
    icon: ImageVector,
    title: String,
    hint: String,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DashboardStat(
    icon: ImageVector,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    tag: String
) {
    Card(
        modifier = modifier.testTag(tag),
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
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint
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
                text = stringResource(entry.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(entry.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
