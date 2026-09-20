package com.example.presentation.ui.screens.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.ChatAutoScrollPolicy
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ChatSourceRef
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ExecutionPhase
import com.example.presentation.state.LiveExecutionState
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ConversationTimeline — the message stream (Chat Workspace Task 1, extended
 * by CHAT CAPABILITIES Task 2 §5/§13/§15)
 * ============================================================================
 *
 * The conversation IS the screen: user messages (with attachment chips),
 * assistant results (rich markdown + collapsible sources), structured
 * capability result blocks, inline approval blocks, the execution lifecycle
 * attached to the last user message, and the auto-scroll contract — follow
 * the stream while the user is at the bottom, never yank a reading user.
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
    modifier: Modifier = Modifier,
    /** §13: approve/reject the inline approval through the real gate. */
    onApprove: (String) -> Unit = {},
    onReject: (String) -> Unit = {},
    /** §13: retry the approved execution under its own id. */
    onRetryAfterApproval: () -> Unit = {},
    /** §13: "allow always" — the standing EXECUTE grant path. */
    onGrantAlways: (String) -> Unit = {}
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

                        is ChatEntry.CapabilityResult -> CapabilityResultMessage(
                            entry = entry,
                            onCopy = { onCopy(entry.detail ?: entry.summary) }
                        )

                        is ChatEntry.ApprovalBlock -> ApprovalBlockMessage(
                            entry = entry,
                            onApprove = onApprove,
                            onReject = onReject,
                            onRetryAfterApproval = onRetryAfterApproval,
                            onGrantAlways = onGrantAlways
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
                Column {
                    SelectionContainer {
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                    // CHAT CAPABILITIES (Task 2 §5): the durable attachment
                    // chips under the sent message.
                    if (entry.attachments.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                                .testTag("user_attachments_${entry.id}")
                        ) {
                            items(entry.attachments, key = { it.id }) { attachment ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = "مرفق: ${attachment.name}",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = attachment.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
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
 * footer (the collapsed lifecycle — tokens/duration/events), collapsible
 * sources (§15), and its real actions (Copy / Regenerate / Retry — §10).
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

            // ---- Collapsible sources (Task 2 §15: "المصادر (N)") ----
            if (entry.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                CollapsibleSources(sources = entry.sources, tagSuffix = entry.id)
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

/**
 * CHAT CAPABILITIES (Task 2 §15): the collapsible sources block —
 * "المصادر (N)" collapsed by default; the details open on demand.
 */
@Composable
private fun CollapsibleSources(
    sources: List<ChatSourceRef>,
    tagSuffix: String
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sources_block_$tagSuffix")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "المصادر (${sources.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "إخفاء المصادر" else "عرض المصادر",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            sources.forEachIndexed { index, source ->
                Text(
                    text = buildString {
                        append("${index + 1}. ")
                        append(source.title)
                        source.url?.let { append("\n$it") }
                        source.confidenceScore?.let {
                            append("\nالثقة: ")
                            append("%.2f".format(it))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .testTag("source_item_${tagSuffix}_$index")
                )
            }
        }
    }
}

/**
 * CHAT CAPABILITIES (Task 2 §9–§12/§15): one STRUCTURED capability result
 * block — visually distinct (icon + kind label), but still a message in the
 * conversation, never a dashboard widget.
 */
@Composable
private fun CapabilityResultMessage(
    entry: ChatEntry.CapabilityResult,
    onCopy: () -> Unit
) {
    val failed = !entry.isSuccessful
    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = when {
            failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            entry.isDegraded -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .animateContentSize()
            .testTag("capability_result_${entry.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    capabilityKindIcon(entry.kind),
                    contentDescription = capabilityKindLabel(entry.kind),
                    tint = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = capabilityKindLabel(entry.kind),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (entry.isDegraded) {
                    StatusPill(text = "نمط تراجعي", color = MaterialTheme.colorScheme.tertiary)
                } else if (!failed) {
                    StatusPill(text = "تم", color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (entry.isDegraded && entry.degradedMessage != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.degradedMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (!entry.detail.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (entry.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                CollapsibleSources(sources = entry.sources, tagSuffix = entry.id)
            }

            if (entry.artifacts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                entry.artifacts.forEach { artifact ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .testTag("artifact_card_${artifact.artifactId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = "أثر: ${artifact.name}",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = artifact.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "نوع: ${artifact.type} • ${artifact.mimeType}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Row {
                MessageAction(
                    label = "نسخ",
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "نسخ نتيجة القدرة",
                    onClick = onCopy,
                    tag = "btn_copy_cap_${entry.id}"
                )
            }
        }
    }
}

/**
 * CHAT CAPABILITIES (Task 2 §13 — MANDATORY): the INLINE approval block —
 * the conversation-visible surface of the REAL HumanApprovalGate request.
 * PENDING shows the real description/action/risk + Approve / Reject / Allow
 * always (the standing-grant path); a decision shows its honest state and
 * the retry that re-executes the blocked message (history append-only).
 */
@Composable
private fun ApprovalBlockMessage(
    entry: ChatEntry.ApprovalBlock,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onRetryAfterApproval: () -> Unit,
    onGrantAlways: (String) -> Unit = {}
) {
    val pending = entry.state == ApprovalBlockState.PENDING
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when (entry.state) {
            ApprovalBlockState.PENDING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
            ApprovalBlockState.APPROVED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ApprovalBlockState.REJECTED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            ApprovalBlockState.EXPIRED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("approval_block_${entry.approvalId}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Gavel,
                    contentDescription = "طلب موافقة",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (entry.state) {
                        ApprovalBlockState.PENDING -> "يتطلب التنفيذ موافقتك"
                        ApprovalBlockState.APPROVED -> "تمت الموافقة"
                        ApprovalBlockState.REJECTED -> "تم الرفض"
                        ApprovalBlockState.EXPIRED -> "انتهت صلاحية الطلب"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = when (entry.state) {
                        ApprovalBlockState.REJECTED -> MaterialTheme.colorScheme.error
                        ApprovalBlockState.PENDING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                StatusPill(text = "مستوى الخطر: ${entry.riskLevel}", color = MaterialTheme.colorScheme.tertiary)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.requestedAction,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.justification.isNotBlank()) {
                Text(
                    text = "السياسة: ${entry.justification}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (entry.state) {
                ApprovalBlockState.PENDING -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = { onApprove(entry.approvalId) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_approve_${entry.approvalId}")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("موافقة")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onReject(entry.approvalId) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_reject_${entry.approvalId}")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("رفض")
                        }
                    }
                    // The standing-consent affordance (recorded EXECUTE grant
                    // — the same path the governance surface offers).
                    TextButton(
                        onClick = { onGrantAlways(entry.approvalId) },
                        modifier = Modifier.testTag("btn_grant_always_${entry.approvalId}")
                    ) {
                        Text("السماح دائماً لهذه الأداة")
                    }
                }
                ApprovalBlockState.APPROVED -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onRetryAfterApproval,
                        modifier = Modifier.testTag("btn_retry_approved_${entry.approvalId}")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إعادة المحاولة بالموافقة الممنوحة")
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun capabilityKindIcon(kind: CapabilityKind): ImageVector = when (kind) {
    CapabilityKind.TOOL -> Icons.Default.SettingsEthernet
    CapabilityKind.SKILL -> Icons.Default.Bolt
    CapabilityKind.MCP -> Icons.Default.Link
    CapabilityKind.SEARCH -> Icons.Default.Search
    CapabilityKind.KNOWLEDGE_RETRIEVAL -> Icons.Default.School
}

private fun capabilityKindLabel(kind: CapabilityKind): String = when (kind) {
    CapabilityKind.TOOL -> "أداة"
    CapabilityKind.SKILL -> "مهارة"
    CapabilityKind.MCP -> "MCP"
    CapabilityKind.SEARCH -> "بحث ذكي"
    CapabilityKind.KNOWLEDGE_RETRIEVAL -> "استرجاع المعرفة"
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
