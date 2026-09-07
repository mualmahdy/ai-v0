package com.example.domain.core.execution

/**
 * ============================================================================
 * Action Idempotency Protocol — gap-closure P0-06
 * ============================================================================
 *
 * Durable identity linking INTENTION -> SIDE EFFECT -> COMPLETION for every
 * paid / side-effectful action inside an execution.
 *
 * Before an action executes, its intent is recorded in the durable ledger
 * ([ActionIntentDao] — Room table `action_intents`). If the process dies
 * between the side effect and the checkpoint, the resume path sees the
 * intent's [state]:
 *  - [COMPLETED]  -> the action's side effect already happened; the resume
 *    REPLAYS the stored output fingerprint instead of re-executing
 *    (exactly-once semantics for side effects).
 *  - [INTENDED]   -> the action may or may not have started; the resume
 *    treats it as ambiguous and fails honestly for side-effectful actions
 *    (no blind re-run).
 *  - [FAILED]     -> the action definitively failed; the resume may retry
 *    through the normal decision loop.
 */
enum class ActionIntentState { INTENDED, COMPLETED, FAILED }

/**
 * Durable intent row. [actionKey] is the stable identity of one action
 * within one execution (step + type + target), so replay detection works
 * across process death.
 */
data class ActionIntent(
    val executionId: String,
    val actionKey: String,
    val actionType: String,
    val targetId: String?,
    val stepIndex: Int,
    val state: ActionIntentState = ActionIntentState.INTENDED,
    /** Deterministic fingerprint of the recorded outcome (output hash). */
    val outputFingerprint: String? = null,
    /** Short stored outcome summary used when replaying a completed action. */
    val outputSummary: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(executionId.isNotBlank()) { "executionId must not be blank" }
        require(actionKey.isNotBlank()) { "actionKey must not be blank" }
    }

    val isCompleted: Boolean get() = state == ActionIntentState.COMPLETED
}

/** Result of trying to open an action intent (idempotency gate). */
sealed interface IntentGate {
    /** Fresh intent — the caller may execute the action. */
    data class Proceed(val intent: ActionIntent) : IntentGate

    /** A COMPLETED intent already exists — replay, never re-execute. */
    data class AlreadyCompleted(val intent: ActionIntent) : IntentGate

    /** Ledger unavailable — exactly-once cannot be guaranteed. */
    data class LedgerUnavailable(val reason: String) : IntentGate
}
