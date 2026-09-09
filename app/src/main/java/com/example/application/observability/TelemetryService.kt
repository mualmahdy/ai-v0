package com.example.application.observability

import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.core.observability.HealthProbe
import com.example.domain.core.observability.MetricDimensions
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricType
import com.example.domain.ports.observability.TelemetryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ============================================================================
 * TelemetryService — Phase 5 Observability (P1 remediation)
 * ============================================================================
 *
 * Domain-friendly facade over `TelemetryPort`. Where the port speaks in
 * generic `MetricSample`s, this service exposes typed operations that
 * match real runtime events: tool calls, provider calls, decisions,
 * executions, security evaluations.
 *
 * Also subscribes to the orchestrator's `ExecutionEvent` flow and turns
 * each event into (a) a `MetricSample` and (b) an `ExecutionTraceNode`
 * so the Unified Activity Feed has a unified, persistent view of what
 * happened during an execution — not just the in-memory stream that was
 * previously lost on every process restart.
 *
 * Closes the Observability gap (audit: 25–35% → ~55%) by:
 *   1. Wiring the previously-unused `ExecutionLogDao` into production.
 *   2. Adding aggregate metrics (counters, latency histograms, token usage,
 *      cost tracking, failure rates) per provider/tool/agent/workspace.
 *   3. Persisting execution trace nodes so the dashboard can show a
 *      structured trace timeline, not a flat log.
 *   4. Providing a `recordAudit` path for security decisions.
 */
class TelemetryService(
    private val telemetryPort: TelemetryPort,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * GOVERNANCE PHASE FIX (workspace isolation): the active workspace id —
     * previously `MetricDimensions.workspaceId` was NEVER populated, so all
     * metric rows were globally scoped. Late-bound wiring (AppContainer).
     */
    var workspaceIdProvider: (() -> String?)? = null
) {

    /** Standard counter increment. */
    suspend fun incrementCounter(
        action: String,
        dimensions: MetricDimensions,
        amount: Long = 1L
    ) {
        telemetryPort.record(
            MetricSample(
                type = MetricType.COUNTER,
                dimensions = dimensions.copy(actionType = action),
                value = amount
            )
        )
    }

    /** Record a latency observation (e.g. tool execution, provider call). */
    suspend fun recordLatency(
        dimensions: MetricDimensions,
        latencyMs: Long
    ) {
        telemetryPort.record(
            MetricSample(
                type = MetricType.LATENCY_HISTOGRAM,
                dimensions = dimensions,
                value = latencyMs
            )
        )
    }

    /** Record token consumption (prompt + completion). */
    suspend fun recordTokenUsage(
        dimensions: MetricDimensions,
        promptTokens: Int,
        completionTokens: Int,
        providerId: String
    ) {
        val promptSample = MetricSample(
            type = MetricType.TOKEN_USAGE,
            dimensions = dimensions.copy(providerId = providerId, actionType = "PROMPT"),
            value = promptTokens.toLong()
        )
        val completionSample = MetricSample(
            type = MetricType.TOKEN_USAGE,
            dimensions = dimensions.copy(providerId = providerId, actionType = "COMPLETION"),
            value = completionTokens.toLong()
        )
        telemetryPort.recordBatch(listOf(promptSample, completionSample))
    }

    /**
     * Record USD cost. Value is stored as Long (micro-dollars = 1e-6 USD)
     * to keep all metrics Long-typed. UI divides by 1e6 to display.
     */
    suspend fun recordCost(
        dimensions: MetricDimensions,
        costMicroUsd: Long
    ) {
        telemetryPort.record(
            MetricSample(
                type = MetricType.COST_USD,
                dimensions = dimensions,
                value = costMicroUsd
            )
        )
    }

    /** Record a failure (counts into the failure_rate bucket). */
    suspend fun recordFailure(
        dimensions: MetricDimensions,
        failureCode: String
    ) {
        telemetryPort.record(
            MetricSample(
                type = MetricType.FAILURE_RATE,
                dimensions = dimensions.copy(actionType = failureCode),
                value = 1L
            )
        )
    }

    /** Record a security audit decision. */
    suspend fun recordAudit(event: AuditEvent): Long = telemetryPort.recordAudit(event)

    /** Convenience overload for the most common audit shape. */
    suspend fun recordAudit(
        severity: AuditSeverity,
        actor: String,
        action: String,
        resourceType: String,
        resourceId: String,
        decision: String,
        reason: String,
        workspaceId: String? = null
    ): Long = telemetryPort.recordAudit(
        AuditEvent(
            id = UUID.randomUUID().toString(),
            severity = severity,
            actor = actor,
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            decision = decision,
            reason = reason,
            workspaceId = workspaceId
        )
    )

    /** Record a periodic health probe. */
    suspend fun recordHealthProbe(probe: HealthProbe) = telemetryPort.recordHealthProbe(probe)

    /** Record a single execution trace node. */
    suspend fun recordTraceNode(
        executionId: String,
        stepIndex: Int,
        actionType: String,
        agentId: String?,
        targetResourceId: String?,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long?,
        outcome: String,
        summary: String,
        observationSummary: String? = null,
        workspaceId: String? = null
    ) {
        val durationMs = completedAtEpochMs?.let { it - startedAtEpochMs }
        telemetryPort.recordTraceNode(
            com.example.domain.core.observability.ExecutionTraceNode(
                executionId = executionId,
                stepIndex = stepIndex,
                actionType = actionType,
                targetResourceId = targetResourceId,
                agentId = agentId,
                startedAtEpochMs = startedAtEpochMs,
                completedAtEpochMs = completedAtEpochMs,
                durationMs = durationMs,
                outcome = outcome,
                summary = summary,
                observationSummary = observationSummary,
                // P1-10: explicit workspace attribution on the durable trace row.
                workspaceId = workspaceId
            )
        )
    }

    /**
     * Subscribe the telemetry pipeline to the orchestrator's
     * `ExecutionEvent` flow. Each event becomes a trace node plus
     * appropriate metric samples. This is the bridge that turns the
     * previously-transient event stream into a persistent observability
     * surface.
     */
    fun subscribeToExecutionEvents(events: Flow<ExecutionEvent>) {
        scope.launch {
            events.collect { event ->
                try {
                    handle(event)
                } catch (_: Throwable) {
                    // Observability must NEVER break the runtime.
                }
            }
        }
    }

    /**
     * GAP-CLOSURE P0-02: execution → workspace attribution binding.
     * `ExecutionEvent.Started` now carries the execution's PINNED workspace
     * id; every later event of the SAME executionId is attributed to THAT
     * workspace — not to whichever workspace happens to be active when the
     * event lands (mid-run workspace switching no longer moves telemetry
     * attribution across workspaces).
     */
    private val executionWorkspaceBinding = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun resolveWorkspaceFor(event: ExecutionEvent): String? {
        if (event is ExecutionEvent.Started) {
            event.workspaceId?.let { pinned ->
                executionWorkspaceBinding[event.executionId] = pinned
                return pinned
            }
        }
        return executionWorkspaceBinding[event.executionId]
            ?: runCatching { workspaceIdProvider?.invoke() }.getOrNull()
    }

    private suspend fun handle(event: ExecutionEvent) {
        val dims = MetricDimensions(
            executionId = event.executionId,
            sessionId = null,
            // GOVERNANCE PHASE + P0-02: pinned execution→workspace binding.
            workspaceId = resolveWorkspaceFor(event)
        )
        when (event) {
            is ExecutionEvent.Started -> {
                incrementCounter("EXECUTION_STARTED", dims.copy(agentId = event.agentId.value))
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = -1,
                    actionType = "STARTED",
                    agentId = event.agentId.value,
                    targetResourceId = event.modelId,
                    startedAtEpochMs = event.timestampMs,
                    completedAtEpochMs = null,
                    outcome = "STARTED",
                    summary = "بدء التنفيذ بالوكيل ${event.agentId.value} عبر ${event.modelId}",
                    workspaceId = dims.workspaceId
                )
            }
            is ExecutionEvent.DecisionMade -> {
                incrementCounter("DECISION_MADE", dims.copy(actionType = event.decision.chosenAction.type.name))
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = -1,
                    actionType = "DECISION",
                    agentId = null,
                    targetResourceId = event.decision.chosenAction.targetId,
                    startedAtEpochMs = event.timestampMs,
                    completedAtEpochMs = event.timestampMs,
                    outcome = "DECISION",
                    summary = "قرار: ${event.decision.chosenAction.type.name} → ${event.decision.chosenAction.targetId ?: "-"}",
                    workspaceId = dims.workspaceId
                )
            }
            is ExecutionEvent.ActionStarted -> {
                incrementCounter("ACTION_STARTED", dims.copy(actionType = event.action.type.name))
            }
            is ExecutionEvent.ActionCompleted -> {
                incrementCounter("ACTION_COMPLETED", dims.copy(actionType = event.action.type.name))
                recordLatency(dims.copy(actionType = event.action.type.name), event.observation.actualLatencyMs)
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = event.observation.stepIndex,
                    actionType = event.action.type.name,
                    agentId = null,
                    targetResourceId = event.action.targetId,
                    startedAtEpochMs = event.timestampMs - event.observation.actualLatencyMs,
                    completedAtEpochMs = event.timestampMs,
                    outcome = "SUCCESS",
                    summary = event.outputSummary,
                    observationSummary = event.observation.action.type.code,
                    workspaceId = dims.workspaceId
                )
            }
            is ExecutionEvent.ActionFailed -> {
                recordFailure(dims.copy(actionType = event.action.type.name), "ACTION_FAILED")
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = event.observation.stepIndex,
                    actionType = event.action.type.name,
                    agentId = null,
                    targetResourceId = event.action.targetId,
                    startedAtEpochMs = event.timestampMs - event.observation.actualLatencyMs,
                    completedAtEpochMs = event.timestampMs,
                    outcome = "FAILURE",
                    summary = event.errorDescription,
                    observationSummary = event.observation.action.type.code,
                    workspaceId = dims.workspaceId
                )
            }
            is ExecutionEvent.ObservationRecorded -> {
                incrementCounter("OBSERVATION_RECORDED", dims)
            }
            is ExecutionEvent.Replanned -> {
                incrementCounter("REPLANNED", dims.copy(actionType = event.reason))
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = event.stepIndex,
                    actionType = "REPLANNED",
                    agentId = null,
                    targetResourceId = null,
                    startedAtEpochMs = event.timestampMs,
                    completedAtEpochMs = event.timestampMs,
                    outcome = "REPLANNED",
                    summary = event.reason,
                    workspaceId = dims.workspaceId
                )
            }
            is ExecutionEvent.ContentChunk -> {
                // High-frequency; we just bump a counter so we know how many chunks were emitted.
                incrementCounter("CONTENT_CHUNK", dims)
            }
            is ExecutionEvent.ToolRequested -> {
                incrementCounter("TOOL_REQUESTED", dims.copy(toolName = event.toolName))
            }
            is ExecutionEvent.ToolResult -> {
                val outcome = when (event.outcome) {
                    is com.example.domain.core.Outcome.Success<*> -> "SUCCESS"
                    is com.example.domain.core.Outcome.Degraded<*, *> -> "DEGRADED"
                    is com.example.domain.core.Outcome.Error<*> -> "FAILURE"
                }
                incrementCounter("TOOL_RESULT", dims.copy(toolName = event.toolName, actionType = outcome))
                if (outcome == "FAILURE") {
                    recordFailure(dims.copy(toolName = event.toolName), "TOOL_FAILURE")
                }
            }
            is ExecutionEvent.Degraded -> {
                incrementCounter("DEGRADED", dims.copy(actionType = event.reason.code))
            }
            is ExecutionEvent.Error -> {
                recordFailure(dims.copy(actionType = event.failureCode), "EXECUTION_ERROR")
                if (event.isFatal) {
                    recordAudit(
                        AuditSeverity.ERROR,
                        actor = "orchestrator",
                        action = "execution_error",
                        resourceType = "execution",
                        resourceId = event.executionId,
                        decision = "ERROR",
                        reason = event.message
                    )
                }
            }
            is ExecutionEvent.UsageBudgetUpdate -> {
                recordTokenUsage(
                    dims,
                    promptTokens = event.promptTokens,
                    completionTokens = event.completionTokens,
                    // GOVERNANCE PHASE FIX: real provider attribution
                    // (previously the hard-coded string "unknown").
                    providerId = event.providerId ?: "unknown"
                )
            }
            is ExecutionEvent.BudgetGateDecision -> {
                incrementCounter(
                    "BUDGET_GATE_${event.decision}",
                    dims.copy(providerId = event.providerId)
                )
                if (event.decision == "DENIED") {
                    recordAudit(
                        AuditSeverity.WARN,
                        actor = "economic_governance",
                        action = "budget_gate_denied",
                        resourceType = "execution",
                        resourceId = event.executionId,
                        decision = "DENY",
                        reason = event.reason,
                        workspaceId = dims.workspaceId
                    )
                }
            }
            is ExecutionEvent.CostRecorded -> {
                // Ledger rows already persisted by EconomicGovernanceService;
                // here we keep the metrics surface in sync (COST_USD only for
                // USD — no silent currency normalization).
                if (event.costAmountMicro != null && event.currency == "USD") {
                    recordCost(
                        dims.copy(providerId = event.providerId),
                        costMicroUsd = event.costAmountMicro
                    )
                }
            }
            is ExecutionEvent.RateLimitEncountered -> {
                recordFailure(
                    dims.copy(providerId = event.providerId, actionType = "RATE_LIMITED"),
                    "RATE_LIMIT_ENCOUNTERED"
                )
            }
            is ExecutionEvent.Completed -> {
                incrementCounter("EXECUTION_COMPLETED", dims)
                recordLatency(dims, event.totalDurationMs)
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = Int.MAX_VALUE,
                    actionType = "COMPLETED",
                    agentId = null,
                    targetResourceId = null,
                    startedAtEpochMs = event.timestampMs - event.totalDurationMs,
                    completedAtEpochMs = event.timestampMs,
                    outcome = if (event.isDegraded) "DEGRADED" else "SUCCESS",
                    summary = event.finalText.take(200),
                    observationSummary = event.degradedReason?.userFriendlyLabel,
                    workspaceId = dims.workspaceId
                )
            }
            is ExecutionEvent.Cancelled -> {
                incrementCounter("EXECUTION_CANCELLED", dims.copy(actionType = event.reason))
                recordTraceNode(
                    executionId = event.executionId,
                    stepIndex = Int.MAX_VALUE,
                    actionType = "CANCELLED",
                    agentId = null,
                    targetResourceId = null,
                    startedAtEpochMs = event.timestampMs,
                    completedAtEpochMs = event.timestampMs,
                    outcome = "CANCELLED",
                    summary = event.reason,
                    workspaceId = dims.workspaceId
                )
            }
        }
    }
}
