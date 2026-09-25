package com.example.presentation.ui.screens.studio

import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp

/**
 * ============================================================================
 * ChatMarkdown — THE unified, dependency-free markdown model (Chat Workspace
 * Task 1 §11; FRONTIER unification 2026)
 * ============================================================================
 *
 * One PURE Kotlin model now owns everything message rendering parses:
 * paragraphs, headings, ordered/unordered lists, fenced code, pipe tables,
 * and the tolerant inline set — **bold**, *italic*, ~~strike~~, `code`,
 * $math$, \(math\) and [links](url). It is the single parser behind BOTH the
 * plain [MarkdownContent] path and the rich conversation renderer
 * ([RichMarkdownContent] — math blocks, quotes, charts, diagrams, wide
 * tables); the previously DUPLICATED table/fence/inline scanners of the two
 * files are gone (one parser, one tolerance contract, one test suite).
 *
 * The parser half is deliberately free of Compose dependencies so it is
 * unit-testable on the JVM ([ChatMarkdownParser], [MdBlock], [MdSpan]);
 * the only Compose-aware piece is the pure [toAnnotatedString] span
 * renderer, parameterized by theme colors ([MdSpanStyles]).
 *
 * Malformed input NEVER crashes and never silently loses text: unclosed
 * markers degrade to their literal form, unclosed fences run to the end of
 * the message, and unknown syntax stays plain text.
 */

/** One inline-styled span of a text block. */
sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan

    /** FRONTIER (unification): ~~strike~~ — carried from the rich renderer. */
    data class Strike(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan

    /** FRONTIER (unification): $…$ / \(…\) inline math (Unicode-normalized). */
    data class Math(val text: String) : MdSpan
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

    internal fun isTableSeparator(line: String): Boolean {
        if (!line.contains('-')) return false
        return line.all { it == '|' || it == '-' || it == ':' || it == ' ' } &&
            line.count { it == '-' } >= 2
    }

    internal fun splitTableRow(line: String): List<String> =
        line.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /**
     * Tolerant inline parsing: **bold**, *italic*, ~~strike~~, `code`,
     * $math$, \(math\) and [label](url). Unclosed markers degrade to
     * literal text. Marker precedence mirrors the historical rich renderer
     * exactly (bold before italic, strike/links/code before single markers)
     * so unified rendering never pairs markers the old surface would not.
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

                // Strike: ~~x~~
                c == '~' && text.startsWith("~~", i) -> {
                    val close = text.indexOf("~~", i + 2)
                    if (close > i + 2) {
                        flushPlain()
                        spans += MdSpan.Strike(text.substring(i + 2, close))
                        i = close + 2
                    } else {
                        plain.append("~~")
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

                // Inline math: $x$
                c == '$' -> {
                    val close = text.indexOf('$', i + 1)
                    if (close > i + 1) {
                        flushPlain()
                        spans += MdSpan.Math(text.substring(i + 1, close))
                        i = close + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                // Inline math: \(x\)
                c == '\\' && text.startsWith("\\(", i) -> {
                    val close = text.indexOf("\\)", i + 2)
                    if (close > i + 2) {
                        flushPlain()
                        spans += MdSpan.Math(text.substring(i + 2, close))
                        i = close + 2
                    } else {
                        plain.append("\\(")
                        i += 2
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
    val codeBackground: Color,
    val linkColor: Color
)

/**
 * PURE spans → [AnnotatedString] (links are REAL [LinkAnnotation.Url]s the
 * Text framework resolves and opens through the platform handler). Math
 * spans are Unicode-normalized through [normalizeMath] (no LaTeX engine —
 * an honest, readable approximation).
 */
fun List<MdSpan>.toAnnotatedString(styles: MdSpanStyles): AnnotatedString =
    buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.Strike -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough)
                ) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = styles.codeBackground)
                ) { append(span.text) }
                is MdSpan.Math -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                ) { append(normalizeMath(span.text)) }
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
 * Unicode-normalizes a LaTeX-ish formula into readable text (moved verbatim
 * from the old rich renderer — the single math approximation of the app):
 * common commands → their Unicode twins, \frac{a}{b} → (a)/(b), simple
 * super/subscripts → Unicode scripts, remaining braces dropped.
 */
internal fun normalizeMath(source: String): String {
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
