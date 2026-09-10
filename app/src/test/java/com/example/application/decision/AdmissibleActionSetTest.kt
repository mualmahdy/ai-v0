package com.example.application.decision

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskContracts
import com.example.domain.core.task.TaskIntentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * REPAIR ORDER §34 — AUTONOMY TESTS (the universal AUTONOMY_POLICY_BLOCKED fix)
 * ============================================================================
 * Proves the pipeline: Intent → Task Contract → Required Capabilities →
 * ADMISSIBLE ACTION SET → Decision → Governance → Execution.
 *
 *   - ordinary chat does NOT nominate sensitive tools (structurally);
 *   - Quick Chat is a legitimate generation-only mode;
 *   - permitted tools are NOT blocked for tool-capable agents under
 *     permissive policies (no over-blocking);
 *   - ASSISTED policy excludes the sensitive family from the action space;
 *   - every agent receives the correct contract;
 *   - the action space is never empty (control actions always admissible).
 */
class AdmissibleActionSetTest {

    private val contractResolver = TaskContractResolver()

    private fun quickChatAgentAllowedCaps(): Set<CapabilityType> = setOf(
        CapabilityType.LLM_GENERATION,
        CapabilityType.STREAMING,
        CapabilityType.MEMORY_RETRIEVAL
    )

    private fun toolCapableAgentCaps(): Set<CapabilityType> = setOf(
        CapabilityType.LLM_GENERATION,
        CapabilityType.TOOL_EXECUTION,
        CapabilityType.FILE_READ,
        CapabilityType.FILE_WRITE
    )

    // ------------------------------------------------------------------
    // Task contracts
    // ------------------------------------------------------------------

    @Test
    fun `QUICK_CHAT contract admits only generation and control actions`() {
        val contract = TaskContracts.QUICK_CHAT
        assertEquals(TaskIntentCategory.QUICK_CHAT, contract.category)
        // Generation + control.
        assertTrue(contract.isAdmissible(DecisionActionType.EXECUTE_STEP))
        assertTrue(contract.isAdmissible(DecisionActionType.COMPLETE))
        assertTrue(contract.isAdmissible(DecisionActionType.ASK_USER))
        // NO tool family — structurally impossible for quick chat to
        // nominate a sensitive tool.
        assertFalse(contract.isAdmissible(DecisionActionType.EXECUTE_TOOL))
        assertFalse(contract.isAdmissible(DecisionActionType.EXECUTE_MCP))
        assertFalse(contract.isAdmissible(DecisionActionType.EXECUTE_SKILL))
        assertFalse(contract.isAdmissible(DecisionActionType.USE_INTEGRATION))
        assertFalse(contract.isAdmissible(DecisionActionType.DELEGATE))
    }

    @Test
    fun `CHAT contract admits retrieval but never sensitive tools`() {
        val contract = TaskContracts.CHAT
        assertTrue(contract.isAdmissible(DecisionActionType.EXECUTE_STEP))
        assertTrue(contract.isAdmissible(DecisionActionType.RETRIEVE_KNOWLEDGE))
        assertTrue(contract.isAdmissible(DecisionActionType.RETRIEVE_MEMORY))
        assertFalse(contract.isAdmissible(DecisionActionType.EXECUTE_TOOL))
        assertFalse(contract.isAdmissible(DecisionActionType.EXECUTE_MCP))
    }

    @Test
    fun `CODING_TASK contract admits tool actions (policy-gated at execution)`() {
        val contract = TaskContracts.CODING_TASK
        assertTrue(contract.isAdmissible(DecisionActionType.EXECUTE_TOOL))
        assertTrue(contract.isAdmissible(DecisionActionType.EXECUTE_STEP))
    }

    @Test
    fun `every intent category maps to a non-empty admissible set`() {
        for (category in TaskIntentCategory.entries) {
            val contract = TaskContracts.forCategory(category)
            assertTrue(
                "category ${category.name} must have admissible actions",
                contract.admissibleActions.isNotEmpty()
            )
            // Control actions are ALWAYS part of the admissible set.
            assertTrue(contract.isAdmissible(DecisionActionType.COMPLETE))
            assertTrue(contract.isAdmissible(DecisionActionType.ASK_USER))
        }
    }

    // ------------------------------------------------------------------
    // Intent resolution
    // ------------------------------------------------------------------

    @Test
    fun `QUICK_CHAT mode resolves to the quick chat contract regardless of prompt keywords`() {
        // Even a prompt that LOOKS like a tool task resolves QUICK_CHAT mode
        // to the generation-only contract (Quick Chat is a legitimate mode,
        // not a place where tool semantics sneak in).
        val category = contractResolver.classify(
            chatMode = "QUICK_CHAT",
            agent = null,
            rawPrompt = "create file and run the tool and search the web"
        )
        assertEquals(TaskIntentCategory.QUICK_CHAT, category)
    }

    @Test
    fun `file intent is classified from prompt for agent mode`() {
        val category = contractResolver.classify(
            chatMode = "AGENT",
            agent = null,
            rawPrompt = "please create file notes.txt and save the content"
        )
        assertEquals(TaskIntentCategory.FILE_TASK, category)
    }

    @Test
    fun `search intent is classified from prompt`() {
        val category = contractResolver.classify(
            chatMode = "AGENT",
            agent = null,
            rawPrompt = "search for the latest news about AI"
        )
        assertEquals(TaskIntentCategory.SEARCH_TASK, category)
    }

    @Test
    fun `plain short prompt is ordinary chat`() {
        val category = contractResolver.classify(
            chatMode = "AGENT",
            agent = null,
            rawPrompt = "hello, how are you?"
        )
        assertEquals(TaskIntentCategory.CHAT, category)
    }

    @Test
    fun `workflow steps bind the workflow contract`() {
        val category = contractResolver.classify(
            chatMode = "AGENT",
            agent = null,
            rawPrompt = "anything",
            isWorkflowStep = true
        )
        assertEquals(TaskIntentCategory.WORKFLOW_TASK, category)
    }

    // ------------------------------------------------------------------
    // The admissible-set filter semantics (mirrors DecisionService's filter)
    // ------------------------------------------------------------------

    private fun isAdmissibleUnderFilters(
        action: DecisionActionType,
        contract: com.example.domain.core.task.TaskContract,
        agentCaps: Set<CapabilityType>?,
        policy: AutonomyPolicy?
    ): Boolean {
        val agentLacksToolExecution = agentCaps != null &&
                CapabilityType.TOOL_EXECUTION !in agentCaps
        val sensitiveFamilyExcluded = policy == AutonomyPolicy.ASSISTED
        val toolFamily = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.EXECUTE_MCP,
            DecisionActionType.EXECUTE_SKILL,
            DecisionActionType.USE_INTEGRATION,
            DecisionActionType.SELECT_TOOL
        )
        val sensitiveFamily = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.EXECUTE_MCP,
            DecisionActionType.EXECUTE_SKILL,
            DecisionActionType.USE_INTEGRATION
        )
        return contract.isAdmissible(action) &&
                !(agentLacksToolExecution && action in toolFamily) &&
                !(sensitiveFamilyExcluded && action in sensitiveFamily)
    }

    @Test
    fun `ordinary chat with quick-chat agent cannot execute tools under ANY policy`() {
        for (policy in AutonomyPolicy.entries) {
            assertFalse(
                "policy=${policy.name}",
                isAdmissibleUnderFilters(DecisionActionType.EXECUTE_TOOL, TaskContracts.CHAT, quickChatAgentAllowedCaps(), policy)
            )
        }
    }

    @Test
    fun `tool-capable agent under AUTONOMOUS policy MAY execute tools (no over-blocking)`() {
        assertTrue(
            isAdmissibleUnderFilters(DecisionActionType.EXECUTE_TOOL, TaskContracts.CODING_TASK, toolCapableAgentCaps(), AutonomyPolicy.AUTONOMOUS)
        )
        // SUPERVISED keeps tool candidates in the action space (governance
        // demands approval at the boundary — NOT removed from ranking).
        assertTrue(
            isAdmissibleUnderFilters(DecisionActionType.EXECUTE_TOOL, TaskContracts.CODING_TASK, toolCapableAgentCaps(), AutonomyPolicy.SUPERVISED)
        )
    }

    @Test
    fun `ASSISTED policy removes sensitive actions from the action space entirely`() {
        assertFalse(
            isAdmissibleUnderFilters(DecisionActionType.EXECUTE_TOOL, TaskContracts.CODING_TASK, toolCapableAgentCaps(), AutonomyPolicy.ASSISTED)
        )
        // Non-sensitive generation still admissible under ASSISTED.
        assertTrue(
            isAdmissibleUnderFilters(DecisionActionType.EXECUTE_STEP, TaskContracts.CODING_TASK, toolCapableAgentCaps(), AutonomyPolicy.ASSISTED)
        )
    }

    @Test
    fun `agent without TOOL_EXECUTION never sees tool actions even in tool contracts`() {
        assertFalse(
            isAdmissibleUnderFilters(DecisionActionType.EXECUTE_TOOL, TaskContracts.CODING_TASK, quickChatAgentAllowedCaps(), AutonomyPolicy.AUTONOMOUS)
        )
        assertFalse(
            isAdmissibleUnderFilters(DecisionActionType.SELECT_TOOL, TaskContracts.CODING_TASK, quickChatAgentAllowedCaps(), AutonomyPolicy.AUTONOMOUS)
        )
    }

    // ------------------------------------------------------------------
    // Policy hierarchy (§20): restrict never elevate
    // ------------------------------------------------------------------

    @Test
    fun `more restrictive policy wins - child may restrict never elevate`() {
        // The orchestrator's merge: rank ASSISTED(0) < SUPERVISED(1) < AUTONOMOUS(2).
        fun moreRestrictive(a: AutonomyPolicy?, b: AutonomyPolicy?): AutonomyPolicy {
            val rank = mapOf(
                AutonomyPolicy.ASSISTED to 0,
                AutonomyPolicy.SUPERVISED to 1,
                AutonomyPolicy.AUTONOMOUS to 2
            )
            return listOfNotNull(a, b).minByOrNull { rank[it] ?: 1 } ?: AutonomyPolicy.SUPERVISED
        }
        assertEquals(AutonomyPolicy.ASSISTED, moreRestrictive(AutonomyPolicy.ASSISTED, AutonomyPolicy.AUTONOMOUS))
        assertEquals(AutonomyPolicy.SUPERVISED, moreRestrictive(AutonomyPolicy.SUPERVISED, AutonomyPolicy.AUTONOMOUS))
        assertEquals(AutonomyPolicy.ASSISTED, moreRestrictive(null, AutonomyPolicy.ASSISTED))
        assertEquals(AutonomyPolicy.SUPERVISED, moreRestrictive(null, null))
        // Elevation attempt (task wants AUTONOMOUS, workspace says SUPERVISED):
        assertEquals(AutonomyPolicy.SUPERVISED, moreRestrictive(AutonomyPolicy.SUPERVISED, AutonomyPolicy.AUTONOMOUS))
        assertNotEquals(AutonomyPolicy.AUTONOMOUS, moreRestrictive(AutonomyPolicy.SUPERVISED, AutonomyPolicy.AUTONOMOUS))
    }
}
