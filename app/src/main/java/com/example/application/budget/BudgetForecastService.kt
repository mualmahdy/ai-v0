package com.example.application.budget

import com.example.application.observability.TelemetryService
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.AuditSeverity
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.dao.DailyPostedEffect
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ============================================================================
 * BudgetForecastService — Stage 4: the budget-depletion forecast (Circle 2
 * of the intelligence layer).
 * ============================================================================
 * Roadmap Phase-4 exit criterion: «تنبؤ بنفاد الميزانية يصدر قبل نفادها
 * بسبعة أيام على الأقل بدقة مقبولة على بيانات فعلية». Method (the study,
 * Section 9, Circle 2): the daily consumption rate measured from the
 * CURRENT period's posted ledger entries, a weighted moving average, the
 * projected depletion day, and a confidence around it — with the alert
 * channel riding the EXISTING telemetry service («دون بناء جديد»).
 *
 * Honesty rules (NEVER guessed):
 *  - the projection needs >= [minDataDays] distinct posting days in the
 *    window; fewer -> INSUFFICIENT_DATA and NO projection is emitted;
 *  - available comes from the balance equation as of the evaluation moment
 *    (holds included) — it may honestly be negative (already exhausted);
 *  - a zero/negative weighted rate means no depletion is projectable
 *    (periods that refill or net-positive days) — projectedDepletion is
 *    null, never zero;
 *  - the accuracy definition (I-40): at the first warning, the projected
 *    day is within ±2 days of the realized depletion day on actual data.
 *
 * The forecast itself is a DETERMINISTIC computation over the ledger and
 * is reproducible at any moment — it is not persisted as a first-class
 * row (the PDM has no forecast table; interpretations I-41). The durable
 * artifact is the early-warning audit event (TelemetryService.recordAudit).
 */
class BudgetForecastService(
    private val ledger: BudgetLedgerService,
    private val database: AppDatabase,
    private val telemetry: TelemetryService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class Confidence { HIGH, MEDIUM, LOW, INSUFFICIENT_DATA }

    /** One calendar day of the burn series (positive = net spend that day). */
    data class DailyBurn(
        val utcDay: Long,
        val burnMicroUnits: Long,
        val weight: Int
    )

    data class ForecastResult(
        val periodId: String,
        val asOfEpochMs: Long,
        val availableNowMicroUnits: Long,
        val weightedDailyBurnMicroUnits: Long,
        val distinctPostingDays: Int,
        val windowCalendarDays: Int,
        val series: List<DailyBurn>,
        val confidence: Confidence,
        val projectedDepletionEpochMs: Long?,
        val daysUntilDepletion: Long?,
        val reason: String
    ) {
        val depletionProjectable: Boolean get() = projectedDepletionEpochMs != null
    }

    sealed class WarningOutcome {
        data class Emitted(val result: ForecastResult, val auditEventId: String) : WarningOutcome()
        data class Suppressed(val result: ForecastResult, val why: String) : WarningOutcome()
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val UTC_DAY_MS = 86_400_000L

        /** Distinct posting days required before ANY projection (I-38). */
        const val MIN_DATA_DAYS = 3

        /** The WMA calendar window (days); the most recent day weighs most. */
        const val WINDOW_CALENDAR_DAYS = 14

        /** A warning fires when the projection is within this horizon. */
        const val DEFAULT_HORIZON_DAYS = 14

        /** The exit criterion's bound: lead time must be at least this. */
        const val REQUIRED_LEAD_DAYS = 7

        /** The acceptable-accuracy bound (I-40): ±2 days at first warning. */
        const val ACCURACY_TOLERANCE_DAYS = 2
    }

    // ------------------------------------------------------------------
    // The forecast
    // ------------------------------------------------------------------

    suspend fun forecast(periodId: String, asOfEpochMs: Long = clock()): ForecastResult {
        val balance = ledger.availableForPeriod(periodId, asOfEpochMs)
        val raw = database.budgetForecastDao().postedEffectsByDay(periodId)

        // Calendar-day series over the window ending at the evaluation day.
        // Gap days contribute ZERO burn (I-38) — a silent week lowers the
        // average honestly instead of being skipped.
        val asOfDay = asOfEpochMs / UTC_DAY_MS
        val postingsByDay = raw.associate { it.utcDay to it.postedEffectMicroUnits }
        val firstPostingDay = postingsByDay.keys.minOrNull()
        val windowStart = maxOf(asOfDay - (WINDOW_CALENDAR_DAYS - 1), firstPostingDay ?: asOfDay)
        val windowDays = (windowStart..asOfDay).toList()
        val series = windowDays.mapIndexed { index, day ->
            val posted = postingsByDay[day] ?: 0L
            // consumption is negative by convention (و.2) — burn is the negated
            // net effect; a refund day can honestly be negative (reduces the WMA).
            val burn = -posted
            DailyBurn(utcDay = day, burnMicroUnits = burn, weight = index + 1)
        }

        val distinctPostingDays = postingsByDay.size
        val dataDays = postingsByDay.keys.count { it in windowStart..asOfDay }

        if (dataDays < MIN_DATA_DAYS) {
            return ForecastResult(
                periodId = periodId,
                asOfEpochMs = asOfEpochMs,
                availableNowMicroUnits = balance.availableMicroUnits,
                weightedDailyBurnMicroUnits = 0,
                distinctPostingDays = distinctPostingDays,
                windowCalendarDays = windowDays.size,
                series = series,
                confidence = Confidence.INSUFFICIENT_DATA,
                projectedDepletionEpochMs = null,
                daysUntilDepletion = null,
                reason = "insufficient data: $dataDays posting day(s) in window (< $MIN_DATA_DAYS); no projection is guessed"
            )
        }

        val weightSum = series.sumOf { it.weight }
        val weightedBurn = series.sumOf { BigDecimal(it.burnMicroUnits).multiply(BigDecimal(it.weight)) }
            .divide(BigDecimal(weightSum), 0, RoundingMode.HALF_UP).longValueExact()

        // Confidence from data volume + day-to-day variation.
        val burns = series.map { it.burnMicroUnits.toDouble() }
        val mean = burns.average()
        val cv = if (mean == 0.0) 0.0 else kotlin.runCatching {
            val sd = Math.sqrt(burns.map { (it - mean) * (it - mean) }.average())
            sd / Math.abs(mean)
        }.getOrDefault(Double.MAX_VALUE)
        val confidence = when {
            dataDays >= 7 && cv < 0.5 -> Confidence.HIGH
            dataDays >= 5 && cv < 1.0 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val available = balance.availableMicroUnits
        if (weightedBurn <= 0) {
            return ForecastResult(
                periodId = periodId,
                asOfEpochMs = asOfEpochMs,
                availableNowMicroUnits = available,
                weightedDailyBurnMicroUnits = weightedBurn,
                distinctPostingDays = distinctPostingDays,
                windowCalendarDays = windowDays.size,
                series = series,
                confidence = confidence,
                projectedDepletionEpochMs = null,
                daysUntilDepletion = null,
                reason = "no positive weighted burn (net-negative/zero spend) — depletion not projectable"
            )
        }

        if (available <= 0) {
            // Already exhausted (or overspent) at the evaluation moment — the
            // projection is "now", reported honestly.
            return ForecastResult(
                periodId = periodId,
                asOfEpochMs = asOfEpochMs,
                availableNowMicroUnits = available,
                weightedDailyBurnMicroUnits = weightedBurn,
                distinctPostingDays = distinctPostingDays,
                windowCalendarDays = windowDays.size,
                series = series,
                confidence = confidence,
                projectedDepletionEpochMs = asOfEpochMs,
                daysUntilDepletion = 0,
                reason = "available is $available — already exhausted"
            )
        }

        val daysUntil = BigDecimal(available)
            .divide(BigDecimal(weightedBurn), 0, RoundingMode.UP) // conservative: earliest crossing
            .longValueExact()
        val projected = asOfEpochMs + daysUntil * DAY_MS
        return ForecastResult(
            periodId = periodId,
            asOfEpochMs = asOfEpochMs,
            availableNowMicroUnits = available,
            weightedDailyBurnMicroUnits = weightedBurn,
            distinctPostingDays = distinctPostingDays,
            windowCalendarDays = windowDays.size,
            series = series,
            confidence = confidence,
            projectedDepletionEpochMs = projected,
            daysUntilDepletion = daysUntil,
            reason = "weighted burn $weightedBurn/day over $dataDays posting day(s); available $available"
        )
    }

    // ------------------------------------------------------------------
    // The early-warning channel (the existing telemetry audit path —
    // «قناة التنبيه: خدمة القياس عن بُعد القائمة ... دون بناء جديد»)
    // ------------------------------------------------------------------

    suspend fun checkAndWarn(
        periodId: String,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        asOfEpochMs: Long = clock()
    ): WarningOutcome {
        val result = forecast(periodId, asOfEpochMs)
        if (!result.depletionProjectable) {
            return WarningOutcome.Suppressed(result, why = result.reason)
        }
        val days = result.daysUntilDepletion ?: return WarningOutcome.Suppressed(result, why = "no projection")
        if (days > horizonDays) {
            return WarningOutcome.Suppressed(result, why = "projected depletion in $days days — outside the $horizonDays-day horizon")
        }
        val event = AuditEvent(
            id = "budget_forecast_${periodId}_$asOfEpochMs",
            severity = AuditSeverity.WARN,
            actor = "budget-intelligence",
            action = "BUDGET_FORECAST",
            resourceType = "budget_period",
            resourceId = periodId,
            decision = "EARLY_WARNING",
            reason = result.reason,
            attributes = mapOf(
                "daysUntilDepletion" to days.toString(),
                "projectedDepletionEpochMs" to result.projectedDepletionEpochMs.toString(),
                "weightedDailyBurnMicroUnits" to result.weightedDailyBurnMicroUnits.toString(),
                "availableNowMicroUnits" to result.availableNowMicroUnits.toString(),
                "confidence" to result.confidence.name,
                "distinctPostingDays" to result.distinctPostingDays.toString(),
                "requiredLeadDays" to REQUIRED_LEAD_DAYS.toString()
            ),
            occurredAtEpochMs = asOfEpochMs
        )
        val auditId = telemetry.recordAudit(event)
        return WarningOutcome.Emitted(result, auditEventId = auditId.toString())
    }
}
