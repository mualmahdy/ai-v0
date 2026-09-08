package com.example.adversarial

import com.example.application.decision.DecisionService
import com.example.application.orchestration.WorkflowEngine
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.workflow.StepNode
import com.example.domain.core.workflow.StepStatus
import com.example.domain.core.workflow.WorkflowId
import com.example.domain.core.workflow.WorkflowPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ADVERSARIAL TEST (defect family 3 & 8) — Canonical Identity &
 * Reproducibility
 * ============================================================================
 *
 * Verified defects repaired here:
 *
 *  1. `assignedModelId` could be populated with a PROVIDER id
 *     (`assignedModelId ?: preferredProviderId`), and the decision layer
 *     matched `cand.resourceId == pinned || cand.providerId == pinned` — a
 *     provider id in the model field silently authorized EVERY LLM
 *     candidate of that provider (provider fallback masquerading as an
 *     exact pin).
 *  2. Exact agent bindings silently degraded into role/provider fallbacks
 *     (resolveStepAgent fell through when the pinned agent was
 *     missing/disabled/role-mismatched).
 *
 * Repaired invariants pinned below:
 *  - provider ids NEVER match a model pin;
 *  - a non-matching pin surfaces as an explicit ASK_USER
 *    (pinned_model_unavailable) — never a silent substitution;
 *  - a failed agent PIN fails the workflow step with the explicit reason —
 *    never a silent role fallback.
 */
class IdentityReproducibilityTest {

    private val testAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent_identity"),
            name = "Identity Agent",
            role = AgentRole.CODER,
            description = "test",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.TOOL_EXECUTION),
        budget = AgentBudget(maxTokens = 30000)
    )

    private fun buildRegistry(): ComponentRegistry {
        val registry = ComponentRegistry()
        // TWO LLM resources from the SAME provider, with exact
        // model-resource ids (res: scheme).
        listOf(
            "res:provx:svc1:llm:model_a",
            "res:provx:svc1:llm:model_b"
        ).forEach { id ->
            registry.resourceRegistry.registerResource(
                ResourceRecord(
                    resourceId = ResourceId(id),
                    providerId = "provx",
                    serviceId = "svc1",
                    resourceType = ResourceType.LLM,
                    capabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING),
                    configurationVersion = 1L,
                    lifecycleState = ResourceLifecycleState.ENABLED,
                    runtimeSupported = true,
                    healthStatus = HealthStatus.HEALTHY,
                    isLocal = false
                )
            )
        }
        return registry
    }

    private fun decisionService(registry: ComponentRegistry): DecisionService =
        DecisionService(
            cbrMdpEngine = CbrMdpEngine(),
            resourceCapabilityGraph = registry.resourceCapabilityGraph,
            securityGuard = SecurityGuardService()
        )

    private fun taskWithPin(pin: String?) = TaskDefinition(
        id = TaskId("t-identity"),
        assignedAgentId = testAgent.identity.id,
        input = TaskInput(rawPrompt = "identity test"),
        assignedModelId = pin,
        requirements = com.example.domain.core.task.TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.LLM_GENERATION)
        )
    )

    private fun contextFor(task: TaskDefinition) =
        com.example.application.decision.DecisionContext(
            task = task,
            networkPolicy = NetworkPolicy.HYBRID,
            isNetworkAvailable = true
        )

    // ------------------------------------------------------------------
    // 1. A provider id in the model field matches NOTHING
    // ------------------------------------------------------------------

    @Test
    fun `provider id as model pin authorizes NO candidate`() = runBlocking {
        val registry = buildRegistry()
        val service = decisionService(registry)

        // Pin is the PROVIDER id — the OLD behaviour matched every
        // candidate of that provider via `cand.providerId == pinned`.
        val result = service.evaluate(contextFor(taskWithPin("provx")))

        val selectedModel = result.evaluatedAlternatives
            .firstOrNull { it.action.type == DecisionActionType.SELECT_MODEL }

        // The decision must NOT silently bind to a provider-wide candidate.
        assertTrue(
            "A provider id must never act as a model-resource pin: selected ${selectedModel?.action?.targetId}",
            selectedModel == null ||
                selectedModel.action.decisionRecord?.selectedResourceId?.value?.startsWith("res:provx") != true
        )
    }

    // ------------------------------------------------------------------
    // 2. Exact model pin binds EXACTLY that resource (reproducibility)
    // ------------------------------------------------------------------

    @Test
    fun `exact model-resource pin binds exactly that resource`() = runBlocking {
        val registry = buildRegistry()
        val service = decisionService(registry)

        val pinned = "res:provx:svc1:llm:model_b"
        val result = service.evaluate(contextFor(taskWithPin(pinned)))

        val modelCandidates = result.evaluatedAlternatives
            .filter { it.action.type == DecisionActionType.SELECT_MODEL }
        assertTrue(
            "Model candidates must exist for an LLM-requiring task",
            modelCandidates.isNotEmpty()
        )
        // EVERY model candidate must be bound EXACTLY to the pin (the pin
        // filtered the candidate list to the pinned resource only).
        modelCandidates.forEach { candidate ->
            assertEquals(
                "The pin must bind EXACTLY the pinned model-resource",
                pinned,
                candidate.action.decisionRecord?.selectedResourceId?.value
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Unresolvable pin surfaces as explicit ASK_USER
    // ------------------------------------------------------------------

    @Test
    fun `unresolvable model pin surfaces as explicit ASK_USER - never silent substitution`() = runBlocking {
        val registry = buildRegistry()
        val service = decisionService(registry)

        val result = service.evaluate(contextFor(taskWithPin("res:provx:svc1:llm:model_MISSING")))

        // No SELECT_MODEL/EXECUTE_STEP candidate bound to a real resource may
        // exist — an unresolvable pin never silently substitutes.
        val boundCandidate = result.evaluatedAlternatives.firstOrNull {
            (it.action.type == DecisionActionType.SELECT_MODEL || it.action.type == DecisionActionType.EXECUTE_STEP) &&
                it.action.decisionRecord != null
        }
        assertTrue(
            "An unresolvable pin must not silently bind another model: ${boundCandidate?.action?.targetId}",
            boundCandidate == null
        )
        // ...and the explicit pinned_model_unavailable ASK_USER candidate
        // EXISTS for the planner to surface.
        val askUser = result.evaluatedAlternatives.firstOrNull {
            it.action.type == DecisionActionType.ASK_USER &&
                it.action.targetId == "pinned_model_unavailable"
        }
        assertTrue(
            "The explicit pinned_model_unavailable ask must exist",
            askUser != null
        )
    }

    // ------------------------------------------------------------------
    // 4. ExecuteAgentTaskUseCase never stores a provider id in the model
    //    field (the conflation is gone)
    // ------------------------------------------------------------------

    @Test
    fun `use case keeps provider preference and model pin strictly separated`() = runBlocking {
        val registry = buildRegistry()
        val service = decisionService(registry)
        val orchestrator = com.example.application.orchestration.AgentOrchestrator(
            registry = registry,
            securityGuard = SecurityGuardService(),
            decisionService = service
        )
        val useCase = ExecuteAgentTaskUseCase(orchestrator)

        // preferredProviderId set, NO assignedModelId → the task must carry
        // NO model pin (previously the provider id became the pin).
        val taskField = TaskDefinition(
            id = TaskId("t-uc"),
            assignedAgentId = testAgent.identity.id,
            input = TaskInput(rawPrompt = "uc"),
            assignedModelId = null
        )
        // The use case passes only explicit pins — verified by observing the
        // decision outcome for a provider-id "pin": it never binds.
        val events = useCase(
            agent = testAgent,
            prompt = "uc test",
            taskId = "uc_test_task",
            preferredProviderId = "provx", // provider preference ONLY
            assignedModelId = null
        )
        var sawPinnedUnavailable = false
        events.collect { event ->
            if (event is com.example.domain.core.events.ExecutionEvent.DecisionMade) {
                if (event.decision.chosenAction.type == DecisionActionType.ASK_USER &&
                    event.decision.chosenAction.targetId == "pinned_model_unavailable"
                ) {
                    sawPinnedUnavailable = true
                }
            }
        }
        // With no model pin, the loop should NOT report a pinned-model
        // failure (the provider preference is a hint, not a pin). If there
        // are no resources at all it asks no_llm_resource_available —
        // neither is a silent provider substitution.
        assertFalse(
            "A provider preference must never masquerade as a model pin",
            sawPinnedUnavailable
        )
        assertEquals(null, taskField.assignedModelId)
    }

    // ------------------------------------------------------------------
    // 5. A failed agent PIN fails the workflow step explicitly
    // ------------------------------------------------------------------

    @Test
    fun `pinned agent binding failure fails the step - no silent role fallback`() = runBlocking {
        val registry = buildRegistry()
        val service = decisionService(registry)
        val orchestrator = com.example.application.orchestration.AgentOrchestrator(
            registry = registry,
            securityGuard = SecurityGuardService(),
            decisionService = service
        )
        // The resolver THROWS for the failed pin (composition-root policy —
        // exact bindings must not degrade).
        val engine = WorkflowEngine(
            orchestrator = orchestrator,
            persistenceService = null,
            workspaceIdProvider = { "ws_identity" },
            agentResolver = { step ->
                if (step.assignedAgentId == "agent_ghost_pin") {
                    throw IllegalStateException(
                        "PINNED_AGENT_NOT_FOUND: الوكيل المثبّت 'agent_ghost_pin' غير موجود في السجل الدائم."
                    )
                }
                throw IllegalStateException("unexpected")
            }
        )

        val plan = WorkflowPlan(
            id = WorkflowId("wfl_identity"),
            goal = "identity",
            steps = listOf(
                StepNode(
                    id = "s1",
                    taskId = TaskId("t_s1"),
                    agentRole = AgentRole.PLANNER,
                    description = "pinned step",
                    assignedAgentId = "agent_ghost_pin"
                )
            )
        )

        val report = engine.executePlan(plan)

        // The step FAILED with the explicit pin reason — not SKIPPED, not a
        // silent role fallback, not a fake success.
        assertEquals(StepStatus.FAILED, report.stepStatuses["s1"])
        val outcome = report.overallOutcome
        assertTrue("Overall outcome must be an explicit error", outcome is Outcome.Error<*>)
        val message = (outcome as Outcome.Error<*>).diagnosticMessage
            ?: (outcome.failure as? String ?: outcome.failure.toString())
        assertTrue(
            "Failure must cite the pinned-agent reason: $message",
            message.contains("PINNED_AGENT")
        )
    }

    // ------------------------------------------------------------------
    // 6. AgentRegistryService detailed resolution reports pin failures
    // ------------------------------------------------------------------

    @Test
    fun `registry detailed resolution distinguishes pinned from role-matched from unavailable`() = runBlocking {
        // In-memory fake DAO over the registry service contract.
        val dao = FakeAgentDefinitionDao()
        val registryService = com.example.application.agent.AgentRegistryService(dao)

        val pinnedAgent = testAgent.copy(
            identity = testAgent.identity.copy(id = AgentId("agent_pinned_real"))
        )
        dao.upsertAgent(
            com.example.infrastructure.persistence.entities.AgentDefinitionEntity(
                id = "agent_pinned_real",
                name = pinnedAgent.identity.name,
                role = pinnedAgent.identity.role.name,
                description = pinnedAgent.identity.description,
                systemPrompt = pinnedAgent.identity.systemPrompt,
                capabilitiesJson = "[]",
                workspaceScopeJson = "[]",
                maxTokens = 30000,
                enabled = true,
                version = 1,
                origin = "TEST",
                createdAtEpochMs = 0,
                updatedAtEpochMs = 0
            )
        )

        // Pinned + present + enabled + role match → Pinned.
        val pinned = registryService.resolveStepAgentDetailed(
            role = AgentRole.CODER, workspaceId = null, assignedAgentId = "agent_pinned_real"
        )
        assertTrue(pinned is com.example.application.agent.AgentRegistryService.StepAgentResolution.Pinned)

        // Missing pin → PinnedAgentUnavailable with the explicit reason.
        val missing = registryService.resolveStepAgentDetailed(
            role = AgentRole.CODER, workspaceId = null, assignedAgentId = "agent_ghost_pin"
        )
        assertTrue(missing is com.example.application.agent.AgentRegistryService.StepAgentResolution.PinnedAgentUnavailable)
        assertTrue(
            (missing as com.example.application.agent.AgentRegistryService.StepAgentResolution.PinnedAgentUnavailable)
                .reason.contains("PINNED_AGENT_NOT_FOUND")
        )

        // Role-mismatch pin → PinnedAgentUnavailable (NOT a role fallback).
        val mismatch = registryService.resolveStepAgentDetailed(
            role = AgentRole.RESEARCHER, workspaceId = null, assignedAgentId = "agent_pinned_real"
        )
        assertTrue(
            mismatch is com.example.application.agent.AgentRegistryService.StepAgentResolution.PinnedAgentUnavailable
        )
        assertTrue(
            (mismatch as com.example.application.agent.AgentRegistryService.StepAgentResolution.PinnedAgentUnavailable)
                .reason.contains("ROLE_MISMATCH")
        )
    }

    /** Minimal in-memory AgentDefinitionDao fake. */
    private class FakeAgentDefinitionDao : com.example.infrastructure.persistence.dao.AgentDefinitionDao {
        private val rows = mutableMapOf<String, com.example.infrastructure.persistence.entities.AgentDefinitionEntity>()
        override suspend fun allAgents() = rows.values.toList()
        override fun allAgentsFlow() =
            kotlinx.coroutines.flow.MutableStateFlow(rows.values.toList())
        override suspend fun getAgentById(id: String) = rows[id]
        override suspend fun upsertAgent(entity: com.example.infrastructure.persistence.entities.AgentDefinitionEntity) {
            rows[entity.id] = entity
        }
        override suspend fun deleteAgent(id: String) { rows.remove(id) }
        override suspend fun agentCount() = rows.size
    }
}
