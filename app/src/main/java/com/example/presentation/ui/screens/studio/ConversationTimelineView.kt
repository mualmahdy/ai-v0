package com.example.presentation.ui.screens.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.state.ChatAutoScrollPolicy
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ExecutionPhase
import com.example.presentation.state.LiveExecutionState
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ConversationTimeline — the message stream (Chat Workspace Task 1)
 * ============================================================================
 *
 * The conversation IS the screen: user messages, assistant results (rich
 * markdown), the execution lifecycle attached to the last user message, and
 * the auto-scroll contract — follow the stream while the user is at the
 * bottom, never yank a reading user, and offer a "new messages" affordance
 * instead (§9). [sendSignal] is a counter the workspace increments when the
 * user sends a message — the timeline then returns to the bottom
 * INTENTIONALLY (never as a blind reaction to streaming updates).
 */
@Composable
fun ConversationTimeline(
    timeline: List<ChatEntry>,
    liveExecution: LiveExecutionState?,
    streamText: String,
    sendSignal: Int,
    emptyContent: @Composable () -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // ---- Auto-scroll policy state (§9) ----
    var isFollowing by remember { mutableStateOf(true) }
    var hasNewContent by remember { mutableStateOf(false) }
    var pendingSendScroll by remember { mutableStateOf(false) }

    // The list content the policy reacts to: entries + the live block.
    val showLiveBlock = liveExecution != null
    val itemCount = timeline.size + if (showLiveBlock) 1 else 0

    // Track whether the user is near the bottom on every scroll/layout pass.
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            ChatAutoScrollPolicy.isNearBottom(lastVisible, info.totalItemsCount)
        }
    }
    LaunchedEffect(nearBottom) {
        isFollowing = nearBottom
        if (isFollowing) hasNewContent = false
    }

    // A user send is ALWAYS an intentional return to the bottom (§9).
    LaunchedEffect(sendSignal) {
        if (sendSignal == 0) return@LaunchedEffect
        pendingSendScroll = true
        isFollowing = true
        hasNewContent = false
        if (itemCount > 0) {
            runCatching { listState.animateScrollToItem(itemCount - 1) }
        }
    }

    // Content-driven following: item-count changes animate; stream-length
    // changes follow instantly (streaming must feel attached, not springy).
    LaunchedEffect(itemCount) {
        if (itemCount == 0) return@LaunchedEffect
        if (ChatAutoScrollPolicy.shouldFollow(isFollowing, pendingSendScroll)) {
            runCatching { listState.animateScrollToItem(itemCount - 1) }
            pendingSendScroll = false
            hasNewContent = false
        } else {
            hasNewContent = true
        }
    }
    LaunchedEffect(streamText.length) {
        if (streamText.isEmpty()) return@LaunchedEffect
        if (ChatAutoScrollPolicy.shouldFollow(isFollowing, pendingSendScroll)) {
            runCatching { listState.scrollToItem(itemCount - 1) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("conversation_timeline"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (timeline.isEmpty() && liveExecution == null) {
                item(key = "empty_state") { emptyContent() }
            } else {
                items(timeline, key = { it.id }) { entry ->
                    when (entry) {
                        is ChatEntry.User -> UserMessage(
                            entry = entry,
                            onCopy = { onCopy(entry.text) },
                            onEdit = { onEdit(entry.text) }
                        )

                        is ChatEntry.Assistant -> AssistantMessage(
                            entry = entry,
                            onCopy = { onCopy(entry.text) },
                            onRegenerate = onRegenerate,
                            onRetry = onRetry
                        )
                    }
                }

                if (showLiveBlock) {
                    item(key = "live_execution") {
                        ExecutionLifecycleView(
                            live = liveExecution!!,
                            streamText = streamText,
                            onCopyStream = { clipboard.setText(AnnotatedString(streamText)) }
                        )
                    }
                }
            }
        }

        // ---- "New messages" affordance (§9): an explicit jump, never a yank ----
        AnimatedVisibility(
            visible = ChatAutoScrollPolicy.shouldShowUnreadAffordance(isFollowing, hasNewContent) && itemCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        ) {
            Surface(
                onClick = {
                    hasNewContent = false
                    isFollowing = true
                    scope.launch {
                        runCatching { listState.animateScrollToItem(itemCount - 1) }
                    }
                },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier.testTag("btn_new_messages")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "رسائل جديدة",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/** One USER message bubble + its real actions (Copy, Edit — §10). */
@Composable
private fun UserMessage(
    entry: ChatEntry.User,
    onCopy: () -> Unit,
    onEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("user_message_${entry.id}")
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth(0.86f)
            ) {
                SelectionContainer {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
        Row {
            MessageAction(
                label = "نسخ",
                icon = Icons.Default.ContentCopy,
                contentDescription = "نسخ الرسالة",
                onClick = onCopy,
                tag = "btn_copy_user_${entry.id}"
            )
            MessageAction(
                label = "تحرير",
                icon = Icons.Default.Edit,
                contentDescription = "تحرير نص الرسالة في حقل الإدخال",
                onClick = onEdit,
                tag = "btn_edit_user_${entry.id}"
            )
        }
    }
}

/**
 * One ASSISTANT result bubble: rich markdown (§11), the execution summary
 * footer (the collapsed lifecycle — tokens/duration/events), and its real
 * actions (Copy / Regenerate / Retry — §10).
 */
@Composable
private fun AssistantMessage(
    entry: ChatEntry.Assistant,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit
) {
    val failed = !entry.isSuccessful
    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("assistant_message_${entry.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.agentRole.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.agentRole,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (failed) {
                    StatusPill(text = "فشل التنفيذ", color = MaterialTheme.colorScheme.error)
                } else if (entry.isDegraded) {
                    StatusPill(text = "اكتمل بنمط تراجعي", color = MaterialTheme.colorScheme.tertiary)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (entry.text.isBlank()) {
                Text(
                    text = "— لا يوجد نص لهذه الدورة —",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MarkdownContent(markdown = entry.text)
            }

            // ---- Execution summary (the collapsed lifecycle — §5) ----
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(if (entry.durationMs > 0) "%.1fs".format(entry.durationMs / 1000.0) else "—")
                    append("   •   ")
                    append("رموز: ${entry.tokensConsumed}")
                    append("   •   ")
                    append("أحداث: ${entry.eventCount}")
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Default,
                color = MaterialTheme.colorScheme.outline
            )

            // ---- Message actions (§10): real callbacks only ----
            Row(modifier = Modifier.padding(top = 2.dp)) {
                MessageAction(
                    label = "نسخ",
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "نسخ الإجابة",
                    onClick = onCopy,
                    tag = "btn_copy_asst_${entry.id}"
                )
                if (failed) {
                    MessageAction(
                        label = "إعادة المحاولة",
                        icon = Icons.Default.Refresh,
                        contentDescription = "إعادة إرسال السؤال الذي فشل",
                        onClick = onRetry,
                        tag = "btn_retry_asst_${entry.id}"
                    )
                } else {
                    MessageAction(
                        label = "إعادة التوليد",
                        icon = Icons.Default.Refresh,
                        contentDescription = "إعادة توليد الإجابة",
                        onClick = onRegenerate,
                        tag = "btn_regen_asst_${entry.id}"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The execution lifecycle attached to the LAST user message (§5): a compact
 * phase line (real events only — see ExecutionLifecycleProjection), the
 * streaming text while tokens arrive, and the honest partial text when the
 * execution was cancelled.
 */
@Composable
fun ExecutionLifecycleView(
    live: LiveExecutionState,
    streamText: String,
    onCopyStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cancelled = live.phase == ExecutionPhase.CANCELLED
    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (cancelled) 0.35f else 0.55f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("execution_lifecycle_${live.executionId}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!cancelled) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = lifecycleLabel(live),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (cancelled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                if (!cancelled) {
                    live.phaseDetail?.let { detail ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (live.toolCount > 0 || live.actionCount > 0) {
                    Text(
                        text = buildString {
                            if (live.actionCount > 0) append("${live.actionCount} إجراء")
                            if (live.toolCount > 0) {
                                if (isNotEmpty()) append(" • ")
                                append("${live.toolCount} أداة")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (streamText.isNotBlank()) {
                    IconButton(onClick = onCopyStream, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "نسخ النص الجزئي",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (live.isDegraded && live.degradedMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = live.degradedMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (streamText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = streamText + if (!cancelled) " ▌" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (cancelled) 0.7f else 1f)
                    )
                }
                if (cancelled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "نص جزئي — أُلغي التنفيذ قبل اكتماله (لم يُحفظ في السجل الدائم).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/** The user-facing phase label (Arabic-only copy — D-12). */
internal fun lifecycleLabel(live: LiveExecutionState): String = when (live.phase) {
    ExecutionPhase.QUEUED -> "في الانتظار…"
    ExecutionPhase.PLANNING -> "تخطيط"
    ExecutionPhase.EXECUTING -> "تنفيذ"
    ExecutionPhase.AWAITING_APPROVAL -> "بانتظار موافقة"
    ExecutionPhase.STREAMING -> "بث الإجابة…"
    ExecutionPhase.COMPLETED -> "اكتمل التنفيذ"
    ExecutionPhase.FAILED -> "فشل التنفيذ"
    ExecutionPhase.CANCELLED -> "أُلغي التنفيذ"
}

/** A small, honest message action (§10) — real callbacks only. */
@Composable
private fun MessageAction(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
