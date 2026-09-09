package com.example.application.decision

import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.ResourceCapabilityGraph
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * P1-1 (audit 2026 §8) — DecisionContext reflects the REAL world state.
 * ============================================================================
 *
 * The audit's finding: `buildDecisionContext()` shipped
 * `capabilities = emptyList()` and `availableTools = emptyList()` while the
 * ResourceRegistry actually contained capabilities/tools — "planner state
 * does not match actual world state". This test proves the context is now
 * fed by the authoritative providers.
 */
class DecisionContextTruthTest {

    private fun decisionService(
        capabilities: List<CapabilityDescriptor>,
        tools: List<String>
    ): DecisionService = DecisionService(
        cbrMdpEngine = CbrMdpEngine(caseBase = CaseBase()),
        resourceCapabilityGraph = ResourceCapabilityGraph(emptyList()),
        securityGuard = SecurityGuardService()
    ).apply {
        liveCapabilityDescriptorsProvider = { capabilities }
        liveToolNamesProvider = { tools }
    }

    private fun task(
        required: Set<CapabilityType> = setOf(CapabilityType.TOOL_EXECUTION)
    ): TaskDefinition = TaskDefinition(
        id = TaskId("t-truth"),
        assignedAgentId = com.example.domain.core.agent.AgentId("a"),
        input = TaskInput(rawPrompt = "test"),
        budget = TaskBudget(tokenLimit = 30000),
        requirements = com.example.domain.core.task.TaskCapabilityRequirements(
            requiredCapabilities = required
        )
    )

    @Test
    fun `capabilities come from the live registry provider - not emptyList`() {
        val service = decisionService(
            capabilities = listOf(
                CapabilityDescriptor(
                    type = CapabilityType.SEARCH,
                    providerId = "multi_source_search",
                    resourceType = "SEARCH"
                ),
                CapabilityDescriptor(
                    type = CapabilityType.LLM_GENERATION,
                    providerId = "gemini",
                    resourceType = "LLM"
                )
            ),
            tools = listOf("workspace_file_tool", "workspace_summary")
        )

        val context = service.buildDecisionContext(task(), networkPolicy = NetworkPolicy.HYBRID)

        assertEquals(
            "The DecisionContext must carry the registry's capability descriptors",
            setOf(CapabilityType.SEARCH, CapabilityType.LLM_GENERATION),
            context.capabilities.map { it.type }.toSet()
        )
        assertEquals(
            "availableTools must list the registered adapter tools",
            listOf("workspace_file_tool", "workspace_summary"),
            context.availableTools
        )
    }

    @Test
    fun `the capability graph used for gap analysis is built from the live descriptors`() {
        val service = decisionService(
            capabilities = listOf(
                CapabilityDescriptor(
                    type = CapabilityType.TOOL_EXECUTION,
                    providerId = "workspace_file_tool",
                    resourceType = "TOOL"
                )
            ),
            tools = listOf("workspace_file_tool")
        )

        val context = service.buildDecisionContext(task())
        assertEquals(1, context.capabilityGraph.getResourcesProviding(CapabilityType.TOOL_EXECUTION).size)
        assertTrue(
            "a capability provided by a live resource must not be reported missing",
            context.capabilityGap.missingCapabilities.isEmpty()
        )
    }

    @Test
    fun `honest empty providers still produce a coherent (empty) context`() {
        val service = decisionService(capabilities = emptyList(), tools = emptyList())
        val context = service.buildDecisionContext(task())
        assertTrue(context.capabilities.isEmpty())
        assertTrue(context.availableTools.isEmpty())
    }
}
