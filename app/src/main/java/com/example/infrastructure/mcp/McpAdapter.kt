package com.example.infrastructure.mcp

import com.example.domain.core.Outcome
import com.example.domain.core.extension.McpDiscoveredTool
import com.example.domain.core.provider.ServiceConfiguration
import com.example.infrastructure.network.EgressControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 * McpAdapterPort / McpAdapter — Phase 4 (Streamable HTTP JSON-RPC 2.0)
 * ============================================================================
 *
 * Minimal REAL MCP session: initialize + tools/list against the configured
 * endpoint using the MCP "Streamable HTTP" transport (single POST endpoint,
 * JSON-RPC 2.0 envelopes). Each session holds its own JSON-RPC id counter.
 *
 * Discovering tools returns domain [McpDiscoveredTool]s — the control plane
 * converts them to ServiceOfferings (OfferingType.TOOL). No fabricated
 * success: transport/parse failures are explicit errors.
 */
interface McpAdapterPort {
    suspend fun discoverTools(): Outcome<List<McpDiscoveredTool>, String>
    suspend fun callTool(name: String, argumentsJson: String): Outcome<String, String>
    suspend fun close()
}

/**
 * EGRESS SCOPE (MCP egress-scope closure): EVERY outbound MCP request this
 * adapter issues — initialize, notifications/initialized, tools/list,
 * tools/call — is stamped with the governing [EgressControl.EgressScopeTag]
 * via [EgressControl.applyEgressScope] (the same legal mechanism every other
 * governed adapter uses). The tag is derived from the coroutine's pinned
 * [com.example.domain.core.execution.ExecutionScope], so an execution's MCP
 * traffic stays governed by the workspace BOUND TO THAT EXECUTION even when
 * the ACTIVE workspace changes mid-execution. Requests issued outside an
 * execution (user-driven control-plane paths) carry no tag and resolve
 * against the active workspace — the documented EgressControl fallback for
 * unscoped requests.
 *
 * EGRESS_BLOCKED is never swallowed or reclassified: a policy denial
 * (EgressBlockedException) surfaces as Outcome.Error("EGRESS_BLOCKED", …)
 * carrying the machine-readable reason — never as a misleading MCP_TRANSPORT
 * transport failure, and never silently discarded on the fire-and-forget
 * notifications/initialized path.
 */
class McpAdapter(
    private val serviceId: String,
    private val config: ServiceConfiguration,
    /**
     * GAP-25 (Design Closure 2026): an unused `transportType` constructor
     * parameter was removed — it was declared (default SSE) but never read
     * by this class (the SSE endpoint URL in [config] already determines
     * the transport).
     */
    /**
     * The composition-root-owned egress authority that stamps the execution
     * scope onto every outbound MCP request (applyEgressScope). It must be
     * the SAME instance whose interceptor governs [client] — the composition
     * root wires them as a pair (see
     * ProviderControlPlaneService.getOrCreateMcpSession).
     */
    private val egressControl: EgressControl = EgressControl.default,
    /**
     * GAP-03 (Design Closure 2026, ADR-3): the client is now built by
     * [com.example.infrastructure.network.GovernedHttpClientFactory], so the
     * EgressControl interceptor is ALWAYS installed — an OFFLINE workspace
     * denies MCP JSON-RPC calls BEFORE any socket is opened. Previously this
     * constructor accepted an injected-but-NEVER-used `McpClient` (dead
     * parameter — all traffic flowed through this PRIVATE ungoverned client);
     * the dead parameter is removed and the real client is governed.
     */
    private val client: OkHttpClient = com.example.infrastructure.network.GovernedHttpClientFactory(
        egressControl
    )
        .create(connectTimeoutSeconds = 10, readTimeoutSeconds = 30)
) : McpAdapterPort {

    private val nextRequestId = AtomicLong(1)
    private var initialized = false

    private suspend fun rpc(method: String, params: JSONObject): Outcome<JSONObject, String> =
        withContext(Dispatchers.IO) {
            try {
                val envelope = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", nextRequestId.getAndIncrement())
                    .put("method", method)
                    .put("params", params)

                // EGRESS SCOPE: stamp the pinned ExecutionScope's workspace
                // (and sandbox session) onto THIS request — the egress
                // decision then follows the execution-bound workspace, not
                // the active workspace at request time.
                val request = egressControl.applyEgressScope(
                    Request.Builder()
                        .url(config.endpointUrl)
                        .post(envelope.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Accept", "application/json, text/event-stream")
                ).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Outcome.Error(
                            "MCP_HTTP_${response.code}",
                            "MCP $method failed: HTTP ${response.code} from ${config.endpointUrl}"
                        )
                    }
                    val body = response.body?.string()
                        ?: return@withContext Outcome.Error(
                            "MCP_EMPTY_BODY",
                            "MCP $method returned empty body"
                        )
                    // Streamable HTTP may answer with SSE frames — take the first data line.
                    val jsonText = if (body.trimStart().startsWith("{")) {
                        body
                    } else {
                        body.lineSequence()
                            .firstOrNull { it.startsWith("data:") }
                            ?.removePrefix("data:")?.trim()
                            ?: return@withContext Outcome.Error(
                                "MCP_PARSE_FAILURE",
                                "MCP $method: unparseable response"
                            )
                    }
                    val json = JSONObject(jsonText)
                    if (json.has("error")) {
                        val err = json.getJSONObject("error")
                        return@withContext Outcome.Error(
                            "MCP_${err.optInt("code", -1)}",
                            err.optString("message", "MCP error")
                        )
                    }
                    Outcome.Success(json)
                }
            } catch (e: EgressControl.EgressBlockedException) {
                // EGRESS_BLOCKED is a POLICY denial, not a transport failure:
                // surface it explicitly with its machine-readable reason —
                // never let it fall into the IOException catch below and be
                // reclassified as a misleading MCP_TRANSPORT diagnostic.
                Outcome.Error(
                    "EGRESS_BLOCKED",
                    "MCP $method denied by egress policy: reason=${e.reasonCode}; ${e.message}"
                )
            } catch (e: java.net.SocketTimeoutException) {
                Outcome.Error("MCP_TIMEOUT", "MCP $method timed out: ${e.message}")
            } catch (e: java.io.IOException) {
                Outcome.Error("MCP_TRANSPORT", "MCP $method transport failure: ${e.message}")
            } catch (e: Exception) {
                Outcome.Error("MCP_FAILURE", "MCP $method failed: ${e.message}")
            }
        }

    private suspend fun ensureInitialized(): Outcome<Unit, String> {
        if (initialized) return Outcome.Success(Unit)
        val result = rpc(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "ai-v0").put("version", "1.0"))
        )
        return when (result) {
            is Outcome.Success -> {
                // Send the initialized notification (fire-and-forget, no id).
                // EGRESS SCOPE: stamped with the execution's pinned scope like
                // every other MCP request — initialize, notifications/
                // initialized, tools/list and tools/call are ALL governed by
                // the execution-bound workspace.
                val notificationRequest = egressControl.applyEgressScope(
                    Request.Builder()
                        .url(config.endpointUrl)
                        .post(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("method", "notifications/initialized")
                                .toString()
                                .toRequestBody("application/json".toMediaType())
                        )
                        .addHeader("Accept", "application/json, text/event-stream")
                ).build()
                val notificationOutcome = withContext(Dispatchers.IO) {
                    try {
                        client.newCall(notificationRequest).execute().close()
                        Outcome.Success(Unit)
                    } catch (e: EgressControl.EgressBlockedException) {
                        // A POLICY denial on the notification is NOT swallowed:
                        // fire-and-forget applies to transport noise only. The
                        // session must not be marked initialized while its
                        // governing scope is denied egress.
                        Outcome.Error(
                            "EGRESS_BLOCKED",
                            "MCP notifications/initialized denied by egress policy: " +
                                "reason=${e.reasonCode}; ${e.message}"
                        )
                    } catch (e: Exception) {
                        // Transport-level failure on a NOTIFICATION is
                        // non-fatal per MCP semantics (no response is
                        // expected) — fire-and-forget for noise ONLY.
                        Outcome.Success(Unit)
                    }
                }
                if (notificationOutcome is Outcome.Error) return notificationOutcome
                initialized = true
                Outcome.Success(Unit)
            }
            is Outcome.Error -> Outcome.Error(result.failure, result.diagnosticMessage)
            is Outcome.Degraded -> Outcome.Error("MCP_INIT_DEGRADED", result.diagnosticMessage)
        }
    }

    override suspend fun discoverTools(): Outcome<List<McpDiscoveredTool>, String> {
        val init = ensureInitialized()
        if (init !is Outcome.Success) {
            return Outcome.Error(
                (init as? Outcome.Error)?.failure ?: "MCP_INIT_FAILED",
                init.let { (it as? Outcome.Error)?.diagnosticMessage } ?: "initialize failed"
            )
        }
        return when (val result = rpc("tools/list", JSONObject())) {
            is Outcome.Success -> {
                val toolsJson: JSONArray = result.value.optJSONObject("result")
                    ?.optJSONArray("tools") ?: JSONArray()
                val tools = (0 until toolsJson.length()).mapNotNull { i ->
                    val tool = toolsJson.getJSONObject(i)
                    val name = tool.optString("name")
                    if (name.isBlank()) return@mapNotNull null
                    McpDiscoveredTool(
                        name = name,
                        description = tool.optString("description"),
                        inputSchemaJson = tool.optJSONObject("inputSchema")?.toString() ?: "{}"
                    )
                }
                Outcome.Success(tools)
            }
            is Outcome.Error -> Outcome.Error(result.failure, result.diagnosticMessage)
            is Outcome.Degraded -> Outcome.Error("MCP_TOOLS_DEGRADED", result.diagnosticMessage)
        }
    }

    override suspend fun callTool(name: String, argumentsJson: String): Outcome<String, String> {
        val init = ensureInitialized()
        if (init !is Outcome.Success) {
            return Outcome.Error(
                (init as? Outcome.Error)?.failure ?: "MCP_INIT_FAILED",
                "initialize failed"
            )
        }
        val params = JSONObject()
            .put("name", name)
            .put("arguments", JSONObject(argumentsJson.ifBlank { "{}" }))
        return when (val result = rpc("tools/call", params)) {
            is Outcome.Success -> {
                val content = result.value.optJSONObject("result")?.optJSONArray("content")
                val text = (0 until (content?.length() ?: 0))
                    .filter { content?.getJSONObject(it)?.optString("type") == "text" }
                    .joinToString("\n") { content!!.getJSONObject(it).optString("text") }
                Outcome.Success(text)
            }
            is Outcome.Error -> Outcome.Error(result.failure, result.diagnosticMessage)
            is Outcome.Degraded -> Outcome.Error("MCP_CALL_DEGRADED", result.diagnosticMessage)
        }
    }

    override suspend fun close() {
        initialized = false
    }
}
