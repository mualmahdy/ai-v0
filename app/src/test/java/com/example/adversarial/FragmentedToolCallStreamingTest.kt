package com.example.adversarial

import com.example.domain.core.DegradedReason
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmRequest
import com.example.domain.ports.llm.LlmProviderPort
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * ============================================================================
 * ADVERSARIAL TEST (defect family 6 & 8) — Fragmented Tool-Call Streaming
 * ============================================================================
 *
 * Verified defect: streamed OpenAI-style tool calls arrive as INDEX-KEYED
 * deltas (first delta: id + name + first arguments fragment; continuation
 * deltas: ONLY index + function.arguments). The previous adapter emitted a
 * ToolRequested PER DELTA:
 *   - the FIRST fragment executed with truncated/empty arguments
 *     (partially-formed call EXECUTED);
 *   - every continuation fragment was SILENTLY DROPPED (blank name);
 *   - malformed chunks vanished silently (runCatching swallow).
 *
 * The repaired adapter ACCUMULATES (id, name, argument fragments) keyed by
 * index and emits a tool call ONLY when the accumulated arguments form a
 * complete valid JSON object. This test feeds deliberately fragmented,
 * interleaved, and malformed SSE streams:
 *
 *  1. multi-chunk single tool call → ONE ToolRequested with the COMPLETE
 *     reconstructed arguments (never partial execution);
 *  2. parallel tool calls (two indices interleaved) → both complete, each
 *     with unique call ids;
 *  3. truncated/malformed argument accumulation → NO ToolRequested, honest
 *     Degraded event (fail-safe);
 *  4. malformed JSON chunks are counted and surfaced, never silently
 *     dropped.
 */
@RunWith(RobolectricTestRunner::class)
class FragmentedToolCallStreamingTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val egress = com.example.infrastructure.network.EgressControl()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        baseUrl = "http://127.0.0.1:$port"
        egress.pinWorkspacePolicy("ws_stream", com.example.domain.core.network.NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_stream")
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        egress.reset()
    }

    private fun adapter(): OpenAiCompatibleLlmAdapter = OpenAiCompatibleLlmAdapter(
        baseUrl = baseUrl,
        apiKeyProvider = { "test-key" },
        defaultModel = "test-model",
        providerId = "test_stream",
        egressControl = egress
    )

    private fun serveSse(chunks: List<String>) {
        server.createContext("/v1/chat/completions") { exchange ->
            val body = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun toolCallDelta(
        index: Int,
        id: String? = null,
        name: String? = null,
        argumentsFragment: String? = null
    ): String {
        val fn = org.json.JSONObject()
        name?.let { fn.put("name", it) }
        argumentsFragment?.let { fn.put("arguments", it) }
        val tc = org.json.JSONObject()
        tc.put("index", index)
        id?.let { tc.put("id", it) }
        tc.put("type", "function")
        tc.put("function", fn)
        val delta = org.json.JSONObject().put("tool_calls", org.json.JSONArray().put(tc))
        val choice = org.json.JSONObject().put("delta", delta)
        return org.json.JSONObject().put("choices", org.json.JSONArray().put(choice)).toString()
    }

    private fun contentChunk(text: String): String {
        val delta = org.json.JSONObject().put("content", text)
        val choice = org.json.JSONObject().put("delta", delta)
        return org.json.JSONObject().put("choices", org.json.JSONArray().put(choice)).toString()
    }

    private fun usageChunk(): String =
        org.json.JSONObject()
            .put("choices", org.json.JSONArray())
            .put("usage", org.json.JSONObject()
                .put("prompt_tokens", 25)
                .put("completion_tokens", 30)
                .put("total_tokens", 55))
            .toString()

    private fun request() = LlmRequest(
        messages = listOf(
            com.example.domain.core.llm.LlmMessage(com.example.domain.core.llm.MessageRole.USER, "test")
        ),
        availableTools = listOf(
            com.example.domain.core.tools.ToolDeclaration(
                name = "workspace_file_tool",
                description = "file tool",
                parameters = listOf(
                    com.example.domain.core.tools.ToolParameter("action", "string", "act"),
                    com.example.domain.core.tools.ToolParameter("path", "string", "path")
                )
            )
        )
    )

    // ------------------------------------------------------------------
    // 1. Fragmented single tool call → ONE COMPLETE ToolRequested
    // ------------------------------------------------------------------

    @Test
    fun `fragmented arguments across many chunks execute ONCE with complete arguments`() = runBlocking {
        // The argument JSON is deliberately split into FOUR fragments.
        serveSse(
            listOf(
                toolCallDelta(index = 0, id = "call_abc", name = "workspace_file_tool", argumentsFragment = """{"act"""),
                toolCallDelta(index = 0, argumentsFragment = """ion":"read",""""),
                toolCallDelta(index = 0, argumentsFragment = """path":"notes/frag"""),
                toolCallDelta(index = 0, argumentsFragment = """ment.md"}"""),
                usageChunk()
            )
        )

        val events = adapter().stream(request(), "exec_frag_1").toList()
        val toolRequests = events.filterIsInstance<ExecutionEvent.ToolRequested>()

        assertEquals("Exactly ONE complete tool call must be emitted", 1, toolRequests.size)
        val call = toolRequests.first()
        assertEquals("call_abc", call.callId)
        assertEquals("workspace_file_tool", call.toolName)
        val args = org.json.JSONObject(call.argumentsJson)
        assertEquals("read", args.getString("action"))
        assertEquals("notes/fragment.md", args.getString("path"))
    }

    // ------------------------------------------------------------------
    // 2. Parallel tool calls (two indices, interleaved fragments)
    // ------------------------------------------------------------------

    @Test
    fun `interleaved parallel tool calls stay isolated with unique ids`() = runBlocking {
        serveSse(
            listOf(
                toolCallDelta(index = 0, id = "call_first", name = "workspace_file_tool", argumentsFragment = """{"a"""),
                toolCallDelta(index = 1, id = "call_second", name = "workspace_file_tool", argumentsFragment = """{"b"""),
                toolCallDelta(index = 0, argumentsFragment = """":1}"""),
                toolCallDelta(index = 1, argumentsFragment = """":2}"""),
                usageChunk()
            )
        )

        val events = adapter().stream(request(), "exec_frag_2").toList()
        val toolRequests = events.filterIsInstance<ExecutionEvent.ToolRequested>()

        assertEquals(2, toolRequests.size)
        val byId = toolRequests.associateBy { it.callId }
        assertTrue("call_first" in byId)
        assertTrue("call_second" in byId)
        assertEquals(1, org.json.JSONObject(byId.getValue("call_first").argumentsJson).getInt("a"))
        assertEquals(2, org.json.JSONObject(byId.getValue("call_second").argumentsJson).getInt("b"))
    }

    // ------------------------------------------------------------------
    // 3. TRUNCATED stream → NO tool call, honest degradation
    // ------------------------------------------------------------------

    @Test
    fun `truncated arguments never execute - fail safe with explicit degradation`() = runBlocking {
        serveSse(
            listOf(
                toolCallDelta(index = 0, id = "call_trunc", name = "workspace_file_tool", argumentsFragment = """{"path":"/etc"""),
                // Stream ends mid-argument — no closing brace.
                usageChunk()
            )
        )

        val events = adapter().stream(request(), "exec_frag_3").toList()
        val toolRequests = events.filterIsInstance<ExecutionEvent.ToolRequested>()

        assertEquals("A truncated (invalid JSON) accumulation must NOT execute", 0, toolRequests.size)
        val degraded = events.filterIsInstance<ExecutionEvent.Degraded>()
        assertTrue(
            "The dropped tool call must be surfaced honestly",
            degraded.any { it.message.contains("TOOL_CALL") }
        )
    }

    // ------------------------------------------------------------------
    // 4. Malformed SSE chunks are counted, never silently swallowed
    // ------------------------------------------------------------------

    @Test
    fun `malformed chunks are counted and surfaced`() = runBlocking {
        server.createContext("/v1/chat/completions") { exchange ->
            val body = "data: {not json at all!!\n\n" +
                "data: \"orphan string\"\n\n" +
                "data: [DONE]\n\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val events = adapter().stream(request(), "exec_frag_4").toList()
        val degraded = events.filterIsInstance<ExecutionEvent.Degraded>()
        assertTrue(
            "Malformed chunks must be reported (never silent)",
            degraded.any { it.message.contains("TOOL_STREAM_MALFORMED_CHUNKS") }
        )
    }

    // ------------------------------------------------------------------
    // 5. No arguments fragments at all → empty-args call is valid
    // ------------------------------------------------------------------

    @Test
    fun `a tool call with no argument fragments executes with empty args`() = runBlocking {
        serveSse(
            listOf(
                toolCallDelta(index = 0, id = "call_noargs", name = "workspace_file_tool"),
                usageChunk()
            )
        )

        val events = adapter().stream(request(), "exec_frag_5").toList()
        val toolRequests = events.filterIsInstance<ExecutionEvent.ToolRequested>()
        assertEquals(1, toolRequests.size)
        assertEquals("{}", toolRequests.first().argumentsJson)
    }

    // ------------------------------------------------------------------
    // 6. Egress policy applies to the streamed request (transport gate)
    // ------------------------------------------------------------------

    @Test
    fun `offline workspace policy blocks the streamed request before any socket`() = runBlocking {
        serveSse(
            listOf(
                toolCallDelta(index = 0, id = "call_x", name = "workspace_file_tool", argumentsFragment = "{}"),
                usageChunk()
            )
        )
        egress.pinWorkspacePolicy("ws_stream", com.example.domain.core.network.NetworkPolicy.OFFLINE)

        val events = adapter().stream(request(), "exec_frag_6").toList()
        val errors = events.filterIsInstance<ExecutionEvent.Error>()
        // The interceptor fails the call with the blocked egress exception
        // (surfaced as a STREAM_ERROR carrying EGRESS_BLOCKED) — the stream
        // never reaches the (reachable) local server.
        assertTrue(
            "OFFLINE policy must block the streamed egress: $errors",
            errors.isNotEmpty()
        )
        assertEquals(0, events.filterIsInstance<ExecutionEvent.ToolRequested>().size)
    }
}
