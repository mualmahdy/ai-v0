package com.example.presentation.ui.screens.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ChatSyntaxHighlightingTest — the dependency-free code-highlighting
 * contract (FRONTIER chat upgrade 2026)
 * ============================================================================
 *
 * The tokenizer is PURE Kotlin (JVM-testable): keywords/strings/numbers/
 * comments/annotations per language family, TOLERANT of malformed input
 * (unterminated strings, unclosed block comments), and degrading honestly
 * to PLAIN for unknown languages — the same never-crash/never-lose
 * contract as the markdown parser.
 */
class ChatSyntaxHighlightingTest {

    private fun kinds(line: String, family: CodeLanguageFamily): List<HighlightKind> =
        ChatSyntaxHighlighting.tokenizeLine(line, family).tokens.map { it.kind }

    private fun text(line: String, family: CodeLanguageFamily): String =
        ChatSyntaxHighlighting.tokenizeLine(line, family).tokens.joinToString("") { it.text }

    // ------------------------------------------------------------------
    // Language resolution
    // ------------------------------------------------------------------

    @Test
    fun `known language labels resolve to their families`() {
        assertEquals(CodeLanguageFamily.KOTLIN, ChatSyntaxHighlighting.familyOf("kotlin"))
        assertEquals(CodeLanguageFamily.KOTLIN, ChatSyntaxHighlighting.familyOf("Kotlin "))
        assertEquals(CodeLanguageFamily.PYTHON, ChatSyntaxHighlighting.familyOf("py"))
        assertEquals(CodeLanguageFamily.JAVASCRIPT, ChatSyntaxHighlighting.familyOf("typescript"))
        assertEquals(CodeLanguageFamily.SQL, ChatSyntaxHighlighting.familyOf("postgres"))
        assertEquals(CodeLanguageFamily.XML, ChatSyntaxHighlighting.familyOf("html"))
        assertEquals(CodeLanguageFamily.C_LIKE, ChatSyntaxHighlighting.familyOf("rust"))
    }

    @Test
    fun `unknown or missing language degrades to PLAIN`() {
        assertEquals(CodeLanguageFamily.PLAIN, ChatSyntaxHighlighting.familyOf(null))
        assertEquals(CodeLanguageFamily.PLAIN, ChatSyntaxHighlighting.familyOf("klingon"))
    }

    // ------------------------------------------------------------------
    // Kotlin
    // ------------------------------------------------------------------

    @Test
    fun `kotlin keywords strings and line comments tokenize`() {
        val result = ChatSyntaxHighlighting.tokenizeLine(
            "val name = \"world\" // تحية", CodeLanguageFamily.KOTLIN
        )
        val tokens = result.tokens
        // val → KEYWORD, name → PLAIN, = → PLAIN, "world" → STRING, // … → COMMENT
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "val" }.kind)
        assertEquals(HighlightKind.STRING, tokens.first { it.text == "\"world\"" }.kind)
        val comment = tokens.first { it.text.startsWith("//") }
        assertEquals(HighlightKind.COMMENT, comment.kind)
        assertTrue(comment.text.contains("تحية")) // Arabic comment text survives
        // No loss: joined tokens rebuild the line
        assertEquals("val name = \"world\" // تحية", tokens.joinToString("") { it.text })
    }

    @Test
    fun `kotlin annotations tokenize as annotations`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "@Composable fun x()", CodeLanguageFamily.KOTLIN
        ).tokens
        assertEquals(HighlightKind.ANNOTATION, tokens.first { it.text == "@Composable" }.kind)
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "fun" }.kind)
    }

    @Test
    fun `escaped quotes stay inside the string token`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "val s = \"a \\\" b\"", CodeLanguageFamily.KOTLIN
        ).tokens
        assertEquals(HighlightKind.STRING, tokens.first { it.text.startsWith("\"") }.kind)
        assertEquals("val s = \"a \\\" b\"", tokens.joinToString("") { it.text })
    }

    @Test
    fun `unterminated string paints to end of line tolerantly`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "val s = \"open", CodeLanguageFamily.KOTLIN
        ).tokens
        assertEquals(HighlightKind.STRING, tokens.last().kind)
        assertTrue(tokens.last().text.startsWith("\""))
    }

    // ------------------------------------------------------------------
    // Block comments across lines
    // ------------------------------------------------------------------

    @Test
    fun `block comment state threads across lines`() {
        val lines = ChatSyntaxHighlighting.tokenize(
            "/* start\nstill comment\nend */ val x = 1", CodeLanguageFamily.KOTLIN
        )
        assertEquals(3, lines.size)
        assertEquals(HighlightKind.COMMENT, lines[0].single().kind)
        assertEquals(HighlightKind.COMMENT, lines[1].single().kind)
        // last line: comment closes then real code
        assertTrue(lines[2].any { it.kind == HighlightKind.COMMENT && it.text.endsWith("*/") })
        assertTrue(lines[2].any { it.kind == HighlightKind.KEYWORD && it.text == "val" })
        assertTrue(lines[2].any { it.kind == HighlightKind.NUMBER && it.text == "1" })
    }

    // ------------------------------------------------------------------
    // Python / SQL / Bash
    // ------------------------------------------------------------------

    @Test
    fun `python hash comments and def keyword tokenize`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "def main(): # نقطة الدخول", CodeLanguageFamily.PYTHON
        ).tokens
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "def" }.kind)
        assertEquals(HighlightKind.COMMENT, tokens.first { it.text.startsWith("#") }.kind)
    }

    @Test
    fun `sql keywords match case-insensitively`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "select * from users where id = 5", CodeLanguageFamily.SQL
        ).tokens
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "select" }.kind)
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "from" }.kind)
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "where" }.kind)
        assertEquals(HighlightKind.NUMBER, tokens.first { it.text == "5" }.kind)
    }

    @Test
    fun `bash line comment and keyword tokenize`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "echo \"hi\" # طباعة", CodeLanguageFamily.BASH
        ).tokens
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "echo" }.kind)
        assertEquals(HighlightKind.COMMENT, tokens.first { it.text.startsWith("#") }.kind)
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    @Test
    fun `json object keys recolor as keywords`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "{\"name\": \"value\"}", CodeLanguageFamily.JSON
        ).tokens
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "\"name\"" }.kind)
        assertEquals(HighlightKind.STRING, tokens.first { it.text == "\"value\"" }.kind)
    }

    @Test
    fun `json numbers and booleans tokenize`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "{\"count\": 3, \"ok\": true}", CodeLanguageFamily.JSON
        ).tokens
        assertEquals(HighlightKind.NUMBER, tokens.first { it.text == "3" }.kind)
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "true" }.kind)
    }

    // ------------------------------------------------------------------
    // XML
    // ------------------------------------------------------------------

    @Test
    fun `xml tag names are keywords and attributes carry strings`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "<user name=\"أحمد\"/>", CodeLanguageFamily.XML
        ).tokens
        assertEquals(HighlightKind.KEYWORD, tokens.first { it.text == "user" }.kind)
        assertEquals(HighlightKind.STRING, tokens.first { it.text == "\"أحمد\"" }.kind)
    }

    // ------------------------------------------------------------------
    // Plain-family honesty (no highlighting noise)
    // ------------------------------------------------------------------

    @Test
    fun `plain family never colorizes prose`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "نص عادي مع 'فاصلة' عليا", CodeLanguageFamily.PLAIN
        ).tokens
        assertTrue(tokens.all { it.kind == HighlightKind.PLAIN })
    }

    @Test
    fun `hex and float numbers tokenize in c-like`() {
        val tokens = ChatSyntaxHighlighting.tokenizeLine(
            "let x = 0xFF + 1.5e3", CodeLanguageFamily.C_LIKE
        ).tokens
        assertEquals(HighlightKind.NUMBER, tokens.first { it.text == "0xFF" }.kind)
        assertEquals(HighlightKind.NUMBER, tokens.first { it.text == "1.5e3" }.kind)
    }

    @Test
    fun `tokenization never loses or duplicates text`() {
        val line = "fun compute(x: Int = 42): String = \"النتيجة ${'$'}x\""
        val kotlinText = text(line, CodeLanguageFamily.KOTLIN)
        assertEquals(line, kotlinText)
        val pythonText = text(line, CodeLanguageFamily.PYTHON)
        assertEquals(line, pythonText)
    }

    @Test
    fun `empty and blank lines tokenize to nothing`() {
        assertTrue(ChatSyntaxHighlighting.tokenizeLine("", CodeLanguageFamily.KOTLIN).tokens.isEmpty())
        assertTrue(ChatSyntaxHighlighting.tokenizeLine("   ", CodeLanguageFamily.KOTLIN).tokens.all {
            it.kind == HighlightKind.PLAIN
        })
        assertFalse(ChatSyntaxHighlighting.tokenizeLine("", CodeLanguageFamily.KOTLIN).endsInBlockComment)
    }
}
