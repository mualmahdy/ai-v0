package com.example.domain.core.extension

import com.example.domain.core.provider.HealthStatus

/**
 * Supported MCP (Model Context Protocol) Transport Types.
 */
enum class McpTransportType {
    STDIO,
    SSE,
    HTTP_STREAM,
    WEBSOCKET
}

/**
 * Discovered tool exposed by an MCP Server.
 */
data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}"
)

/**
 * Discovered resource exposed by an MCP Server.
 */
data class McpDiscoveredResource(
    val uri: String,
    val name: String,
    val mimeType: String? = null,
    val description: String? = null
)

/**
 * Representation of an external MCP Server registered in the Capability Registry.
 *
 * FIX DOM-P2-20: Previously defaulted to `health = HEALTHY`, `latencyMs = 45L`, and
 * `lastPingTimestampMs = System.currentTimeMillis()`. A newly registered MCP server
 * with no ping should not claim HEALTHY with 45ms latency and a fresh ping timestamp.
 * Now defaults are honest: `health = UNKNOWN`, `latencyMs = 0L`, `lastPingTimestampMs = null`.
 */
data class McpServerDescriptor(
    val id: String,
    val name: String,
    val endpointUri: String,
    val transportType: McpTransportType = McpTransportType.SSE,
    val health: HealthStatus = HealthStatus.UNKNOWN,
    val isEnabled: Boolean = true,
    val exposedTools: List<McpDiscoveredTool> = emptyList(),
    val exposedResources: List<McpDiscoveredResource> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val latencyMs: Long = 0L,
    val lastPingTimestampMs: Long? = null
)
