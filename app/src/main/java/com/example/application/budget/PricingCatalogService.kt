package com.example.application.budget

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.PricingComponentEntity
import com.example.infrastructure.persistence.entities.PricingProfileEntity
import com.example.infrastructure.persistence.entities.PricingProfileVersionEntity
import java.security.MessageDigest

/**
 * ============================================================================
 * PricingCatalogService — Stage-2 pricing catalog (roadmap Phase 2)
 * ============================================================================
 * Versioned pricing files: pricing_profile (one provider × one
 * applicability target) → pricing_profile_version (non-gapping effective
 * windows) → pricing_component (per-metric price lines). The current flat
 * `pricing_entries` table remains the run-time WRITE surface during the
 * transition; this catalog is the read model that makes pricing
 * VERSIONED, ITEMIZED and AUDITABLE (I-13: legacy upserts are mirrored
 * here by [syncFromLegacyEntries]; the mirror is idempotent).
 *
 * Resolution reproduces the engine's documented precedence
 * (MODEL > SERVICE > PROVIDER, most recent effective window wins) — the
 * parity property the roadmap Phase-2 exit criterion is tested against.
 */
class PricingCatalogService(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    // ------------------------------------------------------------------
    // Identity helpers — shared verbatim with MIGRATION_24_TO_25's data
    // pass (Stage2PricingMigrator) so migrated rows and runtime rows are
    // indistinguishable.
    // ------------------------------------------------------------------

    object Ids {
        /** `SCOPE:modelId-or-'-':serviceId-or-'-'` (both segments explicit —
         *  the legacy table's full identity, no silent collapsing). */
        fun profileCode(scopeType: String, modelId: String?, serviceId: String?): String =
            "$scopeType:${modelId ?: "-"}:${serviceId ?: "-"}"

        fun profileId(providerId: String, profileCode: String): String =
            "pp_${sha256Hex("profile|$providerId|$profileCode").substring(0, 32)}"

        fun versionId(profileId: String, versionNumber: Int): String =
            "ppv_${sha256Hex("version|$profileId|$versionNumber").substring(0, 32)}"

        fun componentId(versionId: String, componentCode: String): String =
            "pc_${sha256Hex("component|$versionId|$componentCode").substring(0, 32)}"

        fun pricingSourceForLegacyEntry(entryId: String): String =
            "MIGRATED:pricing_entries:$entryId"

        const val PRICING_SCOPE_KIND = "PRICING"

        fun scopeCanonical(providerId: String, profileCode: String): String =
            "v1|$PRICING_SCOPE_KIND|provider=$providerId|code=$profileCode"

        fun scopeId(providerId: String, profileCode: String): String =
            "$PRICING_SCOPE_KIND#${sha256Hex(scopeCanonical(providerId, profileCode)).substring(0, 40)}"

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    /** A resolved, covering pricing version with its priced components. */
    data class ResolvedVersion(
        val profile: PricingProfileEntity,
        val version: PricingProfileVersionEntity,
        val components: List<PricingComponentEntity>
    )

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Resolves the pricing version covering [atEpochMs] with the engine's
     * documented precedence: MODEL (model matches, service blank-or-equal)
     * → SERVICE (service matches, no model) → PROVIDER (plain). Within a
     * level, the covering version with the highest effective_from wins
     * (mirrors maxByOrNull(effectiveFromEpochMs) over window-filtered
     * candidates; the exact-service tie-break is a documented
     * refinement of an undefined legacy tie).
     */
    suspend fun resolvePricingVersion(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        atEpochMs: Long
    ): ResolvedVersion? {
        if (providerId.isBlank()) return null

        val modelCandidates = buildList {
            if (!modelId.isNullOrBlank()) {
                if (!serviceId.isNullOrBlank()) {
                    add(Ids.profileCode("MODEL", modelId, serviceId))
                    add(Ids.profileCode("MODEL", modelId, null))
                } else {
                    // request carries no service dimension: every service
                    // segment of this model matches (legacy filter is
                    // trivially true when the request's serviceId is null).
                    database.pricingProfileDao().forProvider(providerId)
                        .filter { it.providerId == providerId && it.profileCode.startsWith("MODEL:$modelId:") }
                        .forEach { add(it.profileCode) }
                }
            }
        }
        val serviceCandidates =
            if (!serviceId.isNullOrBlank()) listOf(Ids.profileCode("SERVICE", null, serviceId)) else emptyList()
        val providerCandidates = listOf(Ids.profileCode("PROVIDER", null, null))

        for (level in listOf(modelCandidates, serviceCandidates, providerCandidates)) {
            val best = level
                .mapNotNull { code -> resolveProfile(providerId, code, atEpochMs) }
                .maxWithOrNull(
                    compareBy(
                        { it.version.effectiveFromEpochMs },
                        // exact-service codes outrank blank-service codes on ties
                        { it.profile.profileCode.count { ch -> ch != '-' } },
                        { it.version.versionNumber }
                    )
                )
            if (best != null) return best
        }
        return null
    }

    private suspend fun resolveProfile(
        providerId: String,
        profileCode: String,
        atEpochMs: Long
    ): ResolvedVersion? {
        val profile = database.pricingProfileDao().byProviderAndCode(providerId, profileCode)
            ?: return null
        val version = database.pricingProfileVersionDao()
            .coveringForProfile(profile.pricingProfileId, atEpochMs) ?: return null
        val components = database.pricingComponentDao().forVersion(version.pricingProfileVersionId)
        return ResolvedVersion(profile, version, components)
    }

    /**
     * Resolve, and when the catalog has nothing covering the request,
     * mirror the current legacy entries once and resolve again (I-13).
     * Returns null when pricing is genuinely unknown — the caller records
     * an honest NOT_POSSIBLE / UNKNOWN, never a fabricated price.
     */
    suspend fun ensureSyncedAndResolve(
        providerId: String,
        serviceId: String?,
        modelId: String?,
        atEpochMs: Long
    ): ResolvedVersion? {
        resolvePricingVersion(providerId, serviceId, modelId, atEpochMs)?.let { return it }
        syncFromLegacyEntries()
        return resolvePricingVersion(providerId, serviceId, modelId, atEpochMs)
    }

    // ------------------------------------------------------------------
    // Idempotent mirror of the legacy pricing surface (I-13)
    // ------------------------------------------------------------------

    data class SyncResult(
        val profilesCreated: Int,
        val versionsCreated: Int,
        val componentsCreated: Int
    )

    /**
     * Mirrors every legacy pricing_entries row that is not yet represented
     * into the catalog. Idempotent: a version whose pricing_source equals
     * [Ids.pricingSourceForLegacyEntry] for an entry is never re-created.
     * A materially edited legacy entry (new effectiveFrom/id) simply lands
     * as the next version of its profile — pricing corrections are version
     * rows, never UPDATEs (append-only, ADR-B2).
     */
    suspend fun syncFromLegacyEntries(): SyncResult = database.withTransaction {
        val now = clock()
        var profiles = 0
        var versions = 0
        var components = 0
        val entries = database.pricingEntryDao().all()
        for (entry in entries) {
            val profileCode = Ids.profileCode(entry.scopeType, entry.modelId, entry.serviceId)
            val pricingSource = Ids.pricingSourceForLegacyEntry(entry.id)
            if (database.pricingProfileVersionDao().byPricingSource(pricingSource) != null) continue

            val profileId = ensureProfile(entry.providerId, profileCode, entry.currency, now)
                .also { if (it.second) profiles++ }.first
            val nextVersion = (database.pricingProfileVersionDao().maxVersionNumber(profileId) ?: 0) + 1
            val versionId = Ids.versionId(profileId, nextVersion)

            val priced = listOf(
                "INPUT_TOKENS" to entry.inputPriceMicroPerMillion,
                "OUTPUT_TOKENS" to entry.outputPriceMicroPerMillion,
                "CACHED_TOKENS" to entry.cachedInputPriceMicroPerMillion
            ).filter { it.second != null }

            database.pricingProfileVersionDao().insert(
                PricingProfileVersionEntity(
                    pricingProfileVersionId = versionId,
                    pricingProfileId = profileId,
                    versionNumber = nextVersion,
                    pricingSource = pricingSource,
                    authority = entry.provenance,
                    effectiveFromEpochMs = entry.effectiveFromEpochMs,
                    effectiveUntilEpochMs = entry.effectiveToEpochMs,
                    retrievedAtEpochMs = entry.createdAtEpochMs,
                    recordedAtEpochMs = now,
                    effectiveStatus = STATUS_ACTIVE,
                    verificationStatus = VERIFICATION_UNVERIFIED,
                    precedence = 0,
                    pricingStatus = if (priced.isEmpty()) STATUS_NOT_CALCULABLE else STATUS_CALCULABLE,
                    roundingStrategy = CostMath.ROUNDING_STRATEGY,
                    createdAtEpochMs = now
                )
            )
            versions++

            priced.forEachIndexed { index, (code, price) ->
                database.pricingComponentDao().insert(componentFor(versionId, code, price!!, entry.currency, index + 1))
                components++
            }
        }
        SyncResult(profiles, versions, components)
    }

    /** (profileId, created) — descriptor + profile row, idempotent. */
    private suspend fun ensureProfile(
        providerId: String,
        profileCode: String,
        currency: String,
        now: Long
    ): Pair<String, Boolean> {
        database.pricingProfileDao().byProviderAndCode(providerId, profileCode)?.let {
            return Pair(it.pricingProfileId, false)
        }
        val profileId = Ids.profileId(providerId, profileCode)
        val scopeId = Ids.scopeId(providerId, profileCode)
        // applicability scope descriptor (same shape Stage-1 ensureDescriptor
        // produces; provider + platform dimensions KNOWN, rest NOT_APPLICABLE)
        val canonical = Ids.scopeCanonical(providerId, profileCode)
        val hash = Ids.sha256Hex(canonical)
        if (database.scopeDescriptorDao().byId(scopeId) == null) {
            database.scopeDescriptorDao().insert(
                com.example.infrastructure.persistence.entities.ScopeDescriptorEntity(
                    scopeId = scopeId,
                    scopeKind = Ids.PRICING_SCOPE_KIND,
                    canonicalRepresentation = canonical,
                    canonicalHash = hash,
                    hashAlgorithm = "SHA-256",
                    canonicalizationVersion = 1,
                    providerDimensionState = "KNOWN",
                    providerId = providerId,
                    integrationDimensionState = "NOT_APPLICABLE",
                    integrationId = null,
                    accessContextDimensionState = "NOT_APPLICABLE",
                    accessContextId = null,
                    providerScopeNodeDimensionState = "NOT_APPLICABLE",
                    providerScopeNodeId = null,
                    offeringDimensionState = "NOT_APPLICABLE",
                    offeringId = null,
                    requestAttemptDimensionState = "NOT_APPLICABLE",
                    requestAttemptId = null,
                    budgetPolicyDimensionState = "UNKNOWN",
                    budgetPolicyId = null,
                    budgetPeriodDimensionState = "UNKNOWN",
                    budgetPeriodId = null,
                    regionDimensionState = "NOT_APPLICABLE",
                    region = null,
                    endpointDimensionState = "NOT_APPLICABLE",
                    endpointRef = null,
                    periodDimensionState = "NOT_APPLICABLE",
                    periodStart = null,
                    periodEnd = null,
                    platformDimensionState = "KNOWN",
                    platformObjectType = "PRICING_TARGET",
                    platformObjectId = profileCode,
                    createdAtEpochMs = now
                )
            )
        }
        database.pricingProfileDao().insert(
            PricingProfileEntity(
                pricingProfileId = profileId,
                providerId = providerId,
                profileCode = profileCode,
                currency = currency,
                applicabilityScopeId = scopeId,
                profileStatus = STATUS_ACTIVE,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
        return Pair(profileId, true)
    }

    internal fun componentFor(
        versionId: String,
        componentCode: String,
        rateMicroPerMillion: Long,
        currency: String,
        order: Int
    ): PricingComponentEntity = PricingComponentEntity(
        pricingComponentId = Ids.componentId(versionId, componentCode),
        pricingProfileVersionId = versionId,
        componentCode = componentCode,
        componentType = "TOKEN_PRICE",
        metricId = "METRIC_$componentCode",
        quantityUnitId = "UNIT_TOKEN",
        currency = currency,
        calculationMode = "SIMPLE_UNIT_PRICE",
        componentApplicationBasis = null,
        tierApplicationMode = "NOT_APPLICABLE",
        quantityScope = "PER_REQUEST",
        rateAmountPrecise = rateMicroPerMillion.toString(),
        priceUnitQuantityPrecise = CostMath.PRICE_UNIT_QUANTITY.toPlainString(),
        roundingPolicy = CostMath.RULE_SET_VERSION,
        componentOrder = order,
        componentStatus = STATUS_ACTIVE
    )

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_CALCULABLE = "CALCULABLE"
        const val STATUS_NOT_CALCULABLE = "NOT_CALCULABLE"
        const val VERIFICATION_UNVERIFIED = "UNVERIFIED"
    }
}

/**
 * The one-shot data pass of MIGRATION_24_TO_25: mirrors every existing
 * pricing_entries row into the catalog with proper window clamping (the
 * runtime [PricingCatalogService.syncFromLegacyEntries] is the incremental
 * variant of the same mapping). Raw SQL because migrations run before
 * DAOs exist; the identity scheme is shared with the service through
 * [PricingCatalogService.Ids].
 */
internal object Stage2PricingMigrator {

    fun migrate(db: SupportSQLiteDatabase): Triple<Int, Int, Int> {
        var profiles = 0
        var versions = 0
        var components = 0

        data class Entry(
            val id: String, val scopeType: String, val providerId: String,
            val serviceId: String?, val modelId: String?,
            val inputPrice: Long?, val outputPrice: Long?, val cachedPrice: Long?,
            val currency: String, val provenance: String,
            val fromMs: Long, val toMs: Long?, val createdAtMs: Long
        )

        val entries = mutableListOf<Entry>()
        db.query(
            "SELECT id, scopeType, providerId, serviceId, modelId, inputPriceMicroPerMillion, " +
                "outputPriceMicroPerMillion, cachedInputPriceMicroPerMillion, currency, provenance, " +
                "effectiveFromEpochMs, effectiveToEpochMs, createdAtEpochMs FROM pricing_entries " +
                "ORDER BY providerId, scopeType, COALESCE(modelId,''), COALESCE(serviceId,''), " +
                "effectiveFromEpochMs, createdAtEpochMs, id"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(
                    Entry(
                        id = cursor.getString(0),
                        scopeType = cursor.getString(1),
                        providerId = cursor.getString(2),
                        serviceId = cursor.getString(3),
                        modelId = cursor.getString(4),
                        inputPrice = if (cursor.isNull(5)) null else cursor.getLong(5),
                        outputPrice = if (cursor.isNull(6)) null else cursor.getLong(6),
                        cachedPrice = if (cursor.isNull(7)) null else cursor.getLong(7),
                        currency = cursor.getString(8),
                        provenance = cursor.getString(9),
                        fromMs = cursor.getLong(10),
                        toMs = if (cursor.isNull(11)) null else cursor.getLong(11),
                        createdAtMs = cursor.getLong(12)
                    )
                )
            }
        }

        // group by profile identity; per profile: dedupe same effectiveFrom
        // (latest createdAt wins), order by from, clamp windows so they are
        // non-overlapping and non-inverted (empty/malformed rows are skipped
        // honestly — a price row that never applies mirrors as nothing).
        val byProfile = entries.groupBy {
            Triple(it.providerId, it.scopeType, Ids.profileCode(it.scopeType, it.modelId, it.serviceId))
        }

        for ((key, group) in byProfile) {
            val (providerId, _, profileCode) = key
            val currency = group.first().currency
            val deduped = group
                .groupBy { it.fromMs }
                .map { (_, same) -> same.maxBy { e -> e.createdAtMs } }
                .sortedBy { it.fromMs }

            var versionNumber = 0
            var profileCreated = false
            for ((index, entry) in deduped.withIndex()) {
                val nextFrom = deduped.getOrNull(index + 1)?.fromMs
                val until = when {
                    nextFrom == null -> entry.toMs
                    entry.toMs == null -> nextFrom
                    else -> minOf(entry.toMs, nextFrom)
                }
                if (until != null && until <= entry.fromMs) continue // malformed or fully shadowed

                if (!profileCreated) {
                    ensureProfileRow(db, providerId, profileCode, currency, entry.createdAtMs)
                    profileCreated = true
                    profiles++
                }
                versionNumber++
                val versionId = Ids.versionId(Ids.profileId(providerId, profileCode), versionNumber)
                val priced = listOf(
                    "INPUT_TOKENS" to entry.inputPrice,
                    "OUTPUT_TOKENS" to entry.outputPrice,
                    "CACHED_TOKENS" to entry.cachedPrice
                ).filter { it.second != null }

                db.execSQL(
                    "INSERT OR IGNORE INTO pricing_profile_version (" +
                        "pricing_profile_version_id, pricing_profile_id, version_number, pricing_source, authority, " +
                        "effective_from_epoch_ms, effective_until_epoch_ms, retrieved_at_epoch_ms, recorded_at_epoch_ms, " +
                        "effective_status, verification_status, precedence, pricing_status, rounding_strategy, created_at_epoch_ms) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        versionId, Ids.profileId(providerId, profileCode), versionNumber,
                        Ids.pricingSourceForLegacyEntry(entry.id), entry.provenance,
                        entry.fromMs, until, entry.createdAtMs, entry.createdAtMs,
                        "ACTIVE", "UNVERIFIED", 0,
                        if (priced.isEmpty()) "NOT_CALCULABLE" else "CALCULABLE",
                        CostMath.ROUNDING_STRATEGY, entry.createdAtMs
                    )
                )
                versions++

                priced.forEachIndexed { i, (code, price) ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO pricing_component (" +
                            "pricing_component_id, pricing_profile_version_id, component_code, component_type, metric_id, " +
                            "quantity_unit_id, currency, calculation_mode, component_application_basis, tier_application_mode, " +
                            "quantity_scope, rate_amount_precise, price_unit_quantity_precise, rounding_policy, component_order, component_status) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf(
                            Ids.componentId(versionId, code), versionId, code, "TOKEN_PRICE",
                            "METRIC_$code", "UNIT_TOKEN", entry.currency, "SIMPLE_UNIT_PRICE", null,
                            "NOT_APPLICABLE", "PER_REQUEST", price.toString(),
                            CostMath.PRICE_UNIT_QUANTITY.toPlainString(), CostMath.RULE_SET_VERSION,
                            i + 1, "ACTIVE"
                        )
                    )
                    components++
                }
            }
        }
        return Triple(profiles, versions, components)
    }

    private fun ensureProfileRow(
        db: SupportSQLiteDatabase,
        providerId: String,
        profileCode: String,
        currency: String,
        nowMs: Long
    ) {
        val canonical = Ids.scopeCanonical(providerId, profileCode)
        val hash = Ids.sha256Hex(canonical)
        val scopeId = Ids.scopeId(providerId, profileCode)
        db.execSQL(
            "INSERT OR IGNORE INTO scope_descriptor (" +
                "scope_id, scope_kind, canonical_representation, canonical_hash, hash_algorithm, canonicalization_version, " +
                "provider_dimension_state, provider_id, integration_dimension_state, integration_id, " +
                "access_context_dimension_state, access_context_id, provider_scope_node_dimension_state, provider_scope_node_id, " +
                "offering_dimension_state, offering_id, request_attempt_dimension_state, request_attempt_id, " +
                "budget_policy_dimension_state, budget_policy_id, budget_period_dimension_state, budget_period_id, " +
                "region_dimension_state, region, endpoint_dimension_state, endpoint_ref, " +
                "period_dimension_state, period_start, period_end, " +
                "platform_dimension_state, platform_object_type, platform_object_id, created_at_epoch_ms) " +
                "VALUES (?,?,?,?,?,?, 'KNOWN', ?, 'NOT_APPLICABLE', NULL, 'NOT_APPLICABLE', NULL, 'NOT_APPLICABLE', NULL, " +
                "'NOT_APPLICABLE', NULL, 'NOT_APPLICABLE', NULL, 'UNKNOWN', NULL, 'UNKNOWN', NULL, " +
                "'NOT_APPLICABLE', NULL, 'NOT_APPLICABLE', NULL, 'NOT_APPLICABLE', NULL, NULL, " +
                "'KNOWN', 'PRICING_TARGET', ?, ?)",
            arrayOf(scopeId, "PRICING", canonical, hash, "SHA-256", 1, providerId, profileCode, nowMs)
        )
        db.execSQL(
            "INSERT OR IGNORE INTO pricing_profile (" +
                "pricing_profile_id, provider_id, profile_code, currency, applicability_scope_id, profile_status, " +
                "created_at_epoch_ms, updated_at_epoch_ms) VALUES (?,?,?,?,?, 'ACTIVE', ?, ?)",
            arrayOf(Ids.profileId(providerId, profileCode), providerId, profileCode, currency, scopeId, nowMs, nowMs)
        )
    }

    private val Ids = PricingCatalogService.Ids
}
