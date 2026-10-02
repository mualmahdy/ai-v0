package com.example.presentation

import com.example.application.governed.CodingToolchainService
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolParameter
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import com.example.presentation.ui.screens.studio.BidiSanitizer
import com.example.presentation.ui.screens.studio.ChatMarkdownParser
import com.example.presentation.ui.screens.studio.MathNode
import com.example.presentation.ui.screens.studio.MathRegionSplitter
import com.example.presentation.ui.screens.studio.MathTypesetter
import com.example.presentation.ui.screens.studio.MdBlock
import com.example.presentation.ui.screens.studio.RichChatBlock
import com.example.presentation.ui.screens.studio.RichChatParser
import com.example.presentation.ui.screens.studio.normalizeMath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * HOTFIX ROUND 4 — REGRESSION SUITE (the experiential layer, take four)
 * ============================================================================
 *
 * Pins the round-4 root causes against the two reported symptoms:
 *
 *  - "HTTP 400 from Gemini stream — GenerateContentRequest.tools[0]
 *    .function_declarations[2].parameters.properties[hunks].items:
 *    missing field"
 *    → Gemini's function-declaration schema (an OpenAPI subset) REQUIRES an
 *      `items` element for every ARRAY-typed property. apply_patch declares
 *      `hunks: array` with no item shape — OpenAI-compatible providers
 *      tolerate that, Gemini rejects the ENTIRE request. The declaration
 *      now carries the full item schema (object with expect/replaceWith),
 *      and the adapter enforces a defensive items fallback so no future
 *      catalog edit can 400 the whole chat.
 *
 *  - "math renders as raw LaTeX noise" (the Navier–Stokes report)
 *    → (a) the Bidi sanitizer wrapped LaTeX command runs (`\frac`,
 *        `\partial`, `\mathbf`) in invisible LRI/PDI isolates BEFORE
 *        parsing, corrupting both the structural typesetter and the
 *        Unicode approximation;
 *      (b) the typesetter rendered `\mathbf{u}` LITERALLY and the first
 *        unmatched `}` TRUNCATED the rest of the equation;
 *      (c) the approximation's `[^{}]+` fraction regex could not handle
 *        NESTED braces (`\frac{\partial \mathbf{u}}{\partial t}`) and its
 *        hand-copied symbol list was missing \partial, \nu, \theta…;
 *      (d) only the delimiters-alone-on-their-lines display-math shape
 *        parsed as math — single-line `\[…\]` / `$$…$$` and
 *        opener-with-content shapes fell to the paragraph path;
 *      (e) table cells bypassed the inline parser (`\(\rho\)` raw).
 */
class HotfixRound4RegressionTest {

    // ------------------------------------------------------------------
    // 1. Gemini 400: array tool parameters MUST carry an items schema
    // ------------------------------------------------------------------

    @Test
    fun `gemini apply_patch hunks parameter carries the full items schema`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(LlmMessage(role = MessageRole.USER, content = "عدّل الملف")),
            availableTools = listOf(applyPatchDeclaration())
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = true))
        val declarations = body.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")

        val applyPatch = declarations.getJSONObject(0)
        val hunks = applyPatch.getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("hunks")

        // The exact field Gemini 400'd on: properties[hunks].items.
        assertTrue("hunks must be array-typed", hunks.getString("type") == "array")
        val items = hunks.optJSONObject("items")
        assertNotNull(
            "GenerateContentRequest rejects the whole request with " +
                "400 INVALID_ARGUMENT 'properties[hunks].items: missing field' " +
                "when an array property carries no items schema",
            items
        )
        assertEquals("object", items!!.getString("type"))
        val itemProps = items.getJSONObject("properties")
        assertEquals("string", itemProps.getJSONObject("expect").getString("type"))
        assertEquals("string", itemProps.getJSONObject("replaceWith").getString("type"))
        val required = items.getJSONArray("required")
        assertEquals(setOf("expect", "replaceWith"), (0 until required.length()).map { required.getString(it) }.toSet())
    }

    @Test
    fun `gemini bare array parameter without a declared item type gets the defensive string items schema`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val unshaped = ToolDeclaration(
            name = "bulk_write",
            description = "أداة مستقبلية بمصفوفة غير موصوفة",
            parameters = listOf(
                ToolParameter("tags", "array", "وسوم", isRequired = true),
                ToolParameter("note", "string", "ملاحظة", isRequired = false)
            )
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage(role = MessageRole.USER, content = "x")),
            availableTools = listOf(unshaped)
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = true))
        val tags = body.getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("tags")
        // Never a bare "array" again — an unshaped array degrades to string
        // items instead of poisoning the entire request with a 400.
        assertEquals("string", tags.getJSONObject("items").getString("type"))
    }

    @Test
    fun `every array property in the whole gemini tools section carries items - property scan`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(LlmMessage(role = MessageRole.USER, content = "x")),
            availableTools = CodingToolchainService.declarations.values.take(12)
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = true))
        val tools: JSONArray = body.getJSONArray("tools")
        var arrayProperties = 0
        for (t in 0 until tools.length()) {
            val decls = tools.getJSONObject(t).getJSONArray("functionDeclarations")
            for (d in 0 until decls.length()) {
                val params = decls.getJSONObject(d).optJSONObject("parameters") ?: continue
                val props = params.optJSONObject("properties") ?: continue
                // ROUND-4b: android.jar's org.json.JSONObject has no
                // keySet() (the AOSP subset shadows the Maven org.json on
                // the unit-test compile classpath) — keys() is the API that
                // compiles against BOTH variants.
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val prop = props.getJSONObject(key)
                    if (prop.optString("type") == "array") {
                        arrayProperties++
                        assertTrue(
                            "property '$key' is array-typed but carries no items — Gemini 400 family",
                            prop.has("items")
                        )
                    }
                }
            }
        }
        assertTrue("the scan must actually exercise array properties", arrayProperties >= 1)
    }

    @Test
    fun `the catalog itself declares the hunks item shape - the domain source of truth`() {
        val applyPatch = CodingToolchainService.declarations.getValue("apply_patch")
        val hunks = applyPatch.parameters.single { it.name == "hunks" }
        assertEquals("array", hunks.type)
        assertEquals("object", hunks.itemType)
        assertEquals(
            setOf("expect", "replaceWith"),
            hunks.itemProperties.map { it.name }.toSet()
        )
    }

    @Test
    fun `openai adapter mirrors the items schema for array parameters`() {
        val adapter = OpenAiCompatibleLlmAdapter(
            baseUrl = "http://localhost:8080",
            apiKeyProvider = { null },
            defaultModel = "test-model"
        )
        val request = LlmRequest(
            messages = listOf(LlmMessage(role = MessageRole.USER, content = "x")),
            availableTools = listOf(applyPatchDeclaration())
        )
        val body = adapter.buildJsonBody(request, stream = true)
        val hunks = body.getJSONArray("tools").getJSONObject(0)
            .getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties").getJSONObject("hunks")
        val items = hunks.optJSONObject("items")
        assertNotNull("the two adapters share one declaration contract", items)
        assertEquals("object", items!!.getString("type"))
        assertEquals("string", items.getJSONObject("properties").getJSONObject("expect").getString("type"))
    }

    private fun applyPatchDeclaration(): ToolDeclaration = ToolDeclaration(
        name = "apply_patch",
        description = "الأداة المفضلة للتعديل",
        parameters = listOf(
            ToolParameter("path", "string", "المسار", isRequired = true),
            ToolParameter(
                "hunks", "array", "قائمة الرقع", isRequired = true,
                itemType = "object",
                itemProperties = listOf(
                    ToolParameter("expect", "string", "النص الحالي", isRequired = true),
                    ToolParameter("replaceWith", "string", "البديل", isRequired = true)
                )
            )
        )
    )

    // ------------------------------------------------------------------
    // 2. Bidi sanitizer: math regions are off-limits to LRI/PDI isolation
    // ------------------------------------------------------------------

    @Test
    fun `bidi sanitizer never wraps latex runs inside math regions`() {
        val message = "المعادلة \\(\\frac{\\partial \\mathbf{u}}{\\partial t}\\) " +
            "والمسار src/main/java/File.kt موجود"
        val sanitized = BidiSanitizer.isolateTechnicalRuns(message)

        // Math body byte-identical: no LRI/PDI between the backslash and the
        // command (that corrupted both the typesetter and the approximation).
        assertTrue(sanitized.contains("\\frac{\\partial \\mathbf{u}}{\\partial t}"))
        assertFalse(
            "the LaTeX command run must not be isolated — the invisible marks break command tokenization",
            sanitized.contains("\u2066\\frac")
        )
        assertFalse(sanitized.contains("\\frac\u2069"))
        // The REAL technical identifier outside math is still isolated (R2/R3
        // bidi correctness untouched).
        assertTrue(sanitized.contains("\u2066src/main/java/File.kt\u2069"))
    }

    @Test
    fun `bidi sanitizer keeps display math regions intact too`() {
        val message = "الشرح:\n\\[\n\\rho \\left( \\frac{\\partial \\mathbf{u}}{\\partial t}\\right)\n\\]\nوالتتمة عربية."
        val sanitized = BidiSanitizer.isolateTechnicalRuns(message)
        assertTrue(sanitized.contains("\\rho \\left( \\frac{\\partial \\mathbf{u}}{\\partial t}\\right)"))
        assertFalse(sanitized.contains("\u2066\\rho"))
    }

    @Test
    fun `math region splitter segments the four delimiter shapes`() {
        val segments = MathRegionSplitter.split(
            "قبل \\(a+b\\) وسط \\[x^2\\] آخر \$\$y\$\$ ونهاية"
        )
        val math = segments.filter { it.isMath }.map { it.text }
        // ROUND-4b: the dollars are escaped (\$) — a bare "$$y" is a
        // Kotlin string TEMPLATE (\$y → unresolved 'y') and broke the
        // round-4 patch's own test compilation.
        assertEquals(listOf("\\(a+b\\)", "\\[x^2\\]", "\$\$y\$\$"), math)
        // The prose segments concatenate back to the text around the math.
        assertEquals("قبل  وسط  آخر  ونهاية", segments.filter { !it.isMath }.joinToString("") { it.text })
    }

    @Test
    fun `an unclosed inline math region stops at its line - it cannot swallow the message`() {
        val text = "نص \\\\(غير مغلق\nسطر عربي آخر src/main/File.kt"
        val segments = MathRegionSplitter.split(text)
        val math = segments.filter { it.isMath }.single()
        assertFalse(math.text.contains("سطر"))
        // The second line still gets its isolation pass.
        val sanitized = BidiSanitizer.isolateTechnicalRuns(text)
        assertTrue(sanitized.contains("\u2066src/main/File.kt\u2069"))
    }

    @Test
    fun `prose dollars without a latex signal are not mistaken for math`() {
        val segments = MathRegionSplitter.split("السعر 5$ والتوصيل 7$ معاً")
        assertTrue(segments.none { it.isMath })
    }

    // ------------------------------------------------------------------
    // 3. MathTypesetter: mathbf family, stray braces, spacing macros
    // ------------------------------------------------------------------

    @Test
    fun `mathbf parses its group and renders bold - never the literal command`() {
        val (tree, _) = MathTypesetter.parse("a = \\mathbf{u} + b")
        val atoms = flattenAtoms(tree)
        // The literal "mathbf" text used to render as an atom; the group
        // content now arrives parsed and bolded.
        assertTrue(atoms.none { it.text == "mathbf" })
        assertTrue(atoms.none { it.text == "{" })
        val u = atoms.single { it.text == "u" }
        assertTrue("vectors/tensors keep their bold identity", u.bold)
        // No truncation: the token after the closed group survives.
        assertTrue(atoms.any { it.text == "b" })
    }

    @Test
    fun `an unmatched closing brace no longer truncates the rest of the formula`() {
        val (tree, _) = MathTypesetter.parse("a}b + c")
        val atoms = flattenAtoms(tree)
        // Pre-round-4 the row loop BROKE at the first unmatched '}' and
        // silently dropped everything after it.
        assertTrue(atoms.any { it.text == "b" })
        assertTrue(atoms.any { it.text == "c" })
    }

    @Test
    fun `mode and spacing macros render as spacing - never as literal words`() {
        val (tree, _) = MathTypesetter.parse("\\displaystyle x \\! + \\, y")
        val atoms = flattenAtoms(tree)
        assertTrue(atoms.none { it.text == "displaystyle" })
        assertTrue(atoms.none { it.text == "!" })
        assertTrue(atoms.none { it.text == "," })
        assertTrue(atoms.any { it.text == "x" })
        assertTrue(atoms.any { it.text == "y" })
    }

    @Test
    fun `the navier-stokes report formula parses structurally end to end`() {
        val formula = """
            \rho \left( \frac{\partial \mathbf{u}}{\partial t}
            + (\mathbf{u}\!\cdot\!\nabla)\mathbf{u}\right)
            = -\nabla p
            + \mu \nabla^{2}\mathbf{u}
            + \mathbf{f}
        """.trimIndent()
        val (tree, structural) = MathTypesetter.parse(formula)
        // \frac → the REAL stacked-fraction layout path (not the approximation).
        assertTrue("the formula uses structural constructs — it must render as a layout tree", structural)
        val atoms = flattenAtoms(tree)
        assertTrue(atoms.none { it.text == "mathbf" })
        assertTrue(atoms.none { it.text == "left" })
        assertTrue(atoms.none { it.text == "partial" })
        // The multi-line body does not leak literal line-break atoms.
        assertTrue(atoms.none { it.text.contains('\n') })
        // The final \mathbf{f} survived: no truncation anywhere in the chain.
        assertTrue(atoms.any { it.text == "f" })
    }

    @Test
    fun `vec accent attaches a combining mark to the first character`() {
        val (tree, _) = MathTypesetter.parse("\\vec{v} + u")
        val atoms = flattenAtoms(tree)
        val v = atoms.single { it.text.startsWith("v") }
        assertEquals("v\u20D7", v.text)
    }

    private fun flattenAtoms(node: MathNode): List<MathNode.Atom> = when (node) {
        is MathNode.Atom -> listOf(node)
        is MathNode.Row -> node.children.flatMap { flattenAtoms(it) }
        is MathNode.Frac -> flattenAtoms(node.numerator) + flattenAtoms(node.denominator)
        is MathNode.Sqrt -> flattenAtoms(node.content)
        is MathNode.Sup -> flattenAtoms(node.base) + flattenAtoms(node.exponent)
        is MathNode.Sub -> flattenAtoms(node.base) + flattenAtoms(node.subscript)
    }

    // ------------------------------------------------------------------
    // 4. normalizeMath: nested braces, shared symbols, braced scripts
    // ------------------------------------------------------------------

    @Test
    fun `nested-brace fractions approximate correctly - the old regex could not match them`() {
        val result = normalizeMath("\\frac{\\partial \\mathbf{u}}{\\partial t}")
        // The old `[^{}]+` regex failed on the nested \mathbf{u} and left
        // raw backslash noise; the brace-matched rewrite resolves it.
        assertFalse("no raw backslash may survive the approximation", result.contains("\\"))
        assertTrue(result.contains("∂ u"))
        assertTrue(result.contains("∂ t"))
        assertTrue(result.contains(")/("))
    }

    @Test
    fun `the approximation shares the typesetter symbol table`() {
        assertEquals("ν = μ/ρ", normalizeMath("\\nu = \\mu/\\rho"))
        assertTrue(normalizeMath("\\theta + \\Theta").contains("θ"))
        assertTrue(normalizeMath("\\theta + \\Theta").contains("Θ"))
    }

    @Test
    fun `braced superscripts convert to unicode scripts`() {
        assertEquals("∇²", normalizeMath("\\nabla^{2}"))
        assertTrue(normalizeMath("x^{n}").contains("ⁿ"))
    }

    @Test
    fun `left-right pairs keep their delimiters and rightarrow survives`() {
        assertEquals("(x)", normalizeMath("\\left(x\\right)"))
        // The old bare `\\right` replace corrupted \\rightarrow into "arrow".
        assertEquals("→", normalizeMath("\\rightarrow"))
        assertEquals("a → b", normalizeMath("a \\rightarrow b"))
    }

    @Test
    fun `displaystyle and thin spaces vanish from the approximation`() {
        val result = normalizeMath("\\displaystyle \\rho \\! = \\, 1")
        assertFalse(result.contains("displaystyle"))
        assertFalse(result.contains("!"))
        assertEquals("ρ = 1", result)
    }

    // ------------------------------------------------------------------
    // 5. Display-math shapes the rich scanner accepts
    // ------------------------------------------------------------------

    @Test
    fun `single-line bracket display math parses as a math block`() {
        val blocks = RichChatParser.parse("مقدمة عربية\n\\[ E = mc^2 \\]\nخاتمة")
        val math = blocks.filterIsInstance<RichChatBlock.MathBlock>().single()
        assertEquals("E = mc^2", math.formula)
    }

    @Test
    fun `single-line dollar display math parses as a math block`() {
        val blocks = RichChatParser.parse("$$ a^2 + b^2 = c^2 $$")
        assertEquals("a^2 + b^2 = c^2", blocks.filterIsInstance<RichChatBlock.MathBlock>().single().formula)
    }

    @Test
    fun `an opener with trailing content closes at the delimiter line`() {
        val blocks = RichChatParser.parse("\\[ E = mc^2\n+ \\gamma m v\n\\]\nتم")
        val math = blocks.filterIsInstance<RichChatBlock.MathBlock>().single()
        assertTrue(math.formula.contains("E = mc^2"))
        assertTrue(math.formula.contains("+ \\gamma m v"))
    }

    @Test
    fun `the classic alone-on-their-lines shape still parses`() {
        val blocks = RichChatParser.parse("$$\n\\int_0^1 x dx\n$$")
        assertEquals("\\int_0^1 x dx", blocks.filterIsInstance<RichChatBlock.MathBlock>().single().formula)
    }

    @Test
    fun `tables still route to the dedicated table block after the math changes`() {
        val blocks = RichChatParser.parse("| الرمز | المعنى |\n|---|---|\n| α | زاوية |")
        assertTrue(blocks.filterIsInstance<RichChatBlock.Table>().isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 6. Thematic breaks + table cell content model
    // ------------------------------------------------------------------

    @Test
    fun `a lone dashed line parses as a thematic break - not literal noise`() {
        val blocks = ChatMarkdownParser.parse("فقرة أولى\n---\nفقرة ثانية")
        assertTrue(blocks.any { it is MdBlock.HorizontalRule })
        // And the surrounding paragraphs stay paragraphs.
        assertEquals(2, blocks.filterIsInstance<MdBlock.Paragraph>().size)
    }

    @Test
    fun `a table separator line is never a thematic break`() {
        val blocks = ChatMarkdownParser.parse("| العمود الأول | العمود الثاني |\n|:---|:---|\n| قيمة | قيمة |")
        assertTrue(blocks.filterIsInstance<MdBlock.Table>().isNotEmpty())
        assertTrue(blocks.none { it is MdBlock.HorizontalRule })
    }

    @Test
    fun `table cell math is preserved for the inline parser - the cell content model is unchanged`() {
        // The CELL RENDERER now runs parseInline (a Compose-side change); the
        // parse contract that matters here: the raw cell text carries the
        // delimiters intact so parseInline can convert them.
        val blocks = ChatMarkdownParser.parse("| الرمز | التفسير |\n|---|---|\n| \\(\\rho\\) | الكثافة |")
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals("\\(\\rho\\)", table.rows[0][0])
        val spans = ChatMarkdownParser.parseInline(table.rows[0][0])
        assertTrue(spans.any { it is com.example.presentation.ui.screens.studio.MdSpan.Math })
    }
}
