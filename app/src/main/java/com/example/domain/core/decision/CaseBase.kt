package com.example.domain.core.decision

import kotlin.math.sqrt

/**
 * Case Base storing historical decision experiences with similarity retrieval.
 */
class CaseBase(initialCases: List<DecisionCase> = emptyList()) {

    private val cases = mutableListOf<DecisionCase>()

    init {
        if (initialCases.isNotEmpty()) {
            cases.addAll(initialCases)
        } else {
            bootstrapDefaultCases()
        }
    }

    @Synchronized
    fun addCase(case: DecisionCase) {
        // Keep case base bounded to 2000 most relevant cases
        if (cases.size >= 2000) {
            cases.sortBy { it.timestampMs }
            cases.removeAt(0)
        }
        cases.add(case)
    }

    @Synchronized
    fun getAllCases(): List<DecisionCase> = cases.toList()

    /**
     * Finds the k-nearest historical cases using weighted cosine similarity over state feature vectors.
     */
    @Synchronized
    fun findSimilarCases(queryFeatures: FloatArray, k: Int = 5, minSimilarity: Float = 0.5f): List<Pair<DecisionCase, Float>> {
        if (cases.isEmpty()) return emptyList()

        return cases.map { case ->
            val similarity = computeCosineSimilarity(queryFeatures, case.problemFeatures)
            case to similarity
        }
            .filter { it.second >= minSimilarity }
            .sortedByDescending { it.second }
            .take(k)
    }

    private fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        val minLen = minOf(vecA.size, vecB.size)
        if (minLen == 0) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until minLen) {
            dotProduct += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }

        val denominator = (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
        return if (denominator > 1e-6f) {
            (dotProduct / denominator).coerceIn(-1.0f, 1.0f)
        } else {
            0.0f
        }
    }

    private fun bootstrapDefaultCases() {
        // 1. Coding task with tools -> Code craftsman agent + tool execution
        cases.add(
            DecisionCase(
                id = "boot_1",
                problemFeatures = floatArrayOf(0.7f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f),
                chosenAction = DecisionAction(DecisionActionType.SELECT_AGENT, targetId = "code_craftsman"),
                outcomeReward = 0.95f,
                taskType = "CODING"
            )
        )
        // 2. High uncertainty or complex multi-step planning -> Strategic planner
        cases.add(
            DecisionCase(
                id = "boot_2",
                problemFeatures = floatArrayOf(0.9f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.8f),
                chosenAction = DecisionAction(DecisionActionType.CREATE_PLAN, targetId = "architect_orchestrator"),
                outcomeReward = 0.90f,
                taskType = "PLANNING"
            )
        )
        // 3. Web Search required -> Search tool / Tavily
        cases.add(
            DecisionCase(
                id = "boot_3",
                problemFeatures = floatArrayOf(0.5f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.3f),
                chosenAction = DecisionAction(DecisionActionType.SEARCH, targetId = "tavily_search"),
                outcomeReward = 0.88f,
                taskType = "INFORMATION_RETRIEVAL"
            )
        )
        // 4. Repeated failures -> Replan & Degrade gracefully
        cases.add(
            DecisionCase(
                id = "boot_4",
                problemFeatures = floatArrayOf(0.6f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 0.5f, 0.6f, 0.7f),
                chosenAction = DecisionAction(DecisionActionType.REPLAN),
                outcomeReward = 0.85f,
                taskType = "RECOVERY"
            )
        )
        // 5. Offline environment -> Local execution
        cases.add(
            DecisionCase(
                id = "boot_5",
                problemFeatures = floatArrayOf(0.4f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.0f, 1.0f, 0.0f, 0.1f),
                chosenAction = DecisionAction(DecisionActionType.SELECT_PROVIDER, targetId = "local_on_device"),
                outcomeReward = 0.92f,
                taskType = "OFFLINE_TASK"
            )
        )
    }
}
