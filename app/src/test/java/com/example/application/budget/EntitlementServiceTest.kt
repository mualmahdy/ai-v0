package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * ============================================================================
 * STAGE 3 — EntitlementService (the accruals layer)
 * ============================================================================
 * Exactly-one-of value shapes, idempotent consumption, conservative
 * remaining. I-29: this layer RECORDS, spend routing is Stage 4.
 */
@RunWith(RobolectricTestRunner::class)
class EntitlementServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var entitlements: EntitlementService

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
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        entitlements = EntitlementService(database = db, clock = { 0L })
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-e','WORKSPACE','v1|WORKSPACE|ws-e','h','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-e',0)"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `recording is idempotent and shapes are validated`() = runBlocking {
        val spec = EntitlementService.EntitlementSpec(
            providerId = "prov-e",
            applicabilityScopeId = "sd-e",
            entitlementType = "FREE_QUOTA",
            valueKind = EntitlementService.VALUE_QUANTITY_LIMIT,
            quantityLimitPrecise = "1000000",
            unitId = "UNIT_TOKEN",
            startsAtEpochMs = 0L,
            expiresAtEpochMs = 10_000L,
            consumptionOrder = 1
        )
        val first = entitlements.recordEntitlement(spec)
        assertTrue(first is EntitlementService.RecordOutcome.Recorded)
        val replay = entitlements.recordEntitlement(spec)
        assertTrue("identical entitlement replays", replay is EntitlementService.RecordOutcome.Replayed)

        // exactly-one-of violations rejected with clear messages
        try {
            entitlements.recordEntitlement(
                spec.copy(valueKind = EntitlementService.VALUE_UNLIMITED, quantityLimitPrecise = "5", unitId = null)
            )
            assertTrue("UNLIMITED with a value field must be rejected", false)
        } catch (expected: IllegalArgumentException) {
            // correct
        }
        try {
            entitlements.recordEntitlement(
                spec.copy(valueKind = EntitlementService.VALUE_MONETARY_CREDIT, quantityLimitPrecise = null, unitId = null)
            )
            assertTrue("MONETARY_CREDIT without amount+currency must be rejected", false)
        } catch (expected: IllegalArgumentException) {
            // correct
        }
    }

    @Test
    fun `consumption is idempotent per (entitlement, key) and immutable`() = runBlocking {
        val recorded = entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov-e", applicabilityScopeId = "sd-e",
                entitlementType = "FREE_QUOTA",
                valueKind = EntitlementService.VALUE_QUANTITY_LIMIT,
                quantityLimitPrecise = "1000000", unitId = "UNIT_TOKEN"
            )
        ) as EntitlementService.RecordOutcome.Recorded

        val first = entitlements.consume(
            entitlementId = recorded.entitlementId,
            consumptionGranularity = EntitlementService.GRANULARITY_PROVIDER_DECLARED,
            idempotencyKey = "decl-2026-10",
            quantityPrecise = "400000",
            unitId = "UNIT_TOKEN"
        )
        assertTrue(first is EntitlementService.ConsumptionOutcome.Consumed)

        val replay = entitlements.consume(
            entitlementId = recorded.entitlementId,
            consumptionGranularity = EntitlementService.GRANULARITY_PROVIDER_DECLARED,
            idempotencyKey = "decl-2026-10",
            quantityPrecise = "400000",
            unitId = "UNIT_TOKEN"
        )
        assertTrue("replay returns the recorded row", replay is EntitlementService.ConsumptionOutcome.Replayed)

        val remaining = entitlements.remaining(recorded.entitlementId)
                as EntitlementService.Remaining.RemainingValue
        assertEquals(BigDecimal("600000"), remaining.value)

        try {
            db.openHelper.writableDatabase.execSQL("UPDATE entitlement_consumption SET consumption_status='X'")
            assertTrue("consumption UPDATE must be rejected", false)
        } catch (expected: android.database.SQLException) {
            // trigger fired — correct
        }
    }

    @Test
    fun `remaining semantics per value kind`() = runBlocking {
        // UNLIMITED is unlimited
        val unlimited = entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov-e", applicabilityScopeId = "sd-e",
                entitlementType = "TRIAL", valueKind = EntitlementService.VALUE_UNLIMITED
            )
        ) as EntitlementService.RecordOutcome.Recorded
        assertTrue(entitlements.remaining(unlimited.entitlementId) is EntitlementService.Remaining.Unlimited)

        // MONETARY_CREDIT: micro arithmetic
        val credit = entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov-e", applicabilityScopeId = "sd-e",
                entitlementType = "PROMOTIONAL",
                valueKind = EntitlementService.VALUE_MONETARY_CREDIT,
                monetaryLimitMicroUnits = 500_000L, currency = "USD"
            )
        ) as EntitlementService.RecordOutcome.Recorded
        entitlements.consume(
            entitlementId = credit.entitlementId,
            consumptionGranularity = EntitlementService.GRANULARITY_PERIOD_AGGREGATE,
            idempotencyKey = "oct",
            amountMicroUnits = 120_000L
        )
        val creditRemaining = entitlements.remaining(credit.entitlementId)
                as EntitlementService.Remaining.RemainingValue
        assertEquals(BigDecimal(380_000L), creditRemaining.value)
        assertEquals("USD", creditRemaining.unit)

        // PRICE_OVERRIDE carries no consumable magnitude
        val override = entitlements.recordEntitlement(
            EntitlementService.EntitlementSpec(
                providerId = "prov-e", applicabilityScopeId = "sd-e",
                entitlementType = "FREE_PRICING",
                valueKind = EntitlementService.VALUE_PRICE_OVERRIDE,
                priceOverrideMode = "ZERO_PRICE"
            )
        ) as EntitlementService.RecordOutcome.Recorded
        assertTrue(entitlements.remaining(override.entitlementId) is EntitlementService.Remaining.NotApplicable)

        // unknown id
        assertTrue(entitlements.remaining("ent-none") is EntitlementService.Remaining.NotFound)
    }
}
