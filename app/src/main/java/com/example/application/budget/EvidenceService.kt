package com.example.application.budget

import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.CurrencyConversionRuleEvidenceEntity
import com.example.infrastructure.persistence.entities.EntitlementEvidenceEntity
import com.example.infrastructure.persistence.entities.EvidenceEntity
import com.example.infrastructure.persistence.entities.MetricRelationshipEvidenceEntity
import com.example.infrastructure.persistence.entities.ModelIdentityMatchEvidenceEntity
import com.example.infrastructure.persistence.entities.ObservationEvidenceEntity
import com.example.infrastructure.persistence.entities.PricingComponentEvidenceEntity
import com.example.infrastructure.persistence.entities.PricingProfileEvidenceEntity
import com.example.infrastructure.persistence.entities.PricingProfileVersionEvidenceEntity
import com.example.infrastructure.persistence.entities.ProviderBillingRecordEvidenceEntity
import com.example.infrastructure.persistence.entities.ReconciliationEvidenceEntity
import com.example.infrastructure.persistence.entities.ResourceConstraintEvidenceEntity
import com.example.infrastructure.persistence.entities.UsageRecordEvidenceEntity
import java.security.MessageDigest

/**
 * ============================================================================
 * EvidenceService — Stage 4: the evidence & sources network service layer.
 * ============================================================================
 * Gap-matrix Phase-4 row: "evidence + 12 جدول ربط بأدوار وثقة وفترات وشرط
 * دليل أساسي عند النشر". Every economic fact links to its source through
 * ONE of the twelve typed link tables; the link carries a role
 * (PRIMARY/SUPPORTING/CONTRADICTING), a confidence state, an is_primary
 * flag coherent with the role, and an optional validity window.
 *
 * The PRIMARY-evidence-at-authorization requirement (شرط الدليل الأساسي
 * عند النشر) is enforced in BOTH layers (I-35):
 *   DB:    the trg_*_requires_primary_evidence guard family rejects the
 *          transition into an authoritative state without a PRIMARY link;
 *   HERE:  [assertAuthoritative] checks the same thing inside the adopting
 *          transaction, so callers get a clear error instead of a raw
 *          trigger ABORT (defense in depth, the I-25 precedent).
 *
 * evidence rows are IMMUTABLE after insert (trg_no_update_evidence /
 * trg_no_delete_evidence); corrections are new evidence rows. Links are
 * append-only too (ABORT inserts; a duplicate (target, evidence, role) is
 * a replay, surfaced as Replayed — idempotent by composite PK).
 */
class EvidenceService(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    // ------------------------------------------------------------------
    // Vocabularies (I-33) — CHECKed at the DB level in Stage4MigrationSql;
    // mirrored here so callers never pass a raw string.
    // ------------------------------------------------------------------
    object Vocab {
        const val ROLE_PRIMARY = "PRIMARY"
        const val ROLE_SUPPORTING = "SUPPORTING"
        const val ROLE_CONTRADICTING = "CONTRADICTING"
        val ROLES = setOf(ROLE_PRIMARY, ROLE_SUPPORTING, ROLE_CONTRADICTING)

        const val CONFIDENCE_HIGH = "HIGH"
        const val CONFIDENCE_MEDIUM = "MEDIUM"
        const val CONFIDENCE_LOW = "LOW"
        const val CONFIDENCE_UNVERIFIED = "UNVERIFIED"
        val CONFIDENCES = setOf(CONFIDENCE_HIGH, CONFIDENCE_MEDIUM, CONFIDENCE_LOW, CONFIDENCE_UNVERIFIED)

        const val MODE_REDACTED_INLINE = "REDACTED_INLINE"
        const val MODE_EXTERNAL_POINTER = "EXTERNAL_POINTER"
        const val MODE_DIGEST_ONLY = "DIGEST_ONLY"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RETIRED = "RETIRED"

        const val REDACTION_NONE = "NONE"
        const val REDACTION_PARTIAL = "PARTIAL"
        const val REDACTION_FULL = "FULL"

        const val CLASSIFICATION_PUBLIC = "PUBLIC"
        const val CLASSIFICATION_INTERNAL = "INTERNAL"
        const val CLASSIFICATION_CONFIDENTIAL = "CONFIDENTIAL"

        /** Well-known evidence types (open vocabulary at the DB level —
         *  the PDM leaves evidence_type free; these are the typed reference
         *  evidence kinds the platform itself produces). */
        const val TYPE_PROVIDER_RESPONSE = "PROVIDER_RESPONSE"
        const val TYPE_PROVIDER_INVOICE = "PROVIDER_INVOICE"
        const val TYPE_PRICING_SHEET = "PRICING_SHEET"
        const val TYPE_CONSOLE_SCREENSHOT = "CONSOLE_SCREENSHOT"
        const val TYPE_OPERATOR_DOCUMENT = "OPERATOR_DOCUMENT"
        const val TYPE_DERIVATION_RULE = "DERIVATION_RULE"
    }

    /** What to record. Mode-specific consistency is validated HERE for a
     *  clear message and AGAIN by the DB CHECKs (defense in depth). */
    data class EvidenceSpec(
        val evidenceId: String,
        val evidenceType: String,
        val payloadStorageMode: String,
        val acquisitionRunId: String? = null,
        val sourceLocation: String? = null,
        val sourceField: String? = null,
        val sourceReference: String? = null,
        val retrievedAtEpochMs: Long? = null,
        val providerRequestId: String? = null,
        val payloadDigest: String? = null,
        val hashAlgorithm: String? = null,
        val verificationAvailable: Boolean = false,
        val redactionStatus: String = Vocab.REDACTION_NONE,
        val retentionUntilEpochMs: Long? = null,
        val evidenceStatus: String = Vocab.STATUS_ACTIVE,
        val securityClassification: String = Vocab.CLASSIFICATION_INTERNAL,
        val sanitizedPayload: String? = null,
        val recordedAtEpochMs: Long? = null
    )

    sealed class RecordOutcome {
        data class Recorded(val evidenceId: String) : RecordOutcome()
        data class Replayed(val evidenceId: String) : RecordOutcome()
    }

    sealed class LinkOutcome {
        data class Linked(val targetId: String, val evidenceId: String) : LinkOutcome()
        data class Replayed(val targetId: String, val evidenceId: String) : LinkOutcome()
    }

    /** The twelve typed domains (one link table each). */
    enum class Domain {
        CURRENCY_CONVERSION_RULE,
        ENTITLEMENT,
        METRIC_RELATIONSHIP,
        MODEL_IDENTITY_MATCH,
        OBSERVATION,
        PRICING_COMPONENT,
        PRICING_PROFILE,
        PRICING_PROFILE_VERSION,
        PROVIDER_BILLING_RECORD,
        RECONCILIATION,
        RESOURCE_CONSTRAINT,
        USAGE_RECORD
    }

    // ------------------------------------------------------------------
    // Recording evidence (immutable, idempotent by evidence_id).
    // ------------------------------------------------------------------

    suspend fun record(spec: EvidenceSpec): RecordOutcome {
        if (spec.evidenceId.isBlank()) throw IllegalArgumentException("evidence id is required")
        validatePayloadMode(spec)
        val existing = database.evidenceDao().byId(spec.evidenceId)
        if (existing != null) return RecordOutcome.Replayed(spec.evidenceId)
        if (spec.payloadDigest != null) {
            database.evidenceDao().byDigest(spec.payloadDigest)?.let {
                // Same payload digest already recorded under a different id:
                // replay that row instead of duplicating content (digest
                // dedup, the ix_evidence_digest purpose).
                return RecordOutcome.Replayed(it.evidenceId)
            }
        }
        val entity = EvidenceEntity(
            evidenceId = spec.evidenceId,
            acquisitionRunId = spec.acquisitionRunId,
            evidenceType = spec.evidenceType,
            sourceLocation = spec.sourceLocation,
            sourceField = spec.sourceField,
            sourceReference = spec.sourceReference,
            retrievedAtEpochMs = spec.retrievedAtEpochMs,
            providerRequestId = spec.providerRequestId,
            payloadDigest = spec.payloadDigest,
            hashAlgorithm = spec.hashAlgorithm,
            payloadStorageMode = spec.payloadStorageMode,
            verificationAvailable = if (spec.verificationAvailable) 1 else 0,
            redactionStatus = spec.redactionStatus,
            retentionUntilEpochMs = spec.retentionUntilEpochMs,
            evidenceStatus = spec.evidenceStatus,
            securityClassification = spec.securityClassification,
            sanitizedPayload = spec.sanitizedPayload,
            recordedAtEpochMs = spec.recordedAtEpochMs ?: clock()
        )
        database.evidenceDao().insert(entity)
        return RecordOutcome.Recorded(spec.evidenceId)
    }

    // ------------------------------------------------------------------
    // Linking evidence to the twelve domains (append-only, idempotent by
    // composite PK). role PRIMARY <-> isPrimary=1 coherence is enforced
    // HERE (the DB CHECKs the values but not their relationship).
    // ------------------------------------------------------------------

    private fun validateLink(role: String, confidence: String, isPrimary: Boolean, validFrom: Long?, validUntil: Long?) {
        if (role !in Vocab.ROLES) throw IllegalArgumentException("unknown evidence role: $role")
        if (confidence !in Vocab.CONFIDENCES) throw IllegalArgumentException("unknown confidence state: $confidence")
        if (role == Vocab.ROLE_PRIMARY != isPrimary) {
            throw IllegalArgumentException("is_primary must mirror the PRIMARY role (role=$role, is_primary=$isPrimary)")
        }
        if (validUntil != null && validFrom != null && validFrom >= validUntil) {
            throw IllegalArgumentException("empty validity window")
        }
    }

    suspend fun linkReconciliation(
        reconciliationId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.reconciliationLinkExists(reconciliationId, evidenceId, role)) {
            return LinkOutcome.Replayed(reconciliationId, evidenceId)
        }
        dao.linkReconciliation(
            ReconciliationEvidenceEntity(
                reconciliationId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(reconciliationId, evidenceId)
    }

    suspend fun linkProviderBillingRecord(
        providerBillingRecordId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.providerBillingRecordLinkExists(providerBillingRecordId, evidenceId, role)) {
            return LinkOutcome.Replayed(providerBillingRecordId, evidenceId)
        }
        dao.linkProviderBillingRecord(
            ProviderBillingRecordEvidenceEntity(
                providerBillingRecordId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(providerBillingRecordId, evidenceId)
    }

    suspend fun linkUsageRecord(
        usageRecordId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.usageRecordLinkExists(usageRecordId, evidenceId, role)) {
            return LinkOutcome.Replayed(usageRecordId, evidenceId)
        }
        dao.linkUsageRecord(
            UsageRecordEvidenceEntity(
                usageRecordId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(usageRecordId, evidenceId)
    }

    suspend fun linkCurrencyConversionRule(
        currencyConversionRuleId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.currencyConversionRuleLinkExists(currencyConversionRuleId, evidenceId, role)) {
            return LinkOutcome.Replayed(currencyConversionRuleId, evidenceId)
        }
        dao.linkCurrencyConversionRule(
            CurrencyConversionRuleEvidenceEntity(
                currencyConversionRuleId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(currencyConversionRuleId, evidenceId)
    }

    suspend fun linkEntitlement(
        entitlementId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.entitlementLinkExists(entitlementId, evidenceId, role)) {
            return LinkOutcome.Replayed(entitlementId, evidenceId)
        }
        dao.linkEntitlement(
            EntitlementEvidenceEntity(
                entitlementId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(entitlementId, evidenceId)
    }

    suspend fun linkPricingProfile(
        pricingProfileId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.pricingProfileLinkExists(pricingProfileId, evidenceId, role)) {
            return LinkOutcome.Replayed(pricingProfileId, evidenceId)
        }
        dao.linkPricingProfile(
            PricingProfileEvidenceEntity(
                pricingProfileId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(pricingProfileId, evidenceId)
    }

    suspend fun linkPricingProfileVersion(
        pricingProfileVersionId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.pricingProfileVersionLinkExists(pricingProfileVersionId, evidenceId, role)) {
            return LinkOutcome.Replayed(pricingProfileVersionId, evidenceId)
        }
        dao.linkPricingProfileVersion(
            PricingProfileVersionEvidenceEntity(
                pricingProfileVersionId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(pricingProfileVersionId, evidenceId)
    }

    suspend fun linkPricingComponent(
        pricingComponentId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.pricingComponentLinkExists(pricingComponentId, evidenceId, role)) {
            return LinkOutcome.Replayed(pricingComponentId, evidenceId)
        }
        dao.linkPricingComponent(
            PricingComponentEvidenceEntity(
                pricingComponentId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(pricingComponentId, evidenceId)
    }

    suspend fun linkModelIdentityMatch(
        matchId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.modelIdentityMatchLinkExists(matchId, evidenceId, role)) {
            return LinkOutcome.Replayed(matchId, evidenceId)
        }
        dao.linkModelIdentityMatch(
            ModelIdentityMatchEvidenceEntity(
                matchId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(matchId, evidenceId)
    }

    suspend fun linkMetricRelationship(
        relationId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.metricRelationshipLinkExists(relationId, evidenceId, role)) {
            return LinkOutcome.Replayed(relationId, evidenceId)
        }
        dao.linkMetricRelationship(
            MetricRelationshipEvidenceEntity(
                relationId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(relationId, evidenceId)
    }

    suspend fun linkResourceConstraint(
        constraintId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.resourceConstraintLinkExists(constraintId, evidenceId, role)) {
            return LinkOutcome.Replayed(constraintId, evidenceId)
        }
        dao.linkResourceConstraint(
            ResourceConstraintEvidenceEntity(
                constraintId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(constraintId, evidenceId)
    }

    suspend fun linkObservation(
        observationId: String,
        evidenceId: String,
        role: String = Vocab.ROLE_PRIMARY,
        confidence: String = Vocab.CONFIDENCE_HIGH,
        validFromEpochMs: Long? = null,
        validUntilEpochMs: Long? = null,
        createdAtEpochMs: Long = clock()
    ): LinkOutcome {
        validateLink(role, confidence, role == Vocab.ROLE_PRIMARY, validFromEpochMs, validUntilEpochMs)
        val dao = database.evidenceLinkDao()
        if (dao.observationLinkExists(observationId, evidenceId, role)) {
            return LinkOutcome.Replayed(observationId, evidenceId)
        }
        dao.linkObservation(
            ObservationEvidenceEntity(
                observationId, evidenceId, role, confidence,
                if (role == Vocab.ROLE_PRIMARY) 1 else 0,
                validFromEpochMs, validUntilEpochMs, createdAtEpochMs
            )
        )
        return LinkOutcome.Linked(observationId, evidenceId)
    }

    /**
     * The service half of the I-35 guard: does [targetId] in [domain]
     * carry at least one PRIMARY evidence link? Call this INSIDE the
     * adopting/publishing transaction before flipping the record into
     * its authoritative state; the DB trigger is the backstop.
     */
    suspend fun assertAuthoritative(domain: Domain, targetId: String) {
        val ok = when (domain) {
            Domain.CURRENCY_CONVERSION_RULE -> database.evidenceLinkDao().hasPrimaryCurrencyConversionRule(targetId)
            Domain.ENTITLEMENT -> database.evidenceLinkDao().hasPrimaryEntitlement(targetId)
            Domain.METRIC_RELATIONSHIP -> database.evidenceLinkDao().hasPrimaryMetricRelationship(targetId)
            Domain.MODEL_IDENTITY_MATCH -> database.evidenceLinkDao().hasPrimaryModelIdentityMatch(targetId)
            Domain.OBSERVATION -> database.evidenceLinkDao().hasPrimaryObservation(targetId)
            Domain.PRICING_COMPONENT -> database.evidenceLinkDao().hasPrimaryPricingComponent(targetId)
            Domain.PRICING_PROFILE -> database.evidenceLinkDao().hasPrimaryPricingProfile(targetId)
            Domain.PRICING_PROFILE_VERSION -> database.evidenceLinkDao().hasPrimaryPricingProfileVersion(targetId)
            Domain.PROVIDER_BILLING_RECORD -> database.evidenceLinkDao().hasPrimaryProviderBillingRecord(targetId)
            Domain.RECONCILIATION -> database.evidenceLinkDao().hasPrimaryReconciliation(targetId)
            Domain.RESOURCE_CONSTRAINT -> database.evidenceLinkDao().hasPrimaryResourceConstraint(targetId)
            Domain.USAGE_RECORD -> database.evidenceLinkDao().hasPrimaryUsageRecord(targetId)
        }
        if (!ok) {
            throw IllegalStateException(
                "$targetId ($domain) carries no PRIMARY evidence — an authoritative " +
                    "state requires at least one PRIMARY link (شرط الدليل الأساسي عند النشر)"
            )
        }
    }

    /** Dashboard/coverage: how many authoritative facts carry their source. */
    suspend fun countPrimaryLinks(): Int = database.evidenceLinkDao().countPrimaryLinks()

    /** SHA-256 hex digest helper for DIGEST_ONLY mode (the platform's
     *  single hash algorithm — recorded per row in hash_algorithm). */
    fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun validatePayloadMode(spec: EvidenceSpec) {
        when (spec.payloadStorageMode) {
            Vocab.MODE_DIGEST_ONLY ->
                require(!spec.payloadDigest.isNullOrBlank()) { "DIGEST_ONLY requires a payload digest" }
            Vocab.MODE_EXTERNAL_POINTER ->
                require(!spec.sourceReference.isNullOrBlank() || !spec.sourceLocation.isNullOrBlank()) {
                    "EXTERNAL_POINTER requires source_reference or source_location"
                }
            Vocab.MODE_REDACTED_INLINE ->
                require(!spec.sanitizedPayload.isNullOrBlank()) { "REDACTED_INLINE requires a sanitized payload" }
            else -> throw IllegalArgumentException("unknown payload storage mode: ${spec.payloadStorageMode}")
        }
    }
}
