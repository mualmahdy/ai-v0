package com.example.application.agent

import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.task.AutonomyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — AgentLifecycleService unit tests.
 *
 * Closes the test-coverage aspect of P5-P0-07 (Agent Intelligence):
 * proves the lifecycle state machine, budget enforcement, autonomy
 * evaluation, and validation all work as designed.
 */
class AgentLifecycleServiceTest {

    private val service = AgentLifecycleService(memoryLifecyclePort = null)

    @Test
    fun `registerOrUpdate creates a new agent in CREATED state with version 1_0_0`() = kotlinx.coroutines.runBlocking {
        val version = service.registerOrUpdate(makeDefinition("agent_test_1"))
        assertEquals(1, version.major)
        assertEquals(0, version.minor)
        assertEquals(0, version.patch)
        val state = service.currentState(AgentId("agent_test_1"))
        assertNotNull(state)
        assertEquals(com.example.domain.core.agent.lifecycle.AgentLifecycleState.CREATED, state!!.lifecycleState)
    }

    @Test
    fun `transition from CREATED to INITIALIZED is valid; from CREATED to RUNNING is invalid`() = kotlinx.coroutines.runBlocking {
        service.registerOrUpdate(makeDefinition("agent_test_2"))
        val valid = service.transition(AgentId("agent_test_2"), com.example.domain.core.agent.lifecycle.AgentLifecycleState.INITIALIZED)
        assertTrue(valid)
        val invalid = service.transition(AgentId("agent_test_2"), com.example.domain.core.agent.lifecycle.AgentLifecycleState.RUNNING)
        assertFalse(invalid)
    }

    @Test
    fun `evaluateAutonomy with ASSISTED always requires consent`() {
        val eval = service.evaluateAutonomy(
            AgentId("a"), AutonomyPolicy.ASSISTED, "ANY_ACTION", isSensitiveTool = false
        )
        assertFalse(eval.isAllowed)
        assertTrue(eval.requireHumanConsent)
    }

    @Test
    fun `evaluateAutonomy with AUTONOMOUS never requires consent`() {
        val eval = service.evaluateAutonomy(
            AgentId("a"), AutonomyPolicy.AUTONOMOUS, "ANY_ACTION", isSensitiveTool = true
        )
        assertTrue(eval.isAllowed)
        assertFalse(eval.requireHumanConsent)
    }

    @Test
    fun `evaluateAutonomy with SUPERVISED requires consent only for sensitive tools`() {
        val nonSensitive = service.evaluateAutonomy(
            AgentId("a"), AutonomyPolicy.SUPERVISED, "READ", isSensitiveTool = false
        )
        assertTrue(nonSensitive.isAllowed)
        assertFalse(nonSensitive.requireHumanConsent)

        val sensitive = service.evaluateAutonomy(
            AgentId("a"), AutonomyPolicy.SUPERVISED, "WRITE_FILE", isSensitiveTool = true
        )
        assertFalse(sensitive.isAllowed)
        assertTrue(sensitive.requireHumanConsent)
    }

    @Test
    fun `evaluateBudget blocks when budget is depleted`() {
        val depleted = AgentBudget(maxTokens = 100, usedTokens = 100, inFlightTokens = 0)
        val eval = service.evaluateBudget(AgentId("a"), depleted, estimatedTokensForAction = 10)
        assertTrue(eval.isDepleted)
        assertEquals(com.example.domain.core.agent.lifecycle.BudgetRecommendedAction.BLOCK, eval.recommendedAction)
    }

    @Test
    fun `evaluateBudget throttles when approaching limit`() {
        val almost = AgentBudget(maxTokens = 100, usedTokens = 90, inFlightTokens = 0)
        val eval = service.evaluateBudget(AgentId("a"), almost, estimatedTokensForAction = 5)
        assertTrue(eval.isApproachingLimit)
        assertEquals(com.example.domain.core.agent.lifecycle.BudgetRecommendedAction.THROTTLE, eval.recommendedAction)
    }

    @Test
    fun `evaluateBudget proceeds when budget is healthy`() {
        val healthy = AgentBudget(maxTokens = 1000, usedTokens = 100, inFlightTokens = 0)
        val eval = service.evaluateBudget(AgentId("a"), healthy, estimatedTokensForAction = 50)
        assertFalse(eval.isDepleted)
        assertEquals(com.example.domain.core.agent.lifecycle.BudgetRecommendedAction.PROCEED, eval.recommendedAction)
    }

    @Test
    fun `validateForDeployment flags empty identity fields`() {
        val bad = makeDefinition("agent_bad").copy(
            identity = AgentIdentity(
                id = AgentId("agent_bad"),
                name = "",
                role = AgentRole.GENERAL_ASSISTANT,
                description = "",
                systemPrompt = ""
            )
        )
        val result = service.validateForDeployment(bad)
        assertFalse(result.isValid)
        assertTrue(result.identityIssues.any { it.contains("اسم") })
        assertTrue(result.identityIssues.any { it.contains("system prompt") })
    }

    @Test
    fun `validateForDeployment flags Coder agent missing TOOL_EXECUTION`() {
        val coder = makeDefinition("agent_coder").copy(
            identity = AgentIdentity(
                id = AgentId("agent_coder"),
                name = "Coder",
                role = AgentRole.CODER,
                description = "desc",
                systemPrompt = "prompt"
            ),
            allowedCapabilities = setOf(CapabilityType.LLM_GENERATION) // missing TOOL_EXECUTION
        )
        val result = service.validateForDeployment(coder)
        assertTrue(result.capabilityIssues.any { it.contains("TOOL_EXECUTION") })
    }

    @Test
    fun `validateForDeployment passes for a well-formed agent`() {
        val good = makeDefinition("agent_good")
        val result = service.validateForDeployment(good)
        assertTrue(result.allIssues.isEmpty())
        assertTrue(result.isValid)
    }

    private fun makeDefinition(id: String): AgentDefinition {
        return AgentDefinition(
            identity = AgentIdentity(
                id = AgentId(id),
                name = "Test Agent",
                role = AgentRole.GENERAL_ASSISTANT,
                description = "Test description",
                systemPrompt = "Test system prompt"
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.MEMORY_RETRIEVAL
            ),
            budget = AgentBudget(maxTokens = 30000)
        )
    }
}
