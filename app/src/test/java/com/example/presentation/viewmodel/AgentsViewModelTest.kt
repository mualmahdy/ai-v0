package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.agent.AgentRegistryService
import com.example.application.agent.CanonicalAgentCatalog
import com.example.application.registry.ComponentRegistry
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * AgentsViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage for
 * the AGENTS feature ViewModel (ADR-6 slice 7)
 * ============================================================================
 *
 * Drives the REAL durable registry over a REAL in-memory Room database (the
 * same AgentDefinitionDao the production AppContainer wires) and the REAL
 * runtime ComponentRegistry — no service-seam stubbing:
 *
 *  - the P1-08 honest catalog: the synchronous cold-start (instant UX, the
 *    same definitions the durable seed uses) followed by the asynchronous
 *    authoritative refresh from the durable registry;
 *  - the selection seam: the picked agent survives a refresh that still
 *    contains it, and falls back to the first catalog entry when it does
 *    not (a deleted agent can never stay "active");
 *  - the P1-10 Agent Builder loop: create → durable row + runtime
 *    registration (verified by querying the REAL ComponentRegistry back) +
 *    immediate selection + the honest banner; the honest registry-missing
 *    error; the durable delete;
 *  - the feature's own dismissible channels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var registry: AgentRegistryService
    private lateinit var componentRegistry: ComponentRegistry
    private lateinit var viewModel: AgentsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        registry = AgentRegistryService(db.agentDefinitionDao())
        componentRegistry = ComponentRegistry()
        viewModel = AgentsViewModel(
            agentRegistryService = registry,
            componentRegistry = componentRegistry
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the registry hops
     * to Dispatchers.IO internally (Room queries), so outcomes settle
     * asynchronously even under the Unconfined Main dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 25L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(intervalMs)
        }
    }

    // ------------------------------------------------------------------
    // Init — the P1-08 honest catalog
    // ------------------------------------------------------------------

    @Test
    fun `init cold-starts the canonical catalog instantly and selects the first agent`() {
        // The synchronous cold start: no IO, no empty Studio — the SAME
        // definitions the durable seed uses.
        assertEquals(CanonicalAgentCatalog.defaults, viewModel.state.value.availableAgents)
        assertEquals(
            CanonicalAgentCatalog.defaults.first().identity.id,
            viewModel.state.value.activeAgent?.identity?.id
        )
    }

    @Test
    fun `init authoritative refresh replaces the cold-start catalog from the durable registry`() {
        // Seed the DURABLE registry with a custom agent (beyond the
        // defaults) — the async refresh must replace the catalog with the
        // registry's truth (P1-08: one durable source of truth).
        kotlinx.coroutines.runBlocking {
            registry.ensureSeeded(CanonicalAgentCatalog.defaults)
            registry.createAgent(
                name = "وكيل مخصص للاختبار",
                role = AgentRole.CODER,
                description = "وكيل أنشأه الاختبار",
                systemPrompt = "اختبر"
            )
        }
        viewModel = AgentsViewModel(
            agentRegistryService = registry,
            componentRegistry = componentRegistry
        )
        awaitUntil { viewModel.state.value.availableAgents.size == CanonicalAgentCatalog.defaults.size + 1 }
        assertTrue(
            viewModel.state.value.availableAgents.any { it.identity.name == "وكيل مخصص للاختبار" }
        )
    }

    // ------------------------------------------------------------------
    // The selection seam across refreshes
    // ------------------------------------------------------------------

    @Test
    fun `selection survives a refresh that still contains the agent`() {
        // Seed the durable registry with the SAME defaults (ensureSeeded
        // preserves the catalog's agent ids) PLUS one extra agent — the
        // extra row makes the refresh's landing DISTINGUISHABLE from the
        // cold-start catalog (the Providers race-fix precedent: await the
        // specific terminal size, never "isNotEmpty").
        kotlinx.coroutines.runBlocking {
            registry.ensureSeeded(CanonicalAgentCatalog.defaults)
            registry.createAgent(
                name = "وكيل إضافي",
                role = AgentRole.CODER,
                description = "لتمييز هبوط التحديث",
                systemPrompt = ""
            )
        }
        // A FRESH VM constructed AFTER the seeding completed: its init
        // refresh deterministically reads the full seeded+extra registry
        // (the setUp VM's init refresh could otherwise race the seeding
        // loop and observe a PARTIAL list).
        viewModel = AgentsViewModel(
            agentRegistryService = registry,
            componentRegistry = componentRegistry
        )
        awaitUntil {
            viewModel.state.value.availableAgents.size == CanonicalAgentCatalog.defaults.size + 1
        }
        val secondAgent = viewModel.state.value.availableAgents[1]
        viewModel.selectAgent(secondAgent)
        assertEquals(secondAgent.identity.id, viewModel.state.value.activeAgent?.identity?.id)

        viewModel.refreshAgentCatalog()
        awaitUntil {
            viewModel.state.value.availableAgents.size == CanonicalAgentCatalog.defaults.size + 1
        }
        // The refresh replaced the catalog with the durable truth — the
        // picked agent is IN it, so the selection survives (verbatim
        // behavior: stillPresent ?: first).
        assertEquals(secondAgent.identity.id, viewModel.state.value.activeAgent?.identity?.id)
        assertTrue(
            viewModel.state.value.availableAgents.any { it.identity.id == secondAgent.identity.id }
        )
    }

    @Test
    fun `selection falls back to the first agent when the durable catalog no longer contains it`() {
        // The +1 pattern again: the extra row makes the init refresh's
        // landing distinguishable — we WAIT for it before mutating, so no
        // stale writer can race the manual refresh (documented test-side
        // race fix, the ProvidersViewModelTest precedent).
        kotlinx.coroutines.runBlocking {
            registry.ensureSeeded(CanonicalAgentCatalog.defaults)
            registry.createAgent(
                name = "وكيل مؤقت",
                role = AgentRole.CODER,
                description = "سيُحذف",
                systemPrompt = ""
            )
        }
        // A FRESH VM constructed AFTER the seeding: its init refresh reads
        // the seeded+extra registry (the setUp VM's init refresh already
        // consumed an EMPTY read at construction time and never writes
        // again — the documented test-side race fix).
        viewModel = AgentsViewModel(
            agentRegistryService = registry,
            componentRegistry = componentRegistry
        )
        awaitUntil {
            viewModel.state.value.availableAgents.size == CanonicalAgentCatalog.defaults.size + 1
        }
        // Pick the EXTRA agent (by name — never by position).
        val picked = viewModel.state.value.availableAgents.first { it.identity.name == "وكيل مؤقت" }
        viewModel.selectAgent(picked)
        assertEquals(picked.identity.id, viewModel.state.value.activeAgent?.identity?.id)

        // Delete it from the DURABLE registry, then refresh — the active
        // agent cannot stay pointing at a deleted row.
        kotlinx.coroutines.runBlocking { registry.deleteAgent(picked.identity.id.value) }
        viewModel.refreshAgentCatalog()
        awaitUntil {
            viewModel.state.value.availableAgents.none { it.identity.id == picked.identity.id }
        }
        assertEquals(
            viewModel.state.value.availableAgents.first().identity.id,
            viewModel.state.value.activeAgent?.identity?.id
        )
    }

    // ------------------------------------------------------------------
    // P1-10 — the Agent Builder loop
    // ------------------------------------------------------------------

    @Test
    fun `createAgent persists durably registers into the runtime selects it and banners honestly`() {
        viewModel.createAgent(
            name = "وكيل البناء",
            role = AgentRole.PLANNER,
            description = "من الاختبار",
            systemPrompt = "خطط",
            capabilities = setOf(CapabilityType.LLM_GENERATION)
        )
        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        val created = viewModel.state.value.activeAgent
        assertNotNull(created)
        assertEquals("وكيل البناء", created?.identity?.name)

        // Durably persisted: a FRESH registry read over the same DB finds it.
        kotlinx.coroutines.runBlocking {
            assertTrue(registry.listAgents().any { it.identity.id == created!!.identity.id })
        }
        // Runtime-registered (P1-10: create → configure → run): verified by
        // querying the REAL ComponentRegistry back.
        assertTrue(
            componentRegistry.listAgents().any { it.identity.id == created!!.identity.id }
        )
        // Immediately selectable (the catalog contains it).
        assertTrue(
            viewModel.state.value.availableAgents.any { it.identity.id == created!!.identity.id }
        )
    }

    @Test
    fun `createAgent without a durable registry surfaces the honest error`() {
        val bare = AgentsViewModel(
            agentRegistryService = null,
            componentRegistry = componentRegistry
        )
        bare.createAgent(
            name = "وكيل بلا سجل",
            role = AgentRole.CODER,
            description = "",
            systemPrompt = "",
            capabilities = emptySet()
        )
        // The honest failure the user sees — no silent no-op.
        assertEquals("سجل الوكلاء الدائم غير متاح في هذا التكوين.", bare.state.value.errorMessage)
        assertNull(bare.state.value.activeAgent?.takeIf { it.identity.name == "وكيل بلا سجل" })
        // The channel dismisses.
        bare.clearErrorMessage()
        assertNull(bare.state.value.errorMessage)
    }

    // ------------------------------------------------------------------
    // Delete + the dismissible banner channel
    // ------------------------------------------------------------------

    @Test
    fun `deleteAgent removes the agent from the durable catalog`() {
        // The +1 pattern: wait for the init refresh to land (size N+1 is
        // ONLY reachable after the async refresh read the seeded+extra
        // registry), THEN delete — no stale writer can restore the row.
        kotlinx.coroutines.runBlocking {
            registry.ensureSeeded(CanonicalAgentCatalog.defaults)
            registry.createAgent(
                name = "وكيل الحذف",
                role = AgentRole.CODER,
                description = "هدف الحذف",
                systemPrompt = ""
            )
        }
        // A FRESH VM constructed AFTER the seeding: its init refresh reads
        // the seeded+extra registry (the setUp VM's init refresh already
        // consumed an EMPTY read at construction time and never writes
        // again).
        viewModel = AgentsViewModel(
            agentRegistryService = registry,
            componentRegistry = componentRegistry
        )
        awaitUntil {
            viewModel.state.value.availableAgents.size == CanonicalAgentCatalog.defaults.size + 1
        }
        val target = viewModel.state.value.availableAgents
            .first { it.identity.name == "وكيل الحذف" }

        viewModel.deleteAgent(target.identity.id.value)

        awaitUntil {
            viewModel.state.value.availableAgents.none { it.identity.id == target.identity.id }
        }
        // Durably gone: a fresh registry read over the same DB confirms.
        kotlinx.coroutines.runBlocking {
            assertTrue(registry.listAgents().none { it.identity.id == target.identity.id })
        }
    }

    @Test
    fun `the diagnostic banner channel dismisses`() {
        viewModel.createAgent(
            name = "وكيل البانر",
            role = AgentRole.CODER,
            description = "",
            systemPrompt = "",
            capabilities = emptySet()
        )
        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
    }
}
