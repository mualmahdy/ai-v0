package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * FIW Stage 3 — EntitlementManagementService: the entitlements SURFACE
 * ============================================================================
 * The accruals ENGINE ([EntitlementService]) shipped in Stage 3 of the
 * economic-governance roadmap with zero UI and zero read-side listing —
 * the audit's finding: "خدمة الاستحقاقات موجودة دون واجهة أو مسارات
 * استخدام". This service is the honest management projection over it:
 *
 *  - [listEntitlements]: every recorded entitlement with its DERIVED state
 *    (active/expired by the honest window arithmetic, the CONSERVATIVE
 *    remaining from the engine itself, the consumption count);
 *  - [registerEntitlement]: the validated registration path (delegates to
 *    the engine's own shape validation — no second validation drifts).
 *
 * What this surface deliberately does NOT do (recorded as debt, not hidden):
 *  - it does NOT route billing consumption (spend routing is the documented
 *    Stage-4 intelligence decision I-29 — no routing decision is made here);
 *  - it does NOT invent prices, quotas or expiries — every field the user
 *    registers is marked with its verification status (UNKNOWN by default —
 *    a user-registered entitlement is not provider-verified evidence).
 */
class EntitlementManagementService(
    private val database: AppDatabase,
    private val entitlements: EntitlementService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** One entitlement's honest projection for the management list. */
    data class EntitlementView(
        val entitlementId: String,
        val providerId: String,
        val applicabilityScopeId: String,
        val entitlementType: String,
        val valueKind: String,
        val quantityLimitPrecise: String?,
        val unitId: String?,
        val monetaryLimitMicroUnits: Long?,
        val currency: String?,
        val creditLimitPrecise: String?,
        val creditUnitId: String?,
        val startsAtEpochMs: Long?,
        val expiresAtEpochMs: Long?,
        val resetPolicy: String?,
        val status: String,
        val verificationStatus: String,
        val createdAtEpochMs: Long,
        /** Derived from the window vs now (never stored as truth). */
        val isWindowActive: Boolean,
        /** The ENGINE's conservative remaining (formatted for display). */
        val remainingLabel: String,
        /** How many consumption rows the trail carries. */
        val consumptionCount: Int
    )

    /** The registration form (validated by the ENGINE's shape rules). */
    data class RegistrationSpec(
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
        val startsAtEpochMs: Long? = null,
        val expiresAtEpochMs: Long? = null,
        val resetPolicy: String? = null
    )

    sealed class RegistrationOutcome {
        data class Registered(val entitlementId: String) : RegistrationOutcome()
        /** The engine already has this exact entitlement (idempotent). */
        data class Replayed(val entitlementId: String) : RegistrationOutcome()
        data class Rejected(val reason: String) : RegistrationOutcome()
    }

    /** Every recorded entitlement with its derived state (newest first). */
    suspend fun listEntitlements(): List<EntitlementView> = withContext(Dispatchers.IO) {
        val now = clock()
        val records = database.entitlementRecordDao().listAll()
        records.map { record ->
            val remaining = entitlements.remaining(record.entitlementId)
            val consumptionCount = database.entitlementConsumptionDao()
                .forEntitlement(record.entitlementId).size
            EntitlementView(
                entitlementId = record.entitlementId,
                providerId = record.providerId,
                applicabilityScopeId = record.applicabilityScopeId,
                entitlementType = record.entitlementType,
                valueKind = record.valueKind,
                quantityLimitPrecise = record.quantityLimitPrecise,
                unitId = record.unitId,
                monetaryLimitMicroUnits = record.monetaryLimitMicroUnits,
                currency = record.currency,
                creditLimitPrecise = record.creditLimitPrecise,
                creditUnitId = record.creditUnitId,
                startsAtEpochMs = record.startsAtEpochMs,
                expiresAtEpochMs = record.expiresAtEpochMs,
                resetPolicy = record.resetPolicy,
                status = record.status,
                verificationStatus = record.verificationStatus,
                createdAtEpochMs = record.createdAtEpochMs,
                isWindowActive =
                (record.startsAtEpochMs == null || record.startsAtEpochMs <= now) &&
                    (record.expiresAtEpochMs == null || record.expiresAtEpochMs > now),
                remainingLabel = when (remaining) {
                    is EntitlementService.Remaining.Unlimited -> "غير محدود"
                    is EntitlementService.Remaining.RemainingValue ->
                        "${remaining.value.toPlainString()} ${remaining.unit ?: ""}".trim()
                    is EntitlementService.Remaining.NotApplicable -> "غير قابل للاستهلاك المباشر"
                    is EntitlementService.Remaining.NotFound -> "غير موجود"
                },
                consumptionCount = consumptionCount
            )
        }
    }

    /**
     * The validated registration path. The ENGINE validates the shape (the
     * exactly-one-of CHECK) — this surface only trims/normalizes inputs and
     * translates the engine's rejections into honest reasons.
     */
    suspend fun registerEntitlement(spec: RegistrationSpec): RegistrationOutcome =
        withContext(Dispatchers.IO) {
            if (spec.providerId.isBlank() || spec.applicabilityScopeId.isBlank()) {
                return@withContext RegistrationOutcome.Rejected(
                    "معرّف المزود ونطاق التطبيق حقلان إلزاميان."
                )
            }
            val engineSpec = EntitlementService.EntitlementSpec(
                providerId = spec.providerId.trim(),
                applicabilityScopeId = spec.applicabilityScopeId.trim(),
                entitlementType = spec.entitlementType.trim(),
                valueKind = spec.valueKind,
                quantityLimitPrecise = spec.quantityLimitPrecise?.trim()?.takeIf { it.isNotEmpty() },
                unitId = spec.unitId?.trim()?.takeIf { it.isNotEmpty() },
                monetaryLimitMicroUnits = spec.monetaryLimitMicroUnits,
                currency = spec.currency?.trim()?.takeIf { it.isNotEmpty() },
                creditLimitPrecise = spec.creditLimitPrecise?.trim()?.takeIf { it.isNotEmpty() },
                creditUnitId = spec.creditUnitId?.trim()?.takeIf { it.isNotEmpty() },
                startsAtEpochMs = spec.startsAtEpochMs,
                expiresAtEpochMs = spec.expiresAtEpochMs,
                resetPolicy = spec.resetPolicy?.trim()?.takeIf { it.isNotEmpty() }
            )
            // The engine THROWS on an invalid shape (its exactly-one-of
            // contract) and the database ENFORCES the reference integrity
            // (scope descriptors, unit definitions). Both surface here as
            // honest Rejected reasons — never as a crash, never silently.
            return@withContext try {
                when (val outcome = entitlements.recordEntitlement(engineSpec)) {
                    is EntitlementService.RecordOutcome.Recorded ->
                        RegistrationOutcome.Registered(outcome.entitlementId)
                    is EntitlementService.RecordOutcome.Replayed ->
                        RegistrationOutcome.Replayed(outcome.entitlementId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                RegistrationOutcome.Rejected(
                    "شكل القيمة غير صالح (العلاقة أحادية-من-القيم): ${e.message}"
                )
            } catch (e: Exception) {
                RegistrationOutcome.Rejected(
                    "رُفض التسجيل بسبب قيد مرجعي — تأكد أن نطاق التطبيق مسجّل كموصّف نطاق (Scope Descriptor) " +
                        "وأن الوحدة معرّفة في السجلات المرجعية (الوحدة القياسية للتوكنات: UNIT_TOKEN). " +
                        "السبب: ${e.message}"
                )
            }
        }

    companion object {
        /** The honest entitlement type vocabulary (UI labels ↔ stored grammar). */
        val ENTITLEMENT_TYPES = listOf(
            "FREE_QUOTA" to "حصة مجانية",
            "TRIAL" to "تجربة",
            "PROMOTIONAL_CREDIT" to "رصيد ترويجي",
            "INCLUDED_SUBSCRIPTION_USAGE" to "استخدام مشمول بالاشتراك",
            "PROVIDER_CREDIT" to "رصيد من المزود",
            "LOCAL_USAGE" to "استخدام محلي",
            "PAID_USAGE" to "استخدام مدفوع",
            "UNKNOWN" to "غير معروف"
        )
        val VALUE_KINDS = listOf(
            EntitlementService.VALUE_UNLIMITED,
            EntitlementService.VALUE_QUANTITY_LIMIT,
            EntitlementService.VALUE_MONETARY_CREDIT,
            EntitlementService.VALUE_PROVIDER_CREDIT,
            EntitlementService.VALUE_UNKNOWN
        )
    }
}
