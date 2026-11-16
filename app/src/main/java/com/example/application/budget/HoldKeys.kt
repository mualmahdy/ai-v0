package com.example.application.budget

/**
 * ============================================================================
 * HoldKeys — the ONE reservation/accounting identity grammar (Stage 1A)
 * ============================================================================
 * Root cause this object closes: authorization wrote holds keyed by
 * `exec:pre_{taskId}_{step}` while settlement looked them up by
 * `exec:{executionId}` — two production grammars that never matched, so a
 * decide-time hold was never settled, never released and never expired.
 *
 * The canonical identity of ONE billable operation is the triple
 *
 *   (executionId, stepIndex, attempt)
 *
 *  - executionId — stable across resumes (P1-03);
 *  - stepIndex — one loop iteration = at most one billable dispatch, and
 *    every reconsideration consumes a NEW stepIndex, so separate billable
 *    steps and distinct retry attempts never collide on one reservation;
 *  - attempt — the canonical context's attempt counter, incremented on
 *    every durable resume: a re-dispatch after process death is a DISTINCT
 *    billable attempt (it can incur a fresh provider charge) and must not
 *    replay the pre-crash reservation.
 *
 * Replaying the SAME operation (decide-gate + LLM-step gate authorizing the
 * same step, or an idempotent re-entry) derives the SAME keys and is
 * deduplicated by the existing unique-index replay paths — never a second
 * hold.
 *
 * BACKWARD COMPATIBILITY CONTRACT: with `stepIndex == null` every function
 * reproduces the PRE-Stage-1A key formats byte-for-byte (reason
 * `exec:{executionId}`, auth key `auth:{executionId}:{scope}:{id}`, settle
 * key `settle:exec:{executionId}`, ...). Pre-existing persisted holds and
 * every existing caller therefore keep working unchanged; step-qualified
 * requests extend the grammar with an explicit, well-defined suffix. The
 * identity is carried by explicit request/input FIELDS — never by parsing
 * these strings.
 */
object HoldKeys {

    /**
     * Bounded reservation lifetime. 24h comfortably exceeds an execution
     * (the wall-clock timeout floor is 60s per task) plus any realistic
     * resume window, while guaranteeing an abandoned reservation stops
     * suppressing availability after at most one day — enforced LAZILY by
     * the balance query (`expires_at_epoch_ms IS NULL OR > now`), with no
     * dependence on a sweep, restart or cleanup worker. A subsequent
     * billable dispatch always authorizes FRESH (new step/attempt identity)
     * — expired reservations are never "renewed in place".
     */
    const val DEFAULT_HOLD_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /** `:step:{n}:attempt:{a}` — appended only for step-qualified identities. */
    fun stepSuffix(stepIndex: Int?, attempt: Int?): String =
        if (stepIndex == null) "" else ":step:$stepIndex:attempt:${(attempt ?: 1).coerceAtLeast(1)}"

    /** The hold `reason` — the settlement-side correlation lookup key. */
    fun holdReason(executionId: String, stepIndex: Int? = null, attempt: Int? = null): String =
        "exec:$executionId" + stepSuffix(stepIndex, attempt)

    /** authorizeAndReserve idempotency key (per scope, as before). */
    fun authorizeIdempotencyKey(
        executionId: String,
        stepIndex: Int?,
        attempt: Int?,
        scopeType: String,
        scopeId: String?
    ): String = "auth:$executionId" + stepSuffix(stepIndex, attempt) + ":$scopeType:$scopeId"

    /** settleHold idempotency key (positive-actual settlement). */
    fun settleKey(executionId: String, stepIndex: Int? = null, attempt: Int? = null): String =
        "settle:exec:$executionId" + stepSuffix(stepIndex, attempt)

    /** releaseHold idempotency key (remainder / zero-cost / undispatched). */
    fun releaseKey(executionId: String, stepIndex: Int? = null, attempt: Int? = null): String =
        "release:exec:$executionId" + stepSuffix(stepIndex, attempt)

    /** postUsageActual idempotency key when no hold survived. */
    fun actualKey(executionId: String, stepIndex: Int? = null, attempt: Int? = null): String =
        "actual:exec:$executionId" + stepSuffix(stepIndex, attempt)

    /** postUsageActual idempotency key for the I-11 overage remainder. */
    fun overageKey(executionId: String, stepIndex: Int? = null, attempt: Int? = null): String =
        actualKey(executionId, stepIndex, attempt) + ":overage"
}
