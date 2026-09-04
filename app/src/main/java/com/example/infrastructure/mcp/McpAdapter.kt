package com.example.infrastructure.mcp

import com.example.domain.core.Outcome
import com.example.domain.core.extension.McpDiscoveredTool
import com.example.domain.core.extension.McpTransportType
import com.example.domain.core.provider.ServiceConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
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

class McpAdapter(
    private val serviceId: String,
    private val config: ServiceConfiguration,
    private val transportType: McpTransportType = McpTransportType.SSE,
    private val mcpClient: McpClient = McpClient(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
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

                val request = Request.Builder()
                    .url(config.endpointUrl)
                    .post(envelope.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Accept", "application/json, text/event-stream")
                    .build()

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
                withContext(Dispatchers.IO) {
                    runCatching {
                        client.newCall(
                            Request.Builder()
                                .url(config.endpointUrl)
                                .post(
                                    JSONObject()
                                        .put("jsonrpc", "2.0")
                                        .put("method", "notifications/initialized")
                                        .toString()
                                        .toRequestBody("application/json".toMediaType())
                                )
                                .build()
                        ).execute().close()
                    }
                }
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
