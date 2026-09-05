package com.example.domain.core.memory.lifecycle

import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryType

/**
 * ============================================================================
 * Memory Intelligence Domain Models — Phase 5
 * ============================================================================
 *
 * Extends the original 4-type MemoryType taxonomy (PREFERENCE,
 * FACTUAL_INSIGHT, CASE_EXAMPLE, CONVERSATION_SUMMARY) with the cognitive
 * memory types requested in the audit:
 *
 *   WORKING        — short-term scratch buffer for the current execution
 *   EPISODIC       — what happened during a specific task/session
 *   SEMANTIC       — distilled facts the user has confirmed
 *   PROCEDURAL     — how-to knowledge (tool sequences that worked)
 *   USER_PREFERENCE — explicit user-stated preferences
 *   WORKSPACE      — facts scoped to a workspace
 *   AGENT          — facts scoped to a specific agent
 *
 * The legacy four are kept for backwards compatibility (existing
 * `MemoryEntity.memoryType` defaults to "FACTUAL_INSIGHT"). The new
 * `CognitiveMemoryType` enum is what `MemoryLifecycleService` works in;
 * it round-trips losslessly to/from the legacy `MemoryType` and the
 * `memoryType` String column on `MemoryEntity`.
 */

/**
 * Full cognitive memory taxonomy. Maps 1:1 to a String value stored in
 * `MemoryEntity.memoryType` (the column added in MIGRATION_7_TO_8).
 */
enum class CognitiveMemoryType(val storageCode: String, val displayLabelAr: String) {
    WORKING("WORKING", "ذاكرة عاملة"),
    EPISODIC("EPISODIC", "ذاكرة حلقات"),
    SEMANTIC("SEMANTIC", "ذاكرة دلالية"),
    PROCEDURAL("PROCEDURAL", "ذاكرة إجرائية"),
    USER_PREFERENCE("USER_PREFERENCE", "تفضيلات المستخدم"),
    WORKSPACE("WORKSPACE", "ذاكرة مساحة العمل"),
    AGENT("AGENT", "ذاكرة الوكيل"),
    // Legacy types — kept so existing rows read back correctly.
    FACTUAL_INSIGHT("FACTUAL_INSIGHT", "حقيقة"),
    CASE_EXAMPLE("CASE_EXAMPLE", "مثال حالة"),
    CONVERSATION_SUMMARY("CONVERSATION_SUMMARY", "ملخص محادثة"),
    PREFERENCE("PREFERENCE", "تفضيل (قديم)");

    companion object {
        fun fromStorageCode(code: String): CognitiveMemoryType =
            entries.firstOrNull { it.storageCode == code } ?: FACTUAL_INSIGHT

        /**
         * Convert a legacy `MemoryType` to the new taxonomy. PREFERENCE maps
         * to USER_PREFERENCE (the new naming) for forward compatibility.
         */
        fun fromLegacy(legacy: MemoryType): CognitiveMemoryType = when (legacy) {
            MemoryType.PREFERENCE -> USER_PREFERENCE
            MemoryType.FACTUAL_INSIGHT -> FACTUAL_INSIGHT
            MemoryType.CASE_EXAMPLE -> CASE_EXAMPLE
            MemoryType.CONVERSATION_SUMMARY -> CONVERSATION_SUMMARY
        }
    }
}

/**
 * Decay policy for a single memory type. Different memory types decay at
 * different rates (working memory fast, semantic memory slow).
 */
data class MemoryDecayPolicy(
    val type: CognitiveMemoryType,
    val halfLifeMs: Long,           // time for decay_score to halve
    val minDecayScore: Float = 0.05f, // floor — never fully zero out
    val importanceWeight: Float = 1.0f, // higher importance slows decay
    val accessBoost: Float = 0.2f   // each access bumps decay_score by this much (capped at 1.0)
) {
    companion object {
        /**
         * Default policies per memory type. The numbers are calibrated so:
         *   - WORKING decays in hours (4h half-life)
         *   - EPISODIC decays in days (3d half-life)
         *   - PROCEDURAL decays in weeks (30d half-life)
         *   - SEMANTIC / USER_PREFERENCE effectively never decay (5y half-life)
         */
        fun defaults(): List<MemoryDecayPolicy> = listOf(
            MemoryDecayPolicy(CognitiveMemoryType.WORKING, 4L * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.EPISODIC, 3L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.PROCEDURAL, 30L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.CONVERSATION_SUMMARY, 14L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.CASE_EXAMPLE, 90L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.FACTUAL_INSIGHT, 90L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.WORKSPACE, 180L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.AGENT, 60L * 24 * 60 * 60 * 1000),
            MemoryDecayPolicy(CognitiveMemoryType.USER_PREFERENCE, 5L * 365 * 24 * 60 * 60 * 1000L),
            MemoryDecayPolicy(CognitiveMemoryType.SEMANTIC, 5L * 365 * 24 * 60 * 60 * 1000L),
            MemoryDecayPolicy(CognitiveMemoryType.PREFERENCE, 5L * 365 * 24 * 60 * 60 * 1000L)
        )

        fun forType(type: CognitiveMemoryType): MemoryDecayPolicy =
            defaults().firstOrNull { it.type == type } ?: defaults().first()
    }
}

/**
 * Consolidation request — merge two similar memories into one. Used by
 * the background consolidator when it detects near-duplicate semantic
 * memories.
 */
data class MemoryConsolidationRequest(
    val sourceMemoryIds: List<String>,
    val targetContent: String,
    val targetType: CognitiveMemoryType,
    val confidenceBoost: Float = 0.1f,
    val reason: String
)

/**
 * Forgetting policy. Drives which memories get archived/deleted when
 * the store exceeds a size threshold or when decay_score drops below
 * `minDecayScore` for an extended period.
 */
data class ForgettingPolicy(
    val maxActiveMemoriesPerWorkspace: Int = 500,
    val maxActiveMemoriesPerAgent: Int = 200,
    val globalMaxActiveMemories: Int = 5000,
    val archiveDecayThreshold: Float = 0.02f,
    val deleteArchivedAfterMs: Long = 90L * 24 * 60 * 60 * 1000
)

/**
 * Ranked memory record — extends `ScoredMemoryRecord` with the new
 * ranking dimensions (importance × confidence × recency × decay).
 */
data class RankedMemoryRecord(
    val entry: MemoryEntry,
    val cognitiveType: CognitiveMemoryType,
    val similarityScore: Float,
    val importanceScore: Float,
    val recencyScore: Float,
    val decayScore: Float,
    val finalRankScore: Float,
    val workspaceId: String?,
    val agentId: String?
)

/**
 * Memory namespace descriptor — the scope at which a memory lives.
 */
data class MemoryNamespace(
    val namespaceId: String,
    val workspaceId: String,
    val agentId: String,
    val scope: MemoryScope
)

enum class MemoryScope { PRIVATE, SHARED_WITH_WORKSPACE, GLOBAL }
