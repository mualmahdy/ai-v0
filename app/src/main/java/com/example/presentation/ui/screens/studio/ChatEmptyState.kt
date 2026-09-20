package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ============================================================================
 * ChatEmptyState — the task-oriented empty conversation (Chat Workspace
 * Task 1 §13)
 * ============================================================================
 *
 * Replaces the old intro card that explained the ENGINE (CBR-MDP loops,
 * policies, event logs) to a person who just opened a chat. The empty state
 * now answers "how do I start?": a friendly greeting + a few REAL starter
 * actions — suggested prompts that FILL THE DRAFT (the user still sends),
 * and resuming a past durable session when one exists. No invented
 * capabilities.
 */
@Composable
fun ChatEmptyState(
    hasSessions: Boolean,
    onStarterPrompt: (String) -> Unit,
    onResumeSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Honest starter actions: they only prefill the composer — the user
    // stays in control of the actual send.
    val starters = listOf(
        "لخّص لي موضوعاً أتعلمه",
        "اكتب لي خطة عمل لمشروع صغير",
        "اشرح لي فكرة برمجية ببساطة"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .testTag("chat_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(16.dp)
                    .size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "كيف أساعدك؟",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "اكتب رسالتك للبدء، أو جرّب إحدى البدايات التالية:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        starters.forEach { starter ->
            Surface(
                onClick = { onStarterPrompt(starter) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .fillMaxWidth()
                    .testTag("starter_prompt")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = starter,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (hasSessions) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onResumeSessions,
                modifier = Modifier.testTag("btn_resume_session")
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("استئناف جلسة سابقة")
            }
        }
    }
}
