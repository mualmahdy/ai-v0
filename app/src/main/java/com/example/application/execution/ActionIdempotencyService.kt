package com.example.application.execution

import com.example.domain.core.execution.ActionIntent
import com.example.domain.core.execution.ActionIntentState
import com.example.domain.core.execution.IntentGate
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.infrastructure.persistence.dao.ActionIntentDao
import com.example.infrastructure.persistence.entities.ActionIntentEntity
import java.security.MessageDigest

/**
 * ============================================================================
 * ActionIdempotencyService — gap-closure P0-06 (Action Idempotency Protocol)
 * ============================================================================
 *
 * Durable bridge between the orchestrator and the `action_intents` ledger:
 *
 *   begin()   — records the INTENTION before a side-effectful action runs
 *               (insert-or-ignore). Returns AlreadyCompleted when this exact
 *               action already finished in a previous attempt — the caller
 *               must REPLAY the stored outcome instead of re-executing.
 *   complete()/fail() — records the outcome + fingerprint AFTER the action.
 *
 * Exactly-once recovery (P0-05) is then achievable: the resume path sees
 * COMPLETED intents and skips re-execution; INTENDED intents are ambiguous
 * and the caller decides honestly (fail-closed for side-effectful actions).
 *
 * When no ledger is wired (pure JVM tests) the gate reports
 * [IntentGate.LedgerUnavailable] and the orchestrator proceeds WITHOUT
 * exactly-once guarantees (documented, not hidden).
 */
class ActionIdempotencyService(private val dao: ActionIntentDao?) {

    /** Actions with durable side effects (files, paid calls, delegation). */
    val gatedActionTypes: Set<DecisionActionType> = setOf(
        DecisionActionType.EXECUTE_STEP,
        DecisionActionType.EXECUTE_TOOL,
        DecisionActionType.SEARCH,
        DecisionActionType.RETRIEVE_KNOWLEDGE,
        DecisionActionType.DELEGATE,
        DecisionActionType.SELECT_MODEL,
        DecisionActionType.RETRY
    )

    /** Stable identity of one action within one execution. */
    fun actionKey(executionId: String, stepIndex: Int, action: DecisionAction): String {
        val target = action.targetId ?: ""
        return "s$stepIndex|${action.type.name}|$target"
    }

    /**
     * Opens (or re-opens) the intent for this action. Insert-or-ignore keeps
     * the FIRST row authoritative: a resumed attempt sees the prior state.
     */
    suspend fun begin(
        executionId: String,
        stepIndex: Int,
        action: DecisionAction
    ): IntentGate {
        val ledger = dao ?: return IntentGate.LedgerUnavailable("ACTION_INTENT_LEDGER_NOT_CONFIGURED")
        return try {
            val key = actionKey(executionId, stepIndex, action)
            val now = System.currentTimeMillis()
            val row = ActionIntentEntity.fromDomain(
                ActionIntent(
                    executionId = executionId,
                    actionKey = key,
                    actionType = action.type.name,
                    targetId = action.targetId,
                    stepIndex = stepIndex,
                    state = ActionIntentState.INTENDED,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            ledger.insertIntent(row)
            val stored = ledger.getIntent(executionId, key)
            when {
                stored == null -> IntentGate.LedgerUnavailable("INTENT_NOT_READABLE")
                stored.toDomain().isCompleted -> IntentGate.AlreadyCompleted(stored.toDomain())
                else -> IntentGate.Proceed(stored.toDomain())
            }
        } catch (e: Exception) {
            IntentGate.LedgerUnavailable("INTENT_LEDGER_WRITE_FAILED: ${e.message}")
        }
    }

    /**
     * Records a completed side effect with a deterministic output fingerprint.
     *
     * GAP-13 (Design Closure 2026): returns whether the outcome row was
     * actually written. The caller EMITS a degradation event on `false` —
     * an outcome-recording failure is never silently swallowed (the
     * begin() row stays INTENDED and the resume path treats it as
     * ambiguous — exactly-once is weakened, which the user must see).
     */
    suspend fun complete(
        executionId: String,
        stepIndex: Int,
        action: DecisionAction,
        outputText: String
    ): Boolean {
        val ledger = dao ?: return true // no ledger wired — nothing to guarantee
        return try {
            ledger.updateIntentOutcome(
                executionId = executionId,
                actionKey = actionKey(executionId, stepIndex, action),
                state = ActionIntentState.COMPLETED.name,
                fingerprint = fingerprint(outputText),
                summary = outputText.take(200),
                now = System.currentTimeMillis()
            )
            true
        } catch (e: Exception) {
            // Outcome recording is best-effort; the begin() row (INTENDED)
            // stays authoritative and the resume path treats it as ambiguous.
            lastOutcomeWriteFailure = "INTENT_OUTCOME_WRITE_FAILED: ${e::class.simpleName}: ${e.message?.take(120)}"
            false
        }
    }

    /**
     * Records a definitively failed action (safe to retry through the loop).
     * GAP-13: same honest-return policy as [complete].
     */
    suspend fun fail(executionId: String, stepIndex: Int, action: DecisionAction, error: String): Boolean {
        val ledger = dao ?: return true
        return try {
            ledger.updateIntentOutcome(
                executionId = executionId,
                actionKey = actionKey(executionId, stepIndex, action),
                state = ActionIntentState.FAILED.name,
                fingerprint = null,
                summary = error.take(200),
                now = System.currentTimeMillis()
            )
            true
        } catch (e: Exception) {
            lastOutcomeWriteFailure = "INTENT_OUTCOME_WRITE_FAILED: ${e::class.simpleName}: ${e.message?.take(120)}"
            false
        }
    }

    /**
     * GAP-13: the most recent outcome-write failure (null = none) — exposed
     * for callers/diagnostics so a weakened exactly-once guarantee is
     * observable instead of invisible.
     */
    var lastOutcomeWriteFailure: String? = null
        private set

    /** Number of completed intents for an execution (replay statistics). */
    suspend fun completedCount(executionId: String): Int =
        runCatching { dao?.completedIntentCount(executionId) ?: 0 }.getOrDefault(0)

    /** Deterministic fingerprint (SHA-256, hex, 16 chars) of an action outcome. */
    fun fingerprint(output: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
