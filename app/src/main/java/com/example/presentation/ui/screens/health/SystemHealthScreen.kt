package com.example.presentation.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.presentation.ui.components.EmptyState
import com.example.presentation.ui.components.SectionHeader
import com.example.presentation.viewmodel.SystemHealthViewModel

/**
 * ============================================================================
 * SystemHealthScreen — CLOSURE §11 (Repair / Recovery UX)
 * ============================================================================
 * The user-facing System Health surface for the repair/reconciliation
 * center (previously a backend service with ZERO callers):
 *   - health summary + full scan action;
 *   - every detected condition with an explain + REPAIR (deterministic,
 *     auditable, VERIFIED by re-detection — the verification result is
 *     shown honestly);
 *   - INTERRUPTED EXECUTIONS bound to their EXACT task identity, each with
 *     the honest choice: RESUME (real durable resume) or reconcile.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SystemHealthScreen(
    viewModel: SystemHealthViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.diagnosticBanner) {
        state.diagnosticBanner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeDiagnostic()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = modifier.testTag("system_health_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                actions = {
                    IconButton(
                        onClick = { viewModel.scan() },
                        modifier = Modifier.testTag("btn_rescan_health")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.health_rescan)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ===================== HEALTH SUMMARY =====================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("health_summary_card"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (state.healthy) Icons.Default.CheckCircle else Icons.Default.Healing,
                            contentDescription = null,
                            tint = if (state.healthy) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = if (state.isScanning) stringResource(R.string.health_scanning)
                            else if (state.healthy) stringResource(R.string.health_all_good)
                            else stringResource(R.string.health_issues_found, state.conditions.size),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        if (!state.healthy) {
                            Text(
                                text = stringResource(R.string.health_summary_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // ===================== INTERRUPTED EXECUTIONS =====================
            if (state.interruptedExecutions.isNotEmpty()) {
                item {
                    SectionHeader(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(
                            R.string.health_interrupted_title,
                            state.interruptedExecutions.size
                        ),
                        subtitle = stringResource(R.string.health_interrupted_subtitle)
                    )
                }
                itemsIndexedCompat(state.interruptedExecutions) { _, execution ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("interrupted_execution_card"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = execution.rawPrompt.ifBlank {
                                    stringResource(R.string.health_task_no_prompt)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                modifier = Modifier.testTag("interrupted_prompt")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.health_task_identity,
                                    execution.taskId
                                ) + (execution.workspaceId?.let { " • $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                Button(
                                    onClick = { viewModel.resumeInterruptedExecution(execution.taskId) },
                                    enabled = state.resumingTaskId != execution.taskId,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("btn_resume_execution"),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                                ) {
                                    if (state.resumingTaskId == execution.taskId) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .height(16.dp)
                                                .width(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        stringResource(R.string.health_resume),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        viewModel.repairCondition(
                                            com.example.application.repair.RepairCenterService.Condition(
                                                code = "STALE_EXECUTION",
                                                description = "",
                                                affectedIds = listOf(execution.taskId)
                                            )
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("btn_reconcile_execution"),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.health_reconcile),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ===================== CONDITIONS =====================
            if (state.conditions.isNotEmpty()) {
                item {
                    SectionHeader(
                        icon = Icons.Default.HealthAndSafety,
                        title = stringResource(R.string.health_conditions_title),
                        subtitle = stringResource(R.string.health_conditions_subtitle)
                    )
                }
                itemsIndexedCompat(state.conditions.filter { it.code != "STALE_EXECUTION" }) { _, condition ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("health_condition_card"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = condition.code,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.testTag("condition_code")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = condition.description,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("condition_description")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { viewModel.repairCondition(condition) },
                                modifier = Modifier.testTag("btn_repair_condition")
                            ) {
                                Icon(
                                    Icons.Default.Healing,
                                    contentDescription = null,
                                    modifier = Modifier.height(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.health_repair))
                            }
                        }
                    }
                }
            }

            if (!state.isScanning && state.conditions.isEmpty() &&
                state.interruptedExecutions.isEmpty()
            ) {
                item {
                    EmptyState(
                        icon = Icons.Default.CheckCircle,
                        title = stringResource(R.string.health_all_good),
                        hint = stringResource(R.string.health_all_good_hint)
                    )
                }
            }
        }
    }
}

/** Local items helper with per-item keys (avoids the count-based overload clash). */
private fun <T> LazyListScope.itemsIndexedCompat(
    list: List<T>,
    key: (T) -> Any = { it.hashCode() },
    itemContent: @Composable (Int, T) -> Unit
) {
    items(count = list.size, key = { index -> key(list[index]) }) { index ->
        itemContent(index, list[index])
    }
}
