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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.example.domain.core.session.ChatMode
import com.example.domain.core.session.TurnAttachment
import com.example.presentation.state.ContextWindowGauge

/**
 * ============================================================================
 * ChatComposer — the COMMAND SURFACE of the conversation (Chat Workspace
 * Task 1 §12, CHAT CAPABILITIES Task 2 §3/§5, UI POLISH §7)
 * ============================================================================
 *
 * IME (§4A): the composer root owns the SINGLE imePadding of the screen —
 * it is the only element that must hug the area above the keyboard, and the
 * shell consumes the navigation-bar share of the insets so this padding
 * lands EXACTLY at the keyboard's top edge (no gap, no double padding).
 *
 * THE COMMAND SURFACE (§7), top to bottom:
 *  1. the ATTACHMENT DRAFTS chips row (SAF-picked, removable before send);
 *  2. the TRANSIENT context gauge — only while the conversation's context
 *     window is filling (NEAR_FULL/CRITICAL) or an execution is live;
 *  3. the INPUT ROW — [+] the SINGLE capability hub entry (attachments,
 *     search, knowledge, skills, tools, MCP… one door, frontier pattern)
 *     | the field | the VOICE placeholder (disabled with the honest
 *     reason — UNAVAILABLE ≠ HIDDEN) | Send/Stop.
 *
 * UNIFICATION HOTFIX (the duplicated capabilities entries): the composer
 * previously carried TWO entries into the same capability space — the
 * "القدرات" chip in a strip ABOVE the input row and a [+] quick-attach
 * beside the field. Both launched pickers on divergent paths and both
 * crashed the app (see the import-path hotfix). Per the frontier
 * composer pattern (Claude / ChatGPT), the [+] is now the ONE entry: it
 * always opens the categorized hub (whose rows each carry their own
 * honest availability), and the duplicated strip is gone.
 *
 * The VOICE placeholder (§3/§7): speech has NO runtime execution path in
 * this version (the provider architecture knows the service type; nothing
 * executes it). The mic is therefore VISIBLE but non-executable, at reduced
 * opacity, with the real reason in its accessibility label — never hidden,
 * never a dead button that pretends to work.
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
    /** UNIFICATION: the [+] entry — opens the capability hub (ONE door). */
    onQuickAttach: () -> Unit = {},
    /** UI POLISH §7: opens the conversation-context sheet (agent/model). */
    onOpenContext: () -> Unit = {},
    /** UI POLISH §7: the live binding the agent/model chip summarizes. */
    chatMode: ChatMode = ChatMode.QUICK_CHAT,
    selectedModelDisplayName: String? = null,
    activeAgentName: String? = null,
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
    attachmentError: String? = null,
    /**
     * FRONTIER CONTEXT WINDOW: the conversation's real token gauge —
     * measured usage + the governance layer's REAL remaining budget.
     * Unknown figures hide the gauge entirely (never a fabricated bar).
     */
    contextTokensUsed: Int = 0,
    contextTokensRemaining: Int = 0
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
                                    maxLines = 1,
                                    // CHAT FINAL CLOSURE (§13): a long filename
                                    // ellipsizes (never breaks the chips row).
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                                // CHAT FINAL CLOSURE (§12 touch targets): the
                                // remove control keeps the default 48dp
                                // minimum interactive size — the previous
                                // explicit .size(24.dp) made a 14dp icon the
                                // de-facto touch target.
                                IconButton(
                                    onClick = { onRemoveAttachment(attachment.id) }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "إزالة المرفق «${attachment.name}»",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
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

            // ---- CLOSURE §13 (Composer Redesign) + UNIFICATION HOTFIX: the
            // composer shows INPUT + PRIMARY ACTIONS permanently. The
            // duplicated "القدرات" strip is REMOVED — the [+] beside the
            // field is the single hub entry. The gauge is TRANSIENT: it
            // appears only when the window fills (NEAR_FULL/CRITICAL) or an
            // execution is live — the composer never becomes a permanent
            // control panel.
            val gaugeVisible = ContextWindowGauge
                .isKnown(contextTokensUsed, contextTokensRemaining) &&
                    (ContextWindowGauge.severity(contextTokensUsed, contextTokensRemaining) !=
                            ContextWindowGauge.Severity.NORMAL || isExecuting)
            if (gaugeVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .testTag("composer_context_strip")
                ) {
                    // ---- FRONTIER CONTEXT WINDOW (TRANSIENT per §13): the honest
                    // token gauge — only while numbers exist AND the window is
                    // filling (or an execution is live); tinted by severity.
                    val severity = ContextWindowGauge.severity(contextTokensUsed, contextTokensRemaining)
                    val gaugeColor = when (severity) {
                        ContextWindowGauge.Severity.NORMAL -> MaterialTheme.colorScheme.primary
                        ContextWindowGauge.Severity.NEAR_FULL -> MaterialTheme.colorScheme.tertiary
                        ContextWindowGauge.Severity.CRITICAL -> MaterialTheme.colorScheme.error
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("context_window_gauge")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = ContextWindowGauge.label(contextTokensUsed, contextTokensRemaining),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = {
                                    ContextWindowGauge.fraction(contextTokensUsed, contextTokensRemaining)
                                },
                                color = gaugeColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .width(72.dp)
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
            // ---- §7 + UNIFICATION: the INPUT ROW — [+] hub | field | voice | send ----
            Row(verticalAlignment = Alignment.Bottom) {
                // The [+] capability-hub entry (UNIFICATION HOTFIX): one door
                // for attachments AND every conversation capability, always
                // tappable — the hub's own rows carry the honest per-row
                // availability + reasons (the previously gated + ALSO
                // duplicated the hub above the field; one entry now).
                Surface(
                    onClick = onQuickAttach,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "القدرات — إرفاق ملفات واستدعاء الأدوات" }
                        .testTag("btn_quick_attach")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
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
                        .heightIn(max = 168.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                                if (value.isNotBlank() && !isExecuting) onSend()
                                true
                            } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && isExecuting) {
                                onCancel()
                                true
                            } else {
                                false
                            }
                        }
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

                // ---- §3/§7: the VOICE placeholder — speech has no runtime
                // execution path in this version, so the mic stays VISIBLE
                // (reduced opacity, NO click action) with the honest reason
                // in its accessibility label. UNAVAILABLE ≠ HIDDEN. (It is
                // non-interactive by design — no touch target is required,
                // and the semantics node stays discoverable.)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription =
                                "الإدخال الصوتي — غير متاح في هذا الإصدار (لا يوجد مسار تنفيذ للصوت)"
                        }
                        .testTag("voice_input_placeholder")
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))

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
                                contentDescription = "إيقاف التنفيذ",
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

