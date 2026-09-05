package com.example.infrastructure.llm.gemini

import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmMessage
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.MessageRole
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.infrastructure.provider.ProtocolAdapterFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * ============================================================================
 * Gemini REST adapter verification — fix "firebase not initiated"
 * ============================================================================
 *
 * Regression tests for the device-reported crash: the old adapter called
 * Firebase.ai which requires google-services.json (absent) → every call died
 * with "FirebaseApp initialization unsuccessful". The REST adapter must:
 *
 *   1. Work with ZERO Firebase involvement (no FirebaseApp needed).
 *   2. Fail honestly with AuthenticationFailed when no key is stored.
 *   3. Send the vault key in the x-goog-api-key HEADER (never the URL).
 *   4. Parse generateContent + streamGenerateContent (SSE) wire formats.
 *   5. Map HTTP 401/403 onto LlmFailure.AuthenticationFailed.
 *   6. Receive the apiKeyProvider from ProtocolAdapterFactory (the stored key
 *      must actually reach generation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiRestAdapterRobolectricTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val lastRequestPath = AtomicReference<String?>(null)
    private val lastApiKeyHeader = AtomicReference<String?>(null)
    private val lastRequestBody = AtomicReference<String?>(null)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        baseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun route(path: String, status: Int = 200, body: String = "") {
        server.createContext(path) { exchange: HttpExchange ->
            lastRequestPath.set(exchange.requestURI.path)
            lastApiKeyHeader.set(exchange.requestHeaders.getFirst("x-goog-api-key"))
            lastRequestBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun request(system: String? = "أنت مساعد ذكي", user: String = "مرحبا"): LlmRequest =
        LlmRequest(
            messages = buildList {
                system?.let { add(LlmMessage(MessageRole.SYSTEM, it)) }
                add(LlmMessage(MessageRole.USER, user))
            }
        )

    private val geminiResponse = """
        {
          "candidates": [
            {
              "content": {"role": "model", "parts": [{"text": "الرد من الخادم المحلي"}]},
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 7},
          "modelVersion": "gemini-2.5-flash"
        }
    """.trimIndent()

    /* -------------------------------------------------------------------- */

    @Test
    fun `generate without any key fails honestly without firebase`() = runBlocking {
        // No google-services.json, no FirebaseApp.initializeApp — the adapter
        // must never touch Firebase; a missing key is an honest auth failure.
        val adapter = GeminiLlmAdapter(
            defaultModelName = "gemini-2.5-flash",
            apiKeyProvider = { null },
            baseUrl = "https://generativelanguage.googleapis.com"
        )
        val outcome = adapter.generate(request())
        assertTrue("Expected Error outcome, got $outcome", outcome is com.example.domain.core.Outcome.Error)
        val failure = (outcome as com.example.domain.core.Outcome.Error).failure
        assertTrue(
            "Expected AuthenticationFailed, got $failure",
            failure is LlmFailure.AuthenticationFailed
        )
    }

    @Test
    fun `generate sends key header, systemInstruction and parses the response`() = runBlocking {
        route("/v1beta/models/gemini-2.5-flash:generateContent", body = geminiResponse)
        val adapter = GeminiLlmAdapter(
            defaultModelName = "gemini-2.5-flash",
            apiKeyProvider = { "test-key-123" },
            baseUrl = baseUrl
        )
        val outcome = adapter.generate(request(system = "أنت مساعد رسمي", user = "اكتب تقريراً"))

        assertTrue("Expected success, got $outcome", outcome is com.example.domain.core.Outcome.Success)
        val response = (outcome as com.example.domain.core.Outcome.Success).value
        assertEquals("الرد من الخادم المحلي", response.text)
        assertEquals("STOP", response.finishReason)
        assertEquals(12, response.usage.promptTokens)
        assertEquals(7, response.usage.completionTokens)

        // Wire format verification
        assertEquals(
            "Path must be the REST generateContent endpoint",
            "/v1beta/models/gemini-2.5-flash:generateContent",
            lastRequestPath.get()
        )
        assertEquals("Key must travel in the x-goog-api-key header", "test-key-123", lastApiKeyHeader.get())
        val body = JSONObject(lastRequestBody.get() ?: failBody())
        assertEquals(
            "SYSTEM messages must be lifted into systemInstruction (REST-native)",
            "أنت مساعد رسمي",
            body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        )
        assertEquals(
            "User turn must map to role=user with parts",
            "user",
            body.getJSONArray("contents").getJSONObject(0).getString("role")
        )
        assertEquals(
            "User text must be carried in parts[0].text",
            "اكتب تقريراً",
            body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun `http 401 maps to authentication failure`() = runBlocking {
        route("/v1beta/models/gemini-2.5-flash:generateContent", status = 401, body = """{"error": {"code": 401}}""")
        val adapter = GeminiLlmAdapter(
            defaultModelName = "gemini-2.5-flash",
            apiKeyProvider = { "wrong-key" },
            baseUrl = baseUrl
        )
        val outcome = adapter.generate(request())
        assertTrue(outcome is com.example.domain.core.Outcome.Error)
        assertTrue(
            (outcome as com.example.domain.core.Outcome.Error).failure is LlmFailure.AuthenticationFailed
        )
    }

    @Test
    fun `stream collects SSE chunks and completes`() = runBlocking {
        val sse = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"مرح\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"با بك\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" المطور\"}]}}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}\n\n")
        }
        route("/v1beta/models/gemini-2.5-flash:streamGenerateContent", body = sse)
        val adapter = GeminiLlmAdapter(
            defaultModelName = "gemini-2.5-flash",
            apiKeyProvider = { "test-key-123" },
            baseUrl = baseUrl
        )
        val events = adapter.stream(request(system = null), executionId = "exec-1").toList()

        val chunks = events.filterIsInstance<ExecutionEvent.ContentChunk>()
        assertEquals(3, chunks.size)
        assertEquals("مرحبا بك المطور", chunks.joinToString(separator = "") { it.deltaText })
        assertNotNull("Completed event required", events.firstOrNull { it is ExecutionEvent.Completed })
        val completed = events.first { it is ExecutionEvent.Completed } as ExecutionEvent.Completed
        assertEquals("مرحبا بك المطور", completed.finalText)
    }

    @Test
    fun `factory wires the vault key provider into the REST gemini adapter`() {
        // ProtocolAdapterFactory must pass the apiKeyProvider (the old code
        // dropped it, so the stored key never reached generation).
        var queried = false
        val factory = ProtocolAdapterFactory(geminiBootstrap = null)
        val adapter = factory.createLlmAdapter(
            service = ProviderService(
                id = "svc_test",
                providerId = "prov_test",
                name = "Gemini test service",
                serviceType = ServiceType.LLM,
                supportedProtocolIds = listOf(ServiceProtocolId.GEMINI_NATIVE.code),
                isEnabled = true
            ),
            protocolId = ServiceProtocolId.GEMINI_NATIVE,
            config = ServiceConfiguration(
                id = "cfg_test",
                serviceId = "svc_test",
                protocolId = ServiceProtocolId.GEMINI_NATIVE,
                endpointUrl = "https://generativelanguage.googleapis.com",
                defaultOfferingId = "gemini-2.5-flash",
                authAlias = "prov_key",
                isEnabled = true,
                isDefault = true
            ),
            apiKeyProvider = {
                queried = true
                "key-from-vault"
            },
            offeringModelId = "gemini-2.5-flash"
        )
        assertNotNull(adapter)
        assertEquals("GEMINI_REST", adapter!!.metadata.providerType)

        // The provider must actually be consulted at generation time.
        runBlocking {
            adapter.generate(request())
        }
        assertTrue("apiKeyProvider was never consulted — the key would be ignored", queried)
    }

    private fun failBody(): String = throw AssertionError("Request body was not captured")
}
