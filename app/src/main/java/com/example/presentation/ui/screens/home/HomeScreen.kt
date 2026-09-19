package com.example.presentation.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.core.session.ConversationSession
import com.example.presentation.ui.navigation.WorkspaceRoutes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * HomeScreen — the REAL Work Center (UI Design Closure, phase B — defect
 * D-05)
 * ============================================================================
 *
 * The previous Home was a static CTA grid with no live context. This is the
 * work center the package specifies — EVERY value is evidence-derived from
 * its owning feature ViewModel (wired by MainAppScreen):
 *
 *  - CONTEXT: the active workspace + the REAL active project (from the
 *    projects feature — never a workspace relabeled as a project);
 *  - ACT: start a new session (one tap into the Studio) or resume the
 *    most-recent session (durable registry);
 *  - RECENT SESSIONS: the live project-scoped session list (top three,
 *    most recent first) with an honest EMPTY state;
 *  - PROJECTS: current-project summary + direct management entry.
 *
 * No fabricated data anywhere: an unknown is rendered as unknown, an empty
 * list as empty. All copy is resource-backed Arabic (the D-12 rule for
 * rewritten screens).
 */
@Composable
fun HomeScreen(
    workspaceName: String,
    currentProjectName: String?,
    recentSessions: List<ConversationSession>,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Live context header ----
        item {
            Column(modifier = Modifier.testTag("home_context_header")) {
                Text(
                    text = stringResource(com.example.R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(com.example.R.string.home_welcome, workspaceName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Primary actions: new session / resume last ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(com.example.R.string.home_new_session_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentProjectName?.let {
                            stringResource(com.example.R.string.home_new_session_hint)
                        } ?: stringResource(com.example.R.string.home_no_project),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onNewSession,
                            modifier = Modifier.testTag("btn_home_new_session")
                        ) {
                            Icon(Icons.Default.AddComment, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(com.example.R.string.home_new_session_cta))
                        }
                        if (recentSessions.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { recentSessions.first().id.value.let(onOpenSession) },
                                modifier = Modifier.testTag("btn_home_resume_last")
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(com.example.R.string.home_resume_last))
                            }
                        }
                    }
                }
            }
        }

        // ---- Current project ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_project_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(com.example.R.string.home_current_project),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentProjectName
                                ?: stringResource(com.example.R.string.home_no_project),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = { onNavigate(WorkspaceRoutes.PROJECTS) },
                        modifier = Modifier.testTag("btn_home_projects")
                    ) {
                        Text(stringResource(com.example.R.string.home_projects_cta))
                    }
                }
            }
        }

        // ---- Recent sessions (evidence-derived, honest empty state) ----
        item {
            Text(
                text = stringResource(com.example.R.string.home_recent_sessions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (recentSessions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_sessions_empty"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(com.example.R.string.home_recent_sessions_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(recentSessions.take(3), key = { it.id.value }) { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_session_${session.id.value}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    onClick = { onOpenSession(session.id.value) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = stringResource(
                                com.example.R.string.home_open_session,
                                session.title
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(com.example.R.string.home_turns_count, session.turnCount) +
                                    " · " + formatDate(session.lastActiveAtEpochMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- Quick access ----
        item {
            Text(
                text = stringResource(com.example.R.string.home_quick_access),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onNavigate(WorkspaceRoutes.EXPLORER) },
                    modifier = Modifier.weight(1f).testTag("btn_home_explorer")
                ) {
                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(com.example.R.string.home_explorer_cta))
                }
                OutlinedButton(
                    onClick = { onNavigate(WorkspaceRoutes.MORE) },
                    modifier = Modifier.weight(1f).testTag("btn_home_more")
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(com.example.R.string.home_more_cta))
                }
            }
        }
    }
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale("ar")).format(Date(epochMs))
