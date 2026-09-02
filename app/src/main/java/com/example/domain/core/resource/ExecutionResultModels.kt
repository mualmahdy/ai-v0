package com.example.domain.core.resource

/**
 * P0.5 — ExecutionResult (APPROVED-BASELINE v2.1, Section F — LOCKED).
 *
 * MANDATORY INVARIANT (ExecutionService, Section F):
 *   result.executedResourceId == decision.selectedResourceId
 *   OR execution failed
 *   OR a new DecisionRecord was created by the orchestrator (replan)
 *     and THAT record was executed.
 *
 * There is NO code path in which execution substitutes a resource.
 */
enum class ExecutionOutcome {
    SUCCESS,
    FAILURE,
    REPLAN_REQUESTED
}

data class ExecutionResult(
    val decisionId: String,
    /** == decision.selectedResourceId on SUCCESS; the intended resource on FAILURE. */
    val executedResourceId: ResourceId,
    val outcome: ExecutionOutcome,
    val output: Any?,
    val latencyMs: Long,
    /** Non-null only on transport/resolution failure. Machine-readable failure codes:
     *  resource_unresolvable | runtime_unsupported | resource_not_usable |
     *  adapter_binding_failed | governance_blocked | execution_error */
    val transportError: String?,
    val timestamp: Long
) {
    companion object {
        /** Factory guaranteeing the identity invariant on the success path (asserted by callers). */
        fun success(
            decision: DecisionRecord,
            output: Any?,
            latencyMs: Long
        ): ExecutionResult {
            require(decision.executionPermitted()) { "Cannot produce success for an unpermitted decision" }
            return ExecutionResult(
                decisionId = decision.decisionId,
                executedResourceId = decision.selectedResourceId,
                outcome = ExecutionOutcome.SUCCESS,
                output = output,
                latencyMs = latencyMs,
                transportError = null,
                timestamp = System.currentTimeMillis()
            )
        }

        /** Explicit failure factory: executedResourceId records the INTENDED resource. */
        fun failure(
            decision: DecisionRecord,
            transportError: String,
            latencyMs: Long = 0L,
            output: Any? = null
        ): ExecutionResult = ExecutionResult(
            decisionId = decision.decisionId,
            executedResourceId = decision.selectedResourceId,
            outcome = ExecutionOutcome.FAILURE,
            output = output,
            latencyMs = latencyMs,
            transportError = transportError,
            timestamp = System.currentTimeMillis()
        )
    }
}
