package com.example.application.extension

import com.example.application.registry.ComponentRegistry
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.extension.IntegrationDescriptor
import com.example.domain.core.extension.McpDiscoveredResource
import com.example.domain.core.extension.McpDiscoveredTool
import com.example.domain.core.extension.McpServerDescriptor
import com.example.domain.core.extension.McpTransportType
import com.example.domain.core.extension.PluginManifest
import com.example.domain.core.extension.PluginState
import com.example.domain.core.extension.SkillManifest
import com.example.domain.core.extension.SkillState
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.tools.ToolPort
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.persistence.dao.ExtensionConfigDao
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import com.example.infrastructure.skills.ExecutableSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Unified Extension Manager governing Skills, Plugins, MCP Servers, and Integrations.
 * Backed by genuine MCP protocol execution, live integration authentication, and Room persistence.
 */
class ExtensionManager(
    private val componentRegistry: ComponentRegistry,
    private val mcpClient: McpClient = McpClient(),
    private val integrationGateway: IntegrationGateway = IntegrationGateway(),
    private val extensionConfigDao: ExtensionConfigDao? = null,
    private val executableSkills: List<ExecutableSkill> = emptyList(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _skills = MutableStateFlow<List<SkillManifest>>(emptyList())
    val skills: StateFlow<List<SkillManifest>> = _skills.asStateFlow()

    private val _plugins = MutableStateFlow<List<PluginManifest>>(emptyList())
    val plugins: StateFlow<List<PluginManifest>> = _plugins.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerDescriptor>>(emptyList())
    val mcpServers: StateFlow<List<McpServerDescriptor>> = _mcpServers.asStateFlow()

    private val _integrations = MutableStateFlow<List<IntegrationDescriptor>>(emptyList())
    val integrations: StateFlow<List<IntegrationDescriptor>> = _integrations.asStateFlow()

    init {
        bootstrapDefaultExtensions()
        loadPersistedConfigs()
    }

    private fun bootstrapDefaultExtensions() {
        // 1. Skills
        _skills.value = listOf(
            SkillManifest(
                id = "skill_clean_arch_scaffold",
                name = "هيكلة معمارية النظم النظيفة (Clean Architecture Scaffolder)",
                version = "1.2.0",
                description = "توليد ملفات ومخططات النظم المعمارية المقسمة وفق Clean Architecture وPorts/Adapters.",
                category = "ARCHITECTURE",
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.FILE_STORAGE),
                requiredTools = listOf("workspace_fs"),
                state = SkillState.ENABLED,
                isVerified = true,
                installedTimestampMs = System.currentTimeMillis()
            ),
            SkillManifest(
                id = "skill_code_review_security",
                name = "التدقيق الأمني البرمجي (Security & Policy Auditor)",
                version = "2.0.1",
                description = "فحص الشيفرات ضد تسريب المفاتيح، الثغرات الأمنية، وهجمات حقن الأوامر.",
                category = "SECURITY",
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
                requiredTools = listOf("safe_diagnostics"),
                state = SkillState.ENABLED,
                isVerified = true,
                installedTimestampMs = System.currentTimeMillis()
            )
            // FIX F-11: `skill_automated_testing` was a manifest with NO backing
            // executable skill (executing it always failed "غير مسجلة") — removed.
        )

        // 2. Plugins — FIX F-11 (audit c03919d): the two fabricated plugin
        // manifests (git tools and a chart generator that NEVER existed as
        // executable code) are REMOVED. A plugin manifest whose declaredTools
        // have no real implementation is a UI illusion, not an extension.
        _plugins.value = emptyList()

        // 3. MCP Servers — FIX F-11 (audit c03919d): descriptors describe
        // *potential* servers. Their declared tools are NOT registered into the
        // runtime registry until a REAL discovery/handshake succeeds
        // (see registerMcpToolsInRegistry: HEALTHY-only gate).
        _mcpServers.value = listOf(
            McpServerDescriptor(
                id = "mcp_local_bridge",
                name = "Local Standard MCP Bridge",
                endpointUri = "inprocess://local-bridge",
                transportType = McpTransportType.STDIO,
                health = HealthStatus.HEALTHY,
                isEnabled = true,
                exposedTools = listOf(
                    McpDiscoveredTool("workspace_summary", "استعراض ملخص ملفات مساحة العمل الحالية", "{\"type\":\"object\"}"),
                    McpDiscoveredTool("system_diagnostics", "فحص موارد الذاكرة والنظام المحلية", "{\"type\":\"object\"}")
                ),
                exposedResources = listOf(
                    McpDiscoveredResource("workspace://manifest.json", "ملف إعدادات المشروع", "application/json")
                )
            ),
            McpServerDescriptor(
                id = "mcp_filesystem_bridge",
                name = "Local Filesystem MCP Server",
                endpointUri = "http://127.0.0.1:8080/sse",
                transportType = McpTransportType.SSE,
                health = HealthStatus.UNKNOWN,
                isEnabled = true,
                exposedTools = emptyList()
            ),
            McpServerDescriptor(
                id = "mcp_github_context",
                name = "GitHub Context MCP Server",
                endpointUri = "https://mcp.github.com/v1",
                transportType = McpTransportType.HTTP_STREAM,
                health = HealthStatus.UNKNOWN,
                isEnabled = true,
                exposedTools = emptyList()
            )
        )

        // 4. Integrations - Accurately unconfigured until credentials provided
        _integrations.value = listOf(
            IntegrationDescriptor(
                id = "integ_google_drive",
                name = "Google Drive Workspace",
                serviceType = "GOOGLE_DRIVE",
                isConnected = false,
                accountIdentifier = null,
                health = HealthStatus.UNKNOWN,
                supportedOperations = listOf("READ_FILES", "SYNC_WORKSPACE", "EXPORT_DOCS"),
                requiredScopes = listOf("https://www.googleapis.com/auth/drive.readonly")
            ),
            IntegrationDescriptor(
                id = "integ_github",
                name = "GitHub Enterprise & Cloud",
                serviceType = "GITHUB",
                isConnected = false,
                accountIdentifier = null,
                health = HealthStatus.UNKNOWN,
                supportedOperations = listOf("LIST_REPOS", "FETCH_ISSUES", "PULL_REQUESTS"),
                requiredScopes = listOf("repo", "read:user")
            ),
            IntegrationDescriptor(
                id = "integ_dropbox",
                name = "Dropbox Storage",
                serviceType = "DROPBOX",
                isConnected = false,
                accountIdentifier = null,
                health = HealthStatus.UNKNOWN,
                supportedOperations = listOf("READ_FILES", "WRITE_BACKUP")
            )
        )

        registerMcpToolsInRegistry()
        registerSkillsInRegistry()
    }

    private fun loadPersistedConfigs() {
        if (extensionConfigDao == null) return
        coroutineScope.launch {
            try {
                val configs = extensionConfigDao.getConfigsByType("MCP_SERVER")
                if (configs.isNotEmpty()) {
                    val enabledMap = configs.associate { it.id to it.isEnabled }
                    _mcpServers.update { list ->
                        list.map { s ->
                            enabledMap[s.id]?.let { s.copy(isEnabled = it) } ?: s
                        }
                    }
                    registerMcpToolsInRegistry()
                }
            } catch (_: Exception) {
                // Ignore failure and maintain defaults
            }
        }
    }

    /**
     * Registers REAL MCP tools in ComponentRegistry with actual JSON-RPC 2.0 network/in-process invocation.
     *
     * FIX F-11 (audit c03919d): tools are registered ONLY for servers whose
     * health is HEALTHY — i.e. after a successful real MCP handshake/
     * discovery (or the genuinely-local in-process bridge). Previously the
     * pre-declared tools of UNKNOWN-health servers were registered instantly,
     * fabricating capabilities that were never verified to exist.
     */
    private fun registerMcpToolsInRegistry() {
        for (server in _mcpServers.value) {
            if (!server.isEnabled) continue
            if (server.health != HealthStatus.HEALTHY) {
                // Unverified server — its declared tools stay UNREGISTERED
                // until pingAndDiscoverMcpServer() completes a real handshake.
                continue
            }
            for (mcpTool in server.exposedTools) {
                    val inferredCaps = when {
                        mcpTool.name.contains("read") || mcpTool.name.contains("list") -> setOf(CapabilityType.FILE_STORAGE, CapabilityType.FILE_READ, CapabilityType.MCP_INVOCATION, CapabilityType.TOOL_EXECUTION)
                        mcpTool.name.contains("diag") || mcpTool.name.contains("system") -> setOf(CapabilityType.SYSTEM_EXECUTION, CapabilityType.MCP_INVOCATION, CapabilityType.TOOL_EXECUTION)
                        mcpTool.name.contains("search") || mcpTool.name.contains("repo") -> setOf(CapabilityType.SEARCH, CapabilityType.CODE_ANALYSIS, CapabilityType.MCP_INVOCATION, CapabilityType.TOOL_EXECUTION)
                        else -> setOf(CapabilityType.MCP_INVOCATION, CapabilityType.TOOL_EXECUTION)
                    }
                    val toolAdapter = object : ToolPort {
                        override val declaration: ToolDeclaration = ToolDeclaration(
                            name = "${server.id}__${mcpTool.name}",
                            description = "[MCP: ${server.name}] ${mcpTool.description}",
                            providedCapabilities = inferredCaps,
                            networkRequirement = if (server.endpointUri.startsWith("inprocess://")) com.example.domain.core.capability.NetworkRequirement.LOCAL_ONLY else com.example.domain.core.capability.NetworkRequirement.ONLINE_ONLY,
                            locality = if (server.endpointUri.startsWith("inprocess://")) com.example.domain.core.capability.Locality.LOCAL_ON_DEVICE else com.example.domain.core.capability.Locality.REMOTE_CLOUD
                        )
                        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                            return mcpClient.callTool(server, mcpTool.name, input.arguments)
                        }
                    }
                    componentRegistry.registerTool(toolAdapter)
                }
        }
    }

    /**
     * Registers Executable Skills into ComponentRegistry.
     */
    private fun registerSkillsInRegistry() {
        for (skill in executableSkills) {
            val toolAdapter = object : ToolPort {
                override val declaration: ToolDeclaration = ToolDeclaration(
                    name = skill.skillId,
                    description = "تنفيذ مهارة مدمجة: ${skill.skillId}",
                    providedCapabilities = skill.providedCapabilities,
                    networkRequirement = com.example.domain.core.capability.NetworkRequirement.LOCAL_ONLY,
                    locality = com.example.domain.core.capability.Locality.LOCAL_ON_DEVICE
                )
                override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                    return when (val res = skill.execute(input.arguments)) {
                        is Outcome.Success -> Outcome.Success(ToolOutput(content = res.value))
                        is Outcome.Degraded -> Outcome.Degraded(
                            partialValue = res.partialValue?.let { ToolOutput(content = it) },
                            reason = res.reason,
                            diagnosticMessage = res.diagnosticMessage
                        )
                        is Outcome.Error -> Outcome.Error(
                            failure = ToolFailure.InternalExecutionError(res.diagnosticMessage),
                            diagnosticMessage = res.diagnosticMessage
                        )
                    }
                }
            }
            componentRegistry.registerTool(toolAdapter)
        }
    }


    suspend fun executeSkill(skillId: String, parameters: Map<String, Any?>): Outcome<String, String> {
        val skill = executableSkills.firstOrNull { it.skillId == skillId }
            ?: return Outcome.Error("المهارة المطلوبة غير مسجلة: $skillId")
        return skill.execute(parameters)
    }

    /**
     * Authenticates and verifies live integration with the target provider.
     */
    suspend fun verifyAndConnectIntegration(integrationId: String, token: String): Outcome<IntegrationDescriptor, String> {
        val descriptor = _integrations.value.firstOrNull { it.id == integrationId }
            ?: return Outcome.Error("التكامل غير موجود: $integrationId")

        val result = integrationGateway.verifyIntegration(descriptor, token)
        if (result is Outcome.Success) {
            val updated = result.value
            _integrations.update { list ->
                list.map { if (it.id == integrationId) updated else it }
            }
            persistExtensionConfig(updated.id, "INTEGRATION", updated.name, updated.serviceType, updated.isConnected, updated.isConnected, updated.health.name)
        }
        return result
    }

    /**
     * Pings and handshakes with an MCP Server to update its live tools and health status.
     */
    suspend fun pingAndDiscoverMcpServer(serverId: String): Outcome<McpServerDescriptor, String> {
        val server = _mcpServers.value.firstOrNull { it.id == serverId }
            ?: return Outcome.Error("خادم MCP غير موجود: $serverId")

        val result = mcpClient.discoverServer(server)
        if (result is Outcome.Success) {
            val updated = result.value
            _mcpServers.update { list ->
                list.map { if (it.id == serverId) updated else it }
            }
            registerMcpToolsInRegistry()
            persistExtensionConfig(updated.id, "MCP_SERVER", updated.name, updated.endpointUri, updated.isEnabled, updated.health == HealthStatus.HEALTHY, updated.health.name)
        }
        return result
    }

    fun toggleSkill(skillId: String) {
        _skills.update { list ->
            list.map { s ->
                if (s.id == skillId) {
                    val newState = if (s.state == SkillState.ENABLED) SkillState.DISABLED else SkillState.ENABLED
                    val updated = s.copy(state = newState)
                    persistExtensionConfig(updated.id, "SKILL", updated.name, updated.category, newState == SkillState.ENABLED, true, "HEALTHY")
                    updated
                } else s
            }
        }
    }

    fun togglePlugin(pluginId: String) {
        _plugins.update { list ->
            list.map { p ->
                if (p.id == pluginId) {
                    val newState = if (p.state == PluginState.VERIFIED || p.state == PluginState.ENABLED) PluginState.DISABLED else PluginState.ENABLED
                    val updated = p.copy(state = newState)
                    persistExtensionConfig(updated.id, "PLUGIN", updated.name, updated.version, newState == PluginState.ENABLED, true, "HEALTHY")
                    updated
                } else p
            }
        }
    }

    fun toggleMcpServer(serverId: String) {
        _mcpServers.update { list ->
            list.map { s ->
                if (s.id == serverId) {
                    val updated = s.copy(isEnabled = !s.isEnabled)
                    persistExtensionConfig(updated.id, "MCP_SERVER", updated.name, updated.endpointUri, updated.isEnabled, updated.health == HealthStatus.HEALTHY, updated.health.name)
                    updated
                } else s
            }
        }
        registerMcpToolsInRegistry()
    }

    fun registerNewMcpServer(name: String, endpointUri: String, transport: McpTransportType = McpTransportType.SSE) {
        // FIX F-11 (audit c03919d): a newly registered server starts with NO
        // exposed tools and UNKNOWN health. Previously it was instantly given
        // a fake "custom_mcp_query" tool as if it had been discovered. Real
        // tools appear only after a successful ping/discovery handshake.
        val newServer = McpServerDescriptor(
            id = "mcp_${System.currentTimeMillis()}",
            name = name,
            endpointUri = endpointUri,
            transportType = transport,
            health = if (endpointUri.startsWith("inprocess://")) HealthStatus.HEALTHY else HealthStatus.UNKNOWN,
            isEnabled = true,
            exposedTools = emptyList()
        )
        _mcpServers.update { it + newServer }
        registerMcpToolsInRegistry()
        persistExtensionConfig(newServer.id, "MCP_SERVER", newServer.name, newServer.endpointUri, true, newServer.health == HealthStatus.HEALTHY, newServer.health.name)
    }

    private fun persistExtensionConfig(id: String, type: String, name: String, endpoint: String, isEnabled: Boolean, isConnected: Boolean, health: String) {
        if (extensionConfigDao == null) return
        coroutineScope.launch {
            try {
                extensionConfigDao.insertOrUpdateConfig(
                    ExtensionConfigEntity(
                        id = id,
                        type = type,
                        name = name,
                        endpointOrConfig = endpoint,
                        isEnabled = isEnabled,
                        isConnected = isConnected,
                        healthStatus = health,
                        authMetadataJson = null,
                        lastVerifiedEpochMs = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {
                // Ignore non-fatal db save error
            }
        }
    }
}
