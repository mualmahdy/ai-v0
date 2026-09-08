package com.example.domain.core.decision

/**
 * ============================================================================
 * DECISION CASE STORE — domain-owned persistence port (report fix:
 * "domain → infrastructure violation" in CaseBase)
 * ============================================================================
 *
 * CaseBase previously imported `DecisionCaseDao` and `DecisionCaseEntity`
 * from `com.example.infrastructure.persistence` directly, making the domain
 * layer depend on Room infrastructure. Following the established
 * [MdpLearningStore] pattern, persistence is now expressed as a domain-owned
 * port; the Room implementation lives in infrastructure
 * (`RoomDecisionCaseStore`) and is injected at the composition root.
 *
 * Semantics contract for implementors:
 *  - [loadAll] returns every persisted case (called once at startup).
 *  - [append] persists a single new case.
 *  - [pruneOldest] evicts the oldest cases when the case base exceeds its
 *    bound (the domain owns the bound; the store owns the eviction write).
 *
 * Implementations must throw on failure — the domain layer COUNTS failures
 * honestly instead of swallowing them (see CaseBase persistence accounting).
 */
interface DecisionCaseStore {
    /** Loads every persisted decision case (called once at startup). */
    suspend fun loadAll(): List<DecisionCase>

    /** Persists one new case. */
    suspend fun append(case: DecisionCase)

    /**
     * Evicts the oldest persisted cases so at most [keepMostRecent] rows
     * remain (FIFO by timestamp — mirrors the in-memory bound of CaseBase).
     */
    suspend fun pruneOldest(keepMostRecent: Int)
}

/** Pure in-memory implementation (unit tests / null-object default). */
class InMemoryDecisionCaseStore : DecisionCaseStore {
    private val cases = mutableListOf<DecisionCase>()

    override suspend fun loadAll(): List<DecisionCase> = cases.toList()

    override suspend fun append(case: DecisionCase) {
        cases.add(case)
    }

    override suspend fun pruneOldest(keepMostRecent: Int) {
        if (cases.size > keepMostRecent) {
            cases.sortBy { it.timestampMs }
            while (cases.size > keepMostRecent) cases.removeAt(0)
        }
    }
}
