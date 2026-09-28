package com.example.domain.core.decision

/**
 * ============================================================================
 * StandardActionSpace — CLOSURE P1-2 (audit 2026 §5 item 5 + §5.16)
 * ============================================================================
 *
 * THE canonical, context-free action space of the decision runtime — one
 * definition shared by every consumer. The audit's finding was a three-way
 * duplication of the action-space truth:
 *
 *  1. `DecisionIntelligenceService.standardCandidateActions()` carried a
 *     PRIVATE static list manually mirroring the planner's space — a mirror
 *     that silently drifts whenever the real builder changes (the
 *     measurement layer would then measure a DIFFERENT action space than
 *     the one production decides with).
 *  2. The admissibility family sets (tool family / sensitive family /
 *     always-admissible control actions) were split between
 *     `DecisionService`'s companion and a top-level val in
 *     `TaskContractResolver.kt`, forcing `AdmissibleActionSetTest` to
 *     RE-IMPLEMENT the filter locally because the real one was private —
 *     tests asserting a copy of the semantics, not the semantics.
 *  3. Policy metrics compared action names with `startsWith(prefix)` —
 *     string shape deciding semantic credit.
 *
 * This object is the single home for all three: the canonical candidate
 * skeleton ([candidates]), the family classification ([familyOf] — the typed
 * replacement for prefix matching), and the family sets the admissibility
 * filter consumes ([AdmissibleActionSet]).
 *
 * D7 — DELIBERATE DEFERRAL (audit §5 item 7, documented here per §5.16):
 * SELECT_MODEL and EXECUTE_STEP still both drive `executeLlmStep()`
 * downstream; the BIND_RESOURCE transition semantics (a distinct bound-
 * resource state between selection and execution) were consciously deferred
 * out of this stage: introducing a new transition requires designing the
 * orchestrator's state machine as a whole, not a candidate-builder patch.
 * This object therefore still models the CURRENT semantics honestly.
 */
enum class ActionFamily {
    /** Direct execution of work (steps, tools, integrations, search, delegation). */
    EXECUTION,

    /** Choosing a resource/actor to bind (model, provider, agent, tool). */
    SELECTION,

    /** Knowledge/memory retrieval feeding later synthesis. */
    RETRIEVAL,

    /** Loop-control actions (planning, replanning, retry, user interaction). */
    CONTROL,

    /** Terminal outcomes. */
    TERMINATION
}

object StandardActionSpace {

    /**
     * REPAIR ORDER §3B — the tool family mirrored from the governance
     * boundary (§3B/§20). Consolidated here from its previous split homes
     * (DecisionService companion + TaskContractResolver top-level).
     */
    val TOOL_FAMILY_ACTIONS: Set<DecisionActionType> = setOf(
        DecisionActionType.EXECUTE_TOOL,
        DecisionActionType.EXECUTE_MCP,
        DecisionActionType.EXECUTE_SKILL,
        DecisionActionType.USE_INTEGRATION,
        DecisionActionType.SELECT_TOOL
    )

    /**
     * REPAIR ORDER §3B — the sensitive action family excluded from the
     * action space entirely under ASSISTED autonomy (consent-first).
     */
    val SENSITIVE_ACTION_FAMILY: Set<DecisionActionType> = setOf(
        DecisionActionType.EXECUTE_TOOL,
        DecisionActionType.EXECUTE_MCP,
        DecisionActionType.EXECUTE_SKILL,
        DecisionActionType.USE_INTEGRATION
    )

    /**
     * REPAIR ORDER §3B — control actions that are always admissible: the
     * filter's never-empty guarantee (a task can never end up with an EMPTY
     * action space — that would loop forever).
     */
    val ALWAYS_ADMISSIBLE_CONTROL_ACTIONS: Set<DecisionActionType> = setOf(
        DecisionActionType.COMPLETE,
        DecisionActionType.STOP,
        DecisionActionType.ASK_USER,
        DecisionActionType.RETRY,
        DecisionActionType.REPLAN
    )

    /**
     * The TYPED family classification — the P1-2 replacement for the
     * `startsWith(prefix)` metric: semantic credit is decided by the action's
     * FAMILY, never by the lexical shape of its name (a renamed/reworded
     * action can no longer flip measurement outcomes).
     *
     * Note: SELECT_TOOL is classified EXECUTION (not SELECTION) — it is a
     * member of [TOOL_FAMILY_ACTIONS] (tool-binding semantics, gated on
     * TOOL_EXECUTION capability), so its family follows the tool path it
     * serves, not the lexical "SELECT_" prefix. This is exactly the case the
     * old prefix metric got wrong by accident of naming.
     */
    fun familyOf(type: DecisionActionType): ActionFamily = when (type) {
        DecisionActionType.EXECUTE_STEP,
        DecisionActionType.EXECUTE_TOOL,
        DecisionActionType.EXECUTE_MCP,
        DecisionActionType.EXECUTE_SKILL,
        DecisionActionType.USE_INTEGRATION,
        DecisionActionType.SELECT_TOOL,
        DecisionActionType.SEARCH,
        DecisionActionType.DELEGATE,
        DecisionActionType.WAIT -> ActionFamily.EXECUTION

        DecisionActionType.SELECT_MODEL,
        DecisionActionType.SELECT_PROVIDER,
        DecisionActionType.SELECT_AGENT -> ActionFamily.SELECTION

        DecisionActionType.RETRIEVE_MEMORY,
        DecisionActionType.RETRIEVE_KNOWLEDGE -> ActionFamily.RETRIEVAL

        DecisionActionType.CREATE_PLAN,
        DecisionActionType.REPLAN,
        DecisionActionType.RETRY,
        DecisionActionType.ASK_USER -> ActionFamily.CONTROL

        DecisionActionType.COMPLETE,
        DecisionActionType.STOP -> ActionFamily.TERMINATION
    }

    /**
     * The canonical static target of each standard candidate — moved VERBATIM
     * from the old private mirror in DecisionIntelligenceService so the
     * skeleton's identity (types + targets) survives the unification.
     */
    private val STANDARD_TARGETS: Map<DecisionActionType, String> = mapOf(
        DecisionActionType.EXECUTE_STEP to "current",
        DecisionActionType.SELECT_MODEL to "auto",
        DecisionActionType.SEARCH to "web",
        DecisionActionType.RETRIEVE_KNOWLEDGE to "rag",
        DecisionActionType.RETRIEVE_MEMORY to "memory",
        DecisionActionType.DELEGATE to "sub_agent",
        DecisionActionType.CREATE_PLAN to "dag_workflow_planner",
        DecisionActionType.REPLAN to "self",
        DecisionActionType.COMPLETE to "terminal_complete",
        DecisionActionType.STOP to "terminal_stop",
        DecisionActionType.ASK_USER to "guidance"
    )

    /**
     * THE context-free standard candidate skeleton — the single definition
     * the measurement layer ([DecisionIntelligenceService]) feeds to the REAL
     * engine when no full DecisionContext exists. It is NOT a replacement
     * for the context-aware builder (`DecisionService.generateCandidateActions`)
     * — production decisions always build candidates from live context; this
     * skeleton is the honest fallback for context-free measurement, shared
     * instead of mirrored.
     *
     * Returns fresh instances (defensive copies) — callers may mutate their
     * list without corrupting the canonical skeleton.
     */
    fun candidates(): List<DecisionAction> = STANDARD_TARGETS.entries.map { (type, target) ->
        DecisionAction(type = type, targetId = target)
    }

    /** The canonical skeleton's action TYPES (identity without instances). */
    val standardCandidateTypes: Set<DecisionActionType> = STANDARD_TARGETS.keys
}
