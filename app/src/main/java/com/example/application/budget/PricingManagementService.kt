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
    private val clock: () -> Long = System::currentTimeMillis,
    /** STAGE 2B (WS-D) — optional identity probe against the provider
     *  registry: a pricing entry referencing a nonexistent provider (or a
     *  service that does not exist under it) must not silently appear
     *  valid while failing to match actual runtime requests. The probe
     *  does NOT reject the write (pre-registration stays possible) — it
     *  attaches an honest identity status to the outcome so the UI can
     *  warn. Null = validation unavailable (tests/legacy wiring). */
    private val identityProbe: (suspend (providerId: String, serviceId: String?) -> PricingIdentityStatus)? = null
) {

    /** STAGE 2B (WS-D) — the honest registry-check verdict for a pricing
     *  entry's identity. PROVIDER_AND_SERVICE_FOUND means the referenced
     *  identities exist; anything else is surfaced to the operator, never
     *  silently swallowed. */
    enum class PricingIdentityStatus {
        PROVIDER_AND_SERVICE_FOUND,
        PROVIDER_FOUND_SERVICE_UNKNOWN,
        PROVIDER_UNKNOWN,
        /** No probe wired — honesty means saying validation did not run. */
        VALIDATION_UNAVAILABLE
    }

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
        val sourceNote: String? = null,
        /** STAGE 2B (WS-D) — optional EVIDENCE REFERENCE: the URL / document /
         *  artifact the operator verified the price against (e.g. the
         *  provider's published price sheet). Recorded in the provenance
         *  chain — user-ATTESTED accuracy evidence, explicitly distinct
         *  from independent verification (which only a future verified
         *  pipeline may set; nothing here ever promotes USER_CONFIG to
         *  VERIFIED). */
        val evidenceReference: String? = null
    )

    sealed class RegisterOutcome {
        /** New pricing evidence recorded (+ mirrored into the catalog). */
        data class Registered(
            val entryId: String,
            val catalogSync: PricingCatalogService.SyncResult?,
            /** STAGE 2B (WS-C) — when the pricing entry was persisted but
             *  the catalog mirror failed, the failure is reported honestly
             *  (never swallowed): catalogSync is null and the reason is
             *  carried. Recovery: [resyncPricingCatalog] (idempotent) or
             *  any later successful save (the mirror processes every
             *  unrepresented entry). */
            val catalogSyncFailure: String? = null,
            /** STAGE 2B (WS-D) — the identity check verdict (honest warning
             *  surface; never a silent "valid"). */
            val identityStatus: PricingIdentityStatus = PricingIdentityStatus.VALIDATION_UNAVAILABLE
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

        val entryId = userEntryId(providerId, serviceId, modelId, effectiveFrom, request, currency)
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
            provenance = userProvenance(request.sourceNote, request.evidenceReference)
        )
        pricingRepository.upsertPricing(entry)

        // ---- STAGE 2B (WS-D): the honest identity check (after the write,
        //      never a silent validity — the outcome carries the verdict
        //      and the UI warns when the referenced identities are unknown).
        val identityStatus = identityProbe?.let { probe ->
            runCatching { probe(providerId, serviceId) }.getOrDefault(
                PricingIdentityStatus.VALIDATION_UNAVAILABLE
            )
        } ?: PricingIdentityStatus.VALIDATION_UNAVAILABLE

        // ---- Mirror into the versioned catalog (the SAME idempotent pass
        //      the runtime uses; the new row lands as the next version of
        //      its pricing profile — append-only, reproducible history).
        //      STAGE 2B (WS-C): a mirror failure after the entry was
        //      persisted is PARTIAL FAILURE — reported honestly, never
        //      swallowed: the entry stands, the catalog catch-up has a
        //      defined recovery ([resyncPricingCatalog] / the next save /
        //      ensureSyncedAndResolve on read).
        val sync = runCatching { pricingCatalog.syncFromLegacyEntries() }
        return sync.fold(
            onSuccess = { RegisterOutcome.Registered(entryId = entryId, catalogSync = it, identityStatus = identityStatus) },
            onFailure = { failure ->
                RegisterOutcome.Registered(
                    entryId = entryId,
                    catalogSync = null,
                    catalogSyncFailure = failure.message ?: failure.javaClass.simpleName,
                    identityStatus = identityStatus
                )
            }
        )
    }

    /**
     * STAGE 2B (WS-C) — the catalog-mirror RECOVERY path: re-runs the
     * idempotent sync so a catalog left stale by a partial failure
     * (pricing entry persisted, mirror failed) catches up with every
     * unrepresented entry. Historical resolveAt results stay stable —
     * the mirror only APPENDS missing versions, never rewrites one.
     */
    suspend fun resyncPricingCatalog(): PricingCatalogService.SyncResult =
        pricingCatalog.syncFromLegacyEntries()

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

    /**
     * STAGE 2B — the USER_CONFIG provenance grammar (append-only audit
     * chain, stable prefix for every existing consumer):
     *
     *   USER_CONFIG
     *   USER_CONFIG:<note>
     *   USER_CONFIG:<note>|ref=<evidence reference>
     *   USER_CONFIG|ref=<evidence reference>
     *
     * The `ref` segment is the operator's EVIDENCE REFERENCE (WS-D): the
     * URL/document the price was attested against. USER_CONFIG + ref is
     * user-ATTESTED accuracy — explicitly NOT independent verification.
     */
    private fun userProvenance(sourceNote: String?, evidenceReference: String?): String {
        val note = sourceNote?.trim()?.takeIf { it.isNotBlank() }
        val ref = evidenceReference?.trim()?.takeIf { it.isNotBlank() }
        return buildString {
            append(PROVENANCE_USER_CONFIG)
            if (note != null) append(":").append(note)
            if (ref != null) append("|ref=").append(ref)
        }
    }

    /**
     * STAGE 2B (WS-C) — collision-resistant entry identity: the legacy
     * id derived identity fields + effectiveFrom ONLY, so two materially
     * different writes under the SAME injected clock tick collided and
     * the second silently REPLACED the first (@Upsert). The id now
     * carries a SHA-256 digest of the FULL material content (prices,
     * currency, billing class, window, provenance) — materially different
     * evidence can never collide, while an identical re-save reproduces
     * the same id (verified no-op by the pre-check, so the upsert never
     * even runs). The pricing history stays append-only.
     */
    private fun userEntryId(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        effectiveFrom: Long,
        request: UserPricingRequest,
        currency: String
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append(request.inputPriceMicroPerMillion?.toString() ?: "-").append('|')
                    append(request.outputPriceMicroPerMillion?.toString() ?: "-").append('|')
                    append(request.cachedInputPriceMicroPerMillion?.toString() ?: "-").append('|')
                    append(currency).append('|')
                    append(request.billingClass.name).append('|')
                    append(request.effectiveUntilEpochMs?.toString() ?: "-").append('|')
                    append(userProvenance(request.sourceNote, request.evidenceReference))
                }.toByteArray(Charsets.UTF_8)
            )
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "price-user:$providerId:${serviceId ?: "-"}:${modelId ?: "-"}:$effectiveFrom:$digest"
    }

    companion object {
        /** The provenance stamp of operator-entered pricing evidence. */
        const val PROVENANCE_USER_CONFIG = "USER_CONFIG"
    }
}
