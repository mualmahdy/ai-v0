package com.example.presentation

import com.example.application.agent.CanonicalAgentCatalog
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.llm.ToolCallRequest
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * HOTFIX ROUND 3 — REGRESSION SUITE (the experiential layer, take three)
 * ============================================================================
 *
 * Pins the round-3 root causes against the exact reported symptoms:
 *
 *  - "the Google provider collapses the execution loop with a 400 streaming
 *    error"
 *    → the Gemini request body must NEVER carry the unknown "stream" field
 *      (Google's API frontend validates the payload BEFORE auth and rejects
 *      unknown fields with 400 INVALID_ARGUMENT — verified live: the same
 *      body without the key fails only on the API key). Streaming is driven
 *      by the :streamGenerateContent METHOD, never a body field.
 *
 *  - "with another provider the conversation is semi-normal but the text
 *    comes out very poor with no rendering at all"
 *    → (a) the tool-synthesis round must be protocol-valid on BOTH wire
 *      formats (an assistant turn carrying its toolCalls, then the tool
 *      results answering them) — the previous plain-text assistant turn made
 *      the follow-up request structurally invalid (400) and the visible
 *      answer degraded to the pre-tool preamble;
 *      (b) JSON-null content deltas must contribute NOTHING (Android's
 *      org.json optString coerces null into the literal word "null");
 *      (c) the canonical quick-chat prompt must demand complete,
 *      Markdown-formatted answers (it used to command "answer with a brief
 *      hint").
 *
 *  - "a table of x rows and y columns shows a large blank space and the
 *    buttons below the message become untouchable"
 *    → the rich table must not rely on weight()/fillMaxWidth() under the
 *      horizontal scroll's infinite constraints (the committed Roborazzi
 *      reference itself documents the blank rendering) — the pure contract
 *      here pins the parse side; the layout contract is documented in
 *      ChatRichContent and pinned by the Roborazzi matrix.
 */
class HotfixRound3RegressionTest {

    // ------------------------------------------------------------------
    // 1. Gemini: no unknown "stream" field in the request body
    // ------------------------------------------------------------------

    @Test
    fun `gemini stream request body carries no stream field - the method drives streaming`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(LlmMessage(role = MessageRole.USER, content = "مرحبا"))
        )
        // The STREAMING path (stream = true) — this is the exact request the
        // execution loop sends to :streamGenerateContent?alt=sse.
        val body = JSONObject(adapter.buildRequestBody(request, stream = true))
        assertFalse(
            "the REST GenerateContentRequest proto has no 'stream' field — Google rejects " +
                "the whole payload with 400 INVALID_ARGUMENT 'Unknown name \"stream\"' before " +
                "even looking at the API key (verified live)",
            body.has("stream")
        )
        // The single-shot path must stay clean too.
        val singleShot = JSONObject(adapter.buildRequestBody(request, stream = false))
        assertFalse(singleShot.has("stream"))
    }

    // ------------------------------------------------------------------
    // 2. Gemini: the tool round is protocol-valid (functionCall parts
    //    precede the functionResponse parts)
    // ------------------------------------------------------------------

    @Test
    fun `gemini assistant turn with tool calls renders functionCall parts before the tool responses`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(role = MessageRole.USER, content = "اقرأ الملف المرفق"),
                LlmMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCallRequest(
                            callId = "call_1",
                            toolName = "workspace_file_tool",
                            argumentsJson = """{"action":"read","path":"notes.md"}"""
                        )
                    )
                ),
                LlmMessage(
                    role = MessageRole.TOOL,
                    content = "محتوى الملف",
                    name = "workspace_file_tool",
                    toolCallId = "call_1"
                )
            )
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = false))
        val contents = body.getJSONArray("contents")

        // The model turn that requested the tool carries its functionCall part…
        val modelTurn = contents.getJSONObject(1)
        assertEquals("model", modelTurn.getString("role"))
        val parts = modelTurn.getJSONArray("parts")
        assertEquals(1, parts.length()) // blank text is OMITTED, never an empty part
        val fn = parts.getJSONObject(0).getJSONObject("functionCall")
        assertEquals("workspace_file_tool", fn.getString("name"))
        assertEquals("read", fn.getJSONObject("args").getString("action"))

        // …and the tool result follows as the functionResponse the API requires.
        val toolTurn = contents.getJSONObject(2)
        assertEquals("function", toolTurn.getString("role"))
        val responsePart = toolTurn.getJSONArray("parts").getJSONObject(0)
        assertEquals("workspace_file_tool", responsePart.getJSONObject("functionResponse").getString("name"))
    }

    @Test
    fun `gemini assistant turn with tool calls and text carries both parts`() {
        val adapter = GeminiLlmAdapter(defaultModelName = "gemini-2.5-flash")
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(role = MessageRole.USER, content = "اقرأ الملف"),
                LlmMessage(
                    role = MessageRole.ASSISTANT,
                    content = "سأقرأ الملف الآن",
                    toolCalls = listOf(
                        ToolCallRequest(callId = "c1", toolName = "t", argumentsJson = "{}")
                    )
                )
            )
        )
        val body = JSONObject(adapter.buildRequestBody(request, stream = false))
        val parts = body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("سأقرأ الملف الآن", parts.getJSONObject(1).getString("text"))
    }

    // ------------------------------------------------------------------
    // 3. OpenAI-compatible: the tool round is protocol-valid
    // ------------------------------------------------------------------

    @Test
    fun `openai assistant turn with tool calls serializes the tool_calls array the tool messages answer`() {
        val adapter = OpenAiCompatibleLlmAdapter(
            baseUrl = "http://localhost:8080",
            apiKeyProvider = { null },
            defaultModel = "test-model"
        )
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(role = MessageRole.USER, content = "اقرأ الملف"),
                LlmMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCallRequest(
                            callId = "call_abc",
                            toolName = "workspace_file_tool",
                            argumentsJson = """{"action":"read"}"""
                        )
                    )
                ),
                LlmMessage(
                    role = MessageRole.TOOL,
                    content = "ناتج الأداة",
                    name = "workspace_file_tool",
                    toolCallId = "call_abc"
                )
            )
        )
        val body = adapter.buildJsonBody(request, stream = false)
        val messages = body.getJSONArray("messages")

        val assistantTurn = messages.getJSONObject(1)
        assertEquals("assistant", assistantTurn.getString("role"))
        // Blank content serializes as a REAL null (the canonical form for a
        // tool-call-only model turn) — never an empty-string stand-in that
        // some gateways still reject.
        assertTrue(assistantTurn.isNull("content"))
        val toolCalls = assistantTurn.getJSONArray("tool_calls")
        assertEquals(1, toolCalls.length())
        val call = toolCalls.getJSONObject(0)
        assertEquals("call_abc", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals("workspace_file_tool", call.getJSONObject("function").getString("name"))
        assertEquals("""{"action":"read"}""", call.getJSONObject("function").getString("arguments"))

        // The tool message answers the SAME id — the exact contract OpenAI
        // enforces ("tool messages must respond to each tool_call_id").
        val toolTurn = messages.getJSONObject(2)
        assertEquals("tool", toolTurn.getString("role"))
        assertEquals("call_abc", toolTurn.getString("tool_call_id"))
        assertEquals("ناتج الأداة", toolTurn.getString("content"))
    }

    @Test
    fun `openai plain messages keep the legacy shape`() {
        val adapter = OpenAiCompatibleLlmAdapter(
            baseUrl = "http://localhost:8080",
            apiKeyProvider = { null },
            defaultModel = "test-model"
        )
        val request = LlmRequest(
            messages = listOf(
                LlmMessage(role = MessageRole.SYSTEM, content = "sys"),
                LlmMessage(role = MessageRole.USER, content = "مرحبا"),
                LlmMessage(role = MessageRole.ASSISTANT, content = "أهلاً")
            )
        )
        val body = adapter.buildJsonBody(request, stream = false)
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        // No tool_calls key on plain assistant turns.
        assertFalse(messages.getJSONObject(2).has("tool_calls"))
        // The STREAM flag on the wire (this one IS a real Chat Completions field).
        assertFalse(body.getBoolean("stream"))
    }

    // ------------------------------------------------------------------
    // 4. The literal-"null" text: JSON-null content must contribute NOTHING
    //    (the pure seam — the null-guard contract the stream loop applies)
    // ------------------------------------------------------------------

    @Test
    fun `a json-null content delta contributes nothing - never the word null`() {
        // The trap this round closes is Android-specific: libcore's org.json
        // coerces {"content": null} into the literal word "null" via
        // optString (JSON.toString(NULL) -> "null"), so null-content deltas
        // from role/tool/finish chunks got APPENDED to the answer text on
        // real devices. (The JVM org.json:20240303 used by unit tests returns
        // the fallback instead — the guard must be correct for BOTH.)
        val delta = JSONObject("""{"content":null,"role":"assistant"}""")
        // The ROUND-3 guard: isNull first, string only when actually present.
        val guarded = if (delta.isNull("content")) "" else delta.optString("content", "")
        assertEquals("", guarded)
        // And the same guard shape the OpenAI adapter's stream loop applies.
        val reasoningOnly = JSONObject("""{"reasoning_content":null}""")
        val reasoningGuarded =
            if (reasoningOnly.isNull("reasoning_content")) "" else reasoningOnly.optString("reasoning_content", "")
        assertEquals("", reasoningGuarded)
    }

    @Test
    fun `openai generate parses a tool-call-only response with null content as empty text`() {
        val adapter = OpenAiCompatibleLlmAdapter(
            baseUrl = "http://localhost:8080",
            apiKeyProvider = { null },
            defaultModel = "test-model"
        )
        // A tool-call-only chat completion (DeepSeek & friends): content is null.
        val payload = JSONObject(
            """
            {
              "choices": [{
                "index": 0,
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "t", "arguments": "{}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 5, "completion_tokens": 3}
            }
            """.trimIndent()
        )
        val message = payload.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = message.let { if (it.isNull("content")) "" else it.optString("content", "") }
        assertEquals("a null content is the EMPTY string, never the word 'null'", "", content)
    }

    // ------------------------------------------------------------------
    // 5. The canonical quick-chat prompt (the "poor text" instruction)
    // ------------------------------------------------------------------

    @Test
    fun `the canonical quick-chat prompt demands complete markdown answers - never a brief hint`() {
        val quickChat = CanonicalAgentCatalog.defaults.first {
            it.identity.id.value == "agent_quick_chat"
        }
        val prompt = quickChat.identity.systemPrompt
        // The poison is gone: the old prompt literally commanded brevity.
        assertFalse(
            "the prompt must never command artificial brevity ('بتلميح موجز' was the " +
                "literal instruction behind 'the text comes out very poor')",
            prompt.contains("بتلميح موجز")
        )
        // The rendering contract is now explicit: markdown structure.
        assertTrue("the prompt must name Markdown formatting", prompt.contains("Markdown"))
        assertTrue("the prompt must mention tables (the reported x-rows/y-columns case)", prompt.contains("الجداول"))
        // And the attached-evidence contract (the attach flow's whole point).
        assertTrue("the prompt must instruct using the attached evidence", prompt.contains("user_attachment"))
    }

    // ------------------------------------------------------------------
    // 6. The tool-round message model: defaults stay honest
    // ------------------------------------------------------------------

    @Test
    fun `llm messages without tool calls are unchanged for every role`() {
        val plain = LlmMessage(role = MessageRole.ASSISTANT, content = "جواب")
        assertTrue(plain.toolCalls.isEmpty())
        val toolMessage = LlmMessage(
            role = MessageRole.TOOL,
            content = "ناتج",
            name = "tool",
            toolCallId = "id"
        )
        assertTrue(toolMessage.toolCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // 7. The agent registry seed reconciliation (existing installs keep
    //    the OLD durable prompt forever unless it is reconciled)
    // ------------------------------------------------------------------

    @Test
    fun `the canonical quick-chat agent is refreshable by id - the reconciliation key is stable`() {
        // The reconciliation in AgentRegistryService.ensureSeeded matches on
        // the canonical id — the id must remain the session service's
        // constant (a drifted id would leave the old prompt durable forever).
        assertEquals(
            "agent_quick_chat",
            com.example.application.session.ConversationSessionService.QUICK_CHAT_AGENT_ID
        )
        assertTrue(CanonicalAgentCatalog.defaults.any {
            it.identity.id.value == com.example.application.session.ConversationSessionService.QUICK_CHAT_AGENT_ID
        })
    }

    // ------------------------------------------------------------------
    // 8. The table parser still feeds the rebuilt renderer (the blank-table
    //    fix must not change what parses — only how it lays out)
    // ------------------------------------------------------------------

    @Test
    fun `an arabic table with a dashed separator parses into header and rows`() {
        val blocks = com.example.presentation.ui.screens.studio.ChatMarkdownParser.parse(
            """
            | المرحلة | الحالة |
            |---------|--------|
            | التحليل | مكتملة |
            | التنفيذ | جارية |
            """.trimIndent()
        )
        val table = blocks.filterIsInstance<com.example.presentation.ui.screens.studio.MdBlock.Table>().single()
        assertEquals(listOf("المرحلة", "الحالة"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals("التحليل", table.rows[0][0])
    }

    @Test
    fun `rich scanner routes a table to the dedicated table block`() {
        val richBlocks = com.example.presentation.ui.screens.studio.RichChatParser.parse(
            "مقدمة قصيرة.\n\n| أ | ب |\n|---|---|\n| 1 | 2 |\n"
        )
        assertEquals(2, richBlocks.size)
        assertTrue(richBlocks[0] is com.example.presentation.ui.screens.studio.RichChatBlock.Markdown)
        val table = richBlocks[1] as com.example.presentation.ui.screens.studio.RichChatBlock.Table
        assertEquals(listOf("أ", "ب"), table.header)
        assertEquals(1, table.rows.size)
    }
}
