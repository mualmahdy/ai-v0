package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * ============================================================================
 * ChatComposer — the conversation input foundation (Chat Workspace Task 1
 * §12)
 * ============================================================================
 *
 * The composer stays focused on TEXT in this task — input, send, cancel
 * during execution, and clear draft — built as a foundation that later
 * capabilities (attachments/tools/skills/MCP — Task 2) can extend through
 * [leadingSlot] WITHOUT restructuring this surface.
 *
 * IME (§4A): the composer root owns the SINGLE imePadding of the screen —
 * it is the only element that must hug the area above the keyboard, and the
 * shell consumes the navigation-bar share of the insets so this padding
 * lands EXACTLY at the keyboard's top edge (no gap, no double padding).
 */
@Composable
fun ChatComposer(
    value: String,
    isExecuting: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClearDraft: () -> Unit,
    modifier: Modifier = Modifier,
    /** Task-2 extension point (attachments/tools/skills) — unused today. */
    leadingSlot: @Composable () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .testTag("chat_composer")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // The future-capabilities slot (empty in Task 1 — progressive
                // disclosure lands with Task 2, nothing fake is rendered).
                leadingSlot()

                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("اكتب رسالتك…") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("prompt_text_field"),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 5,
                    trailingIcon = if (value.isNotEmpty() && !isExecuting) {
                        {
                            androidx.compose.material3.IconButton(
                                onClick = onClearDraft,
                                modifier = Modifier.testTag("btn_clear_draft")
                            ) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "مسح المسودة",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        null
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isExecuting) {
                    Surface(
                        onClick = onCancel,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("cancel_execution_button")
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "إلغاء التنفيذ",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = { if (value.isNotBlank()) onSend() },
                        shape = CircleShape,
                        color = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("execute_prompt_button")
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "إرسال",
                                tint = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
