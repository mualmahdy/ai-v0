package com.example.infrastructure.persistence.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * DB v22 — CLOSURE FINAL STAGE (§5/item 4 — B6, central transfer id mapping)
 * ============================================================================
 * ONE durable, queryable mapping table for EVERY id remapped during a
 * project transfer/import. The audit (step 5 §5.4/B6) found the remapping
 * scattered as ad-hoc locals (a sessions-only local map) and inline
 * collision checks, leaving cross-entity references DANGLING after import
 * (artifact→session always, task→parent and turn-attachments whenever the
 * source id collided).
 *
 * Every imported entity id lands here — written INSIDE the same Room
 * transaction as the rows it maps, so the table is the durable substitute
 * for the in-memory maps and the honest record of what the import renamed:
 *
 *   - consumers rebind cross-references through it (one lookup, one truth);
 *   - the REBUILD_INDEXES recovery pass can find documents whose chunk
 *     rebuild never completed (totalChunks = 0 with a mapping row);
 *   - future dedup / re-import detection has its ledger.
 *
 * Rows are append-only history: a later import under the SAME operationId
 * would collide on the (operationId, entityType, sourceId) key and REPLACE
 * (idempotent per operation).
 */
@Entity(
    tableName = "id_mappings",
    indices = [
        Index("operationId"),
        Index(value = ["entityType", "sourceId"]),
        Index(value = ["entityType", "targetId"]),
        Index("projectId"),
        Index("createdAtEpochMs")
    ]
)
data class IdMappingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The transfer/import operation this mapping belongs to. */
    val operationId: String,
    /** IdMappingEntityType.name — PROJECT / DOCUMENT / SESSION / TURN / TIMELINE_EVENT / TASK / ARTIFACT. */
    val entityType: String,
    /** The id INSIDE the package (source device). */
    val sourceId: String,
    /** The id in THIS database after import. */
    val targetId: String,
    /** True when sourceId == targetId (kept after collision check). */
    val unchanged: Boolean,
    val workspaceId: String,
    val projectId: Long?,
    val createdAtEpochMs: Long
)

/** The entity families whose ids can be remapped by a transfer/import. */
enum class IdMappingEntityType {
    PROJECT,
    DOCUMENT,
    SESSION,
    TURN,
    TIMELINE_EVENT,
    TASK,
    ARTIFACT
}
