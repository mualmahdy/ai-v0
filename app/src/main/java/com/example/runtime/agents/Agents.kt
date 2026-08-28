package com.example.runtime.agents

import com.example.domain.models.PlanStep
import com.example.domain.models.WorkflowPlan
import com.example.runtime.memory.MemoryManager
import com.example.runtime.providers.ModelOrchestrator
import org.json.JSONArray
import org.json.JSONObject

class DirectAgent(
    private val orchestrator: ModelOrchestrator
) : BaseAgent {
    override val name: String = "direct"
    override val description: String = "استجابة فورية سريعة عبر نموذج التوجيه والتنسيق المباشر"
    override val modelRole: String = "fast_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val prompt = task["prompt"] as? String ?: return AgentResult("لا توجد رسالة", status = "error")
        val outcome = orchestrator.generateWithResilience(modelRole, prompt)
        return AgentResult(
            response = outcome.output,
            status = outcome.status,
            providerUsed = outcome.providerUsed,
            modelUsed = outcome.modelUsed,
            degradedReason = outcome.degradedReason
        )
    }
}

class PlannerAgent(
    private val orchestrator: ModelOrchestrator,
    private val memoryManager: MemoryManager? = null
) : BaseAgent {
    override val name: String = "planner"
    override val description: String = "تخطيط وتحليل الأهداف وبناء خرائط التدفق التنفيذية"
    override val modelRole: String = "planner_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val goal = task["goal"] as? String ?: task["prompt"] as? String ?: ""
        val plan = createPlan(task["projectId"] as? Long ?: 1L, goal)
        return AgentResult(
            response = "تم إنشاء خطة العمل بنجاح (${plan.steps.size} خطوات)",
            status = "success",
            providerUsed = "Local Native Engine",
            modelUsed = "native-cbr-engine",
            rawOutput = plan
        )
    }

    suspend fun createPlan(projectId: Long, goal: String): WorkflowPlan {
        val memoryContext = try {
            val mems = memoryManager?.searchMemories(projectId, goal, topK = 3) ?: emptyList()
            if (mems.isNotEmpty()) {
                "\nسياق من الذاكرة السابقة:\n" + mems.joinToString("\n") { "- ${it.content}" }
            } else ""
        } catch (e: Exception) { "" }

        val prompt = """
        أنت مخطط استراتيجي. الهدف: $goal
        $memoryContext
        قسم الهدف إلى خطوات واضحة مع تحديد الوكيل المناسب لكل خطوة (direct, code, research, reviewer, search, memory).
        """.trimIndent()

        val outcome = orchestrator.generateWithResilience(modelRole, prompt, "أنت وكيل تخطيط مهام ينتج خطوات عمل بصيغة JSON.")
        return parsePlan(outcome.output, goal)
    }

    suspend fun proposeNextStep(goal: String, resultsSummary: String, nextId: Int): PlanStep? {
        val prompt = """
        الهدف الأصلي: $goal
        النتائج حتى الآن:
        $resultsSummary
        إذا كان ما تم تنفيذه كافياً تماماً، أجب بـ NONE. وإلا اقترح خطوة إضافية واحدة بصيغة JSON.
        """.trimIndent()

        val outcome = orchestrator.generateWithResilience(modelRole, prompt)
        val text = outcome.output.trim()
        if (text.startsWith("NONE", ignoreCase = true)) return null

        return try {
            val json = JSONObject(text.substringAfter("{").substringBeforeLast("}").let { "{$it}" })
            PlanStep(
                id = nextId,
                action = json.optString("action", "خطوة متابعة تكيّفية"),
                description = json.optString("description", text),
                agent = json.optString("agent", "code"),
                tools = emptyList()
            )
        } catch (e: Exception) {
            PlanStep(
                id = nextId,
                action = "خطوة متابعة تكيّفية إضافية",
                description = "إكمال ما تبقى من الهدف: $goal",
                agent = "code",
                tools = emptyList()
            )
        }
    }

    private fun parsePlan(raw: String, goal: String): WorkflowPlan {
        try {
            val jsonText = raw.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val obj = JSONObject(jsonText)
            val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<PlanStep>()
            for (i in 0 until stepsArray.length()) {
                val s = stepsArray.getJSONObject(i)
                val toolsArr = s.optJSONArray("tools")
                val tools = mutableListOf<String>()
                if (toolsArr != null) {
                    for (t in 0 until toolsArr.length()) tools.add(toolsArr.getString(t))
                }
                steps.add(
                    PlanStep(
                        id = s.optInt("id", i + 1),
                        action = s.optString("action", "تنفيذ"),
                        description = s.optString("description", ""),
                        agent = s.optString("agent", "direct"),
                        tools = tools
                    )
                )
            }
            if (steps.isNotEmpty()) {
                return WorkflowPlan(goal = goal, steps = steps)
            }
        } catch (e: Exception) {
            // fallback
        }

        return WorkflowPlan(
            goal = goal,
            steps = listOf(
                PlanStep(1, "جمع وتحليل المتطلبات", "تحليل الهدف: $goal", "research", listOf("offline_knowledge")),
                PlanStep(2, "التنفيذ البرمجي أو الكتابي", "تنفيذ محتوى: $goal", "code", listOf("file_reader")),
                PlanStep(3, "المراجعة والتأكيد", "تقييم جودة المخرجات", "reviewer", emptyList())
            )
        )
    }
}

class CodeAgent(
    private val toolLoop: ToolLoop
) : BaseAgent {
    override val name: String = "code"
    override val description: String = "وكيل كتابة وتحليل وتعديل وفحص الأكواد البرمجية"
    override val modelRole: String = "coding_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val prompt = task["prompt"] as? String ?: "كتابة وفحص الكود"
        val projectId = task["projectId"] as? Long ?: 1L
        return toolLoop.runLoop(
            projectId = projectId,
            role = modelRole,
            prompt = prompt,
            tools = listOf("file_reader", "file_writer", "calculator", "code_runner"),
            systemInstruction = "أنت وكيل برمجي ماهر."
        )
    }
}

class ResearchAgent(
    private val toolLoop: ToolLoop
) : BaseAgent {
    override val name: String = "research"
    override val description: String = "وكيل البحث والتحليل والتعمق في المعرفة والإنترنت"
    override val modelRole: String = "search_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val prompt = task["prompt"] as? String ?: "بحث وتحليل"
        val projectId = task["projectId"] as? Long ?: 1L
        return toolLoop.runLoop(
            projectId = projectId,
            role = modelRole,
            prompt = prompt,
            tools = listOf("offline_knowledge", "memory_search", "web_search"),
            systemInstruction = "أنت باحث دقيق يجمع المعلومات ويقارن بينها."
        )
    }
}

class SearchAgent(
    private val toolLoop: ToolLoop
) : BaseAgent {
    override val name: String = "search"
    override val description: String = "وكيل البحث الميداني السريع عن النصوص والمستندات"
    override val modelRole: String = "search_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val prompt = task["prompt"] as? String ?: "بحث سريع"
        val projectId = task["projectId"] as? Long ?: 1L
        return toolLoop.runLoop(
            projectId = projectId,
            role = modelRole,
            prompt = prompt,
            tools = listOf("offline_knowledge", "web_search"),
            systemInstruction = "أنت وكيل بحث فوري."
        )
    }
}

class MemoryAgent(
    private val memoryManager: MemoryManager
) : BaseAgent {
    override val name: String = "memory"
    override val description: String = "وكيل إدارة الذاكرة طويلة المدى واسترجاع التفضيلات"
    override val modelRole: String = "fast_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val action = task["action"] as? String ?: "search"
        val projectId = task["projectId"] as? Long ?: 1L
        val content = task["content"] as? String ?: task["prompt"] as? String ?: ""

        return if (action == "store" && content.isNotEmpty()) {
            val id = memoryManager.addLongTermMemory(projectId, content)
            AgentResult("تم حفظ الذاكرة بنجاح (ID: $id)", status = "success")
        } else {
            val mems = memoryManager.searchMemories(projectId, content, topK = 4)
            val result = if (mems.isEmpty()) "لا توجد ذكريات سابقة." else mems.joinToString("\n") { "• [${it.memoryType}] ${it.content}" }
            AgentResult(result, status = "success")
        }
    }
}

class ReviewerAgent(
    private val orchestrator: ModelOrchestrator
) : BaseAgent {
    override val name: String = "reviewer"
    override val description: String = "وكيل المراجعة وتقييم الجودة وفحص الأخطاء (CBR Evaluation)"
    override val modelRole: String = "review_model"

    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val content = task["content"] as? String ?: task["prompt"] as? String ?: ""
        val prompt = "راجع المحتوى التالي وقيم جودته:\n$content"
        val outcome = orchestrator.generateWithResilience(modelRole, prompt, "أنت مراجع جودة دقيق.")

        val (approved, score, feedback) = parseReview(outcome.output)
        return AgentResult(
            response = feedback,
            status = if (approved) "success" else "degraded",
            score = score,
            providerUsed = outcome.providerUsed,
            modelUsed = outcome.modelUsed
        )
    }

    private fun parseReview(raw: String): Triple<Boolean, Float, String> {
        return try {
            val jsonText = raw.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val obj = JSONObject(jsonText)
            val approved = obj.optBoolean("approved", true)
            val score = obj.optDouble("score", if (approved) 0.85 else 0.40).toFloat()
            val feedback = obj.optString("feedback", raw)
            Triple(approved, score, feedback)
        } catch (e: Exception) {
            Triple(true, 0.85f, raw)
        }
    }
}

class ConfigurableAgent(
    override val name: String,
    override val description: String,
    override val modelRole: String,
    private val systemPrompt: String,
    private val tools: List<String>,
    private val toolLoop: ToolLoop
) : BaseAgent {
    override suspend fun execute(task: Map<String, Any>): AgentResult {
        val prompt = task["prompt"] as? String ?: ""
        val projectId = task["projectId"] as? Long ?: 1L
        val fullPrompt = if (systemPrompt.isNotEmpty()) "$systemPrompt\n\n$prompt" else prompt

        return toolLoop.runLoop(
            projectId = projectId,
            role = modelRole,
            prompt = fullPrompt,
            tools = tools,
            systemInstruction = systemPrompt
        )
    }
}

class AgentRegistry {
    private val agents = mutableMapOf<String, BaseAgent>()

    fun register(agent: BaseAgent) {
        agents[agent.name] = agent
    }

    fun get(name: String): BaseAgent? = agents[name]

    fun getAll(): List<BaseAgent> = agents.values.toList()

    fun listNames(): List<String> = agents.keys.toList()
}
