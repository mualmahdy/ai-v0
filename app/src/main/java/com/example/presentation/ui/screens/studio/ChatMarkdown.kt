package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ============================================================================
 * ChatMarkdown — the conversation message renderer (Chat Workspace Task 1
 * §11)
 * ============================================================================
 *
 * A dependency-free, MALFORMED-TOLERANT markdown renderer for assistant
 * messages: paragraphs, headings, bold/italic/inline-code, ordered and
 * unordered lists, links, fenced code blocks (language label + copy), and
 * basic pipe tables. Unknown syntax degrades to literal text — never a
 * crash, never silent loss.
 *
 * The PARSER half ([MdBlock]/[MdSpan]/[ChatMarkdownParser]) is PURE Kotlin
 * with zero Compose dependencies so it is unit-testable on the JVM.
 */

/** One inline-styled span of a text block. */
sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val label: String, val url: String) : MdSpan
}

/** One block-level element of a message. */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class ListItem(val ordered: Boolean, val ordinal: Int, val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

/**
 * The tolerant block parser. Rules (deliberately small and forgiving):
 *  - fenced code: a line starting with ``` (an optional language label
 *    follows) opens a block closed by the next ``` — an unclosed fence
 *    runs to the end of the message (never truncates the visible text);
 *  - heading: 1–6 leading '#' followed by a space;
 *  - list item: "- ", "* " or "+ " (unordered) / "N. " (ordered);
 *  - table: a header line containing '|' followed by a separator line of
 *    --- pipes; subsequent pipe rows are body rows;
 *  - everything else accumulates into paragraphs (blank line separates).
 */
object ChatMarkdownParser {

    private val headingRegex = Regex("^#{1,6}\\s+(.*)$")
    private val unorderedRegex = Regex("^[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^(\\d{1,3})\\.\\s+(.*)$")
    private val fenceRegex = Regex("^```(.*)$")

    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.lines()
        var i = 0

        val paragraph = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim('\n')
            paragraph.setLength(0)
            if (text.isNotBlank()) {
                blocks += MdBlock.Paragraph(parseInline(text))
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fenced code block (takes precedence over everything).
            val fence = fenceRegex.find(trimmed)
            if (fence != null) {
                flushParagraph()
                val language = fence.groupValues[1].trim().ifBlank { null }
                val code = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    val bodyLine = lines[i]
                    if (fenceRegex.matches(bodyLine.trim())) {
                        closed = true
                        i++
                        break
                    }
                    code.appendLine(bodyLine)
                    i++
                }
                // TOLERANT: an unclosed fence still renders the code it has.
                if (!closed && language != null && code.isBlank()) {
                    // "```lang" with nothing after and no closer: render the
                    // label line itself as literal text, not a phantom block.
                    blocks += MdBlock.Paragraph(listOf(MdSpan.Text(trimmed)))
                } else {
                    blocks += MdBlock.CodeBlock(language, code.toString().trimEnd('\n'))
                }
                continue
            }

            // Heading.
            val heading = headingRegex.find(trimmed)
            if (heading != null) {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, parseInline(heading.groupValues[1]))
                i++
                continue
            }

            // Table: header row + separator.
            if (trimmed.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
                flushParagraph()
                val header = splitTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (rowLine.contains('|')) {
                        rows += splitTableRow(rowLine)
                        i++
                    } else {
                        break
                    }
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }

            // List item.
            val unordered = unorderedRegex.find(trimmed)
            val ordered = orderedRegex.find(trimmed)
            if (unordered != null) {
                flushParagraph()
                blocks += MdBlock.ListItem(ordered = false, ordinal = 0, spans = parseInline(unordered.groupValues[1]))
                i++
                continue
            }
            if (ordered != null) {
                flushParagraph()
                blocks += MdBlock.ListItem(
                    ordered = true,
                    ordinal = ordered.groupValues[1].toIntOrNull() ?: 1,
                    spans = parseInline(ordered.groupValues[2])
                )
                i++
                continue
            }

            // Blank line closes the current paragraph.
            if (trimmed.isEmpty()) {
                flushParagraph()
            } else {
                paragraph.appendLine(line)
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun isTableSeparator(line: String): Boolean {
        if (!line.contains('-')) return false
        return line.all { it == '|' || it == '-' || it == ':' || it == ' ' } &&
            line.count { it == '-' } >= 2
    }

    private fun splitTableRow(line: String): List<String> =
        line.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /**
     * Tolerant inline parsing: **bold**, *italic*, `code`,
     * [label](url). Unclosed markers degrade to literal text.
     */
    fun parseInline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var i = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans += MdSpan.Text(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                // Bold: **x** or __x__
                (c == '*' && text.startsWith("**", i)) || (c == '_' && text.startsWith("__", i)) -> {
                    val marker = text.substring(i, i + 2)
                    val close = text.indexOf(marker, i + 2)
                    if (close > i + 2) {
                        flushPlain()
                        spans += MdSpan.Bold(text.substring(i + 2, close))
                        i = close + 2
                    } else {
                        // TOLERANT: an unclosed bold marker renders as its
                        // full literal form (both chars consumed, so a lone
                        // leftover marker can't pair across it as italic).
                        plain.append(marker)
                        i += 2
                    }
                }

                // Link: [label](url)
                c == '[' -> {
                    val labelEnd = text.indexOf(']', i + 1)
                    if (labelEnd > i && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                        val urlEnd = text.indexOf(')', labelEnd + 2)
                        if (urlEnd > labelEnd + 1) {
                            flushPlain()
                            spans += MdSpan.Link(
                                label = text.substring(i + 1, labelEnd),
                                url = text.substring(labelEnd + 2, urlEnd)
                            )
                            i = urlEnd + 1
                            continue
                        }
                    }
                    plain.append(c)
                    i++
                }

                // Inline code: `x`
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i) {
                        flushPlain()
                        spans += MdSpan.Code(text.substring(i + 1, close))
                        i = close + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                // Italic: *x* or _x_ (single markers, never part of bold)
                c == '*' || c == '_' -> {
                    val close = text.indexOf(c, i + 1)
                    if (close > i + 1) {
                        flushPlain()
                        spans += MdSpan.Italic(text.substring(i + 1, close))
                        i = close + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flushPlain()
        return spans
    }
}

/**
 * The theme-dependent style inputs the span renderer needs (kept as a plain
 * value class so the annotated-string build stays a PURE, rememberable
 * function of (spans, styles)).
 */
data class MdSpanStyles(
    val codeBackground: androidx.compose.ui.graphics.Color,
    val linkColor: androidx.compose.ui.graphics.Color
)

/**
 * PURE spans → [AnnotatedString] (links are REAL [LinkAnnotation.Link]s the
 * Text framework resolves and opens through the platform handler).
 */
fun List<MdSpan>.toAnnotatedString(styles: MdSpanStyles): AnnotatedString =
    buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = styles.codeBackground)
                ) { append(span.text) }
                is MdSpan.Link -> withStyle(
                    SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline)
                ) {
                    withLink(LinkAnnotation.Url(url = span.url, styles = TextLinkStyles())) {
                        append(span.label)
                    }
                }
            }
        }
    }

/**
 * The RENDERER half: block list → composables. Code blocks are forced LTR
 * (source code reads left-to-right even inside the app's RTL surfaces).
 */
@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { ChatMarkdownParser.parse(markdown) }
    // Theme reads happen OUTSIDE remember (CompositionLocal reads are
    // composable-only); the style object is the rememberable value.
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val spanStyles = remember(codeBackground, linkColor) {
        MdSpanStyles(codeBackground = codeBackground, linkColor = linkColor)
    }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> {
                    val annotated = remember(block, spanStyles) { block.spans.toAnnotatedString(spanStyles) }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    )
                }

                is MdBlock.Heading -> {
                    val annotated = remember(block, spanStyles) { block.spans.toAnnotatedString(spanStyles) }
                    val style = when {
                        block.level <= 1 -> MaterialTheme.typography.titleLarge
                        block.level == 2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = annotated,
                        style = style,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 3.dp)
                    )
                }

                is MdBlock.ListItem -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            text = if (block.ordered) "${block.ordinal}." else "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, end = 6.dp)
                        )
                        val annotated = remember(block, spanStyles) { block.spans.toAnnotatedString(spanStyles) }
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.CodeBlock -> CodeBlockView(block)

                is MdBlock.Table -> TableView(block)
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Code reads left-to-right even inside the app's RTL surfaces.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = block.language ?: "كود",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "نسخ الكود",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                // Horizontal scroll protects the message column from wide
                // lines (code is never force-wrapped).
                Column(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableView(block: MdBlock.Table) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                block.header.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            block.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    // Column-count tolerance: pad/truncate to the header.
                    val cells = row + List((block.header.size - row.size).coerceAtLeast(0)) { "" }
                    cells.take(block.header.size).forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
