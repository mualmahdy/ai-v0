package com.example.adversarial

import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.infrastructure.llm.gemini.GeminiLlmAdapter
import com.example.infrastructure.llm.openai.OpenAiCompatibleLlmAdapter
import com.example.infrastructure.network.EgressControl
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * ============================================================================
 * ADVERSARIAL TEST (FRONTIER REASONING) — Reasoning-Token Streaming
 * ============================================================================
 *
 * Verified contract: a reasoning-capable model streams its thinking in a
 * SEPARATE lane that must NEVER leak into the answer:
 *
 *   - OpenAI-compatible providers stream `delta.reasoning_content`
 *     (DeepSeek-R1 convention) or `delta.reasoning` (gateway convention)
 *     BEFORE/DURING the content tokens.
 *   - Gemini streams thought parts (`parts[].thought == true`) only when the
 *     request asks for them (`generationConfig.thinkingConfig.includeThoughts`).
 *
 * The adapters must:
 *   1. emit ReasoningChunk per reasoning delta, IN ORDER, separate from
 *      ContentChunk;
 *   2. keep the final Completed.finalText (and the answer lane generally)
 *      100% reasoning-free — thinking NEVER mixes into the answer;
 *   3. stay honest: a provider that reports NO reasoning yields ZERO
 *      ReasoningChunk events (no fabricated thinking);
 *   4. (Gemini) actually REQUEST the thoughts — the adapter advertised the
 *      "reasoning" capability; now it must send includeThoughts.
 */
@RunWith(RobolectricTestRunner::class)
class ReasoningTokenStreamingTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val egress = EgressControl()
    private val lastGeminiRequestBody = AtomicReference<String?>(null)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        egress.pinWorkspacePolicy("ws_reason", com.example.domain.core.network.NetworkPolicy.HYBRID)
        egress.setActiveWorkspace("ws_reason")
        EgressControl.default.apply {
            pinWorkspacePolicy("ws_reason", com.example.domain.core.network.NetworkPolicy.HYBRID)
            setActiveWorkspace("ws_reason")
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        egress.reset()
        EgressControl.default.reset()
    }

    private fun request(): LlmRequest = LlmRequest(
        messages = listOf(
            LlmMessage(MessageRole.SYSTEM, "أنت مساعد ذكي"),
            LlmMessage(MessageRole.USER, "اشرح باختصار")
        )
    )

    // ------------------------------------------------------------------
    // OpenAI-compatible
    // ------------------------------------------------------------------

    private fun openAiAdapter(): OpenAiCompatibleLlmAdapter = OpenAiCompatibleLlmAdapter(
        baseUrl = baseUrl,
        apiKeyProvider = { "test-key" },
        defaultModel = "test-model",
        providerId = "test_reason",
        egressControl = egress
    )

    private fun serveOpenAiSse(chunks: List<String>) {
        server.createContext("/v1/chat/completions") { exchange ->
            val body = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun openAiDelta(payload: String): String {
        val delta = JSONObject(payload)
        return JSONObject().put(
            "choices",
            org.json.JSONArray().put(JSONObject().put("delta", delta))
        ).toString()
    }

    @Test
    fun `openai reasoning_content fragments stream as ordered ReasoningChunks and never reach the answer`() = runBlocking {
        serveOpenAiSse(
            listOf(
                openAiDelta("""{"reasoning_content": "أفكر"}"""),
                openAiDelta("""{"reasoning_content": " في الخطوة"}"""),
                openAiDelta("""{"content": "الجواب"}""")
            )
        )
        val events = openAiAdapter().stream(request(), "exec_r1").toList()

        val reasoning = events.filterIsInstance<ExecutionEvent.ReasoningChunk>()
        assertEquals(listOf("أفكر", " في الخطوة"), reasoning.map { it.deltaText })
        // Ordered interleaving: reasoning arrives BEFORE the content token.
        assertTrue(
            events.indexOfFirst { it is ExecutionEvent.ReasoningChunk } <
                events.indexOfFirst { it is ExecutionEvent.ContentChunk }
        )

        val content = events.filterIsInstance<ExecutionEvent.ContentChunk>()
        assertEquals(listOf("الجواب"), content.map { it.deltaText })

        val completed = events.filterIsInstance<ExecutionEvent.Completed>().single()
        assertEquals("الجواب", completed.finalText)
        assertFalse("thinking must never leak into the final text", completed.finalText.contains("أفكر"))
    }

    @Test
    fun `openai gateway reasoning field variant also streams as reasoning`() = runBlocking {
        serveOpenAiSse(
            listOf(
                openAiDelta("""{"reasoning": "thinking hard"}"""),
                openAiDelta("""{"content": "the answer"}""")
            )
        )
        val events = openAiAdapter().stream(request(), "exec_r2").toList()

        val reasoning = events.filterIsInstance<ExecutionEvent.ReasoningChunk>()
        assertEquals(listOf("thinking hard"), reasoning.map { it.deltaText })
        val completed = events.filterIsInstance<ExecutionEvent.Completed>().single()
        assertEquals("the answer", completed.finalText)
    }

    @Test
    fun `provider without reasoning streams zero ReasoningChunks - no fabricated thinking`() = runBlocking {
        serveOpenAiSse(
            listOf(
                openAiDelta("""{"content": "مرحبا"}"""),
                openAiDelta("""{"content": " بك"}""")
            )
        )
        val events = openAiAdapter().stream(request(), "exec_r3").toList()

        assertTrue(events.filterIsInstance<ExecutionEvent.ReasoningChunk>().isEmpty())
        assertEquals(
            listOf("مرحبا", " بك"),
            events.filterIsInstance<ExecutionEvent.ContentChunk>().map { it.deltaText }
        )
    }

    // ------------------------------------------------------------------
    // Gemini
    // ------------------------------------------------------------------

    private fun geminiAdapter(): GeminiLlmAdapter = GeminiLlmAdapter(
        defaultModelName = "gemini-2.5-flash",
        apiKeyProvider = { "test-key" },
        baseUrl = baseUrl
    )

    private fun serveGeminiSse(payloads: List<String>) {
        server.createContext("/v1beta/models/gemini-2.5-flash:streamGenerateContent") { exchange ->
            lastGeminiRequestBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val body = payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun geminiPayload(vararg parts: JSONObject): String = JSONObject().put(
        "candidates",
        org.json.JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray(parts)))
        )
    ).toString()

    @Test
    fun `gemini thought parts stream as ReasoningChunk separated from content`() = runBlocking {
        serveGeminiSse(
            listOf(
                // A payload may carry thought and non-thought parts together —
                // the adapter must split them into their own lanes.
                geminiPayload(
                    JSONObject().put("text", "أحلل السؤال").put("thought", true),
                    JSONObject().put("text", " ثم أجيب")
                ),
                geminiPayload(JSONObject().put("text", "الجواب النهائي"))
            )
        )
        val events = geminiAdapter().stream(request(), "exec_g1").toList()

        assertEquals(
            listOf("أحلل السؤال"),
            events.filterIsInstance<ExecutionEvent.ReasoningChunk>().map { it.deltaText }
        )
        assertEquals(
            listOf(" ثم أجيب", "الجواب النهائي"),
            events.filterIsInstance<ExecutionEvent.ContentChunk>().map { it.deltaText }
        )
        val completed = events.filterIsInstance<ExecutionEvent.Completed>().single()
        assertEquals(" ثم أجيبالجواب النهائي", completed.finalText)
        assertFalse("thought text must never leak into the answer", completed.finalText.contains("أحلل"))
    }

    @Test
    fun `gemini request body asks for thoughts with includeThoughts`() = runBlocking {
        serveGeminiSse(
            listOf(geminiPayload(JSONObject().put("text", "جواب")))
        )
        geminiAdapter().stream(request(), "exec_g2").toList()

        val body = lastGeminiRequestBody.get()
        assertTrue("request body was captured", body != null)
        val thinkingConfig = JSONObject(body!!)
            .getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig")
        assertTrue("includeThoughts must be requested", thinkingConfig.getBoolean("includeThoughts"))
    }

    @Test
    fun `gemini reasoning-only stream is complete - no duplicate fallback request`() = runBlocking {
        var hits = 0
        server.createContext("/v1beta/models/gemini-2.5-flash:streamGenerateContent") { exchange ->
            hits++
            val body = "data: ${geminiPayload(JSONObject().put("text", "تفكير فقط").put("thought", true))}\n\n" + "data: [DONE]\n\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent") { exchange ->
            hits++
            val bytes = "{}".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val events = geminiAdapter().stream(request(), "exec_g3").toList()

        assertEquals(1, hits) // the single-shot fallback must NOT fire
        assertEquals(
            listOf("تفكير فقط"),
            events.filterIsInstance<ExecutionEvent.ReasoningChunk>().map { it.deltaText }
        )
    }
}
