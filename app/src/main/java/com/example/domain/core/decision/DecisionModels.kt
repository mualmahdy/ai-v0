package com.example.domain.core.decision

import com.example.domain.core.agent.AgentId
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.task.TaskId

/**
 * Extensible Action Space for CBR-MDP Decision Intelligence as mandated by AI-V0 Platform.
 */
enum class DecisionActionType(val code: String, val displayName: String) {
    SELECT_MODEL("select_model", "اختيار النموذج الأنسب للمهمة"),
    SELECT_PROVIDER("select_provider", "اختيار المزود (سحابي/محلي)"),
    SELECT_AGENT("select_agent", "تعيين الوكيل المتخصص"),
    SELECT_TOOL("select_tool", "اختيار الأداة البرمجية"),
    EXECUTE_TOOL("execute_tool", "تنفيذ أداة برمجية مباشرة"),
    EXECUTE_MCP("execute_mcp", "تنفيذ أداة عبر بروتوكول MCP"),
    EXECUTE_SKILL("execute_skill", "تنفيذ مهارة متخصصة"),
    USE_INTEGRATION("use_integration", "استخدام خدمة تكامل خارجية"),
    SEARCH("search", "استعلام شبكي موثوق"),
    RETRIEVE_MEMORY("retrieve_memory", "استرجاع الذاكرة الذكية"),
    RETRIEVE_KNOWLEDGE("retrieve_knowledge", "استرجاع المعرفة والوثائق (RAG)"),
    CREATE_PLAN("create_plan", "إنشاء خطة تنفيذية (Workflow DAG)"),
    EXECUTE_STEP("execute_step", "تنفيذ خطوة برمجية / توليد ذكي"),
    RETRY("retry", "إعادة المحاولة مع التعديل"),
    REPLAN("replan", "إعادة التخطيط واستدراك الفشل"),
    DELEGATE("delegate", "تفويض مهمة لوكيل آخر"),
    ASK_USER("ask_user", "طلب توضيح أو موافقة من المستخدم"),
    WAIT("wait", "انتظار اكتمال مهمة خلفية"),
    STOP("stop", "إنهاء التنفيذ بنجاح أو توقف آمن"),
    COMPLETE("complete", "إكمال المهمة وتحقيق الهدف النهائي")
}

/**
 * Multi-dimensional state vector representing the environment and task state.
 */
data class DecisionState(
    val taskId: TaskId,
    val taskComplexity: Float = 0.5f, // 0.0 to 1.0
    val requiresVision: Boolean = false,
    val requiresToolCalling: Boolean = false,
    val requiresLargeContext: Boolean = false,
    val requiresWebSearch: Boolean = false,
    val requiresCoding: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
    val isNetworkAvailable: Boolean = true,
    val remainingTokenBudget: Int = 30000,
    val consecutiveFailures: Int = 0,
    val uncertaintyScore: Float = 0.2f, // 0.0 (certain) to 1.0 (highly uncertain)
    val hasSearchEvidence: Boolean = false,
    val hasMemoryEvidence: Boolean = false,
    val hasToolExecutionEvidence: Boolean = false,
    val lastActionType: DecisionActionType? = null,
    val lastActionSuccess: Boolean? = null,
    val contextFeatures: Map<String, Float> = emptyMap()
) {
    /**
     * Converts state into a normalized numerical feature vector for CBR similarity search.
     */
    fun toFeatureVector(): FloatArray {
        return floatArrayOf(
            taskComplexity,
            if (requiresVision) 1.0f else 0.0f,
            if (requiresToolCalling) 1.0f else 0.0f,
            if (requiresLargeContext) 1.0f else 0.0f,
            if (requiresWebSearch) 1.0f else 0.0f,
            if (requiresCoding) 1.0f else 0.0f,
            (currentStep.toFloat() / totalSteps.coerceAtLeast(1).toFloat()).coerceIn(0.0f, 1.0f),
            if (isNetworkAvailable) 1.0f else 0.0f,
            (remainingTokenBudget.toFloat() / 30000.0f).coerceIn(0.0f, 1.0f),
            (consecutiveFailures.toFloat() / 5.0f).coerceIn(0.0f, 1.0f),
            uncertaintyScore,
            if (hasSearchEvidence) 1.0f else 0.0f,
            if (hasMemoryEvidence) 1.0f else 0.0f,
            if (hasToolExecutionEvidence) 1.0f else 0.0f,
            if (lastActionSuccess == true) 1.0f else if (lastActionSuccess == false) -1.0f else 0.0f
        )
    }
}

/**
 * Concrete action proposed by CBR-MDP engine.
 */
data class DecisionAction(
    val type: DecisionActionType,
    val targetId: String? = null, // ModelId, AgentId, ToolName, etc.
    val payload: Map<String, String> = emptyMap(),
    val estimatedCost: Double = 0.0,
    val estimatedLatencyMs: Long = 500L
)

/**
 * Historical case stored in the CBR Case Base.
 */
data class DecisionCase(
    val id: String,
    val problemFeatures: FloatArray,
    val chosenAction: DecisionAction,
    val outcomeReward: Float, // -1.0 (failure) to +1.0 (optimal success)
    val taskType: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecisionCase
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Scored action alternative for observability and auditing.
 */
data class ScoredActionCandidate(
    val action: DecisionAction,
    val cbrScore: Float, // Similarity & historical reward from Case-Based Reasoning
    val mdpValue: Float, // Expected utility Q(s, a) from Markov Decision Process
    val finalScore: Float,
    val confidence: Float,
    val reason: String
)

/**
 * Complete decision output with trace for explainability.
 */
data class DecisionResult(
    val chosenAction: DecisionAction,
    val confidence: Float,
    val rationale: String,
    val stateSnapshot: DecisionState,
    val evaluatedAlternatives: List<ScoredActionCandidate>,
    val matchedHistoricalCasesCount: Int,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Observation returned by the environment after executing an action.
 */
data class EnvironmentObservation(
    val action: DecisionAction,
    val isSuccess: Boolean,
    val actualLatencyMs: Long,
    val tokensConsumed: Int = 0,
    val errorDescription: String? = null,
    val outputSummary: String = "",
    val outputData: Map<String, Any?> = emptyMap(),
    val stepIndex: Int = 0,
    val feedbackReward: Float = if (isSuccess) 1.0f else -0.5f,
    val timestampMs: Long = System.currentTimeMillis()
)
