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
 * This port persists one row per (state-region, action) pair:
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

/** One learned table cell: value + transition statistics for a (region, action). */
data class MdpQEntry(
    val regionKey: String,
    val actionType: DecisionActionType,
    val qValue: Float,
    val visitCount: Int,
    val successCount: Int,
    val lastUpdatedEpochMs: Long = 0L
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
            this.entries["${e.regionKey}|${e.actionType.name}"] = e
        }
    }
}
