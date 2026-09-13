package com.example.domain.core.evolution

import com.example.domain.core.radar.RadarItem

/**
 * Stages of the Capability Evolution Pipeline:
 * DISCOVERED, UNDERSTOOD, CLASSIFIED, EVALUATED, CANDIDATE, APPROVAL_PENDING, INTEGRATED, VERIFIED, REGISTERED, REJECTED
 */
enum class EvolutionStage(val code: String, val displayName: String) {
    DISCOVERED("discovered", "مكتشف (Discovered)"),
    UNDERSTOOD("understood", "مفهوم ومعالج دلالياً (Understood)"),
    CLASSIFIED("classified", "مصنف (Classified)"),
    EVALUATED("evaluated", "تم التقييم الأمني والتقني (Evaluated)"),
    CANDIDATE("candidate", "مرشح للإدماج (Candidate)"),
    APPROVAL_PENDING("approval_pending", "بانتظار موافقة الحوكمة (Approval Pending)"),
    INTEGRATED("integrated", "مدمج بالنظام (Integrated)"),
    VERIFIED("verified", "مختبر ومحقق (Verified)"),
    REGISTERED("registered", "مسجل بمصفوفة القدرات (Registered)"),
    REJECTED("rejected", "مرفوض أو غير متوافق (Rejected)")
}

/**
 * An item progressing through the Capability Evolution Pipeline.
 */
data class EvolutionCandidate(
    val id: String,
    val radarItemId: String,
    val title: String,
    val description: String,
    val stage: EvolutionStage = EvolutionStage.DISCOVERED,
    val targetType: String, // "TOOL", "MODEL", "MCP_SERVER", "PLUGIN", "SKILL"
    val evaluationNotes: String = "",
    val securityAuditPassed: Boolean = false,
    val governanceApproved: Boolean = false,
    val confidence: Float = 0.90f,
    val provenanceUrl: String = "",
    val lastUpdatedTimestampMs: Long = System.currentTimeMillis()
)

/**
 * GAP-19 (Design Closure 2026, ADR-6 step 6): the evolution stage
 * transition table lives in the DOMAIN — the pipeline's governance gate
 * and the radar screen's action buttons BOTH derive from this ONE table
 * (previously the ordered list was duplicated inline in
 * IntelligenceRadarPipeline and the per-stage action set was hardcoded in
 * the Compose when(stage) block, so the two could drift apart).
 */
object EvolutionStageTransitions {

    /** The governance-ordered pipeline (REJECTED is reachable from anywhere). */
    val ordered: List<EvolutionStage> = listOf(
        EvolutionStage.DISCOVERED,
        EvolutionStage.UNDERSTOOD,
        EvolutionStage.CLASSIFIED,
        EvolutionStage.EVALUATED,
        EvolutionStage.CANDIDATE,
        EvolutionStage.APPROVAL_PENDING,
        EvolutionStage.INTEGRATED,
        EvolutionStage.VERIFIED,
        EvolutionStage.REGISTERED
    )

    /** The next stage in the ordered pipeline (null = terminal / REJECTED). */
    fun nextOf(stage: EvolutionStage): EvolutionStage? {
        val index = ordered.indexOf(stage)
        if (index < 0 || index + 1 >= ordered.size) return null
        return ordered[index + 1]
    }

    /** True only for a single FORWARD step (or any → REJECTED). */
    fun isAllowedTransition(from: EvolutionStage, to: EvolutionStage): Boolean {
        if (to == EvolutionStage.REJECTED) return true
        return nextOf(from) == to
    }
}
