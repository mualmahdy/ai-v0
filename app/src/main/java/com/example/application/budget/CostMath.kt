package com.example.application.budget

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ============================================================================
 * G08 — deterministic decimal money math (closed this stage)
 * ============================================================================
 * PDM gate PDM-G08: "دقة decimals والتقريب — التنفيذ في طبقة المجال بمكتبة
 * عشرية حتمية واختبارات مرجعية" (decimals & rounding live in the domain
 * layer with a deterministic decimal library and reference tests).
 *
 * Every Stage-2 money computation goes through java.math.BigDecimal —
 * exact decimal arithmetic, never binary floats. The approved v1 policy:
 *
 *   ROUND_LINE_THEN_SUM with HALF_UP at micro scale (10^-6 of the
 *   currency unit) — each line's exact decimal amount is rounded to an
 *   integer micro value, and the calculation total is the SUM of the
 *   rounded lines. [RULE_SET_VERSION] versions the policy; golden tests
 *   in CostBreakdownServiceTest pin the exact behavior, including the
 *   documented ±1 micro per line divergence from the legacy truncating
 *   engine (parity test, roadmap Phase-2 exit criterion).
 *
 * All Stage-2 rates are micro-currency per million tokens (the platform's
 * canonical pricing basis), stored as exact decimal strings
 * (pricing_component.rate_amount_precise) — dividing by 10^6 is always a
 * finite decimal, so exact division is guaranteed and no precision is
 * ever silently lost.
 */
object CostMath {

    /** Versions the approved rounding policy (G08). Bump only with approval. */
    const val RULE_SET_VERSION = "rounding:v1"

    /** The line-level rounding mode of policy v1. */
    val ROUNDING_MODE = RoundingMode.HALF_UP

    /** The strategy name recorded on every pricing_profile_version. */
    const val ROUNDING_STRATEGY = "ROUND_LINE_THEN_SUM"

    /** Canonical pricing basis: prices are per this many tokens. */
    const val PRICE_UNIT_QUANTITY = BigDecimal(1_000_000)

    /**
     * The exact (pre-rounding) line amount in micro currency units for
     * `quantity` tokens at `rateMicroPerMillion` micro currency per
     * million tokens: quantity × rate / 10^6 — always finite.
     */
    fun exactLineMicro(quantity: Long, rateMicroPerMillion: BigDecimal): BigDecimal =
        BigDecimal(quantity).multiply(rateMicroPerMillion).divide(PRICE_UNIT_QUANTITY)

    /**
     * Rounds an exact decimal micro amount to the integer micro value
     * under policy v1 (HALF_UP). Never truncates, never floats.
     */
    fun roundLineMicro(exactMicro: BigDecimal): Long =
        exactMicro.setScale(0, ROUNDING_MODE).longValueExact()

    /**
     * Full line computation: exact decimal, rounded micro, and the
     * pre-rounded evidence string — the triple persisted on every
     * cost_calculation_line for auditability.
     */
    data class LineResult(
        val preRoundedAmountPrecise: String,
        val finalAmountMicroUnits: Long
    )

    fun computeLine(quantity: Long, rateMicroPerMillion: BigDecimal): LineResult {
        val exact = exactLineMicro(quantity, rateMicroPerMillion)
        return LineResult(
            preRoundedAmountPrecise = exact.stripTrailingZeros().toPlainString(),
            finalAmountMicroUnits = roundLineMicro(exact)
        )
    }

    /**
     * Sums rounded lines (ROUND_LINE_THEN_SUM). The total is by
     * construction the sum of the persisted line integers — the audit
     * trail reconciles exactly, no hidden re-rounding.
     */
    fun sumLines(lineMicroUnits: List<Long>): Long = lineMicroUnits.sum()
}
