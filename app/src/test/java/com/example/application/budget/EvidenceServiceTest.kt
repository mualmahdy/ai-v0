package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 4 — EvidenceService (the evidence & sources network service layer)
 * ============================================================================
 * Covers: payload-mode consistency (three modes), record idempotency,
 * digest dedup, immutability (DB triggers), the link replay pattern,
 * the is-primary/role coherence rule, the validity-window rule, and the
 * service half of the I-35 guard (assertAuthoritative).
 */
@RunWith(RobolectricTestRunner::class)
class EvidenceServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var evidence: EvidenceService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage4MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        evidence = EvidenceService(database = db, clock = { 5_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Recording: modes, idempotency, digest dedup, immutability
    // ------------------------------------------------------------------

    @Test
    fun `record - all three payload modes are accepted and validated`() = runBlocking {
        // REDACTED_INLINE
        val inline = evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-1",
                evidenceType = EvidenceService.Vocab.TYPE_OPERATOR_DOCUMENT,
                payloadStorageMode = EvidenceService.Vocab.MODE_REDACTED_INLINE,
                sanitizedPayload = "{\"list_price\": 3.0}",
                redactionStatus = EvidenceService.Vocab.REDACTION_PARTIAL
            )
        )
        assertTrue(inline is EvidenceService.RecordOutcome.Recorded)

        // EXTERNAL_POINTER
        val pointer = evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-2",
                evidenceType = EvidenceService.Vocab.TYPE_PRICING_SHEET,
                payloadStorageMode = EvidenceService.Vocab.MODE_EXTERNAL_POINTER,
                sourceLocation = "https://provider.example/pricing"
            )
        )
        assertTrue(pointer is EvidenceService.RecordOutcome.Recorded)

        // DIGEST_ONLY (digest computed by the service helper)
        val digest = evidence.sha256Hex("invoice-2026-10-json")
        val digestOnly = evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-3",
                evidenceType = EvidenceService.Vocab.TYPE_PROVIDER_INVOICE,
                payloadStorageMode = EvidenceService.Vocab.MODE_DIGEST_ONLY,
                payloadDigest = digest,
                hashAlgorithm = "SHA-256"
            )
        )
        assertTrue(digestOnly is EvidenceService.RecordOutcome.Recorded)

        // mode violations are rejected with clear messages
        try {
            evidence.record(
                EvidenceService.EvidenceSpec(
                    evidenceId = "ev-4",
                    evidenceType = "X",
                    payloadStorageMode = EvidenceService.Vocab.MODE_DIGEST_ONLY
                )
            )
            assertTrue("DIGEST_ONLY without a digest must be rejected", false)
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `record - the same id replays, the same digest dedups`() = runBlocking {
        val digest = evidence.sha256Hex("same-payload")
        evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-a", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_DIGEST_ONLY,
                payloadDigest = digest
            )
        )
        // same id -> replay
        val replay = evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-a", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_DIGEST_ONLY,
                payloadDigest = evidence.sha256Hex("other")
            )
        )
        assertTrue(replay is EvidenceService.RecordOutcome.Replayed)
        // same digest, different id -> dedup replay of the EXISTING row
        val dedup = evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-b", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_DIGEST_ONLY,
                payloadDigest = digest
            )
        )
        assertTrue(dedup is EvidenceService.RecordOutcome.Replayed)
        assertEquals("ev-a", (dedup as EvidenceService.RecordOutcome.Replayed).evidenceId)
    }

    @Test
    fun `evidence rows are immutable at the DB level`() = runBlocking {
        evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-imm", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_REDACTED_INLINE,
                sanitizedPayload = "x"
            )
        )
        try {
            db.openHelper.writableDatabase.execSQL("UPDATE evidence SET evidence_type='T2' WHERE evidence_id='ev-imm'")
            assertTrue("UPDATE must be rejected", false)
        } catch (expected: android.database.SQLException) { }
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM evidence WHERE evidence_id='ev-imm'")
            assertTrue("DELETE must be rejected", false)
        } catch (expected: android.database.SQLException) { }
    }

    // ------------------------------------------------------------------
    // Linking: replay, coherence, windows
    // ------------------------------------------------------------------

    private suspend fun seedOneReconciliation(): String {
        // a minimal terminal reconciliation row the links can point at
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-ev','WORKSPACE','v1|WORKSPACE|ws-ev','hev','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-ev',0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO provider_billing_record (provider_billing_record_id, provider_id, billing_record_type, " +
                "record_granularity, primary_amount_type, currency, amount_consistency_status, source_authority, " +
                "recorded_at_epoch_ms, billing_status) VALUES " +
                "('pbr-ev','prov-ev','INVOICE','MONTHLY','GROSS_PAYABLE','USD','CONSISTENT','manual',0,'POSTED')"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO reconciliation_record (reconciliation_id, provider_billing_record_id, scope_id, " +
                "period_start_epoch_ms, period_end_epoch_ms, provider_currency, local_currency, comparison_currency, " +
                "conversion_rule_id, provider_amount_micro_units, difference_micro_units, tolerance_micro_units, " +
                "coverage_status, reconciliation_status, finality_status, rule_version, recorded_at_epoch_ms) VALUES " +
                "('rec-ev','pbr-ev','sd-ev',0,5000,'USD','USD','USD',null,500,0,0,'COMPLETE','CONFLICT','DRAFT','fx:v1',10)"
        )
        return "rec-ev"
    }

    @Test
    fun `link - first insert links, the same triple replays`() = runBlocking {
        val recId = seedOneReconciliation()
        evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-link", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_REDACTED_INLINE,
                sanitizedPayload = "x"
            )
        )
        val first = evidence.linkReconciliation(
            reconciliationId = recId, evidenceId = "ev-link",
            role = EvidenceService.Vocab.ROLE_PRIMARY, confidence = EvidenceService.Vocab.CONFIDENCE_HIGH
        )
        assertTrue(first is EvidenceService.LinkOutcome.Linked)
        val second = evidence.linkReconciliation(
            reconciliationId = recId, evidenceId = "ev-link",
            role = EvidenceService.Vocab.ROLE_PRIMARY, confidence = EvidenceService.Vocab.CONFIDENCE_HIGH
        )
        assertTrue(second is EvidenceService.LinkOutcome.Replayed)
        // exactly one link row exists
        val count = db.evidenceLinkDao().countPrimaryLinks()
        assertEquals(1, count)
    }

    @Test
    fun `link - vocabulary and window rules are enforced`() = runBlocking {
        val recId = seedOneReconciliation()
        evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-co", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_REDACTED_INLINE,
                sanitizedPayload = "x"
            )
        )
        // NOTE (I-33): is_primary is DERIVED from the role at this layer —
        // an incoherent (role, is_primary) pair cannot be produced through
        // the service; the DB-level vocabulary rejections (role/confidence)
        // are proven by scripts/verify_stage4_ddl.py section 6.
        // empty validity window is rejected
        try {
            evidence.linkReconciliation(
                reconciliationId = recId, evidenceId = "ev-co",
                role = EvidenceService.Vocab.ROLE_SUPPORTING,
                confidence = EvidenceService.Vocab.CONFIDENCE_UNVERIFIED,
                validFromEpochMs = 100L, validUntilEpochMs = 50L
            )
            assertTrue("an empty window must be rejected", false)
        } catch (expected: IllegalArgumentException) { }
        // unknown role rejected
        try {
            evidence.linkReconciliation(
                reconciliationId = recId, evidenceId = "ev-co",
                role = "MAIN",
                confidence = EvidenceService.Vocab.CONFIDENCE_HIGH
            )
            assertTrue("an unknown role must be rejected", false)
        } catch (expected: IllegalArgumentException) { }
    }

    // ------------------------------------------------------------------
    // assertAuthoritative (the service half of I-35)
    // ------------------------------------------------------------------

    @Test
    fun `assertAuthoritative - rejects without PRIMARY, accepts with`() = runBlocking {
        val recId = seedOneReconciliation()
        try {
            evidence.assertAuthoritative(EvidenceService.Domain.RECONCILIATION, recId)
            assertTrue("must reject without PRIMARY evidence", false)
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("PRIMARY"))
        }
        evidence.record(
            EvidenceService.EvidenceSpec(
                evidenceId = "ev-auth", evidenceType = "T",
                payloadStorageMode = EvidenceService.Vocab.MODE_REDACTED_INLINE,
                sanitizedPayload = "x"
            )
        )
        evidence.linkReconciliation(
            reconciliationId = recId, evidenceId = "ev-auth",
            role = EvidenceService.Vocab.ROLE_PRIMARY, confidence = EvidenceService.Vocab.CONFIDENCE_HIGH
        )
        // now it passes
        evidence.assertAuthoritative(EvidenceService.Domain.RECONCILIATION, recId)
    }

    @Test
    fun `sha256Hex is deterministic and hex`() {
        val h1 = evidence.sha256Hex("payload")
        val h2 = evidence.sha256Hex("payload")
        assertEquals("deterministic", h1, h2)
        assertEquals("64 hex chars", 64, h1.length)
        assertTrue("hex alphabet only", h1.all { c -> c.isDigit() || c in 'a'..'f' })
    }
}
