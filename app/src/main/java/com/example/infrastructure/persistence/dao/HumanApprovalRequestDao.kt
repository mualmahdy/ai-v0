package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.HumanApprovalRequestEntity

/**
 * P1-15 / audit 2026 §17: DAO for the DURABLE human approval store.
 */
@Dao
interface HumanApprovalRequestDao {

    @Query("SELECT * FROM human_approval_requests WHERE approvalId = :approvalId LIMIT 1")
    suspend fun find(approvalId: String): HumanApprovalRequestEntity?

    @Query(
        "SELECT * FROM human_approval_requests WHERE executionId = :executionId " +
            "AND toolName = :toolName AND resolution = 'PENDING' LIMIT 1"
    )
    suspend fun findPendingFor(executionId: String, toolName: String): HumanApprovalRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HumanApprovalRequestEntity)

    @Query(
        "UPDATE human_approval_requests SET resolution = :resolution, resolvedBy = :resolvedBy, " +
            "resolvedAtEpochMs = :resolvedAtEpochMs WHERE approvalId = :approvalId"
    )
    suspend fun resolve(approvalId: String, resolution: String, resolvedBy: String, resolvedAtEpochMs: Long)

    @Query(
        "UPDATE human_approval_requests SET resolution = 'EXPIRED', resolvedBy = 'system:ttl', " +
            "resolvedAtEpochMs = :nowEpochMs WHERE resolution = 'PENDING' AND expiresAtEpochMs < :nowEpochMs"
    )
    suspend fun expireStale(nowEpochMs: Long): Int

    @Query("UPDATE human_approval_requests SET isTokenConsumed = 1 WHERE approvalId = :approvalId")
    suspend fun markTokenConsumed(approvalId: String)

    @Query("SELECT COUNT(*) FROM human_approval_requests WHERE approvalId = :approvalId AND isTokenConsumed = 1")
    suspend fun isTokenConsumed(approvalId: String): Int

    @Query("SELECT COUNT(*) FROM human_approval_requests")
    suspend fun countAll(): Int
}
