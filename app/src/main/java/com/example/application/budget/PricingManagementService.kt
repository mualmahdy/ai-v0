package com.example.application.budget

import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.ports.budget.PricingRepositoryPort

/**
 * ============================================================================
 * PricingManagementService — STAGE 2 (trustworthy pricing management path)
 * ============================================================================
 * Discovery cannot produce trustworthy prices: OpenAI-compatible /models and
 * Gemini discovery return identifiers and capability metadata — not unit
 * prices; ServiceOffering pricing fields are nullable and
 * registerOfferingPricing intentionally publishes NOTHING when the input
 * price is unknown. That behavior is CORRECT and stays untouched.
 *
 * This service is the explicit, attributable path for VERIFIED pricing
 * evidence — the user enters prices they have verified against their
 * provider's published price sheet, and the entry is stored through the
 * EXISTING pricing domain (PricingEntry → pricing_entries) and mirrored
 * into the EXISTING versioned catalog (PricingCatalogService →
 * pricing_profile / pricing_profile_version / pricing_component). No second
 * pricing authority is introduced.
 *
 * Strict pricing rules enforced here:
 *  - User-entered pricing is stamped provenance = [PROVENANCE_USER_CONFIG]
 *    — clearly USER-CONFIGURED evidence, never provider-discovered.
 *  - A missing price stays missing (null = not published at this scope);
 *    at least ONE price must be present — UNKNOWN pricing is never
 *    "configured" and never becomes zero.
 *  - No price is ever invented, derived from a model name, or defaulted
 *    from another provider; the currency is an explicit field (never
 *    silently assumed to be USD).
 *  - LOCAL is a billing CLASS, not a price: local compute cost is not
 *    monetized here (the accounting path keeps treating it honestly).
 *  - A materially changed price is a NEW effective entry (append-only
 *    history); recorded transactions keep their own applied-pricing
 *    snapshots, and the catalog's windowed version chain keeps historical
 *    resolution reproducible.
 *  - Repeated identical saves are verified no-ops (no version spam).
 */
class PricingManagementService(
    private val pricingRepository: PricingRepositoryPort,
    private val pricingCatalog: PricingCatalogService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** A verified unit-price configuration entered by the operator. */
    data class UserPricingRequest(
        val providerId: String,
        /** Null for provider-scope pricing. */
        val serviceId: String? = null,
        /** Null for provider/service-scope pricing. */
        val modelId: String? = null,
        /** Micro currency units per MILLION input tokens (null = not published). */
        val inputPriceMicroPerMillion: Long? = null,
        /** Micro currency units per MILLION output tokens (null = not published). */
        val outputPriceMicroPerMillion: Long? = null,
        /** Micro currency units per MILLION cached input tokens (null = not published). */
        val cachedInputPriceMicroPerMillion: Long? = null,
        val currency: String,
        val billingClass: BillingClass = BillingClass.PAID,
        val effectiveFromEpochMs: Long? = null,
        val effectiveUntilEpochMs: Long? = null,
        /** Free-form operator note embedded in the provenance (audit trail). */
        val sourceNote: String? = null
    )

    sealed class RegisterOutcome {
        /** New pricing evidence recorded (+ mirrored into the catalog). */
        data class Registered(
            val entryId: String,
            val catalogSync: PricingCatalogService.SyncResult
        ) : RegisterOutcome()

        /** An identical entry already covers `now` — verified no-op. */
        data class Unchanged(val existingEntryId: String) : RegisterOutcome()
    }

    /**
     * Registers user-verified pricing. The scope is derived from identity
     * presence: MODEL (modelId, requires serviceId per the domain contract)
     * > SERVICE (serviceId) > PROVIDER.
     */
    suspend fun registerUserConfiguredPricing(
        request: UserPricingRequest
    ): RegisterOutcome {
        val providerId = request.providerId.trim()
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val serviceId = request.serviceId?.trim()?.takeIf { it.isNotBlank() }
        val modelId = request.modelId?.trim()?.takeIf { it.isNotBlank() }
        val currency = request.currency.trim().uppercase()
        require(currency.isNotBlank()) { "currency must be explicit — never silently assumed" }

        val scope = when {
            modelId != null -> {
                require(serviceId != null) {
                    "MODEL-scope pricing requires serviceId (the domain's identity contract)"
                }
                PricingScope.MODEL
            }
            serviceId != null -> PricingScope.SERVICE
            else -> PricingScope.PROVIDER
        }

        val input = request.inputPriceMicroPerMillion
        val output = request.outputPriceMicroPerMillion
        val cached = request.cachedInputPriceMicroPerMillion
        require(input != null || output != null || cached != null) {
            "at least one unit price must be provided — UNKNOWN pricing is never \"configured\" and never becomes zero"
        }
        listOfNotNull(
            input?.let { "input" to it },
            output?.let { "output" to it },
            cached?.let { "cached" to it }
        ).forEach { (name, price) ->
            require(price >= 0) { "$name price must be >= 0 (an explicitly configured 0 is a real price; missing is null)" }
        }

        val now = clock()
        val effectiveFrom = request.effectiveFromEpochMs ?: now
        val effectiveUntil = request.effectiveUntilEpochMs
        require(effectiveUntil == null || effectiveUntil > effectiveFrom) {
            "effective window invalid: [$effectiveFrom, $effectiveUntil)"
        }

        // ---- Idempotency: an identical USER_CONFIG entry already covering
        //      `now` makes this save a verified no-op (repeated UI saves and
        //      retries never pile up versions). A MATERIALLY different entry
        //      is appended with effectiveFrom = now (history preserved).
        val current = pricingRepository.effectivePricingForProvider(providerId, now)
            .filter { entry ->
                entry.scope == scope &&
                    entry.serviceId == serviceId &&
                    entry.modelId == modelId &&
                    entry.provenance.startsWith(PROVENANCE_USER_CONFIG)
            }
            .maxByOrNull { it.effectiveFromEpochMs }

        if (current != null && materiallyEqual(current, request, currency)) {
            return RegisterOutcome.Unchanged(existingEntryId = current.id)
        }

        val entryId = userEntryId(providerId, serviceId, modelId, effectiveFrom)
        val entry = PricingEntry(
            id = entryId,
            scope = scope,
            providerId = providerId,
            serviceId = serviceId,
            modelId = modelId,
            inputPricePerMillion = input?.let { MoneyAmount.of(it, currency) },
            outputPricePerMillion = output?.let { MoneyAmount.of(it, currency) },
            cachedInputPricePerMillion = cached?.let { MoneyAmount.of(it, currency) },
            billingClass = request.billingClass,
            pricingVersion = "user-$effectiveFrom",
            effectiveFromEpochMs = effectiveFrom,
            effectiveToEpochMs = effectiveUntil,
            provenance = if (request.sourceNote.isNullOrBlank()) {
                PROVENANCE_USER_CONFIG
            } else {
                "$PROVENANCE_USER_CONFIG:${request.sourceNote.trim()}"
            }
        )
        pricingRepository.upsertPricing(entry)

        // ---- Mirror into the versioned catalog (the SAME idempotent pass
        //      the runtime uses; the new row lands as the next version of
        //      its pricing profile — append-only, reproducible history).
        val sync = pricingCatalog.syncFromLegacyEntries()
        return RegisterOutcome.Registered(entryId = entryId, catalogSync = sync)
    }

    /** All configured/discovered pricing evidence (the management list). */
    suspend fun listPricing(): List<PricingEntry> = pricingRepository.allPricing()

    /**
     * Reproducible HISTORICAL resolution: which catalog version covered a
     * provider/service/model at [atEpochMs] (null = UNKNOWN, honestly).
     */
    suspend fun resolveAt(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        atEpochMs: Long
    ): PricingCatalogService.ResolvedVersion? =
        pricingCatalog.resolvePricingVersion(providerId, serviceId, modelId, atEpochMs)

    /** Material equality for the no-op check — prices, currency, class and
     *  window ONLY (identity fields already matched by the caller). */
    private fun materiallyEqual(
        current: PricingEntry,
        request: UserPricingRequest,
        currency: String
    ): Boolean {
        // A window that has since closed means the entry no longer covers
        // `now` — the caller filtered by effective window, so a `current`
        // hit is covering; a closed one would not have matched.
        return current.inputPricePerMillion?.amountMicro == request.inputPriceMicroPerMillion &&
            current.outputPricePerMillion?.amountMicro == request.outputPriceMicroPerMillion &&
            current.cachedInputPricePerMillion?.amountMicro == request.cachedInputPriceMicroPerMillion &&
            priceCurrency(current) == currency &&
            current.billingClass == request.billingClass
    }

    private fun priceCurrency(entry: PricingEntry): String =
        entry.inputPricePerMillion?.currency
            ?: entry.outputPricePerMillion?.currency
            ?: entry.cachedInputPricePerMillion?.currency
            ?: ""

    private fun userEntryId(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        effectiveFrom: Long
    ): String = "price-user:$providerId:${serviceId ?: "-"}:${modelId ?: "-"}:$effectiveFrom"

    companion object {
        /** The provenance stamp of operator-entered pricing evidence. */
        const val PROVENANCE_USER_CONFIG = "USER_CONFIG"
    }
}
