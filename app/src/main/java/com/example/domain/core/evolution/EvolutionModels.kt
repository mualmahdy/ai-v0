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
