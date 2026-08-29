package com.example.domain.core.decision

import com.example.infrastructure.persistence.dao.DecisionCaseDao
import com.example.infrastructure.persistence.entities.DecisionCaseEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.sqrt

/**
 * Case Base storing historical decision experiences with similarity retrieval.
 * Supports asynchronous Room Database persistence to maintain learned experiences across app restarts.
 */
class CaseBase(
    private val decisionCaseDao: DecisionCaseDao? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val cases = mutableListOf<DecisionCase>()

    init {
        // Load initial bootstrap cases first
        bootstrapDefaultCases()
        // Load persisted cases from Room DB if DAO is provided
        if (decisionCaseDao != null) {
            coroutineScope.launch {
                try {
                    val persistedEntities = decisionCaseDao.getAllCases()
                    if (persistedEntities.isNotEmpty()) {
                        val mappedCases = persistedEntities.map { entity ->
                            entity.toDomain()
                        }
                        synchronized(this@CaseBase) {
                            // Merge avoiding ID duplication
                            val existingIds = cases.map { it.id }.toSet()
                            for (c in mappedCases) {
                                if (c.id !in existingIds) {
                                    cases.add(c)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Fallback to in-memory bootstrap cases
                }
            }
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

        // Persist to Room asynchronously
        decisionCaseDao?.let { dao ->
            coroutineScope.launch {
                try {
                    dao.insertCase(case.toEntity())
                } catch (_: Exception) {
                    // Non-fatal logging
                }
            }
        }
    }

    @Synchronized
    fun getAllCases(): List<DecisionCase> = cases.toList()

    /**
     * Finds the k-nearest historical cases using weighted cosine similarity over state feature vectors.
     */
    @Synchronized
    fun findSimilarCases(queryFeatures: FloatArray, k: Int = 5, minSimilarity: Float = 0.4f): List<Pair<DecisionCase, Float>> {
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

    private fun DecisionCase.toEntity(): DecisionCaseEntity {
        val arr = JSONArray()
        problemFeatures.forEach { arr.put(it.toDouble()) }
        return DecisionCaseEntity(
            id = id,
            featuresJson = arr.toString(),
            actionType = chosenAction.type.name,
            targetId = chosenAction.targetId,
            outcomeReward = outcomeReward,
            taskType = taskType,
            timestampEpochMs = timestampMs
        )
    }

    private fun DecisionCaseEntity.toDomain(): DecisionCase {
        val arr = JSONArray(featuresJson)
        val floats = FloatArray(arr.length())
        for (i in 0 until arr.length()) {
            floats[i] = arr.getDouble(i).toFloat()
        }
        val type = try {
            DecisionActionType.valueOf(actionType)
        } catch (_: Exception) {
            DecisionActionType.EXECUTE_STEP
        }
        return DecisionCase(
            id = id,
            problemFeatures = floats,
            chosenAction = DecisionAction(type, targetId),
            outcomeReward = outcomeReward,
            timestampMs = timestampEpochMs,
            taskType = taskType
        )
    }
}
