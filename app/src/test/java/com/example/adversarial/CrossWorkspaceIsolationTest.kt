package com.example.adversarial

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.session.ConversationSessionService
import com.example.application.workflow.WorkflowLibraryService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.domain.core.session.ChatMode
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.infrastructure.persistence.repository.RoomConversationSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * ADVERSARIAL TEST (defect family 1 & 8) — Cross-Workspace Isolation
 * ============================================================================
 *
 * Verified defect: session and workflow access/mutation operations were
 * ID-ONLY (no workspace authorization) — any caller holding a bare id could
 * read, mutate, or delete another workspace's sessions, turns, workflow
 * definitions and workflow executions.
 *
 * The repaired services are WORKSPACE-AUTHORIZED: an id that belongs to a
 * DIFFERENT workspace is indistinguishable from nonexistent. This test
 * attacks every mutation surface across two live workspaces.
 */
@RunWith(RobolectricTestRunner::class)
class CrossWorkspaceIsolationTest {

    private lateinit var dbFile: File
    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var sessionService: ConversationSessionService
    private lateinit var libraryService: WorkflowLibraryService
    private lateinit var persistenceService: WorkflowPersistenceService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File.createTempFile("xworkspace_", ".db")
        db = Room.databaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repository = RoomConversationSessionRepository(
            database = db,
            sessionDao = db.conversationSessionDao(),
            turnDao = db.conversationTurnDao()
        )
        sessionService = ConversationSessionService(repository = repository, workspaceIdProvider = { "ws_alpha" })
        libraryService = WorkflowLibraryService(
            workflowDefinitionDao = db.workflowDefinitionDao(),
            planSerializer = WorkflowPersistenceService(
                workflowExecutionDao = db.workflowExecutionDao(),
                workflowStepStateDao = db.workflowStepStateDao()
            ),
            workspaceIdProvider = { "ws_alpha" }
        )
        persistenceService = WorkflowPersistenceService(
            workflowExecutionDao = db.workflowExecutionDao(),
            workflowStepStateDao = db.workflowStepStateDao()
        )
    }

    @After
    fun teardown() {
        db.close()
        dbFile.delete()
    }

    private fun plan(id: String) = WorkflowPlan(
        id = WorkflowId(id),
        goal = "goal $id",
        executionMode = ExecutionMode.SEQUENTIAL,
        steps = listOf(
            StepNode(
                id = "s1",
                taskId = TaskId("t_$id"),
                agentRole = AgentRole.PLANNER,
                description = "step one"
            )
        )
    )

    // ------------------------------------------------------------------
    // SESSIONS
    // ------------------------------------------------------------------

    @Test
    fun `cross-workspace session access is indistinguishable from nonexistent`() = runBlocking {
        val alphaSession = sessionService.createSession(mode = ChatMode.QUICK_CHAT)

        // Attacker in ws_beta queries the SAME id — must see NOTHING.
        assertNull(
            "Another workspace's session must be invisible",
            sessionService.getSessionWithTurns(alphaSession.id, workspaceId = "ws_beta")
        )
        assertNull(
            sessionService.getSession(alphaSession.id, workspaceId = "ws_beta")
        )
        // The owning workspace still sees it.
        assertNotNull(sessionService.getSessionWithTurns(alphaSession.id, workspaceId = "ws_alpha"))
    }

    @Test
    fun `cross-workspace turn append writes NOTHING`() = runBlocking {
        val alphaSession = sessionService.createSession(mode = ChatMode.QUICK_CHAT)

        val written = sessionService.appendTurn(
            sessionId = alphaSession.id,
            prompt = "intrusion",
            answer = "intrusion",
            agentName = null,
            agentRole = null,
            modelResourceId = null,
            tokensConsumed = 10,
            durationMs = 5,
            isSuccessful = true,
            eventCount = 1,
            workspaceId = "ws_beta" // attacker's workspace
        )
        assertNull("Cross-workspace append must not write anything", written)

        // No turn, no aggregate drift in the owning workspace.
        val reloaded = sessionService.getSessionWithTurns(alphaSession.id)
        assertEquals(0, reloaded!!.turns.size)
        assertEquals(0, reloaded.session.turnCount)
        assertEquals(0L, reloaded.session.totalTokensConsumed.toLong())
    }

    @Test
    fun `cross-workspace delete rename and model pin are no-ops`() = runBlocking {
        val alphaSession = sessionService.createSession(mode = ChatMode.QUICK_CHAT)
        sessionService.appendTurn(
            sessionId = alphaSession.id,
            prompt = "hello",
            answer = "world",
            agentName = null,
            agentRole = null,
            modelResourceId = null,
            tokensConsumed = 5,
            durationMs = 5,
            isSuccessful = true,
            eventCount = 1
        )

        // Attacker's delete must do NOTHING.
        val deleted = sessionService.deleteSession(alphaSession.id, workspaceId = "ws_beta")
        assertFalse(deleted)
        assertNotNull(sessionService.getSession(alphaSession.id, workspaceId = "ws_alpha"))

        // Attacker's rename must do NOTHING.
        sessionService.renameSession(alphaSession.id, "HACKED", workspaceId = "ws_beta")
        assertEquals(
            "محادثة جديدة",
            sessionService.getSession(alphaSession.id, workspaceId = "ws_alpha")!!.title
        )

        // Attacker's model pin must do NOTHING.
        sessionService.setSessionModel(
            alphaSession.id,
            modelResourceId = "attacker_model",
            modelDisplayName = "attacker",
            workspaceId = "ws_beta"
        )
        assertNull(
            sessionService.getSession(alphaSession.id, workspaceId = "ws_alpha")!!.modelResourceId
        )
    }

    @Test
    fun `session lists never bleed across workspaces`() = runBlocking {
        sessionService.createSession(mode = ChatMode.QUICK_CHAT)
        sessionService.createSession(mode = ChatMode.QUICK_CHAT)
        val betaRepository = RoomConversationSessionRepository(
            database = db,
            sessionDao = db.conversationSessionDao(),
            turnDao = db.conversationTurnDao()
        )
        val betaService = ConversationSessionService(repository = betaRepository, workspaceIdProvider = { "ws_beta" })
        betaService.createSession(mode = ChatMode.QUICK_CHAT)

        assertEquals(2, sessionService.observeSessions("ws_alpha").first().size)
        assertEquals(1, betaService.observeSessions("ws_beta").first().size)
    }

    // ------------------------------------------------------------------
    // WORKFLOW LIBRARY + EXECUTIONS
    // ------------------------------------------------------------------

    @Test
    fun `cross-workflow-definition access is workspace-authorized`() = runBlocking {
        val definitionId = libraryService.saveDefinition(
            existingId = null,
            name = "خطة ألفا",
            plan = plan("wfl_alpha")
        )

        // Attacker in ws_beta cannot load, clone, record-run, or delete it.
        assertNull(libraryService.loadDefinition(definitionId, workspaceId = "ws_beta"))
        assertNull(libraryService.cloneDefinition(definitionId, workspaceId = "ws_beta"))
        libraryService.recordRun(definitionId, workspaceId = "ws_beta")
        assertFalse(libraryService.deleteDefinition(definitionId, workspaceId = "ws_beta"))

        // The attacker's failed record-run did NOT bump the counter.
        assertEquals(0, libraryService.observeLibrary("ws_alpha").first().first().runCount)

        // Run counts only for the OWNER.
        libraryService.recordRun(definitionId, workspaceId = "ws_alpha")
        assertEquals(1, libraryService.observeLibrary("ws_alpha").first().first().runCount)

        // Attacker's delete fails; owner's delete succeeds.
        assertTrue(libraryService.deleteDefinition(definitionId, workspaceId = "ws_alpha"))
        assertNull(libraryService.loadDefinition(definitionId, workspaceId = "ws_alpha"))
    }

    @Test
    fun `cross-workspace workflow execution mutations are no-ops`() = runBlocking {
        val wfId = WorkflowId("wfl_exec_alpha")
        persistenceService.start(wfId, "ws_alpha", plan("wfl_exec_alpha"))

        // Attacker checkpoint/complete from ws_beta must NOT touch the row.
        persistenceService.checkpoint(wfId, 99, workspaceId = "ws_beta")
        persistenceService.complete(wfId, isDegraded = false, workspaceId = "ws_beta")
        val state = persistenceService.byId(wfId)
        assertEquals("RUNNING", state!!.lifecycleState)
        assertEquals(0, state.currentStepIndex)

        // The OWNER's terminal write applies.
        persistenceService.complete(wfId, isDegraded = false, workspaceId = "ws_alpha")
        assertEquals("COMPLETED", persistenceService.byId(wfId)!!.lifecycleState)

        // byId with the attacker's workspace: invisible.
        assertNull(persistenceService.byId(wfId, workspaceId = "ws_beta"))
    }

    @Test
    fun `resumable workflows are workspace-scoped`() = runBlocking {
        persistenceService.start(WorkflowId("wfl_alpha_run"), "ws_alpha", plan("wfl_alpha_run"))
        persistenceService.start(WorkflowId("wfl_beta_run"), "ws_beta", plan("wfl_beta_run"))

        assertEquals(1, persistenceService.resumable("ws_alpha").size)
        assertEquals(1, persistenceService.resumable("ws_beta").size)
        assertEquals(2, persistenceService.resumable(null).size)
    }
}
