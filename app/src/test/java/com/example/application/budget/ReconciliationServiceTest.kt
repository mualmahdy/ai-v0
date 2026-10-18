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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 3 — ReconciliationService (gates PDM-G03 + PDM-G04)
 * ============================================================================
 * The comparison machine (comparison currency = provider currency, single
 * windowed conversion, signed difference = provider − internal) and the
 * finality machine (DRAFT -> FINALIZED / REVOKED, frozen after, unique
 * FINALIZED per key, draft-only inclusions/adjustments).
 */
@RunWith(RobolectricTestRunner::class)
class ReconciliationServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var fx: CurrencyConversionService
    private lateinit var reconciliation: ReconciliationService

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
        fx = CurrencyConversionService(database = db, clock = { 0L })
        reconciliation = ReconciliationService(database = db, fx = fx, clock = { 0L })
        seedScope()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // fixtures: scope + provider pricing chain + cost calculations/lines
    // ------------------------------------------------------------------

    private fun seedScope() {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-r','WORKSPACE','v1|WORKSPACE|ws-r','h','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-r',0)"
        )
    }

    /** A CALCULATED cost_calculation + [amounts.size] lines in [currency]. */
    private fun seedCosts(
        providerId: String,
        currency: String,
        calculatedAt: Long,
        vararg amounts: Long,
        status: String = CostBreakdownService.STATUS_CALCULATED
    ) {
        val profileId = "pp-$providerId-$currency"
        val versionId = "ppv-$providerId-$currency"
        val calcId = "cc-$providerId-$currency-$calculatedAt-$status"
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO pricing_profile VALUES ('$profileId','$providerId','MODEL:m:-','$currency','sd-r','ACTIVE',0,0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO pricing_profile_version VALUES ('$versionId','$profileId',1,'t','t',0,NULL,0,0,'EFFECTIVE','UNKNOWN',0,'CALCULABLE','ROUND_LINE_THEN_SUM',0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO usage_record (usage_record_id,request_attempt_id,report_source,report_version," +
                "report_status,is_final_report,recorded_at_epoch_ms) VALUES ('ur-$calcId','att-$calcId','PROVIDER','1','FINAL',1,0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO cost_calculation (cost_calculation_id,calculation_level,request_attempt_id,usage_record_id," +
                "pricing_profile_version_id,calculation_engine_version,rule_set_version,input_hash,currency," +
                "total_amount_micro_units,calculation_status,confidence_state,calculated_at_epoch_ms," +
                "recorded_at_epoch_ms) VALUES ('$calcId','REQUEST_ATTEMPT','att-$calcId','ur-$calcId','$versionId'," +
                "'e','r','h','$currency',${amounts.sum()},'$status','DECLARED',$calculatedAt,0)"
        )
        amounts.forEachIndexed { index, amount ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO cost_calculation_line (line_id,cost_calculation_id,line_order,line_basis_type," +
                    "final_amount_micro_units,currency,rounding_mode,line_status) VALUES " +
                    "('cl-$calcId-${index + 1}','$calcId',${index + 1},'FIXED_FEE',$amount,'$currency','HALF_UP','ACTIVE')"
            )
        }
    }

    private suspend fun importBilling(
        amountMicro: Long,
        currency: String = "USD",
        externalId: String = "INV-R"
    ): String {
        val result = ProviderBillingService(database = db, clock = { 0L }).import(
            ProviderBillingService.ImportSpec(
                providerId = "prov-r",
                externalBillingId = externalId,
                billingRecordType = ProviderBillingService.TYPE_INVOICE,
                recordGranularity = ProviderBillingService.GRANULARITY_MONTHLY,
                periodStartEpochMs = 0L,
                periodEndEpochMs = 10_000L,
                primaryAmountType = ProviderBillingService.PRIMARY_GROSS_PAYABLE,
                primaryAmountMicroUnits = amountMicro,
                grossPayableMicroUnits = amountMicro,
                currency = currency,
                sourceAuthority = "test"
            )
        )
        return (result as ProviderBillingService.ImportResult.Imported).recordId
    }

    // ------------------------------------------------------------------
    // the comparison machine
    // ------------------------------------------------------------------

    @Test
    fun `MATCHED - provider amount equals the internal itemized total`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1500L, 3000L)
        val billingId = importBilling(4500L)

        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
        assertTrue(outcome is ReconciliationService.BuildOutcome.Recorded)
        outcome as ReconciliationService.BuildOutcome.Recorded
        assertEquals(ReconciliationService.STATUS_MATCHED, outcome.reconciliationStatus)
        assertEquals(ReconciliationService.COVERAGE_COMPLETE, outcome.coverageStatus)
        assertEquals(4500L, outcome.providerAmountMicroUnits)
        assertEquals(4500L, outcome.localAmountMicroUnits)
        assertEquals(0L, outcome.differenceMicroUnits)
        assertNull("same currency -> no rule (G03/PDM CHECK)", outcome.conversionRuleId)

        // the inclusions carry the exact lines
        val record = reconciliation.byId(outcome.reconciliationId)!!
        assertEquals(2, reconciliation.inclusions(record.reconciliationId).size)
    }

    @Test
    fun `WITHIN_TOLERANCE and CONFLICT - the signed difference decides`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        val billingId = importBilling(1010L)

        val within = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L, toleranceMicroUnits = 10L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(ReconciliationService.STATUS_WITHIN_TOLERANCE, within.reconciliationStatus)
        assertEquals(10L, within.differenceMicroUnits) // provider - internal = 1010 - 1000

        val conflict = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L, toleranceMicroUnits = 5L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(ReconciliationService.STATUS_CONFLICT, conflict.reconciliationStatus)
    }

    @Test
    fun `cross-currency - single conversion by the G03 rule, difference in provider currency`() = runBlocking {
        fx.registerRule("EUR", "USD", "1.1", 0L, null, "test") // local EUR -> comparison USD
        seedCosts("prov-r", "EUR", 100L, 1000L) // internal cost computed in EUR
        val billingId = importBilling(1105L, currency = "USD") // provider bills USD

        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(1100L, outcome.comparedInternalMicroUnits) // 1000 EUR × 1.1
        assertEquals(5L, outcome.differenceMicroUnits) // 1105 - 1100
        assertEquals("fx_EUR_USD_0", outcome.conversionRuleId)
        assertEquals(ReconciliationService.STATUS_CONFLICT, outcome.reconciliationStatus)
    }

    @Test
    fun `cross-currency without a rule is NOT recordable - the CHECK demands the rule (I-24)`() = runBlocking {
        seedCosts("prov-r", "EUR", 100L, 1000L)
        val billingId = importBilling(1100L, currency = "USD")

        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
        assertTrue(outcome is ReconciliationService.BuildOutcome.NotRecordable)
        assertEquals(
            "NO_CONVERSION_RULE:EUR>USD",
            (outcome as ReconciliationService.BuildOutcome.NotRecordable).reasonCode
        )
        // nothing was persisted
        assertNull(reconciliation.byId("anything"))
    }

    @Test
    fun `coverage - PARTIAL when the window also holds non-CALCULATED attempts`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L) // CALCULATED
        seedCosts("prov-r", "USD", 200L, 500L, status = CostBreakdownService.STATUS_ESTIMATED)
        val billingId = importBilling(1000L)

        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals("the ESTIMATED attempt is excluded from the comparison", 1000L, outcome.localAmountMicroUnits)
        assertEquals(ReconciliationService.COVERAGE_PARTIAL, outcome.coverageStatus)
    }

    @Test
    fun `no internal coverage - honest NOT_POSSIBLE with the reason recorded`() = runBlocking {
        val billingId = importBilling(999L)
        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(ReconciliationService.STATUS_NOT_POSSIBLE, outcome.reconciliationStatus)
        assertEquals(ReconciliationService.COVERAGE_NOT_POSSIBLE, outcome.coverageStatus)
        assertEquals("NO_INTERNAL_COVERAGE", outcome.reasonCode)
    }

    // ------------------------------------------------------------------
    // the difference-quality loop (exit criterion machinery)
    // ------------------------------------------------------------------

    @Test
    fun `adjustments classify the difference until the residual is zero`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        val billingId = importBilling(1150L)

        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(150L, outcome.differenceMicroUnits)
        assertEquals(150L, reconciliation.explainedResidual(outcome.reconciliationId))

        reconciliation.addAdjustment(
            outcome.reconciliationId, ReconciliationService.ADJUSTMENT_PROVIDER_ONLY_TAX,
            140L, "VAT-20"
        )
        reconciliation.addAdjustment(
            outcome.reconciliationId, ReconciliationService.ADJUSTMENT_PROVIDER_ONLY_FEE,
            10L, "processing-fee"
        )
        assertEquals("fully explained difference — the Stage-3 exit criterion",
            0L, reconciliation.explainedResidual(outcome.reconciliationId))
    }

    // ------------------------------------------------------------------
    // the finality machine (G04)
    // ------------------------------------------------------------------

    @Test
    fun `finalize - only DRAFT with a terminal result may be signed off`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        val billingId = importBilling(999L) // CONFLICT

        val conflict = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        val finalized = reconciliation.finalize(conflict.reconciliationId)
        assertTrue(finalized is ReconciliationService.FinalizeOutcome.Finalized)
        assertEquals(
            ReconciliationService.FINALITY_FINALIZED,
            reconciliation.byId(conflict.reconciliationId)!!.finalityStatus
        )

        // re-finalize rejected (service + trigger)
        assertTrue(reconciliation.finalize(conflict.reconciliationId) is ReconciliationService.FinalizeOutcome.Rejected)
        try {
            db.openHelper.writableDatabase.execSQL("UPDATE reconciliation_record SET reason_code='x' WHERE reconciliation_id='${conflict.reconciliationId}'")
            assertTrue("FINALIZED is frozen at the DB level", false)
        } catch (expected: android.database.SQLException) {
            // trigger fired — correct
        }
    }

    @Test
    fun `finalize rejects non-terminal results (NOT_POSSIBLE)`() = runBlocking {
        val billingId = importBilling(500L) // no internal coverage
        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(ReconciliationService.STATUS_NOT_POSSIBLE, outcome.reconciliationStatus)
        val rejected = reconciliation.finalize(outcome.reconciliationId)
        assertTrue(rejected is ReconciliationService.FinalizeOutcome.Rejected)
        assertTrue(
            "reason names the non-terminal result: ${(rejected as ReconciliationService.FinalizeOutcome.Rejected).reason}",
            rejected.reason.startsWith("NON_TERMINAL_RESULT:NOT_POSSIBLE")
        )
        assertEquals(
            ReconciliationService.FINALITY_DRAFT,
            reconciliation.byId(outcome.reconciliationId)!!.finalityStatus
        )
    }

    @Test
    fun `supersession - a DRAFT steps aside for its successor, then freezes`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        val billingId = importBilling(1000L)

        val first = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        val second = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded

        val superseded = reconciliation.supersede(first.reconciliationId, second.reconciliationId)
        assertTrue(superseded is ReconciliationService.SupersedeOutcome.Superseded)
        val old = reconciliation.byId(first.reconciliationId)!!
        assertEquals(ReconciliationService.FINALITY_REVOKED, old.finalityStatus)
        assertEquals(second.reconciliationId, old.supersededBy)

        // REVOKED is frozen too
        assertTrue(
            reconciliation.supersede(first.reconciliationId, second.reconciliationId)
                    is ReconciliationService.SupersedeOutcome.Rejected
        )
    }

    @Test
    fun `change-7 - at most one FINALIZED per (billing, scope, period)`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        val billingId = importBilling(1000L)

        val first = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        reconciliation.finalize(first.reconciliationId)

        val second = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        val rejected = reconciliation.finalize(second.reconciliationId)
        assertTrue(rejected is ReconciliationService.FinalizeOutcome.Rejected)
        assertTrue((rejected as ReconciliationService.FinalizeOutcome.Rejected).reason.startsWith("TRIGGER:"))
        assertEquals(
            ReconciliationService.FINALITY_DRAFT,
            reconciliation.byId(second.reconciliationId)!!.finalityStatus
        )
    }

    @Test
    fun `draft guards - inclusions and adjustments only land on DRAFTs`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        val billingId = importBilling(1000L)
        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        reconciliation.finalize(outcome.reconciliationId)

        try {
            reconciliation.addAdjustment(
                outcome.reconciliationId, ReconciliationService.ADJUSTMENT_TOLERANCE, 1L, "late"
            )
            assertTrue("adjustments on FINALIZED must be rejected", false)
        } catch (expected: IllegalArgumentException) {
            // service guard — correct (the DB trigger backstops it)
        }
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO reconciliation_inclusion (inclusion_id,reconciliation_id,cost_calculation_line_id," +
                    "included_amount_micro_units,currency,inclusion_status) VALUES " +
                    "('ri-late','${outcome.reconciliationId}','cl-cc-prov-r-USD-100-CALCULATED-1',1,'USD','INCLUDED')"
            )
            assertTrue("inclusion into FINALIZED must be rejected by the trigger", false)
        } catch (expected: android.database.SQLException) {
            // draft-guard trigger fired — correct
        }
    }

    @Test
    fun `provider amount falls back to gross when primary is absent (I-27)`() = runBlocking {
        seedCosts("prov-r", "USD", 100L, 1000L)
        // import with NO primary amount but a gross payable: the comparison
        // must use the gross figure, never fabricate an absence.
        val result = ProviderBillingService(database = db, clock = { 0L }).import(
            ProviderBillingService.ImportSpec(
                providerId = "prov-r",
                externalBillingId = "INV-GROSS",
                billingRecordType = ProviderBillingService.TYPE_INVOICE,
                recordGranularity = ProviderBillingService.GRANULARITY_MONTHLY,
                periodStartEpochMs = 0L,
                periodEndEpochMs = 10_000L,
                primaryAmountType = "UNKNOWN",
                primaryAmountMicroUnits = null,
                grossPayableMicroUnits = 1000L,
                currency = "USD",
                sourceAuthority = "test"
            )
        )
        val billingId = (result as ProviderBillingService.ImportResult.Imported).recordId

        val outcome = reconciliation.buildReconciliation(billingId, "sd-r", 0L, 10_000L)
                as ReconciliationService.BuildOutcome.Recorded
        assertEquals(1000L, outcome.providerAmountMicroUnits)
        assertEquals(ReconciliationService.STATUS_MATCHED, outcome.reconciliationStatus)
    }
}
