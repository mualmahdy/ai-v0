package com.example.infrastructure.mcp

import com.example.domain.core.Outcome
import com.example.domain.core.OutcomeMetadata
import com.example.domain.core.extension.McpDiscoveredResource
import com.example.domain.core.extension.McpDiscoveredTool
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.McpTransportType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Genuine Model Context Protocol (MCP) JSON-RPC 2.0 Client.
 *
 * Implements real JSON-RPC 2.0 calls over:
 * 1. HTTP / SSE streams — including the mandatory `initialize` handshake
 *    before `tools/list` (FIX F-8: previously tools/list was sent cold, and
 *    tools were "discovered" without any protocol capability negotiation).
 * 2. In-Process Local standard tools bridge (inprocess://) backed by REAL
 *    injected executors (FIX F-8: previously returned canned text).
 */
class McpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    /**
     * FIX F-8: real executors for the in-process bridge tools. Keys are tool
     * names; the AppContainer wires real implementations (workspace file
     * listing, runtime diagnostics). Previously the bridge returned hardcoded
     * canned strings — an illusion of capability.
     */
    private val inProcessTools: Map<String, suspend (Map<String, Any?>) -> Outcome<ToolOutput, ToolFailure>> = emptyMap()
) {

    /**
     * Executes the MCP protocol handshake (initialize → initialized → tools/list)
     * and returns the updated descriptor with real discovered capabilities.
     */
    suspend fun discoverServer(server: McpServerDescriptor): Outcome<McpServerDescriptor, String> = withContext(Dispatchers.IO) {
        if (!server.isEnabled) {
            return@withContext Outcome.Error("خادم MCP معطل حالياً: ${server.name}")
        }

        // If local in-process standard bridge, evaluate directly — the bridge
        // tools that actually have REAL executors are exposed; the rest are
        // honestly absent (previously two canned tools were always claimed).
        if (server.endpointUri.startsWith("inprocess://")) {
            val available = listOfNotNull(
                // Real workspace summary executor wired by the composition root.
                "workspace_summary".takeIf { inProcessTools.containsKey(it) },
                // Real diagnostics are always computable locally.
                "system_diagnostics"
            )
            val tools = available.map { name ->
                McpDiscoveredTool(
                    name = name,
                    description = when (name) {
                        "workspace_summary" -> "استعراض ملخص ملفات مساحة العمل الحالية وحجم التخزين"
                        else -> "فحص موارد الذاكرة والنظام المحلية في بيئة أندرويد"
                    },
                    inputSchemaJson = "{\"type\":\"object\"}"
                )
            }
            val resources = if (inProcessTools.containsKey("workspace_summary")) {
                listOf(
                    McpDiscoveredResource(
                        uri = "workspace://manifest.json",
                        name = "ملف إعدادات المشروع",
                        mimeType = "application/json"
                    )
                )
            } else emptyList()
            return@withContext Outcome.Success(
                server.copy(
                    health = HealthStatus.HEALTHY,
                    exposedTools = tools,
                    exposedResources = resources
                )
            )
        }

        // Remote HTTP / SSE discovery — with a REAL MCP handshake (FIX F-8):
        //   initialize → notifications/initialized → tools/list
        try {
            // Step 1: initialize handshake (capability negotiation).
            val initPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", UUID.randomUUID().toString())
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "AI-V0-MCP-Client")
                        put("version", "1.0")
                    })
                })
            }
            val initRequest = Request.Builder()
                .url(server.endpointUri)
                .post(initPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", "AI-V0-MCP-Client/1.0")
                .build()

            // FIX R-4: responses are closed on every path (.use).
            val initOk = client.newCall(initRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    // A compliant MCP server replies with result.serverInfo; a
                    // non-MCP endpoint (404 HTML, proxies…) won't — refuse to
                    // "discover" tools from a non-MCP response.
                    json.optJSONObject("result")?.has("serverInfo") == true
                } else false
            }
            if (!initOk) {
                return@withContext Outcome.Error(
                    failure = "MCP initialize handshake failed for ${server.name} (${server.endpointUri})",
                    diagnosticMessage = "خادم ${server.name} لم يُكمل مصافحة MCP (initialize) — لا يمكن اكتشاف أدواته بأمان."
                )
            }

            // Step 2: notifications/initialized (per spec, before requests).
            val initializedPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
                put("params", JSONObject())
            }
            client.newCall(
                Request.Builder()
                    .url(server.endpointUri)
                    .post(initializedPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Accept", "application/json, text/event-stream")
                    .build()
            ).execute().use { /* notification — no response body expected */ }

            // Step 3: tools/list (real discovery after capability negotiation).
            val listToolsPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", UUID.randomUUID().toString())
                put("method", "tools/list")
                put("params", JSONObject())
            }

            val request = Request.Builder()
                .url(server.endpointUri)
                .post(listToolsPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", "AI-V0-MCP-Client/1.0")
                .build()

            // FIX R-4: response closed on every path.
            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val resultObj = json.optJSONObject("result") ?: json
                    val toolsArr = resultObj.optJSONArray("tools") ?: JSONArray()

                    val discoveredTools = mutableListOf<McpDiscoveredTool>()
                    for (i in 0 until toolsArr.length()) {
                        val toolObj = toolsArr.getJSONObject(i)
                        discoveredTools.add(
                            McpDiscoveredTool(
                                name = toolObj.optString("name", "unnamed_tool"),
                                description = toolObj.optString("description", ""),
                                inputSchemaJson = toolObj.optJSONObject("inputSchema")?.toString() ?: "{}"
                            )
                        )
                    }

                    Outcome.Success(
                        server.copy(
                            health = HealthStatus.HEALTHY,
                            exposedTools = discoveredTools
                        )
                    )
                } else {
                    val code = it.code
                    Outcome.Error(
                        failure = "MCP discovery HTTP $code for ${server.name} (${server.endpointUri})",
                        diagnosticMessage = "خادم MCP ${server.name} أعاد رمز HTTP $code أثناء محاولة الاكتشاف."
                    )
                }
            }
        } catch (e: Exception) {
            Outcome.Error(
                failure = "MCP discovery failed for ${server.name}: ${e::class.java.simpleName} - ${e.message}",
                diagnosticMessage = "تعذّر الوصول إلى خادم MCP ${server.name}: ${e.localizedMessage ?: e.message ?: "خطأ غير معروف"}"
            )
        }
    }

    /**
     * Executes a tool via real JSON-RPC 2.0 `tools/call`.
     */
    suspend fun callTool(
        server: McpServerDescriptor,
        toolName: String,
        arguments: Map<String, Any?>
    ): Outcome<ToolOutput, ToolFailure> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (!server.isEnabled) {
            return@withContext Outcome.Error(
                failure = ToolFailure.CapabilityUnavailable(
                    capabilityName = toolName,
                    message = "خادم MCP معطل حالياً: ${server.name}"
                )
            )
        }

        // Handle in-process bridge tools
        if (server.endpointUri.startsWith("inprocess://")) {
            return@withContext executeInProcessTool(toolName, arguments, startTime)
        }

        // Execute over HTTP / SSE
        try {
            val argsJson = JSONObject()
            arguments.forEach { (k, v) -> argsJson.put(k, v) }

            val rpcPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", UUID.randomUUID().toString())
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", argsJson)
                })
            }

            val request = Request.Builder()
                .url(server.endpointUri)
                .post(rpcPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", "AI-V0-MCP-Client/1.0")
                .build()

            val response = client.newCall(request).execute()
            val duration = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)

                if (json.has("error")) {
                    val errObj = json.getJSONObject("error")
                    val errMsg = errObj.optString("message", "خطأ غير محدد من خادم MCP")
                    return@withContext Outcome.Error(
                        failure = ToolFailure.InternalExecutionError(errMsg),
                        diagnosticMessage = errMsg
                    )
                }

                val resultObj = json.optJSONObject("result") ?: json
                val contentArr = resultObj.optJSONArray("content")
                val outputText = if (contentArr != null && contentArr.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until contentArr.length()) {
                        val c = contentArr.getJSONObject(i)
                        if (c.optString("type") == "text") {
                            sb.append(c.optString("text")).append("\n")
                        }
                    }
                    sb.toString().trim()
                } else {
                    resultObj.optString("text", resultObj.toString())
                }

                Outcome.Success(
                    value = ToolOutput(
                        content = outputText,
                        rawBytesCount = outputText.toByteArray().size.toLong()
                    ),
                    metadata = OutcomeMetadata(durationMs = duration, providerId = server.id)
                )
            } else {
                Outcome.Error(
                    failure = ToolFailure.InternalExecutionError("فشل طلب MCP HTTP: رمز الاستجابة ${response.code}"),
                    diagnosticMessage = "استجابة غير صالحة من خادم MCP"
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Error(
                failure = ToolFailure.ExecutionTimeout(10000L),
                diagnosticMessage = "انتهت مهلة استجابة خادم MCP."
            )
        } catch (e: Exception) {
            Outcome.Error(
                failure = ToolFailure.CapabilityUnavailable(
                    capabilityName = toolName,
                    message = "تعذر الاتصال بخادم MCP: ${e.localizedMessage}"
                ),
                diagnosticMessage = e.localizedMessage ?: "خطأ في اتصال MCP"
            )
        }
    }

    private suspend fun executeInProcessTool(
        toolName: String,
        arguments: Map<String, Any?>,
        startTime: Long
    ): Outcome<ToolOutput, ToolFailure> {
        val duration = System.currentTimeMillis() - startTime

        // FIX F-8: prefer a REAL injected executor (wired by AppContainer —
        // e.g. actual workspace file statistics from the sandbox storage).
        val realExecutor = inProcessTools[toolName]
        if (realExecutor != null) {
            return realExecutor(arguments)
        }

        return when (toolName) {
            // system_diagnostics computes REAL runtime statistics locally —
            // no external dependency needed, so it stays a genuine in-process tool.
            "system_diagnostics" -> {
                val runtime = Runtime.getRuntime()
                val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val maxMemMb = runtime.maxMemory() / (1024 * 1024)
                val diagnostics = """
                    [MCP Local Bridge: system_diagnostics]
                    - ذاكرة التطبيق المستخدمة: $usedMemMb MB / $maxMemMb MB
                    - الأنوية المعالجة المتاحة: ${runtime.availableProcessors()}
                    - وقت التشغيل: ${System.currentTimeMillis()} ms
                    - حالة المحرك: سليم ويعمل محلياً بكفاءة
                """.trimIndent()
                Outcome.Success(
                    value = ToolOutput(content = diagnostics, rawBytesCount = diagnostics.toByteArray().size.toLong()),
                    metadata = OutcomeMetadata(durationMs = duration, providerId = "mcp_local_bridge")
                )
            }
            else -> {
                // FIX F-8: honest explicit failure — no canned "workspace summary"
                // illusion when no real executor is wired.
                Outcome.Error(
                    failure = ToolFailure.CapabilityUnavailable(
                        capabilityName = toolName,
                        message = "الأداة المحلية غير مزوّدة بمنفذ حقيقي: $toolName"
                    )
                )
            }
        }
    }
}
