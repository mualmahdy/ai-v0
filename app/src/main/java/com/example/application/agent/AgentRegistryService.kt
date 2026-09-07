package com.example.application.agent

import com.example.application.registry.ComponentRegistry
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.infrastructure.persistence.dao.AgentDefinitionDao
import com.example.infrastructure.persistence.entities.AgentDefinitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * ============================================================================
 * AgentRegistryService — gap-closure P1-08 / P1-09 / P1-10
 * ============================================================================
 *
 * THE canonical, durable agent registry (Room `agent_definitions`):
 *
 *  - P1-08 (one source of truth): the SAME store feeds the UI catalog and
 *    the runtime ComponentRegistry. Previously the UI invented agents
 *    (architect_orchestrator, code_craftsman, security_guardian) the runtime
 *    never registered, while the runtime registered different agents
 *    (agent_general, agent_coder, agent_researcher) the UI never showed —
 *    the agent the user selected was NOT the agent that executed.
 *
 *  - P1-09 (durable lifecycle): definitions, capabilities, prompts and a
 *    monotonic version counter survive process death (configuration
 *    lineage); nothing is invented at startup.
 *
 *  - P1-10 (Agent Builder): Agent Studio's create/edit/delete actions write
 *    here; the saved agent is immediately usable in the Studio and by the
 *    orchestrator (create → configure → run, durably).
 */
class AgentRegistryService(
    private val dao: AgentDefinitionDao
) {

    /** Seeds the canonical default catalog exactly once (first launch). */
    suspend fun ensureSeeded(defaults: List<AgentDefinition>) = withContext(Dispatchers.IO) {
        if (dao.agentCount() > 0) return@withContext
        val now = System.currentTimeMillis()
        defaults.forEach { agent ->
            dao.upsertAgent(toEntity(agent, origin = "PLATFORM", version = 1, now = now))
        }
    }

    /** All durable agents (disabled included — the UI can filter). */
    suspend fun listAgents(): List<AgentDefinition> = withContext(Dispatchers.IO) {
        dao.allAgents().map { it.toDomain() }
    }

    /** Live stream of the durable catalog (auto-updates the UI). */
    fun observeAgents(): Flow<List<AgentDefinition>> =
        dao.allAgentsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getAgent(id: String): AgentDefinition? = withContext(Dispatchers.IO) {
        dao.getAgentById(id)?.toDomain()
    }

    /**
     * Creates or updates an agent definition (P1-10). Bumps the version
     * counter on update (configuration lineage, P1-09). Returns the saved
     * definition with its new version.
     */
    suspend fun saveAgent(
        definition: AgentDefinition,
        origin: String = "PLANNER"
    ): AgentDefinition = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = dao.getAgentById(definition.identity.id.value)
        val entity = toEntity(
            agent = definition,
            origin = existing?.origin ?: origin,
            version = (existing?.version ?: 0) + 1,
            now = now,
            createdAt = existing?.createdAtEpochMs ?: now
        )
        dao.upsertAgent(entity)
        entity.toDomain()
    }

    suspend fun deleteAgent(id: String) = withContext(Dispatchers.IO) {
        dao.deleteAgent(id)
    }

    /**
     * Creates a NEW agent with a generated id (P1-10 builder path).
     * Requires a non-blank name; everything else gets role defaults.
     */
    suspend fun createAgent(
        name: String,
        role: AgentRole,
        description: String,
        systemPrompt: String,
        capabilities: Set<CapabilityType> = setOf(CapabilityType.LLM_GENERATION),
        maxTokens: Int = 30000
    ): AgentDefinition {
        require(name.isNotBlank()) { "AGENT_NAME_REQUIRED: اسم الوكيل مطلوب." }
        val id = "agent_" + UUID.randomUUID().toString().take(10)
        val definition = AgentDefinition(
            identity = AgentIdentity(
                id = AgentId(id),
                name = name.trim(),
                role = role,
                description = description.ifBlank { role.displayName },
                systemPrompt = systemPrompt.ifBlank { role.defaultSystemPrompt }
            ),
            allowedCapabilities = capabilities.ifEmpty { setOf(CapabilityType.LLM_GENERATION) },
            budget = AgentBudget(maxTokens = maxTokens)
        )
        return saveAgent(definition, origin = "PLANNER")
    }

    /**
     * Mirrors the durable catalog into the runtime ComponentRegistry —
     * the single registration path so the executing agent IS the agent the
     * user selected (P1-08). Returns the registered agents.
     */
    suspend fun syncInto(registry: ComponentRegistry): List<AgentDefinition> {
        val agents = listAgents()
        for (agent in agents) {
            if (agent.enabled) registry.registerAgent(agent)
        }
        return agents
    }

    // --- serialization ---

    private fun toEntity(
        agent: AgentDefinition,
        origin: String,
        version: Int,
        now: Long,
        createdAt: Long = now
    ): AgentDefinitionEntity {
        val caps = JSONArray().also { arr -> agent.allowedCapabilities.forEach { arr.put(it.name) } }
        val scope = JSONArray().also { arr -> agent.workspaceScope.forEach { arr.put(it) } }
        return AgentDefinitionEntity(
            id = agent.identity.id.value,
            name = agent.identity.name,
            role = agent.identity.role.name,
            description = agent.identity.description,
            systemPrompt = agent.identity.systemPrompt,
            capabilitiesJson = caps.toString(),
            workspaceScopeJson = scope.toString(),
            maxTokens = agent.budget.maxTokens,
            enabled = agent.enabled,
            version = version,
            origin = origin,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = now
        )
    }

    private fun AgentDefinitionEntity.toDomain(): AgentDefinition {
        val role = runCatching { AgentRole.valueOf(this.role) }.getOrDefault(AgentRole.GENERAL_ASSISTANT)
        val caps = runCatching {
            val arr = JSONArray(capabilitiesJson)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { CapabilityType.valueOf(arr.getString(i)) }.getOrNull()
            }.toSet()
        }.getOrDefault(setOf(CapabilityType.LLM_GENERATION))
        val scope = runCatching {
            val arr = JSONArray(workspaceScopeJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        return AgentDefinition(
            identity = AgentIdentity(
                id = AgentId(id),
                name = name,
                role = role,
                description = description,
                systemPrompt = systemPrompt
            ),
            allowedCapabilities = caps,
            budget = AgentBudget(maxTokens = maxTokens),
            enabled = enabled,
            workspaceScope = scope
        )
    }
}
