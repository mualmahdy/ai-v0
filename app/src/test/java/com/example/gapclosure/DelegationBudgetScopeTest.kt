package com.example.gapclosure

import com.example.application.decision.DecisionContext
import com.example.application.execution.ExecutionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * GAP-09 (Design Closure 2026) — DelegationBudgetScopeTest
 * ============================================================================
 *
 * The audit finding (ExecutionService.executeDelegation): the parent's
 * remaining budget was computed with TWO "safety floors" —
 * `(limit - consumed).coerceAtLeast(limit/4).coerceAtLeast(1000)` — so a
 * nearly-exhausted parent could grant a child MORE tokens than the parent
 * actually had left (a parent holding 200 remaining granted up to 500; a
 * fully-exhausted parent granted 500).
 *
 * The contract is now hard: the child's token limit can NEVER exceed the
 * parent's unspent remaining budget (child = min(remaining/2, 15000)).
 * A fully-exhausted parent delegates with a ZERO budget.
 */
class DelegationBudgetScopeTest {

    private lateinit var registry: ComponentRegistry

    private val parentAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent-parent"),
            name = "Parent",
            role = AgentRole.PLANNER,
            description = "parent",
            systemPrompt = "You coordinate."
        ),
        allowedCapabilities = setOf(com.example.domain.core.capability.CapabilityType.AGENT_DELEGATION),
        budget = com.example.domain.core.agent.AgentBudget(maxTokens = 30000)
    )

    private val childAgent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("agent-child"),
            name = "Child",
            role = AgentRole.CODER,
            description = "child",
            systemPrompt = "You code."
        ),
        allowedCapabilities = emptySet(),
        budget = com.example.domain.core.agent.AgentBudget(maxTokens = 30000)
    )

    /** Records the child task the delegation executor received. */
    private var capturedChildTask: TaskDefinition? = null

    @Before
    fun setup() {
        registry = ComponentRegistry()
    }

    private fun buildExecutionService(): ExecutionService {
        val es = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService()
        )
        es.registryAgentResolver = { id -> if (id == "agent-child") childAgent else null }
        es.delegationExecutor = { _, childTask ->
            capturedChildTask = childTask
            Outcome.Success("child done")
        }
        return es
    }

    private fun delegationAction(): DecisionAction = DecisionAction(
        type = DecisionActionType.DELEGATE,
        targetId = "agent-child",
        payload = mapOf("goal" to "code something")
    )

    private fun taskWithBudget(limit: Int, consumed: Int): TaskDefinition = TaskDefinition(
        id = TaskId("task-gap09"),
        assignedAgentId = parentAgent.identity.id,
        input = TaskInput(rawPrompt = "delegate work"),
        budget = TaskBudget(tokenLimit = limit, consumedTokens = consumed)
    )

    @Test
    fun `child budget is capped by the parent's ACTUAL remaining tokens`() = runBlocking {
        // Parent: 2000 limit, 1900 consumed → only 100 remaining.
        val es = buildExecutionService()
        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-gap09-a", "ws-a")) {
            es.executeAction(
                action = delegationAction(),
                context = DecisionContext(task = taskWithBudget(limit = 2000, consumed = 1900)),
                agent = parentAgent,
                executionId = "exec-gap09-a",
                onEvent = { }
            )
        }

        val child = capturedChildTask
        assertNotNull("the delegation executor must have been invoked", child)
        // remaining = 100 → child = min(100/2, 15000) = 50. The OLD floors
        // would have granted max(2000/4, 1000)=1000 → 500 (5x the truth).
        assertEquals(
            "GAP-09: child budget must be carved from the parent's ACTUAL remaining",
            50,
            child!!.budget.tokenLimit
        )
        assertTrue("the delegation itself must succeed", result.isSuccess)
    }

    @Test
    fun `fully exhausted parent delegates with a zero budget`() = runBlocking {
        val es = buildExecutionService()
        withContext(com.example.domain.core.execution.ExecutionScope("exec-gap09-b", "ws-b")) {
            es.executeAction(
                action = delegationAction(),
                context = DecisionContext(task = taskWithBudget(limit = 2000, consumed = 2000)),
                agent = parentAgent,
                executionId = "exec-gap09-b",
                onEvent = { }
            )
        }

        val child = capturedChildTask
        assertNotNull(child)
        assertEquals(
            "GAP-09: an exhausted parent must NOT fabricate spend authority for a child",
            0,
            child!!.budget.tokenLimit
        )
    }

    @Test
    fun `over-consumed parent clamps remaining at zero - never negative, never floored`() = runBlocking {
        val es = buildExecutionService()
        withContext(com.example.domain.core.execution.ExecutionScope("exec-gap09-c", "ws-c")) {
            es.executeAction(
                action = delegationAction(),
                context = DecisionContext(task = taskWithBudget(limit = 2000, consumed = 2500)),
                agent = parentAgent,
                executionId = "exec-gap09-c",
                onEvent = { }
            )
        }

        assertEquals(
            "GAP-09: negative remaining clamps to zero (child gets 0), not to a floor",
            0,
            capturedChildTask!!.budget.tokenLimit
        )
    }

    @Test
    fun `rich parent still splits the remaining budget in half with the 15000 cap`() = runBlocking {
        val es = buildExecutionService()
        withContext(com.example.domain.core.execution.ExecutionScope("exec-gap09-d", "ws-d")) {
            es.executeAction(
                action = delegationAction(),
                context = DecisionContext(task = taskWithBudget(limit = 100_000, consumed = 0)),
                agent = parentAgent,
                executionId = "exec-gap09-d",
                onEvent = { }
            )
        }

        // remaining = 100000 → min(100000/2, 15000) = 15000 (cap intact).
        assertEquals(15000, capturedChildTask!!.budget.tokenLimit)
    }

    @Test
    fun `delegation failure paths surface honest DELEGATE_REJECTED - not swallowed`() = runBlocking {
        val es = buildExecutionService()
        // Child agent NOT resolvable (resolver returns null for this id).
        val result = withContext(com.example.domain.core.execution.ExecutionScope("exec-gap09-e", "ws-e")) {
            es.executeAction(
                action = DecisionAction(
                    type = DecisionActionType.DELEGATE,
                    targetId = "agent-ghost",
                    payload = mapOf("goal" to "anything")
                ),
                context = DecisionContext(task = taskWithBudget(limit = 10_000, consumed = 0)),
                agent = parentAgent,
                executionId = "exec-gap09-e",
                onEvent = { }
            )
        }
        assertTrue(result.isSuccess.not())
        assertTrue(
            "unknown child must be rejected honestly: ${result.errorDescription}",
            result.errorDescription?.contains("DELEGATE_REJECTED") == true
        )
    }
}
