package com.example.domain.core.decision

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.task.AutonomyPolicy
import com.example.domain.core.task.TaskContract

/**
 * ============================================================================
 * AdmissibleActionSet — CLOSURE P1-2 (audit 2026 §5 item 5 / REPAIR ORDER §3B)
 * ============================================================================
 *
 * THE single admissibility filter of the decision runtime — extracted
 * VERBATIM from DecisionService's private `applyAdmissibleActionSet` so that
 * the PRODUCTION filter, the measurement layer, and the TESTS all consume
 * ONE implementation. The audit's finding: `AdmissibleActionSetTest`
 * re-implemented this filter locally (a private original forces the copy),
 * so the tests asserted a MIRROR of the semantics — exactly the drift risk
 * this extraction closes.
 *
 * Semantics (unchanged from REPAIR ORDER §3B — defense in depth):
 *
 *   1. TASK-CONTRACT admissibility (semantics): a QUICK_CHAT contract
 *      structurally cannot nominate tools; ordinary chat cannot nominate
 *      sensitive tools; … (null contract = no semantic constraint).
 *   2. AGENT CAPABILITY binding: an agent without TOOL_EXECUTION never sees
 *      tool-family actions.
 *   3. POLICY pre-constraint: under ASSISTED autonomy the whole sensitive
 *      family is excluded from the action space (consent-first: such tasks
 *      surface ASK_USER instead of a mid-loop block).
 *
 * Plus the never-empty guarantee: control actions are ALWAYS admissible —
 * a task can never end up with an empty action space (that would loop
 * forever).
 *
 * Governance remains the FINAL enforcement boundary downstream — this filter
 * just no longer meets surprises.
 */
object AdmissibleActionSet {

    /**
     * Single-predicate admissibility (no fallback injection) — usable by
     * tests and callers that only need the verdict for one action.
     */
    fun isAdmissible(
        action: DecisionActionType,
        contract: TaskContract?,
        agentAllowedCapabilities: Set<CapabilityType>?,
        effectiveAutonomyPolicy: AutonomyPolicy?
    ): Boolean {
        val sensitiveFamilyExcluded = effectiveAutonomyPolicy == AutonomyPolicy.ASSISTED
        val agentLacksToolExecution = agentAllowedCapabilities != null &&
            CapabilityType.TOOL_EXECUTION !in agentAllowedCapabilities
        return (
            // 1. Task-contract admissibility (semantics).
            (contract == null || contract.isAdmissible(action)) &&
                // 2. Agent capability binding.
                !(agentLacksToolExecution && action in StandardActionSpace.TOOL_FAMILY_ACTIONS) &&
                // 3. Policy pre-constraint: ASSISTED tasks never see sensitive
                //    actions in their action space.
                !(sensitiveFamilyExcluded && action in StandardActionSpace.SENSITIVE_ACTION_FAMILY)
            )
    }

    /**
     * The production filter over a candidate list, INCLUDING the never-empty
     * control fallback: if nothing admissible remains, an explicit ASK_USER
     * is injected so the loop can always terminate honestly.
     */
    fun filter(
        candidates: List<DecisionAction>,
        contract: TaskContract?,
        agentAllowedCapabilities: Set<CapabilityType>?,
        effectiveAutonomyPolicy: AutonomyPolicy?
    ): List<DecisionAction> {
        var filtered = candidates.filter { action ->
            isAdmissible(action.type, contract, agentAllowedCapabilities, effectiveAutonomyPolicy)
        }

        // Fallback safety: control actions (COMPLETE/ASK_USER/RETRY/REPLAN)
        // are ALWAYS admissible — a task can never end up with an EMPTY
        // action space (that would loop forever).
        if (filtered.none { it.type in StandardActionSpace.ALWAYS_ADMISSIBLE_CONTROL_ACTIONS }) {
            filtered = filtered + listOfNotNull(
                candidates.firstOrNull { it.type == DecisionActionType.ASK_USER }
            ).ifEmpty {
                listOf(
                    DecisionAction(
                        type = DecisionActionType.ASK_USER,
                        targetId = "empty_admissible_action_set",
                        payload = mapOf(
                            "reason" to "لا توجد أفعال مسموحة لهذه المهمة وفق عقد المهمة والسياسة — " +
                                "مطلوب إرشاد المستخدم."
                        )
                    )
                )
            }
        }
        return filtered
    }
}
