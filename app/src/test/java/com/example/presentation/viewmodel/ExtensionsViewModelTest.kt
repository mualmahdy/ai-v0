package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.extension.ExtensionManager
import com.example.application.registry.ComponentRegistry
import com.example.domain.core.Outcome
import com.example.domain.core.extension.SkillState
import com.example.infrastructure.skills.ExecutableSkill
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.integration.IntegrationGateway
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
 * ExtensionsViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the EXTENSIONS feature ViewModel (ADR-6 slice 7)
 * ============================================================================
 *
 * Drives the REAL ExtensionManager over a REAL in-memory Room database (the
 * same ExtensionConfigDao the production AppContainer wires) with a PORT-level
 * fake executable skill (the skill seam — the same no-service-stubbing
 * contract as the slice-6 suites; the McpClient/IntegrationGateway defaults
 * never open sockets in these paths):
 *
 *  - the four Room-backed collectors reflect the manager's bootstrap truth
 *    reactively (the two verified built-in skills; NO fabricated plugins —
 *    the FIX F-11 honest empty list);
 *  - the honest enable/disable toggles reflect through the state;
 *  - the direct skill execution: Success lands in the feature's own banner,
 *    Error lands in the feature's own error channel (both dismissible);
 *  - the MCP registration lands in the list (FIX F-11: UNKNOWN health, no
 *    fabricated tools) and the toggle reflects;
 *  - the channel dismissal contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExtensionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var manager: ExtensionManager
    private lateinit var viewModel: ExtensionsViewModel

    /** PORT-level skill double: returns a canned outcome per test. */
    private class FakeExecutableSkill(
        override val skillId: String,
        private val result: Outcome<String, String>
    ) : ExecutableSkill {
        override suspend fun execute(parameters: Map<String, Any?>): Outcome<String, String> = result
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = ExtensionManager(
            componentRegistry = ComponentRegistry(),
            mcpClient = McpClient(),
            integrationGateway = IntegrationGateway(),
            extensionConfigDao = db.extensionConfigDao(),
            executableSkills = listOf(
                FakeExecutableSkill(
                    "skill_ok",
                    Outcome.Success("أُنجزت المهارة بنجاح: 3 ملفات.")
                ),
                FakeExecutableSkill(
                    "skill_fails",
                    Outcome.Error(failure = "SKILL_FAILED", diagnosticMessage = "فشل تنفيذ المهارة: معامل غير صالح.")
                )
            ),
            coroutineScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined
            )
        )
        viewModel = ExtensionsViewModel(manager)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the manager hops
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
    // Init collectors — the manager's bootstrap truth, reflected reactively
    // ------------------------------------------------------------------

    @Test
    fun `init collectors reflect the manager bootstrap truth`() {
        awaitUntil { viewModel.state.value.skills.isNotEmpty() }
        // The two VERIFIED built-in skills (FIX F-11: the manifest with no
        // backing executable was removed).
        assertEquals(manager.skills.value.size, viewModel.state.value.skills.size)
        assertEquals(manager.skills.value.map { it.id }, viewModel.state.value.skills.map { it.id })
        assertTrue(viewModel.state.value.skills.all { it.isVerified })

        // FIX F-11: NO fabricated plugins — the honest empty list.
        assertTrue(manager.plugins.value.isEmpty())
        assertEquals(manager.plugins.value, viewModel.state.value.plugins)
        assertEquals(manager.mcpServers.value, viewModel.state.value.mcpServers)
        assertEquals(manager.integrations.value, viewModel.state.value.integrations)
    }

    // ------------------------------------------------------------------
    // Skills — the honest toggle + the direct execution outcomes
    // ------------------------------------------------------------------

    @Test
    fun `toggleSkill reflects through the feature state`() {
        awaitUntil { viewModel.state.value.skills.isNotEmpty() }
        val firstSkill = viewModel.state.value.skills.first()
        assertEquals(SkillState.ENABLED, firstSkill.state)

        viewModel.toggleSkill(firstSkill.id)

        awaitUntil { viewModel.state.value.skills.first { it.id == firstSkill.id }.state == SkillState.DISABLED }
        // The manager's own flow agrees (one source of truth).
        assertEquals(
            SkillState.DISABLED,
            manager.skills.value.first { it.id == firstSkill.id }.state
        )
    }

    @Test
    fun `executeSkillDirectly success lands in the feature's own banner`() {
        viewModel.executeSkillDirectly("skill_ok", mapOf("moduleName" to "test_module"))
        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals("أُنجزت المهارة بنجاح: 3 ملفات.", viewModel.state.value.diagnosticBanner)
        assertNull(viewModel.state.value.errorMessage)
        // Dismissible.
        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
    }

    @Test
    fun `executeSkillDirectly failure lands in the feature's own error channel`() {
        viewModel.executeSkillDirectly("skill_fails", emptyMap())
        awaitUntil { viewModel.state.value.errorMessage != null }
        // The DIAGNOSTIC message is what the user must see (the S-2 family:
        // the machine code lives in the failure slot, the human message in
        // diagnosticMessage).
        assertEquals("فشل تنفيذ المهارة: معامل غير صالح.", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.diagnosticBanner)
        // Dismissible.
        viewModel.clearErrorMessage()
        assertNull(viewModel.state.value.errorMessage)
    }

    // ------------------------------------------------------------------
    // MCP servers — registration + toggle
    // ------------------------------------------------------------------

    @Test
    fun `registerMcpServer lands in the list with UNKNOWN health and no fabricated tools`() {
        viewModel.registerMcpServer("خادم الاختبار", "https://example.com/mcp/sse")

        awaitUntil { viewModel.state.value.mcpServers.any { it.name == "خادم الاختبار" } }
        val registered = viewModel.state.value.mcpServers.first { it.name == "خادم الاختبار" }
        // FIX F-11: honest registration — UNKNOWN health (no fake HEALTHY),
        // NO exposed tools (real tools only after a successful discovery).
        assertEquals(
            com.example.domain.core.provider.HealthStatus.UNKNOWN,
            registered.health
        )
        assertTrue(registered.exposedTools.isEmpty())
        assertTrue(registered.isEnabled)
        assertEquals("https://example.com/mcp/sse", registered.endpointUri)
    }

    @Test
    fun `toggleMcpServer reflects through the feature state`() {
        viewModel.registerMcpServer("خادم التبديل", "https://example.com/mcp/sse")
        awaitUntil { viewModel.state.value.mcpServers.any { it.name == "خادم التبديل" } }
        val serverId = viewModel.state.value.mcpServers.first { it.name == "خادم التبديل" }.id

        viewModel.toggleMcpServer(serverId)

        awaitUntil { viewModel.state.value.mcpServers.first { it.id == serverId }.isEnabled.not() }
        assertEquals(
            false,
            manager.mcpServers.value.first { it.id == serverId }.isEnabled
        )
    }

    // ------------------------------------------------------------------
    // The null-port honesty + channel contract
    // ------------------------------------------------------------------

    @Test
    fun `the honest fallback construction never fabricates state`() {
        // The barest construction: no executable skills, no DAO writes —
        // the collectors still reflect the manager's real bootstrap truth.
        val bare = ExtensionsViewModel(
            ExtensionManager(
                componentRegistry = ComponentRegistry(),
                mcpClient = McpClient(),
                integrationGateway = IntegrationGateway()
            )
        )
        awaitUntil { bare.state.value.skills.isNotEmpty() }
        assertNotNull(bare.state.value)
        assertTrue(bare.state.value.plugins.isEmpty())
    }
}
