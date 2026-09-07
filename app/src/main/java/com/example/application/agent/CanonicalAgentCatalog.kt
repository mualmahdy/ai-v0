package com.example.application.agent

import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityType

/**
 * ============================================================================
 * CanonicalAgentCatalog — gap-closure P1-08
 * ============================================================================
 *
 * THE one definition of the default agent catalog (the union of the legacy
 * runtime agents and the legacy UI-only agents — ONE identity space now).
 *
 * Consumers:
 *  - AppContainer bootstrap seeds it into the durable registry
 *    (agent_definitions, Room) once on first launch;
 *  - the ViewModel uses it as an in-memory fallback ONLY until the durable
 *    seed lands (first milliseconds of a cold start);
 *  - the runtime ComponentRegistry receives the SAME definitions via
 *    AgentRegistryService.syncInto.
 *
 * The agent the user selects IS the agent that executes.
 */
object CanonicalAgentCatalog {

    val defaults: List<AgentDefinition> = listOf(
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("agent_general"),
                name = "المساعد الشامل",
                role = AgentRole.GENERAL_ASSISTANT,
                description = "المساعد العام للنظام",
                systemPrompt = AgentRole.GENERAL_ASSISTANT.defaultSystemPrompt
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.STREAMING,
                CapabilityType.SEARCH,
                CapabilityType.MEMORY_RETRIEVAL,
                CapabilityType.AGENT_DELEGATION
            ),
            budget = AgentBudget()
        ),
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("agent_coder"),
                name = "مهندس البرمجيات",
                role = AgentRole.CODER,
                description = "متخصص في بناء وتطوير وهندسة الكود",
                systemPrompt = AgentRole.CODER.defaultSystemPrompt
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.TOOL_EXECUTION,
                CapabilityType.FILE_STORAGE,
                CapabilityType.MEMORY_RETRIEVAL
            ),
            budget = AgentBudget()
        ),
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("agent_researcher"),
                name = "الباحث المعرفي",
                role = AgentRole.RESEARCHER,
                description = "متخصص في استرجاع المعرفة والبحث الموثوق",
                systemPrompt = AgentRole.RESEARCHER.defaultSystemPrompt
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.SEARCH,
                CapabilityType.MEMORY_RETRIEVAL
            ),
            budget = AgentBudget()
        ),
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("architect_orchestrator"),
                name = "المخطط الرئيسي (Strategic Planner)",
                role = AgentRole.PLANNER,
                description = "يقود تحليل المهام المعقدة، تقسيم العمليات، وحوكمة الموارد.",
                systemPrompt = "أنت المخطط الرئيسي لمنظومة AI-V0 Agent Studio. تتميز بالدقة الهندسية، التحليل المنهجي، وتوضيح القيود الواقعية."
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.STREAMING,
                CapabilityType.MEMORY_RETRIEVAL
            ),
            budget = AgentBudget(maxTokens = 30000)
        ),
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("code_craftsman"),
                name = "المبرمج التنفيذي (Executive Coder)",
                role = AgentRole.CODER,
                description = "متخصص في بناء البرمجيات النظيفة وكتابة الشيفرات المعيارية والملفات.",
                systemPrompt = "أنت مهندس برمجيات محترف ومختص في هندسة النظم النظيفة وتطوير الأدوات."
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.TOOL_EXECUTION,
                CapabilityType.FILE_STORAGE
            ),
            budget = AgentBudget(maxTokens = 40000)
        ),
        AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("security_guardian"),
                name = "حارس الحوكمة والأمان (Security Auditor)",
                role = AgentRole.SECURITY_GUARD,
                description = "يدقق في مدخلات ومخرجات الأدوات، ويتحقق من سلامة الأوامر.",
                systemPrompt = "أنت مدقق أمني مستقل وحارس لسياسات الأمان والحوكمة."
            ),
            allowedCapabilities = setOf(
                CapabilityType.LLM_GENERATION,
                CapabilityType.TOOL_EXECUTION
            ),
            budget = AgentBudget(maxTokens = 20000)
        )
    )
}
