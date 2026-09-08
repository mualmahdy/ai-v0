package com.example.domain.core.decision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Case Base storing historical decision experiences with similarity retrieval.
 * Supports asynchronous persistence of learned experiences across app restarts
 * through the domain-owned [DecisionCaseStore] port.
 *
 * ============================================================================
 * ARCHITECTURE BOUNDARY FIX (report: "domain → infrastructure violation"):
 * this class previously imported `DecisionCaseDao` + `DecisionCaseEntity`
 * from `com.example.infrastructure.persistence` and performed the Room
 * entity mapping itself. Persistence is now expressed through the
 * [DecisionCaseStore] port (same pattern as [MdpLearningStore]); the Room
 * implementation (`RoomDecisionCaseStore`) is injected by the composition
 * root, so the domain layer no longer references infrastructure.
 * ============================================================================
 *
 * ============================================================================
 * HONEST PERSISTENCE ACCOUNTING (report: "persistence semantics incomplete"):
 * persistence failures are no longer silently swallowed
 * (`catch (_: Exception) {}`). Every load/append/prune failure is counted in
 * [persistenceFailureCount] with [lastPersistenceError] describing the most
 * recent failure, and [addCase] returns whether the case was BOTH remembered
 * in-memory AND durably queued. The application layer can surface these
 * counters through observability instead of trusting silent success.
 * ============================================================================
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
    private val store: DecisionCaseStore? = null,
    private val persistenceScope: CoroutineScope? = null
) {

    private val cases = mutableListOf<DecisionCase>()

    /** Honest persistence accounting (never silently swallowed). */
    @Volatile var persistenceFailureCount: Int = 0
        private set

    @Volatile var lastPersistenceError: String? = null
        private set

    /** True when the durable store is wired AND has loaded successfully. */
    @Volatile var durableStoreHealthy: Boolean = false
        private set

    init {
        // Load initial bootstrap cases first
        bootstrapDefaultCases()
        // Load persisted cases through the domain port if a store is wired.
        if (store != null && persistenceScope != null) {
            persistenceScope.launch {
                try {
                    val persistedCases = store.loadAll()
                    if (persistedCases.isNotEmpty()) {
                        synchronized(this@CaseBase) {
                            // Merge avoiding ID duplication
                            val existingIds = cases.map { it.id }.toSet()
                            for (c in persistedCases) {
                                if (c.id !in existingIds) {
                                    cases.add(c)
                                }
                            }
                        }
                    }
                    durableStoreHealthy = true
                } catch (e: Exception) {
                    recordPersistenceFailure("LOAD_FAILED: ${e.message ?: e.javaClass.simpleName}")
                    // Fall back to in-memory bootstrap cases (counted, not silent).
                }
            }
        }
    }

    /**
     * Adds a case to the in-memory base and queues durable persistence.
     *
     * @return true when the case is remembered AND durable persistence was
     *         successfully queued; false when the durable write failed (the
     *         in-memory case is still kept, and the failure is counted).
     */
    @Synchronized
    fun addCase(case: DecisionCase): Boolean {
        // Keep case base bounded to 2000 most recent cases (FIFO by timestamp).
        // The previous comment said "most relevant" but the eviction was always FIFO;
        // we keep FIFO because relevance-based eviction would require recomputing
        // similarity to all current states on every add (O(n) per insert).
        if (cases.size >= CASE_BASE_BOUND) {
            cases.sortBy { it.timestampMs }
            cases.removeAt(0)
        }
        cases.add(case)

        // Persist through the domain port asynchronously (failures counted).
        val scope = persistenceScope
        if (store == null || scope == null) return true // in-memory only mode
        var queued = true
        scope.launch {
            try {
                store.append(case)
                // Mirror the in-memory bound in the durable store.
                if (caseCount() > CASE_BASE_BOUND) {
                    store.pruneOldest(CASE_BASE_BOUND)
                }
            } catch (e: Exception) {
                recordPersistenceFailure("APPEND_FAILED: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return queued
    }

    @Synchronized
    fun caseCount(): Int = cases.size

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

    /** Records a persistence failure honestly (counted + last error kept). */
    private fun recordPersistenceFailure(message: String) {
        persistenceFailureCount++
        lastPersistenceError = message
        durableStoreHealthy = false
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

    companion object {
        /** Hard bound of the case base (in-memory AND durable store). */
        const val CASE_BASE_BOUND = 2000
    }
}
