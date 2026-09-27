package com.example.application.outcome

import com.example.application.execution.ExecutionResult
import com.example.domain.core.capability.CapabilityEvidenceRegistry
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.task.AcceptanceCriterion
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.VerificationStrategy

/**
 * High-level Action Outcome classifications.
 */
enum class ActionOutcomeType {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE,
    UNAVAILABLE,
    BLOCKED,
    WAITING,
    CANCELLED
}

/**
 * High-level Task Verification Outcome classifications (Rule 14).
 */
enum class VerificationOutcomeStatus {
    VERIFIED,
    PARTIALLY_VERIFIED,
    FAILED,
    INCONCLUSIVE
}

/**
 * CLOSURE P0-6 (audit §5.5/C3): the TYPED reason a verification requirement
 * is not satisfied. Strategy evaluation compares THESE kinds — never the
 * wording of the human-readable messages (the previous
 * `missing.none { it.contains("evidence") }` / `contains("Criterion")`
 * matching was wording-decides-semantic-state: a reworded or translated
 * message could flip a VERIFIED verdict).
 */
enum class VerificationGapKind {
    /** A terminal routing action (SELECT_AGENT/SELECT_TOOL) claimed completion. */
    INTERMEDIATE_ROUTING_ACTION,

    /** An unrecovered execution error was observed (typed signal, not text prefix). */
    UNRECOVERED_EXECUTION_ERROR,

    /** Final output below the required minimum length with no evidence. */
    OUTPUT_LENGTH_BELOW_MINIMUM,

    /** A required output key is absent from the evidence map. */
    MISSING_REQUIRED_OUTPUT_KEY,

    /** A required evidence key is absent from the evidence map. */
    MISSING_REQUIRED_EVIDENCE_KEY,

    /** A required capability's evidence contract is not satisfied. */
    MISSING_CAPABILITY_EVIDENCE,

    /** A structured acceptance criterion is not met. */
    ACCEPTANCE_CRITERION_NOT_MET
}

/** One typed, machine-comparable verification gap. */
data class VerificationGap(
    val kind: VerificationGapKind,
    /** The key / capability / criterion id the gap refers to. */
    val ref: String,
    /** Human-readable description — for DISPLAY only, never for matching. */
    val message: String
)

/** Typed per-criterion verdict (audit C3: criteria must be typed results). */
data class CriterionVerification(
    val criterionId: String,
    val description: String,
    val met: Boolean,
    /** The evidence key the criterion was evaluated against (null = final text). */
    val evidenceKey: String?
)

/** Typed evidence pointer — what was actually consulted, not prose. */
data class EvidenceReference(
    val key: String,
    /** Kotlin type name of the evidence value (e.g. String, List, Map). */
    val valueType: String
)

/**
 * Structured verification report for objective evaluation (Rule 14, 15).
 *
 * CLOSURE P0-6: the report now carries the TYPED semantic result —
 * [gaps] (machine-comparable), [criterionResults] (per-criterion verdicts)
 * and [evidenceRefs] (what was consulted). [missingCriteria]/
 * [satisfiedCriteria] remain as the human-readable PROJECTION of the same
 * result (back-compat for existing consumers) — they are derived, never
 * the decision input.
 */
data class TaskVerificationReport(
    val isSatisfied: Boolean,
    val status: VerificationOutcomeStatus = if (isSatisfied) VerificationOutcomeStatus.VERIFIED else VerificationOutcomeStatus.FAILED,
    val isDegradedAcceptable: Boolean = false,
    val missingCriteria: List<String> = emptyList(),
    val satisfiedCriteria: List<String> = emptyList(),
    /** Typed gap list — the strategy-evaluation input (CLOSURE P0-6). */
    val gaps: List<VerificationGap> = emptyList(),
    /** Typed per-criterion verdicts. */
    val criterionResults: List<CriterionVerification> = emptyList(),
    /** Typed evidence pointers actually consulted during verification. */
    val evidenceRefs: List<EvidenceReference> = emptyList(),
    val confidence: Float = 1.0f,
    val summary: String = ""
)

/**
 * Objective Outcome Evaluation Service determining individual action outcomes
 * and verifying task acceptance criteria without relying on keyword heuristics.
 */
class OutcomeService {

    /**
     * Maps an execution result and action into an ActionOutcomeType based on structured execution properties.
     */
    fun evaluateActionOutcome(
        action: DecisionAction,
        result: ExecutionResult
    ): ActionOutcomeType {
        return when {
            !result.isSuccess -> {
                when (result.degradedReason) {
                    com.example.domain.core.DegradedReason.PLATFORM_CAPABILITY_RESTRICTED,
                    com.example.domain.core.DegradedReason.CACHE_FALLBACK -> ActionOutcomeType.BLOCKED
                    com.example.domain.core.DegradedReason.EMBEDDING_UNAVAILABLE,
                    com.example.domain.core.DegradedReason.RATE_LIMIT_BACKOFF -> ActionOutcomeType.UNAVAILABLE
                    else -> {
                        val err = result.errorDescription?.lowercase() ?: ""
                        when {
                            err.contains("unavailable") || err.contains("غير متاح") -> ActionOutcomeType.UNAVAILABLE
                            err.contains("blocked") || err.contains("حظر") || err.contains("refused") || err.contains("رفض") || err.contains("denied") -> ActionOutcomeType.BLOCKED
                            else -> ActionOutcomeType.FAILURE
                        }
                    }
                }
            }
            result.isDegraded -> ActionOutcomeType.PARTIAL_SUCCESS
            action.type == DecisionActionType.WAIT -> ActionOutcomeType.WAITING
            action.type == DecisionActionType.ASK_USER -> ActionOutcomeType.WAITING
            else -> ActionOutcomeType.SUCCESS
        }
    }

    /**
     * Performs strict, objective verification of task criteria against gathered evidence and outputs (Rule 13, 14, 15).
     *
     * CLOSURE P0-6 (audit §5.5/C3) — TWO structural changes:
     *
     *  1. TYPED GAPS: every unmet requirement is recorded as a
     *     [VerificationGap] with a machine-comparable [VerificationGapKind].
     *     Strategy evaluation compares KINDS (see step 8) — the previous
     *     `contains("evidence")` / `contains("Criterion")` matching against
     *     the human-readable missing MESSAGES was removed: wording (or
     *     translation) can no longer flip a VERIFIED verdict.
     *
     *  2. TYPED ERROR SIGNAL: [terminalErrorObserved] is the caller's TYPED
     *     knowledge that the execution ended with unrecovered failures
     *     (e.g. consecutiveFailures > 0 in the agent loop). The previous
     *     `finalOutputText.startsWith("Error:")` wording probe was REMOVED —
     *     text wording no longer decides semantic state. An execution that
     *     observed terminal errors CANNOT verify, regardless of how the
     *     accumulated text reads.
     */
    fun verifyTaskCompletion(
        task: TaskDefinition,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String,
        lastAction: DecisionAction,
        terminalErrorObserved: Boolean = false
    ): TaskVerificationReport {
        val missing = mutableListOf<String>()
        val satisfied = mutableListOf<String>()
        val gaps = mutableListOf<VerificationGap>()
        val criterionResults = mutableListOf<CriterionVerification>()
        val evidenceRefs = mutableListOf<EvidenceReference>()

        val requirements = task.requirements
        val successCriteria = task.successCriteria
        val strategy = successCriteria.verificationStrategy

        fun gap(kind: VerificationGapKind, ref: String, message: String) {
            gaps += VerificationGap(kind, ref, message)
            missing += message
        }

        // 1. Validate intermediate routing actions cannot claim completion
        if (lastAction.type == DecisionActionType.SELECT_AGENT ||
            lastAction.type == DecisionActionType.SELECT_TOOL) {
            gap(
                VerificationGapKind.INTERMEDIATE_ROUTING_ACTION,
                lastAction.type.name,
                "Intermediate routing action (${lastAction.type.name}) does not satisfy task objective."
            )
        }

        // 2. Unrecovered execution errors — TYPED signal (CLOSURE P0-6): the
        //    caller reports whether the loop ended with failures; the
        //    "Error:"/"فشل:" text-prefix heuristic is gone.
        if (terminalErrorObserved) {
            gap(
                VerificationGapKind.UNRECOVERED_EXECUTION_ERROR,
                "execution",
                "Execution ended with unrecovered errors — the output cannot verify."
            )
        }

        // 3. Minimum output length check (if applicable)
        val minChars = successCriteria.minOutputLengthChars.coerceAtLeast(1)
        val hasEvidence = accumulatedEvidence.isNotEmpty()
        if (finalOutputText.trim().length < minChars && !hasEvidence) {
            gap(
                VerificationGapKind.OUTPUT_LENGTH_BELOW_MINIMUM,
                "minOutputLength",
                "Output length (${finalOutputText.length}) is below required minimum ($minChars)."
            )
        } else {
            satisfied.add("Output length meets minimum constraint.")
        }

        // 4. Verify explicit Required Output Keys
        // FIX DOM-P0-04: Previously the check was
        //   `if (accumulatedEvidence.containsKey(requiredKey) || finalOutputText.isNotBlank())`
        // The `||` clause meant ANY non-blank final output satisfied EVERY required output key,
        // even when the evidence map did not contain the key. STRICT verification was effectively
        // bypassed when any text was produced. Now we only accept the evidence-map hit, and
        // allow the permissive path through the strategy evaluation in step 8 instead.
        val allRequiredKeys = (successCriteria.requiredOutputKeys + requirements.requiredResourceTypes).distinct()
        for (requiredKey in allRequiredKeys) {
            val value = accumulatedEvidence[requiredKey]
            if (value != null) {
                evidenceRefs += EvidenceReference(requiredKey, value::class.simpleName ?: "Any")
                satisfied.add("Found required output: $requiredKey")
            } else {
                gap(
                    VerificationGapKind.MISSING_REQUIRED_OUTPUT_KEY,
                    requiredKey,
                    "Missing required output key: $requiredKey"
                )
            }
        }

        // 5. Verify explicit Required Evidence Keys
        val allEvidenceKeys = (successCriteria.requiredEvidenceKeys + requirements.requiredEvidenceKeys).distinct()
        for (evidenceKey in allEvidenceKeys) {
            val value = accumulatedEvidence[evidenceKey]
            if (value != null) {
                evidenceRefs += EvidenceReference(evidenceKey, value::class.simpleName ?: "Any")
                satisfied.add("Evidence verified: $evidenceKey")
            } else {
                gap(
                    VerificationGapKind.MISSING_REQUIRED_EVIDENCE_KEY,
                    evidenceKey,
                    "Required evidence key missing from context: $evidenceKey"
                )
            }
        }

        // 6. Generic Evidence Contract Verification for ALL Required Capabilities (Rule 13, 15)
        for (requiredCap in requirements.requiredCapabilities) {
            val contract = CapabilityEvidenceRegistry.getContract(requiredCap)
            val hasContractEvidence = contract.requiredEvidenceKeys.any { key ->
                val value = accumulatedEvidence[key]
                when (value) {
                    null -> false
                    is String -> value.isNotBlank()
                    is Collection<*> -> value.isNotEmpty()
                    is Map<*, *> -> value.isNotEmpty()
                    is Boolean -> value
                    else -> true
                }
            }

            if (hasContractEvidence) {
                satisfied.add("Capability ${requiredCap.name} evidence confirmed (${contract.description}).")
            } else {
                // If it is LLM_GENERATION and finalOutputText is present, LLM evidence is satisfied
                if (requiredCap == CapabilityType.LLM_GENERATION && finalOutputText.isNotBlank()) {
                    satisfied.add("Capability LLM_GENERATION text output verified.")
                } else if (strategy == VerificationStrategy.STRICT || requirements.requiredCapabilities.size > 1) {
                    gap(
                        VerificationGapKind.MISSING_CAPABILITY_EVIDENCE,
                        requiredCap.name,
                        "Task required capability ${requiredCap.name} but missing evidence keys: ${contract.requiredEvidenceKeys.joinToString()}"
                    )
                }
            }
        }

        // 7. Verify Structured Acceptance Criteria — TYPED per-criterion verdicts
        val allAcceptanceCriteria = (successCriteria.acceptanceCriteria + requirements.acceptanceCriteria).distinctBy { it.id }
        for (criterion in allAcceptanceCriteria) {
            val isMet = evaluateAcceptanceCriterion(criterion, accumulatedEvidence, finalOutputText)
            criterionResults += CriterionVerification(
                criterionId = criterion.id,
                description = criterion.description,
                met = isMet,
                evidenceKey = criterion.requiredKey
            )
            if (isMet) {
                satisfied.add("Criterion met: ${criterion.description}")
            } else {
                gap(
                    VerificationGapKind.ACCEPTANCE_CRITERION_NOT_MET,
                    criterion.id,
                    "Criterion not satisfied: ${criterion.description} [${criterion.id}]"
                )
            }
        }

        // 8. Strategy Evaluation — TYPED KIND comparison (CLOSURE P0-6):
        //    the strategy inspects the gap KINDS, never the message wording.
        //    A translated/reworded missing message cannot flip the verdict.
        val isSatisfied = when (strategy) {
            VerificationStrategy.STRICT -> gaps.isEmpty()
            VerificationStrategy.PERMISSIVE -> gaps.isEmpty() || (finalOutputText.isNotBlank() && requirements.requiredCapabilities.isEmpty())
            VerificationStrategy.EVIDENCE_BASED -> gaps.none {
                it.kind == VerificationGapKind.MISSING_REQUIRED_EVIDENCE_KEY ||
                    it.kind == VerificationGapKind.MISSING_CAPABILITY_EVIDENCE
            }
            VerificationStrategy.CRITERIA_MATCH -> gaps.none {
                it.kind == VerificationGapKind.ACCEPTANCE_CRITERION_NOT_MET
            }
        }

        val verificationStatus = when {
            isSatisfied -> VerificationOutcomeStatus.VERIFIED
            satisfied.isNotEmpty() && gaps.isNotEmpty() -> VerificationOutcomeStatus.PARTIALLY_VERIFIED
            gaps.isNotEmpty() -> VerificationOutcomeStatus.FAILED
            else -> VerificationOutcomeStatus.INCONCLUSIVE
        }

        val confidence = if (isSatisfied) 1.0f else (1.0f - (gaps.size * 0.25f)).coerceAtLeast(0.0f)
        val isDegradedAcceptable = !isSatisfied && task.constraints.allowDegradedExecution &&
                (satisfied.isNotEmpty() || finalOutputText.isNotBlank())

        val summary = if (isSatisfied) {
            "تم التحقق بنجاح من كافة معايير إنجاز المهمة (${satisfied.size} معايير مكتملة)."
        } else {
            "فشل التحقق الموضوعي: ${missing.joinToString("; "))}"
        }

        return TaskVerificationReport(
            isSatisfied = isSatisfied,
            status = verificationStatus,
            isDegradedAcceptable = isDegradedAcceptable,
            missingCriteria = missing,
            satisfiedCriteria = satisfied,
            gaps = gaps,
            criterionResults = criterionResults,
            evidenceRefs = evidenceRefs,
            confidence = confidence,
            summary = summary
        )
    }

    private fun evaluateAcceptanceCriterion(
        criterion: AcceptanceCriterion,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String
    ): Boolean {
        val targetValue = if (criterion.requiredKey != null) {
            accumulatedEvidence[criterion.requiredKey]?.toString() ?: ""
        } else {
            finalOutputText
        }

        return when (criterion.validatorType.uppercase()) {
            "EXISTS" -> targetValue.isNotBlank() || accumulatedEvidence.containsKey(criterion.requiredKey)
            "NOT_BLANK" -> targetValue.isNotBlank()
            "MIN_LENGTH" -> {
                val minLen = criterion.minValue?.toInt() ?: 1
                targetValue.length >= minLen
            }
            "REGEX" -> {
                val pattern = criterion.regexPattern ?: return targetValue.isNotBlank()
                Regex(pattern).containsMatchIn(targetValue)
            }
            "NUMERIC_RANGE" -> {
                val num = targetValue.toDoubleOrNull() ?: return false
                val min = criterion.minValue ?: Double.NEGATIVE_INFINITY
                val max = criterion.maxValue ?: Double.POSITIVE_INFINITY
                num in min..max
            }
            else -> targetValue.isNotBlank()
        }
    }

    /**
     * Determines whether the high-level objective of the task has been fully satisfied.
     */
    fun isTaskObjectiveSatisfied(
        task: TaskDefinition,
        accumulatedEvidence: Map<String, Any?>,
        finalOutputText: String,
        lastAction: DecisionAction,
        terminalErrorObserved: Boolean = false
    ): Boolean {
        return verifyTaskCompletion(task, accumulatedEvidence, finalOutputText, lastAction, terminalErrorObserved).isSatisfied
    }

    /**
     * Checks if the closed-loop execution has reached a terminal state.
     */
    fun isTerminalConditionReached(
        task: TaskDefinition,
        stepCount: Int,
        maxSteps: Int,
        consecutiveFailures: Int,
        isObjectiveMet: Boolean
    ): Boolean {
        if (isObjectiveMet) return true
        if (consecutiveFailures > task.constraints.maxRetries) return true
        if (stepCount >= maxSteps) return true
        return false
    }
}
