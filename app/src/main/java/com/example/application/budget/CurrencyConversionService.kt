package com.example.application.budget

import androidx.room.withTransaction
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.CurrencyConversionRuleEntity
import java.math.BigDecimal

/**
 * ============================================================================
 * CurrencyConversionService — G03 closes here (Stage 3)
 * ============================================================================
 * PDM gate PDM-G03: "comparison currency & direction — comparison currency
 * = provider billing currency; single conversion; FX rules deferred to
 * Stage 3; rounding rule versioned". This service IS those deferred FX
 * rules:
 *
 *  - rules are WINDOWED (from/until, epoch-ms) and DIRECTIONAL — a rate
 *    always means TARGET_UNITS_PER_SOURCE_UNIT, and a rule from A to B
 *    never converts B to A (the direction CHECK test pins it);
 *  - rates are exact decimal TEXT — never binary floats (G08 discipline);
 *  - conversion math is BigDecimal with HALF_UP at micro scale, policy
 *    versioned in [FX_RULE_VERSION] and recorded on every reconciliation
 *    built with a rule (the "rounding rule versioned" half of G03);
 *  - fail-closed: no covering rule -> [ConversionResult.NoRule]. A missing
 *    rate is NEVER assumed to be 1:1 (UNKNOWN is not one — the platform's
 *    founding principle applied to FX);
 *  - a rule referenced by a reconciliation is FROZEN at the DB level
 *    (trg_currency_rule_immutable_when_used) — corrections ship as a new
 *    rule row with a new window, keeping every historical comparison
 *    reproducible.
 *
 * I-18 closure: HoldSettlementService consumes this service to convert an
 * actual cost into the hold's currency before settling — replacing the
 * Stage-2 honest no-op with an evidenced conversion (still fail-closed
 * when no rule covers the moment).
 */
class CurrencyConversionService(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    sealed class ConversionResult {
        /**
         * @param ruleId empty string for the identity conversion (same
         * currency — G03/PDM: conversion_rule_id stays NULL, no rule row
         * is consulted or fabricated).
         */
        data class Converted(
            val ruleId: String,
            val ratePrecise: String,
            val sourceMicroUnits: Long,
            val targetMicroUnits: Long,
            /** The exact pre-rounded decimal — audit evidence. */
            val preRoundedPrecise: String
        ) : ConversionResult()

        /** No covering rule: the honest refusal (never a 1:1 guess). */
        object NoRule : ConversionResult()

        data class InvalidRate(val reason: String) : ConversionResult()
    }

    sealed class RuleRegistration {
        data class Registered(val ruleId: String) : RuleRegistration()
        data class Replayed(val ruleId: String) : RuleRegistration()
    }

    /**
     * Registers a rule. Deterministic id `fx_{from}_{to}_{effectiveFrom}`
     * (the v23 read-before-insert discipline): re-registering the SAME
     * payload is an idempotent replay; the same id with a DIFFERENT rate
     * is a conflict (register a new window instead — history stays
     * reproducible).
     */
    suspend fun registerRule(
        fromCurrency: String,
        toCurrency: String,
        ratePrecise: String,
        effectiveFromEpochMs: Long,
        effectiveUntilEpochMs: Long?,
        sourceAuthority: String
    ): RuleRegistration {
        require(fromCurrency.length == 3) { "from_currency must be a 3-letter ISO code" }
        require(toCurrency.length == 3) { "to_currency must be a 3-letter ISO code" }
        require(fromCurrency != toCurrency) { "a conversion rule must change currency (from <> to)" }
        val rate = BigDecimal(ratePrecise)
        require(rate.signum() > 0) { "rate must be strictly positive" }
        require(effectiveUntilEpochMs == null || effectiveFromEpochMs < effectiveUntilEpochMs) {
            "effective window must be non-empty (from < until)"
        }

        val ruleId = ruleId(fromCurrency, toCurrency, effectiveFromEpochMs)
        return database.withTransaction {
            val existing = database.currencyConversionRuleDao().byId(ruleId)
            if (existing != null) {
                if (existing.ratePrecise == ratePrecise &&
                    existing.effectiveUntilEpochMs == effectiveUntilEpochMs &&
                    existing.sourceAuthority == sourceAuthority
                ) {
                    RuleRegistration.Replayed(ruleId)
                } else {
                    throw IllegalArgumentException(
                        "rule $ruleId already exists with different content — register a new window instead"
                    )
                }
            } else {
                database.currencyConversionRuleDao().insert(
                    CurrencyConversionRuleEntity(
                        currencyConversionRuleId = ruleId,
                        fromCurrency = fromCurrency,
                        toCurrency = toCurrency,
                        ratePrecise = ratePrecise,
                        rateDirection = DIRECTION,
                        effectiveFromEpochMs = effectiveFromEpochMs,
                        effectiveUntilEpochMs = effectiveUntilEpochMs,
                        sourceAuthority = sourceAuthority,
                        roundingPolicy = ROUNDING_POLICY,
                        createdAtEpochMs = clock()
                    )
                )
                RuleRegistration.Registered(ruleId)
            }
        }
    }

    /** The covering rule for a pair at an instant (window: from <= t < until). */
    suspend fun resolveRule(
        fromCurrency: String,
        toCurrency: String,
        atEpochMs: Long
    ): CurrencyConversionRuleEntity? =
        database.currencyConversionRuleDao().coveringForPair(fromCurrency, toCurrency, atEpochMs)

    /**
     * Converts [amountMicroUnits] from [fromCurrency] to [toCurrency].
     * Same currency -> identity (no rule involved). Different currency ->
     * the single covering rule at [atEpochMs], applied as
     * target = source × rate with HALF_UP at micro scale.
     */
    suspend fun convert(
        amountMicroUnits: Long,
        fromCurrency: String,
        toCurrency: String,
        atEpochMs: Long
    ): ConversionResult {
        if (fromCurrency == toCurrency) {
            return ConversionResult.Converted(
                ruleId = "",
                ratePrecise = IDENTITY_RATE,
                sourceMicroUnits = amountMicroUnits,
                targetMicroUnits = amountMicroUnits,
                preRoundedPrecise = amountMicroUnits.toString()
            )
        }
        val rule = resolveRule(fromCurrency, toCurrency, atEpochMs)
            ?: return ConversionResult.NoRule
        val rate = BigDecimal(rule.ratePrecise)
        if (rate.signum() <= 0) {
            return ConversionResult.InvalidRate("rate must be strictly positive: ${rule.ratePrecise}")
        }
        val exact = BigDecimal(amountMicroUnits).multiply(rate)
        val target = exact.setScale(0, CostMath.ROUNDING_MODE).longValueExact()
        return ConversionResult.Converted(
            ruleId = rule.currencyConversionRuleId,
            ratePrecise = rule.ratePrecise,
            sourceMicroUnits = amountMicroUnits,
            targetMicroUnits = target,
            preRoundedPrecise = exact.stripTrailingZeros().toPlainString()
        )
    }

    private fun ruleId(from: String, to: String, effectiveFromEpochMs: Long): String =
        "fx_${from}_${to}_$effectiveFromEpochMs"

    companion object {
        /** G03: the only legal direction — target units per source unit. */
        const val DIRECTION = "TARGET_UNITS_PER_SOURCE_UNIT"

        /** Versions the FX rounding policy (the G03 "versioned" half). */
        const val FX_RULE_VERSION = "fx:v1"

        /** HALF_UP at micro scale — same family as CostMath rounding:v1. */
        const val ROUNDING_POLICY = "HALF_UP:micro"

        const val IDENTITY_RATE = "1"
    }
}
