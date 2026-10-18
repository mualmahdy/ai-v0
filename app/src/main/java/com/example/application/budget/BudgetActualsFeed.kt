package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import java.math.BigDecimal

/**
 * ============================================================================
 * BudgetActualsFeed — Stage 4: the decision-engine bridge (Circle 1).
 * ============================================================================
 * The study, Section 9, Circle 1: «اليوم يغذّي المحرك أرصدة تقريبية مبسطة؛
 * وبعد المرحلة الأولى يمكن تغذيته مباشرة بقيمة المتاح من معادلة الرصيد
 * المعتمدة (حد الفترة + آثار القيود المنشورة − الحجوزات النشطة المتبقية)،
 * فيتحول التعلم من تقديرات إلى محاسبة فعلية. كما يتيح جدول الاستحقاقات
 * المستقبلي احتساب الرصيد الفعلي بما يشمل الائتمانات والpakages المجانية».
 *
 * This feed turns the ACCOUNTING actuals into DecisionState contextFeatures
 * (the same relative-resource form as the CBR b_i axis — the
 * remainingTokenBudgetRatio [0,1] precedent). Honesty rules (I-39):
 *  - MONEY never enters the feature vector as an absolute Float (a Float
 *    mantissa is 24 bits — cents would be fabricated). The authoritative
 *    money gate is the economic gate (authorize -> holds); this bridge
 *    feeds the RELATIVE resource state the learning sees.
 *  - quantity/provider-credit entitlement remainings are NOT money and
 *    never enter the ratio; only ACTIVE MONETARY_CREDIT rows do.
 *  - no active policy -> the features are honestly ABSENT (empty map),
 *    never zero-filled: the engine keeps learning on the platform's
 *    legacy approximation exactly as before.
 */
class BudgetActualsFeed(
    private val ledger: BudgetLedgerService,
    private val entitlements: EntitlementService,
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * The feed keys. All ratios are honest divisions of measured quantities
     * (never clamped to look nice — a >1 ratio means credits cover more
     * than a full period; negative means overspent).
     */
    object Keys {
        const val HAS_ACTIVE_POLICY = "budget.hasActivePolicy"
        const val AVAILABLE_RATIO = "budget.availableRatio"
        const val EFFECTIVE_AVAILABLE_RATIO = "budget.effectiveAvailableRatio"
        const val EXHAUSTED = "budget.exhausted"
        const val CREDITS_RATIO = "budget.creditsRatio"
    }

    /**
     * Scope priority mirrors HoldBasedBudgetGate (و.6): WORKSPACE -> AGENT
     * -> TASK -> global APPLICATION. The first scope with an ACTIVE policy
     * + covering period is the scope whose balance feeds the engine.
     */
    suspend fun featuresFor(
        workspaceObjectId: String?,
        agentObjectId: String?,
        taskObjectId: String?,
        nowEpochMs: Long = clock()
    ): Map<String, Float> {
        val candidates: List<Pair<String, String?>> = buildList {
            workspaceObjectId?.let { add("WORKSPACE" to it) }
            agentObjectId?.let { add("AGENT" to it) }
            taskObjectId?.let { add("TASK" to it) }
            add("APPLICATION" to null)
        }
        for ((scopeType, objectId) in candidates) {
            val active = ledger.activeBudgetForScope(scopeType, objectId, nowEpochMs) ?: continue
            val balance = ledger.availableForPeriod(active.period.budgetPeriodId, nowEpochMs)
            val available = balance.availableMicroUnits
            val limit = balance.limitMicroUnits

            // Monetary credits for the SAME scope (I-39: money only). The
            // descriptor lookup is deterministic — scopeIdFor(same args)
            // returns the SAME descriptor activeBudgetForScope matched.
            val scopeId = ledger.scopeIdFor(scopeType, objectId, nowEpochMs)
            val creditsMicro = if (scopeId != null) {
                val rows = database.entitlementRecordDao()
                    .activeMonetaryCreditsForScope(scopeId, nowEpochMs)
                var sum = BigDecimal.ZERO
                for (row in rows) {
                    val rem = entitlements.remaining(row.entitlementId)
                    if (rem is EntitlementService.Remaining.RemainingValue) {
                        sum = sum.add(rem.value)
                    }
                }
                sum.longValueExact()
            } else 0L

            val exhausted = if (available <= 0) 1f else 0f
            val availableRatio = if (limit > 0) available.toFloat() / limit.toFloat() else Float.NaN
            val creditsRatio = if (limit > 0) creditsMicro.toFloat() / limit.toFloat() else 0f
            val effectiveRatio = if (limit > 0) (available + creditsMicro).toFloat() / limit.toFloat() else Float.NaN
            return mapOf(
                Keys.HAS_ACTIVE_POLICY to 1f,
                Keys.AVAILABLE_RATIO to (if (availableRatio.isNaN()) 0f else availableRatio),
                Keys.EFFECTIVE_AVAILABLE_RATIO to (if (effectiveRatio.isNaN()) 0f else effectiveRatio),
                Keys.EXHAUSTED to exhausted,
                Keys.CREDITS_RATIO to creditsRatio
            )
        }
        // No active policy anywhere in the priority chain — honest absence.
        return emptyMap()
    }
}
