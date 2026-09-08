package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.rag.KnowledgePersistenceService
import com.example.application.workflow.WorkflowPersistenceService
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.KnowledgeDocument
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.task.TaskId
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import com.example.infrastructure.persistence.repository.RoomMdpLearningStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * P0 CONVERGENCE — ProcessDeathRecoveryTest
 * ============================================================================
 *
 * Audit P0-د: "اختبار Process Death الحقيقي — وليس فقط in-memory/Robolectric"
 * (durable state surviving an actual store death, not just in-memory flows).
 *
 * Strategy: a FILE-BACKED Room database on disk (the same durable substrate
 * production uses). The "process death" is simulated by CLOSING the
 * database instance completely and rebuilding a NEW instance from the SAME
 * file — exactly what a restarted process does. Nothing in-memory survives
 * between the two halves of each test.
 *
 * Proves after death+restart:
 *   1. A RUNNING workflow restores its FULL plan + completed-step ledger
 *      (kill → restore → resume from the same DAG).
 *   2. RAG knowledge restores chunk metadata (embedding provenance),
 *      document titles and mimeType (the compatibility boundary survives).
 *   3. The MDP Q-table restores RESOURCE-AWARE learning cells.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessDeathRecoveryTest {

    private lateinit var dbFile: File
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File.createTempFile("process_death_", ".db")
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    private fun openDb(): com.example.infrastructure.persistence.AppDatabase =
        Room.databaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

    // ------------------------------------------------------------------
    // 1. Workflow durability across process death
    // ------------------------------------------------------------------

    @Test
    fun `a killed RUNNING workflow restores its full plan and progress after restart`() = runBlocking {
        val plan = WorkflowPlan(
            id = WorkflowId("wf_death_1"),
            goal = "هدف الاختبار",
            steps = listOf(
                StepNode(
                    id = "s1", taskId = TaskId("t1"), agentRole = AgentRole.PLANNER,
                    description = "الخطوة الأولى",
                    dependencies = emptySet()
                ),
                StepNode(
                    id = "s2", taskId = TaskId("t2"), agentRole = AgentRole.CODER,
                    description = "الخطوة الثانية (تعتمد على الأولى)",
                    dependencies = setOf("s1")
                ),
                StepNode(
                    id = "s3", taskId = TaskId("t3"), agentRole = AgentRole.REVIEWER,
                    description = "المراجعة النهائية",
                    dependencies = setOf("s2")
                )
            )
        )

        // ---- "First process": start the workflow, finish step 1, then DIE. ----
        val db1 = openDb()
        val svc1 = WorkflowPersistenceService(db1.workflowExecutionDao(), db1.workflowStepStateDao())
        svc1.start(plan.id, "ws_death", plan)
        svc1.markStepStatus(plan.id, "s1", com.example.domain.core.workflow.StepStatus.COMPLETED, "تمت", 100L)
        svc1.checkpoint(plan.id, 1)
        db1.close() // ← process death: nothing in-memory survives this.

        // ---- "Second process": reopen the SAME durable file and resume. ----
        val db2 = openDb()
        val svc2 = WorkflowPersistenceService(db2.workflowExecutionDao(), db2.workflowStepStateDao())
        val resumable = svc2.resumable()

        assertEquals("The RUNNING workflow must be resumable after process death", 1, resumable.size)
        val restored = resumable[0]
        assertEquals("wf_death_1", restored.workflowId.value)
        assertEquals("ws_death", restored.workspaceId)
        assertEquals(setOf("s1"), restored.completedStepIds)
        assertEquals(1, restored.currentStepIndex)

        // The FULL plan structure is restored — the resume path does NOT
        // need to rebuild the DAG from task definitions (audit §5).
        assertEquals(3, restored.plan.steps.size)
        assertEquals("s1", restored.plan.steps[0].id)
        assertEquals(setOf("s1"), restored.plan.steps[1].dependencies)
        assertEquals(AgentRole.REVIEWER, restored.plan.steps[2].agentRole)
        assertEquals("هدف الاختبار", restored.plan.goal)
        db2.close()
    }

    // ------------------------------------------------------------------
    // 2. RAG metadata durability across process death
    // ------------------------------------------------------------------

    @Test
    fun `RAG chunk metadata, titles and mimeType survive restart`() = runBlocking {
        val doc = KnowledgeDocument(
            id = "doc_death",
            title = "دليل المعمارية",
            sourceUri = "workspace://docs/x.md",
            mimeType = "text/x-markdown",
            content = "محتوى تجريبي للاختبار"
        )
        val chunks = listOf(
            DocumentChunk(
                id = "doc_death_c0",
                documentId = "doc_death",
                documentTitle = "دليل المعمارية",
                chunkIndex = 0,
                text = "محتوى تجريبي للاختبار",
                vector = EmbeddingVector(floatArrayOf(0.1f, 0.2f, 0.3f)),
                tokenCount = 4,
                metadata = mapOf(
                    "embeddingResourceId" to "res_embedding_a",
                    "embeddingSemantic" to "false",
                    "source" to "official-docs"
                )
            )
        )

        val db1 = openDb()
        val rag1 = KnowledgePersistenceService(db1.knowledgeDocumentDao(), db1.documentChunkDao())
        rag1.persistDocument("ws_death", doc, chunks)
        db1.close() // ← process death.

        val db2 = openDb()
        val rag2 = KnowledgePersistenceService(db2.knowledgeDocumentDao(), db2.documentChunkDao())
        val (docs, restoredChunks) = rag2.loadWorkspaceKnowledge("ws_death")

        assertEquals(1, docs.size)
        assertEquals("text/x-markdown", docs[0].mimeType)
        assertEquals("دليل المعمارية", docs[0].title)

        assertEquals(1, restoredChunks.size)
        val restored = restoredChunks[0]
        assertEquals(
            "Chunk metadata must survive restart (audit §7: the embedding-compatibility boundary)",
            mapOf(
                "embeddingResourceId" to "res_embedding_a",
                "embeddingSemantic" to "false",
                "source" to "official-docs"
            ),
            restored.metadata
        )
        assertEquals("Title must be rebuilt from the document map on reload", "دليل المعمارية", restored.documentTitle)
        db2.close()
    }

    @Test
    fun `retrievalSource label stays honest across restart (lexical fallback is not re-labeled semantic)`() = runBlocking {
        val doc = KnowledgeDocument(id = "doc_honest", title = "T", sourceUri = "u", content = "c")
        val lexicalChunk = DocumentChunk(
            id = "doc_honest_c0", documentId = "doc_honest", documentTitle = "T",
            chunkIndex = 0, text = "c",
            // A lexical-fallback chunk DOES carry a (hash) vector — the old
            // "vector != null → SEMANTIC" rule mislabeled it.
            vector = EmbeddingVector(floatArrayOf(0.5f)),
            metadata = mapOf("embeddingSemantic" to "false")
        )
        val semanticChunk = DocumentChunk(
            id = "doc_honest_c1", documentId = "doc_honest", documentTitle = "T",
            chunkIndex = 1, text = "c2",
            vector = EmbeddingVector(floatArrayOf(0.6f)),
            metadata = mapOf("embeddingSemantic" to "true")
        )

        val db1 = openDb()
        KnowledgePersistenceService(db1.knowledgeDocumentDao(), db1.documentChunkDao())
            .persistDocument("ws_h", doc, listOf(lexicalChunk, semanticChunk))
        db1.close()

        val db2 = openDb()
        val rows = db2.documentChunkDao().getChunksForDocument("doc_honest")
        assertEquals(2, rows.size)
        assertEquals(
            "LEXICAL_FALLBACK", rows.first { it.id == "doc_honest_c0" }.retrievalSource
        )
        assertEquals(
            "SEMANTIC", rows.first { it.id == "doc_honest_c1" }.retrievalSource
        )
        db2.close()
    }

    // ------------------------------------------------------------------
    // 3. Resource-aware MDP learning durability across process death
    // ------------------------------------------------------------------

    private fun sampleState() = DecisionState(taskId = TaskId("t_death"))

    @Test
    fun `resource-aware Q-table cells survive restart`() = runBlocking {
        val resourceA = ResourceId("res_providerA/modelX")
        val resourceB = ResourceId("res_providerB/modelY")
        val decisionA = DecisionRecord(
            selectedResourceId = resourceA, providerId = "provA", serviceId = "svcA",
            configurationVersion = 1, requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
            rationale = "r", confidence = 0.8f
        )
        val decisionB = decisionA.copy(selectedResourceId = resourceB)

        val db1 = openDb()
        val store1 = RoomMdpLearningStore(db1.mdpQValueDao())
        val engine1 = CbrMdpEngine(caseBase = CaseBase(), mdpStore = store1)
        engine1.loadPersistedQTable()

        // Learn DIFFERENT outcomes for the SAME action type on DIFFERENT resources.
        engine1.processObservationAndUpdateBelief(
            sampleState(),
            com.example.domain.core.decision.EnvironmentObservation(
                action = DecisionAction(DecisionActionType.SELECT_MODEL, targetId = resourceA.value, decisionRecord = decisionA),
                isSuccess = true, actualLatencyMs = 100
            )
        )
        engine1.processObservationAndUpdateBelief(
            sampleState(),
            com.example.domain.core.decision.EnvironmentObservation(
                action = DecisionAction(DecisionActionType.SELECT_MODEL, targetId = resourceB.value, decisionRecord = decisionB),
                isSuccess = false, actualLatencyMs = 900
            )
        )
        // Let the async dirty-cell flush land (persistDirtyCells only flushes
        // asynchronously when a persistence scope is wired; persist directly
        // through the store here for determinism).
        store1.persist(
            listOfNotNull(
                engine1.getQEntry(engine1.stateRegionKey(sampleState()), engine1.resourceAxisKey(
                    DecisionAction(DecisionActionType.SELECT_MODEL, targetId = resourceA.value, decisionRecord = decisionA)
                ), DecisionActionType.SELECT_MODEL),
                engine1.getQEntry(engine1.stateRegionKey(sampleState()), engine1.resourceAxisKey(
                    DecisionAction(DecisionActionType.SELECT_MODEL, targetId = resourceB.value, decisionRecord = decisionB)
                ), DecisionActionType.SELECT_MODEL)
            )
        )
        db1.close() // ← process death.

        // ---- Restart: a NEW engine loads the persisted table from disk. ----
        val db2 = openDb()
        val engine2 = CbrMdpEngine(caseBase = CaseBase(), mdpStore = RoomMdpLearningStore(db2.mdpQValueDao()))
        engine2.loadPersistedQTable()

        val region = engine2.stateRegionKey(sampleState())
        val cellA = engine2.getQEntry(region, "R:${resourceA.value}", DecisionActionType.SELECT_MODEL)
        val cellB = engine2.getQEntry(region, "R:${resourceB.value}", DecisionActionType.SELECT_MODEL)

        assertNotNull("The resource-A cell must survive restart", cellA)
        assertNotNull("The resource-B cell must survive restart", cellB)
        assertTrue(
            "Learning must be PER-RESOURCE: the success and the failure must land in DIFFERENT cells",
            cellA!!.successCount != cellB!!.successCount
        )
        assertEquals(1, cellA.visitCount)
        assertEquals(1, cellB.visitCount)
        db2.close()
    }
}
