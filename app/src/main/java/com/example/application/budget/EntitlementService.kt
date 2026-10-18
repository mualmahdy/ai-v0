package com.example.application.budget

import androidx.room.withTransaction
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.EntitlementConsumptionEntity
import com.example.infrastructure.persistence.entities.EntitlementRecordEntity
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * ============================================================================
 * EntitlementService — the accruals layer (Stage 3)
 * ============================================================================
 * The feasibility gap matrix row "الاستحقاقات": BillingClass was a
 * descriptive label only; an entitlement is an auditable record with a
 * scope, a value shape, temporal conditions and consumption that is
 * append-only evidence keyed by an idempotency key.
 *
 *  - value shapes are EXACTLY-ONE-OF (UNLIMITED / QUANTITY_LIMIT /
 *    MONETARY_CREDIT / PROVIDER_CREDIT / PRICE_OVERRIDE / UNKNOWN) — the
 *    table CHECK and this service enforce the same contract;
 *  - consumption is idempotent per (entitlement, idempotency_key):
 *    replays return the recorded row, never a second deduction;
 *  - [remaining] answers the conservative question "how much is left"
 *    counting ALL recorded consumption (CONFIRMED + ESTIMATED + PENDING +
 *    OVER_LIMIT — anything that could consume), never silently ignoring
 *    an inconvenient row;
 *  - consumption_order is RECORDED for the Stage-4 intelligence
 *    (which entitlement pays first — spend routing) but no routing
 *    decision is made here (interpretation I-29);
 *  - entitlement_record itself is a mutable administrative record
 *    (pricing_profile precedent, I-28); the consumption trail is
 *    append-only (triggers).
 */
class EntitlementService(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The caller-declared entitlement shape (mirrors the table CHECK). */
    data class EntitlementSpec(
        val providerId: String,
        val applicabilityScopeId: String,
        val entitlementType: String,
        val valueKind: String,
        val quantityLimitPrecise: String? = null,
        val unitId: String? = null,
        val monetaryLimitMicroUnits: Long? = null,
        val currency: String? = null,
        val creditLimitPrecise: String? = null,
        val creditUnitId: String? = null,
        val priceOverrideMode: String? = null,
        val linkedPricingProfileId: String? = null,
        val startsAtEpochMs: Long? = null,
        val expiresAtEpochMs: Long? = null,
        val resetPolicy: String? = null,
        val stackingPolicy: String? = null,
        val consumptionOrder: Int? = null,
        val status: String = STATUS_ACTIVE,
        val verificationStatus: String = VERIFICATION_UNKNOWN
    )

    sealed class RecordOutcome {
        data class Recorded(val entitlementId: String) : RecordOutcome()
        data class Replayed(val entitlementId: String) : RecordOutcome()
    }

    sealed class ConsumptionOutcome {
        data class Consumed(val consumptionId: String, val status: String) : ConsumptionOutcome()

        /** Same idempotency key — the recorded row is the answer. */
        data class Replayed(val consumptionId: String) : ConsumptionOutcome()
    }

    sealed class Remaining {
        object Unlimited : Remaining()
        /** null = the value kind carries no consumable magnitude. */
        data class RemainingValue(val value: BigDecimal, val unit: String?) : Remaining()
        object NotApplicable : Remaining()
        data class NotFound(val entitlementId: String) : Remaining()
    }

    /** Records an entitlement (deterministic id, idempotent replay). */
    suspend fun recordEntitlement(spec: EntitlementSpec): RecordOutcome {
        validateShape(spec)
        val entitlementId = entitlementId(spec)
        return database.withTransaction {
            val existing = database.entitlementRecordDao().byId(entitlementId)
            if (existing != null) {
                RecordOutcome.Replayed(entitlementId)
            } else {
                database.entitlementRecordDao().insert(
                    EntitlementRecordEntity(
                        entitlementId = entitlementId,
                        providerId = spec.providerId,
                        applicabilityScopeId = spec.applicabilityScopeId,
                        entitlementType = spec.entitlementType,
                        valueKind = spec.valueKind,
                        quantityLimitPrecise = spec.quantityLimitPrecise,
                        unitId = spec.unitId,
                        monetaryLimitMicroUnits = spec.monetaryLimitMicroUnits,
                        currency = spec.currency,
                        creditLimitPrecise = spec.creditLimitPrecise,
                        creditUnitId = spec.creditUnitId,
                        priceOverrideMode = spec.priceOverrideMode,
                        linkedPricingProfileId = spec.linkedPricingProfileId,
                        startsAtEpochMs = spec.startsAtEpochMs,
                        expiresAtEpochMs = spec.expiresAtEpochMs,
                        resetPolicy = spec.resetPolicy,
                        stackingPolicy = spec.stackingPolicy,
                        consumptionOrder = spec.consumptionOrder,
                        status = spec.status,
                        verificationStatus = spec.verificationStatus,
                        createdAtEpochMs = clock()
                    )
                )
                RecordOutcome.Recorded(entitlementId)
            }
        }
    }

    /**
     * Records consumption, idempotent per (entitlement, idempotencyKey).
     * [status] defaults to CONFIRMED (provider-declared); ESTIMATED is
     * the honest label for locally-derived figures.
     */
    suspend fun consume(
        entitlementId: String,
        consumptionGranularity: String,
        idempotencyKey: String,
        requestAttemptId: String? = null,
        usageRecordId: String? = null,
        usageMeasurementId: String? = null,
        periodStartEpochMs: Long? = null,
        periodEndEpochMs: Long? = null,
        sourceType: String = SOURCE_PROVIDER_DECLARED,
        quantityPrecise: String? = null,
        unitId: String? = null,
        amountMicroUnits: Long? = null,
        creditAmountPrecise: String? = null,
        consumptionStatus: String = CONSUMPTION_CONFIRMED
    ): ConsumptionOutcome {
        require(idempotencyKey.isNotBlank()) { "idempotency key must not be blank" }
        return database.withTransaction {
            val existing = database.entitlementConsumptionDao()
                .byIdempotencyKey(entitlementId, idempotencyKey)
            if (existing != null) {
                ConsumptionOutcome.Replayed(existing.consumptionId)
            } else {
                val consumptionId = "ec_${entitlementId}_${idempotencyKey.hashCode().toUInt()}"
                database.entitlementConsumptionDao().insert(
                    EntitlementConsumptionEntity(
                        consumptionId = consumptionId,
                        entitlementId = entitlementId,
                        consumptionGranularity = consumptionGranularity,
                        requestAttemptId = requestAttemptId,
                        usageRecordId = usageRecordId,
                        usageMeasurementId = usageMeasurementId,
                        periodStartEpochMs = periodStartEpochMs,
                        periodEndEpochMs = periodEndEpochMs,
                        sourceType = sourceType,
                        quantityPrecise = quantityPrecise,
                        unitId = unitId,
                        amountMicroUnits = amountMicroUnits,
                        creditAmountPrecise = creditAmountPrecise,
                        consumptionStatus = consumptionStatus,
                        idempotencyKey = idempotencyKey,
                        recordedAtEpochMs = clock()
                    )
                )
                ConsumptionOutcome.Consumed(consumptionId, consumptionStatus)
            }
        }
    }

    /**
     * The conservative remaining value: limit − ALL recorded consumption.
     * UNLIMITED is unlimited; PRICE_OVERRIDE / UNKNOWN carry no
     * consumable magnitude.
     */
    suspend fun remaining(entitlementId: String): Remaining {
        val entitlement = database.entitlementRecordDao().byId(entitlementId)
            ?: return Remaining.NotFound(entitlementId)
        val consumption = database.entitlementConsumptionDao().forEntitlement(entitlementId)
        return when (entitlement.valueKind) {
            VALUE_UNLIMITED -> Remaining.Unlimited
            VALUE_QUANTITY_LIMIT -> {
                val limit = BigDecimal(entitlement.quantityLimitPrecise!!)
                val used = consumption.mapNotNull { it.quantityPrecise }.sumOf(::BigDecimal)
                Remaining.RemainingValue(limit.subtract(used), entitlement.unitId)
            }
            VALUE_MONETARY_CREDIT -> {
                val limit = BigDecimal(entitlement.monetaryLimitMicroUnits!!)
                val used = consumption.mapNotNull { it.amountMicroUnits }.sum()
                Remaining.RemainingValue(
                    limit.subtract(BigDecimal(used)), entitlement.currency
                )
            }
            VALUE_PROVIDER_CREDIT -> {
                val limit = BigDecimal(entitlement.creditLimitPrecise!!)
                val used = consumption.mapNotNull { it.creditAmountPrecise }.sumOf(::BigDecimal)
                Remaining.RemainingValue(limit.subtract(used), entitlement.creditUnitId)
            }
            else -> Remaining.NotApplicable
        }
    }

    /** Mirrors the table's exactly-one-of CHECK with clear messages. */
    private fun validateShape(spec: EntitlementSpec) {
        fun fail(what: String): Nothing =
            throw IllegalArgumentException("invalid $what entitlement shape: ${spec.valueKind}")
        when (spec.valueKind) {
            VALUE_UNLIMITED -> if (spec.quantityLimitPrecise != null || spec.unitId != null ||
                spec.monetaryLimitMicroUnits != null || spec.currency != null ||
                spec.creditLimitPrecise != null || spec.creditUnitId != null
            ) fail("UNLIMITED with value fields")
            VALUE_QUANTITY_LIMIT -> if (spec.quantityLimitPrecise == null || spec.unitId == null ||
                spec.monetaryLimitMicroUnits != null || spec.currency != null ||
                spec.creditLimitPrecise != null || spec.creditUnitId != null
            ) fail("QUANTITY_LIMIT")
            VALUE_MONETARY_CREDIT -> if (spec.monetaryLimitMicroUnits == null || spec.currency == null ||
                spec.quantityLimitPrecise != null || spec.unitId != null ||
                spec.creditLimitPrecise != null || spec.creditUnitId != null
            ) fail("MONETARY_CREDIT")
            VALUE_PROVIDER_CREDIT -> if (spec.creditLimitPrecise == null || spec.creditUnitId == null ||
                spec.monetaryLimitMicroUnits != null || spec.currency != null ||
                spec.quantityLimitPrecise != null || spec.unitId != null
            ) fail("PROVIDER_CREDIT")
            VALUE_PRICE_OVERRIDE -> if (spec.priceOverrideMode == null) fail("PRICE_OVERRIDE without mode")
            VALUE_UNKNOWN -> Unit
            else -> throw IllegalArgumentException("unknown value kind: ${spec.valueKind}")
        }
        if (spec.priceOverrideMode == "LINKED_PRICING_PROFILE" && spec.linkedPricingProfileId == null) {
            throw IllegalArgumentException("LINKED_PRICING_PROFILE requires linkedPricingProfileId")
        }
    }

    private fun entitlementId(spec: EntitlementSpec): String {
        val canonical = buildString {
            append(spec.providerId).append('|')
            append(spec.applicabilityScopeId).append('|')
            append(spec.entitlementType).append('|')
            append(spec.valueKind).append('|')
            append(spec.quantityLimitPrecise ?: "-").append('|')
            append(spec.unitId ?: "-").append('|')
            append(spec.monetaryLimitMicroUnits ?: "-").append('|')
            append(spec.currency ?: "-").append('|')
            append(spec.creditLimitPrecise ?: "-").append('|')
            append(spec.creditUnitId ?: "-").append('|')
            append(spec.priceOverrideMode ?: "-").append('|')
            append(spec.linkedPricingProfileId ?: "-").append('|')
            append(spec.startsAtEpochMs ?: "-").append('|')
            append(spec.expiresAtEpochMs ?: "-")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "ent_${digest.take(24)}"
    }

    companion object {
        const val VALUE_UNLIMITED = "UNLIMITED"
        const val VALUE_QUANTITY_LIMIT = "QUANTITY_LIMIT"
        const val VALUE_MONETARY_CREDIT = "MONETARY_CREDIT"
        const val VALUE_PROVIDER_CREDIT = "PROVIDER_CREDIT"
        const val VALUE_PRICE_OVERRIDE = "PRICE_OVERRIDE"
        const val VALUE_UNKNOWN = "UNKNOWN"

        const val STATUS_ACTIVE = "ACTIVE"
        const val VERIFICATION_UNKNOWN = "UNKNOWN"

        const val GRANULARITY_REQUEST_ATTEMPT = "REQUEST_ATTEMPT"
        const val GRANULARITY_USAGE_RECORD = "USAGE_RECORD"
        const val GRANULARITY_USAGE_MEASUREMENT = "USAGE_MEASUREMENT"
        const val GRANULARITY_PERIOD_AGGREGATE = "PERIOD_AGGREGATE"
        const val GRANULARITY_PROVIDER_DECLARED = "PROVIDER_DECLARED"

        const val CONSUMPTION_CONFIRMED = "CONFIRMED"
        const val CONSUMPTION_ESTIMATED = "ESTIMATED"

        const val SOURCE_PROVIDER_DECLARED = "PROVIDER_DECLARED"
        const val SOURCE_ORCHESTRATOR = "ORCHESTRATOR"
    }
}
