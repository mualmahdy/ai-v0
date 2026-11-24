package com.example.application.budget

import java.math.BigDecimal

/**
 * ============================================================================
 * MoneyInput — STAGE 2B (WS-F): monetary input integrity
 * ============================================================================
 * The single parser for every operator-entered monetary value on the
 * budget/pricing surfaces. It replaces the Double-based conversion
 * (`(amountUsd * 1_000_000.0).toLong()`) whose defects this stage closes:
 *
 *  - NaN and ±Infinity are REJECTED (Double parsing accepted "NaN"/
 *    "Infinity" tokens and Infinity silently became Long.MAX_VALUE).
 *  - Values outside the supported range are REJECTED with an explicit
 *    reason (not silently clamped).
 *  - Decimal precision is EXPLICIT: at most [MAX_DECIMAL_PLACES] (= 6)
 *    fractional digits — the micro unit IS 10^-6; more digits cannot be
 *    represented and are rejected instead of binary-truncated.
 *  - Arithmetic is EXACT decimal (BigDecimal), never binary floating
 *    point: "5.999999" stays 5_999_999 micro, not 5999998.999999999…
 *    truncated by a double multiply.
 *  - The caller gets a first-class [Parsed.Invalid] carrying the
 *    user-facing reason — validation errors are DISPLAYED, never silently
 *    ignored.
 */
object MoneyInput {

    /** The ledger's fixed-point scale: 1 micro = 10^-6 currency units. */
    const val MICRO_SCALE = 6

    /** Accepted decimal precision of an entered amount. */
    const val MAX_DECIMAL_PLACES = 6

    /**
     * Supported range ceiling: 10^12 whole currency units (micro fits in a
     * signed Long with two orders of magnitude of headroom —
     * 10^12 units = 10^18 micro < Long.MAX_VALUE ≈ 9.22×10^18).
     */
    const val MAX_WHOLE_UNITS = 1_000_000_000_000L

    /** The maximum representable micro value (10^18). */
    const val MAX_MICRO_UNITS: Long = MAX_WHOLE_UNITS * 1_000_000L

    sealed class Parsed {
        /** Exact micro-units value (>= 0). */
        data class Micro(val microUnits: Long) : Parsed()

        /** Rejected input with the user-facing reason (never silent). */
        data class Invalid(val reason: String) : Parsed()
    }

    /**
     * Parses an operator-entered monetary amount into exact micro units.
     * Locale-independent: the decimal separator is '.'; leading '+' is
     * rejected with the negative-sign family (an explicit grammar, not a
     * locale guess); surrounding whitespace is tolerated.
     */
    fun parseMicroUnits(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed.Invalid("أدخل مبلغاً")
        if (trimmed.startsWith("+")) return Parsed.Invalid("لا يُقبل محرف \"+\" — أدخل الرقم كما هو")
        // Explicit rejection of the float sentinels Double parsing accepts.
        if (trimmed.equals("NaN", ignoreCase = true) ||
            trimmed.equals("Infinity", ignoreCase = true) ||
            trimmed.equals("-Infinity", ignoreCase = true)
        ) {
            return Parsed.Invalid("قيمة غير رقمية (NaN/Infinity) — أدخل رقماً منتهياً")
        }
        if (trimmed.any { it == ',' }) return Parsed.Invalid("استخدم النقطة (.) كفاصل عشري")
        val value = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            return Parsed.Invalid("صيغة رقمية غير صالحة")
        }
        if (value.signum() < 0) return Parsed.Invalid("القيمة لا يمكن أن تكون سالبة")
        val decimals = if (value.scale() > 0) value.scale() else 0
        if (decimals > MAX_DECIMAL_PLACES) {
            return Parsed.Invalid("دقة عشرية أكثر من $MAX_DECIMAL_PLACES خانات — الميكرو (10^-6) هو أصغر وحدة")
        }
        val micro = try {
            value.movePointRight(MICRO_SCALE).longValueExact()
        } catch (_: ArithmeticException) {
            return Parsed.Invalid("القيمة خارج النطاق المدعوم (الحد الأقصى $MAX_WHOLE_UNITS وحدة نقدية)")
        }
        if (micro > MAX_MICRO_UNITS) {
            return Parsed.Invalid("القيمة خارج النطاق المدعوم (الحد الأقصى $MAX_WHOLE_UNITS وحدة نقدية)")
        }
        return Parsed.Micro(micro)
    }

    /**
     * Parses a currency code: exactly 3 ASCII letters, uppercased. A
     * missing currency is NEVER replaced by a guessed default here — the
     * caller decides its explicit default and this validates it.
     */
    fun parseCurrency(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length != 3) return null
        if (!trimmed.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
        return trimmed.uppercase()
    }

    /** Renders micro units as a readable fixed-point string (debug/UI). */
    fun microToText(microUnits: Long, currency: String): String {
        val whole = microUnits / 1_000_000
        val fraction = microUnits % 1_000_000
        return "%s %d.%06d".format(currency, whole, fraction).trimEnd('0').let {
            if (it.endsWith('.')) it + "0" else it
        }
    }
}

/**
 * ============================================================================
 * FxRateInput — STAGE 2C (FX-rule operational management): rate input
 * integrity
 * ============================================================================
 * The exact-decimal parser for an operator-entered FX rate
 * (TARGET_UNITS_PER_SOURCE_UNIT). Same discipline as [MoneyInput] — NaN /
 * ±Infinity / '+'-prefix / commas / non-numeric are rejected with explicit
 * reasons — but a rate is NOT a money amount:
 *  - it must be STRICTLY POSITIVE (zero and negative rates are never
 *    "clamped" — a wrong-sign rate would silently invert conversions);
 *  - the supported precision is 12 fractional digits (rates like
 *    0.000063900123 are legitimate; the ledger stores the exact decimal
 *    TEXT and computes with BigDecimal, so no micro-scale truncation);
 *  - the supported magnitude is 0 < rate <= 10^12 (enormous but finite —
 *    beyond any real currency pair while keeping the BigDecimal math sane).
 */
object FxRateInput {

    /** Accepted decimal precision of an entered rate. */
    const val MAX_DECIMAL_PLACES = 12

    /** Supported magnitude ceiling (whole units per source unit). */
    const val MAX_RATE = 1_000_000_000_000L

    sealed class Parsed {
        /** The exact rate as normalized decimal TEXT (never a binary float). */
        data class Rate(val ratePrecise: String) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed.Invalid("أدخل معدل التحويل")
        if (trimmed.startsWith("+")) return Parsed.Invalid("لا يُقبل محرف \"+\" — أدخل الرقم كما هو")
        if (trimmed.equals("NaN", ignoreCase = true) ||
            trimmed.equals("Infinity", ignoreCase = true) ||
            trimmed.equals("-Infinity", ignoreCase = true)
        ) {
            return Parsed.Invalid("قيمة غير رقمية (NaN/Infinity) — أدخل رقماً منتهياً")
        }
        if (trimmed.any { it == ',' }) return Parsed.Invalid("استخدم النقطة (.) كفاصل عشري")
        val value = try {
            java.math.BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            return Parsed.Invalid("صيغة رقمية غير صالحة")
        }
        if (value.signum() <= 0) return Parsed.Invalid("المعدل يجب أن يكون موجباً تماماً — الصفر والسالب ليسا معدل تحويل")
        val decimals = if (value.scale() > 0) value.scale() else 0
        if (decimals > MAX_DECIMAL_PLACES) {
            return Parsed.Invalid("دقة عشرية أكثر من $MAX_DECIMAL_PLACES خانة")
        }
        if (value > java.math.BigDecimal(MAX_RATE)) {
            return Parsed.Invalid("المعدل خارج النطاق المدعوم (الحد الأقصى $MAX_RATE)")
        }
        return Parsed.Rate(value.stripTrailingZeros().toPlainString())
    }
}
