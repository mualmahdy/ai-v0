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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.domain.core.session.TurnAttachment

/**
 * ============================================================================
 * ChatComposer — the conversation input (Chat Workspace Task 1 §12, extended
 * by CHAT CAPABILITIES Task 2 §3/§5)
 * ============================================================================
 *
 * IME (§4A): the composer root owns the SINGLE imePadding of the screen —
 * it is the only element that must hug the area above the keyboard, and the
 * shell consumes the navigation-bar share of the insets so this padding
 * lands EXACTLY at the keyboard's top edge (no gap, no double padding).
 *
 * TASK 2: the leading "+" button is the CAPABILITY ENTRY POINT (§3) and the
 * chips row stages the ATTACHMENT DRAFTS picked through SAF (§5) — each
 * chip removable before send, import progress honestly visible.
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
    /** Task-2: opens the capability menu (the "+" entry point — §3). */
    onOpenCapabilities: () -> Unit = {},
    /** Task-2: the staged attachment drafts (chips before send — §5). */
    attachmentDrafts: List<TurnAttachment> = emptyList(),
    /** Task-2: remove one attachment BEFORE send (§5). */
    onRemoveAttachment: (String) -> Unit = {},
    /** Task-2: honest import-in-progress marker (§5 progress state). */
    isImportingAttachment: Boolean = false,
    /**
     * FUNCTIONAL CLOSURE (§14): the attachment layer's honest error channel
     * (import failures, cleanup failures) — VISIBLE here, never swallowed.
     */
    attachmentError: String? = null
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

            // ---- Attachment chips (§5: staged drafts, removable) ----
            if (attachmentDrafts.isNotEmpty() || isImportingAttachment) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .testTag("attachment_chips_row")
                ) {
                    items(attachmentDrafts, key = { it.id }) { attachment ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.testTag("attachment_chip_${attachment.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = "مرفق",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = attachment.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1
                                )
                                IconButton(
                                    onClick = { onRemoveAttachment(attachment.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "إزالة المرفق «${attachment.name}»",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (isImportingAttachment) {
                        item(key = "importing_marker") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "جارٍ استيراد المرفق…",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- FUNCTIONAL CLOSURE (§14): the attachment error line — an
            // import/cleanup failure is shown to the user the moment it
            // happens (it used to be set in state and never rendered).
            if (attachmentError != null) {
                Text(
                    text = attachmentError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .testTag("attachment_error_line")
                )
            }

            // ---- FUNCTIONAL CLOSURE (§15): the attachment-only hint — the
            // send stays visibly blocked WITH the honest reason (Vision is not
            // operational in this version), never a mystery disabled button.
            if (attachmentDrafts.isNotEmpty() && value.isBlank() && !isExecuting) {
                Text(
                    text = "أضف نصاً يوضح المطلوب مع المرفقات — تحليل الصور (Vision) غير مفعّل في هذا الإصدار.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .testTag("attachment_only_hint")
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                // The capability entry point (Task 2 §3 — "+" opens the
                // categorized menu; progressive disclosure, not a giant list).
                Surface(
                    onClick = onOpenCapabilities,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("btn_open_capabilities")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "القدرات — إرفاق ملفات أو استدعاء أدوات ومهارات",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

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
                // FUNCTIONAL CLOSURE (§15): attachment-ONLY sends are refused —
                // Vision is not operational, so "chips without a question" is
                // a meaningless request. The send button requires TEXT; the
                // ViewModel enforces the same rule (defense in depth).
                val canSend = value.isNotBlank() && !isExecuting
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
                        onClick = { if (canSend) onSend() },
                        shape = CircleShape,
                        color = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("execute_prompt_button")
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "إرسال",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
