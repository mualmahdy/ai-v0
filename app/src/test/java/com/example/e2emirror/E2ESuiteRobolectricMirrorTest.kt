package com.example.e2emirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * E2E FULL-SUITE ROBOLECTRIC MIRROR (run-16 diagnostic harness)
 * ============================================================================
 *
 * e2e-device.yml run #16 (commit 965fcd4, the Phase-7 singleton fix) STILL
 * failed at the same step with the same shape: 4m36s job (≈ the 4m35s of
 * runs 1–15), green results-artifact upload, 20.4 KB result XML (vs 19.6 KB
 * before) — i.e. the suite BUILT, the emulator BOOTED, the tests RAN, and at
 * least one still FAILED. Phase 7's mirror covered only the
 * durableSession_survivesDatabaseReopen sequence; this harness mirrors the
 * ENTIRE DurableWorkspaceE2ETest suite the way the emulator executes it:
 *
 *   - ONE process (one Robolectric application),
 *   - ONE durable database file shared across all four test bodies,
 *   - the singleton left OPEN between bodies (only test 2 closes/resets it),
 *   - bodies executed in declaration order AND in alternate orders (the
 *     device runner's method order is deterministic but hash-based).
 *
 * If a body fails here, that is the run-16 root cause candidate. If all
 * bodies pass in all orders, the failure is device/emulator-specific and the
 * artifact XML is required for the verdict (owner logs).
 */
@RunWith(RobolectricTestRunner::class)
class E2ESuiteRobolectricMirrorTest {

    private val productionDbName = "agent_orchestrator_platform.db"
    private lateinit var context: Context

    @Before
    fun freshEmulator() {
        context = ApplicationProvider.getApplicationContext()
        // A fresh emulator has no prior process state and no prior file.
        AppDatabase.resetInstanceForProcessDeath()
        context.deleteDatabase(productionDbName)
    }

    @After
    fun cleanProcess() {
        AppDatabase.resetInstanceForProcessDeath()
        context.deleteDatabase(productionDbName)
    }

    // ---- verbatim mirrors of DurableWorkspaceE2ETest bodies ----

    private fun db(): AppDatabase = AppDatabase.getInstance(context)

    private fun body1_launch() {
        val database = db()
        // Mirrors the FIXED e2e assertion (run-16 root cause): both sides
        // widened to long — SupportSQLiteDatabase.getVersion() is `int`.
        assertEquals(
            AppDatabase.SCHEMA_VERSION.toLong(),
            database.openHelper.readableDatabase.version.toLong()
        )
        assertNotNull(database.conversationSessionDao())
        assertNotNull(database.conversationTurnDao())
        assertNotNull(database.workflowDefinitionDao())
        assertNotNull(database.agentDefinitionDao())
    }

    private fun body2_sessionSurvivesReopen() = runBlocking {
        val first = AppDatabase.getInstance(context)
        val service = com.example.application.session.ConversationSessionService(
            repository = com.example.infrastructure.persistence.repository.RoomConversationSessionRepository(
                database = first,
                sessionDao = first.conversationSessionDao(),
                turnDao = first.conversationTurnDao()
            ),
            workspaceIdProvider = { "ws_e2e" }
        )
        val session = service.createSession(mode = com.example.domain.core.session.ChatMode.QUICK_CHAT)
        service.appendTurn(
            sessionId = session.id,
            prompt = "اختبار E2E",
            answer = "استجابة",
            agentName = "المحادثة السريعة",
            agentRole = "مساعد عام",
            modelResourceId = null,
            tokensConsumed = 12,
            durationMs = 40,
            isSuccessful = true,
            eventCount = 1
        )
        first.close()
        AppDatabase.resetInstanceForProcessDeath()
        val reopened = AppDatabase.getInstance(context)
        val loaded = com.example.infrastructure.persistence.repository
            .RoomConversationSessionRepository(
                database = reopened,
                sessionDao = reopened.conversationSessionDao(),
                turnDao = reopened.conversationTurnDao()
            )
            .getSessionWithTurnsForWorkspace(session.id, "ws_e2e")
        assertNotNull("الجلسة يجب أن تنجو من إعادة الفتح", loaded)
        assertEquals(1, loaded!!.turns.size)
        assertEquals("اختبار E2E", loaded.turns.first().prompt)
    }

    private fun body3_workflowRoundTrip() = runBlocking {
        val database = db()
        val persistence = com.example.application.workflow.WorkflowPersistenceService(
            workflowExecutionDao = database.workflowExecutionDao(),
            workflowStepStateDao = database.workflowStepStateDao()
        )
        val library = com.example.application.workflow.WorkflowLibraryService(
            workflowDefinitionDao = database.workflowDefinitionDao(),
            planSerializer = persistence,
            workspaceIdProvider = { "ws_e2e" }
        )
        val plan = com.example.domain.core.workflow.WorkflowPlan(
            id = com.example.domain.core.workflow.WorkflowId("wf_e2e"),
            goal = "E2E",
            executionMode = com.example.domain.core.workflow.ExecutionMode.SEQUENTIAL,
            steps = listOf(
                com.example.domain.core.workflow.StepNode(
                    id = "e2e_1",
                    taskId = com.example.domain.core.task.TaskId("t_e2e_1"),
                    agentRole = com.example.domain.core.agent.AgentRole.PLANNER,
                    description = "خطوة",
                    dependencies = emptySet(),
                    assignedAgentId = "architect_orchestrator"
                )
            )
        )
        val id = library.saveDefinition(existingId = null, name = "E2E خطة", plan = plan)
        val restored = library.loadDefinition(id)
        assertNotNull(restored)
        assertEquals(1, restored!!.steps.size)
        assertEquals("architect_orchestrator", restored.steps.first().assignedAgentId)
    }

    private fun body4_agentsSeeded() = runBlocking {
        val database = db()
        val registry = com.example.application.agent.AgentRegistryService(database.agentDefinitionDao())
        registry.ensureSeeded(com.example.application.agent.CanonicalAgentCatalog.defaults)
        val agents = registry.listAgents()
        assertTrue(agents.any { it.identity.id.value == "agent_quick_chat" })
        val saved = registry.saveAgent(
            agents.first().copy(goals = listOf(com.example.domain.core.agent.AgentGoal("هدف E2E", 2)))
        )
        assertTrue(saved.goals.any { it.description == "هدف E2E" })
    }

    // ---- suite executions ----

    @Test
    fun suite_declarationOrder() {
        body1_launch()
        body2_sessionSurvivesReopen()
        body3_workflowRoundTrip()
        body4_agentsSeeded()
    }

    @Test
    fun suite_reopenFirst_thenOthers() {
        body2_sessionSurvivesReopen()
        body1_launch()
        body3_workflowRoundTrip()
        body4_agentsSeeded()
    }

    @Test
    fun suite_isolatedBodies_sharedFile() {
        // The device runner may interleave: each body still sees the shared
        // durable file + whatever singleton state earlier bodies left.
        body1_launch()
        body3_workflowRoundTrip()
        body4_agentsSeeded()
        body2_sessionSurvivesReopen()
    }
}
