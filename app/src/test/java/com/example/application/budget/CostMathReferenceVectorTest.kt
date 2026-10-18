package com.example.application.budget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ============================================================================
 * STAGE 6 — G08 acceptance: the pinned reference vectors of rounding:v1
 * ============================================================================
 * PDM-G08: "دقة الكسور العشرية والتقريب بمكتبة حتمية — يُنفذ في طبقة المجال
 * مع اختبارات مرجعية". CostMath (rounding:v1) shipped with Stage 2 and is
 * golden-tested through CostBreakdownServiceTest; this suite adds the pinned
 * REFERENCE VECTOR table the study's acceptance wording demands — a fixed,
 * reviewable list of (quantity × rate) pairs with their exact decimals and
 * their rounded micro values, including:
 *
 *   · the exact-division guarantee of the micro-per-million basis,
 *   · the HALF_UP tie behavior (away from zero, positive AND negative —
 *     the reversal math),
 *   · the sub-micro honest zero (never a fabricated 1),
 *   · the study's خطر-1 unit-distortion guard: every expected value is
 *     pinned in MICRO units (10^-6) — a refactor that silently re-reads a
 *     rate on the centi scale (10^-2, the PDM's native scale) shifts these
 *     vectors by a factor of 10^4 and fails the pin immediately,
 *   · the ROUND_LINE_THEN_SUM vs round-the-sum divergence, pinned as the
 *     reason the strategy is part of the versioned policy.
 *
 * Plain JUnit — CostMath is a pure domain object; no database, no Android.
 */
class CostMathReferenceVectorTest {

    /** One pinned reference vector: (quantity, rate micro/M) → (exact, rounded). */
    private data class Vector(
        val quantity: Long,
        val rateMicroPerMillion: String,
        val exactPreRounded: String,
        val roundedMicro: Long
    )

    private val vectors = listOf(
        // -- exact division: the finite-decimal guarantee -----------------
        Vector(1_000_000L, "3", "3", 3L),
        Vector(2_000_000L, "0.5", "1", 1L),
        // -- HALF_UP ties round AWAY FROM ZERO ----------------------------
        Vector(500_000L, "3", "1.5", 2L),
        Vector(-500_000L, "3", "-1.5", -2L),
        // -- just over / just under the tie -------------------------------
        Vector(166_667L, "3", "0.500001", 1L),
        Vector(166_666L, "3", "0.499998", 0L),
        Vector(-166_667L, "3", "-0.500001", -1L),
        Vector(-166_666L, "3", "-0.499998", 0L),
        // -- eighths and quarters -----------------------------------------
        Vector(1_000_000L, "0.125", "0.125", 0L),
        Vector(3_000_000L, "0.125", "0.375", 0L),
        Vector(7_000_000L, "0.125", "0.875", 1L),
        Vector(250_000L, "3", "0.75", 1L),
        // -- sub-micro honest zeros (never a fabricated unit) -------------
        Vector(1L, "3", "0.000003", 0L),
        Vector(1L, "999", "0.000999", 0L),
        // -- خطر-1 guard: micro-scale pins --------------------------------
        // 1M tokens × 0.05 MICRO per million = 0.05 micro → 0. A centi-scale
        // misread of the same string (50 micro) would post 50 — the pin fails.
        Vector(1_000_000L, "0.05", "0.05", 0L),
        Vector(20_000_000L, "0.05", "1", 1L),
        // -- large rates, long exact decimals ------------------------------
        Vector(1L, "123456789", "123.456789", 123L),
        Vector(999_999L, "2.000002", "1.999999999998", 2L),
        Vector(123_456_000L, "7.777777", "960.213237312", 960L)
    )

    @Test
    fun `G08 reference vectors - every pinned pair computes its exact decimal and rounded micro`() {
        vectors.forEachIndexed { i, v ->
            val result = CostMath.computeLine(v.quantity, java.math.BigDecimal(v.rateMicroPerMillion))
            assertEquals("vector $i (${v.quantity} × ${v.rateMicroPerMillion}) pre-rounded decimal", v.exactPreRounded, result.preRoundedAmountPrecise)
            assertEquals("vector $i (${v.quantity} × ${v.rateMicroPerMillion}) rounded micro", v.roundedMicro, result.finalAmountMicroUnits)
        }
    }

    @Test
    fun `G08 policy pin - the versioned rounding policy is unchanged`() {
        assertEquals("the policy version may only change with an approved re-version", "rounding:v1", CostMath.RULE_SET_VERSION)
        assertEquals("the strategy name is recorded on every pricing_profile_version", "ROUND_LINE_THEN_SUM", CostMath.ROUNDING_STRATEGY)
        assertEquals(java.math.RoundingMode.HALF_UP, CostMath.ROUNDING_MODE)
    }

    @Test
    fun `G08 divergence pin - sum-of-rounded differs from round-of-sum, and the policy chooses the former`() {
        // Two lines of exactly 1.5 micro each.
        val line = CostMath.computeLine(500_000L, java.math.BigDecimal("3"))
        assertEquals(2L, line.finalAmountMicroUnits) // HALF_UP away from zero

        // ROUND_LINE_THEN_SUM: 2 + 2 = 4 — the audit trail reconciles
        // line-by-line because the persisted lines ARE the total.
        assertEquals(4L, CostMath.sumLines(listOf(line.finalAmountMicroUnits, line.finalAmountMicroUnits)))

        // Round-the-sum would give 3 — a different, NON-persisted number
        // that breaks line-level reconciliation. The divergence is the
        // documented reason the strategy is part of the versioned policy.
        val exactSum = CostMath.exactLineMicro(500_000L, java.math.BigDecimal("3"))
            .add(CostMath.exactLineMicro(500_000L, java.math.BigDecimal("3")))
        assertEquals(java.math.BigDecimal("3.0"), exactSum)
        assertEquals(3L, CostMath.roundLineMicro(exactSum))
    }
}
