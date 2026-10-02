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

    /** ROUND-4: a thematic break (`---`, `***`, `___` alone on a line) — the
     * models emit them between sections; they used to accumulate into the
     * paragraph as literal "---" noise inside the rendered bubble. */
    data object HorizontalRule : MdBlock
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

    /** ROUND-4: `---` / `***` / `___` thematic breaks (never a table
     * separator — those carry a `|`; never a list marker — those have no
     * repeat count of 3+). */
    private val thematicBreakRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")

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

            // ROUND-4: thematic break — a lone --- / *** / ___ line.
            if (thematicBreakRegex.matches(trimmed)) {
                flushParagraph()
                blocks += MdBlock.HorizontalRule
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
                    SpanStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp)
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
 * Unicode-normalizes a LaTeX-ish formula into readable text — the INLINE
 * math approximation of the app (display math goes through the REAL
 * [MathTypesetter] layout tree).
 *
 * ROUND-4 REWRITE (the Navier–Stokes report): the old approximation was
 * regex-based and fell apart on exactly what real models emit —
 *  * `\frac{\partial \mathbf{u}}{\partial t}` never matched the
 *    `[^{}]+` fraction regex (NESTED braces) and survived as raw
 *    backslash noise;
 *  * `\partial`, `\mathbf`, `\displaystyle`, `\!`, `\nu`… were not in
 *    the hand-copied symbol list at all;
 *  * `^{2}` / `_{n}` braced scripts stayed literal;
 *  * the bare `\left`/`\right` replaces corrupted `\rightarrow`
 *    (a prefix collision).
 * The rewrite is brace-matching (nesting-safe), shares ONE symbol table
 * with [MathTypesetter] (longest-key-first so `\notin` beats `\in`,
 * `\int` beats `\in`), strips style/spacing macros, converts braced
 * scripts, and attaches accent marks as Unicode combining characters.
 * Still an approximation — but an honest, complete one.
 */
internal fun normalizeMath(source: String): String {
    var value = source.replace("\r", " ").replace("\n", " ")

    // \left / \right pair markers — lookahead keeps \rightarrow intact.
    value = value.replace(Regex("\\\\(left|right)(?![a-zA-Z])"), "")

    // Structural commands (brace-matched, nesting-safe, repeatable).
    value = replaceLaTeXCommand(value, "dfrac") { args -> "(${args[0]})/(${args[1]})" }
    value = replaceLaTeXCommand(value, "tfrac") { args -> "(${args[0]})/(${args[1]})" }
    value = replaceLaTeXCommand(value, "frac") { args -> "(${args[0]})/(${args[1]})" }
    value = replaceLaTeXCommand(value, "sqrt") { args -> "√(${args[0]})" }

    // Style commands: keep the CONTENT only (bold/accents have no inline
    // equivalent inside an AnnotatedString run).
    for (style in LATEX_STYLE_COMMANDS) {
        value = replaceLaTeXCommand(value, style) { args -> args[0] }
    }
    // Accents: attach the combining mark to the first character.
    for ((command, mark) in LATEX_ACCENT_MARKS) {
        value = replaceLaTeXCommand(value, command) { args ->
            args[0].let { content ->
                if (content.isEmpty()) content else content[0] + mark.toString() + content.substring(1)
            }
        }
    }
    // Zero-argument mode/size macros: stripped WITHOUT touching the
    // delimiter that follows (`\bigl(` must keep its parenthesis).
    value = value.replace(ZERO_ARG_MACRO_REGEX, "")
    value = value.replace("\\quad", "  ").replace("\\qquad", "    ")
    value = value.replace("\\!", "").replace("\\,", " ")
        .replace("\\;", " ").replace("\\:", " ")
        .replace("\\ ", " ")

    // Symbol table — SHARED with the real typesetter, longest key first
    // (so \notin never partially matches as \not/\in, \int as \in…).
    for ((command, glyph) in MathTypesetter.SYMBOLS.entries.sortedByDescending { it.key.length }) {
        value = value.replace("\\$command", glyph)
    }

    // Braced scripts ^{...} / _{...}, then bare ^2 / _3.
    value = replaceBracedScript(value, '^', superscriptMap)
    value = replaceBracedScript(value, '_', subscriptMap)
    value = replaceSimpleScript(value, '^', superscriptMap)
    value = replaceSimpleScript(value, '_', subscriptMap)

    return value.replace("{", "").replace("}", "")
        .replace(Regex(" {2,}"), " ").trim()
}

/** Style commands whose argument is kept verbatim in the approximation. */
private val LATEX_STYLE_COMMANDS = listOf(
    "mathbf", "mathit", "mathrm", "mathsf", "mathtt", "bm", "boldsymbol",
    "mathcal", "mathbb", "mathfrak",
    "text", "mbox", "operatorname", "ensuremath", "textbf", "textit"
)

/** Accent commands → Unicode combining marks (attached to the first char). */
private val LATEX_ACCENT_MARKS = listOf(
    "vec" to '\u20D7', "hat" to '\u0302', "widehat" to '\u0302',
    "tilde" to '\u0303', "widetilde" to '\u0303', "dot" to '\u0307',
    "ddot" to '\u0308', "bar" to '\u0304', "overline" to '\u0304',
    "underline" to '\u0332'
)

/** Mode/size macros that take NO argument — removed with a lookahead so
 * the delimiter they scale (`\bigl(` …) survives untouched. */
private val ZERO_ARG_MACRO_REGEX = Regex(
    "\\\\(displaystyle|textstyle|limits|nolimits|middle|bigl|bigr|Bigl|Bigr|biggl|biggr|bigg|Bigg|big|Big)(?![a-zA-Z])"
)

/**
 * Replaces every well-formed `\command{arg}…{arg}` occurrence (and the
 * single-token form `\command x`) with [transform] of its arguments.
 * BRACE-MATCHED: nested groups count depth, so
 * `\frac{\partial u}{\partial t}` parses correctly where the old
 * `[^{}]+` regex could not. A command that is the PREFIX of a longer one
 * (`\ge` inside `\geq`) is skipped via a letter-boundary check; an
 * unclosed argument leaves the rest of the input untouched (tolerance).
 */
private fun replaceLaTeXCommand(
    value: String,
    command: String,
    argCount: Int = 1,
    transform: (List<String>) -> String
): String {
    val token = "\\$command"
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        val idx = value.indexOf(token, i)
        if (idx < 0) {
            out.append(value, i, value.length)
            break
        }
        val after = idx + token.length
        val isFullCommand = after >= value.length || !value[after].isLetter()
        if (!isFullCommand) {
            // A longer command (\geq seen as \ge): copy verbatim, rescan after it.
            out.append(value, i, after)
            i = after
            continue
        }
        out.append(value, i, idx)
        var j = after
        val args = mutableListOf<String>()
        var wellFormed = true
        repeat(argCount) {
            while (j < value.length && value[j] == ' ') j++
            val group = readGroup(value, j)
            if (group == null) {
                wellFormed = false
            } else {
                args += group.first
                j = group.second
            }
        }
        if (!wellFormed) {
            // Unclosed argument: keep the rest verbatim (honest tolerance).
            out.append(value, idx, value.length)
            return out.toString()
        }
        out.append(transform(args))
        i = j
    }
    return out.toString()
}

/** Reads one LaTeX group from [from]: `{…}` (depth-counted, nesting-safe)
 * or a single token character when no brace follows. Returns (content,
 * indexAfter) or null when nothing readable remains. */
private fun readGroup(value: String, from: Int): Pair<String, Int>? {
    if (from >= value.length) return null
    if (value[from] != '{') {
        return value[from].toString() to from + 1
    }
    var depth = 0
    var i = from
    while (i < value.length) {
        when (value[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return value.substring(from + 1, i) to (i + 1)
            }
        }
        i++
    }
    return null // unclosed
}

/** Braced scripts: `^{2}`→², `_{n}`→ₙ (per-character via [map]; characters
 * without a Unicode twin stay literal — the honest approximation). */
private fun replaceBracedScript(value: String, marker: Char, map: Map<Char, Char>): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        if (value[i] == marker && i + 1 < value.length && value[i + 1] == '{') {
            val group = readGroup(value, i + 1)
            if (group != null) {
                out.append(group.first.map { map[it] ?: it })
                i = group.second
                continue
            }
        }
        out.append(value[i])
        i++
    }
    return out.toString()
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
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ'
)

private val subscriptMap = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
    'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'x' to 'ₓ'
)
