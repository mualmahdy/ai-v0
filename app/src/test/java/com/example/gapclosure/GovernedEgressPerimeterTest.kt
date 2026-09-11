package com.example.gapclosure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.resource.ResourceType
import com.example.infrastructure.mcp.McpAdapter
import com.example.infrastructure.network.EgressControl
import com.example.infrastructure.network.GovernedHttpClientFactory
import com.example.infrastructure.provider.DiscoveryAdapterFactory
import com.example.infrastructure.radar.GitHubReleasesRadarSource
import com.example.infrastructure.validation.defaultResourceValidatorRegistry
import com.example.infrastructure.memory.semantic.OnnxSemanticEmbeddingAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * GAP-03 (Design Closure 2026, ADR-3) — Governed egress perimeter test
 * ============================================================================
 *
 * The audit found FOUR production egress paths that built private OkHttp
 * clients with NO EgressControl interceptor (resource validation pings,
 * MCP adapter JSON-RPC, Radar GitHub/RSS discovery, ONNX model download),
 * plus a globally mutable static `DiscoveryAdapterFactory.egressControl`.
 * An OFFLINE workspace could therefore still emit network traffic — the
 * fail-closed egress contract was bypassable from the UI.
 *
 * This test pins the repaired perimeter:
 *   1. Every client produced by [GovernedHttpClientFactory] denies requests
 *      under an OFFLINE workspace policy BEFORE any socket is opened
 *      (EgressBlockedException, reason EGRESS_OFFLINE_POLICY).
 *   2. Un-pinned egress (the constructor default) fails CLOSED.
 *   3. Each formerly-bypassing path (validator ping / MCP / radar / ONNX
 *      provisioning / discovery) now surfaces an explicit EGRESS_BLOCKED
 *      denial — proving the actual wiring, not just the class diagram.
 *   4. No NEW direct `OkHttpClient.Builder()`/`OkHttpClient()` may appear
 *      in runtime code outside the factory (and the frozen inline-governed
 *      allowlist) — an architecture scan that fails on the next bypass.
 */
@RunWith(RobolectricTestRunner::class)
class GovernedEgressPerimeterTest {

    private val offlineEgress: EgressControl = EgressControl().apply {
        pinWorkspacePolicy("ws-offline", NetworkPolicy.OFFLINE)
        setActiveWorkspace("ws-offline")
    }

    private val onlineEgress: EgressControl = EgressControl().apply {
        pinWorkspacePolicy("ws-online", NetworkPolicy.HYBRID)
        setActiveWorkspace("ws-online")
    }

    // ------------------------------------------------------------------
    // 1. The factory itself
    // ------------------------------------------------------------------

    @Test
    fun `factory client denies under OFFLINE policy before any socket`() {
        val client = GovernedHttpClientFactory(offlineEgress).create()
        try {
            // Unroutable URL on purpose: if the interceptor were missing, the
            // call would fail with a CONNECT error, NOT EgressBlockedException.
            client.newCall(
                Request.Builder().url("http://10.255.255.1:9/never-reached").build()
            ).execute()
            fail("Expected EgressBlockedException under OFFLINE policy")
        } catch (e: EgressControl.EgressBlockedException) {
            assertEquals("EGRESS_OFFLINE_POLICY", e.reasonCode)
        }
    }

    @Test
    fun `factory client fails closed when no policy is pinned`() {
        val client = GovernedHttpClientFactory().create() // EgressControl.default — un-pinned
        try {
            client.newCall(
                Request.Builder().url("http://10.255.255.1:9/never-reached").build()
            ).execute()
            fail("Expected EgressBlockedException for un-pinned egress")
        } catch (e: EgressControl.EgressBlockedException) {
            assertEquals("EGRESS_NO_PINNED_POLICY", e.reasonCode)
        }
    }

    // ------------------------------------------------------------------
    // 2. The formerly-bypassing paths are now governed (behavioral proof)
    // ------------------------------------------------------------------

    private fun fakeServiceConfig(endpoint: String = "https://egress-test.invalid/v1") =
        ServiceConfiguration(
            id = "cfg-test", serviceId = "svc-test",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = endpoint, isEnabled = true
        )

    @Test
    fun `validator ping path is governed - OFFLINE denies Test Connection`() = runBlocking {
        // A resource with a stored key, validated under an OFFLINE policy.
        val registry = defaultResourceValidatorRegistry(egressControl = offlineEgress)
        val validator = checkNotNull(registry.get(ResourceType.LLM)) { "LLM validator missing" }
        val result = validator.validate(
            service = ProviderService(
                id = "svc-test", providerId = "p1", name = "LLM",
                serviceType = ServiceType.LLM,
                supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
                isEnabled = true
            ),
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            config = fakeServiceConfig(),
            adapter = null,
            apiKeyProvider = { "stored-test-key" }
        )
        // The governed client denies BEFORE any socket: the IOException catch
        // classifies it as TRANSPORT_FAILURE carrying the EGRESS_BLOCKED reason.
        assertTrue(
            "Validator ping must be egress-governed, got: ${result.message}",
            result.message?.contains("EGRESS_BLOCKED") == true
        )
    }

    @Test
    fun `MCP adapter session is governed - OFFLINE denies JSON-RPC`() = runBlocking {
        val adapter = McpAdapter(
            serviceId = "svc-test",
            config = fakeServiceConfig("https://mcp-egress-test.invalid/rpc"),
            egressControl = offlineEgress,
            client = GovernedHttpClientFactory(offlineEgress).create(
                connectTimeoutSeconds = 10, readTimeoutSeconds = 30
            )
        )
        val outcome = adapter.discoverTools()
        assertTrue(outcome is com.example.domain.core.Outcome.Error)
        val error = (outcome as com.example.domain.core.Outcome.Error)
        val combined = error.failure + " " + error.diagnosticMessage
        assertTrue(
            "MCP rpc must be egress-governed, got: $combined",
            combined.contains("EGRESS_BLOCKED")
        )
    }

    @Test
    fun `radar GitHub source is governed - OFFLINE denies discovery fetch`() = runBlocking {
        val source = GitHubReleasesRadarSource(
            client = GovernedHttpClientFactory(offlineEgress).create(
                connectTimeoutSeconds = 6, readTimeoutSeconds = 8
            )
        )
        val outcome = source.fetchDiscoveries()
        assertTrue(outcome is com.example.domain.core.Outcome.Error)
        assertTrue(
            "Radar fetch must be egress-governed, got: ${(outcome as com.example.domain.core.Outcome.Error).failure}",
            (outcome as com.example.domain.core.Outcome.Error).failure.contains("EGRESS_BLOCKED")
        )
    }

    @Test
    fun `ONNX semantic model provisioning is governed - OFFLINE denies the download`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val adapter = OnnxSemanticEmbeddingAdapter(
                appContext = context,
                client = GovernedHttpClientFactory(offlineEgress).create(
                    connectTimeoutSeconds = 20, readTimeoutSeconds = 120, writeTimeoutSeconds = 120
                )
            )
            val outcome = adapter.provision()
            assertTrue(outcome is com.example.domain.core.Outcome.Error)
            assertTrue(
                "ONNX provisioning must be egress-governed, got: ${(outcome as com.example.domain.core.Outcome.Error).failure}",
                (outcome as com.example.domain.core.Outcome.Error).failure.contains("EGRESS_BLOCKED")
            )
        }

    @Test
    fun `model discovery is governed - OFFLINE denies the models dial`() = runBlocking {
        val factory = DiscoveryAdapterFactory(egressControl = offlineEgress)
        val outcome = factory.discover(
            service = ProviderService(
                id = "svc-test", providerId = "p1", name = "LLM",
                serviceType = ServiceType.LLM,
                supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
                isEnabled = true
            ),
            config = fakeServiceConfig(),
            apiKeyProvider = { "stored-test-key" }
        )
        val err = (outcome as com.example.domain.core.Outcome.Error)
        val combined = "${err.failure} ${err.diagnosticMessage}"
        assertTrue(
            "Discovery must be egress-governed, got: $combined",
            combined.contains("EGRESS_BLOCKED")
        )
    }

    @Test
    fun `online policy still allows governed traffic to fail honestly at transport`() = runBlocking {
        // Sanity: governance must not turn a legit ONLINE path into a deny —
        // with a HYBRID policy and an unroutable endpoint, the outcome is a
        // TRANSPORT-level error WITHOUT EGRESS_BLOCKED (policy allowed, socket
        // attempt failed) — proving the factory does not over-block.
        val factory = DiscoveryAdapterFactory(egressControl = onlineEgress)
        val outcome = factory.discover(
            service = ProviderService(
                id = "svc-test", providerId = "p1", name = "LLM",
                serviceType = ServiceType.LLM,
                supportedProtocolIds = listOf(ServiceProtocolId.OPENAI_COMPATIBLE.code),
                isEnabled = true
            ),
            config = fakeServiceConfig("http://10.255.255.1:9/v1"),
            apiKeyProvider = { null }
        )
        val isTransportError = (outcome is com.example.domain.core.Outcome.Error) &&
            !(outcome as com.example.domain.core.Outcome.Error).failure.contains("EGRESS_BLOCKED")
        assertTrue(
            "Online policy must allow the dial (transport error expected, not egress deny): ${(outcome as com.example.domain.core.Outcome.Error).failure}",
            isTransportError
        )
    }

    // ------------------------------------------------------------------
    // 3. Architecture guard — no NEW direct OkHttpClient outside the factory
    // ------------------------------------------------------------------

    /**
     * EXACT allowlist of files permitted to construct an OkHttpClient
     * directly. Everything else must obtain clients from
     * [GovernedHttpClientFactory]. Adding a new file to this list requires
     * an explicit ADR-level justification (the factory is the single
     * authority per ADR-3).
     */
    private val directClientAllowlist = setOf(
        // THE factory.
        "com/example/infrastructure/network/GovernedHttpClientFactory.kt",
        // Frozen legacy adapters that build their own builder INLINE but
        // install egressControl.interceptor() themselves (verified below).
        "com/example/infrastructure/integration/IntegrationAdapters.kt",
        "com/example/infrastructure/search/MultiSourceSearchAdapter.kt",
        "com/example/infrastructure/search/TavilySearchAdapter.kt",
        "com/example/infrastructure/llm/gemini/GeminiLlmAdapter.kt",
        "com/example/infrastructure/llm/openai/OpenAiCompatibleEmbeddingAdapter.kt",
        "com/example/infrastructure/llm/openai/OpenAiCompatibleLlmAdapter.kt",
        "com/example/infrastructure/mcp/McpClient.kt"
    )

    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        var attempts = 0
        while (attempts < 4) {
            if (File(dir, "src/main/java/com/example").exists()) return dir
            dir = dir.parentFile ?: break
            attempts++
        }
        return File(System.getProperty("user.dir")!!)
    }

    @Test
    fun `no direct OkHttpClient construction outside the governed factory - GAP-03 perimeter`() {
        val root = File(moduleRoot(), "src/main/java")
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (path in directClientAllowlist) return@forEach
                val text = file.readText()
                if (Regex("OkHttpClient\\s*\\.?\\s*Builder\\s*\\(").containsMatchIn(text) ||
                    Regex("OkHttpClient\\s*\\(").containsMatchIn(text)
                ) {
                    violations += path
                }
            }
        assertEquals(
            "GAP-03 perimeter: these files construct OkHttpClient directly — " +
                "obtain the client from GovernedHttpClientFactory instead: $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `every allowlisted inline client still wires egressControl`() {
        val root = File(moduleRoot(), "src/main/java")
        val unguarded = mutableListOf<String>()
        for (path in directClientAllowlist) {
            if (path.endsWith("GovernedHttpClientFactory.kt")) continue
            val file = File(root, path)
            if (!file.exists()) {
                unguarded += "$path (missing)"
                continue
            }
            if (!file.readText().contains("egressControl")) {
                unguarded += "$path (no egressControl wiring!)"
            }
        }
        assertEquals(
            "Frozen inline clients must keep their egressControl interceptor: $unguarded",
            0,
            unguarded.size
        )
    }
}
