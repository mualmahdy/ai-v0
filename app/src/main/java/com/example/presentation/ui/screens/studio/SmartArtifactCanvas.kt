package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.state.ChatArtifactRef
import com.example.presentation.state.formatArtifactSize
import com.example.presentation.state.isTextuallyPreviewable

/**
 * ============================================================================
 * SmartArtifactCanvas — the conversation's ARTIFACT PREVIEW surface
 * (CHAT FINAL CLOSURE §10)
 * ============================================================================
 *
 * THE SCOPE INVARIANT: conversation artifact cards open ONLY through the
 * ViewModel's scope-aware read (the real ArtifactService authorization
 * path). This canvas RENDERS; it never reads, never decodes binary
 * content, and never fabricates a preview for an unsupported type — the
 * honest degradation message is the "preview" for those.
 *
 * THE RENDERING CONTRACT: markdown artifacts ride the SAME unified rich
 * pipeline as assistant messages (one parser, one tolerance contract, one
 * test suite — the frontier series' §9 invariant); every other textual
 * artifact renders as selectable monospace. The preview cap lives in the
 * ViewModel (a visible truncation notice, never a silent cut).
 */
@Composable
internal fun SmartArtifactCanvas(
    artifact: ChatArtifactRef?,
    content: String?,
    isLoading: Boolean,
    error: String?,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier.testTag("smart_artifact_canvas")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val selected = artifact
            if (selected == null) {
                Text(
                    text = "لا يوجد أثر محدد",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // ---- The canvas header: identity + the three actions ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (selected.type.equals("FOLDER", ignoreCase = true)) {
                            Icons.Default.Folder
                        } else {
                            Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selected.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "${selected.mimeType} • ${formatArtifactSize(selected.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = { content?.let { clipboard.setText(AnnotatedString(it)) } },
                        enabled = !content.isNullOrEmpty(),
                        modifier = Modifier.testTag("artifact_copy")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "نسخ محتوى الأثر")
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("artifact_request_edit")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "طلب تعديل الأثر")
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("artifact_close")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق معاينة الأثر")
                    }
                }

                // ---- The canvas body: honest states, exactly one at a time ----
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    !error.isNullOrBlank() -> {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("artifact_error")
                        )
                    }
                    content.isNullOrEmpty() && !selected.isTextuallyPreviewable() -> {
                        Text(
                            text = "هذا النوع من الآثار لا يملك مسار معاينة نصية في النسخة الحالية.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    content.isNullOrEmpty() -> {
                        Text(
                            text = "الأثر فارغ.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    selected.mimeType.substringBefore(';').trim().lowercase() == "text/markdown" ||
                        selected.name.endsWith(".md", ignoreCase = true) ||
                        selected.name.endsWith(".markdown", ignoreCase = true) -> {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            RichMarkdownContent(markdown = content, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    else -> {
                        SelectionContainer {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    text = content,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ARTIFACT CANVAS (§10): the ONE conversation artifact card — a single
 * renderer for assistant messages and capability results (two inline
 * copies is how the duplicated-parser drift started; one card, one
 * contract). The card OPENS the scope-aware preview; it never renders
 * content itself (no fabricated thumbnails — the UI-DESIGN closure's
 * honest-preview rule).
 */
@Composable
internal fun ArtifactCard(
    artifact: ChatArtifactRef,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "فتح الأثر: ${artifact.name}" }
            .testTag("artifact_card_${artifact.artifactId}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (artifact.type.equals("FOLDER", ignoreCase = true)) Icons.Default.Folder
                else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artifact.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "${artifact.mimeType} • ${formatArtifactSize(artifact.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                text = "فتح",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
