package com.example.domain.core.decision

/**
 * ============================================================================
 * FIX D-1 / D-4 (audit c03919d): Tabular MDP learning store
 * ============================================================================
 *
 * The CBR-MDP engine previously kept its "transition estimates" as an
 * in-memory per-action-type EMA map — two structural defects:
 *
 *   1. No state dependence (D-1): every (action) had ONE global success
 *      estimate regardless of the state region it was taken in, so the
 *      "MDP" was nominal — no V/Q values per (s, a), no transition rates.
 *   2. No persistence (D-4): all learned values were lost on every process
 *      death, so the engine never actually accumulated experience.
 *
 * This port persists one row per (state-region, RESOURCE, action) triple:
 *
 *   - `qValue`       — running Q(s,a) estimate updated by TD learning
 *   - `visitCount`   — number of times the pair was executed
 *   - `successCount` — number of successful executions (transition success
 *                      rate = successCount / visitCount)
 *
 * State regions are coarse aggregations of DecisionState (see
 * CbrMdpEngine.stateRegionKey) — a deliberate tabular-MDP design that keeps
 * the table small and learnable on-device without a function approximator.
 */
interface MdpLearningStore {
    /** Loads every persisted (region, action) entry (called once at startup). */
    suspend fun loadAll(): List<MdpQEntry>

    /** Persists the updated entries (called asynchronously after learning updates). */
    suspend fun persist(entries: List<MdpQEntry>)
}

/**
 * The resource axis value for actions that carry NO resource/model/target
 * identity (COMPLETE, STOP, ASK_USER, …). Legacy pre-v12 rows migrate to
 * this axis so their accumulated experience keeps contributing exactly
 * where resource-less actions continue to learn.
 */
const val RESOURCE_AXIS_NONE = "R:none"

/**
 * One learned table cell: value + transition statistics for a
 * (region, RESOURCE, action) triple.
 *
 * P0/P1 CONVERGENCE (audit step 12 §4): the `resourceKey` axis makes the
 * learning RESOURCE-AWARE — the engine can now learn "SELECT_MODEL via
 * resource X outperforms resource Y in this state", which the previous
 * (region, action) key structurally collapsed into one cell.
 */
data class MdpQEntry(
    val regionKey: String,
    val resourceKey: String = RESOURCE_AXIS_NONE,
    val actionType: DecisionActionType,
    val qValue: Float,
    val visitCount: Int,
    val successCount: Int,
    val lastUpdatedEpochMs: Long = 0L,
    /**
     * REPAIR ORDER §19 — action-space version binding. Null = legacy row
     * (pre-versioning); the engine validates compatibility at load and
     * drops rows whose semantics no longer match the current action space.
     */
    val actionSpaceVersion: String? = null
)

/**
 * Pure in-memory implementation (used by unit tests and as the null-object
 * default when no persistence is wired).
 */
class InMemoryMdpLearningStore : MdpLearningStore {
    private val entries = mutableMapOf<String, MdpQEntry>()

    override suspend fun loadAll(): List<MdpQEntry> = entries.values.toList()

    override suspend fun persist(entries: List<MdpQEntry>) {
        for (e in entries) {
            this.entries["${e.regionKey}|${e.resourceKey}|${e.actionType.name}"] = e
        }
    }
}
