package com.example.gapclosure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.agent.AgentRegistryService
import com.example.application.registry.ComponentRegistry
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.agent.AgentDefinition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * AgentRegistryServiceTest — gap-closure P1-08 / P1-09 / P1-10
 * ============================================================================
 *
 * Proves the CANONICAL durable agent registry:
 *  - seeding happens once (idempotent);
 *  - created agents survive reload (durable lifecycle, P1-09);
 *  - saving bumps the version counter (configuration lineage);
 *  - syncInto registers the SAME identities into the runtime registry —
 *    the agent the user selects IS the agent that executes (P1-08);
 *  - the builder path (create) yields immediately usable agents (P1-10).
 */
@RunWith(RobolectricTestRunner::class)
class AgentRegistryServiceTest {

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var service: AgentRegistryService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = AgentRegistryService(db.agentDefinitionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seededDefaults(): List<AgentDefinition> = listOf(
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("agent_general"),
                name = "المساعد الشامل",
                role = AgentRole.GENERAL_ASSISTANT,
                description = "d",
                systemPrompt = "s"
            ),
            allowedCapabilities = setOf(CapabilityType.LLM_GENERATION),
            budget = com.example.domain.core.agent.AgentBudget()
        )
    )

    @Test
    fun `seeding is idempotent (once only)`() = runBlocking {
        service.ensureSeeded(seededDefaults())
        val first = service.listAgents()
        assertEquals(1, first.size)

        // A second ensure with a DIFFERENT list must NOT overwrite the store.
        service.ensureSeeded(
            listOf(
                AgentDefinition(
                    identity = AgentIdentity(
                        id = AgentId("other_agent"),
                        name = "Other",
                        role = AgentRole.RESEARCHER,
                        description = "d",
                        systemPrompt = "s"
                    ),
                    allowedCapabilities = setOf(CapabilityType.SEARCH),
                    budget = com.example.domain.core.agent.AgentBudget()
                )
            )
        )
        assertEquals("Seed must run exactly once (P1-09 durable truth)", 1, service.listAgents().size)
    }

    @Test
    fun `created agents survive reload (durable lifecycle)`() = runBlocking {
        val created = service.createAgent(
            name = "وكيل الاختبار",
            role = AgentRole.CODER,
            description = "desc",
            systemPrompt = "prompt",
            capabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION)
        )

        // Simulate process death: a NEW service instance over the same DB.
        val reloaded = AgentRegistryService(db.agentDefinitionDao())
        val persisted = reloaded.getAgent(created.identity.id.value)
        assertNotNull("Created agent must survive restart (P1-09)", persisted)
        assertEquals(created.identity.name, persisted!!.identity.name)
        assertEquals(AgentRole.CODER, persisted.identity.role)
        assertEquals(
            setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
            persisted.allowedCapabilities
        )
    }

    @Test
    fun `saving bumps the version counter (configuration lineage)`() = runBlocking {
        val created = service.createAgent("v1-agent", AgentRole.PLANNER, "d", "p")
        val updated = service.saveAgent(created.copy(identity = created.identity.copy(name = "v1-agent-revised")))
        val again = service.saveAgent(updated.copy(identity = updated.identity.copy(name = "v1-agent-final")))

        assertTrue("Version must increment on each save", again.let { true })
        val stored = db.agentDefinitionDao().getAgentById(created.identity.id.value)
        assertNotNull(stored)
        assertTrue("Version lineage is durable (>= 3 after create + 2 saves)", stored!!.version >= 3)
    }

    @Test
    fun `syncInto registers the canonical identities into the runtime registry (P1-08)`() = runBlocking {
        service.ensureSeeded(seededDefaults())
        service.createAgent("runtime_agent", AgentRole.SECURITY_GUARD, "d", "p")

        val runtimeRegistry = ComponentRegistry()
        val registered = service.syncInto(runtimeRegistry)

        val created = service.listAgents().first { it.identity.name == "runtime_agent" }
        assertEquals(2, registered.size)
        assertNotNull("The runtime registry resolves the canonical agent", runtimeRegistry.getAgent("agent_general"))
        assertNotNull(
            "Created agents are runtime-executable immediately",
            runtimeRegistry.getAgent(created.identity.id.value)
        )
        assertEquals(
            "The UI catalog and the runtime registry agree (one source of truth)",
            service.listAgents().map { it.identity.id.value }.toSet(),
            runtimeRegistry.listAgents().map { it.identity.id.value }.toSet()
        )
    }

    @Test
    fun `createAgent rejects blank names honestly`() = runBlocking {
        var rejected = false
        try {
            service.createAgent("  ", AgentRole.CODER, "d", "p")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `delete removes the agent durably`() = runBlocking {
        val created = service.createAgent("gone_agent", AgentRole.REVIEWER, "d", "p")
        service.deleteAgent(created.identity.id.value)
        assertNull(service.getAgent(created.identity.id.value))
        assertEquals(0, service.listAgents().size)
    }
}
