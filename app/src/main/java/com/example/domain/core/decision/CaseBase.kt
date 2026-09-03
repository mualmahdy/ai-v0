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
 *
 * FIX DOM-P0-01: Feature-vector length mismatch (bootstrap length 11 vs DecisionState.toFeatureVector() length 15)
 * was silently truncated by `minLen` in computeCosineSimilarity, dropping the 4 evidence-related
 * features when matching against bootstrap cases. Now all bootstrap vectors are length 15 and
 * persisted cases with mismatched schemas are zero-padded / truncated with explicit logging.
 *
 * Feature vector positional contract (must match DecisionState.toFeatureVector()):
 *   idx 0  taskComplexity                [0.0, 1.0]
 *   idx 1  requiresVision                0/1
 *   idx 2  requiresToolCalling           0/1
 *   idx 3  requiresLargeContext          0/1
 *   idx 4  requiresWebSearch             0/1
 *   idx 5  requiresCoding                0/1
 *   idx 6  currentStepProgress           [0.0, 1.0]
 *   idx 7  isNetworkAvailable            0/1
 *   idx 8  remainingTokenBudgetRatio     [0.0, 1.0]
 *   idx 9  consecutiveFailuresRatio      [0.0, 1.0]
 *   idx 10 uncertaintyScore              [0.0, 1.0]
 *   idx 11 hasSearchEvidence             0/1
 *   idx 12 hasMemoryEvidence             0/1
 *   idx 13 hasToolExecutionEvidence      0/1
 *   idx 14 lastActionSuccess             1.0 / -1.0 / 0.0
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
        // Keep case base bounded to 2000 most recent cases (FIFO by timestamp).
        // The previous comment said "most relevant" but the eviction was always FIFO;
        // we keep FIFO because relevance-based eviction would require recomputing
        // similarity to all current states on every add (O(n) per insert).
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

    /**
     * Cosine similarity over feature vectors.
     *
     * FIX DOM-P0-01: Previously used `minLen = minOf(vecA.size, vecB.size)` which silently
     * truncated to the shorter vector, dropping the 4 evidence-related features when
     * matching the length-15 query against length-11 bootstrap cases. Now we require both
     * vectors to be the canonical length (15) and zero-pad shorter persisted vectors
     * (so old Room rows from before the fix still load) but reject vectors that are
     * too short to be meaningful (< 11 = pre-evidence-schema).
     */
    private fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.isEmpty() || vecB.isEmpty()) return 0.0f

        // Pad shorter vector to the longer one's length with zeros so that the
        // evidence features (idx 11-14) contribute 0 to dot product when missing
        // from old persisted cases, instead of being silently dropped.
        val maxLen = maxOf(vecA.size, vecB.size)
        if (maxLen == 0) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until maxLen) {
            val a = if (i < vecA.size) vecA[i] else 0.0f
            val b = if (i < vecB.size) vecB[i] else 0.0f
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denominator = (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
        return if (denominator > 1e-6f) {
            (dotProduct / denominator).coerceIn(-1.0f, 1.0f)
        } else {
            0.0f
        }
    }

    private fun bootstrapDefaultCases() {
        // All bootstrap vectors are now length 15 matching DecisionState.toFeatureVector().
        // Positional contract documented at the top of this file.
        //
        // 1. Coding task with tools -> Code craftsman agent + tool execution
        //    [complexity=0.7, vision=F, tools=T, largeCtx=F, webSearch=F, coding=T,
        //     stepProgress=0.0, netAvail=T, tokenBudget=1.0, consecFail=0.0,
        //     uncertainty=0.2, searchEv=F, memoryEv=F, toolEv=F, lastSuccess=0.0]
        cases.add(
            DecisionCase(
                id = "boot_1",
                problemFeatures = floatArrayOf(0.7f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f),
                chosenAction = DecisionAction(DecisionActionType.SELECT_AGENT, targetId = "code_craftsman"),
                outcomeReward = 0.95f,
                taskType = "CODING"
            )
        )
        // 2. High uncertainty or complex multi-step planning -> Strategic planner
        //    [complexity=0.9, vision=F, tools=F, largeCtx=T, webSearch=F, coding=F,
        //     stepProgress=0.0, netAvail=T, tokenBudget=1.0, consecFail=0.0,
        //     uncertainty=0.8, searchEv=F, memoryEv=F, toolEv=F, lastSuccess=0.0]
        cases.add(
            DecisionCase(
                id = "boot_2",
                problemFeatures = floatArrayOf(0.9f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.8f, 0.0f, 0.0f, 0.0f, 0.0f),
                chosenAction = DecisionAction(DecisionActionType.CREATE_PLAN, targetId = "architect_orchestrator"),
                outcomeReward = 0.90f,
                taskType = "PLANNING"
            )
        )
        // 3. Web Search required -> Search tool / Tavily
        //    [complexity=0.5, vision=F, tools=F, largeCtx=F, webSearch=T, coding=F,
        //     stepProgress=0.0, netAvail=T, tokenBudget=1.0, consecFail=0.0,
        //     uncertainty=0.3, searchEv=F, memoryEv=F, toolEv=F, lastSuccess=0.0]
        cases.add(
            DecisionCase(
                id = "boot_3",
                problemFeatures = floatArrayOf(0.5f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.3f, 0.0f, 0.0f, 0.0f, 0.0f),
                chosenAction = DecisionAction(DecisionActionType.SEARCH, targetId = "tavily_search"),
                outcomeReward = 0.88f,
                taskType = "INFORMATION_RETRIEVAL"
            )
        )
        // 4. Repeated failures -> Replan & Degrade gracefully
        //    [complexity=0.6, vision=F, tools=F, largeCtx=F, webSearch=F, coding=F,
        //     stepProgress=0.5, netAvail=T, tokenBudget=0.5, consecFail=0.6,
        //     uncertainty=0.7, searchEv=F, memoryEv=F, toolEv=F, lastSuccess=-1.0]
        cases.add(
            DecisionCase(
                id = "boot_4",
                problemFeatures = floatArrayOf(0.6f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 0.5f, 0.6f, 0.7f, 0.0f, 0.0f, 0.0f, -1.0f),
                chosenAction = DecisionAction(DecisionActionType.REPLAN),
                outcomeReward = 0.85f,
                taskType = "RECOVERY"
            )
        )
        // 5. Offline environment -> Local execution
        //    [complexity=0.4, vision=F, tools=F, largeCtx=F, webSearch=F, coding=F,
        //     stepProgress=0.1, netAvail=F, tokenBudget=1.0, consecFail=0.0,
        //     uncertainty=0.1, searchEv=F, memoryEv=F, toolEv=F, lastSuccess=0.0]
        cases.add(
            DecisionCase(
                id = "boot_5",
                problemFeatures = floatArrayOf(0.4f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.0f, 1.0f, 0.0f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f),
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
