package com.example.presentation.state

import com.example.domain.core.agent.AgentId
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.task.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ConversationTimelineTest — the PURE Chat Workspace presentation contracts
 * (Task 1 §5/§9): the execution-lifecycle projection (real events ONLY) and
 * the auto-scroll policy.
 * ============================================================================
 */
class ConversationTimelineTest {

    private val base = LiveExecutionState(executionId = "exec_1", startedAtMs = 0L)

    // ------------------------------------------------------------------
    // ExecutionLifecycleProjection — every phase maps to a REAL event
    // ------------------------------------------------------------------

    @Test
    fun `Started projects to EXECUTING`() {
        val next = ExecutionLifecycleProjection.apply(base, started())
        assertEquals(ExecutionPhase.EXECUTING, next.phase)
    }

    @Test
    fun `DecisionMade projects to PLANNING`() {
        val next = ExecutionLifecycleProjection.apply(base, decisionMade())
        assertEquals(ExecutionPhase.PLANNING, next.phase)
    }

    @Test
    fun `ContentChunk projects to STREAMING`() {
        val next = ExecutionLifecycleProjection.apply(base, chunk("أهلا"))
        assertEquals(ExecutionPhase.STREAMING, next.phase)
    }

    // ------------------------------------------------------------------
    // FRONTIER REASONING — the thinking lane is a REAL provider-reported
    // phase, distinct from answer streaming.
    // ------------------------------------------------------------------

    @Test
    fun `ReasoningChunk projects to THINKING`() {
        val next = ExecutionLifecycleProjection.apply(
            base,
            ExecutionEvent.ReasoningChunk("exec_1", "أفكر في المسألة", 0)
        )
        assertEquals(ExecutionPhase.THINKING, next.phase)
    }

    @Test
    fun `reasoning then content moves THINKING to STREAMING in order`() {
        val thinking = ExecutionLifecycleProjection.apply(
            base,
            ExecutionEvent.ReasoningChunk("exec_1", "خطوة تفكير", 0)
        )
        assertEquals(ExecutionPhase.THINKING, thinking.phase)
        val answering = ExecutionLifecycleProjection.apply(thinking, chunk("الجواب"))
        assertEquals(ExecutionPhase.STREAMING, answering.phase)
    }

    @Test
    fun `ActionStarted projects to EXECUTING with the real action label`() {
        val next = ExecutionLifecycleProjection.apply(base, actionStarted(stepIndex = 2))
        assertEquals(ExecutionPhase.EXECUTING, next.phase)
        // CHAT CAPABILITIES (Task 2 §14): the detail is the REAL decision
        // action's display name ("استعلام شبكي موثوق" for SEARCH, …) —
        // actual events, never chain-of-thought.
        val search = ExecutionLifecycleProjection.apply(
            base,
            ExecutionEvent.ActionStarted(
                executionId = "exec",
                action = com.example.domain.core.decision.DecisionAction(
                    type = com.example.domain.core.decision.DecisionActionType.SEARCH
                ),
                stepIndex = 0
            )
        )
        assertEquals(
            com.example.domain.core.decision.DecisionActionType.SEARCH.displayName,
            search.phaseDetail
        )
    }

    @Test
    fun `ToolRequested and ToolResult count tools`() {
        var live = ExecutionLifecycleProjection.apply(base, toolRequested("web_search"))
        assertEquals(1, live.toolCount)
        assertEquals("web_search", live.phaseDetail)
        live = ExecutionLifecycleProjection.apply(live, toolResult("web_search"))
        assertEquals(1, live.toolCount)
        assertEquals(ExecutionPhase.EXECUTING, live.phase)
    }

    @Test
    fun `ActionCompleted and ActionFailed count actions`() {
        var live = ExecutionLifecycleProjection.apply(base, actionCompleted())
        assertEquals(1, live.actionCount)
        live = ExecutionLifecycleProjection.apply(live, actionFailed())
        assertEquals(2, live.actionCount)
        // A failed ACTION is a step event, NOT a terminal execution state.
        assertEquals(ExecutionPhase.EXECUTING, live.phase)
    }

    @Test
    fun `BudgetGateDecision requires approval only for APPROVAL_REQUIRED`() {
        val approval = ExecutionLifecycleProjection.apply(
            base,
            ExecutionEvent.BudgetGateDecision("exec_1", "APPROVAL_REQUIRED", "تجاوز السقف")
        )
        assertEquals(ExecutionPhase.AWAITING_APPROVAL, approval.phase)

        val allowed = ExecutionLifecycleProjection.apply(
            base,
            ExecutionEvent.BudgetGateDecision("exec_1", "ALLOWED", "ضمن السقف")
        )
        assertEquals("The non-approval verdict keeps the current phase", ExecutionPhase.QUEUED, allowed.phase)
        assertEquals("ALLOWED", allowed.phaseDetail)
    }

    @Test
    fun `Degraded sets the degraded flags without changing the phase`() {
        val next = ExecutionLifecycleProjection.apply(
            ExecutionLifecycleProjection.apply(base, started()),
            ExecutionEvent.Degraded("exec_1", com.example.domain.core.DegradedReason.CACHE_FALLBACK, "تراجع")
        )
        assertTrue(next.isDegraded)
        assertEquals("تراجع", next.degradedMessage)
        assertEquals(ExecutionPhase.EXECUTING, next.phase)
    }

    @Test
    fun `Error and Completed project to their terminal phases`() {
        val failed = ExecutionLifecycleProjection.apply(base, ExecutionEvent.Error("exec_1", "X", "فشل"))
        assertEquals(ExecutionPhase.FAILED, failed.phase)

        val done = ExecutionLifecycleProjection.apply(base, ExecutionEvent.Completed("exec_1", "نص", 10))
        assertEquals(ExecutionPhase.COMPLETED, done.phase)
    }

    @Test
    fun `Cancelled projects to CANCELLED`() {
        val next = ExecutionLifecycleProjection.apply(base, ExecutionEvent.Cancelled("exec_1", "بواسطة المستخدم"))
        assertEquals(ExecutionPhase.CANCELLED, next.phase)
    }

    // ------------------------------------------------------------------
    // CHAT FINAL CLOSURE (§15) — deterministic projection timestamps: the
    // caller-supplied nowMs parameterizes the step clock for testing.
    // ------------------------------------------------------------------

    @Test
    fun `step timestamps honor the caller-supplied nowMs - the projection is deterministic`() {
        val fixedNow = 1_000L
        val live = ExecutionLifecycleProjection.apply(
            base,
            toolRequested("web_search"),
            nowMs = fixedNow
        )
        assertEquals(1, live.steps.size)
        assertEquals(fixedNow, live.steps.single().timestampMs)

        val later = ExecutionLifecycleProjection.apply(
            live,
            toolResult("web_search"),
            nowMs = fixedNow + 5_000
        )
        assertEquals(
            "each step carries ITS caller-supplied timestamp",
            listOf(fixedNow, fixedNow + 5_000),
            later.steps.map { it.timestampMs }
        )
    }

    @Test
    fun `telemetry events leave the lifecycle untouched`() {
        val telemetry = listOf(
            ExecutionEvent.UsageBudgetUpdate("exec_1", 10, 20, 30, 40),
            ExecutionEvent.CostRecorded(
                executionId = "exec_1", inputTokens = 1, outputTokens = 2, cachedTokens = 0,
                totalTokens = 3, costAmountMicro = null, currency = "USD",
                costStatus = "UNKNOWN", billingClass = "UNKNOWN"
            ),
            observation()
        )
        var live = ExecutionLifecycleProjection.apply(base, started())
        telemetry.forEach { event ->
            live = ExecutionLifecycleProjection.apply(live, event)
        }
        assertEquals(ExecutionPhase.EXECUTING, live.phase)
        assertEquals(0, live.toolCount)
        assertEquals(0, live.actionCount)
        assertFalse(live.isDegraded)
    }

    // ------------------------------------------------------------------
    // UI POLISH §10 — the expandable details' REAL step history
    // ------------------------------------------------------------------

    @Test
    fun `real events append their steps to the expandable history`() {
        var live = ExecutionLifecycleProjection.apply(base, started())
        live = ExecutionLifecycleProjection.apply(live, decisionMade())
        live = ExecutionLifecycleProjection.apply(live, actionStarted(stepIndex = 0))
        live = ExecutionLifecycleProjection.apply(live, actionCompleted())
        live = ExecutionLifecycleProjection.apply(live, toolRequested("http_fetch"))
        live = ExecutionLifecycleProjection.apply(live, toolResult("http_fetch"))

        assertEquals(5, live.steps.size)
        // The step labels are the REAL events' own labels (the decision's
        // display name, the action's output, the tool names) — never
        // chain-of-thought, never invented.
        assertEquals(DecisionActionType.EXECUTE_STEP.displayName, live.steps[0].label)
        assertEquals(DecisionActionType.EXECUTE_STEP.displayName, live.steps[1].label)
        assertTrue(live.steps[2].label.startsWith("تم:"))
        assertEquals("أداة: http_fetch", live.steps[3].label)
        assertEquals("نتيجة أداة: http_fetch", live.steps[4].label)
        // Every step carries its honest timestamp.
        live.steps.forEach { step -> assertTrue(step.timestampMs > 0L) }
    }

    @Test
    fun `telemetry events add no steps - the details stay real`() {
        var live = ExecutionLifecycleProjection.apply(base, started())
        live = ExecutionLifecycleProjection.apply(live, ExecutionEvent.UsageBudgetUpdate("exec_1", 1, 2, 3, 4))
        live = ExecutionLifecycleProjection.apply(live, observation())
        assertEquals(0, live.steps.size)
    }

    @Test
    fun `the step history is capped - the LAST steps stay visible`() {
        var live = ExecutionLifecycleProjection.apply(base, started())
        repeat(20) { index ->
            live = ExecutionLifecycleProjection.apply(live, toolRequested("tool_$index"))
        }
        assertEquals(14, live.steps.size)
        assertEquals("أداة: tool_19", live.steps.last().label)
        assertEquals("أداة: tool_6", live.steps.first().label)
    }

    @Test
    fun `blank step labels are dropped - no noise rows in the details`() {
        val live = ExecutionLifecycleProjection.apply(base, started())
        // A blank outputSummary would otherwise surface as an empty step.
        val completed = ExecutionEvent.ActionCompleted(
            executionId = "exec_1",
            action = action(),
            outputSummary = "",
            observation = observation().observation
        )
        val next = ExecutionLifecycleProjection.apply(live, completed)
        assertEquals(0, next.steps.size)
    }

    // ------------------------------------------------------------------
    // ChatAutoScrollPolicy (§9)
    // ------------------------------------------------------------------

    @Test
    fun `a list is near the bottom within the threshold`() {
        assertTrue(ChatAutoScrollPolicy.isNearBottom(lastVisibleIndex = 9, totalItems = 10))
        assertTrue(ChatAutoScrollPolicy.isNearBottom(lastVisibleIndex = 8, totalItems = 10, threshold = 2))
        assertFalse(ChatAutoScrollPolicy.isNearBottom(lastVisibleIndex = 5, totalItems = 10))
        // An empty list is trivially at the bottom.
        assertTrue(ChatAutoScrollPolicy.isNearBottom(lastVisibleIndex = 0, totalItems = 0))
    }

    @Test
    fun `following users keep following and a send always follows`() {
        assertTrue(ChatAutoScrollPolicy.shouldFollow(following = true, afterUserSend = false))
        assertTrue(ChatAutoScrollPolicy.shouldFollow(following = false, afterUserSend = true))
        assertFalse(ChatAutoScrollPolicy.shouldFollow(following = false, afterUserSend = false))
    }

    @Test
    fun `the unread affordance appears only for a reading user with new content`() {
        assertTrue(ChatAutoScrollPolicy.shouldShowUnreadAffordance(following = false, hasNewContent = true))
        assertFalse(ChatAutoScrollPolicy.shouldShowUnreadAffordance(following = true, hasNewContent = true))
        assertFalse(ChatAutoScrollPolicy.shouldShowUnreadAffordance(following = false, hasNewContent = false))
    }

    // ------------------------------------------------------------------
    // Helpers: minimal REAL event instances
    // ------------------------------------------------------------------

    private fun started() = ExecutionEvent.Started(
        executionId = "exec_1",
        agentId = AgentId("agent_x"),
        modelId = "model_x"
    )

    private fun action(type: DecisionActionType = DecisionActionType.EXECUTE_STEP) =
        DecisionAction(type = type, targetId = null)

    private fun decisionMade(): ExecutionEvent.DecisionMade {
        val decision = DecisionResult(
            chosenAction = action(),
            confidence = 0.9f,
            rationale = "السبب",
            stateSnapshot = DecisionState(taskId = TaskId("task_1")),
            evaluatedAlternatives = emptyList(),
            matchedHistoricalCasesCount = 0
        )
        return ExecutionEvent.DecisionMade("exec_1", decision)
    }

    private fun actionStarted(stepIndex: Int) = ExecutionEvent.ActionStarted(
        executionId = "exec_1",
        action = action(),
        stepIndex = stepIndex
    )

    private fun actionCompleted() = ExecutionEvent.ActionCompleted(
        executionId = "exec_1",
        action = action(),
        outputSummary = "تم",
        observation = observation().observation
    )

    private fun actionFailed() = ExecutionEvent.ActionFailed(
        executionId = "exec_1",
        action = action(),
        errorDescription = "خطأ",
        observation = observation().observation
    )

    private fun toolRequested(name: String) = ExecutionEvent.ToolRequested(
        executionId = "exec_1",
        callId = "call_1",
        toolName = name,
        argumentsJson = "{}"
    )

    private fun toolResult(name: String) = ExecutionEvent.ToolResult(
        executionId = "exec_1",
        callId = "call_1",
        toolName = name,
        outcome = com.example.domain.core.Outcome.Success("ok")
    )

    private fun chunk(text: String) = ExecutionEvent.ContentChunk(
        executionId = "exec_1",
        deltaText = text,
        sequenceIndex = 0
    )

    private fun observation() = ExecutionEvent.ObservationRecorded(
        executionId = "exec_1",
        observation = com.example.domain.core.decision.EnvironmentObservation(
            action = action(),
            isSuccess = true,
            actualLatencyMs = 10,
            tokensConsumed = 5
        ),
        updatedUncertainty = 0.2f
    )
}
