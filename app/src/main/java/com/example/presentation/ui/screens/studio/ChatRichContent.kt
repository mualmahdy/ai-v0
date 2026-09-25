package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * ============================================================================
 * RichMarkdownContent — the conversation-first RICH renderer (single
 * renderer over the UNIFIED parser — FRONTIER unification 2026)
 * ============================================================================
 *
 * Dependency-free and deterministic, with first-class treatment for the
 * technical content a model often emits: math blocks, wide tables, charts,
 * Mermaid-like flows, quotes, and code. Basic Markdown (paragraphs,
 * headings, lists, inline styles, links, inline math) is parsed by the ONE
 * shared [ChatMarkdownParser] — the duplicated per-line scanner and inline
 * parser this file used to carry are gone; every message surface now shares
 * one tolerance contract and one test suite.
 */
@Composable
fun RichMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { RichChatParser.parse(markdown) }
    // Theme reads happen OUTSIDE remember (CompositionLocal reads are
    // composable-only); the style object is the rememberable value.
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val spanStyles = remember(codeBackground, linkColor) {
        MdSpanStyles(codeBackground = codeBackground, linkColor = linkColor)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is RichChatBlock.Markdown -> MarkdownBlocks(block.blocks, spanStyles)
                is RichChatBlock.Quote -> QuoteBlock(block.text, spanStyles)
                is RichChatBlock.MathBlock -> MathBlock(block.formula)
                is RichChatBlock.Code -> RichCodeBlock(block.language, block.code)
                is RichChatBlock.Table -> RichTable(block)
                is RichChatBlock.Chart -> RichChart(block)
                is RichChatBlock.Diagram -> RichDiagram(block)
            }
        }
    }
}

sealed interface RichChatBlock {
    /**
     * A stretch of basic Markdown, now carried as TYPED [MdBlock]s (parsed
     * once by the shared [ChatMarkdownParser] at flush time) instead of the
     * old raw-text + per-line re-parse — one parse, one rendering path.
     */
    data class Markdown(val blocks: List<MdBlock>) : RichChatBlock
    data class Quote(val text: String) : RichChatBlock
    data class MathBlock(val formula: String) : RichChatBlock
    data class Code(val language: String?, val code: String) : RichChatBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RichChatBlock
    data class Chart(val points: List<ChartPoint>, val raw: String) : RichChatBlock
    data class Diagram(val edges: List<DiagramEdge>, val raw: String) : RichChatBlock
}

data class ChartPoint(val label: String, val value: Float)
data class DiagramEdge(val from: String, val to: String)

/**
 * The rich BLOCK scanner: routes fenced code (incl. chart/mermaid fences),
 * display-math and quote/table blocks to their dedicated renderers, and
 * delegates everything else to the SHARED [ChatMarkdownParser] as typed
 * [MdBlock]s. Fence/table row splitting reuses the parser's helpers — the
 * duplicated scanners are gone.
 */
object RichChatParser {
    private val fenceRegex = Regex("^```(.*)$")

    fun parse(source: String): List<RichChatBlock> {
        val lines = source.replace("\r", "").lines()
        val blocks = mutableListOf<RichChatBlock>()
        val markdown = StringBuilder()

        fun flushMarkdown() {
            val text = markdown.toString().trim('\n')
            markdown.setLength(0)
            if (text.isNotBlank()) {
                // ONE shared parser: the same tolerant block/inline contract
                // as the plain renderer (headings, lists, inline styles…).
                blocks += RichChatBlock.Markdown(ChatMarkdownParser.parse(text))
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            val fence = fenceRegex.matchEntire(trimmed)
            if (fence != null) {
                flushMarkdown()
                val language = fence.groupValues[1].trim().ifBlank { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !fenceRegex.matches(lines[i].trim())) {
                    code.appendLine(lines[i])
                    i++
                }
                if (i < lines.size && fenceRegex.matches(lines[i].trim())) i++
                val normalizedLanguage = language?.lowercase()
                when (normalizedLanguage) {
                    "chart", "charts", "bar-chart", "bar" ->
                        blocks += RichChatBlock.Chart(parseChart(code.toString()), code.toString())
                    "mermaid", "diagram", "flow" ->
                        blocks += RichChatBlock.Diagram(parseDiagram(code.toString()), code.toString())
                    else ->
                        blocks += RichChatBlock.Code(language, code.toString().trimEnd('\n'))
                }
                continue
            }

            if (trimmed == "$$" || trimmed == "\\[") {
                flushMarkdown()
                val close = if (trimmed == "$$") "$$" else "\\]"
                val formula = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != close) {
                    formula.appendLine(lines[i])
                    i++
                }
                if (i < lines.size && lines[i].trim() == close) i++
                blocks += RichChatBlock.MathBlock(formula.toString().trim())
                continue
            }

            if (i + 1 < lines.size && trimmed.contains('|') &&
                ChatMarkdownParser.isTableSeparator(lines[i + 1].trim())
            ) {
                flushMarkdown()
                val header = ChatMarkdownParser.splitTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|')) {
                    rows += ChatMarkdownParser.splitTableRow(lines[i].trim())
                    i++
                }
                blocks += RichChatBlock.Table(header, rows)
                continue
            }

            if (trimmed.startsWith(">")) {
                flushMarkdown()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i].trim().removePrefix(">").trim()).append('\n')
                    i++
                }
                blocks += RichChatBlock.Quote(quote.toString().trimEnd())
                continue
            }

            markdown.appendLine(line)
            i++
        }
        flushMarkdown()
        return blocks
    }

    fun parseChart(source: String): List<ChartPoint> = source.lines()
        .mapNotNull { line ->
            val clean = line.trim()
            if (clean.isBlank() || clean.startsWith("#")) return@mapNotNull null
            val parts = clean.split(',', '|', ':').map { it.trim() }
            if (parts.size < 2) return@mapNotNull null
            val value = parts.last().toFloatOrNull() ?: return@mapNotNull null
            ChartPoint(parts.dropLast(1).joinToString(" "), value)
        }

    fun parseDiagram(source: String): List<DiagramEdge> {
        val edgeRegex = Regex("^\\s*([A-Za-z0-9_ .\\-]+?)\\s*(?:-->|---|==>)\\s*([A-Za-z0-9_ .\\-]+?)\\s*$")
        return source.lines().mapNotNull { line ->
            edgeRegex.matchEntire(line)?.let {
                DiagramEdge(it.groupValues[1].trim(), it.groupValues[2].trim())
            }
        }
    }
}

/**
 * Renders the typed basic-Markdown blocks of one rich chunk — the shared
 * pipeline's visual twin of the plain renderer: annotated spans, M3
 * typography, primary-tinted list markers. Code/table blocks are routed to
 * the SAME dedicated renderers the rich scanner uses (defensive tolerance:
 * the scanner normally consumes them first).
 */
@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, spanStyles: MdSpanStyles) {
    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> {
                    Text(
                        text = block.spans.toAnnotatedString(spanStyles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    )
                }

                is MdBlock.Heading -> {
                    Text(
                        text = block.spans.toAnnotatedString(spanStyles),
                        style = when {
                            block.level <= 1 -> MaterialTheme.typography.titleLarge
                            block.level == 2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 7.dp, bottom = 3.dp)
                    )
                }

                is MdBlock.ListItem -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            text = if (block.ordered) "${block.ordinal}." else "•",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = block.spans.toAnnotatedString(spanStyles),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                is MdBlock.CodeBlock -> RichCodeBlock(block.language, block.code)
                is MdBlock.Table -> RichTable(RichChatBlock.Table(block.header, block.rows))
            }
        }
    }
}

@Composable
private fun MathBlock(formula: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = normalizeMath(formula),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.widthIn(min = 160.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String, spanStyles: MdSpanStyles) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Text(
                text = parseQuoteSpans(text).toAnnotatedString(spanStyles),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

/**
 * Quote content keeps MULTI-LINE structure: each line is inline-parsed
 * independently (a marker can never pair across quote lines), matching the
 * historical quote rendering exactly.
 */
private fun parseQuoteSpans(text: String): List<MdSpan> =
    text.lines().flatMapIndexed { index, line ->
        val spans = ChatMarkdownParser.parseInline(line)
        if (index == 0) spans else listOf(MdSpan.Text("\n")) + spans
    }

@Composable
private fun RichCodeBlock(language: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(language ?: "كود", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "نسخ الكود", modifier = Modifier.padding(4.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
                    SelectionContainer {
                        Column {
                            code.lines().forEachIndexed { index, line ->
                                Row {
                                    Text(
                                        text = (index + 1).toString().padStart(3, ' '),
                                        color = MaterialTheme.colorScheme.outline,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = line,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichTable(block: RichChatBlock.Table) {
    val columns = block.header.size.coerceAtLeast(1)
    val minWidth = (columns * 120).coerceAtLeast(320).dp
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Column(modifier = Modifier.widthIn(min = minWidth)) {
                TableRow(block.header, header = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 4.dp))
                block.rows.forEach { row -> TableRow(row + List((columns - row.size).coerceAtLeast(0)) { "" }, header = false) }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
        cells.forEach { cell ->
            Text(
                text = cell,
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 110.dp).weight(1f, fill = false).padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun RichChart(block: RichChatBlock.Chart) {
    if (block.points.isEmpty()) {
        RichCodeBlock("chart", block.raw)
        return
    }
    val max = block.points.maxOf { it.value }.takeIf { it > 0f } ?: 1f
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, contentDescription = "مخطط بياني", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("مخطط بياني", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            block.points.forEach { point ->
                Column {
                    Text("${point.label}: ${point.value}", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(
                        progress = (point.value / max).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RichDiagram(block: RichChatBlock.Diagram) {
    if (block.edges.isEmpty()) {
        RichCodeBlock("mermaid", block.raw)
        return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, contentDescription = "مخطط تدفق", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("مخطط تدفق", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            block.edges.forEach { edge ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) {
                        Text(edge.from, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                        Text(edge.to, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
