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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * Conversation-first rich renderer. It stays dependency-free and deterministic
 * while adding first-class treatment for technical content that a model often
 * emits: math, wide tables, charts, Mermaid-like flows, quotes, and code.
 * Basic Markdown is rendered by the same lightweight, dependency-free surface.
 */
@Composable
fun RichMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { RichChatParser.parse(markdown) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is RichChatBlock.Markdown -> MarkdownWithInlineMath(block.text)
                is RichChatBlock.Quote -> QuoteBlock(block.text)
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
    data class Markdown(val text: String) : RichChatBlock
    data class Quote(val text: String) : RichChatBlock
    data class MathBlock(val formula: String) : RichChatBlock
    data class Code(val language: String?, val code: String) : RichChatBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RichChatBlock
    data class Chart(val points: List<ChartPoint>, val raw: String) : RichChatBlock
    data class Diagram(val edges: List<DiagramEdge>, val raw: String) : RichChatBlock
}

data class ChartPoint(val label: String, val value: Float)
data class DiagramEdge(val from: String, val to: String)

object RichChatParser {
    private val fenceRegex = Regex("^```(.*)$")
    private val headingRegex = Regex("^#{1,6}\\s+(.*)$")
    private val unorderedRegex = Regex("^[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^(\\d{1,3})\\.\\s+(.*)$")

    fun parse(source: String): List<RichChatBlock> {
        val lines = source.replace("\r", "").lines()
        val blocks = mutableListOf<RichChatBlock>()
        val markdown = StringBuilder()

        fun flushMarkdown() {
            val text = markdown.toString().trim('\n')
            markdown.setLength(0)
            if (text.isNotBlank()) blocks += RichChatBlock.Markdown(text)
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

            if (i + 1 < lines.size && trimmed.contains('|') && isTableSeparator(lines[i + 1].trim())) {
                flushMarkdown()
                val header = splitTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|')) {
                    rows += splitTableRow(lines[i].trim())
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

    private fun isTableSeparator(line: String): Boolean =
        line.contains('-') &&
            line.all { it == '|' || it == '-' || it == ':' || it == ' ' } &&
            line.count { it == '-' } >= 2

    private fun splitTableRow(line: String): List<String> =
        line.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

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

private data class RichInlineSpan(
    val text: String,
    val kind: Kind,
    val url: String? = null
) {
    enum class Kind { TEXT, BOLD, ITALIC, STRIKE, CODE, MATH, LINK }
}

private fun parseRichInline(text: String): List<RichInlineSpan> {
    val spans = mutableListOf<RichInlineSpan>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += RichInlineSpan(plain.toString(), RichInlineSpan.Kind.TEXT)
            plain.setLength(0)
        }
    }

    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flushPlain()
                    spans += RichInlineSpan(text.substring(i + 2, end), RichInlineSpan.Kind.BOLD)
                    i = end + 2
                } else {
                    plain.append(marker)
                    i += 2
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    spans += RichInlineSpan(text.substring(i + 2, end), RichInlineSpan.Kind.STRIKE)
                    i = end + 2
                } else {
                    plain.append("~~")
                    i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    spans += RichInlineSpan(text.substring(i + 1, end), RichInlineSpan.Kind.CODE)
                    i = end + 1
                } else {
                    plain.append('`')
                    i++
                }
            }
            text[i] == '$' -> {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += RichInlineSpan(text.substring(i + 1, end), RichInlineSpan.Kind.MATH)
                    i = end + 1
                } else {
                    plain.append('$')
                    i++
                }
            }
            text.startsWith("\\(", i) -> {
                val end = text.indexOf("\\)", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    spans += RichInlineSpan(text.substring(i + 2, end), RichInlineSpan.Kind.MATH)
                    i = end + 2
                } else {
                    plain.append("\\(")
                    i += 2
                }
            }
            text[i] == '[' -> {
                val labelEnd = text.indexOf(']', i + 1)
                if (labelEnd > i && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd + 1) {
                        flushPlain()
                        spans += RichInlineSpan(
                            text.substring(i + 1, labelEnd),
                            RichInlineSpan.Kind.LINK,
                            text.substring(labelEnd + 2, urlEnd)
                        )
                        i = urlEnd + 1
                        continue
                    }
                }
                plain.append('[')
                i++
            }
            text[i] == '*' || text[i] == '_' -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += RichInlineSpan(text.substring(i + 1, end), RichInlineSpan.Kind.ITALIC)
                    i = end + 1
                } else {
                    plain.append(marker)
                    i++
                }
            }
            else -> {
                plain.append(text[i])
                i++
            }
        }
    }
    flushPlain()
    return spans
}

@Composable
private fun MarkdownWithInlineMath(text: String) {
    val lines = text.lines()
    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEach { line ->
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.matches(Regex("^#{1,6}\\s+.*$")) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val content = line.drop(level).trimStart()
                    Text(
                        text = richAnnotated(content),
                        style = when {
                            level <= 1 -> MaterialTheme.typography.titleLarge
                            level == 2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 7.dp, bottom = 3.dp)
                    )
                }
                line.matches(Regex("^[-*+]\\s+.*$")) -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(richAnnotated(line.drop(2)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                line.matches(Regex("^\\d{1,3}\\.\\s+.*$")) -> {
                    val prefix = line.takeWhile { it != ' ' }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(prefix, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(richAnnotated(line.drop(prefix.length + 1)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(
                    text = richAnnotated(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun richAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    parseRichInline(text).forEach { span ->
        when (span.kind) {
            RichInlineSpan.Kind.TEXT -> append(span.text)
            RichInlineSpan.Kind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            RichInlineSpan.Kind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            RichInlineSpan.Kind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(span.text) }
            RichInlineSpan.Kind.CODE -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceVariant)
            ) { append(span.text) }
            RichInlineSpan.Kind.MATH -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            ) { append(normalizeMath(span.text)) }
            RichInlineSpan.Kind.LINK -> withStyle(
                SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
            ) {
                withLink(LinkAnnotation.Url(url = span.url.orEmpty(), styles = TextLinkStyles())) { append(span.text) }
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
private fun QuoteBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Text(
                text = richAnnotated(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
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

private fun normalizeMath(source: String): String {
    var value = source
        .replace("\\left", "")
        .replace("\\right", "")
        .replace("\\cdot", "·")
        .replace("\\times", "×")
        .replace("\\div", "÷")
        .replace("\\leq", "≤")
        .replace("\\le", "≤")
        .replace("\\geq", "≥")
        .replace("\\ge", "≥")
        .replace("\\neq", "≠")
        .replace("\\approx", "≈")
        .replace("\\infty", "∞")
        .replace("\\alpha", "α")
        .replace("\\beta", "β")
        .replace("\\gamma", "γ")
        .replace("\\delta", "δ")
        .replace("\\epsilon", "ε")
        .replace("\\lambda", "λ")
        .replace("\\mu", "μ")
        .replace("\\pi", "π")
        .replace("\\rho", "ρ")
        .replace("\\sigma", "σ")
        .replace("\\tau", "τ")
        .replace("\\phi", "φ")
        .replace("\\omega", "ω")
        .replace("\\sum", "Σ")
        .replace("\\prod", "Π")
        .replace("\\int", "∫")
        .replace("\\nabla", "∇")
        .replace("\\to", "→")
        .replace("\\rightarrow", "→")
        .replace("\\in", "∈")
        .replace("\\notin", "∉")
        .replace("\\pm", "±")
        .replace("\\sqrt", "√")

    value = value.replace(Regex("\\\\frac\\{([^{}]+)\\}\\{([^{}]+)\\}")) { match ->
        "(${match.groupValues[1]})/(${match.groupValues[2]})"
    }
    value = value.replace(Regex("\\\\sqrt\\{([^{}]+)\\}")) { match ->
        "√(${match.groupValues[1]})"
    }
    value = replaceSimpleScript(value, '^', superscriptMap)
    value = replaceSimpleScript(value, '_', subscriptMap)
    return value.replace("{", "").replace("}", "").trim()
}

private fun replaceSimpleScript(value: String, marker: Char, map: Map<Char, Char>): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        if (value[i] == marker && i + 1 < value.length && value[i + 1].isDigit()) {
            i++
            while (i < value.length && value[i].isDigit()) {
                out.append(map[value[i]] ?: value[i])
                i++
            }
        } else {
            out.append(value[i])
            i++
        }
    }
    return out.toString()
}

private val superscriptMap = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹'
)

private val subscriptMap = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉'
)
