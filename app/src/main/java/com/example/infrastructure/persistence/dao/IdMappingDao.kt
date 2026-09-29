package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.infrastructure.persistence.entities.IdMappingEntity

/**
 * CLOSURE FINAL STAGE (§5/item 4 — B6): DAO for the central transfer id
 * mapping table. Written INSIDE the import transaction (one lookup, one
 * truth for every cross-entity reference rebind); read by recovery/
 * diagnostics and future re-import detection.
 */
@Dao
interface IdMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mappings: List<IdMappingEntity>)

    @Query("SELECT * FROM id_mappings WHERE operationId = :operationId ORDER BY id")
    suspend fun forOperation(operationId: String): List<IdMappingEntity>

    @Query(
        "SELECT * FROM id_mappings WHERE entityType = :entityType AND sourceId = :sourceId " +
                "ORDER BY createdAtEpochMs DESC LIMIT 1"
    )
    suspend fun latestForSource(entityType: String, sourceId: String): IdMappingEntity?

    @Query(
        "SELECT * FROM id_mappings WHERE entityType = :entityType AND targetId = :targetId " +
                "ORDER BY createdAtEpochMs DESC LIMIT 1"
    )
    suspend fun latestForTarget(entityType: String, targetId: String): IdMappingEntity?

    @Query("SELECT COUNT(*) FROM id_mappings WHERE operationId = :operationId")
    suspend fun countForOperation(operationId: String): Int

    @Query("DELETE FROM id_mappings WHERE operationId = :operationId")
    suspend fun deleteForOperation(operationId: String)
}
