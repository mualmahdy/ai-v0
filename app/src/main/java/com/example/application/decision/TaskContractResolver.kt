package com.example.application.decision

import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.task.TaskContract
import com.example.domain.core.task.TaskIntentCategory
import com.example.domain.core.task.TaskContracts

/**
 * ============================================================================
 * REPAIR ORDER §3B — INTENT → TASK CONTRACT RESOLUTION
 * ============================================================================
 * Resolves the task intent category from the execution mode, agent binding
 * and prompt, producing the TaskContract that CONSTRAINS the action space
 * BEFORE the decision engine ranks anything.
 *
 * Quick Chat is ONE legitimate mode: prompt → QUICK_CHAT contract →
 * generation-only admissible actions. It is no longer a global explanation
 * for every task, and ordinary chat can structurally never nominate
 * sensitive tools.
 */
class TaskContractResolver {

    /**
     * @param chatMode the user-facing execution mode (QUICK_CHAT vs AGENT).
     * @param agent the resolved agent definition (capability binding).
     * @param rawPrompt the task prompt (semantic hints).
     * @param isWorkflowStep true when running inside an orchestrated workflow.
     */
    fun resolve(
        chatMode: String?,
        agent: AgentDefinition?,
        rawPrompt: String,
        isWorkflowStep: Boolean = false
    ): TaskContract {
        val category = classify(chatMode, agent, rawPrompt, isWorkflowStep)
        return TaskContracts.forCategory(category)
    }

    fun classify(
        chatMode: String?,
        agent: AgentDefinition?,
        rawPrompt: String,
        isWorkflowStep: Boolean = false
    ): TaskIntentCategory {
        // Workflow steps are contract-bound to the orchestrated plan space.
        if (isWorkflowStep) return TaskIntentCategory.WORKFLOW_TASK

        // Quick Chat mode: generation-only — REGARDLESS of prompt keywords.
        if (chatMode.equals("QUICK_CHAT", ignoreCase = true)) {
            return TaskIntentCategory.QUICK_CHAT
        }

        // Coding agents bind coding contracts.
        val agentId = agent?.identity?.id?.value?.lowercase()
        if (agentId != null) {
            if (agentId.contains("coder") || agentId.contains("craftsman")) {
                return TaskIntentCategory.CODING_TASK
            }
            if (agentId.contains("researcher")) {
                return TaskIntentCategory.SEARCH_TASK
            }
        }

        // Semantic prompt classification (ordered by specificity).
        val prompt = rawPrompt.trim()
        return when {
            looksLikeFileTask(prompt) -> TaskIntentCategory.FILE_TASK
            looksLikeSearchTask(prompt) -> TaskIntentCategory.SEARCH_TASK
            looksLikeKnowledgeTask(prompt) -> TaskIntentCategory.KNOWLEDGE_TASK
            looksLikeCodingTask(prompt) -> TaskIntentCategory.CODING_TASK
            looksLikeComplexTask(prompt, agent) -> TaskIntentCategory.COMPLEX_TASK
            looksLikeChat(prompt) -> TaskIntentCategory.CHAT
            else -> TaskIntentCategory.GENERAL_TASK
        }
    }

    // ------------------------------------------------------------------
    // Heuristics (transparent, keyword-based — documented as such; the
    // contract remains the semantic authority regardless of heuristics).
    // ------------------------------------------------------------------

    private fun looksLikeChat(prompt: String): Boolean {
        if (prompt.length <= 120 && !prompt.contains('\n')) return true
        return chatStarters.any { prompt.startsWith(it, ignoreCase = true) }
    }

    private fun looksLikeFileTask(prompt: String): Boolean =
        fileKeywords.any { prompt.contains(it, ignoreCase = true) }

    private fun looksLikeSearchTask(prompt: String): Boolean =
        searchKeywords.any { prompt.contains(it, ignoreCase = true) }

    private fun looksLikeKnowledgeTask(prompt: String): Boolean =
        knowledgeKeywords.any { prompt.contains(it, ignoreCase = true) }

    private fun looksLikeCodingTask(prompt: String): Boolean =
        codingKeywords.any { prompt.contains(it, ignoreCase = true) }

    private fun looksLikeComplexTask(prompt: String, agent: AgentDefinition?):Boolean {
        val multiStep = prompt.contains('\n') || prompt.length > 400 ||
                planKeywords.any { prompt.contains(it, ignoreCase = true) }
        val complexAgent = agent?.identity?.id?.value?.lowercase()?.let {
            it.contains("architect") || it.contains("orchestrator")
        } ?: false
        return multiStep || complexAgent
    }

    private companion object {
        val chatStarters = listOf(
            "مرحبا", "السلام", "أهلا", "قل لي", "اشرح لي باختصار", "ما رأيك",
            "hi", "hello", "hey", "tell me", "what do you think", "chat"
        )
        val fileKeywords = listOf(
            "أنشئ ملف", "اكتب ملف", "احفظ الملف", "اقرأ الملف", "احذف الملف", "أنشئ مجلد", "المجلد",
            "create file", "write file", "save file", "read file", "delete file", "create folder", "the folder"
        )
        val searchKeywords = listOf(
            "ابحث", "بحث عن", "ابحث لي", "ما هي أخبار", "المعلومات الحديثة", "مصادر",
            "search for", "look up", "find information", "latest news", "sources", "research"
        )
        val knowledgeKeywords = listOf(
            "أضف إلى المعرفة", "فهرس", "قاعدة المعرفة", "الوثائق", "الذاكرة",
            "add to knowledge", "index this", "knowledge base", "ingest", "remember this"
        )
        val codingKeywords = listOf(
            "كود", "برمج", "دالة", "صنف", "خطأ برمجي", "حرر الكود", "اختبر", " compilation",
            "code", "function", "class", "debug", "refactor", "compile", "implement", "unit test"
        )
        val planKeywords = listOf(
            "خطط", "خطة", "قسّم المهمة", "عدة خطوات", "سير عمل",
            "plan", "steps", "workflow", "break down", "multi-step", "roadmap"
        )
    }
}

/** Action types that require the agent to declare TOOL_EXECUTION capability. */
val TOOL_FAMILY_ACTIONS: Set<DecisionActionType> = setOf(
    DecisionActionType.EXECUTE_TOOL,
    DecisionActionType.EXECUTE_MCP,
    DecisionActionType.EXECUTE_SKILL,
    DecisionActionType.USE_INTEGRATION,
    DecisionActionType.SELECT_TOOL
)
