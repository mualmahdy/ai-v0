package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.infrastructure.persistence.entities.BudgetAllocationEntity
import com.example.infrastructure.persistence.entities.CapabilityChangeEntity
import com.example.infrastructure.persistence.entities.CapabilityEvidenceEntity
import com.example.infrastructure.persistence.entities.CostLedgerEntryEntity
import com.example.infrastructure.persistence.entities.PricingEntryEntity
import com.example.infrastructure.persistence.entities.RadarCapabilityStateEntity
import com.example.infrastructure.persistence.entities.RadarRecommendationEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * Governance Phase DAOs — Capability Radar + Economic Budget (Room v10)
 * ============================================================================
 */

@Dao
interface CapabilityEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(evidence: CapabilityEvidenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(evidence: List<CapabilityEvidenceEntity>)

    @Query(
        "SELECT * FROM capability_evidence WHERE capabilityKey = :capabilityKey " +
            "AND (:workspaceId IS NULL OR workspaceId IS NULL OR workspaceId = :workspaceId) " +
            "ORDER BY timestampEpochMs DESC LIMIT :limit"
    )
    suspend fun recentForCapability(
        capabilityKey: String,
        workspaceId: String?,
        limit: Int
    ): List<CapabilityEvidenceEntity>

    @Query("SELECT * FROM capability_evidence WHERE executionId = :executionId ORDER BY timestampEpochMs ASC")
    suspend fun forExecution(executionId: String): List<CapabilityEvidenceEntity>

    @Query("SELECT COUNT(*) FROM capability_evidence WHERE capabilityKey = :capabilityKey")
    suspend fun countForCapability(capabilityKey: String): Int

    @Query("DELETE FROM capability_evidence WHERE timestampEpochMs < :epochMs")
    suspend fun pruneOlderThan(epochMs: Long)
}

@Dao
interface RadarCapabilityStateDao {
    @Upsert
    suspend fun upsert(status: RadarCapabilityStateEntity)

    @Upsert
    suspend fun upsertAll(statuses: List<RadarCapabilityStateEntity>)

    @Query(
        "SELECT * FROM radar_capability_states WHERE capabilityKey = :capabilityKey " +
            "AND workspaceId = :workspaceId LIMIT 1"
    )
    suspend fun get(capabilityKey: String, workspaceId: String): RadarCapabilityStateEntity?

    @Query("SELECT * FROM radar_capability_states WHERE workspaceId = :workspaceId")
    suspend fun forWorkspace(workspaceId: String): List<RadarCapabilityStateEntity>

    @Query("SELECT * FROM radar_capability_states WHERE workspaceId = :workspaceId")
    fun observeForWorkspace(workspaceId: String): Flow<List<RadarCapabilityStateEntity>>
}

@Dao
interface CapabilityChangeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(change: CapabilityChangeEntity)

    @Query(
        "SELECT * FROM capability_changes WHERE " +
            "(:workspaceId IS NULL OR workspaceId IS NULL OR workspaceId = :workspaceId) " +
            "ORDER BY detectedAtEpochMs DESC LIMIT :limit"
    )
    suspend fun recentForWorkspace(workspaceId: String?, limit: Int): List<CapabilityChangeEntity>

    @Query(
        "SELECT * FROM capability_changes WHERE " +
            "(:workspaceId IS NULL OR workspaceId IS NULL OR workspaceId = :workspaceId) " +
            "ORDER BY detectedAtEpochMs DESC LIMIT :limit"
    )
    fun observeForWorkspace(workspaceId: String?, limit: Int): Flow<List<CapabilityChangeEntity>>
}

@Dao
interface RadarRecommendationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(recommendations: List<RadarRecommendationEntity>)

    @Query(
        "SELECT * FROM radar_recommendations WHERE " +
            "(:workspaceId IS NULL OR workspaceId IS NULL OR workspaceId = :workspaceId) " +
            "AND isDismissed = 0 ORDER BY createdAtEpochMs DESC"
    )
    suspend fun activeForWorkspace(workspaceId: String?): List<RadarRecommendationEntity>

    @Query(
        "SELECT * FROM radar_recommendations WHERE " +
            "(:workspaceId IS NULL OR workspaceId IS NULL OR workspaceId = :workspaceId) " +
            "AND isDismissed = 0 ORDER BY createdAtEpochMs DESC"
    )
    fun observeForWorkspace(workspaceId: String?): Flow<List<RadarRecommendationEntity>>

    @Query("UPDATE radar_recommendations SET isDismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: String)
}

@Dao
interface PricingEntryDao {
    @Upsert
    suspend fun upsert(entry: PricingEntryEntity)

    @Query("SELECT * FROM pricing_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PricingEntryEntity?

    @Query("SELECT * FROM pricing_entries ORDER BY providerId, scopeType")
    suspend fun all(): List<PricingEntryEntity>

    @Query("SELECT * FROM pricing_entries ORDER BY providerId, scopeType")
    fun observeAll(): Flow<List<PricingEntryEntity>>

    @Query(
        "SELECT * FROM pricing_entries WHERE providerId = :providerId " +
            "AND effectiveFromEpochMs <= :atEpochMs " +
            "AND (effectiveToEpochMs IS NULL OR effectiveToEpochMs >= :atEpochMs)"
    )
    suspend fun effectiveForProvider(providerId: String, atEpochMs: Long): List<PricingEntryEntity>

    @Query("DELETE FROM pricing_entries WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CostLedgerEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CostLedgerEntryEntity)

    @Query("SELECT * FROM cost_ledger_entries WHERE executionId = :executionId ORDER BY timestampEpochMs ASC")
    suspend fun forExecution(executionId: String): List<CostLedgerEntryEntity>

    @Query(
        "SELECT * FROM cost_ledger_entries WHERE workspaceId = :workspaceId " +
            "ORDER BY timestampEpochMs DESC LIMIT :limit"
    )
    suspend fun recentForWorkspace(workspaceId: String, limit: Int): List<CostLedgerEntryEntity>

    @Query(
        "SELECT * FROM cost_ledger_entries WHERE workspaceId = :workspaceId " +
            "ORDER BY timestampEpochMs DESC"
    )
    fun observeForWorkspace(workspaceId: String): Flow<List<CostLedgerEntryEntity>>

    /** Consumed cost sum for a scope. NULL workspace dimension rows count
     *  toward every scope evaluation that matches the other dimension. */
    @Query(
        "SELECT COALESCE(SUM(costAmountMicro), 0) FROM cost_ledger_entries WHERE " +
            "(CASE WHEN :scopeType = 'WORKSPACE' THEN workspaceId = :scopeId " +
            "      WHEN :scopeType = 'AGENT' THEN agentId = :scopeId " +
            "      WHEN :scopeType = 'TASK' THEN taskId = :scopeId " +
            "      WHEN :scopeType = 'EXECUTION' THEN executionId = :scopeId " +
            "      WHEN :scopeType = 'PROVIDER' THEN providerId = :scopeId " +
            "      WHEN :scopeType = 'SERVICE' THEN serviceId = :scopeId " +
            "      WHEN :scopeType = 'MODEL' THEN modelId = :scopeId " +
            "      ELSE 1 END) " +
            "AND currency = :currency AND costStatus != 'UNKNOWN'"
    )
    suspend fun consumedCostMicro(scopeType: String, scopeId: String, currency: String): Long

    @Query(
        "SELECT COUNT(*) FROM cost_ledger_entries WHERE " +
            "(CASE WHEN :scopeType = 'WORKSPACE' THEN workspaceId = :scopeId " +
            "      WHEN :scopeType = 'AGENT' THEN agentId = :scopeId " +
            "      WHEN :scopeType = 'TASK' THEN taskId = :scopeId " +
            "      WHEN :scopeType = 'EXECUTION' THEN executionId = :scopeId " +
            "      WHEN :scopeType = 'PROVIDER' THEN providerId = :scopeId " +
            "      WHEN :scopeType = 'SERVICE' THEN serviceId = :scopeId " +
            "      WHEN :scopeType = 'MODEL' THEN modelId = :scopeId " +
            "      ELSE 1 END) " +
            "AND currency = :currency AND costStatus = 'UNKNOWN'"
    )
    suspend fun unknownCostRecordCount(scopeType: String, scopeId: String, currency: String): Int

    @Query(
        "SELECT COUNT(*) FROM cost_ledger_entries WHERE " +
            "(CASE WHEN :scopeType = 'WORKSPACE' THEN workspaceId = :scopeId " +
            "      WHEN :scopeType = 'AGENT' THEN agentId = :scopeId " +
            "      WHEN :scopeType = 'TASK' THEN taskId = :scopeId " +
            "      WHEN :scopeType = 'EXECUTION' THEN executionId = :scopeId " +
            "      WHEN :scopeType = 'PROVIDER' THEN providerId = :scopeId " +
            "      WHEN :scopeType = 'SERVICE' THEN serviceId = :scopeId " +
            "      WHEN :scopeType = 'MODEL' THEN modelId = :scopeId " +
            "      ELSE 1 END)"
    )
    suspend fun recordCountForScope(scopeType: String, scopeId: String): Int

    @Query(
        "SELECT COALESCE(SUM(totalTokens), 0) FROM cost_ledger_entries WHERE " +
            "(CASE WHEN :scopeType = 'WORKSPACE' THEN workspaceId = :scopeId " +
            "      WHEN :scopeType = 'AGENT' THEN agentId = :scopeId " +
            "      WHEN :scopeType = 'TASK' THEN taskId = :scopeId " +
            "      WHEN :scopeType = 'EXECUTION' THEN executionId = :scopeId " +
            "      WHEN :scopeType = 'PROVIDER' THEN providerId = :scopeId " +
            "      WHEN :scopeType = 'SERVICE' THEN serviceId = :scopeId " +
            "      WHEN :scopeType = 'MODEL' THEN modelId = :scopeId " +
            "      ELSE 1 END)"
    )
    suspend fun tokensConsumed(scopeType: String, scopeId: String): Long

    @Query(
        "SELECT * FROM cost_ledger_entries WHERE " +
            "(:modelId IS NULL OR modelId = :modelId) " +
            "AND (:providerId IS NULL OR providerId = :providerId) " +
            "ORDER BY timestampEpochMs DESC LIMIT :limit"
    )
    suspend fun recentForIdentity(
        providerId: String?,
        modelId: String?,
        limit: Int
    ): List<CostLedgerEntryEntity>

    @Query("DELETE FROM cost_ledger_entries WHERE timestampEpochMs < :epochMs")
    suspend fun pruneOlderThan(epochMs: Long)
}

@Dao
interface BudgetAllocationDao {
    @Upsert
    suspend fun upsert(allocation: BudgetAllocationEntity)

    @Query("SELECT * FROM budget_allocations WHERE scopeType = :scopeType AND scopeId = :scopeId LIMIT 1")
    suspend fun forScope(scopeType: String, scopeId: String): BudgetAllocationEntity?

    @Query("SELECT * FROM budget_allocations WHERE scopeType = :scopeType AND isActive = 1")
    suspend fun forType(scopeType: String): List<BudgetAllocationEntity>

    @Query("SELECT * FROM budget_allocations")
    suspend fun all(): List<BudgetAllocationEntity>

    @Query("SELECT * FROM budget_allocations")
    fun observeAll(): Flow<List<BudgetAllocationEntity>>

    @Query("UPDATE budget_allocations SET isActive = 0, updatedAtEpochMs = :now WHERE scopeType = :scopeType AND scopeId = :scopeId")
    suspend fun deactivate(scopeType: String, scopeId: String, now: Long)
}
