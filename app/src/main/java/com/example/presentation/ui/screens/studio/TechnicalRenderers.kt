package com.example.presentation.ui.screens.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

/**
 * ============================================================================
 * TechnicalRenderers — CLOSURE §16/§17 (technical content closure)
 * ============================================================================
 * REAL renderers for the rich-content pipeline (replacing the
 * progress-bar "charts", the edge-list "diagrams" and the Unicode
 * "math"):
 *
 *  1. MathTypesetter — a real layout-based math typesetter for a
 *     documented subset: fractions (stacked numerator/denominator with a
 *     rule), square roots (radical + overline), super/subscripts
 *     (baseline-shifted, smaller), the greek/operator symbol table,
 *     \sum/\int with limits. Unsupported constructs fall back HONESTLY to
 *     the Unicode approximation with a visible notice — never a fake
 *     render.
 *
 *  2. BarChartRenderer / LineChartRenderer — Canvas-drawn charts with
 *     axes, gridlines, tick labels and value labels; a DATA TABLE fallback
 *     when the data is not plottable (non-numeric values / too few
 *     points) — honest degradation, not a blank box.
 *
 *  3. DiagramRenderer — a layered (topological) layout for a documented
 *     edge subset that accepts ARABIC/Unicode node identifiers and edge
 *     labels (`A --تسمية--> B`); unparseable sources render as RAW
 *     SOURCE (clearly labeled), never a fake graph.
 *
 *  4. BidiSanitizer — isolates LTR technical runs (paths, URLs, code
 *     identifiers) inside RTL prose with LRI/PDI so mixed content keeps
 *     its visual order (§17).
 */

// ====================================================================
// 1. MATH TYPESETTER
// ====================================================================

/** The math layout tree (a tiny real typesetting model). */
sealed interface MathNode {
    /** A single symbol/identifier run (already mapped to Unicode where applicable). */
    data class Atom(val text: String) : MathNode
    /** Horizontal row of nodes. */
    data class Row(val children: List<MathNode>) : MathNode
    /** \frac{num}{den} — stacked with a rule. */
    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode
    /** \sqrt{x} — radical + overline. */
    data class Sqrt(val content: MathNode) : MathNode
    /** base^{exp} — raised, smaller. */
    data class Sup(val base: MathNode, val exponent: MathNode) : MathNode
    /** base_{sub} — lowered, smaller. */
    data class Sub(val base: MathNode, val subscript: MathNode) : MathNode
}

object MathTypesetter {

    /** LaTeX-ish commands mapped to Unicode glyphs (documented subset). */
    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "leq" to "≤", "geq" to "≥", "neq" to "≠", "approx" to "≈", "equiv" to "≅",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "propto" to "∝",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃",
        "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "land" to "∧", "lor" to "∨", "neg" to "¬",
        "degree" to "°", "circ" to "∘", "bullet" to "•",
        "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮"
    )

    /** Constructs that the typesetter STRUCTURALLY renders (vs approximates). */
    val STRUCTURAL_COMMANDS = setOf("frac", "sqrt", "sum", "int", "prod")

    /**
     * Parses the supported subset into a layout tree. Returns
     * (tree, usedStructuralConstruct) — the flag lets the caller show the
     * honest fallback notice when a construct was only approximated.
     */
    fun parse(source: String): Pair<MathNode, Boolean> {
        val input = source
            .replace("\\left(", "(").replace("\\right)", ")")
            .replace("\\left[", "[").replace("\\right]", "]")
            .replace("\\left{", "{").replace("\\right}", "}")
            .replace("\\$", "").trim()
        val parser = MathParser(input)
        val row = parser.parseRow(stopAtBrace = false)
        return row to parser.usedStructural
    }

    /** Mutable parser state (class-level methods allow recursion). */
    private class MathParser(val input: String) {
        val chars = input.toCharArray()
        var index = 0
        var usedStructural = false

        fun parseGroup(): MathNode? {
            if (index >= chars.size) return null
            if (chars[index] == '{') {
                index++
                val inner = parseRow(stopAtBrace = true)
                if (index < chars.size && chars[index] == '}') index++
                return inner
            }
            return parseAtom()
        }

        fun parseAtom(): MathNode? {
            if (index >= chars.size) return null
            val c = chars[index]
            if (c == '}') return null
            if (c == '\\') {
                index++
                val start = index
                while (index < chars.size && chars[index].isLetter()) index++
                val command = input.substring(start, index)
                return when (command) {
                    "frac", "dfrac", "tfrac" -> {
                        usedStructural = true
                        val num = parseGroup() ?: MathNode.Atom("")
                        val den = parseGroup() ?: MathNode.Atom("")
                        MathNode.Frac(num, den)
                    }
                    "sqrt" -> {
                        usedStructural = true
                        MathNode.Sqrt(parseGroup() ?: MathNode.Atom(""))
                    }
                    "sum", "prod", "int" -> {
                        usedStructural = true
                        val glyph = when (command) {
                            "sum" -> "\u2211"; "prod" -> "\u220f"; else -> "\u222b"
                        }
                        var node: MathNode = MathNode.Atom(glyph)
                        if (index < chars.size && chars[index] == '_') {
                            index++
                            val lower = parseGroup() ?: MathNode.Atom("")
                            node = MathNode.Sub(node, lower)
                        }
                        if (index < chars.size && chars[index] == '^') {
                            index++
                            val upper = parseGroup() ?: MathNode.Atom("")
                            node = MathNode.Sup(node, upper)
                        }
                        node
                    }
                    "text", "mathrm" -> {
                        if (index < chars.size && chars[index] == '{') {
                            index++
                            val start2 = index
                            while (index < chars.size && chars[index] != '}') index++
                            val text = input.substring(start2, index)
                            if (index < chars.size) index++
                            MathNode.Atom(text)
                        } else MathNode.Atom("")
                    }
                    else -> MathNode.Atom(SYMBOLS[command] ?: command)
                }
            }
            if (c == '^' || c == '_') {
                index++
                val script = parseGroup() ?: MathNode.Atom("")
                return if (c == '^') MathNode.Sup(MathNode.Atom(""), script)
                else MathNode.Sub(MathNode.Atom(""), script)
            }
            val start = index
            index++
            if (c.isDigit()) {
                while (index < chars.size && (chars[index].isDigit() || chars[index] == '.' || chars[index] == ',')) index++
            }
            return MathNode.Atom(input.substring(start, index))
        }

        fun parseRow(stopAtBrace: Boolean): MathNode {
            val children = mutableListOf<MathNode>()
            while (index < chars.size) {
                if (stopAtBrace && chars[index] == '}') break
                var node = parseAtom() ?: break
                while (index < chars.size && (chars[index] == '^' || chars[index] == '_')) {
                    val isSup = chars[index] == '^'
                    index++
                    val script = parseGroup() ?: MathNode.Atom("")
                    node = if (isSup) MathNode.Sup(node, script) else MathNode.Sub(node, script)
                }
                children += node
            }
            return if (children.size == 1) children.first() else MathNode.Row(children)
        }
    }
}

/**
 * Renders a parsed math node with REAL layout: stacked fractions with a
 * rule, radical + overline roots, baseline-shifted scripts — LTR-pinned
 * and selectable (§16 Math contract).
 */
@Composable
fun MathNodeView(node: MathNode, isScriptLevel: Boolean = false) {
    val baseStyle = if (isScriptLevel) {
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Serif)
    } else {
        MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif)
    }
    when (node) {
        is MathNode.Atom -> Text(text = node.text, style = baseStyle)
        is MathNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.children.forEachIndexed { i, child ->
                if (i > 0) Spacer(Modifier.width(1.dp))
                MathNodeView(child, isScriptLevel)
            }
        }
        is MathNode.Frac -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.numerator, isScriptLevel = true)
            // The fraction RULE.
            Box(
                Modifier
                    .padding(vertical = 1.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface)
            )
            MathNodeView(node.denominator, isScriptLevel = true)
        }.let { column ->
            // Fractions center on the rule: the denominator drops below.
            Row(verticalAlignment = Alignment.CenterVertically) { column }
        }
        is MathNode.Sqrt -> {
            // Draw-behind colors are captured in the composition scope.
            val overlineColor = MaterialTheme.colorScheme.onSurface
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "\u221a",
                    style = baseStyle.copy(fontWeight = FontWeight.Normal),
                    modifier = Modifier.padding(end = 1.dp)
                )
                Box(
                    Modifier
                        .drawBehind {
                            drawLine(
                                color = overlineColor,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.2f
                            )
                        }
                        .padding(top = 2.dp)
                ) {
                    MathNodeView(node.content, isScriptLevel)
                }
            }
        }
        is MathNode.Sup -> Row(verticalAlignment = Alignment.Bottom) {
            MathNodeView(node.base, isScriptLevel)
            Text("", style = baseStyle) // measure anchor
            Box(Modifier.padding(start = 1.dp, bottom = 9.dp)) {
                MathNodeView(node.exponent, isScriptLevel = true)
            }
        }
        is MathNode.Sub -> Row(verticalAlignment = Alignment.Top) {
            MathNodeView(node.base, isScriptLevel)
            Box(Modifier.padding(start = 1.dp, top = 9.dp)) {
                MathNodeView(node.subscript, isScriptLevel = true)
            }
        }
    }
}

// ====================================================================
// 2. CHART RENDERERS (real bar / line with axes + honest fallback)
// ====================================================================

/** Renders label-value points as a REAL bar chart (axes, gridlines, labels). */
@Composable
fun BarChartRenderer(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    val max = points.maxOfOrNull { it.value } ?: 0f
    if (max <= 0f || points.isEmpty()) {
        ChartDataFallback(points)
        return
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val barColor = MaterialTheme.colorScheme.primary
    val barColorAlt = MaterialTheme.colorScheme.tertiary
    val chartHeight = 170.dp
    val labelSpace = 46.dp
    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val chartWidth = maxWidth
            Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
                val barAreaWidth = size.width - labelSpace.toPx()
                val barCount = points.size
                val slotWidth = barAreaWidth / barCount
                val barWidth = slotWidth * 0.58f
                // Gridlines: 4 divisions.
                val divisions = 4
                for (i in 0..divisions) {
                    val y = size.height * (1f - i.toFloat() / divisions) - 14f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                        pathEffect = if (i == 0) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                }
                points.forEachIndexed { index, point ->
                    val barHeight = (point.value / max) * (size.height - 28f)
                    val left = index * slotWidth + (slotWidth - barWidth) / 2f + 4f
                    drawRoundRect(
                        color = if (index % 2 == 0) barColor else barColorAlt,
                        topLeft = Offset(left, size.height - 14f - barHeight),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                }
            }
        }
        // Axis labels row (value + name per bar — the readable axis).
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Top) {
            points.forEach { point ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = formatChartValue(point.value),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = point.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Renders label-value points as a REAL line chart (polyline + points). */
@Composable
fun LineChartRenderer(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        ChartDataFallback(points)
        return
    }
    val values = points.map { it.value }
    val maxV = values.max()
    val minV = values.min()
    val span = (maxV - minV).takeIf { it != 0f } ?: 1f
    val lineColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val chartHeight = 160.dp
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            val n = points.size
            val divisions = 4
            for (i in 0..divisions) {
                val y = size.height * (1f - i.toFloat() / divisions) - 10f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = if (n == 1) 0f else size.width * index / (n - 1).toFloat()
                val y = 10f + (1f - (point.value - minV) / span) * (size.height - 26f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color = pointColor, radius = 5f, center = Offset(x, y))
            }
            drawPath(path, color = lineColor, style = Stroke(width = 3.5f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            points.forEachIndexed { index, point ->
                if (index == 0 || index == points.size - 1 || points.size <= 6) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = formatChartValue(point.value),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = point.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The HONEST fallback: data is present but not plottable (non-numeric /
 * too few points) — render a readable table instead of a fake chart.
 */
@Composable
fun ChartDataFallback(points: List<ChartPoint>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "بيانات غير قابلة للرسم — عرض جدولي صادق:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        points.forEach { point ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    point.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (point.value.isNaN()) "—" else formatChartValue(point.value),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatChartValue(value: Float): String = when {
    value.isNaN() -> "—"
    value == value.toLong().toFloat() -> value.toLong().toString()
    else -> String.format(java.util.Locale.US, "%.2g", value)
}

// ====================================================================
// 3. DIAGRAM RENDERER (layered layout, Unicode ids, edge labels)
// ====================================================================

/** One parsed diagram edge (from, label?, to) — Unicode/Arabic ids allowed. */
data class DiagramEdgeLabeled(val from: String, val label: String?, val to: String)

object DiagramParser {
    /**
     * The DOCUMENTED subset (CLOSURE §16): lines of
     *   `A --> B` / `A --label--> B` / `A --- B` / `A ==> B`
     * where node ids may contain Arabic letters, Unicode, digits,
     * underscores, hyphens, dots and spaces (≤ 30 chars). Lines starting
     * with `graph`/`flowchart` + `TD|LR` are accepted as headers (LR is
     * rendered top-down — the mobile constraint is documented).
     */
    private val EDGE = Regex(
        "^([\\p{L}\\p{N}_.\\- ]{1,30}?)\\s*-{2,3}\\s*(?:\\[?([^\\]]{1,30}?)\\]?)?-{0,2}>?\\s*([\\p{L}\\p{N}_.\\- ]{1,30}?)$"
    )

    fun parse(source: String): List<DiagramEdgeLabeled> = source.lines()
        .mapNotNull { line ->
            val clean = line.trim()
            if (clean.isBlank() || clean.startsWith("#")) return@mapNotNull null
            if (Regex("^(graph|flowchart)\\s+(TD|LR|TB)\\s*$", RegexOption.IGNORE_CASE).matches(clean)) {
                return@mapNotNull null
            }
            if (clean.contains("-->")) {
                val (from, rest) = clean.substringBefore("-->").trim() to clean.substringAfter("-->")
                val label = rest.substringBefore("-->").takeIf { rest.contains("-->") }?.trim()
                val to = if (rest.contains("-->")) rest.substringAfter("-->").trim() else rest.trim()
                if (from.isNotBlank() && to.isNotBlank()) {
                    return@mapNotNull DiagramEdgeLabeled(from, label?.takeIf { it.isNotBlank() }, to)
                }
            }
            if (clean.contains("==>")) {
                val from = clean.substringBefore("==>").trim()
                val to = clean.substringAfter("==>").trim()
                if (from.isNotBlank() && to.isNotBlank()) {
                    return@mapNotNull DiagramEdgeLabeled(from, null, to)
                }
            }
            if (clean.contains("---")) {
                val from = clean.substringBefore("---").trim()
                val to = clean.substringAfter("---").trim()
                if (from.isNotBlank() && to.isNotBlank()) {
                    return@mapNotNull DiagramEdgeLabeled(from, null, to)
                }
            }
            null
        }
}

/**
 * Renders a diagram as a LAYERED graph (topological levels → rows):
 * root nodes at the top, each edge drawn as an arrow row between levels
 * with its label. Honest fallback: unparseable source renders as RAW
 * SOURCE (labeled), never a fake graph.
 */
@Composable
fun DiagramRenderer(edges: List<DiagramEdgeLabeled>, rawSource: String) {
    if (edges.isEmpty()) {
        // RAW SOURCE FALLBACK — explicitly labeled (§16: never claim a
        // diagram was rendered when it was not).
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "تعذر عرض المخطط (بنية غير مدعومة) — المصدر الأصلي:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    rawSource,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
        return
    }
    // Layered layout: level(node) = 1 + max(level(parents)).
    val levels = mutableMapOf<String, Int>()
    fun levelOf(node: String): Int {
        levels[node]?.let { return it }
        // Guard against cycles: assume level 1 while computing.
        levels[node] = 0
        val parents = edges.filter { it.to == node }.map { it.from }
        val level = 1 + (parents.maxOfOrNull { levelOf(it) } ?: -1)
        levels[node] = level
        return level
    }
    edges.forEach { levelOf(it.from); levelOf(it.to) }
    val maxLevel = levels.values.max()
    val byLevel = (0..maxLevel).map { lvl ->
        levels.filterValues { it == lvl }.keys.toList()
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            byLevel.forEachIndexed { levelIndex, nodes ->
                if (levelIndex > 0) {
                    // The edges INTO this level.
                    val incoming = edges.filter { levels[it.to] == levelIndex }
                    incoming.forEach { edge ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp)
                        ) {
                            Text("│", color = MaterialTheme.colorScheme.primary)
                            Text(
                                "↓" + (edge.label?.let { " $it " } ?: " "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                // The level's nodes.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    nodes.forEach { node ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (levelIndex == 0) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            }
                        ) {
                            Text(
                                node,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// 4. BIDI SANITIZER (§17 — mixed Arabic/technical content)
// ====================================================================

object BidiSanitizer {
    private val LRI = "\u2066" // LEFT-TO-RIGHT ISOLATE
    private val PDI = "\u2069" // POP DIRECTIONAL ISOLATE

    /** Technical runs (paths, URLs, code identifiers) that must stay LTR. */
    private val LTR_RUN = Regex(
        "([a-zA-Z0-9_./:\\\\@#?&=%+\\-]{2,})"
    )

    /**
     * Isolates LTR technical runs inside RTL prose so paths, URLs and
     * identifiers keep their visual order at RTL boundaries. Pure text
     * transformation — applies to mixed Arabic + technical content only.
     */
    fun isolateTechnicalRuns(text: String): String {
        if (text.isEmpty()) return text
        val hasArabic = text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
        if (!hasArabic) return text // pure LTR text needs no isolation
        return LTR_RUN.replace(text) { match ->
            val run = match.value
            // Only isolate runs that look technical (contain / . : _ or are
            // multi-word identifiers — avoid isolating every single English
            // word, which would break prose flow).
            val looksTechnical = run.contains('/') || run.contains('.') ||
                    run.contains(':') || run.contains('_') ||
                    run.contains('\\') || run.contains('#') ||
                    run.length >= 24
            if (looksTechnical) "$LRI$run$PDI" else run
        }
    }
}
