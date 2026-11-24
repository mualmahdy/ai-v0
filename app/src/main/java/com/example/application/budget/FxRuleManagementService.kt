package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.CurrencyConversionRuleEntity

/**
 * ============================================================================
 * FxRuleManagementService — STAGE 2C (FX-rule operational management)
 * ============================================================================
 *
 * The operator-facing management surface over the EXISTING conversion
 * authority ([CurrencyConversionService]) — the roadmap's remaining
 * functional limitation: rules existed and were honored end-to-end (WS-A
 * authorization + I-18 settlement), but could only be registered
 * programmatically, so the application could not honestly claim complete
 * multi-currency operational support.
 *
 * WHAT THIS SURFACE IS:
 *  - VIEW every registered rule with its DERIVED applicability state
 *    (ACTIVE_NOW / NOT_YET_EFFECTIVE / EXPIRED / OPEN_ENDED_ACTIVE);
 *  - REGISTER a new immutable rule through the SAME registration contract
 *    the programmatic path uses (deterministic id, idempotent replay,
 *    content conflict rejected);
 *  - carry the rule's source authority / evidence so registered operator
 *    evidence stays clearly distinguishable from independently verified
 *    exchange rates (nothing here verifies rates — no live scraping, no
 *    automatic rates).
 *
 * WHAT IT DELIBERATELY IS NOT (fail-closed discipline, unchanged):
 *  - NO edit and NO delete: a rule referenced by financial records is
 *    frozen at the DB level (trg_currency_rule_immutable_when_used); a
 *    correction is a NEW immutable rule with a NEW auditable window;
 *  - NO assumed 1:1, NO pair/direction substitution: without a covering
 *    rule a conversion is honestly refused ([CurrencyConversionService.ConversionResult.NoRule]);
 *  - NO rate verification: an operator-entered rate is REGISTERED
 *    EVIDENCE, never an independently verified exchange rate.
 */
class FxRuleManagementService(
    private val database: AppDatabase,
    private val fx: CurrencyConversionService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The derived applicability of a registered rule at an instant. */
    enum class RuleApplicability {
        /** from <= now < until — the rule a conversion at `now` resolves. */
        ACTIVE_NOW,
        /** until == null && from <= now — open-ended and current. */
        OPEN_ENDED_ACTIVE,
        /** from > now — registered for a future window. */
        NOT_YET_EFFECTIVE,
        /** until != null && until <= now — historical only. */
        EXPIRED
    }

    /** A registered rule projected for the operator surface. */
    data class FxRuleView(
        val ruleId: String,
        val fromCurrency: String,
        val toCurrency: String,
        /** Exact decimal TEXT (G08 — never a binary float). */
        val ratePrecise: String,
        /** Always TARGET_UNITS_PER_SOURCE_UNIT (G03). */
        val rateDirection: String,
        val effectiveFromEpochMs: Long,
        val effectiveUntilEpochMs: Long?,
        val sourceAuthority: String,
        val roundingPolicy: String,
        val applicability: RuleApplicability
    )

    /** Verdict of [registerRule]. */
    sealed class RegisterOutcome {
        data class Registered(val ruleId: String) : RegisterOutcome()
        data class Replayed(val ruleId: String) : RegisterOutcome()
        /** Invalid input (codes, rate, window) or a content conflict — the
         *  honest rejection with the reason; NOTHING was written. */
        data class Rejected(val reason: String) : RegisterOutcome()
    }

    /** Every registered rule with its derived applicability (pure read). */
    suspend fun listRules(nowEpochMs: Long = clock()): List<FxRuleView> =
        database.currencyConversionRuleDao().allRules().map { it.toView(nowEpochMs) }

    /**
     * Registers a new immutable rule through the EXISTING registration
     * contract ([CurrencyConversionService.registerRule]). Identical
     * re-registration is an idempotent [RegisterOutcome.Replayed]; a
     * conflicting duplicate (same id, different content) and every invalid
     * input are honest [RegisterOutcome.Rejected]s — never a silent
     * overwrite of a rule that financial records may already reference.
     */
    suspend fun registerRule(
        fromCurrency: String,
        toCurrency: String,
        ratePrecise: String,
        effectiveFromEpochMs: Long,
        effectiveUntilEpochMs: Long?,
        sourceAuthority: String
    ): RegisterOutcome = try {
        when (
            val registration = fx.registerRule(
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                ratePrecise = ratePrecise,
                effectiveFromEpochMs = effectiveFromEpochMs,
                effectiveUntilEpochMs = effectiveUntilEpochMs,
                sourceAuthority = sourceAuthority
            )
        ) {
            is CurrencyConversionService.RuleRegistration.Registered ->
                RegisterOutcome.Registered(registration.ruleId)
            is CurrencyConversionService.RuleRegistration.Replayed ->
                RegisterOutcome.Replayed(registration.ruleId)
        }
    } catch (e: IllegalArgumentException) {
        RegisterOutcome.Rejected(e.message ?: "قاعدة غير صالحة")
    } catch (e: NumberFormatException) {
        RegisterOutcome.Rejected("معدل غير صالح — يجب أن يكون رقمًا عشريًا موجبًا صحيحًا")
    }

    private fun CurrencyConversionRuleEntity.toView(nowEpochMs: Long): FxRuleView = FxRuleView(
        ruleId = currencyConversionRuleId,
        fromCurrency = fromCurrency,
        toCurrency = toCurrency,
        ratePrecise = ratePrecise,
        rateDirection = rateDirection,
        effectiveFromEpochMs = effectiveFromEpochMs,
        effectiveUntilEpochMs = effectiveUntilEpochMs,
        sourceAuthority = sourceAuthority,
        roundingPolicy = roundingPolicy,
        applicability = when {
            effectiveUntilEpochMs == null && effectiveFromEpochMs <= nowEpochMs ->
                RuleApplicability.OPEN_ENDED_ACTIVE
            effectiveUntilEpochMs != null && effectiveUntilEpochMs <= nowEpochMs ->
                RuleApplicability.EXPIRED
            effectiveFromEpochMs > nowEpochMs -> RuleApplicability.NOT_YET_EFFECTIVE
            else -> RuleApplicability.ACTIVE_NOW
        }
    )
}
