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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.ChatArtifactRef
import com.example.presentation.state.ChatAutoScrollPolicy
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ChatSourceRef
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ExecutionPhase
import com.example.presentation.state.LiveExecutionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /** Stable conversation identity; changing it creates a fresh scroll state. */
    conversationKey: String? = null,
    /** FRONTIER REASONING: the live thinking text streamed before the answer. */
    reasoningText: String = "",
    emptyContent: @Composable () -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** §13: approve/reject the inline approval through the real gate. */
    onApprove: (String) -> Unit = {},
    onReject: (String) -> Unit = {},
    /**
     * §13 + RESIDUAL CLOSURE (P4): retry the approved execution under its OWN
     * approval id — the callback carries the tapped block's identity.
     */
    onRetryAfterApproval: (String) -> Unit = {},
    /** §13: "allow always" — the standing EXECUTE grant path (§12: confirmed). */
    onGrantAlways: (String) -> Unit = {},
    /**
     * ARTIFACT CANVAS (§10): opens a conversation artifact card in the
     * scope-aware preview surface (pane at expanded width, sheet below).
     */
    onOpenArtifact: (ChatArtifactRef) -> Unit = {}
) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(conversationKey, saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // ---- Auto-scroll policy state (§9) ----
    var isFollowing by remember { mutableStateOf(true) }
    var hasNewContent by remember { mutableStateOf(false) }
    var pendingSendScroll by remember { mutableStateOf(false) }

    // ---- FUNCTIONAL CLOSURE (§8/§11): the CHRONOLOGY-CORRECT render order.
    // The live execution block belongs EXACTLY AFTER its ORIGINATING user
    // entry (the anchor), not "always at the end" — capability results and
    // approval blocks that land during/after the execution keep their true
    // conversation order.
    val anchorIndex = liveExecution?.originUserEntryId
        ?.let { anchorId -> timeline.indexOfFirst { it.id == anchorId } }
        ?: -1
    val liveBlockIndex = when {
        liveExecution == null -> -1
        anchorIndex >= 0 -> anchorIndex + 1
        else -> timeline.size
    }
    val entriesBeforeLive = if (liveBlockIndex >= 0) timeline.take(liveBlockIndex) else timeline
    val entriesAfterLive = if (liveBlockIndex >= 0) timeline.drop(liveBlockIndex) else emptyList()

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
    // FRONTIER REASONING: thinking tokens stream too — a following user keeps
    // seeing the live block grow while the model thinks.
    LaunchedEffect(reasoningText.length) {
        if (reasoningText.isEmpty()) return@LaunchedEffect
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
                items(entriesBeforeLive, key = { it.id }) { entry ->
                    RenderTimelineEntry(
                        entry = entry,
                        onCopy = onCopy,
                        onEdit = onEdit,
                        onRegenerate = onRegenerate,
                        onRetry = onRetry,
                        onApprove = onApprove,
                        onReject = onReject,
                        onRetryAfterApproval = onRetryAfterApproval,
                        onGrantAlways = onGrantAlways,
                        onOpenArtifact = onOpenArtifact
                    )
                }

                if (showLiveBlock) {
                    item(key = "live_execution") {
                        ExecutionLifecycleView(
                            live = liveExecution!!,
                            streamText = streamText,
                            reasoningText = reasoningText,
                            onCopyStream = { clipboard.setText(AnnotatedString(streamText)) }
                        )
                    }
                }

                items(entriesAfterLive, key = { it.id }) { entry ->
                    RenderTimelineEntry(
                        entry = entry,
                        onCopy = onCopy,
                        onEdit = onEdit,
                        onRegenerate = onRegenerate,
                        onRetry = onRetry,
                        onApprove = onApprove,
                        onReject = onReject,
                        onRetryAfterApproval = onRetryAfterApproval,
                        onGrantAlways = onGrantAlways,
                        onOpenArtifact = onOpenArtifact
                    )
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

/** FUNCTIONAL CLOSURE: the single dispatch for every timeline entry. */
@Composable
private fun RenderTimelineEntry(
    entry: ChatEntry,
    onCopy: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onRetry: (String) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onRetryAfterApproval: (String) -> Unit,
    onGrantAlways: (String) -> Unit,
    onOpenArtifact: (ChatArtifactRef) -> Unit
) {
    when (entry) {
        is ChatEntry.User -> UserMessage(
            entry = entry,
            onCopy = { onCopy(entry.text) },
            onEdit = { onEdit(entry.text) }
        )

        is ChatEntry.Assistant -> AssistantMessage(
            entry = entry,
            onCopy = { onCopy(entry.text) },
            // FUNCTIONAL CLOSURE (§6): the action carries the ENTRY ID — the
            // targeted message is regenerated/retried, never "the last one".
            onRegenerate = { onRegenerate(entry.id) },
            onRetry = { onRetry(entry.id) },
            onOpenArtifact = onOpenArtifact
        )

        is ChatEntry.CapabilityResult -> CapabilityResultMessage(
            entry = entry,
            onCopy = { onCopy(entry.detail ?: entry.summary) },
            onOpenArtifact = onOpenArtifact
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
                                            maxLines = 1,
                                            // CHAT FINAL CLOSURE (§13): a long
                                            // filename ellipsizes instead of
                                            // breaking the bubble's layout.
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 180.dp)
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
            // FUNCTIONAL CLOSURE (§7 — honest semantics): the original
            // message is NEVER mutated. The action stages the text in the
            // composer for a RE-SEND as a NEW message — the label says
            // exactly that (no misleading "edit in place" impression).
            MessageAction(
                label = "تعديل وإعادة الإرسال",
                icon = Icons.Default.Edit,
                contentDescription = "تحرير نص الرسالة في حقل الإدخال ثم إرساله كرسالة جديدة (الأصل لا يتغير)",
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
    onRetry: () -> Unit,
    /** ARTIFACT CANVAS (§10): opens one of this message's artifacts. */
    onOpenArtifact: (ChatArtifactRef) -> Unit
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
                if (failed && entry.isDegraded) {
                    // D-11 MERGE: one turn that erred then completed degraded —
                    // the honest merged label, not a bare "فشل" that hides the
                    // recovered output.
                    StatusPill(text = "اكتمل جزئياً بعد خطأ", color = MaterialTheme.colorScheme.tertiary)
                } else if (failed) {
                    StatusPill(text = "فشل التنفيذ", color = MaterialTheme.colorScheme.error)
                } else if (entry.isDegraded) {
                    StatusPill(text = "اكتمل بنمط تراجعي", color = MaterialTheme.colorScheme.tertiary)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ---- FRONTIER REASONING: the model's thinking rides the finished
            // entry (runtime-only — restored sessions honestly show none).
            if (entry.reasoning.isNotBlank()) {
                CollapsibleReasoning(
                    reasoning = entry.reasoning,
                    isLive = false,
                    tagSuffix = entry.id
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (entry.text.isBlank()) {
                Text(
                    text = "— لا يوجد نص لهذه الدورة —",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                RichMarkdownContent(markdown = entry.text)
            }

            // ---- Collapsible sources (Task 2 §15: "المصادر (N)") ----
            if (entry.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                CollapsibleSources(sources = entry.sources, tagSuffix = entry.id)
            }

            // ---- ARTIFACT CANVAS (§10): the turn's produced artifacts as
            // OPENABLE cards (the same shared renderer capability results
            // use — one card, one contract; the open goes through the
            // scope-aware preview, never a local read). ----
            if (entry.artifacts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                entry.artifacts.forEach { artifact ->
                    ArtifactCard(artifact = artifact, onOpen = { onOpenArtifact(artifact) })
                }
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
 *
 * CHAT FINAL CLOSURE (§11 actionable sources): a source carrying a REAL
 * http(s) URL is OPENABLE — its row opens the platform browser through the
 * standard Android ACTION_VIEW intent (no new navigation infrastructure;
 * rows without a usable URL stay plainly readable with their provider id).
 * Each row keeps a ≥48dp touch target and explicit open-state semantics.
 */
@Composable
private fun CollapsibleSources(
    sources: List<ChatSourceRef>,
    tagSuffix: String
) {
    val context = LocalContext.current
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
        Column(modifier = Modifier.padding(top = 4.dp).heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            sources.forEachIndexed { index, source ->
                // UI POLISH §16: the citation line shows the title and the
                // DOMAIN (parsed from the real URL) — a readable reference
                // instead of a raw URL wall; the provider id is the honest
                // fallback for local/provider-scoped citations.
                val domain = sourceDomain(source.url)
                val openable = source.url.isWebUrl()
                val origin = domain ?: source.providerId
                val rowText = buildString {
                    append("${index + 1}. ")
                    append(source.title)
                    if (origin != null) append(" — $origin")
                    source.confidenceScore?.let {
                        append("\nالثقة: ")
                        append("%.2f".format(it))
                    }
                }
                if (openable) {
                    Surface(
                        onClick = {
                            // §11: the platform's own browser navigation —
                            // ACTION_VIEW over the source's REAL URL (a
                            // malformed URL at click time degrades honestly
                            // to a no-op, never a crash).
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(source.url)
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "فتح المصدر: ${source.title}" }
                            .testTag("source_item_${tagSuffix}_$index")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = rowText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = rowText,
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
}

/**
 * UI POLISH §16: the readable domain of a citation URL (null when absent).
 */
internal fun sourceDomain(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
}

/**
 * CHAT FINAL CLOSURE (§11): whether a citation URL is really OPENABLE in a
 * browser — http(s) only (workspace:// and other internal schemes stay
 * readable rows; they have no browser target).
 */
internal fun String?.isWebUrl(): Boolean {
    if (isNullOrBlank()) return false
    return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true)
}

/**
 * CHAT CAPABILITIES (Task 2 §9–§12/§15): one STRUCTURED capability result
 * block — visually distinct (icon + kind label), but still a message in the
 * conversation, never a dashboard widget.
 */
@Composable
private fun CapabilityResultMessage(
    entry: ChatEntry.CapabilityResult,
    onCopy: () -> Unit,
    /** ARTIFACT CANVAS (§10): opens one of this result's artifacts. */
    onOpenArtifact: (ChatArtifactRef) -> Unit
) {
    val failed = !entry.isSuccessful
    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = when {
            entry.isPending -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                when {
                    // FUNCTIONAL CLOSURE (§22): the PENDING state is visible
                    // with a live progress marker — the user always knows the
                    // invocation is still running (the sheet already closed).
                    entry.isPending -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp).testTag("pending_marker_${entry.id}"),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(text = "قيد التنفيذ", color = MaterialTheme.colorScheme.primary)
                    }
                    entry.isDegraded -> StatusPill(text = "نمط تراجعي", color = MaterialTheme.colorScheme.tertiary)
                    !failed -> StatusPill(text = "تم", color = MaterialTheme.colorScheme.primary)
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
                RichMarkdownContent(markdown = entry.detail)
            }

            if (entry.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                CollapsibleSources(sources = entry.sources, tagSuffix = entry.id)
            }

            if (entry.artifacts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                entry.artifacts.forEach { artifact ->
                    // ARTIFACT CANVAS (§10): the ONE shared card — assistant
                    // messages and capability results stopped drifting apart
                    // by sharing the renderer (and the scope-aware open).
                    ArtifactCard(artifact = artifact, onOpen = { onOpenArtifact(artifact) })
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
    onRetryAfterApproval: (String) -> Unit,
    onGrantAlways: (String) -> Unit = {}
) {
    val pending = entry.state == ApprovalBlockState.PENDING
    // FUNCTIONAL CLOSURE (§12): "allow always" grants a STANDING permission —
    // the user confirms the REAL scope before it is recorded (it is NOT a
    // one-time approval, and the UI may no longer imply that it is).
    var confirmGrantAlways by remember { mutableStateOf(false) }
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
                    // — the same path the governance surface offers). §12: the
                    // button OPENS THE SCOPE CONFIRMATION, it never grants
                    // silently.
                    TextButton(
                        onClick = { confirmGrantAlways = true },
                        modifier = Modifier.testTag("btn_grant_always_${entry.approvalId}")
                    ) {
                        Text("السماح دائماً لهذه الأداة")
                    }
                    Text(
                        text = "السماح دائماً = منح صلاحية تنفيذ دائمة لهذه الأداة على مستوى الجهاز (كل الجلسات) — وليس موافقة لهذا الطلب فقط.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ApprovalBlockState.APPROVED -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        // P4: the retry carries THIS block's approval id —
                        // the targeted message, never "the last approved".
                        onClick = { onRetryAfterApproval(entry.approvalId) },
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

    // §12: the standing-grant scope confirmation — the REAL contract in
    // plain words, an explicit confirm, and an explicit cancel.
    if (confirmGrantAlways) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmGrantAlways = false },
            title = { Text("السماح دائماً بهذه الأداة؟") },
            text = {
                Text(
                    "سيُسجَّل منح EXECUTE دائم للأداة «${entry.toolName}» على مستوى الجهاز " +
                            "(كل الجلسات والمساحات)، ولن يُطلب موافقتك مجدداً على استدعاءاتها. " +
                            "هذا ليس موافقة على الطلب الحالي فقط — يمكنك سحب المنح لاحقاً من شاشة الحوكمة."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        confirmGrantAlways = false
                        onGrantAlways(entry.approvalId)
                    },
                    modifier = Modifier.testTag("btn_confirm_grant_always_${entry.approvalId}")
                ) { Text("السماح دائماً") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { confirmGrantAlways = false },
                    modifier = Modifier.testTag("btn_cancel_grant_always_${entry.approvalId}")
                ) { Text("إلغاء") }
            }
        )
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
 *
 * UI POLISH §10: the counts row is now an EXPANDABLE details affordance —
 * "2 أداة • 4.2s ⌄" opens the REAL step history (the actions/tools/
 * verdicts the kernel actually reported, with their honest elapsed times —
 * never chain-of-thought, never raw telemetry).
 */
@Composable
fun ExecutionLifecycleView(
    live: LiveExecutionState,
    streamText: String,
    reasoningText: String = "",
    onCopyStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cancelled = live.phase == ExecutionPhase.CANCELLED

    // §10: the live duration — recomputed every second while the execution
    // is running (a real elapsed time, frozen naturally once terminal).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val isRunning = live.phase != ExecutionPhase.COMPLETED &&
        live.phase != ExecutionPhase.FAILED &&
        live.phase != ExecutionPhase.CANCELLED
    LaunchedEffect(live.executionId, isRunning) {
        while (isRunning && kotlinx.coroutines.currentCoroutineContext().isActive) {
            kotlinx.coroutines.delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val durationSeconds = if (live.startedAtMs > 0) {
        ((if (isRunning) nowMs else System.currentTimeMillis()) - live.startedAtMs)
            .coerceAtLeast(0) / 1000.0
    } else 0.0

    // §10: the expandable execution details (real steps only).
    var detailsExpanded by remember { mutableStateOf(false) }
    val hasDetails = live.steps.isNotEmpty() || live.toolCount > 0 || live.actionCount > 0

    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (cancelled) 0.35f else 0.55f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .animateContentSize()
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
                if (streamText.isNotBlank()) {
                    // CHAT FINAL CLOSURE (§12): default-sized IconButton — the
                    // explicit 28dp size override killed the 48dp minimum touch
                    // target (the icon inside stays visually compact).
                    IconButton(
                        onClick = onCopyStream,
                        modifier = Modifier.testTag("btn_copy_stream_${live.executionId}")
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "نسخ النص الجزئي",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // ---- FRONTIER REASONING: the model's own streamed thinking,
            // collapsible — one honest lane, never mixed into the answer.
            if (reasoningText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                CollapsibleReasoning(
                    reasoning = reasoningText,
                    isLive = live.phase == ExecutionPhase.THINKING,
                    tagSuffix = live.executionId
                )
            }

            // ---- §10: the expandable DETAILS affordance ("2 أداة • 4.2s ⌄")
            // — real counts + the honest elapsed time; expanding reveals the
            // step history projected from the REAL kernel events.
            if (hasDetails) {
                Surface(
                    onClick = { detailsExpanded = !detailsExpanded },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("execution_details_toggle_${live.executionId}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildString {
                                if (live.toolCount > 0) append("${live.toolCount} أداة")
                                if (live.actionCount > 0) {
                                    if (isNotEmpty()) append(" • ")
                                    append("${live.actionCount} إجراء")
                                }
                                if (durationSeconds > 0) {
                                    if (isNotEmpty()) append(" • ")
                                    append("%.1fs".format(durationSeconds))
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (detailsExpanded) "إخفاء تفاصيل التنفيذ" else "عرض تفاصيل التنفيذ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                AnimatedVisibility(visible = detailsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("execution_details_panel_${live.executionId}")
                    ) {
                        if (live.steps.isEmpty()) {
                            Text(
                                text = "لا تفاصيل بعد — الخطوات الفعلية تظهر هنا فور حدوثها.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            live.steps.forEach { step ->
                                val elapsed = if (live.startedAtMs > 0) {
                                    ((step.timestampMs - live.startedAtMs).coerceAtLeast(0)) / 1000.0
                                } else 0.0
                                Text(
                                    text = "+%.1fs • ${step.label}".format(elapsed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
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
                    Column {
                        // STREAMING POLISH: the live answer renders through the
                        // SAME rich pipeline as the finished message — code
                        // fences, bold, tables format AS they complete, never
                        // a wall of monochrome text that snaps into shape at
                        // the end. The tolerant parser degrades half-written
                        // markdown honestly (an unclosed fence is text until
                        // it closes).
                        RichMarkdownContent(markdown = streamText)
                        if (!cancelled) {
                            // The streaming cursor — its own line under the
                            // last completed block, primary-tinted.
                            Text(
                                text = "▌",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
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
    ExecutionPhase.THINKING -> "يفكر…"
    ExecutionPhase.STREAMING -> "بث الإجابة…"
    ExecutionPhase.COMPLETED -> "اكتمل التنفيذ"
    ExecutionPhase.FAILED -> "فشل التنفيذ"
    ExecutionPhase.CANCELLED -> "أُلغي التنفيذ"
}

/**
 * FRONTIER REASONING: the collapsible thinking block — the model's OWN
 * streamed reasoning (ReasoningChunk accumulation), rendered in a subdued
 * lane DISTINCT from the answer (smaller type, outline tint, italic), so
 * thinking never reads as content. Collapsed by default with an honest
 * word count; the live variant shows the streaming cursor while the model
 * is still thinking. Never rendered for absent reasoning (no fabricated
 * "thinking" placeholders).
 */
@Composable
private fun CollapsibleReasoning(
    reasoning: String,
    isLive: Boolean,
    tagSuffix: String
) {
    var expanded by remember { mutableStateOf(false) }
    val wordCount = reasoning.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reasoning_block_$tagSuffix")
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "التفكير ($wordCount كلمة)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "إخفاء التفكير" else "عرض التفكير",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    SelectionContainer {
                        Text(
                            text = reasoning + if (isLive) " ▌" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("reasoning_text_$tagSuffix")
                        )
                    }
                }
            }
        }
    }
}

/** A small, honest message action (§10) — real callbacks only.
 *  CHAT FINAL CLOSURE (§12 touch targets): the row keeps a ≥48dp minimum
 *  INTERACTIVE height — a 14dp icon is never the de-facto touch target. */
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
            .heightIn(min = 48.dp)
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
