package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.workflow.WorkflowLibraryService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.task.TaskId
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import kotlinx.coroutines.flow.first
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
import java.io.File

/**
 * ============================================================================
 * REPORT GAP-CLOSURE — WorkflowLibraryDurabilityTest
 * ============================================================================
 *
 * Report verdict: "workflow library/history NOT FIXED — durable EXECUTION
 * exists, but the USER-AUTHORED workflow definition is not a durable,
 * re-editable asset (no Create → Save → List → Edit → Resume → Clone →
 * Version path)."
 *
 * This test pins the restored workflow library:
 *  1. Save → the definition appears in the workspace library (versioned).
 *  2. PROCESS DEATH: reopening the database preserves the definition.
 *  3. Load → the FULL plan round-trips (steps, dependencies, canonical
 *     agent bindings — schema v3).
 *  4. Re-save bumps the version (edit lineage).
 *  5. Clone produces an independent copy; run history is recorded.
 *  6. The library is workspace-scoped (no cross-workspace bleed).
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowLibraryDurabilityTest {

    private lateinit var dbFile: File
    private lateinit var context: Context
    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var persistence: WorkflowPersistenceService
    private lateinit var library: WorkflowLibraryService

    /** File-backed DB (in-memory Room LOSES data on close — real process
     *  death needs a persisted file, same pattern as ProcessDeathRecoveryTest). */
    private fun openDb(): com.example.infrastructure.persistence.AppDatabase =
        Room.databaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

    private fun samplePlan(): WorkflowPlan = WorkflowPlan(
        id = WorkflowId("wf_sample"),
        goal = "بناء وحدة RAG",
        executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
        steps = listOf(
            StepNode(
                id = "step_1",
                taskId = TaskId("task_step_1"),
                agentRole = AgentRole.PLANNER,
                description = "تحليل المتطلبات",
                dependencies = emptySet()
            ),
            StepNode(
                id = "step_2",
                taskId = TaskId("task_step_2"),
                agentRole = AgentRole.CODER,
                description = "تنفيذ الخطوة",
                dependencies = setOf("step_1"),
                assignedAgentId = "code_craftsman",
                assignedModelId = "google_google_gemini_model_gemini-2_5_flash"
            )
        )
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File.createTempFile("wf_library_death_", ".db")
        db = openDb()
        persistence = WorkflowPersistenceService(
            workflowExecutionDao = db.workflowExecutionDao(),
            workflowStepStateDao = db.workflowStepStateDao()
        )
        library = WorkflowLibraryService(
            workflowDefinitionDao = db.workflowDefinitionDao(),
            planSerializer = persistence,
            workspaceIdProvider = { "ws_alpha" }
        )
    }

    @After
    fun teardown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `save then process death then load preserves the full plan`() = runBlocking {
        val id = library.saveDefinition(existingId = null, name = "خطة RAG", plan = samplePlan())

        // ---- PROCESS DEATH (file-backed) ----
        db.close()
        db = openDb()
        val reopenedPersistence = WorkflowPersistenceService(
            workflowExecutionDao = db.workflowExecutionDao(),
            workflowStepStateDao = db.workflowStepStateDao()
        )
        val reopenedLibrary = WorkflowLibraryService(
            workflowDefinitionDao = db.workflowDefinitionDao(),
            planSerializer = reopenedPersistence,
            workspaceIdProvider = { "ws_alpha" }
        )

        val restored = reopenedLibrary.loadDefinition(id)
        assertNotNull("التعريف يجب أن ينجو من موت العملية", restored)
        assertEquals("بناء وحدة RAG", restored!!.goal)
        assertEquals(ExecutionMode.DIRECTED_ACYCLIC_GRAPH, restored.executionMode)
        assertEquals(2, restored.steps.size)
        assertEquals(setOf("step_1"), restored.steps[1].dependencies)
        // Schema v3 — canonical agent binding + model pin survive the round-trip.
        assertEquals("code_craftsman", restored.steps[1].assignedAgentId)
        assertEquals("google_google_gemini_model_gemini-2_5_flash", restored.steps[1].assignedModelId)
    }

    @Test
    fun `re-save bumps version and library list reflects it`() = runBlocking {
        val plan = samplePlan()
        val id = library.saveDefinition(existingId = null, name = "خطة", plan = plan)
        library.saveDefinition(existingId = id, name = "خطة معدلة", plan = plan.copy(goal = "هدف معدل"))

        val summaries = library.observeLibrary("ws_alpha").first()
        assertEquals(1, summaries.size)
        assertEquals(2, summaries.first().version)
        assertEquals("خطة معدلة", summaries.first().name)
        assertEquals("هدف معدل", library.loadDefinition(id)?.goal)
    }

    @Test
    fun `clone produces an independent copy and recordRun updates history`() = runBlocking {
        val id = library.saveDefinition(existingId = null, name = "الأصل", plan = samplePlan())
        val cloneId = library.cloneDefinition(id)

        assertNotNull(cloneId)
        assertTrue(cloneId != id)

        library.recordRun(cloneId!!)
        library.recordRun(cloneId)

        val summaries = library.observeLibrary("ws_alpha").first()
        val clone = summaries.first { it.workflowId == cloneId }
        val original = summaries.first { it.workflowId == id }
        assertEquals(2, clone.runCount)
        assertEquals(0, original.runCount)
        assertEquals(1, clone.version)
    }

    @Test
    fun `library is workspace-scoped`() = runBlocking {
        library.saveDefinition(existingId = null, name = "خطة أ", plan = samplePlan())

        val otherWorkspaceLibrary = WorkflowLibraryService(
            workflowDefinitionDao = db.workflowDefinitionDao(),
            planSerializer = persistence,
            workspaceIdProvider = { "ws_beta" }
        )
        otherWorkspaceLibrary.saveDefinition(existingId = null, name = "خطة ب", plan = samplePlan())

        val alpha = library.observeLibrary("ws_alpha").first()
        assertEquals(1, alpha.size)
        assertEquals("خطة أ", alpha.first().name)
    }

    @Test
    fun `delete removes the definition`() = runBlocking {
        val id = library.saveDefinition(existingId = null, name = "مؤقتة", plan = samplePlan())
        library.deleteDefinition(id)
        assertNull(library.loadDefinition(id))
        assertEquals(0, library.observeLibrary("ws_alpha").first().size)
    }
}
