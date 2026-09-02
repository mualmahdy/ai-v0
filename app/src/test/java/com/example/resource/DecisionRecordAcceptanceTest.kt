package com.example.resource

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.resource.InMemoryResourceHealthService
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.resource.GovernanceResult
import com.example.domain.core.resource.GovernanceState
import com.example.domain.core.resource.ProviderId
import com.example.domain.core.resource.ResourceCategory
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecordInput
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.resource.ServiceId
import com.example.domain.core.resource.executionPermitted
import com.example.domain.ports.resource.RuntimeSupportToken
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.resource.RoomDecisionRecordStore
import com.example.infrastructure.resource.RoomResourceRegistryService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Section N acceptance tests — DecisionRecord (P0.4, Section F/H — LOCKED).
 * Covers matrix rows 7, 14, 14b + candidateEvaluations invariant + persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DecisionRecordAcceptanceTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var registry: RoomResourceRegistryService
    private lateinit var decisionStore: RoomDecisionRecordStore
    private lateinit var health: InMemoryResourceHealthService
    private lateinit var decisionService: DecisionService
    private val token: RuntimeSupportToken = RuntimeSupportToken.issueForControlPlane()

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        health = InMemoryResourceHealthService()
        registry = RoomResourceRegistryService(
            dao = database.resourceRecordDao(),
            cooldownChecker = { id -> health.isInCooldown(id) }
        ).apply { bindControlPlaneToken(token) }
        decisionStore = RoomDecisionRecordStore(database.decisionRecordDao())

        decisionService = DecisionService(
            cbrMdpEngine = CbrMdpEngine(caseBase = CaseBase()),
            componentRegistry = ComponentRegistry(),
            securityGuard = SecurityGuardService(),
            resourceRegistry = registry,
            resourceHealthService = health,
            decisionRecordStore = decisionStore
        )

        // Seed two usable LLM resources through the full control-plane path.
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId("openai"),
                serviceId = ServiceId("gpt-4o"),
                resourceType = ResourceType.LLM,
                category = ResourceCategory.REMOTE,
                capabilities = listOf(CapabilityType.LLM_GENERATION.code, CapabilityType.REASONING.code)
            )
        )
        registry.register(
            ResourceRecordInput(
                providerId = ProviderId("ollama"),
                serviceId = ServiceId("llama3.2"),
                resourceType = ResourceType.LLM,
                category = ResourceCategory.LOCAL,
                capabilities = listOf(CapabilityType.LLM_GENERATION.code)
            )
        )
        for (id in listOf(ResourceId("res_openai__gpt-4o"), ResourceId("res_ollama__llama3.2"))) {
            registry.setLifecycleState(id, ResourceLifecycleState.VALIDATING)
            registry.setRuntimeSupported(id, true, token)
            registry.setLifecycleState(id, ResourceLifecycleState.HEALTHY)
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    /**
     * Test 7 — DecisionService output for a capability-requiring step: DecisionRecord
     * with non-null selectedResourceId + governanceResult (explicit state).
     */
    @Test
    fun test7_decisionRecordProduced_withExplicitGovernance() = runBlocking {
        val record = decisionService.evaluateWithRecord(
            taskId = "task-1",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code)
        )

        assertNotNull(record)
        assertNotNull(record!!.selectedResourceId.value)
        assertTrue(record.selectedResourceId.value.isNotBlank())
        assertNotNull(record.governanceResult) // never null (Section F)
        assertTrue(record.governanceResult.state in GovernanceState.entries)
        assertTrue(record.candidateEvaluations.isNotEmpty())
        assertTrue(record.candidateEvaluations.any { it.resourceId == record.selectedResourceId && it.isSelected })
    }

    /** Test 14 — governanceResult.state is ALWAYS an explicit member of the locked enum. */
    @Test
    fun test14_governanceStateNeverNull_alwaysExplicit() = runBlocking {
        repeat(5) { i ->
            val record = decisionService.evaluateWithRecord(
                taskId = "task-14",
                stepId = "step-$i",
                requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code)
            )
            assertNotNull(record)
            assertNotNull(record!!.governanceResult.state)
            assertTrue(record.governanceResult.state in GovernanceState.entries)
        }
    }

    /** Test 14b — resource class with no governance policy: state == NOT_APPLICABLE. */
    @Test
    fun test14b_noGovernancePolicy_notApplicable() = runBlocking {
        val record = decisionService.evaluateWithRecord(
            taskId = "task-14b",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code)
        )
        assertNotNull(record)
        assertEquals(GovernanceState.NOT_APPLICABLE, record!!.governanceResult.state)
        assertEquals(GovernanceResult.NOT_APPLICABLE.state, record.governanceResult.state)
    }

    /** GOV-3: default decision is execution-permitted (security permits + NOT_APPLICABLE). */
    @Test
    fun gov3_defaultDecisionIsExecutionPermitted() = runBlocking {
        val record = decisionService.evaluateWithRecord(
            taskId = "task-gov3",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code)
        )
        assertNotNull(record)
        assertTrue(record!!.executionPermitted())
    }

    /** DecisionRecord persists to the Room `decision_records` table and versions increment. */
    @Test
    fun decisionRecord_persisted_andVersionedPerStep() = runBlocking {
        val first = decisionService.evaluateWithRecord(
            taskId = "task-persist",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code)
        )!!
        val stored = decisionStore.get(first.decisionId)
        assertNotNull(stored)
        assertEquals(first.selectedResourceId.value, stored!!.selectedResourceId.value)
        assertEquals(1, stored.decisionVersion)

        // Re-decision of the same (taskId, stepId) increments decisionVersion (test 10 basis).
        val second = decisionService.evaluateWithRecord(
            taskId = "task-persist",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.LLM_GENERATION.code)
        )!!
        assertEquals(2, second.decisionVersion)
    }

    /** No usable resource -> null (explicit no-capable-resource, never silent substitution). */
    @Test
    fun noUsableResource_returnsNull() = runBlocking {
        val record = decisionService.evaluateWithRecord(
            taskId = "task-none",
            stepId = "step-1",
            requiredCapabilityIds = setOf(CapabilityType.CODE_ENGINEERING.code) // no resource provides it
        )
        assertEquals(null, record)
    }
}
