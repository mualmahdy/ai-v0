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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Unified Extension Manager governing Skills, Plugins, MCP Servers, and Integrations.
 */
class ExtensionManager(
    private val componentRegistry: ComponentRegistry
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
            ),
            SkillManifest(
                id = "skill_automated_testing",
                name = "أتمتة اختبارات الوحدة والعقود (Contract Test Generator)",
                version = "1.0.0",
                description = "بناء اختبارات الوحدة الشاملة للمنافذ والمحولات البرمجية.",
                category = "TESTING",
                requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
                state = SkillState.AVAILABLE
            )
        )

        // 2. Plugins
        _plugins.value = listOf(
            PluginManifest(
                id = "plugin_git_workspace",
                name = "حزمة Git & Workspace Versioning",
                version = "1.1.0",
                description = "أدوات تتبع التغييرات وإنشاء لقطات الأكواد داخل مساحة العمل المعزولة.",
                declaredTools = listOf("git_status", "git_diff", "git_commit"),
                requiredPermissions = listOf("WORKSPACE_READ", "WORKSPACE_WRITE"),
                state = PluginState.VERIFIED,
                trustLevel = "VERIFIED",
                installedTimestampMs = System.currentTimeMillis()
            ),
            PluginManifest(
                id = "plugin_data_viz_engine",
                name = "حزمة الرسوم البيانية التفاعلية (Data Visualization)",
                version = "1.0.4",
                description = "توليد الرسوم البيانية والمخططات الهندسية من مخرجات الأدوات.",
                declaredTools = listOf("chart_generator"),
                state = PluginState.INSTALLED,
                trustLevel = "SANDBOXED",
                installedTimestampMs = System.currentTimeMillis()
            )
        )

        // 3. MCP Servers
        _mcpServers.value = listOf(
            McpServerDescriptor(
                id = "mcp_filesystem_bridge",
                name = "Local Filesystem MCP Server",
                endpointUri = "http://127.0.0.1:8080/sse",
                transportType = McpTransportType.SSE,
                health = HealthStatus.HEALTHY,
                isEnabled = true,
                exposedTools = listOf(
                    McpDiscoveredTool("read_file", "قراءة محتوى ملف محدد", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
                    McpDiscoveredTool("list_directory", "استعراض محتويات المجلد", "{\"type\":\"object\",\"properties\":{\"dir\":{\"type\":\"string\"}}}")
                ),
                exposedResources = listOf(
                    McpDiscoveredResource("file:///workspace/project.json", "ملف إعدادات المشروع", "application/json")
                )
            ),
            McpServerDescriptor(
                id = "mcp_github_context",
                name = "GitHub Context MCP Server",
                endpointUri = "https://mcp.github.com/v1",
                transportType = McpTransportType.HTTP_STREAM,
                health = HealthStatus.HEALTHY,
                isEnabled = true,
                exposedTools = listOf(
                    McpDiscoveredTool("search_repositories", "بحث المستودعات البرمجية", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                )
            )
        )

        // 4. Integrations
        _integrations.value = listOf(
            IntegrationDescriptor(
                id = "integ_google_drive",
                name = "Google Drive Workspace",
                serviceType = "GOOGLE_DRIVE",
                isConnected = true,
                accountIdentifier = "user.workspace@gmail.com",
                health = HealthStatus.HEALTHY,
                supportedOperations = listOf("READ_FILES", "SYNC_WORKSPACE", "EXPORT_DOCS"),
                requiredScopes = listOf("https://www.googleapis.com/auth/drive.readonly"),
                lastSyncTimestampMs = System.currentTimeMillis() - 120000
            ),
            IntegrationDescriptor(
                id = "integ_github",
                name = "GitHub Enterprise & Cloud",
                serviceType = "GITHUB",
                isConnected = true,
                accountIdentifier = "mualmahdy",
                health = HealthStatus.HEALTHY,
                supportedOperations = listOf("LIST_REPOS", "FETCH_ISSUES", "PULL_REQUESTS"),
                requiredScopes = listOf("repo", "read:user"),
                lastSyncTimestampMs = System.currentTimeMillis() - 360000
            ),
            IntegrationDescriptor(
                id = "integ_dropbox",
                name = "Dropbox Storage",
                serviceType = "DROPBOX",
                isConnected = false,
                health = HealthStatus.UNKNOWN,
                supportedOperations = listOf("READ_FILES", "WRITE_BACKUP")
            )
        )

        // Register MCP tools dynamically in ComponentRegistry
        registerMcpToolsInRegistry()
    }

    private fun registerMcpToolsInRegistry() {
        for (server in _mcpServers.value) {
            if (server.isEnabled) {
                for (mcpTool in server.exposedTools) {
                    val toolAdapter = object : ToolPort {
                        override val declaration: ToolDeclaration = ToolDeclaration(
                            name = "${server.id}__${mcpTool.name}",
                            description = "[MCP: ${server.name}] ${mcpTool.description}"
                        )
                        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                            return Outcome.Success(
                                ToolOutput(
                                    content = "تم استدعاء أداة MCP بنجاح: ${mcpTool.name} عبر خادم ${server.name} بالمدخلات: ${input.arguments}"
                                )
                            )
                        }
                    }
                    componentRegistry.registerTool(toolAdapter)
                }
            }
        }
    }

    fun toggleSkill(skillId: String) {
        _skills.update { list ->
            list.map { s ->
                if (s.id == skillId) {
                    val newState = if (s.state == SkillState.ENABLED) SkillState.DISABLED else SkillState.ENABLED
                    s.copy(state = newState)
                } else s
            }
        }
    }

    fun togglePlugin(pluginId: String) {
        _plugins.update { list ->
            list.map { p ->
                if (p.id == pluginId) {
                    val newState = if (p.state == PluginState.VERIFIED || p.state == PluginState.ENABLED) PluginState.DISABLED else PluginState.ENABLED
                    p.copy(state = newState)
                } else p
            }
        }
    }

    fun toggleMcpServer(serverId: String) {
        _mcpServers.update { list ->
            list.map { s ->
                if (s.id == serverId) {
                    s.copy(isEnabled = !s.isEnabled)
                } else s
            }
        }
        registerMcpToolsInRegistry()
    }

    fun registerNewMcpServer(name: String, endpointUri: String, transport: McpTransportType = McpTransportType.SSE) {
        val newServer = McpServerDescriptor(
            id = "mcp_${System.currentTimeMillis()}",
            name = name,
            endpointUri = endpointUri,
            transportType = transport,
            health = HealthStatus.HEALTHY,
            isEnabled = true,
            exposedTools = listOf(
                McpDiscoveredTool("custom_mcp_query", "أداة مخصصة مستكشفة من خادم $name")
            )
        )
        _mcpServers.update { it + newServer }
        registerMcpToolsInRegistry()
    }
}
