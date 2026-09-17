package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.extension.ExtensionManager
import com.example.domain.core.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * ExtensionsViewModel — ADR-6 slice 7 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The EXTENSIONS ECOSYSTEM (MCP servers + executable skills + plugins +
 * external integrations) left the MainViewModel per ADR-6 option-ج, with
 * its whole dependency set (extensionManager) — the Room-backed control
 * room for the whole extension surface.
 *
 * STATE: the 4 extension UiState fields (skills / plugins / mcpServers /
 * integrations) moved to this feature-owned [ExtensionsUiState] with its
 * own error + banner channels.
 *
 * BEHAVIOR (moved verbatim): the four Room-backed flow collectors (they
 * lived in MainViewModel.observeSubsystems), the honest enable/disable
 * toggles, the MCP health-check + tool discovery (PING), the MCP server
 * registration, the direct skill execution (a real [Outcome] surfaced in
 * the feature's own channels — Success into the banner, Error into the
 * snackbar channel) and the live-token integration connect.
 */
class ExtensionsViewModel(
    private val extensionManager: ExtensionManager
) : ViewModel() {

    data class ExtensionsUiState(
        /** The executable built-in skills (verified, toggleable). */
        val skills: List<com.example.domain.core.extension.SkillManifest> = emptyList(),
        /** The installed plugins. */
        val plugins: List<com.example.domain.core.extension.PluginManifest> = emptyList(),
        /** The registered MCP servers (health + discovered tools). */
        val mcpServers: List<com.example.domain.core.extension.McpServerDescriptor> = emptyList(),
        /** The external integrations (GitHub / Drive / Notion …). */
        val integrations: List<com.example.domain.core.extension.IntegrationDescriptor> = emptyList(),
        /** The feature's own transient diagnostic channel (local banner). */
        val diagnosticBanner: String? = null,
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(ExtensionsUiState())
    val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

    init {
        // (Moved verbatim from MainViewModel.observeSubsystems — the four
        // extension collectors were its whole remaining body after the
        // slice-3/6 extractions.)
        viewModelScope.launch {
            extensionManager.skills.collect { skills ->
                _state.update { it.copy(skills = skills) }
            }
        }
        viewModelScope.launch {
            extensionManager.plugins.collect { plugins ->
                _state.update { it.copy(plugins = plugins) }
            }
        }
        viewModelScope.launch {
            extensionManager.mcpServers.collect { servers ->
                _state.update { it.copy(mcpServers = servers) }
            }
        }
        viewModelScope.launch {
            extensionManager.integrations.collect { integ ->
                _state.update { it.copy(integrations = integ) }
            }
        }
    }

    // --- Skills ---

    fun toggleSkill(skillId: String) {
        extensionManager.toggleSkill(skillId)
    }

    fun executeSkillDirectly(skillId: String, parameters: Map<String, Any?>) {
        viewModelScope.launch {
            when (val outcome = extensionManager.executeSkill(skillId, parameters)) {
                is Outcome.Success -> {
                    _state.update { it.copy(diagnosticBanner = outcome.value) }
                    // (ADR-6 slice 1) the sandbox listing refresh moved with
                    // the Files feature — FilesViewModel re-lists on every
                    // visit to the Files screen and on project switches, so
                    // files a skill generated appear when the user opens the
                    // explorer.
                }
                is Outcome.Error -> _state.update { it.copy(errorMessage = outcome.diagnosticMessage) }
                else -> Unit
            }
        }
    }

    // --- Plugins ---

    fun togglePlugin(pluginId: String) {
        extensionManager.togglePlugin(pluginId)
    }

    // --- MCP servers ---

    fun toggleMcpServer(serverId: String) {
        extensionManager.toggleMcpServer(serverId)
    }

    fun pingMcpServer(serverId: String) {
        viewModelScope.launch {
            extensionManager.pingAndDiscoverMcpServer(serverId)
        }
    }

    fun registerMcpServer(name: String, endpointUri: String) {
        extensionManager.registerNewMcpServer(name, endpointUri)
    }

    // --- Integrations ---

    fun connectIntegration(integrationId: String, token: String) {
        viewModelScope.launch {
            extensionManager.verifyAndConnectIntegration(integrationId, token)
        }
    }

    /** Clears the feature's honest error channel (after the global snackbar). */
    fun clearErrorMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Dismisses the feature's transient local diagnostic banner. */
    fun dismissDiagnosticBanner() {
        _state.update { it.copy(diagnosticBanner = null) }
    }
}
