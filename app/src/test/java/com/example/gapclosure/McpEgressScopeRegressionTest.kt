package com.example.gapclosure

import com.example.domain.core.Outcome
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.infrastructure.mcp.McpAdapter
import com.example.infrastructure.network.EgressControl
import com.example.infrastructure.network.GovernedHttpClientFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * ============================================================================
 * MCP Egress Scope — regression tests (Phase 0, MCP egress-scope closure)
 * ============================================================================
 *
 * McpAdapter must apply the EXECUTION's pinned [ExecutionScope] to EVERY
 * outbound MCP request — initialize, notifications/initialized, tools/list,
 * tools/call — via the legal mechanism [EgressControl.applyEgressScope].
 *
 * Before the fix, McpAdapter built requests with NO scope tag, so the
 * EgressControl interceptor fell back to the ACTIVE workspace at request
 * time: a mid-execution workspace switch silently re-targeted an in-flight
 * execution's MCP traffic to another workspace's policy, and
 * EgressBlockedException (an IOException) was caught by the generic
 * transport catch and reclassified as a misleading "MCP_TRANSPORT" failure
 * (or swallowed entirely on the fire-and-forget notifications/initialized
 * path).
 *
 * Proof strategy — no real network, no MockWebServer dependency:
 *   * The adapter's client is produced by [GovernedHttpClientFactory] with a
 *     test-local [EgressControl] (the REAL interceptor chain), then derived
 *     via newBuilder().addInterceptor(recorder). The recorder runs AFTER the
 *     egress interceptor, so a request only reaches it if egress ALLOWED it.
 *     The recorder answers every request with a canned JSON-RPC response, so
 *     a socket is never opened.
 *   * A request that carries NO scope tag resolves against the active
 *     workspace — so pointing the active workspace at an OFFLINE (or
 *     un-pinned) workspace makes ANY untagged request FAIL. Success of the
 *     full MCP handshake under such an active workspace therefore PROVES
 *     every request carried the execution's tag.
 */
@RunWith(RobolectricTestRunner::class)
class McpEgressScopeRegressionTest {

    // ------------------------------------------------------------------
    // Test infrastructure
    // ------------------------------------------------------------------

    /** One recorded outbound MCP request: method + the egress tag it carried. */
    private data class Recorded(val method: String, val tag: EgressControl.EgressScopeTag?)

    /**
     * Interceptor appended AFTER the EgressControl interceptor: records the
     * JSON-RPC method and the request's [EgressControl.EgressScopeTag], then
     * answers with a canned JSON-RPC 2.0 success. Optional [onServed] hook
     * lets a test mutate the world mid-flight (e.g. switch the active
     * workspace) to simulate a mid-execution change.
     */
    private class RpcRecorder(
        val recorded: MutableList<Recorded> = Collections.synchronizedList(mutableListOf()),
        val onServed: (method: String) -> Unit = {}
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val bodyText = request.body?.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            } ?: ""
            val method = runCatching {
                org.json.JSONObject(bodyText).optString("method", "unknown")
            }.getOrDefault("unknown")
            recorded += Recorded(method, request.tag(EgressControl.EgressScopeTag::class.java))
            onServed(method)
            val payload = when (method) {
                "initialize" ->
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26",""" +
                        """"capabilities":{},"serverInfo":{"name":"recorder","version":"1.0"}}}"""
                "tools/list" ->
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo",""" +
                        """"description":"echoes input","inputSchema":{"type":"object"}}]}}"""
                "tools/call" ->
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text",""" +
                        """"text":"echoed"}]}}"""
                else -> "{}" // notifications/initialized: server never answers
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun fakeConfig(endpoint: String = "http://127.0.0.1:9/mcp") =
        ServiceConfiguration(
            id = "cfg-mcp", serviceId = "svc-mcp",
            protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
            endpointUrl = endpoint, isEnabled = true
        )

    /**
     * Builds an McpAdapter whose client comes from the REAL governed factory
     * (test-local egress authority) with the recorder appended after the
     * egress interceptor. Same construction contract as the composition root:
     * egressControl and client share ONE authority instance.
     */
    private fun adapterWithRecorder(
        egress: EgressControl,
        onServed: (String) -> Unit = {}
    ): Pair<McpAdapter, RpcRecorder> {
        val recorder = RpcRecorder(onServed = onServed)
        val client = GovernedHttpClientFactory(egress).create()
            .newBuilder()
            .addInterceptor(recorder)
            .build()
        return McpAdapter(
            serviceId = "svc-mcp",
            config = fakeConfig(),
            egressControl = egress,
            client = client
        ) to recorder
    }

    /** The execution pinned for every test: workspace ws-exec, session sess-1. */
    private fun executionScope() = ExecutionScope(
        executionId = "exec-1",
        workspaceId = "ws-exec",
        projectId = null,
        sessionId = "sess-1"
    )

    // ------------------------------------------------------------------
    // 1. Mid-execution active-workspace switch must NOT re-target policy
    // ------------------------------------------------------------------

    @Test
    fun `execution scope governs MCP - active workspace switch mid-execution does not re-target`() =
        runBlocking {
            // ws-exec (the execution's workspace) allows; ws-other denies.
            val egress = EgressControl().apply {
                pinWorkspacePolicy("ws-exec", NetworkPolicy.HYBRID)
                pinWorkspacePolicy("ws-other", NetworkPolicy.OFFLINE)
                setActiveWorkspace("ws-exec")
            }
            // Switch the ACTIVE workspace to the OFFLINE one right after the
            // initialize request is served — the execution is mid-flight.
            val (adapter, recorder) = adapterWithRecorder(egress) { method ->
                if (method == "initialize") setActiveWorkspaceOf(egress, "ws-other")
            }
            val outcome = withContext(executionScope()) { adapter.discoverTools() }

            assertTrue(
                "discoverTools must stay governed by the execution's workspace " +
                    "(ws-exec=HYBRID) despite the mid-execution active-workspace switch " +
                    "to ws-other=OFFLINE; got: $outcome",
                outcome is Outcome.Success
            )
            assertEquals(
                "The full handshake must have run (initialize, notification, tools/list)",
                listOf("initialize", "notifications/initialized", "tools/list"),
                recorder.recorded.map { it.method }
            )
            assertTrue(
                "Every request must carry the execution's scope tag",
                recorder.recorded.all {
                    it.tag?.workspaceId == "ws-exec" && it.tag?.sessionId == "sess-1"
                }
            )
            // And the discovered tool really came through the governed pipe.
            assertEquals(1, (outcome as Outcome.Success).value.size)
            assertEquals("echo", outcome.value.first().name)
        }

    // ------------------------------------------------------------------
    // 2. Fail-closed: an OFFLINE execution workspace cannot be escaped by
    //    switching the active workspace to an ONLINE one
    // ------------------------------------------------------------------

    @Test
    fun `OFFLINE execution workspace stays fail-closed even when active workspace allows`() =
        runBlocking {
            val egress = EgressControl().apply {
                pinWorkspacePolicy("ws-exec", NetworkPolicy.OFFLINE)
                pinWorkspacePolicy("ws-online", NetworkPolicy.HYBRID)
                setActiveWorkspace("ws-online") // an ALLOWING active workspace
            }
            val (adapter, recorder) = adapterWithRecorder(egress)
            val outcome = withContext(executionScope()) { adapter.discoverTools() }

            assertTrue("OFFLINE execution workspace must deny egress", outcome is Outcome.Error)
            val error = outcome as Outcome.Error
            assertEquals(
                "The denial must carry the explicit EGRESS_BLOCKED failure code",
                "EGRESS_BLOCKED",
                error.failure
            )
            assertTrue(
                "The diagnostic must carry the machine-readable reason, got: ${error.diagnosticMessage}",
                error.diagnosticMessage.contains("EGRESS_OFFLINE_POLICY")
            )
            assertTrue(
                "A policy denial must NOT be reclassified as a transport failure",
                error.failure != "MCP_TRANSPORT" &&
                    !error.diagnosticMessage.startsWith("MCP initialize transport failure")
            )
            assertEquals(
                "The deny must happen BEFORE any request reaches transport (recorder empty)",
                emptyList<String>(),
                recorder.recorded.map { it.method }
            )
        }

    // ------------------------------------------------------------------
    // 3. ALL FOUR MCP request types carry the execution scope tag
    // ------------------------------------------------------------------

    @Test
    fun `all four MCP request types carry the execution scope tag`() = runBlocking {
        // The active workspace is pointed at an UN-PINNED workspace for every
        // request: any request lacking the tag would fall back to it and be
        // denied (EGRESS_POLICY_MISSING / EGRESS_NO_PINNED_POLICY). Success of
        // the full handshake therefore proves EVERY request was tagged.
        val egress = EgressControl().apply {
            pinWorkspacePolicy("ws-exec", NetworkPolicy.HYBRID)
            setActiveWorkspace("ws-impostor") // pinned nowhere — fail-closed fallback
        }
        val (adapter, recorder) = adapterWithRecorder(egress) { _ ->
            setActiveWorkspaceOf(egress, "ws-impostor") // keep it switched mid-flight
        }

        val discovered = withContext(executionScope()) { adapter.discoverTools() }
        val called = withContext(executionScope()) { adapter.callTool("echo", """{"x":1}""") }

        assertTrue("discoverTools must succeed under the pinned scope, got: $discovered", discovered is Outcome.Success)
        assertTrue("callTool must succeed under the pinned scope, got: $called", called is Outcome.Success)
        assertEquals(
            "Exactly the four MCP request types, in protocol order",
            listOf("initialize", "notifications/initialized", "tools/list", "tools/call"),
            recorder.recorded.map { it.method }
        )
        assertEquals(
            "Every request must be stamped with the execution-bound workspace",
            List(4) { EgressControl.EgressScopeTag("ws-exec", "sess-1") },
            recorder.recorded.map { it.tag }
        )
    }

    // ------------------------------------------------------------------
    // 4. EGRESS_BLOCKED on notifications/initialized is surfaced, never
    //    swallowed (the old runCatching discarded it silently)
    // ------------------------------------------------------------------

    @Test
    fun `EGRESS_BLOCKED on notifications initialized is surfaced not swallowed`() = runBlocking {
        val egress = EgressControl().apply {
            pinWorkspacePolicy("ws-exec", NetworkPolicy.HYBRID)
            pinWorkspacePolicy("ws-elsewhere", NetworkPolicy.HYBRID)
            setActiveWorkspace("ws-elsewhere") // fallback would ALLOW (old bug path)
        }
        // Flip the EXECUTION workspace's policy to OFFLINE right after
        // initialize is served: the notification request (tagged ws-exec)
        // must now be denied — while an untagged request (old behavior)
        // would have fallen back to ws-elsewhere and silently succeeded.
        val (adapter, recorder) = adapterWithRecorder(egress) { method ->
            if (method == "initialize") {
                egress.pinWorkspacePolicy("ws-exec", NetworkPolicy.OFFLINE)
            }
        }
        val outcome = withContext(executionScope()) { adapter.discoverTools() }

        assertTrue("The notification denial must fail the session, got: $outcome", outcome is Outcome.Error)
        val error = outcome as Outcome.Error
        assertEquals("EGRESS_BLOCKED", error.failure)
        assertTrue(
            "The error must name notifications/initialized as the denied request, got: ${error.diagnosticMessage}",
            error.diagnosticMessage.contains("notifications/initialized")
        )
        assertTrue(
            "The error must carry the machine-readable reason, got: ${error.diagnosticMessage}",
            error.diagnosticMessage.contains("EGRESS_OFFLINE_POLICY")
        )
        assertEquals(
            "Only initialize reached transport — the deny hit the notification, " +
                "and tools/list was never issued",
            listOf("initialize"),
            recorder.recorded.map { it.method }
        )
    }

    // ------------------------------------------------------------------
    // 5. Unscoped (user-driven) requests keep the documented active-workspace
    //    fallback — the fix must not over-block user-driven control-plane use
    // ------------------------------------------------------------------

    @Test
    fun `unscoped user-driven request still resolves against the active workspace`() =
        runBlocking {
            val egress = EgressControl().apply {
                pinWorkspacePolicy("ws-user", NetworkPolicy.HYBRID)
                setActiveWorkspace("ws-user")
            }
            val (adapter, recorder) = adapterWithRecorder(egress)

            // NO ExecutionScope pinned — the documented fallback path.
            val outcome = adapter.discoverTools()

            assertTrue(
                "User-driven discovery (no execution scope) must stay allowed via the " +
                    "active workspace, got: $outcome",
                outcome is Outcome.Success
            )
            assertEquals(
                "No scope tag is stamped outside an execution (active-workspace fallback)",
                listOf(null, null, null),
                recorder.recorded.map { it.tag }
            )
        }

    private fun setActiveWorkspaceOf(egress: EgressControl, workspaceId: String) {
        egress.setActiveWorkspace(workspaceId)
    }
}
