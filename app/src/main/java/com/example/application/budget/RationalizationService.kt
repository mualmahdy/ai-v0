package com.example.application.budget

import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.domain.ports.observability.TelemetryPort
import com.example.infrastructure.persistence.AppDatabase

/**
 * ============================================================================
 * RationalizationService — Stage 5: توصيات الترشيد + مؤشر جودة المصالحة
 * ============================================================================
 * The last open roadmap Phase-4 item («توصيات الترشيد») plus the study's
 * Section-9 circle-3 remainder («مصالحة تتعلم من فروقها ... ويصبح الفرق
 * غير المفسر مؤشر جودة يضيق مع الزمن»).
 *
 * Everything here is a DETERMINISTIC computation over the tables, recomputed
 * per call — the I-41 forecast precedent: never cached, never persisted as
 * first-class rows. The durable artifact of a recommendation is the audit
 * event over the EXISTING telemetry channel (دون بناء جديد).
 *
 * The recommendation policy is versioned: rationalization:v1 —
 *   * under-utilization: an OPEN period with >= 3 distinct posting days
 *     (the I-38 minimum-data discipline — never guessed) whose posted
 *     consumption stayed under 50% of its limit;
 *   * overage review: an OPEN period whose |posted| exceeded the limit —
 *     the policy allowed it through; the limit deserves a review;
 *   * provider comparison: the same canonical model (VERIFIED identity
 *     match) delivered by >= 2 providers, both with measured volume —
 *     recommend the cheaper one when the per-token gap exceeds 20%. The
 *     ratio math uses double for the RATIO only; the money it reports is
 *     the exact integer micro units;
 *   * reconciliation quality: the LOCAL_UNMODELED (unexplained) share of
 *     the absolute adjustment volume per reconciliation, plus the modal
 *     historical classification as a suggestion for the provider — honest
 *     INSUFFICIENT_DATA whenever the history is thinner than three rows
 *     (never guessed).
 */
class RationalizationService(
    private val database: AppDatabase,
    private val telemetry: TelemetryPort,
    private val clock: () -> Long = System::currentTimeMillis
) {

    object Vocab {
        const val POLICY_VERSION = "rationalization:v1"

        const val KIND_UNDER_UTILIZATION = "UNDER_UTILIZATION"
        const val KIND_OVERAGE_REVIEW = "OVERAGE_REVIEW"
        const val KIND_PROVIDER_COMPARISON = "PROVIDER_COMPARISON"
        const val KIND_RECONCILIATION_QUALITY = "RECONCILIATION_QUALITY"

        /** An OPEN period must have posted on >= this many distinct days
         *  before an under-utilization verdict is allowed (I-38). */
        const val MIN_POSTING_DAYS = 3

        /** posted/limit below this ratio is under-utilization (v1 policy). */
        const val UNDER_UTILIZATION_RATIO = 0.5

        /** A per-token provider gap above this ratio recommends the cheaper
         *  side (v1 policy — the gap must clear the noise floor). */
        const val PROVIDER_GAP_RATIO = 1.2

        /** History thinner than this many rows is INSUFFICIENT_DATA (never
         *  guessed — the same discipline as the forecast). */
        const val MIN_HISTORY_FOR_SUGGESTION = 3
    }

    data class Recommendation(
        val kind: String,
        val resourceType: String,
        val resourceId: String,
        val headline: String,
        val detail: String,
        /** The exact numbers the recommendation is derived from — never a
         *  rounded story: the audit event carries them verbatim. */
        val evidence: Map<String, String>,
        val policyVersion: String = Vocab.POLICY_VERSION
    )

    // ------------------------------------------------------------------
    // Under-utilization + overage review — one pass over the periods.
    // ------------------------------------------------------------------

    suspend fun underUtilization(nowEpochMs: Long = clock()): List<Recommendation> {
        val rows = database.rationalizationDao().periodUtilization(nowEpochMs)
        val out = mutableListOf<Recommendation>()
        for (row in rows) {
            if (row.limitMicroUnits <= 0) continue
            val consumed = -row.postedEffectsMicroUnits
            val utilization = consumed.toDouble() / row.limitMicroUnits.toDouble()
            if (consumed > row.limitMicroUnits) {
                out += Recommendation(
                    kind = Vocab.KIND_OVERAGE_REVIEW,
                    resourceType = "budget_period",
                    resourceId = row.periodId,
                    headline = "the period consumed past its limit — review the limit or the overage policy",
                    detail = "posted ${consumed} micro units against a limit of ${row.limitMicroUnits} " +
                        "(${"%.1f".format(utilization * 100)}% of limit); " +
                        "the overage policy allowed it through — the next policy version deserves a review",
                    evidence = mapOf(
                        "limitMicroUnits" to row.limitMicroUnits.toString(),
                        "postedConsumptionMicroUnits" to consumed.toString(),
                        "utilization" to "%.3f".format(utilization),
                        "distinctPostingDays" to row.distinctPostingDays.toString(),
                        "remainingHoldMicroUnits" to row.remainingHoldMicroUnits.toString()
                    )
                )
            } else if (row.distinctPostingDays >= Vocab.MIN_POSTING_DAYS && utilization < Vocab.UNDER_UTILIZATION_RATIO) {
                out += Recommendation(
                    kind = Vocab.KIND_UNDER_UTILIZATION,
                    resourceType = "budget_period",
                    resourceId = row.periodId,
                    headline = "the limit is consistently under-consumed — review it downward",
                    detail = "posted ${consumed} micro units against a limit of ${row.limitMicroUnits} " +
                        "(${"%.1f".format(utilization * 100)}% of limit) over ${row.distinctPostingDays} distinct posting days; " +
                        "a lower limit on the next policy version frees the reserved headroom for other scopes",
                    evidence = mapOf(
                        "limitMicroUnits" to row.limitMicroUnits.toString(),
                        "postedConsumptionMicroUnits" to consumed.toString(),
                        "utilization" to "%.3f".format(utilization),
                        "distinctPostingDays" to row.distinctPostingDays.toString(),
                        "remainingHoldMicroUnits" to row.remainingHoldMicroUnits.toString()
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Provider comparison — the identity chain + calculated cost volume.
    // ------------------------------------------------------------------

    suspend fun providerComparison(): List<Recommendation> {
        val rows = database.rationalizationDao().providerModelCostVolume()
        val out = mutableListOf<Recommendation>()
        // Group by canonical model; compare the providers that measured it.
        val byModel = rows.groupBy { it.canonicalModelId }
        for ((modelId, providers) in byModel) {
            if (providers.size < 2) continue
            val withVolume = providers.filter { it.totalTokens > 0.0 && it.totalCostMicroUnits > 0 }
            if (withVolume.size < 2) continue
            val rated = withVolume
                .map { it to (it.totalCostMicroUnits.toDouble() / it.totalTokens) }
                .sortedByDescending { it.second }
            val most = rated.first()
            val least = rated.last()
            val gap = most.second / least.second
            if (gap < Vocab.PROVIDER_GAP_RATIO) continue
            val modelName = most.first.canonicalModelName
            val mostAtLeastRate = (least.first.totalCostMicroUnits.toDouble() / least.first.totalTokens * most.first.totalTokens)
            val savingMicro = most.first.totalCostMicroUnits - mostAtLeastRate.toLong()
            out += Recommendation(
                kind = Vocab.KIND_PROVIDER_COMPARISON,
                resourceType = "model",
                resourceId = modelId,
                headline = "$modelName is measurably cheaper on ${least.first.providerId} — review the default routing",
                detail = "per-token cost ${most.first.providerId}=%.6f vs ${least.first.providerId}=%.6f " +
                    "(${"%.1f".format(gap * 100)}% gap) over ${most.first.calculationCount + least.first.calculationCount} " +
                    "calculations; routing the default to ${least.first.providerId} would have saved " +
                    "$savingMicro micro units at the measured volume",
                evidence = buildMap {
                    put("canonicalModelId", modelId)
                    put("canonicalModelName", modelName)
                    for (r in withVolume) {
                        put(
                            "provider.${r.providerId}.costMicroUnits",
                            r.totalCostMicroUnits.toString()
                        )
                        put("provider.${r.providerId}.tokens", r.totalTokens.toString())
                        put(
                            "provider.${r.providerId}.perToken",
                            "%.9f".format(r.totalCostMicroUnits.toDouble() / r.totalTokens)
                        )
                    }
                }
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // Reconciliation quality — the unexplained share + the modal
    // classification suggestion («مصالحة تتعلم من فروقها»).
    // ------------------------------------------------------------------

    data class QualityFinding(
        val reconciliationId: String,
        val providerId: String,
        val totalAdjustments: Int,
        val unexplainedShare: Double,
        val suggestion: String?,
        val suggestionBasis: String
    )

    suspend fun reconciliationQuality(): List<QualityFinding> {
        val rows = database.rationalizationDao().reconciliationQuality()
        val out = mutableListOf<QualityFinding>()
        for (row in rows) {
            if (row.totalAdjustments == 0) continue
            val history = database.rationalizationDao().classificationHistory(row.providerId)
            val historyCount = history.sumOf { it.cnt }
            val modal = history.maxByOrNull { it.cnt }
            val suggestion: String?
            val basis: String
            if (historyCount < Vocab.MIN_HISTORY_FOR_SUGGESTION || modal == null) {
                suggestion = null
                basis = "INSUFFICIENT_DATA: $historyCount classified adjustment(s) for provider ${row.providerId} — " +
                    "the suggestion engine stays silent until ${Vocab.MIN_HISTORY_FOR_SUGGESTION} accumulate"
            } else {
                suggestion = modal.adjustmentType
                basis = "modal classification over $historyCount historical adjustment(s): " +
                    "${modal.adjustmentType} x${modal.cnt}"
            }
            val share = row.unexplainedAbsAmountMicroUnits.toDouble() / row.totalAbsAmountMicroUnits.toDouble()
            out += QualityFinding(
                reconciliationId = row.reconciliationId,
                providerId = row.providerId,
                totalAdjustments = row.totalAdjustments,
                unexplainedShare = share,
                suggestion = suggestion,
                suggestionBasis = basis
            )
        }
        return out
    }

    /** The quality findings as recommendations (the audit-facing shape). */
    suspend fun reconciliationQualityRecommendations(): List<Recommendation> =
        reconciliationQuality().map { finding ->
            Recommendation(
                kind = Vocab.KIND_RECONCILIATION_QUALITY,
                resourceType = "reconciliation_record",
                resourceId = finding.reconciliationId,
                headline = "the unexplained share is ${"%.1f".format(finding.unexplainedShare * 100)}% — it must narrow over time",
                detail = "${finding.totalAdjustments} adjustment(s); unexplained share " +
                    "${"%.3f".format(finding.unexplainedShare)}; suggestion: " +
                    (finding.suggestion ?: "none") + " (${finding.suggestionBasis})",
                evidence = mapOf(
                    "providerId" to finding.providerId,
                    "totalAdjustments" to finding.totalAdjustments.toString(),
                    "unexplainedShare" to "%.6f".format(finding.unexplainedShare),
                    "suggestedClassification" to (finding.suggestion ?: "INSUFFICIENT_DATA"),
                    "suggestionBasis" to finding.suggestionBasis
                )
            )
        }

    // ------------------------------------------------------------------
    // The durable artifact: audit events over the existing channel
    // (the forecast's early-warning precedent — دون بناء جديد).
    // ------------------------------------------------------------------

    suspend fun emit(
        recommendations: List<Recommendation>,
        asOfEpochMs: Long = clock()
    ): List<Long> {
        val ids = mutableListOf<Long>()
        for (r in recommendations) {
            val event = AuditEvent(
                id = "rationalization_${r.kind}_${r.resourceId}_$asOfEpochMs",
                severity = AuditSeverity.WARN,
                actor = "budget-intelligence",
                action = "RATIONALIZATION",
                resourceType = r.resourceType,
                resourceId = r.resourceId,
                decision = r.kind,
                reason = r.headline,
                attributes = r.evidence + ("policyVersion" to r.policyVersion),
                occurredAtEpochMs = asOfEpochMs
            )
            ids += telemetry.recordAudit(event)
        }
        return ids
    }

    /** One pass: every family, computed and emitted. Returns the events' ids. */
    suspend fun checkAndRecommend(nowEpochMs: Long = clock()): List<Long> =
        emit(
            underUtilization(nowEpochMs) +
                providerComparison() +
                reconciliationQualityRecommendations(),
            asOfEpochMs = nowEpochMs
        )
}
