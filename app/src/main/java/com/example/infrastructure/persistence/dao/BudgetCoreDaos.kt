package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.infrastructure.persistence.entities.BudgetHoldEntity
import com.example.infrastructure.persistence.entities.BudgetHoldSettlementEntity
import com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity
import com.example.infrastructure.persistence.entities.BudgetPeriodEntity
import com.example.infrastructure.persistence.entities.BudgetPolicyEntity
import com.example.infrastructure.persistence.entities.ScopeDescriptorEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * BUDGET CORE DAOs (Room v23, MIGRATION_22_TO_23)
 * ============================================================================
 * Design constraints baked into the DAO surface (Section و / ADR-B4):
 *  - Every insert uses the DEFAULT OnConflictStrategy.ABORT — a duplicate
 *    id must FAIL loudly, never silently replace (risk ز-4).
 *  - [BudgetLedgerEntryDao] has NO update and NO delete path at all. The
 *    ledger is append-only; the DB triggers are the second line of
 *    defense, the missing DAO paths are the first.
 *  - Idempotency replay reads (read-before-insert) are first-class
 *    citizens: every writer table has a byIdempotencyKey lookup.
 */

@Dao
interface ScopeDescriptorDao {
    @Insert
    suspend fun insert(descriptor: ScopeDescriptorEntity)

    @Query("SELECT * FROM scope_descriptor WHERE scope_id = :scopeId LIMIT 1")
    suspend fun byId(scopeId: String): ScopeDescriptorEntity?

    @Query(
        "SELECT * FROM scope_descriptor WHERE scope_kind = :scopeKind " +
            "AND canonicalization_version = :canonicalizationVersion " +
            "AND canonical_hash = :canonicalHash LIMIT 1"
    )
    suspend fun byCanonicalHash(
        scopeKind: String,
        canonicalizationVersion: Int,
        canonicalHash: String
    ): ScopeDescriptorEntity?

    @Query("SELECT * FROM scope_descriptor ORDER BY created_at_epoch_ms")
    fun observeAll(): Flow<List<ScopeDescriptorEntity>>
}

@Dao
interface BudgetPolicyDao {
    /** Append-only: a material edit inserts a NEW version row (PDM-G05). */
    @Insert
    suspend fun insert(policy: BudgetPolicyEntity)

    @Query("SELECT * FROM budget_policy WHERE budget_policy_id = :policyId LIMIT 1")
    suspend fun byId(policyId: String): BudgetPolicyEntity?

    @Query(
        "SELECT * FROM budget_policy WHERE scope_id = :scopeId " +
            "AND policy_status = :status AND effective_from_epoch_ms <= :nowEpochMs " +
            "ORDER BY policy_version DESC LIMIT 1"
    )
    suspend fun latestEffectiveForScope(scopeId: String, status: String, nowEpochMs: Long): BudgetPolicyEntity?

    @Query(
        "SELECT MAX(policy_version) FROM budget_policy WHERE policy_family_id = :familyId"
    )
    suspend fun latestVersionInFamily(familyId: String): Int?

    /** Lifecycle status transitions only (e.g. ACTIVE → RETIRED); every
     *  financial field of a version row is immutable by convention and
     *  audit requirement. */
    @Query(
        "UPDATE budget_policy SET policy_status = :status, updated_at_epoch_ms = :nowEpochMs " +
            "WHERE budget_policy_id = :policyId"
    )
    suspend fun updateStatus(policyId: String, status: String, nowEpochMs: Long)

    @Query("SELECT * FROM budget_policy WHERE policy_family_id = :familyId ORDER BY policy_version")
    suspend fun familyHistory(familyId: String): List<BudgetPolicyEntity>
}

@Dao
interface BudgetPeriodDao {
    @Insert
    suspend fun insert(period: BudgetPeriodEntity)

    @Query("SELECT * FROM budget_period WHERE budget_period_id = :periodId LIMIT 1")
    suspend fun byId(periodId: String): BudgetPeriodEntity?

    @Query(
        "SELECT * FROM budget_period WHERE budget_policy_id = :policyId " +
            "AND period_start_epoch_ms <= :nowEpochMs AND period_end_epoch_ms > :nowEpochMs " +
            "ORDER BY period_start_epoch_ms DESC LIMIT 1"
    )
    suspend fun activePeriodForPolicy(policyId: String, nowEpochMs: Long): BudgetPeriodEntity?

    /** Status transitions only — limit_micro_units has NO update path
     *  (frozen by trigger after first POSTED entry, PDM-G05 acceptance). */
    @Query(
        "UPDATE budget_period SET period_status = :status, updated_at_epoch_ms = :nowEpochMs " +
            "WHERE budget_period_id = :periodId"
    )
    suspend fun updateStatus(periodId: String, status: String, nowEpochMs: Long)

    @Query("SELECT * FROM budget_period WHERE budget_policy_id = :policyId ORDER BY period_start_epoch_ms")
    suspend fun forPolicy(policyId: String): List<BudgetPeriodEntity>
}

@Dao
interface BudgetHoldDao {
    @Insert
    suspend fun insert(hold: BudgetHoldEntity)

    @Query("SELECT * FROM budget_hold WHERE hold_id = :holdId LIMIT 1")
    suspend fun byId(holdId: String): BudgetHoldEntity?

    @Query(
        "SELECT * FROM budget_hold WHERE budget_period_id = :periodId " +
            "AND idempotency_key = :idempotencyKey LIMIT 1"
    )
    suspend fun byIdempotencyKey(periodId: String, idempotencyKey: String): BudgetHoldEntity?

    /** Status is the ONLY mutable field of a hold (one-way lifecycle). */
    @Query("UPDATE budget_hold SET status = :status WHERE hold_id = :holdId")
    suspend fun updateStatus(holdId: String, status: String)

    /**
     * Σ over ACTIVE/PARTIALLY_SETTLED, non-expired (lazy expiry evaluation,
     * risk ز-9) holds of (hold amount − settled/released so far). This is
     * the hold term of the balance equation, computed from raw rows only.
     */
    @Query(
        "SELECT COALESCE(SUM(remaining_micro_units), 0) FROM (" +
            "SELECT h.hold_amount_micro_units - " +
            "  COALESCE((SELECT SUM(s.amount_micro_units) FROM budget_hold_settlement s " +
            "            WHERE s.hold_id = h.hold_id), 0) AS remaining_micro_units " +
            "FROM budget_hold h " +
            "WHERE h.budget_period_id = :periodId " +
            "  AND h.status IN ('ACTIVE','PARTIALLY_SETTLED') " +
            "  AND (h.expires_at_epoch_ms IS NULL OR h.expires_at_epoch_ms > :nowEpochMs))"
    )
    suspend fun sumRemainingHoldsMicro(periodId: String, nowEpochMs: Long): Long

    /** Background bookkeeping for expired holds (availability already
     *  ignores them lazily — this only makes the state visible). */
    @Query(
        "UPDATE budget_hold SET status = 'EXPIRED' WHERE hold_id IN (" +
            "SELECT hold_id FROM budget_hold WHERE status IN ('ACTIVE','PARTIALLY_SETTLED') " +
            "AND expires_at_epoch_ms IS NOT NULL AND expires_at_epoch_ms <= :nowEpochMs)"
    )
    suspend fun markExpired(nowEpochMs: Long): Int

    @Query("SELECT * FROM budget_hold WHERE budget_period_id = :periodId ORDER BY created_at_epoch_ms")
    suspend fun forPeriod(periodId: String): List<BudgetHoldEntity>

    /**
     * Stage-2 (I-5 settlement hooks): the holds a specific execution's
     * authorize() created (reason = "exec:{executionId}"), still in a
     * settleable state. Ordered oldest-first — the gate creates at most
     * one, the order makes multi-hold behavior deterministic.
     */
    @Query(
        "SELECT * FROM budget_hold WHERE reason = :reason " +
            "AND status IN ('ACTIVE','PARTIALLY_SETTLED') ORDER BY created_at_epoch_ms"
    )
    suspend fun mutableHoldsForReason(reason: String): List<BudgetHoldEntity>

    /**
     * Stage-2 replay detection: ALL holds an execution created, in ANY
     * lifecycle state — a terminal hold plus a SETTLE settlement with the
     * execution's settle key proves the accountUsage hook already ran;
     * replaying must be a no-op, never a second posting.
     */
    @Query("SELECT * FROM budget_hold WHERE reason = :reason ORDER BY created_at_epoch_ms")
    suspend fun allForReason(reason: String): List<BudgetHoldEntity>
}

@Dao
interface BudgetLedgerEntryDao {
    /** Append-only insert (ABORT on conflict — never REPLACE, ADR-B4). */
    @Insert
    suspend fun insert(entry: BudgetLedgerEntryEntity)

    @Query("SELECT * FROM budget_ledger_entry WHERE entry_id = :entryId LIMIT 1")
    suspend fun byId(entryId: String): BudgetLedgerEntryEntity?

    @Query(
        "SELECT * FROM budget_ledger_entry WHERE budget_period_id = :periodId " +
            "AND idempotency_key = :idempotencyKey LIMIT 1"
    )
    suspend fun byIdempotencyKey(periodId: String, idempotencyKey: String): BudgetLedgerEntryEntity?

    /** Σ available_effect over POSTED entries — the posted term of the
     *  balance equation (consumption is negative by convention). */
    @Query(
        "SELECT COALESCE(SUM(available_effect_micro_units), 0) FROM budget_ledger_entry " +
            "WHERE budget_period_id = :periodId AND posting_state = 'POSTED'"
    )
    suspend fun sumPostedEffectsMicro(periodId: String): Long

    @Query(
        "SELECT * FROM budget_ledger_entry WHERE budget_period_id = :periodId " +
            "ORDER BY entry_timestamp_epoch_ms"
    )
    suspend fun forPeriod(periodId: String): List<BudgetLedgerEntryEntity>

    @Query(
        "SELECT * FROM budget_ledger_entry WHERE hold_id = :holdId AND posting_state = 'POSTED' " +
            "ORDER BY entry_timestamp_epoch_ms"
    )
    suspend fun postedEntriesForHold(holdId: String): List<BudgetLedgerEntryEntity>

    @Query("SELECT COUNT(*) FROM budget_ledger_entry")
    suspend fun count(): Int
    // NOTE: deliberately NO update / NO delete methods — append-only core.
}

@Dao
interface BudgetHoldSettlementDao {
    @Insert
    suspend fun insert(settlement: BudgetHoldSettlementEntity)

    @Query("SELECT * FROM budget_hold_settlement WHERE settlement_id = :settlementId LIMIT 1")
    suspend fun byId(settlementId: String): BudgetHoldSettlementEntity?

    @Query(
        "SELECT * FROM budget_hold_settlement WHERE hold_id = :holdId " +
            "AND idempotency_key = :idempotencyKey LIMIT 1"
    )
    suspend fun byIdempotencyKey(holdId: String, idempotencyKey: String): BudgetHoldSettlementEntity?

    @Query(
        "SELECT COALESCE(SUM(amount_micro_units), 0) FROM budget_hold_settlement WHERE hold_id = :holdId"
    )
    suspend fun totalSettledMicro(holdId: String): Long

    @Query("SELECT * FROM budget_hold_settlement WHERE hold_id = :holdId ORDER BY created_at_epoch_ms")
    suspend fun forHold(holdId: String): List<BudgetHoldSettlementEntity>
}
