package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.application.agent.AgentRegistryService
import com.example.application.registry.ComponentRegistry
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * AgentsViewModel — ADR-6 slice 7 (Design Closure 2026 UI-redesign track)
 * ============================================================================
 *
 * The AGENT CATALOG left the MainViewModel per ADR-6 option-ج, with its
 * whole dependency set (agentRegistryService + the runtime
 * ComponentRegistry registration seam) — the last feature state that
 * still lived in the shared UiState.
 *
 * STATE: the 2 agent UiState fields (availableAgents / activeAgent) moved
 * to this feature-owned [AgentsUiState] with its own error + banner
 * channels.
 *
 * BEHAVIOR (moved verbatim): the P1-08 honest catalog — a synchronous
 * cold-start from [com.example.application.agent.CanonicalAgentCatalog]
 * (instant UX, no IO on the main thread, no empty Studio) followed by the
 * asynchronous authoritative refresh from the durable registry (one
 * durable source of truth; the runtime registry's live list is the
 * fallback when the durable service is not wired), the selection seam
 * (the agent the user picks IS the agent that executes), the P1-10 Agent
 * Builder loop (create → immediately selectable + executable through the
 * runtime registration) and the durable delete.
 *
 * CONSUMERS: the Studio agent picker + builder + delete confirmation, the
 * Tasks builder's step-agent assignment, and the Explorer's agents row.
 */
class AgentsViewModel(
    /**
     * GAP-CLOSURE P1-08/P1-10: the canonical durable agent registry — the
     * SAME authority the runtime ComponentRegistry syncs from. Nullable
     * for source compatibility with existing call sites (the honest
     * fallback keeps the cold-start catalog).
     */
    private val agentRegistryService: AgentRegistryService?,
    private val componentRegistry: ComponentRegistry
) : ViewModel() {

    data class AgentsUiState(
        /** The selectable agent catalog (durable registry authority). */
        val availableAgents: List<AgentDefinition> = emptyList(),
        /** The agent the user selected (the one the Studio executes with). */
        val activeAgent: AgentDefinition? = null,
        /** The feature's own transient diagnostic channel (local banner). */
        val diagnosticBanner: String? = null,
        /** The feature's own honest error channel (global snackbar). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    init {
        // (Moved verbatim from MainViewModel.initializeAgents — the
        // catalog's cold start + authoritative refresh.)
        //
        // Synchronous cold-start catalog: the SAME definitions the durable
        // seed uses (no IO on the main thread, instant UX, no empty Studio).
        val initial = com.example.application.agent.CanonicalAgentCatalog.defaults
        _state.update {
            it.copy(
                availableAgents = initial,
                activeAgent = initial.firstOrNull()
            )
        }
        // Asynchronous authoritative refresh: once the durable registry is
        // loaded (first launch bootstrap or user-created agents), it
        // replaces the cold-start catalog (P1-08: one durable source of
        // truth).
        agentRegistryService?.let { registry ->
            viewModelScope.launch {
                runCatching {
                    val agents = registry.listAgents().ifEmpty { componentRegistry.listAgents() }
                    if (agents.isNotEmpty()) {
                        _state.update { state ->
                            val stillPresent = state.activeAgent?.takeIf { a ->
                                agents.any { it.identity.id == a.identity.id }
                            }
                            state.copy(
                                availableAgents = agents,
                                activeAgent = stillPresent ?: agents.firstOrNull()
                            )
                        }
                    }
                }
            }
        }
    }

    /** Refreshes the agent catalog from the durable registry. */
    fun refreshAgentCatalog() {
        agentRegistryService?.let { registry ->
            viewModelScope.launch {
                runCatching {
                    val agents = registry.listAgents()
                    _state.update { state ->
                        state.copy(
                            availableAgents = agents,
                            activeAgent = state.activeAgent?.takeIf { a ->
                                agents.any { it.identity.id == a.identity.id }
                            } ?: agents.firstOrNull()
                        )
                    }
                }
            }
        }
    }

    /**
     * GAP-CLOSURE P1-10 (Agent Builder): creates a NEW agent through the
     * canonical durable registry and makes it immediately selectable +
     * executable (registered into the runtime ComponentRegistry).
     */
    fun createAgent(
        name: String,
        role: AgentRole,
        description: String,
        systemPrompt: String,
        capabilities: Set<CapabilityType>
    ) {
        val registry = agentRegistryService ?: run {
            _state.update { it.copy(errorMessage = "سجل الوكلاء الدائم غير متاح في هذا التكوين.") }
            return
        }
        viewModelScope.launch {
            runCatching {
                val created = registry.createAgent(
                    name = name,
                    role = role,
                    description = description,
                    systemPrompt = systemPrompt,
                    capabilities = capabilities
                )
                // Immediately executable (P1-10: create → configure → run).
                componentRegistry.registerAgent(created)
                created
            }.onSuccess { created ->
                _state.update { state ->
                    state.copy(
                        availableAgents = state.availableAgents + created,
                        activeAgent = created,
                        diagnosticBanner = "تم إنشاء الوكيل «${created.identity.name}» وحفظه في السجل الدائم — جاهز للتنفيذ."
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(errorMessage = "تعذر إنشاء الوكيل: ${e.localizedMessage}") }
            }
        }
    }

    /** Deletes an agent from the durable catalog (P1-10 builder loop). */
    fun deleteAgent(agentId: String) {
        val registry = agentRegistryService ?: return
        viewModelScope.launch {
            runCatching {
                registry.deleteAgent(agentId)
                refreshAgentCatalog()
            }
        }
    }

    /** The selection seam — the picked agent IS the executed agent. */
    fun selectAgent(agent: AgentDefinition) {
        _state.update { it.copy(activeAgent = agent) }
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
