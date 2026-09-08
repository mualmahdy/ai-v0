package com.example.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.workflow.WorkflowPersistenceService
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.SideEffectClassification
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.AcceptanceCriterion
import com.example.domain.core.task.TaskCapabilityRequirements
import com.example.domain.core.task.TaskId
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.workflow.ExecutionMode
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
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
 * P0 CONVERGENCE — WorkflowPlanDurabilityTest
 * ============================================================================
 *
 * Audit step 12 §5: "الـ Workflow persistence لا يحفظ كامل الـ plan" —
 * serializePlan() previously persisted only (id, description, agentRole) and
 * deserializePlan() returned `steps = emptyList()`, so a killed workflow
 * could NOT be restored as a durable artifact: the plan's structure
 * (dependencies, requirements, acceptance criteria, execution mode) was
 * lost on process death.
 *
 * This test pins the LOSSLESS round-trip: a persisted workflow restores the
 * FULL plan (every StepNode field) through the durable Room substrate, so
 * kill → restore → resume operates on the SAME DAG.
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowPlanDurabilityTest {

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var service: WorkflowPersistenceService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = WorkflowPersistenceService(
            workflowExecutionDao = db.workflowExecutionDao(),
            workflowStepStateDao = db.workflowStepStateDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A plan exercising EVERY StepNode field (nothing may be silently dropped). */
    private fun richPlan(): WorkflowPlan {
        val requirements = TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.SEARCH),
            optionalCapabilities = setOf(CapabilityType.CODE_ENGINEERING),
            prohibitedCapabilities = setOf(CapabilityType.SHELL_EXECUTION),
            requiredResourceTypes = listOf("LLM", "SEARCH"),
            requiredModelCapabilities = listOf("vision"),
            requiredAgentCapabilities = setOf(CapabilityType.REASONING),
            networkRequirement = NetworkPolicy.HYBRID,
            requiresLocalInference = true,
            securityRequirements = listOf("no-secrets-in-output"),
            requiredEvidenceKeys = listOf("source-url"),
            expectedOutputType = "MARKDOWN",
            acceptanceCriteria = listOf(
                AcceptanceCriterion(
                    id = "ac_1",
                    description = "الإخراج لا يقل عن 200 حرف",
                    requiredKey = "length",
                    validatorType = "MIN_LENGTH",
                    minValue = 200.0,
                    maxValue = 100000.0,
                    regexPattern = null
                ),
                AcceptanceCriterion(
                    id = "ac_2",
                    description = "يذكر مصدراً واحداً على الأقل",
                    validatorType = "REGEX",
                    regexPattern = "https?://\\S+"
                )
            ),
            localityConstraint = Locality.REMOTE_CLOUD,
            maxAllowedSideEffect = SideEffectClassification.IDEMPOTENT
        )
        return WorkflowPlan(
            id = WorkflowId("wf_durability_1"),
            goal = "بناء تقرير معماري كامل مع مصادر",
            executionMode = ExecutionMode.DIRECTED_ACYCLIC_GRAPH,
            steps = listOf(
                StepNode(
                    id = "step_research",
                    taskId = TaskId("task_research"),
                    agentRole = AgentRole.RESEARCHER,
                    description = "البحث عن مراجع معمارية",
                    requirements = requirements,
                    expectedOutputs = listOf("قائمة مراجع"),
                    evidenceRequirements = listOf("source-url"),
                    acceptanceCriteria = requirements.acceptanceCriteria,
                    dependencies = emptySet(),
                    status = StepStatus.COMPLETED,
                    outputSummary = "تم العثور على 5 مراجع",
                    durationMs = 1234L
                ),
                StepNode(
                    id = "step_write",
                    taskId = TaskId("task_write"),
                    agentRole = AgentRole.CODER,
                    description = "كتابة التقرير",
                    requirements = requirements.copy(requiredCapabilities = setOf(CapabilityType.LLM_GENERATION)),
                    expectedOutputs = listOf("التقرير النهائي"),
                    evidenceRequirements = emptyList(),
                    acceptanceCriteria = emptyList(),
                    dependencies = setOf("step_research"),
                    status = StepStatus.PENDING,
                    outputSummary = null,
                    durationMs = 0L
                )
            )
        )
    }

    @Test
    fun `full plan survives persistence round-trip with every field intact`() = runBlocking {
        val plan = richPlan()
        service.start(plan.id, "ws_durability", plan)

        val restored = service.byId(WorkflowId("wf_durability_1"))
        assertNotNull("The workflow row must be persisted", restored)

        // The plan itself is restored losslessly through resumable().
        val resumable = service.resumable()
        assertEquals(1, resumable.size)
        val restoredPlan = resumable[0].plan

        assertEquals(plan.id, restoredPlan.id)
        assertEquals(plan.goal, restoredPlan.goal)
        assertEquals(plan.executionMode, restoredPlan.executionMode)
        assertEquals(plan.steps.size, restoredPlan.steps.size)

        val original = plan.steps[0]
        val restoredStep = restoredPlan.steps[0]
        assertEquals(original.id, restoredStep.id)
        assertEquals(original.taskId, restoredStep.taskId)
        assertEquals(original.agentRole, restoredStep.agentRole)
        assertEquals(original.description, restoredStep.description)
        assertEquals(original.expectedOutputs, restoredStep.expectedOutputs)
        assertEquals(original.evidenceRequirements, restoredStep.evidenceRequirements)
        assertEquals(original.dependencies, restoredStep.dependencies)
        assertEquals(original.outputSummary, restoredStep.outputSummary)
        assertEquals(original.durationMs, restoredStep.durationMs)
        assertEquals(original.status, restoredStep.status)

        // The FULL requirements tree round-trips.
        val req = restoredStep.requirements
        assertEquals(original.requirements.requiredCapabilities, req.requiredCapabilities)
        assertEquals(original.requirements.optionalCapabilities, req.optionalCapabilities)
        assertEquals(original.requirements.prohibitedCapabilities, req.prohibitedCapabilities)
        assertEquals(original.requirements.requiredResourceTypes, req.requiredResourceTypes)
        assertEquals(original.requirements.requiredModelCapabilities, req.requiredModelCapabilities)
        assertEquals(original.requirements.requiredAgentCapabilities, req.requiredAgentCapabilities)
        assertEquals(original.requirements.networkRequirement, req.networkRequirement)
        assertEquals(original.requirements.requiresLocalInference, req.requiresLocalInference)
        assertEquals(original.requirements.securityRequirements, req.securityRequirements)
        assertEquals(original.requirements.requiredEvidenceKeys, req.requiredEvidenceKeys)
        assertEquals(original.requirements.expectedOutputType, req.expectedOutputType)
        assertEquals(original.requirements.localityConstraint, req.localityConstraint)
        assertEquals(original.requirements.maxAllowedSideEffect, req.maxAllowedSideEffect)

        // Acceptance criteria round-trip with nullable fields intact.
        val acOriginal = original.requirements.acceptanceCriteria[0]
        val acRestored = req.acceptanceCriteria.first { it.id == "ac_1" }
        assertEquals(acOriginal.description, acRestored.description)
        assertEquals(acOriginal.requiredKey, acRestored.requiredKey)
        assertEquals(acOriginal.validatorType, acRestored.validatorType)
        assertEquals(acOriginal.minValue, acRestored.minValue)
        assertEquals(acOriginal.maxValue, acRestored.maxValue)
        assertEquals(acOriginal.regexPattern, acRestored.regexPattern)
    }

    @Test
    fun `completed step ids are durably tracked for resume`() = runBlocking {
        val plan = richPlan()
        service.start(plan.id, "ws_durability", plan)
        service.markStepStatus(plan.id, "step_research", StepStatus.COMPLETED, "تم", 1200L)
        service.checkpoint(plan.id, 1)

        val resumable = service.resumable()
        assertEquals(1, resumable.size)
        assertEquals(setOf("step_research"), resumable[0].completedStepIds)
        assertEquals(1, resumable[0].currentStepIndex)
        assertTrue(
            "The resumed plan must carry its real steps (never an empty plan)",
            resumable[0].plan.steps.isNotEmpty()
        )
    }

    @Test
    fun `legacy pre-convergence rows degrade honestly (steps kept, never silently empty)`() = runBlocking {
        // A row written by the OLD serializer (id/description/agentRole only).
        val legacyJson = """
            {"steps":[{"id":"s1","description":"خطوة قديمة","agentRole":"PLANNER"}]}
        """.trimIndent()
        db.workflowExecutionDao().upsert(
            com.example.infrastructure.persistence.entities.WorkflowExecutionEntity(
                workflowId = "wf_legacy",
                workspaceId = "ws_legacy",
                planJson = legacyJson,
                lifecycleState = "RUNNING",
                currentStepIndex = 0,
                totalSteps = 1,
                startedAtEpochMs = 1L,
                lastCheckpointAtEpochMs = 1L,
                completedAtEpochMs = null,
                failureReason = null,
                cancellationReason = null
            )
        )
        val resumable = service.resumable()
        assertEquals(1, resumable.size)
        assertEquals(
            "Legacy steps still produce REAL StepNodes — not an empty plan",
            1,
            resumable[0].plan.steps.size
        )
        assertEquals("s1", resumable[0].plan.steps[0].id)
        assertEquals("خطوة قديمة", resumable[0].plan.steps[0].description)
    }
}
