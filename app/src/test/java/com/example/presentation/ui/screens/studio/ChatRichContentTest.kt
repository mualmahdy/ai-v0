package com.example.presentation.ui.screens.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRichContentTest {
    @Test
    fun `math block is recognized`() {
        val blocks = RichChatParser.parse("$$\n\\nabla \\cdot \\mathbf{E} = \\frac{\\rho}{\\epsilon_0}\n$$")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is RichChatBlock.MathBlock)
    }

    @Test
    fun `wide pipe table is recognized`() {
        val blocks = RichChatParser.parse("| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |")
        val table = blocks.single() as RichChatBlock.Table
        assertEquals(listOf("A", "B", "C"), table.header)
        assertEquals(listOf("1", "2", "3"), table.rows.single())
    }

    @Test
    fun `chart fence parses numeric points`() {
        val blocks = RichChatParser.parse("```chart\nA,10\nB,20\n```")
        val chart = blocks.single() as RichChatBlock.Chart
        assertEquals(2, chart.points.size)
        assertEquals("B", chart.points.last().label)
        assertEquals(20f, chart.points.last().value)
    }

    @Test
    fun `mermaid flow parses edges`() {
        val blocks = RichChatParser.parse("```mermaid\nA --> B\nB --> C\n```")
        val diagram = blocks.single() as RichChatBlock.Diagram
        assertEquals(2, diagram.edges.size)
        assertEquals(DiagramEdge("A", "B"), diagram.edges.first())
    }

    @Test
    fun `technical identifier is kept as ordinary markdown`() {
        val blocks = RichChatParser.parse("استخدم some_variable_name مع `x_1` و **نص**.")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is RichChatBlock.Markdown)
    }
}
