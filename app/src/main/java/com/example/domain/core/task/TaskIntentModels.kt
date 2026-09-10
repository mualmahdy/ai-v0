package com.example.domain.core.task

import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.decision.DecisionActionType

/**
 * REPAIR ORDER §3B — task intent categories. The intent is resolved from
 * the prompt + execution mode + agent binding, and defines the SEMANTIC
 * shape of the task BEFORE any action is generated. Quick Chat is one
 * legitimate mode — not a global explanation for every task.
 */
enum class TaskIntentCategory {
    QUICK_CHAT,
    CHAT,
    GENERAL_TASK,
    COMPLEX_TASK,
    CODING_TASK,
    SEARCH_TASK,
    KNOWLEDGE_TASK,
    FILE_TASK,
    WORKFLOW_TASK
}

/**
 * REPAIR ORDER §3B/§4 — the Task Contract.
 *
 * The execution pipeline becomes:
 *
 *   Intent → Task Contract → Required Capabilities → Admissible Action Set
 *         → Decision/Ranking → Governance → Execution
 *
 * The decision engine NEVER sees "all actions": it receives the action
 * space already constrained by task semantics ([admissibleActions]),
 * required capabilities, agent capability binding and policy. Governance
 * remains the FINAL enforcement boundary (defense in depth) — but it is
 * no longer the FIRST place a prohibited action is discovered.
 */
data class TaskContract(
    val category: TaskIntentCategory,
    val requiredCapabilities: Set<CapabilityType> = emptySet(),
    val admissibleActions: Set<DecisionActionType>,
    /**
     * Actions that are semantically excluded for this task regardless of
     * policy or grants (e.g. no EXECUTE_TOOL for plain chat).
     */
    val prohibitedActions: Set<DecisionActionType> = emptySet(),
    /**
     * Task-level autonomy hint. The EFFECTIVE policy is still resolved by
     * the governance hierarchy (SYSTEM → WORKSPACE → PROJECT → TASK → AGENT),
     * where a child scope may restrict but never elevate.
     */
    val autonomyPolicy: AutonomyPolicy? = null,
    val rationale: String
) {
    fun isAdmissible(action: DecisionActionType): Boolean =
        action in admissibleActions && action !in prohibitedActions
}

/**
 * Canonical contract factory used by the TaskContractResolver.
 *
 * Design notes:
 *  - CHAT-family contracts admit ONLY the generation path (EXECUTE_STEP is
 *    the LLM generation action; COMPLETE/STOP/RETRY/ASK_USER are control
 *    actions every contract needs). A chat contract therefore CANNOT
 *    nominate sensitive tools — the universal AUTONOMY_POLICY_BLOCKED on
 *    ordinary chat becomes structurally impossible.
 *  - Tool-family contracts admit tool actions but they remain subject to
 *    capability + policy + grant filtering in the decision layer, and to
 *    governance as final boundary.
 */
object TaskContracts {

    private val CONTROL_ACTIONS: Set<DecisionActionType> = setOf(
        DecisionActionType.COMPLETE,
        DecisionActionType.STOP,
        DecisionActionType.RETRY,
        DecisionActionType.ASK_USER
    )

    private val GENERATION_ACTIONS: Set<DecisionActionType> = CONTROL_ACTIONS + setOf(
        DecisionActionType.EXECUTE_STEP,
        DecisionActionType.SELECT_MODEL,
        DecisionActionType.SELECT_PROVIDER
    )

    /** Quick Chat: pure conversation — no tools, no search, no delegation. */
    val QUICK_CHAT: TaskContract = TaskContract(
        category = TaskIntentCategory.QUICK_CHAT,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
        admissibleActions = GENERATION_ACTIONS,
        prohibitedActions = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.EXECUTE_MCP,
            DecisionActionType.EXECUTE_SKILL,
            DecisionActionType.USE_INTEGRATION,
            DecisionActionType.DELEGATE,
            DecisionActionType.CREATE_PLAN
        ),
        rationale = "محادثة سريعة: توليد نص فقط، دون أدوات أو تفويض."
    )

    /** Ordinary chat (agent-bound conversation): generation + memory/knowledge retrieval. */
    val CHAT: TaskContract = TaskContract(
        category = TaskIntentCategory.CHAT,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
        admissibleActions = GENERATION_ACTIONS + setOf(
            DecisionActionType.RETRIEVE_MEMORY,
            DecisionActionType.RETRIEVE_KNOWLEDGE,
            DecisionActionType.SELECT_AGENT
        ),
        prohibitedActions = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.EXECUTE_MCP,
            DecisionActionType.EXECUTE_SKILL,
            DecisionActionType.USE_INTEGRATION
        ),
        rationale = "محادثة وكيل: توليد + استرجاع ذاكرة/معرفة، دون أدوات حساسة."
    )

    /** Complex task: full problem-solving space (plan, tools, search, delegate). */
    val COMPLEX_TASK: TaskContract = TaskContract(
        category = TaskIntentCategory.COMPLEX_TASK,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING),
        admissibleActions = setOf(*DecisionActionType.entries.toTypedArray()),
        prohibitedActions = emptySet(),
        rationale = "مهمة معقدة: فضاء أفعال كامل، تخضع للقدرات والسياسة والمنح."
    )

    /** Coding task: generation + file/code tools admitted (still policy-gated). */
    val CODING_TASK: TaskContract = TaskContract(
        category = TaskIntentCategory.CODING_TASK,
        requiredCapabilities = setOf(
            CapabilityType.LLM_GENERATION,
            CapabilityType.CODE_ANALYSIS,
            CapabilityType.CODE_ENGINEERING
        ),
        admissibleActions = setOf(*DecisionActionType.entries.toTypedArray()),
        rationale = "مهمة برمجية: أدوات الملفات والكود مسموح ضمن نطاق السياسة."
    )

    /** Search task: generation + search/memory/knowledge. */
    val SEARCH_TASK: TaskContract = TaskContract(
        category = TaskIntentCategory.SEARCH_TASK,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.SEARCH),
        admissibleActions = GENERATION_ACTIONS + setOf(
            DecisionActionType.SEARCH,
            DecisionActionType.RETRIEVE_MEMORY,
            DecisionActionType.RETRIEVE_KNOWLEDGE,
            DecisionActionType.REPLAN
        ),
        prohibitedActions = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.EXECUTE_MCP,
            DecisionActionType.EXECUTE_SKILL,
            DecisionActionType.USE_INTEGRATION,
            DecisionActionType.DELEGATE
        ),
        rationale = "مهمة بحث: توليد + بحث/استرجاع، دون أدوات تنفيذية."
    )

    /** Knowledge task: ingestion/retrieval only. */
    val KNOWLEDGE_TASK: TaskContract = TaskContract(
        category = TaskIntentCategory.KNOWLEDGE_TASK,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.MEMORY_RETRIEVAL),
        admissibleActions = GENERATION_ACTIONS + setOf(
            DecisionActionType.RETRIEVE_MEMORY,
            DecisionActionType.RETRIEVE_KNOWLEDGE
        ),
        prohibitedActions = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.EXECUTE_MCP,
            DecisionActionType.EXECUTE_SKILL,
            DecisionActionType.USE_INTEGRATION,
            DecisionActionType.SEARCH
        ),
        rationale = "مهمة معرفة: استرجاع فقط."
    )

    /** File task: explicit user file operation request. */
    val FILE_TASK: TaskContract = TaskContract(
        category = TaskIntentCategory.FILE_TASK,
        requiredCapabilities = setOf(
            CapabilityType.LLM_GENERATION,
            CapabilityType.FILE_READ,
            CapabilityType.FILE_WRITE
        ),
        admissibleActions = setOf(
            DecisionActionType.EXECUTE_TOOL,
            DecisionActionType.SELECT_TOOL,
            DecisionActionType.RETRIEVE_KNOWLEDGE
        ) + GENERATION_ACTIONS,
        rationale = "مهمة ملفات: أدوات الملفات داخل نطاق المشروع الحالي فقط."
    )

    /** Workflow task: orchestrated plan execution. */
    val WORKFLOW_TASK: TaskContract = TaskContract(
        category = TaskIntentCategory.WORKFLOW_TASK,
        requiredCapabilities = setOf(CapabilityType.LLM_GENERATION),
        admissibleActions = setOf(
            DecisionActionType.CREATE_PLAN,
            DecisionActionType.EXECUTE_STEP,
            DecisionActionType.SELECT_MODEL,
            DecisionActionType.SELECT_AGENT,
            DecisionActionType.DELEGATE,
            DecisionActionType.REPLAN
        ) + CONTROL_ACTIONS,
        prohibitedActions = emptySet(),
        rationale = "مهمة سير عمل: تخطيط وتنفيذ خطوات."
    )

    val DEFAULT: TaskContract get() = CHAT

    fun forCategory(category: TaskIntentCategory): TaskContract = when (category) {
        TaskIntentCategory.QUICK_CHAT -> QUICK_CHAT
        TaskIntentCategory.CHAT -> CHAT
        TaskIntentCategory.COMPLEX_TASK -> COMPLEX_TASK
        TaskIntentCategory.CODING_TASK -> CODING_TASK
        TaskIntentCategory.SEARCH_TASK -> SEARCH_TASK
        TaskIntentCategory.KNOWLEDGE_TASK -> KNOWLEDGE_TASK
        TaskIntentCategory.FILE_TASK -> FILE_TASK
        TaskIntentCategory.WORKFLOW_TASK -> WORKFLOW_TASK
        TaskIntentCategory.GENERAL_TASK -> COMPLEX_TASK
    }
}
