package com.example.presentation

import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolParameter
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.presentation.state.ChatAutoScrollPolicy
import com.example.presentation.state.ChatCapabilityFacts
import com.example.presentation.state.ChatCapabilityKey
import com.example.presentation.state.ChatCapabilityPolicy
import com.example.presentation.state.ChatCapabilityStatus
import com.example.presentation.state.RegenerationTurnMarker
import com.example.presentation.ui.screens.studio.BidiSanitizer
import com.example.presentation.ui.screens.studio.ChatMarkdownParser
import com.example.presentation.ui.screens.studio.MdBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * EMERGENCY HOTFIX R2 — REGRESSION SUITE (presentation + provider seams)
 * ============================================================================
 *
 * Pins the round-2 experiential fixes against the exact reported symptoms:
 *
 *  - "the text comes out very poor, none of the existing rendering applies"
 *    → the bidi sanitizer must never corrupt markdown STRUCTURE (headings,
 *      ordered lists, table separators) in Arabic messages.
 *
 *  - "attaching does nothing / there is no way to attach"
 *    → the attach rows stay AVAILABLE whenever a workspace exists (the
 *      import path repairs the project binding).
 *
 *  - "a table of x rows and y columns shows a large blank space and the
 *    buttons become untouchable"
 *    → tall-item-aware near-bottom + bottom-pinned follow scrolling.
 *
 *  - "the regenerate button sometimes repeats the message"
 *    → the regeneration turn marker contract (no duplicate user bubble on
 *      reopen, clean prompt in history/export).
 *
 *  - "the Google provider collapses with HTTP 400"
 *    → no empty text parts in the Gemini request body, sanitized tool
 *      schema, dropped malformed declarations.
 */
class HotfixRound2RegressionTest {

    // ------------------------------------------------------------------
    // 1. Bidi sanitization preserves markdown structure (Arabic messages)
    // ------------------------------------------------------------------

    @Test
    fun `bidi sanitizer preserves headings, ordered lists and table separators in Arabic markdown`() {
        val arabicDoc = """
            ## عنوان رئيسي
            فقرة عربية مع مسار تقني src/main/java/MainActivity.kt داخلها.

            1. العنصر الأول
            2. العنصر الثاني

            | العمود الأول | العمود الثاني |
            |:---|:---|
            | قيمة أولى | قيمة ثانية |
        """.trimIndent()

        val sanitized = BidiSanitizer.isolateTechnicalRuns(arabicDoc)
        val blocks = ChatMarkdownParser.parse(sanitized)

        // The structure survives sanitization INTACT (before the fix, '##',
        // '1.' and ':---' were wrapped in LRI/PDI and NOTHING parsed).
        assertTrue(
            "heading must survive sanitization",
            blocks.any { it is MdBlock.Heading }
        )
        assertTrue(
            "ordered list items must survive sanitization",
            blocks.any { it is MdBlock.ListItem && it.ordered }
        )
        assertTrue(
            "aligned table separators must survive sanitization",
            blocks.any { it is MdBlock.Table }
        )

        // The REAL technical identifier is still isolated (bidi correctness
        // for mixed content is untouched).
        assertTrue(
            "technical identifiers keep their LTR isolation",
            sanitized.contains("\u2066src/main/java/MainActivity.kt\u2069")
        )
    }

    @Test
    fun `bidi sanitizer leaves pure punctuation runs unisolated`() {
        val sanitized = BidiSanitizer.isolateTechnicalRuns("عنوان\n## قسم\n2024 رقم")
        // '##' and '2024' carry no letters — never wrapped.
        assertFalse(sanitized.contains("\u2066##\u2069"))
        assertFalse(sanitized.contains("\u20662024\u2069"))
    }

    // ------------------------------------------------------------------
    // 2. Attach rows stay available whenever a workspace exists
    // ------------------------------------------------------------------

    @Test
    fun `attach rows are AVAILABLE with an unbound project but an active workspace`() {
        val resolved = ChatCapabilityPolicy.resolve(
            ChatCapabilityFacts(activeProjectId = null, hasActiveWorkspace = true)
        )
        val file = resolved.first { it.key == ChatCapabilityKey.ATTACH_FILE }
        val folder = resolved.first { it.key == ChatCapabilityKey.ATTACH_FOLDER }
        assertEquals(ChatCapabilityStatus.AVAILABLE, file.status)
        assertEquals(ChatCapabilityStatus.AVAILABLE, folder.status)
    }

    @Test
    fun `attach rows stay honestly unavailable with no workspace at all`() {
        val resolved = ChatCapabilityPolicy.resolve(
            ChatCapabilityFacts(activeProjectId = null, hasActiveWorkspace = false)
        )
        val file = resolved.first { it.key == ChatCapabilityKey.ATTACH_FILE }
        assertEquals(ChatCapabilityStatus.UNAVAILABLE, file.status)
        assertTrue(file.reason != null)
    }

    // ------------------------------------------------------------------
    // 3. Tall-item-aware auto-scroll (the unreachable buttons)
    // ------------------------------------------------------------------

    @Test
    fun `a last item taller than the viewport is NOT near-bottom while its bottom edge is below the fold`() {
        assertFalse(
            ChatAutoScrollPolicy.isNearBottom(
                lastVisibleIndex = 4, totalItems = 5,
                lastItemOffset = -100, lastItemSize = 3000, viewportEndOffset = 2000
            )
        )
        assertTrue(
            ChatAutoScrollPolicy.isNearBottom(
                lastVisibleIndex = 4, totalItems = 5,
                lastItemOffset = -500, lastItemSize = 2000, viewportEndOffset = 2000
            )
        )
    }

    @Test
    fun `follow scroll offset pins the tall item's bottom edge`() {
        // A 3000px item in a 2000px viewport pins with offset 1000.
        assertEquals(1000, ChatAutoScrollPolicy.followScrollOffset(3000, 0, 2000))
        // Ordinary short items keep the legacy behavior: offset 0.
        assertEquals(0, ChatAutoScrollPolicy.followScrollOffset(200, 0, 2000))
        assertEquals(0, ChatAutoScrollPolicy.followScrollOffset(200, -50, 2000))
    }

    // ------------------------------------------------------------------
    // 4. The regeneration turn marker (the duplicated message on reopen)
    // ------------------------------------------------------------------

    @Test
    fun `regeneration marker is idempotent, detectable and lossless`() {
        val prompt = "اكتب قائمة من 10 صفوف و3 أعمدة"
        val marked = RegenerationTurnMarker.mark(prompt)
        assertTrue(RegenerationTurnMarker.isRegeneration(marked))
        assertEquals(prompt, RegenerationTurnMarker.strip(marked))
        // Idempotent marking.
        assertEquals(marked, RegenerationTurnMarker.mark(marked))
        // Unmarked prompts are untouched.
        assertFalse(RegenerationTurnMarker.isRegeneration(prompt))
        assertEquals(prompt, RegenerationTurnMarker.strip(prompt))
    }

    // ------------------------------------------------------------------
    // 5. Gemini request body: no empty parts, sanitized tool schema
    // ------------------------------------------------------------------

    @Test
    fun `gemini request body never carries an empty text part`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(role = MessageRole.USER, content = "مرحبا"),
                // The poisoned blank turn (the aftermath of a failed step).
                LlmMessage(role = MessageRole.ASSISTANT, content = ""),
                LlmMessage(role = MessageRole.USER, content = "أكمل من حيث توقفت")
            )
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = false))
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        for (i in 0 until contents.length()) {
            val parts = contents.getJSONObject(i).getJSONArray("parts")
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                if (part.has("text")) {
                    assertTrue(
                        "empty text parts are rejected by Gemini with HTTP 400",
                        part.getString("text").isNotEmpty()
                    )
                }
            }
        }
    }

    @Test
    fun `gemini tool declarations are sanitized and malformed tools are dropped`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(LlmMessage(role = MessageRole.USER, content = "مرحبا")),
            availableTools = listOf(
                ToolDeclaration(
                    name = "workspace_file_tool",
                    description = "أداة ملفات",
                    parameters = listOf(
                        ToolParameter(
                            name = "action",
                            type = "string",
                            description = "العملية",
                            isRequired = true,
                            enumValues = listOf("read", "write", "list")
                        )
                    )
                ),
                // Blank-named tool — must be dropped, not the whole request.
                ToolDeclaration(
                    name = "   ",
                    description = "broken",
                    parameters = emptyList()
                )
            )
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = false))
        val declarations = body
            .getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")
        assertEquals(1, declarations.length())
        val actionProp = declarations.getJSONObject(0)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("action")
        // An enum parameter is always STRING-typed (the only legal carrier).
        assertEquals("string", actionProp.getString("type"))
        assertEquals(3, actionProp.getJSONArray("enum").length())
    }
}
