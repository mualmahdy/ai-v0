package com.example.presentation.ui.screens.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ChatMarkdownTest — the rich-message rendering contract (Task 1 §11):
 * paragraphs, headings, bold/italic, lists, links, fenced code blocks (with
 * language label), basic tables — and TOLERANCE for plain and malformed
 * markdown (no crash, no silent loss).
 * ============================================================================
 */
class ChatMarkdownTest {

    private val parser = ChatMarkdownParser

    // ------------------------------------------------------------------
    // Paragraphs and plain text (tolerant baseline)
    // ------------------------------------------------------------------

    @Test
    fun `plain text renders as one paragraph`() {
        val blocks = parser.parse("نص عادي بلا أي رموز")
        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as MdBlock.Paragraph
        assertEquals(1, paragraph.spans.size)
        assertEquals(MdSpan.Text("نص عادي بلا أي رموز"), paragraph.spans.single())
    }

    @Test
    fun `multi-line text accumulates into one paragraph until a blank line`() {
        val blocks = parser.parse("السطر الأول\nالسطر الثاني\n\nفقرة ثانية")
        assertEquals(2, blocks.size)
        val first = blocks[0] as MdBlock.Paragraph
        assertTrue(first.spans.any { (it as? MdSpan.Text)?.text?.contains("السطر الأول") == true })
        val second = blocks[1] as MdBlock.Paragraph
        assertTrue(second.spans.any { (it as? MdSpan.Text)?.text?.contains("فقرة ثانية") == true })
    }

    @Test
    fun `an empty message produces no blocks`() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   \n  \n").isEmpty())
    }

    // ------------------------------------------------------------------
    // Headings
    // ------------------------------------------------------------------

    @Test
    fun `headings of every level parse`() {
        val blocks = parser.parse("# عنوان كبير\n## عنوان متوسط\n### عنوان صغير")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
        assertEquals(
            MdSpan.Text("عنوان كبير"),
            (blocks[0] as MdBlock.Heading).spans.single()
        )
    }

    // ------------------------------------------------------------------
    // Inline styles
    // ------------------------------------------------------------------

    @Test
    fun `bold italic and inline code parse`() {
        val spans = parser.parseInline("هذا **مهم** و *ملفت* و `code`")
        assertEquals(
            listOf(
                MdSpan.Text("هذا "),
                MdSpan.Bold("مهم"),
                MdSpan.Text(" و "),
                MdSpan.Italic("ملفت"),
                MdSpan.Text(" و "),
                MdSpan.Code("code")
            ),
            spans
        )
    }

    @Test
    fun `links parse with label and url`() {
        val spans = parser.parseInline("زُر [التوثيق](https://example.com/docs) للمزيد")
        val link = spans.filterIsInstance<MdSpan.Link>().single()
        assertEquals("التوثيق", link.label)
        assertEquals("https://example.com/docs", link.url)
    }

    @Test
    fun `unclosed markers degrade to literal text (malformed tolerance)`() {
        val spans = parser.parseInline("نص **غير مكتمل و *نصف مائل")
        assertEquals(1, spans.size)
        assertEquals(MdSpan.Text("نص **غير مكتمل و *نصف مائل"), spans.single())
    }

    @Test
    fun `an unmatched single marker stays literal`() {
        val spans = parser.parseInline("نص بـ * واحد فقط")
        assertEquals(listOf(MdSpan.Text("نص بـ * واحد فقط")), spans)
    }

    // ------------------------------------------------------------------
    // FRONTIER unification: strike + inline math (carried from the rich
    // renderer into the ONE shared inline parser)
    // ------------------------------------------------------------------

    @Test
    fun `strikethrough parses and unclosed tildes stay literal`() {
        val spans = parser.parseInline("هذا ~~ملغى~~ ونص ~ مفرد")
        assertEquals(
            listOf(
                MdSpan.Text("هذا "),
                MdSpan.Strike("ملغى"),
                MdSpan.Text(" ونص ~ مفرد")
            ),
            spans
        )
    }

    @Test
    fun `dollar inline math parses and a lone dollar stays literal`() {
        val source = "المعادلة " + '$' + "x^2+1" + '$' + " وسعره 5" + '$'
        val spans = parser.parseInline(source)
        val math = spans.filterIsInstance<MdSpan.Math>().single()
        assertEquals("x^2+1", math.text)
        // The trailing lone '$' never pairs backwards across the math span.
        assertTrue(spans.last() is MdSpan.Text)
    }

    @Test
    fun `backslash-paren inline math parses`() {
        val spans = parser.parseInline("يكتب \\(a+b\\) كصيغة")
        assertEquals(MdSpan.Math("a+b"), spans.filterIsInstance<MdSpan.Math>().single())
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    @Test
    fun `unordered and ordered lists parse`() {
        val blocks = parser.parse("- أول\n- ثاني\n1. الأول\n2. الثاني")
        assertEquals(4, blocks.size)
        val unordered = blocks[0] as MdBlock.ListItem
        assertEquals(false, unordered.ordered)
        val ordered = blocks[2] as MdBlock.ListItem
        assertEquals(true, ordered.ordered)
        assertEquals(1, ordered.ordinal)
        assertEquals(2, (blocks[3] as MdBlock.ListItem).ordinal)
    }

    // ------------------------------------------------------------------
    // Fenced code blocks
    // ------------------------------------------------------------------

    @Test
    fun `a fenced code block parses with its language label`() {
        val source = "شرح:\n```kotlin\nfun main() {\n    println(\"مرحبا\")\n}\n```"
        val blocks = parser.parse(source)
        val code = blocks.last() as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main()"))
        assertTrue(code.code.contains("مرحبا"))
    }

    @Test
    fun `an unclosed fence renders the remaining text as code (no loss)`() {
        val blocks = parser.parse("```python\nprint(1)")
        assertEquals(1, blocks.size)
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("python", code.language)
        assertTrue(code.code.contains("print(1)"))
    }

    @Test
    fun `an inline code span inside a paragraph is not a fence`() {
        val blocks = parser.parse("استخدم `x` في الكود")
        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as MdBlock.Paragraph
        assertTrue(paragraph.spans.any { it is MdSpan.Code })
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    @Test
    fun `a basic pipe table parses`() {
        val source = "| العمود أ | العمود ب |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val blocks = parser.parse(source)
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("العمود أ", "العمود ب"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
        assertEquals(listOf("3", "4"), table.rows[1])
    }

    @Test
    fun `a pipe row without a separator is a plain paragraph`() {
        val blocks = parser.parse("سطر | به | أنابيب فقط")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    // ------------------------------------------------------------------
    // Full-message integration
    // ------------------------------------------------------------------

    @Test
    fun `a full rich assistant message parses into the expected block sequence`() {
        val source = """
            ## الخطة
            هذه **خطة** العمل:
            - الخطوة الأولى
            - الخطوة الثانية

            ```bash
            echo hi
            ```

            التفاصيل في [الرابط](https://example.com).
        """.trimIndent()

        val blocks = parser.parse(source)
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.ListItem)
        assertTrue(blocks[3] is MdBlock.ListItem)
        assertTrue(blocks[4] is MdBlock.CodeBlock)
        assertTrue(blocks[5] is MdBlock.Paragraph)

        val code = blocks[4] as MdBlock.CodeBlock
        assertEquals("bash", code.language)
        assertEquals("echo hi", code.code)
    }
}
